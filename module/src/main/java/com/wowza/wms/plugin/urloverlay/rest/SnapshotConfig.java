/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.wowza.util.FLVUtils;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;
import com.wowza.wms.rest.WMSConfig;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoder;
import com.wowza.wms.transcoder.model.LiveStreamTranscoder;
import com.wowza.wms.transcoder.model.TranscoderStream;
import com.wowza.wms.transcoder.model.TranscoderStreamDestination;
import com.wowza.wms.transcoder.model.TranscoderStreamDestinationVideo;
import com.wowza.wms.transcoder.thumbnailer.ThumbnailerContext;
import com.wowza.wms.transcoder.thumbnailer.ThumbnailerRequest;
import com.wowza.wms.transcoder.thumbnailer.ThumbnailerResponse;
import com.wowza.wms.transcoder.thumbnailer.ThumbnailerUtils;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

/**
 * A JPEG of one transcoder output's last key frame, decoded by WSE's thumbnailer, as base64 inside JSON with what it
 * cost: decode, CPU and JPEG time, bytes, whether it came from the cache, and the key frame's age. ?width= is clamped
 * to 320 to 960 (640 by default); ?since=<keyFrameTimecode> answers {unchanged:true} when the key frame is the same.
 * Reads only, so no administrator is needed. Decodes are capped across the server by SnapshotCache.
 */
// Spike for a snapshot preview in the page, which does not use it yet; may be removed once that is decided.
@WMSConfig(restLocation = "/v1/{server}/plugin/urloverlay/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/outputs/{encode}/snapshot", restVerbs = "GET", xmlFilename = "")
public class SnapshotConfig extends VHostResourceBase
{
	static final int WIDTH_MIN = 320, WIDTH_MAX = 960, WIDTH_DEFAULT = 640;
	static final float JPEG_QUALITY = 0.75f;

	@Override
	protected Object doGet(Map<String, String> path, Map<String, String> query) throws Exception
	{
		long start = System.nanoTime();
		String vhostName = path.get("vhostName"), app = path.get("appName"), instance = path.get("instance");
		String stream = path.get("stream"), encode = path.get("encode");
		IVHost vhost = VHostSingleton.getInstance(vhostName);
		IApplicationInstance ai = instance(vhost, app, instance);
		if(ai == null)
		{
			throw new TemplateFiles.Refused(404, "Application instance " + app + "/" + instance + " is not running.");
		}
		IMediaStream source = ai.getStreams().getStream(stream);
		if(source == null)
		{
			throw new TemplateFiles.Refused(404, "Stream " + stream + " is not publishing.");
		}
		TranscoderStreamDestination dest = destination(source, encode);
		if(dest == null)
		{
			throw new TemplateFiles.Refused(404, "Stream " + stream + " has no running output for encode " + encode + ".");
		}
		String outputName = Viewers.bare(dest.getStreamName(stream));
		int width = width(query.get("width"));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("spike", true);
		m.put("encode", encode);
		m.put("outputStream", outputName);
		m.put("requestedWidth", width);
		SnapshotCache cache = SnapshotCache.server();
		m.put("cap", cache.cap());
		IMediaStream out = ai.getStreams().getStream(outputName);
		AMFPacket keyFrame = out == null ? null : out.getLastKeyFrame();
		if(keyFrame == null)
		{
			// no key frame: the output is not publishing yet, or getLastKeyFrame() is not kept for it
			m.put("pending", true);
			m.put("reason", out == null ? "output not publishing" : "no key frame");
			return finish(m, cache, vhost, start);
		}
		long tc = keyFrame.getAbsTimecode();
		int codec = FLVUtils.getVideoCodec(keyFrame);
		m.put("codec", codecName(codec));
		m.put("timecode", tc);
		m.put("ageMs", Math.max(0, out.getMaxTimecode() - tc));
		if(String.valueOf(tc).equals(query.get("since")))
		{
			m.put("unchanged", true);
			return finish(m, cache, vhost, start);
		}
		if(codec != IVHost.CODEC_VIDEO_H264 && codec != IVHost.CODEC_VIDEO_H265 && codec != IVHost.CODEC_VIDEO_MPEG2 && codec != IVHost.CODEC_VIDEO_MPEG4)
		{
			m.put("unsupported", true);
			m.put("reason", "No snapshot for " + codecName(codec));
			return finish(m, cache, vhost, start);
		}
		int height = height(width, dest.getVideo());
		String key = vhostName + "/" + app + "/" + instance + "/" + outputName + "/" + width;
		Map<String, Object> got = cache.get(key, tc, () -> decode(vhost, out, keyFrame, width, height));
		SnapshotCache.Shot shot = (SnapshotCache.Shot)got.remove("shot");
		m.putAll(got);
		if(shot != null)
		{
			m.put("timecode", shot.keyFrameTimecode());
			m.put("width", shot.width());
			m.put("height", shot.height());
			m.put("bytes", shot.jpeg().length);
			m.put("decodeMs", shot.decodeMs());
			m.put("cpuMs", shot.cpuMs());
			m.put("encodeMs", shot.encodeMs());
			m.put("madeAt", shot.madeAt());
			m.put("jpeg", Base64.getEncoder().encodeToString(shot.jpeg()));
		}
		else if(!got.containsKey("error"))
		{
			m.put("pending", true);
		}
		return finish(m, cache, vhost, start);
	}

	private static Map<String, Object> finish(Map<String, Object> m, SnapshotCache cache, IVHost vhost, long start)
	{
		m.put("decoding", cache.decoding());
		m.put("peakDecoding", cache.peak());
		m.put("nativeEncoders", nativeEncoders(vhost));
		m.put("totalMs", (System.nanoTime() - start) / 1_000_000L);
		return m;
	}

	/** Decodes the key frame and encodes the JPEG, timing each, on the snapshot thread. */
	static SnapshotCache.Shot decode(IVHost vhost, IMediaStream out, AMFPacket keyFrame, int width, int height) throws Exception
	{
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		boolean cpu = mx.isCurrentThreadCpuTimeSupported();
		long cpu0 = cpu ? mx.getCurrentThreadCpuTime() : 0;
		long t0 = System.nanoTime();
		ThumbnailerRequest request = new ThumbnailerRequest(out, width, height, TranscoderStream.FRAMESIZE_FITMODE_LETTERBOX);
		request.addPacket(keyFrame);
		ThumbnailerResponse response = ThumbnailerUtils.generateThumbnail(vhost, request);
		long t1 = System.nanoTime();
		long cpu1 = cpu ? mx.getCurrentThreadCpuTime() : 0;
		if(response == null)
		{
			throw new IllegalStateException("The thumbnailer could not decode the key frame");
		}
		BufferedImage img = ThumbnailerUtils.nativeImageToBufferedImage(response);
		if(img == null)
		{
			throw new IllegalStateException("The thumbnailer returned an empty picture");
		}
		byte[] jpeg = jpeg(img);
		long t2 = System.nanoTime();
		return new SnapshotCache.Shot(keyFrame.getAbsTimecode(), img.getWidth(), img.getHeight(), jpeg, (t1 - t0) / 1_000_000L,
				cpu ? (cpu1 - cpu0) / 1_000_000L : -1, (t2 - t1) / 1_000_000L, System.currentTimeMillis());
	}

	static byte[] jpeg(BufferedImage img) throws java.io.IOException
	{
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if(!writers.hasNext())
		{
			throw new IllegalStateException("No JPEG writer in this Java");
		}
		ImageWriter w = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes))
		{
			ImageWriteParam p = w.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(JPEG_QUALITY);
			w.setOutput(ios);
			w.write(null, new IIOImage(img, null, null), p);
		}
		finally
		{
			w.dispose();
		}
		return bytes.toByteArray();
	}

	static int width(String w) throws TemplateFiles.Refused
	{
		int v = WIDTH_DEFAULT;
		if(w != null && !w.isBlank())
		{
			try
			{
				v = Integer.parseInt(w.trim());
			}
			catch(NumberFormatException e)
			{
				throw new TemplateFiles.Refused(400, "width must be a number of pixels");
			}
		}
		return Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, v));
	}

	/** The output's own aspect ratio when the transcoder knows its frame size, else 16:9; always even. */
	static int height(int width, TranscoderStreamDestinationVideo video)
	{
		int fw = video == null ? 0 : video.getFrameWidth(), fh = video == null ? 0 : video.getFrameHeight();
		int h = fw > 0 && fh > 0 ? Math.round(width * (float)fh / fw) : Math.round(width * 9f / 16f);
		return Math.max(ThumbnailerRequest.HEIGHT_MIN, h & ~1);
	}

	static String codecName(int codec)
	{
		return switch(codec)
		{
			case IVHost.CODEC_VIDEO_H264 -> "H.264";
			case IVHost.CODEC_VIDEO_H265 -> "H.265";
			case IVHost.CODEC_VIDEO_MPEG2 -> "MPEG-2";
			case IVHost.CODEC_VIDEO_MPEG4 -> "MPEG-4";
			default -> "codec " + codec;
		};
	}

	/** The enabled destination of the stream's transcoder with this encode name, or null. */
	static TranscoderStreamDestination destination(IMediaStream source, String encode)
	{
		Map<String, ILiveStreamTranscoder> all = source.getLiveStreamTranscoders();
		if(all == null)
		{
			return null;
		}
		for(ILiveStreamTranscoder t: all.values())
		{
			TranscoderStream ts = t instanceof LiveStreamTranscoder lst ? lst.getTranscodingStream() : null;
			List<TranscoderStreamDestination> dests = ts == null ? null : ts.getDestinations();
			if(dests == null)
			{
				continue;
			}
			for(TranscoderStreamDestination d: dests)
			{
				if(d.isEnable() && encode.equals(d.getName()))
				{
					return d;
				}
			}
		}
		return null;
	}

	private static IApplicationInstance instance(IVHost vhost, String app, String instance)
	{
		if(vhost == null || !vhost.isApplicationLoaded(app))
		{
			return null;
		}
		IApplication a = vhost.getApplication(app);
		return a == null || !a.isAppInstanceLoaded(instance) ? null : a.getAppInstance(instance);
	}

	/**
	 * The virtual host's native thumbnailer encoders, busy and idle, read from ThumbnailerContext's private lists since
	 * it has no getter; null when they cannot be read. Shared with every other thumbnailer user of the virtual host.
	 */
	static Map<String, Object> nativeEncoders(IVHost vhost)
	{
		ThumbnailerContext ctx = vhost == null ? null : vhost.getThumbnailerContext();
		if(ctx == null)
		{
			return null;
		}
		try
		{
			Field running = ThumbnailerContext.class.getDeclaredField("encodersRunning");
			Field waiting = ThumbnailerContext.class.getDeclaredField("encodersWaiting");
			running.setAccessible(true);
			waiting.setAccessible(true);
			synchronized(ctx.getLock())
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("running", ((List<?>)running.get(ctx)).size());
				m.put("idle", ((List<?>)waiting.get(ctx)).size());
				return m;
			}
		}
		catch(Exception | LinkageError e)
		{
			return null;
		}
	}
}
