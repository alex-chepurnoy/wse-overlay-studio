/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.restlet.resource.ResourceException;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.plugin.overlaystudio.Fonts;
import com.wowza.wms.plugin.overlaystudio.OverlayImage;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioLog;
import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.plugin.overlaystudio.templates.ModuleFiles;
import com.wowza.wms.plugin.overlaystudio.rest.WMSResponse2;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.rest.WMSResponse;
import com.wowza.wms.rest.vhosts.VHostsConfig;

@JsonInclude(Include.NON_NULL)
@JsonSerialize
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE, creatorVisibility = JsonAutoDetect.Visibility.NONE)
@JacksonXmlRootElement(localName = "overlay")

@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/overlay", restVerbs = "GET,POST,PUT,DELETE", xmlFilename = "")
public class OverlayConfig extends JsonApplicationConfigBase
{
	// wse-plugin-overlays and VIF draw in slot 1; a separate slot keeps the two overlays from replacing each other.
	public static final int DEFAULT_OVERLAY_INDEX = 16;

	@JsonProperty("vhostName")
	protected String vhostName = null;

	@JsonProperty("appName")
	protected String appName = null;

	@JsonProperty("applicationInstance")
	protected String instanceName = null;

	@JsonProperty("streamName")
	protected String streamName = null;

	@JsonProperty("overlaySource")
	protected boolean overlaySource = true;

	@JsonProperty("overlayEncodes")
	protected ArrayList<String> overlayEncodes = new ArrayList<String>();

	@JsonProperty("opacity")
	protected int opacity = -1;

	@JsonProperty("overlayIndex")
	// 0 means the application's OverlayStudioIndex (default DEFAULT_OVERLAY_INDEX)
	protected int overlayIndex = 0;

	@JsonProperty("refreshRate")
	protected double refreshRate = 0.0;

	@JsonProperty("threadDrawing")
	protected boolean threadDrawing = true;

	@JsonProperty("maxDrawingTime")
	protected int maxDrawingTime = 0;

	@JsonProperty("x")
	protected int x = 0;

	@JsonProperty("y")
	protected int y = 0;

	@JsonProperty("w")
	protected int w = -1;

	@JsonProperty("h")
	protected int h = -1;

	@JsonProperty("even_coords")
	protected boolean evenCoords;

	//DEFAULTS
	@JsonProperty("stroke")
	protected int stroke = 1;

	@JsonProperty("color")
	protected String color = "black";

	@JsonProperty("fillColor")
	protected String fillColor = null;

	@JsonProperty("shadowColor")
	protected String shadowColor = null;

	@JsonProperty("shadowOffset")
	protected int shadowOffset = 2;

	//Java defines five logical font families that are Serif, SansSerif, Monospaced, Dialog, and DialogInput.
	@JsonProperty("font")
	protected String font = Fonts.DEFAULT_FAMILY;

	@JsonProperty("fontStyle")
	protected String fontStyle = "plain";

	@JsonProperty("fontSize")
	protected int fontSize = 16;
	
	// Copy-on-write: never changed in place once set. An edit builds a new list and swaps it in under the image's lock
	// (editLock), so a draw walks the list it took and never sees an edit half done (D12).
	@JsonProperty("objects")
	protected volatile List<ObjectConfig> objects = new ArrayList<ObjectConfig>();

	// the image drawing this overlay, set by OverlayImage; null for an overlay that is not on a stream
	private transient volatile OverlayImage image;

	@JsonIgnore
	private volatile UUID uuid = UUID.randomUUID();

	// set when this overlay came from a named overlay through the stream's template; such an overlay is not saved per stream
	@JsonIgnore
	private String namedOverlay = null;

	@JsonIgnore
	protected BufferedImage backgroundImage = null;

	@JsonIgnore
	protected HashMap<Double, BufferedImage> backgroundImageCache = new HashMap<>();

	public OverlayConfig()
	{
		addFieldForKeyMap("vhostName", "vhostName");
		addFieldForKeyMap("appName", "appName");
		addFieldForKeyMap("instance", "instanceName");
		addFieldForKeyMap("stream", "streamName");
		setDefaults();
	}

	/**
	 * Reads a per-stream overlay file and binds it to the stream it was found for. The file's own context fields,
	 * if any, are ignored: a hand-written or copied file without them would otherwise reach getModule with a null
	 * stream name and fail.
	 */
	public static OverlayConfig loadStreamFile(File file, String vhostName, String appName, String instanceName, String streamName) throws IOException
	{
		OverlayConfig config = new ObjectMapper().readValue(file, OverlayConfig.class);
		config.vhostName = vhostName;
		config.appName = appName;
		config.instanceName = instanceName;
		config.streamName = streamName;
		return config;
	}

	@JsonIgnore
	public String getNamedOverlay() { return namedOverlay; }
	@JsonIgnore
	public void setNamedOverlay(String name) { this.namedOverlay = name; }

	@JsonIgnore
	public String getBoundVHostName() { return vhostName; }
	@JsonIgnore
	public String getBoundAppName() { return appName; }
	@JsonIgnore
	public String getBoundInstanceName() { return instanceName; }
	@JsonIgnore
	public String getBoundStreamName() { return streamName; }

	public OverlayConfig(String vhostName, String appName, String instanceName, String streamName)
	{
		super(vhostName, appName);
		this.vhostName = vhostName;
		this.appName = appName;
		this.instanceName = instanceName;
		this.streamName = streamName;
		addFieldForKeyMap("vhostName", "vhostName");
		addToStringKeyMap("vhostName", vhostName);
		addFieldForKeyMap("appName", "appName");
		addToStringKeyMap("appName", appName);
		addFieldForKeyMap("instance", "instanceName");
		addToStringKeyMap("instance", instanceName);
		addFieldForKeyMap("stream", "streamName");
		addToStringKeyMap("stream", streamName);
		setDefaults();
	}

	public OverlayConfig(UUID uuid)
	{
		setDefaults();
		this.uuid = uuid;
	}


	public boolean getOverlaySource() { return this.overlaySource; };
	public void setOverlaySource(boolean overlaySource) { this.overlaySource = overlaySource; };

	public boolean getThreadDrawing() { return this.threadDrawing; };
	public void setThreadDrawing(boolean threadDrawing) {this.threadDrawing = threadDrawing; };

	public ArrayList<String> getOverlayEncodes() { return this.overlayEncodes; };
	public void setOverlayEncodes(ArrayList<String> overlayEncodes) { this.overlayEncodes = overlayEncodes; };

	public int getX() { return x; };
	public void setX(int x) { this.x = x; };

	public int getY() { return y; };
	public void setY(int y) { this.y = y; };

	public int getW() { return w; };
	public void setW(int w) { this.w = w; };

	public int getH() { return h; };
	public void setH(int h) { this.h = h; };

	public boolean getEvenCoords() { return evenCoords; };
	public void setEvenCoords(Boolean evenCoords) { this.evenCoords = evenCoords; };

	public int getOpacity() { return opacity; };
	public void setOpacity(int opacity) { this.opacity = opacity; };

	public int getOverlayIndex() { return overlayIndex; };

	public double getRefreshRate() { return refreshRate; };
	public void setRefreshRate(double refreshRate) { this.refreshRate = refreshRate; };

	public List<ObjectConfig> getObjects() { return objects; };
	public void setObjects(List<ObjectConfig> objects) { this.objects = objects; };

	/** Attaches the image that draws this overlay; its lock then orders every edit with every draw. */
	public void setImage(OverlayImage image) { this.image = image; }

	/** The lock edits take: the drawing image's, or this overlay's own when it is not on a stream. */
	private Object editLock()
	{
		OverlayImage i = image;
		return i != null ? i.lock() : this;
	}

	/**
	 * After an edit: disposes removed objects once no draw that may still use them is running, and lets the image plan
	 * its next wake-up from what the overlay now holds (an added clock starts ticking).
	 */
	private void retire(List<ObjectConfig> removed)
	{
		OverlayImage i = image;
		if(i != null)
		{
			i.retire(removed);
			i.reschedule();
		}
		else
		{
			removed.forEach(ObjectConfig::dispose);
		}
	}

	public BufferedImage getBackgroundImage(double scaled)
	{
		if(backgroundImage == null)
			return null;
		if(scaled == 1.0)
			return backgroundImage;

		if(!backgroundImageCache.containsKey(scaled))
		{
			int newWidth = (int)(backgroundImage.getWidth() * scaled);
			int newHeight = (int)(backgroundImage.getHeight() * scaled);
			BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, backgroundImage.getType());
			Graphics2D g = resizedImage.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_DEFAULT);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(backgroundImage, 0, 0, newWidth, newHeight, null);
			g.dispose();
			backgroundImageCache.put(scaled,resizedImage);
		}
		return backgroundImageCache.get(scaled);
	};
	public void setBackgroundImage(BufferedImage backgroundImage) { this.backgroundImage = backgroundImage; };

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("vhostName");
		addToStringKeyMapFromRequest("appName");
		addToStringKeyMapFromRequest("instance");
		addToStringKeyMapFromRequest("stream");
		setPathMap();
	}

	public final void setDefaults()
	{
		vhostName = null;
		appName = null;
		instanceName = null;
		streamName = null;
		overlaySource = true;
		overlayEncodes.clear();
		opacity = -1;
		overlayIndex = 0;
		refreshRate = 0.0;
		threadDrawing = true;
		maxDrawingTime = 0;
		x = 0;
		y = 0;
		w = -1;
		h = -1;
		evenCoords = false;
		stroke = 1;
		color = "black";
		fillColor = null;
		shadowColor = null;
		shadowOffset = 2;
		font = Fonts.DEFAULT_FAMILY;
		fontStyle = "plain";
		fontSize = 16;
		objects = new ArrayList<>();
		uuid = UUID.randomUUID();
		backgroundImage = null;
		backgroundImageCache.clear();
	}

	public String getVhostName() {return vhostName;};
	public String getAppName() {return appName;};

	public String getInstanceName()
	{
		return this.instanceName;
	}

	public void setInstanceName(String instanceName)
	{
		this.instanceName = instanceName;
	}

	public String getStreamName()
	{
		return this.streamName;
	}

	public UUID getUuid()
	{
		return uuid;
	}

	public void resetUuid()
	{
		synchronized(editLock())
		{
			uuid = UUID.randomUUID();
		}
	}

	/** Whether any web page on this overlay has a frame that has not been drawn yet. */
	public boolean hasNewFrame()
	{
		List<ObjectConfig> list = objects;
		OverlayImage i = image;
		// the scales this overlay goes to now; scale 1.0 for an overlay on no stream
		java.util.Set<Double> scales = i != null ? i.activeScales() : java.util.Set.of(1.0);
		if(list != null)
		{
			for(ObjectConfig o: list)
			{
				if(o.hasNewFrame(scales))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Override
	protected boolean exists(String restVerb)
	{
		return !restVerb.equals("post");
	}

	//this is the GET
	@Override
	public boolean loadObject()
	{
		if(namesProblem() != null)
		{
			return false;
		}
		OverlayStudioModule overlayModule = (OverlayStudioModule)getModule(vhostName, appName, instanceName, streamName, new WMSResponse());

		if(overlayModule == null)
		{
			return false;
		}
		OverlayImage img = overlayModule.getOverlayImage(streamName);
		if(img != null)
		{
			this.copyFrom(img.getOverlayConfig());
		}
		else
		{
			// not publishing: show the saved layers, which load when the stream's transcoder starts
			File saved = new File(getFilename());
			if(saved.isFile())
			{
				try
				{
					this.copyFrom(loadStreamFile(saved, vhostName, appName, instanceName, streamName));
				}
				catch(IOException | RuntimeException e)
				{
					logWarn("OverlayStudio: could not read " + saved.getName() + ": " + e);
				}
			}
		}
		return true;
	}

	//this is the POST
	@Override
	public WMSResponse saveNewObject() throws ConfigBaseException
	{
		checkNames();
		WMSResponse2 retVal = new WMSResponse2();
		OverlayStudioModule overlayModule = (OverlayStudioModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}
		// this overlay's own canvas: its w, h, or the source's size; nothing stream-wide is changed (D14)
		OverlayImage newOverlay = new OverlayImage(this.x ,this.y, OverlayStudioModule.canvasSize(this.w, overlayModule.getSourceWidth(streamName)),
				OverlayStudioModule.canvasSize(this.h, overlayModule.getSourceHeight(streamName)), this, threadDrawing, maxDrawingTime);
		newOverlay.setEvenCoords(evenCoords);

		applyDefaults();
		if(objects != null)
		{
			for(ObjectConfig obj: objects)
			{
				obj.checkWebPageUrl();
			}
		}
		sortAndSaveObjects();
		newOverlay.setOverlayConfig(this);
		overlayModule.setOverlayImage(streamName, newOverlay);
		resetUuid();
		OverlayStudioLog.change(this, "set overlay on " + OverlayStudioLog.stream(vhostName, appName, instanceName, streamName) + " with "
				+ (objects == null ? 0 : objects.size()) + " object(s)");

		retVal.setSuccess();
		retVal.theObject = this;
		return retVal;
	}

	//this is the PUT
	@Override
	public WMSResponse saveObject() throws ConfigBaseException
	{
		checkNames();
		WMSResponse2 retVal = new WMSResponse2();
		OverlayStudioModule overlayModule = (OverlayStudioModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay Module not found");
			throw ex;
		}

		OverlayImage overlay = overlayModule.getOverlayImage(streamName);
		if(overlay == null)
		{
			ConfigBaseException ex =new ConfigBaseException();
			ex.addError("","Overlay not created yet");
			throw ex;
		}
		applyDefaults();
		overlay.mergeOverlayConfig(this);
		sortAndSaveObjects();
		OverlayStudioLog.change(this, "updated overlay on " + OverlayStudioLog.stream(vhostName, appName, instanceName, streamName));

		resetUuid();
		retVal.setSuccess();
		retVal.theObject = this;
		return retVal;
	}

	//this is the DELETE
	@Override
	public WMSResponse deleteObject()
	{
		WMSResponse2 retVal = new WMSResponse2();
		String bad = namesProblem();
		if(bad != null)
		{
			retVal.message = bad;
			return retVal;
		}
		OverlayStudioModule overlayModule = (OverlayStudioModule)getModule(vhostName, appName, instanceName, streamName, retVal);
		if(overlayModule == null)
		{
			retVal.message = "Overlay Module not found";
			return retVal;
		}

		overlayModule.clearOverlayImage(streamName);
		resetUuid();
		File f = new File(this.getFilename());
		f.delete();
		OverlayStudioLog.change(this, "deleted overlay on " + OverlayStudioLog.stream(vhostName, appName, instanceName, streamName));

		retVal.setSuccess();
		retVal.message = "Deleted";
		return retVal;
	}

	/** conf.modules/overlaystudio/&lt;app&gt;/streams/&lt;instance&gt;/&lt;stream&gt;.json. */
	@Override
	public String getFilename() {
		String dir = vhostName == null ? null : VHostsConfig.getVHostDir(vhostName);
		if (dir == null || appName == null || instanceName == null || streamName == null) {
			return null;
		}
		return ModuleFiles.streamFile(java.nio.file.Paths.get(dir), appName.trim(), instanceName, streamName).toString();
	}

	/** Why the request's names cannot give this stream's file, or null when they can. */
	String namesProblem()
	{
		try
		{
			return getFilename() == null ? "No stream file for " + vhostName + "/" + appName + "/" + instanceName + "/" + streamName : null;
		}
		catch(ModuleFiles.BadName e)
		{
			return e.getMessage();
		}
	}

	private void checkNames() throws ConfigBaseException
	{
		String bad = namesProblem();
		if(bad != null)
		{
			ConfigBaseException ex = new ConfigBaseException();
			ex.addError("stream", bad);
			throw ex;
		}
	}

	protected WMSResponse saveObjectToFile()
	{
		return saveObjectToFile(this.getFilename());
	}

	@Override
	protected WMSResponse saveObjectToFile(String filename) {
		WMSResponse retVal = new WMSResponse();
		if(namedOverlay != null)
		{
			// the named overlay's own file is the record; a per-stream copy would shadow later edits
			return retVal;
		}
		try {
			ObjectMapper mapper = new ObjectMapper();
			ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
			File out = new File(getFilename());
			out.getParentFile().mkdirs();
			writer.writeValue(out, this);

		} catch (IOException var9) {
			retVal.message = var9.getMessage();
			this.logException("saveObjectToFile", var9);
		}
		return retVal;
	}

	public static ModuleBase getModule(String vHostName, String appName, String instanceName, String streamName, WMSResponse retVal )
	{
		return OverlayStudioModule.getModule(vHostName, appName, instanceName, streamName, why -> {
			retVal.message = why;
			logWarn("OverlayConfig:" + why);
		});
	}

	public void sortAndSaveObjects()
	{
		sortObjects();
		saveObjectToFile();
	}

	public void sortObjects()
	{
		synchronized(editLock())
		{
			List<ObjectConfig> n = new ArrayList<>(this.objects);
			sortObjects(n);
			this.objects = n;
		}
		retire(List.of());
	}

	@SuppressWarnings("Convert2Lambda")
	public static void sortObjects(List<ObjectConfig> list)
	{
		Collections.sort(list, new Comparator<>()
		{
			@Override
			public int compare(ObjectConfig obj1, ObjectConfig obj2)
			{
				if(obj1.getzOrder() == obj2.getzOrder())
					if(obj1.getX() == obj2.getX())
						return obj1.getId().compareTo(obj2.getId());
					else
						return Integer.compare(obj1.getX(), obj2.getX());
				else
					return Integer.compare(obj1.getzOrder(), obj2.getzOrder());
			}
		});
	}

	public void addObject(ObjectConfig obj)
	{
		putObject(obj);
	}

	/**
	 * Adds obj, or puts it in place of the object with its id, in one swap with the uuid reset, so no draw sees the
	 * overlay without either. The object it replaces is disposed once draws that may use it end.
	 */
	public void putObject(ObjectConfig obj)
	{
		List<ObjectConfig> removed = new ArrayList<>();
		synchronized(editLock())
		{
			List<ObjectConfig> n = new ArrayList<>(this.objects.size() + 1);
			for(ObjectConfig o: this.objects)
			{
				if(o != obj && o.getId() != null && o.getId().equals(obj.getId()))
				{
					removed.add(o);
				}
				else if(o != obj)
				{
					n.add(o);
				}
			}
			n.add(obj);
			sortObjects(n);
			this.objects = n;
			uuid = UUID.randomUUID();
		}
		retire(removed);
	}

	public ObjectConfig getObject(String obectId)
	{
		ObjectConfig retVal =null;
		for (ObjectConfig obj : this.objects)
		{
			if(obj.getId().equals(obectId))
			{
				retVal = obj;
				break;
			}
		}
		return retVal;
	}

	public void deleteObject(String objectId)
	{
		List<ObjectConfig> removed = new ArrayList<>();
		synchronized(editLock())
		{
			List<ObjectConfig> n = new ArrayList<>(this.objects.size());
			for(ObjectConfig obj: this.objects)
			{
				if(removed.isEmpty() && obj.getId().equals(objectId))
				{
					removed.add(obj);
				}
				else
				{
					n.add(obj);
				}
			}
			if(removed.isEmpty())
			{
				return;
			}
			this.objects = n;
			uuid = UUID.randomUUID();
		}
		retire(removed);
	}

	public void applyDefaults()
	{
		if(this.objects != null)
		{
			for (ObjectConfig obj : this.objects)
			{
				obj.applyDefaults();
				obj.applyDefaults(this);
			}
		}
	}
}
