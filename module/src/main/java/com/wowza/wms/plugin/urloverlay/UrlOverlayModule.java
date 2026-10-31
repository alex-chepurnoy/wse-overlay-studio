/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.Level;

import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.mediacaster.MediaCasterItem;
import com.wowza.wms.mediacaster.MediaCasterStreamId;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.plugin.urloverlay.rest.OverlayConfig;
import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.vhosts.VHostsConfig;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoder;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoderNotify;
import com.wowza.wms.transcoder.model.LiveStreamTranscoder;
import com.wowza.wms.transcoder.model.LiveStreamTranscoderActionNotifyBase;
import com.wowza.wms.transcoder.model.TranscoderSession;
import com.wowza.wms.transcoder.model.TranscoderSessionVideo;
import com.wowza.wms.transcoder.model.TranscoderSessionVideoEncode;
import com.wowza.wms.transcoder.model.TranscoderStream;
import com.wowza.wms.transcoder.model.TranscoderStreamDestination;
import com.wowza.wms.transcoder.model.TranscoderStreamDestinationVideo;
import com.wowza.wms.transcoder.model.TranscoderStreamSourceVideo;
import com.wowza.wms.transcoder.model.TranscoderVideoDecoderNotifyBase;
import com.wowza.wms.transcoder.model.TranscoderVideoFrameSizeHolder;
import com.wowza.wms.transcoder.model.TranscoderVideoOverlayFrame;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

public class UrlOverlayModule extends ModuleBase
{
	private static final String MODULE_NAME = "UrlOverlayModule";
	IApplicationInstance appInstance = null;
	private String appName;
	private WMSLogger logger = null;
	static String  logPrefix = "UrlOverlayModule:";


	OverlayLiveStreamTranscoderNotifier liveStreamTranscoderNotifier = null;
	// read on every frame of every transcoder thread: a concurrent map, with no lock and no allocation once a stream has its entry
	private final java.util.concurrent.ConcurrentHashMap<String, StreamInfo> streamInfos = new java.util.concurrent.ConcurrentHashMap<>();

	private boolean debugOverlayLogs = false;
	private int maxDebugOverlayLogs = 100;
	// overlay slot used when an overlay's own overlayIndex is 0; UrlOverlayIndex in Application.xml
	private int appOverlayIndex = OverlayConfig.DEFAULT_OVERLAY_INDEX;
	// kept apart from StreamInfo, which is removed when the transcoder stops, so status can still say "stopped"
	private final java.util.Map<String, String> transcoderStates = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, List<EncoderInfo>> encodersByStream = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, int[]> decoderSizes = new java.util.concurrent.ConcurrentHashMap<>();
	// per stream, from its transcoder template when the transcoder started: the template WSE loaded, whether it is the
	// stream's own (<stream>.xml), the overlay it names and the encodes that carry it
	private final java.util.Map<String, java.util.Map<String, Object>> streamTemplates = new java.util.concurrent.ConcurrentHashMap<>();
	// the template file each stream's transcoder loaded, and what its outputs were then (structure(), not the overlay)
	private final java.util.Map<String, File> streamTemplateFiles = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, String> streamStructure = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, String> streamOverlayNames = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, List<String>> streamOverlayEncodes = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> missingOverlays = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// running modules, so an edit to a named overlay reaches every stream using it
	private static final java.util.Set<UrlOverlayModule> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private int debugOverlayLogsCnt=0;

	private boolean initTransparentOverlay =false;

	/** Everything the status endpoint reports for one stream. */
	public java.util.Map<String, Object> streamStatus(String streamName)
	{
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("stream", streamName);
		OverlayImage overlay = getOverlayImage(streamName);
		m.put("configured", overlay != null);
		m.put("overlayIndex", overlay == null ? null : (overlay.getOverlayIndex() > 0 ? overlay.getOverlayIndex() : appOverlayIndex));

		java.util.Map<String, Object> transcoder = new java.util.LinkedHashMap<>();
		transcoder.put("state", transcoderStates.getOrDefault(streamName, "not-started"));
		int[] size = decoderSizes.get(streamName);
		transcoder.put("decoderWidth", size == null ? null : size[0]);
		transcoder.put("decoderHeight", size == null ? null : size[1]);
		List<java.util.Map<String, Object>> encodes = new ArrayList<>();
		List<EncoderInfo> encoders = encodersByStream.get(streamName);
		if(encoders != null)
		{
			for(EncoderInfo e: encoders)
			{
				java.util.Map<String, Object> em = new java.util.LinkedHashMap<>();
				em.put("name", e.encodeName);
				em.put("outputStream", e.streamName);
				em.put("width", e.getFrameWidth());
				em.put("height", e.getFrameHeight());
				em.put("passThrough", e.destinationVideo.isPassThrough());
				boolean carries = overlay != null && !e.destinationVideo.isPassThrough()
						&& (overlay.getOverlaySource() || overlay.overlayEncode(e.encodeName, e.streamName));
				em.put("overlay", carries);
				encodes.add(em);
			}
		}
		transcoder.put("encodes", encodes);
		m.put("transcoder", transcoder);
		java.util.Map<String, Object> tpl = streamTemplates.get(streamName);
		if(tpl != null)
		{
			tpl = new java.util.LinkedHashMap<>(tpl);
			tpl.put("outputsChanged", outputsChanged(streamName));
		}
		m.put("template", tpl);
		java.util.Map<String, Object> from = new java.util.LinkedHashMap<>();
		String named = streamOverlayNames.get(streamName);
		from.put("source", named != null ? "template" : (overlay != null ? "stream" : null));
		from.put("name", named);
		from.put("missing", named != null && missingOverlays.contains(streamName));
		m.put("overlayFrom", from);

		m.put("draw", overlay == null ? null : overlay.drawStats());
		List<java.util.Map<String, Object>> objects = new ArrayList<>();
		if(overlay != null && overlay.getOverlayConfig() != null && overlay.getOverlayConfig().getObjects() != null)
		{
			for(com.wowza.wms.plugin.urloverlay.rest.ObjectConfig o: overlay.getOverlayConfig().getObjects())
			{
				java.util.Map<String, Object> om = new java.util.LinkedHashMap<>();
				om.put("id", o.getId());
				om.put("type", o.getType());
				if("webPage".equals(o.getType()))
				{
					om.put("capture", o.getCaptureStatus());
				}
				objects.add(om);
			}
		}
		m.put("objects", objects);
		return m;
	}

	/** @return the overlay's object with this id, or null */
	public com.wowza.wms.plugin.urloverlay.rest.ObjectConfig findObject(String streamName, String id)
	{
		OverlayImage overlay = getOverlayImage(streamName);
		if(overlay == null || overlay.getOverlayConfig() == null)
		{
			return null;
		}
		return overlay.getOverlayConfig().getObject(id);
	}

	/** Encode name and output stream name of each encode running now, for the preview endpoint. */
	public List<String[]> encodeOutputs(String streamName)
	{
		List<String[]> out = new ArrayList<>();
		List<EncoderInfo> encoders = encodersByStream.get(streamName);
		if(encoders != null)
		{
			for(EncoderInfo e: encoders)
			{
				out.add(new String[] { e.encodeName, e.streamName });
			}
		}
		return out;
	}

	public com.wowza.wms.application.IApplicationInstance getAppInstance()
	{
		return appInstance;
	}

	public class StreamInfo
	{
		// volatile: written on REST/receive threads (setStreamWidth/Height, setQueueDrawing,
		// addOverlayImageQueue) and read on the transcoder thread (onBeforeScaleFrame). A ConcurrentHashMap
		// get() only publishes the put that inserted this StreamInfo, not these later field writes, so
		// without volatile the transcoder can read stale values (e.g. queueDrawings stuck false).
		private volatile int streamWidth = 0;
		private volatile int streamHeight = 0;
		// Actual decoded source video size, only written by the transcoder (never by REST w/h) so the
		// overlay canvas size can be validated against it.
		private volatile int sourceWidth = 0;
		private volatile int sourceHeight = 0;
		// Last crop warning emitted, used to avoid logging on every refresh of the same overlay.
		private volatile String lastCropWarning = null;
		private OverlayImage streamOverlayImage = null;
		private final Queue<OverlayImage> overlayImageQueue = new LinkedList<>();;
		private volatile boolean queueDrawings = false;
		private OverlayImage lastQueuedOverlayImage = null;
		private final ArrayList<IOverlayListener> overlayListeners = new ArrayList<>();
		private int lastOverlayIndex = OverlayConfig.DEFAULT_OVERLAY_INDEX;
		private UUID lastImageUuid = new UUID(0L,0L);
		private final HashMap<String, Boolean> overlayOnEncode = new HashMap<>();
		private boolean overlayOnSource = false;
		private boolean overlayOnEncodes =false;		
	}

    public UrlOverlayModule() {
    }

	public void onAppStart(IApplicationInstance appInstance)
	{
		this.appInstance = appInstance;
		this.appName = appInstance.getApplication().getName();
		this.logger = getLogger();
		logger.info(logPrefix + "onAppStart:" + ReleaseInfo.getVersion());
		this.appInstance = appInstance;

		this.liveStreamTranscoderNotifier = new OverlayLiveStreamTranscoderNotifier();
		this.appInstance.addLiveStreamTranscoderListener(liveStreamTranscoderNotifier);
		debugOverlayLogs = this.appInstance.getProperties().getPropertyBoolean("UrlOverlayDebugLogs",debugOverlayLogs);
		initTransparentOverlay = this.appInstance.getProperties().getPropertyBoolean("UrlOverlayInitTransparentOverlay",
				initTransparentOverlay);
		maxDebugOverlayLogs = this.appInstance.getProperties().getPropertyInt("UrlOverlayMaxDebugOverlayLogs",maxDebugOverlayLogs);
		appOverlayIndex = this.appInstance.getProperties().getPropertyInt("UrlOverlayIndex", appOverlayIndex);
		if(appOverlayIndex <= 0)
		{
			appOverlayIndex = OverlayConfig.DEFAULT_OVERLAY_INDEX;
		}
		RUNNING.add(this);
	}

	/** The named overlays folder of this module's application. */
	private java.nio.file.Path overlayDir()
	{
		return com.wowza.wms.plugin.urloverlay.templates.OverlayStore.dir(java.nio.file.Paths.get(VHostsConfig.getVHostDir(appInstance.getVHost().getName())), appName);
	}

	/**
	 * Applies the named overlay this stream's template names, from its file, replacing what the stream shows. The
	 * template decides which encodes carry it: none listed means drawn on the source, so every encode gets it.
	 * @return false when the stream's template names no overlay
	 */
	private boolean applyNamedOverlay(String streamName)
	{
		String name = streamOverlayNames.get(streamName);
		if(name == null)
		{
			return false;
		}
		try
		{
			File f = com.wowza.wms.plugin.urloverlay.templates.OverlayStore.file(overlayDir(), name).toFile();
			if(!f.isFile())
			{
				if(missingOverlays.add(streamName))
				{
					logger.warn(logPrefix + "stream " + streamName + ": its template names overlay '" + name + "', which does not exist");
				}
				clearOverlayImage(streamName);
				return true;
			}
			missingOverlays.remove(streamName);
			OverlayConfig cfg = OverlayConfig.loadStreamFile(f, appInstance.getVHost().getName(), appName, appInstance.getName(), streamName);
			cfg.setNamedOverlay(name);
			List<String> enc = streamOverlayEncodes.getOrDefault(streamName, List.of());
			cfg.setOverlaySource(enc.isEmpty());
			cfg.setOverlayEncodes(new ArrayList<>(enc));
			cfg.saveNewObject();
			logger.info(logPrefix + "stream " + streamName + ": overlay '" + name + "' from its template, " + (enc.isEmpty() ? "on every encode" : "on " + enc));
		}
		catch(Exception e)
		{
			logger.error(logPrefix + "stream " + streamName + ": could not apply overlay '" + name + "': " + e);
		}
		return true;
	}

	/**
	 * After a named overlay is saved or deleted: re-applies it to every running stream of this application whose
	 * template names it.
	 * @return the streams updated
	 */
	public static List<String> reapplyNamedOverlay(String vhostName, String appName, String name)
	{
		List<String> out = new ArrayList<>();
		for(UrlOverlayModule mod: RUNNING)
		{
			IApplicationInstance ai = mod.appInstance;
			if(ai == null || !appName.equals(mod.appName) || !vhostName.equals(ai.getVHost().getName()))
			{
				continue;
			}
			for(java.util.Map.Entry<String, String> e: mod.streamOverlayNames.entrySet())
			{
				if(name.equals(e.getValue()) && "running".equals(mod.transcoderStates.get(e.getKey())))
				{
					mod.applyNamedOverlay(e.getKey());
					out.add(e.getKey());
				}
			}
		}
		return out;
	}

	/**
	 * What a template file makes the transcoder build, without the overlay it names: the validator's reading less the
	 * overlay properties and the description. WSE builds a stream's transcoder once (LiveReceiver), so when this differs
	 * from what the stream started with, its outputs change only when the stream restarts.
	 */
	static String structure(File f)
	{
		try(java.io.InputStream in = new java.io.FileInputStream(f))
		{
			java.util.Map<String, Object> m = new java.util.TreeMap<>(com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.validate(in).toMap());
			for(String k: new String[] { "overlay", "overlayEncodes", "description", "problems", "usable", "managed" })
			{
				m.remove(k);
			}
			return m.toString();
		}
		catch(Exception e)
		{
			return "unreadable";
		}
	}

	/** True when the template file this stream's transcoder loaded now builds different outputs than it did then. */
	public boolean outputsChanged(String streamName)
	{
		File f = streamTemplateFiles.get(streamName);
		String then = streamStructure.get(streamName);
		if(f == null || then == null)
		{
			return false;
		}
		// a stream that now has its own copy (step 3), or whose list now picks another file, loads that one on restart
		java.util.Map<String, Object> t = streamTemplates.get(streamName);
		File dir = f.getParentFile();
		String now = t == null ? null : com.wowza.wms.plugin.urloverlay.templates.TemplateLists.resolve((String)t.get("list"), streamName,
				n -> new File(dir, n).isFile());
		if(now != null && !now.equalsIgnoreCase(f.getName()))
		{
			return true;
		}
		return !then.equals(structure(f));
	}

	/**
	 * After a template file is written: every running stream of this virtual host whose transcoder loaded that file
	 * takes the overlay it now names, and on which outputs, at once. The module draws through the transcoder's dynamic
	 * overlays on every output it registered at start, so this needs no restart; outputs need one (outputsChanged).
	 * @return per stream: application, stream, the overlay now shown, and whether its outputs wait for a restart
	 */
	public static List<java.util.Map<String, Object>> reloadTemplateFile(String vhostName, java.nio.file.Path file)
	{
		List<java.util.Map<String, Object>> out = new ArrayList<>();
		File target = file.toAbsolutePath().normalize().toFile();
		for(UrlOverlayModule mod: RUNNING)
		{
			IApplicationInstance ai = mod.appInstance;
			if(ai == null || !vhostName.equals(ai.getVHost().getName()))
			{
				continue;
			}
			for(java.util.Map.Entry<String, File> e: mod.streamTemplateFiles.entrySet())
			{
				String stream = e.getKey();
				if(!target.equals(e.getValue().getAbsoluteFile().toPath().normalize().toFile()) || !"running".equals(mod.transcoderStates.get(stream)))
				{
					continue;
				}
				String name = null;
				List<String> encs = new ArrayList<>();
				try(java.io.InputStream in = new java.io.FileInputStream(target))
				{
					com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.Result r = com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.validate(in);
					name = r.overlay();
					encs.addAll(r.overlayEncodes());
				}
				catch(Exception ex)
				{
					mod.logger.warn(mod.logPrefix + "stream " + stream + ": could not read " + target + " after it changed: " + ex);
					continue;
				}
				if(name != null)
				{
					mod.streamOverlayNames.put(stream, name);
					mod.streamOverlayEncodes.put(stream, encs);
					mod.applyNamedOverlay(stream);
				}
				else
				{
					mod.streamOverlayNames.remove(stream);
					mod.streamOverlayEncodes.remove(stream);
					mod.clearOverlayImage(stream);
					mod.logger.info(mod.logPrefix + "stream " + stream + ": its template now names no overlay; cleared");
				}
				java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
				m.put("application", mod.appName);
				m.put("stream", stream);
				m.put("overlay", name);
				m.put("outputsChanged", mod.outputsChanged(stream));
				out.add(m);
			}
		}
		return out;
	}

	/**
	 * Restarts a stream so its transcoder is built again from its template. A stream WSE pulls is reset (as WSE REST's
	 * resetStream does); a pushed stream's publisher is disconnected (an RTMP client, or an RTP session for RTSP and
	 * WebRTC), and its encoder publishes again with the new template if it reconnects by itself. WSE has no call that
	 * rebuilds a pushed stream's transcoder under a connected publisher (LiveReceiver.createLiveStreamTranscoders).
	 * @return how it was restarted: "reset", "rtmp" or "rtp"
	 */
	public static String restartStream(String vhostName, String appName, String instance, String streamName) throws com.wowza.wms.plugin.urloverlay.templates.TemplateFiles.Refused
	{
		for(UrlOverlayModule mod: RUNNING)
		{
			IApplicationInstance ai = mod.appInstance;
			if(ai == null || !appName.equals(mod.appName) || !vhostName.equals(ai.getVHost().getName()) || !ai.getName().equals(instance))
			{
				continue;
			}
			if(ai.getMediaCasterStreams() != null && ai.getMediaCasterStreams().getMediaCaster(streamName) != null)
			{
				ai.getMediaCasterStreams().getMediaCaster(streamName).getMediaCaster().forceReset();
				mod.logger.info(mod.logPrefix + "stream " + streamName + ": reset, to build its transcoder again");
				return "reset";
			}
			IMediaStream ms = ai.getStreams().getStream(streamName);
			if(ms == null)
			{
				throw new com.wowza.wms.plugin.urloverlay.templates.TemplateFiles.Refused(404, streamName + " is not publishing.");
			}
			if(ms.getClient() != null)
			{
				ms.getClient().setShutdownClient(true);
				mod.logger.info(mod.logPrefix + "stream " + streamName + ": disconnected its RTMP publisher, to build its transcoder again");
				return "rtmp";
			}
			if(ms.getRTPStream() != null && ms.getRTPStream().getSession() != null)
			{
				ai.getVHost().getRTPContext().shutdownRTPSession(ms.getRTPStream().getSession());
				mod.logger.info(mod.logPrefix + "stream " + streamName + ": disconnected its RTP publisher, to build its transcoder again");
				return "rtp";
			}
			throw new com.wowza.wms.plugin.urloverlay.templates.TemplateFiles.Refused(409, streamName + " arrives in a way this cannot restart; stop and start it at its source.");
		}
		throw new com.wowza.wms.plugin.urloverlay.templates.TemplateFiles.Refused(404, "URL Overlay is not running in " + appName + "/" + instance + ".");
	}

	/** Whether this application's running stream has outputs waiting for a restart (AppStreamsConfig). */
	public static boolean outputsChanged(String vhostName, String appName, String streamName)
	{
		for(UrlOverlayModule mod: RUNNING)
		{
			IApplicationInstance ai = mod.appInstance;
			if(ai != null && appName.equals(mod.appName) && vhostName.equals(ai.getVHost().getName()) && mod.outputsChanged(streamName))
			{
				return true;
			}
		}
		return false;
	}

	/** Running streams of this application whose template names this overlay. */
	public static List<String> streamsShowing(String vhostName, String appName, String name)
	{
		List<String> out = new ArrayList<>();
		for(UrlOverlayModule mod: RUNNING)
		{
			IApplicationInstance ai = mod.appInstance;
			if(ai == null || !appName.equals(mod.appName) || !vhostName.equals(ai.getVHost().getName()))
			{
				continue;
			}
			for(java.util.Map.Entry<String, String> e: mod.streamOverlayNames.entrySet())
			{
				if(name.equals(e.getValue()) && "running".equals(mod.transcoderStates.get(e.getKey())))
				{
					out.add(e.getKey());
				}
			}
		}
		java.util.Collections.sort(out);
		return out;
	}

	public void onAppStop(IApplicationInstance appInstance)
	{
		logger.info(logPrefix + "onAppStop");
		RUNNING.remove(this);

		if (this.liveStreamTranscoderNotifier != null)
		{
			this.appInstance.removeLiveStreamTranscoderListener(liveStreamTranscoderNotifier);
			this.liveStreamTranscoderNotifier = null;
		}
		appInstance = null;

		logger.info(logPrefix + "onAppStopped");
	}

	public static UrlOverlayModule getModule(String vHostName, String appName, String instanceName, String streamName)
	{
		WMSLogger logger = WMSLoggerFactory.getLogger(null);
		return getModule(vHostName, appName, instanceName, streamName, why -> logger.warn(logPrefix + why));
	}

	/**
	 * The module on a stream's application instance, or null with the reason given to why. Streams of the HTTP
	 * packetizers and repeaters have no overlay, and null is returned for them without a reason.
	 */
	public static UrlOverlayModule getModule(String vHostName, String appName, String instanceName, String streamName, java.util.function.Consumer<String> why)
	{
		MediaCasterStreamId id = MediaCasterItem.parseIdString(streamName);
		if (id.getLiveStreamPacketizer() != null || id.getLiveStreamRepeater() != null)
			return null;
		IVHost iVhost = (IVHost)VHostSingleton.getInstance(vHostName);
		if (iVhost == null)
		{
			why.accept("Could not find VHost:" + vHostName);
			return null;
		}
		IApplication iApp = iVhost.getApplication(appName);
		if (iApp == null)
		{
			why.accept("Could not find Application:" + appName);
			return null;
		}
		IApplicationInstance appInstance = iApp.getAppInstance(instanceName);
		if (appInstance == null)
		{
			why.accept("Could not find Instance:" + instanceName);
			return null;
		}
		UrlOverlayModule ol = (UrlOverlayModule)appInstance.getModuleInstance(MODULE_NAME);
		if(ol == null)
		{
			why.accept("Could not find Module with name: " + MODULE_NAME);
		}
		return ol;
	}

	private StreamInfo getStreamInfo(String streamName)
	{
		StreamInfo s = streamInfos.get(streamName);
		return s != null ? s : streamInfos.computeIfAbsent(streamName, k -> new StreamInfo());
	}

	// overlay change lines, up to UrlOverlayMaxDebugOverlayLogs, when UrlOverlayDebugLogs is on
	private void debugLog(String msg)
	{
		if(debugOverlayLogs && debugOverlayLogsCnt < maxDebugOverlayLogs)
		{
			debugOverlayLogsCnt++;
			logger.info(msg);
		}
	}

	private StreamInfo findStreamInfo(String streamName)
	{
		return streamInfos.get(streamName);
	}

	private StreamInfo removeStreamInfo(String streamName)
	{
		return streamInfos.remove(streamName);
	}

	public void addListener(String streamName, IOverlayListener listener)
	{
		synchronized(getStreamInfo(streamName).overlayListeners)
		{
			getStreamInfo(streamName).overlayListeners.add(listener);
		}
	}

	public void removeListener(String streamName, IOverlayListener listener)
	{
		synchronized(getStreamInfo(streamName).overlayListeners)
		{
			getStreamInfo(streamName).overlayListeners.remove(listener);
		}
	}

	private void sendToListeners(String streamName, Level level, String msg)
	{
		StreamInfo streamInfo = getStreamInfo(streamName);
		synchronized(streamInfo.overlayListeners)
		{
			for(IOverlayListener listener: streamInfo.overlayListeners)
			{
				listener.onOverlayMessage(streamName, level, msg);
			}
		}
	}

	public int getStreamWidth(String streamName)
	{
		return getStreamInfo(streamName).streamWidth;
	}

	public void setStreamWidth(String streamName, int w)
	{
		getStreamInfo(streamName).streamWidth = w;
	}

	public void setStreamHeight(String streamName, int h)
	{
		getStreamInfo(streamName).streamHeight = h;
	}

	public int getStreamHeight(String streamName)
	{
		return getStreamInfo(streamName).streamHeight;
	}

	/**
	 * Warns (log + listeners) if the overlay canvas, at its position, extends past the source video.
	 * Does nothing when the source size is not known yet; the transcoder notifier re-checks once it is.
	 *
	 * @return true if the overlay fits (or the source size is unknown), false if it will be cropped
	 */
	public boolean checkOverlayFitsSource(String streamName, OverlayImage overlayImage)
	{
		if(overlayImage == null)
		{
			return true;
		}
		StreamInfo streamInfo = getStreamInfo(streamName);
		int srcW = streamInfo.sourceWidth;
		int srcH = streamInfo.sourceHeight;
		if(srcW <= 0 || srcH <= 0)
		{
			return true;
		}
		int w = overlayImage.getWidth(1.0);
		int h = overlayImage.getHeight(1.0);
		int x = overlayImage.getxPos(1.0);
		int y = overlayImage.getyPos(1.0);
		if(w <= 0 || h <= 0)
		{
			return true;
		}
		if(x < 0 || y < 0 || x + w > srcW || y + h > srcH)
		{
			String msg = logPrefix + "Overlay " + w + "x" + h + " at (" + x + "," + y + ") exceeds source video " + srcW + "x" + srcH + ", it will be cropped to fit. Stream:" + appName + "/" + streamName;
			logger.warn(msg);
			sendToListeners(streamName, Level.WARN, msg);
			return false;
		}
		return true;
	}

	/**
	 * Builds an overlay frame that fits inside a frameWidth x frameHeight video frame. If the overlay at
	 * (dstX,dstY) extends past the frame, the ABGR buffer is cropped to the visible region so the
	 * transcoder still draws it instead of silently dropping it. Cropping only happens when the image
	 * changes, not per video frame.
	 *
	 * @return the frame to add, or null if nothing of the overlay is visible
	 */
	private TranscoderVideoOverlayFrame createOverlayFrame(String streamName, String target, int frameWidth, int frameHeight,
			int width, int height, int dstX, int dstY, byte[] buffer)
	{
		if(frameWidth <= 0 || frameHeight <= 0)
		{
			// frame size unknown, nothing to crop against
			TranscoderVideoOverlayFrame overlayFrame = new TranscoderVideoOverlayFrame(width, height, buffer);
			overlayFrame.setDstX(dstX);
			overlayFrame.setDstY(dstY);
			return overlayFrame;
		}

		OverlayCrop.Region visible = OverlayCrop.visible(frameWidth, frameHeight, width, height, dstX, dstY);

		StreamInfo streamInfo = getStreamInfo(streamName);
		if(visible == null)
		{
			String msg = logPrefix + target + " overlay " + width + "x" + height + " at (" + dstX + "," + dstY + ") is completely outside the " + frameWidth + "x" + frameHeight + " frame, not drawing. Stream:" + appName + "/" + streamName;
			if(!msg.equals(streamInfo.lastCropWarning))
			{
				streamInfo.lastCropWarning = msg;
				logger.warn(msg);
				sendToListeners(streamName, Level.WARN, msg);
			}
			return null;
		}

		int x0 = visible.x();
		int y0 = visible.y();
		int cropWidth = visible.width();
		int cropHeight = visible.height();
		if(cropWidth == width && cropHeight == height)
		{
			TranscoderVideoOverlayFrame overlayFrame = new TranscoderVideoOverlayFrame(width, height, buffer);
			overlayFrame.setDstX(dstX);
			overlayFrame.setDstY(dstY);
			return overlayFrame;
		}

		String msg = logPrefix + target + " overlay " + width + "x" + height + " at (" + dstX + "," + dstY + ") exceeds the " + frameWidth + "x" + frameHeight + " frame, cropping to " + cropWidth + "x" + cropHeight + " at (" + x0 + "," + y0 + "). Stream:" + appName + "/" + streamName;
		if(!msg.equals(streamInfo.lastCropWarning))
		{
			streamInfo.lastCropWarning = msg;
			logger.warn(msg);
			sendToListeners(streamName, Level.WARN, msg);
		}

		byte[] cropped = OverlayCrop.crop(buffer, width, dstX, dstY, visible);

		TranscoderVideoOverlayFrame overlayFrame = new TranscoderVideoOverlayFrame(cropWidth, cropHeight, cropped);
		overlayFrame.setDstX(x0);
		overlayFrame.setDstY(y0);
		return overlayFrame;
	}

	public boolean getQueueDrawing(String streamName)
	{
		return getStreamInfo(streamName).queueDrawings;
	}

	public void setQueueDrawing(String streamName, boolean queueDrawing)
	{
		getStreamInfo(streamName).queueDrawings = queueDrawing;
	}

	public OverlayImage getOverlayImage(String streamName)
	{
		return getStreamInfo(streamName).streamOverlayImage;
	}

	public void clearOverlayImage(String streamName)
	{
		StreamInfo streamInfo = getStreamInfo(streamName);
		OverlayImage oldImage;
		// Lock on the same monitor as setOverlayImage so the check-then-act (take + null) can't race a
		// concurrent set; otherwise a clear can drop a just-configured overlay.
		synchronized(streamInfo)
		{
			oldImage = streamInfo.streamOverlayImage;
			streamInfo.streamOverlayImage = null;
		}
		// outside the lock: closing headless browsers can be slow
		if(oldImage != null)
		{
			oldImage.dispose();
		}
	}

	public void setOverlayImage(String streamName, OverlayImage overlayImage)
	{
		if(overlayImage == null)
		{
			return;
		}
		StreamInfo streamInfo = getStreamInfo(streamName);
		OverlayImage oldImage;
		synchronized(streamInfo)
		{
			oldImage = streamInfo.streamOverlayImage;
			streamInfo.streamOverlayImage = overlayImage;
			streamInfo.streamWidth = overlayImage.getWidth(1.0);
			streamInfo.streamHeight = overlayImage.getHeight(1.0);
			overlayImage.setOverlayModule(this);
			overlayImage.setStreamName(streamName);
			streamInfo.streamOverlayImage.start();
		}
		if(oldImage != null && oldImage != overlayImage)
		{
			oldImage.dispose();
		}
		checkOverlayFitsSource(streamName, overlayImage);
	}

	public void clearOverlayImageQueue(String streamName)
	{
		StreamInfo streamInfo = getStreamInfo(streamName);
		synchronized(streamInfo.overlayImageQueue)
		{
			for(OverlayImage img: streamInfo.overlayImageQueue)
			{
				img.dispose();
			}
			streamInfo.overlayImageQueue.clear();
			streamInfo.overlayImageQueue.add(OverlayImage.createRemoveOverlayImage());
		}
	}

	public void addOverlayImageQueue(String streamName, OverlayImage overlayImage)
	{
		if(overlayImage == null)
		{
			return;
		}
		StreamInfo streamInfo = getStreamInfo(streamName);

		synchronized(streamInfo.overlayImageQueue)
		{
			if(streamInfo.overlayImageQueue.size() > 30)
			{
				OverlayImage removeImage = streamInfo.overlayImageQueue.poll();
				removeImage.dispose();
			}	
			streamInfo.overlayImageQueue.add(overlayImage);
			streamInfo.streamWidth = overlayImage.getWidth(1.0);
			streamInfo.streamHeight = overlayImage.getHeight(1.0);
			overlayImage.setOverlayModule(this);
			overlayImage.setStreamName(streamName);
			overlayImage.start();
		}
		checkOverlayFitsSource(streamName, overlayImage);
	}

	public OverlayImage getOverlayImageQueue(String streamName)
	{
		OverlayImage returnImage = null;
		StreamInfo streamInfo = getStreamInfo(streamName);

		synchronized(streamInfo.overlayImageQueue)
		{
			returnImage = streamInfo.overlayImageQueue.poll();
		}
		return returnImage;
	}

	class OverlayLiveStreamTranscoderNotifier implements ILiveStreamTranscoderNotify
	{
		OverlayTranscoderActionNotifier transcoderActionNotifier = null;
		@Override

		public void onLiveStreamTranscoderCreate(ILiveStreamTranscoder liveStreamTranscoder, IMediaStream stream)
		{
			transcoderActionNotifier = new OverlayTranscoderActionNotifier(stream);
			((LiveStreamTranscoder)liveStreamTranscoder).addActionListener(transcoderActionNotifier);
		}

		@Override
		public void onLiveStreamTranscoderDestroy(ILiveStreamTranscoder liveStreamTranscoder, IMediaStream stream)
		{
			if(transcoderActionNotifier != null)
			{
				((LiveStreamTranscoder)liveStreamTranscoder).removeActionListener(transcoderActionNotifier);
				transcoderActionNotifier = null;
			}
		}

		@Override
		public void onLiveStreamTranscoderInit(ILiveStreamTranscoder liveStreamTranscoder, IMediaStream stream)
		{
		}
	}

	/** A transcoder session running a VIF template has VIF's frame encode among its destinations. */
	static boolean isVifSession(List<String> destinationNames)
	{
		return destinationNames.stream().anyMatch(com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.VIF_ENCODE::equals);
	}

	// Records which template the stream's transcoder loaded and the overlay it names (Transcode/Properties).
	private void noteTemplate(String streamName, LiveStreamTranscoder lst)
	{
		java.util.Map<String, Object> t = new java.util.LinkedHashMap<>();
		String list = lst.getTemplateName();
		String dir = lst.getTemplateDir();
		String loaded = com.wowza.wms.plugin.urloverlay.templates.TemplateLists.resolve(list, streamName,
				n -> dir != null && new File(dir, n).isFile());
		t.put("loaded", loaded);
		t.put("own", loaded != null && loaded.equalsIgnoreCase(streamName + ".xml"));
		t.put("list", list);
		streamTemplates.put(streamName, t);
		if(loaded != null && dir != null)
		{
			File f = new File(dir, loaded);
			streamTemplateFiles.put(streamName, f);
			streamStructure.put(streamName, structure(f));
		}
		else
		{
			streamTemplateFiles.remove(streamName);
			streamStructure.remove(streamName);
		}
		String name = lst.getProperties() == null ? null : lst.getProperties().getPropertyStr(
				com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.PROP_OVERLAY, null);
		if(name != null && !name.isBlank())
		{
			streamOverlayNames.put(streamName, name.trim());
			String enc = lst.getProperties().getPropertyStr(com.wowza.wms.plugin.urloverlay.templates.TemplateValidator.PROP_ENCODES, "");
			List<String> encs = new ArrayList<>();
			for(String e: enc.split(","))
			{
				if(!e.trim().isEmpty())
				{
					encs.add(e.trim());
				}
			}
			streamOverlayEncodes.put(streamName, encs);
		}
		else
		{
			streamOverlayNames.remove(streamName);
			streamOverlayEncodes.remove(streamName);
		}
		missingOverlays.remove(streamName);
	}

	class OverlayTranscoderActionNotifier extends LiveStreamTranscoderActionNotifyBase
	{
		OverlayTranscoderVideoDecoderNotifier transcoderVideoDecoderNotifier = null;
		IMediaStream iMediaStream = null;

		public OverlayTranscoderActionNotifier(IMediaStream iMediaStream)
		{
			this.iMediaStream = iMediaStream;
		}

		@Override
		public void onSessionVideoEncodeSetup(LiveStreamTranscoder liveStreamTranscoder, TranscoderSessionVideoEncode sessionVideoEncode)
		{
			getLogger().info(logPrefix + "onSessionVideoEncodeSetup["+appInstance.getContextStr()+"]");
			TranscoderStream transcoderStream = liveStreamTranscoder.getTranscodingStream();
			if (transcoderStream != null && transcoderVideoDecoderNotifier==null)
			{
				noteTemplate(iMediaStream.getName(), liveStreamTranscoder);
				TranscoderSession transcoderSession = liveStreamTranscoder.getTranscodingSession();
				TranscoderSessionVideo transcoderVideoSession = transcoderSession.getSessionVideo();
				List<TranscoderStreamDestination> alltrans = transcoderStream.getDestinations();
				if(isVifSession(alltrans.stream().map(TranscoderStreamDestination::getName).toList()))
				{
					// VIF and URL Overlay are not mixed: never draw into a transcoder running a VIF template
					if(!"vif-template".equals(transcoderStates.put(iMediaStream.getName(), "vif-template")))
					{
						logger.warn(logPrefix + "stream " + iMediaStream.getName() + " runs a Video Intelligence template; no URL overlay is drawn on it");
					}
					return;
				}

				int w = transcoderVideoSession.getDecoderWidth();
				int h = transcoderVideoSession.getDecoderHeight();
				transcoderVideoDecoderNotifier = new OverlayTranscoderVideoDecoderNotifier(iMediaStream.getName(), w,h);
				transcoderVideoSession.addFrameListener(transcoderVideoDecoderNotifier);

				//apply an overlay to all outputs
				for(TranscoderStreamDestination destination:alltrans)
				{
					TranscoderStreamDestinationVideo videoDestination = destination.getVideo();

					if (sessionVideoEncode != null && videoDestination !=null)
					{
						if(destination.isEnable())
						{
							transcoderVideoDecoderNotifier.addEncoder(destination.getName(), destination.getStreamName(iMediaStream.getName()),
									sessionVideoEncode, videoDestination, w, h);
						}
					}
				}
			}
		}
		@Override
		public void onShutdownStop(LiveStreamTranscoder liveStreamTranscoder)
		{
			if(transcoderVideoDecoderNotifier != null)
			{
				transcoderVideoDecoderNotifier.shutdown();
				transcoderVideoDecoderNotifier = null;
			}
		}
	}

	class EncoderInfo
	{
		public String encodeName;
		public String streamName;
		public TranscoderSessionVideoEncode sessionVideoEncode = null;
		public TranscoderStreamDestinationVideo destinationVideo = null;
		public int srcWidth = 0;
		public int srcHeight = 0;
		public int destWidth = 0;
		public int destHeight = 0;

		public EncoderInfo(String name, String streamName, TranscoderSessionVideoEncode sessionVideoEncode,
				TranscoderStreamDestinationVideo destinationVideo, int srcWidth, int srcHeight)
		{
			this.encodeName = name;
			this.streamName = streamName;
			this.sessionVideoEncode = sessionVideoEncode;
			this.destinationVideo = destinationVideo;
			this.srcWidth = srcWidth;
			this.srcHeight = srcHeight;
			this.destWidth = destinationVideo.getFrameSizeWidth();
			this.destHeight = destinationVideo.getFrameSizeHeight();

			if(this.destHeight == 0)
			{
				if (destinationVideo.getFrameSizeFitMode() == TranscoderStream.FRAMESIZE_FITMODE_FITWIDTH)
				{
					this.destHeight = (int)(this.destWidth*((double)srcHeight / (double)srcWidth));
				}
			}
			if(this.destWidth == 0)
			{
				if (destinationVideo.getFrameSizeFitMode() == TranscoderStream.FRAMESIZE_FITMODE_FITHEIGHT)
				{
					this.destWidth = (int)(this.destHeight*((double)srcWidth / (double)srcHeight));
				}
			}
			if (destinationVideo.getFrameSizeFitMode() == TranscoderStream.FRAMESIZE_FITMODE_MATCHSOURCE)
			{
				// match-source encodes are configured as 0 x 0 on purpose: they take the source size
				this.destWidth = srcWidth;
				this.destHeight = srcHeight;
			}
			// an output whose video is off (audio only) or passed through has no frame of its own to draw on
			boolean noVideo = destinationVideo.getCodec() == TranscoderStream.TRANSCODING_DISABLE || destinationVideo.getCodec() == TranscoderStream.TRANSCODING_PASSTHROUGH;
			if(this.destWidth == 0 && !noVideo)
			{
				logger.warn(logPrefix + "Width is 0, overlays may not work");
			}
			if(this.destHeight == 0 && !noVideo)
			{
				logger.warn(logPrefix  + "Height is 0, overlays may not work");
			}

		}

		// Actual encoded frame size. destWidth/destHeight are the configured frame-size box, which is larger than the
		// output for fit modes such as fit-height with a different source aspect. The decoder sets the actual size
		// before the first frame is scaled; until then fall back to the configured size, then the source size.
		public int getFrameWidth()
		{
			TranscoderVideoFrameSizeHolder holder = destinationVideo.getFrameSizeHolder();
			if(holder != null && holder.getActualWidth() > 0)
			{
				return (int)holder.getActualWidth();
			}
			return destWidth > 0 ? destWidth : srcWidth;
		}

		public int getFrameHeight()
		{
			TranscoderVideoFrameSizeHolder holder = destinationVideo.getFrameSizeHolder();
			if(holder != null && holder.getActualHeight() > 0)
			{
				return (int)holder.getActualHeight();
			}
			return destHeight > 0 ? destHeight : srcHeight;
		}
	}

	class OverlayTranscoderVideoDecoderNotifier extends TranscoderVideoDecoderNotifyBase
	{
		private int lastDrawWarning = 0;

		// at most one warning a second when a draw on the transcoder's thread takes longer than maxDrawingTime
		private void warnIfSlow(OverlayImage overlayImage, long ms, String shownStream)
		{
			if(overlayImage.getMaxDrawingTime() <= 0 || ms <= overlayImage.getMaxDrawingTime())
			{
				return;
			}
			int curSec = (int)(System.currentTimeMillis() / 1000);
			if(curSec != lastDrawWarning)
			{
				String msg = logPrefix + "Drawing object is taking more than " + overlayImage.getMaxDrawingTime() + "ms (" + ms + "ms), transcoder may get behind. Stream:" + appName + "/" + shownStream;
				logger.warn(msg);
				sendToListeners(streamName, Level.WARN, msg);
				lastDrawWarning = curSec;
			}
		}

		// read by the status endpoint while the transcoder adds encoders
		List<EncoderInfo> encoderInfoList = new java.util.concurrent.CopyOnWriteArrayList<>();

		private final Object lock = new Object();
		private final String streamName;
		private int srcWidth;
		private int srcHeight;

		public OverlayTranscoderVideoDecoderNotifier(String streamName, int srcWidth, int srcHeight)
		{
			getLogger().info(logPrefix+"OverlayTranscoderVideoDecoderNotify:"+srcWidth+"x"+srcHeight+ "<"+streamName+">");
			this.srcHeight = srcHeight;
			this.srcWidth = srcWidth;
			transcoderStates.put(streamName, "running");
			encodersByStream.put(streamName, encoderInfoList);
			decoderSizes.put(streamName, new int[] { srcWidth, srcHeight });
			try
			{
				// an overlay named by the stream's template comes first; otherwise the stream's own saved file
				if(!applyNamedOverlay(streamName))
				{
					// read from where OverlayConfig saves it
					File file = com.wowza.wms.plugin.urloverlay.templates.ModuleFiles.streamFile(
							java.nio.file.Paths.get(VHostsConfig.getVHostDir(appInstance.getVHost().getName())),
							appInstance.getApplication().getName(), appInstance.getName(), streamName).toFile();
					if (file.exists())
					{
						OverlayConfig newConfig = OverlayConfig.loadStreamFile(file, appInstance.getVHost().getName(),
								appInstance.getApplication().getName(), appInstance.getName(), streamName);
						newConfig.saveNewObject();
						logger.info(
								logPrefix + "OverlayTranscoderVideoDecoderNotify:Loaded overlay. Threads:" + newConfig.getThreadDrawing() + " Refresh:" + newConfig.getRefreshRate() + " Source:" + newConfig.getOverlaySource() + " Sources: " + newConfig.getOverlayEncodes()
										.toString());
					}
					else
					{
						//clear the overlay in case it was removed
						clearOverlayImage(streamName);
					}
				}
			}
			catch(ConfigBase.ConfigBaseException | IOException | RuntimeException e) {
				// a bad overlay file must not stop the stream's decoder listener from being set up
				logger.error(logPrefix + "OverlayTranscoderVideoDecoderNotify: could not load overlay file for " + streamName + ": " + e);
			}

			this.streamName = streamName;
			StreamInfo streamInfo = getStreamInfo(streamName);
			streamInfo.streamWidth = srcWidth;
			streamInfo.streamHeight = srcHeight;
			streamInfo.sourceWidth = srcWidth;
			streamInfo.sourceHeight = srcHeight;
			if(streamInfo.streamOverlayImage != null)
			{
				streamInfo.streamOverlayImage.setWidth(srcWidth);
				streamInfo.streamOverlayImage.setHeight(srcHeight);
				// overlay may have been created before the source size was known
				checkOverlayFitsSource(streamName, streamInfo.streamOverlayImage);
			}
		}

		public void shutdown()
		{
			getLogger().info(logPrefix+"OverlayTranscoderVideoDecoderNotify:Shutdown");
			transcoderStates.put(streamName, "stopped");
			encodersByStream.remove(streamName);
			synchronized(lock)
			{
				clearOverlayImageQueue(streamName);
			}
			StreamInfo streamInfo = removeStreamInfo(streamName);
			if(streamInfo != null)
			{
				OverlayImage oldImage;
				synchronized(streamInfo)
				{
					oldImage = streamInfo.streamOverlayImage;
					streamInfo.streamOverlayImage = null;
				}
				if(oldImage != null)
				{
					oldImage.dispose();
				}
			}
		}

		public void addEncoder(String name, String streamName, TranscoderSessionVideoEncode sessionVideoEncode,
				TranscoderStreamDestinationVideo destinationVideo, int srcWidth, int srcHeight)
		{
			//do this just for logging purpose and specific encodes
			String shortStreamName = StringUtils.removeStart(streamName,"mp4:");
			encoderInfoList.add(new EncoderInfo(name, shortStreamName, sessionVideoEncode, destinationVideo, srcWidth, srcHeight));
		}


		//The real work for putting overlays onto video
		@Override
		public void onBeforeScaleFrame(TranscoderSessionVideo sessionVideo, TranscoderStreamSourceVideo sourceVideo,
				long frameCount)
		{

			int overlayIndex = appOverlayIndex;
			double scalingFactor = 1.0;
			synchronized(lock)
			{
				// after shutdown a late frame must not bring the stream's entry back
				StreamInfo streamInfo = findStreamInfo(streamName);
				if(streamInfo == null)
				{
					return;
				}

				// on the first frame, a transparent overlay in every slot; some WSE builds otherwise also drew encode overlays on the source
				if (frameCount == 0 && initTransparentOverlay)
				{
					for (EncoderInfo encoderInfo : encoderInfoList)
					{
						if (!encoderInfo.destinationVideo.isPassThrough())
						{
							//fully transparent overlay
							int frameWidth = encoderInfo.getFrameWidth();
							int frameHeight = encoderInfo.getFrameHeight();
							byte[] newOverlayBuffer = new byte[frameWidth*frameHeight*4];
							TranscoderVideoOverlayFrame overlayFrame = new TranscoderVideoOverlayFrame(
									frameWidth,
									frameHeight,
									newOverlayBuffer);
							overlayFrame.setDstX(0);
							overlayFrame.setDstY(0);
							logger.info(logPrefix + "Adding Transparent Encode <" + encoderInfo.encodeName + "> " + "Overlay (" + overlayIndex + "): " + frameWidth + "x" + frameHeight);
							encoderInfo.destinationVideo.addOverlay(overlayIndex, overlayFrame);
						}
					}
					byte[] newOverlayBuffer = new byte[srcWidth*srcHeight*4];
					TranscoderVideoOverlayFrame overlayFrame = new TranscoderVideoOverlayFrame(
							srcWidth,
							srcHeight,
							newOverlayBuffer);
					overlayFrame.setDstX(0);
					overlayFrame.setDstY(0);
					logger.info(logPrefix + "Adding Transparent Source Overlay ("+overlayIndex+ "): " +srcWidth +"x"+srcHeight);
					sourceVideo.addOverlay(overlayIndex,overlayFrame);
					return;
				}
				OverlayImage overlayImage;
				Boolean queueDrawing = streamInfo.queueDrawings;
				if(queueDrawing)
				{
					overlayImage = getOverlayImageQueue(streamName);
					OverlayImage lastQueuedOverlayImage = streamInfo.lastQueuedOverlayImage;
					if(overlayImage == null)
					{
						//nothing to do, just leave the current image there
						overlayImage = lastQueuedOverlayImage;
					}
					if(lastQueuedOverlayImage!= null &&lastQueuedOverlayImage != overlayImage)
					{
						lastQueuedOverlayImage.dispose();
					}
					streamInfo.lastQueuedOverlayImage = overlayImage;
				}
				else {
					overlayImage = getOverlayImage(streamName);
				}
				try
				{
					UUID currentImageUuid;
					if(overlayImage == null)
					{
						currentImageUuid = new UUID(0L,0L);
						overlayIndex = streamInfo.lastOverlayIndex;
					}
					else {
						overlayIndex = overlayImage.getOverlayIndex() > 0 ? overlayImage.getOverlayIndex() : appOverlayIndex;
						currentImageUuid = overlayImage.getCurrentImageUuid();
					}
					UUID lastImageUuid = streamInfo.lastImageUuid;
					if(!lastImageUuid.equals(currentImageUuid))
					{
						debugLog(logPrefix + "Updating Image: " + lastImageUuid + "->" + currentImageUuid);
						if(currentImageUuid.equals(new UUID(0L,0L)))
						{
							if(streamInfo.overlayOnSource)
							{
								debugLog(logPrefix + "Clearing Source Overlay (" + overlayIndex + ")");
								sourceVideo.clearOverlay(overlayIndex);
							}
							streamInfo.overlayOnSource = false;
							if(streamInfo.overlayOnEncodes)
							{
								for (EncoderInfo encoderInfo : encoderInfoList)
								{
									debugLog(logPrefix + "Clearing Encode <" + encoderInfo.encodeName + "> Overlay (" + overlayIndex + ")");
									encoderInfo.destinationVideo.clearOverlay(overlayIndex);
									streamInfo.overlayOnEncode.put(encoderInfo.encodeName,false);
								}
							}
							streamInfo.overlayOnEncodes = false;
							streamInfo.lastImageUuid = currentImageUuid;
						}
						else
						{
							if(overlayImage != null)
							{
								if (overlayImage.getOverlaySource())
								{
									if (streamInfo.overlayOnEncodes)
									{
										for (EncoderInfo encoderInfo : encoderInfoList)
										{
											debugLog(logPrefix + "Clearing Encode <" + encoderInfo.encodeName + "> Overlay (" + overlayIndex + ")");
											encoderInfo.destinationVideo.clearOverlay(overlayIndex);
											streamInfo.overlayOnEncode.put(encoderInfo.encodeName, false);
										}
										streamInfo.overlayOnEncodes = false;
									}
									long startTime = System.currentTimeMillis();
									//put overlay on source
									byte[] newOverlayBuffer = overlayImage.getCurrentImageBuffer(scalingFactor);
									long endTime = System.currentTimeMillis();
									streamInfo.lastImageUuid = overlayImage.getDrawnUuid(scalingFactor);
									warnIfSlow(overlayImage, endTime - startTime, streamName);

									if (newOverlayBuffer != null)
									{
										TranscoderVideoOverlayFrame overlayFrame = createOverlayFrame(streamName, "Source",
												srcWidth, srcHeight,
												overlayImage.getWidth(scalingFactor),
												overlayImage.getHeight(scalingFactor),
												overlayImage.getxPos(scalingFactor),
												overlayImage.getyPos(scalingFactor),
												newOverlayBuffer);
										if (overlayFrame != null)
										{
											debugLog(logPrefix + "Adding Source Overlay (" + overlayIndex + "): " + overlayFrame.getVideoFrame().getWidth() + "x" + overlayFrame.getVideoFrame().getHeight());
											sourceVideo.addOverlay(overlayIndex, overlayFrame);
											streamInfo.overlayOnSource = true;
										}
									}
								}
								else
								{
									//put overlay on each encode
									if (streamInfo.overlayOnSource)
									{
										debugLog(logPrefix + "Clearing Source Overlay ("+overlayIndex+")");
										sourceVideo.clearOverlay(overlayIndex);
										streamInfo.overlayOnSource = false;
									}

									for (EncoderInfo encoderInfo : encoderInfoList)
									{
										if (!encoderInfo.destinationVideo.isPassThrough())
										{

											if (overlayImage.overlayEncode(encoderInfo.encodeName, encoderInfo.streamName))
											{
												int destinationHeight = encoderInfo.destHeight;
												int destinationWidth = encoderInfo.destWidth;
												if (encoderInfo.destinationVideo.getFrameSizeFitMode() == TranscoderStream.FRAMESIZE_FITMODE_MATCHSOURCE)
												{
													destinationHeight = getStreamHeight(streamName);
													destinationWidth = getStreamWidth(streamName);
												}
												if (destinationHeight > 0)
												{
													scalingFactor = (double)destinationHeight / (double)getStreamHeight(
															streamName);
												}
												else if (destinationWidth > 0)
												{
													scalingFactor = (double)destinationWidth / (double)getStreamWidth(streamName);
												}
												long startTime = System.currentTimeMillis();
												byte[] newOverlayBuffer = overlayImage.getCurrentImageBuffer(scalingFactor);
												long endTime = System.currentTimeMillis();
												streamInfo.lastImageUuid = overlayImage.getDrawnUuid(scalingFactor);
												warnIfSlow(overlayImage, endTime - startTime, encoderInfo.streamName);
												if (newOverlayBuffer != null)
												{
													int frameWidth = encoderInfo.getFrameWidth();
													int frameHeight = encoderInfo.getFrameHeight();
													TranscoderVideoOverlayFrame overlayFrame = createOverlayFrame(streamName,
															"Encode <" + encoderInfo.encodeName + ">",
															frameWidth, frameHeight,
															overlayImage.getWidth(scalingFactor),
															overlayImage.getHeight(scalingFactor),
															overlayImage.getxPos(scalingFactor),
															overlayImage.getyPos(scalingFactor),
															newOverlayBuffer);
													if (overlayFrame != null)
													{
														debugLog(logPrefix + "Adding Encode <" + encoderInfo.encodeName + "> Overlay (" + overlayIndex + "): " + overlayFrame.getVideoFrame().getWidth() + "x" + overlayFrame.getVideoFrame().getHeight() + " ScalingFactor:" + scalingFactor);
														encoderInfo.destinationVideo.addOverlay(overlayIndex, overlayFrame);
														streamInfo.overlayOnEncode.put(encoderInfo.encodeName, true);
													}
												}
												else
												{
												}
												streamInfo.overlayOnEncodes = true; //at least on has an encode
											}
											else
											{
												if (streamInfo.overlayOnEncode.getOrDefault(encoderInfo.encodeName, false))
												{
													debugLog(logPrefix + "Clearing Encode <" + encoderInfo.encodeName + "> Overlay (" + overlayIndex + ")");
													encoderInfo.destinationVideo.clearOverlay(overlayIndex);
													streamInfo.overlayOnEncode.put(encoderInfo.encodeName, false);
												}
											}
										}
									}
								}
							}
						}
					}
					streamInfo.lastOverlayIndex = overlayIndex;
				}

				catch(Exception e)
				{
					logger.error(logPrefix + "onBeforeScaleFrame:Error:" + e.toString(), e);
				}
			}
		}
	}
}
