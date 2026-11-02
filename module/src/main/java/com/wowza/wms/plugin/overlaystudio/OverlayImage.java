/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

public class OverlayImage
{
	//done so memory allocations are minimized
	private final Map<Double, BufferedImage> bufferedImageMap = new HashMap<>();
	// the other half of each double buffer; guarded by imageLock
	private final Map<Double, byte[]> spareByteImageMap = new HashMap<>();

	/**
	 * What one draw produced for one scale, handed to the transcoder as a whole: the buffer, its size and place, and
	 * the content (uuid) it shows. Immutable, so a buffer is never paired with another draw's size or place.
	 */
	public record Published(byte[] buffer, int width, int height, int x, int y, UUID uuid)
	{
	}

	// the last record per scale; guarded by imageLock
	private final Map<Double, Published> published = new HashMap<>();

	/**
	 * The image lock: a draw takes the object list and the uuid together under it, and publishes under it; every edit
	 * swaps the list and resets the uuid under it (OverlayConfig.editLock). So a draw never pairs one list with
	 * another's uuid, and an edit never waits for a draw to finish.
	 */
	private final Object imageLock = new Object();
	// draws that took a list and have not finished; objects replaced meanwhile wait in retired until it is 0
	private int drawsInFlight = 0;
	private final List<ObjectConfig> retired = new ArrayList<>();

	public int xPos = 0;
	public int yPos = 0;
	private int width = 0;
	private int height = 0;
	private boolean evenCoords = false;
	// set while a background draw runs; at most one at a time
	private final java.util.concurrent.atomic.AtomicBoolean drawingImage = new java.util.concurrent.atomic.AtomicBoolean();
	private ScheduledFuture<?> refreshTimer = null;

	/**
	 * Background draws for every stream, on a few threads rather than one new thread per draw: each overlay has at most
	 * one draw queued or running, so the queue holds at most one per stream. Half the cores, 2 to 8, so draws never
	 * take every core from the transcoder.
	 */
	static final ExecutorService DRAWS;
	static
	{
		int n = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
		ThreadPoolExecutor e = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
			Thread t = new Thread(r, "OverlayStudioDraw");
			t.setDaemon(true);
			return t;
		});
		e.allowCoreThreadTimeOut(true);
		DRAWS = e;
	}
	/** One thread ticks every overlay's refresh; a tick only marks the overlay for redrawing. */
	static final ScheduledExecutorService REFRESH = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "OverlayStudioRefresh");
		t.setDaemon(true);
		return t;
	});

	private OverlayConfig mainOverlayConfig;
	private String streamName;
	private boolean overlaySource;
	private ArrayList<String> overlayEncodes;
	private boolean threadDrawing = true;
	private long maxDrawingTime = 0;
	private final Object statsLock = new Object();
	private long lastDrawMs = -1;
	private long drawCount = 0;
	private long drawTotalMs = 0;
	private long slowCount = 0;
	private java.time.Instant lastDrawAt = null;


	WMSLogger log = WMSLoggerFactory.getLogger(null);

	public OverlayImage()
	{
	}	

	public OverlayImage(int x, int y, int width, int height, OverlayConfig overlayConfig)
	{
		this(x,y,width,height,overlayConfig,true);
	}

	public OverlayImage(int x, int y, int width, int height, OverlayConfig overlayConfig, boolean threadDrawing)
	{
		this(x,y,width,height,overlayConfig,threadDrawing, 50);
	}

	public OverlayImage(int x, int y, int width, int height, OverlayConfig overlayConfig, boolean threadDrawing, int maxDrawingTime)
	{
		this.mainOverlayConfig = overlayConfig;
		this.xPos = x;
		this.yPos = y;
		this.width = width;
		this.height = height;
		this.threadDrawing = threadDrawing;
		this.maxDrawingTime = maxDrawingTime;
		this.overlaySource = overlayConfig.getOverlaySource();
		this.overlayEncodes = overlayConfig.getOverlayEncodes();
		overlayConfig.setImage(this);
	}

	/** The image lock (see imageLock). */
	public Object lock()
	{
		return imageLock;
	}

	/** Disposes objects taken out of this overlay, now or once the draws that may still use them end. */
	public void retire(List<ObjectConfig> objects)
	{
		if(objects.isEmpty())
		{
			return;
		}
		synchronized(imageLock)
		{
			if(drawsInFlight > 0)
			{
				retired.addAll(objects);
				return;
			}
		}
		objects.forEach(ObjectConfig::dispose);
	}

	//specal constructor to create an overlay that removes the overlay from the stream
	public static OverlayImage createRemoveOverlayImage()
	{
		OverlayImage retVal = new OverlayImage();
		OverlayConfig overlayConfig = new OverlayConfig(new UUID(0L,0L));
		retVal.mainOverlayConfig = overlayConfig;
		retVal.threadDrawing = false;
		return retVal;
	}

	public boolean getOverlaySource() { return this.overlaySource; };
	public void setOverlaySource(boolean overlaySource) { this.overlaySource = overlaySource; };

	public boolean getEvenCoords() { return this.evenCoords; };
	public void setEvenCoords(boolean evenCoords) { this.evenCoords = evenCoords; };
	public void setOverlayModule(OverlayStudioModule overlayModule)
	{
	}

	public void setStreamName(String streamName)
	{
		this.streamName = streamName;
	}

	public OverlayConfig getOverlayConfig()
	{
		return mainOverlayConfig;
	}

	public void setOverlayConfig(OverlayConfig overlayConfig)
	{
		synchronized(imageLock)
		{
			this.mainOverlayConfig = overlayConfig;
			if(this.mainOverlayConfig != null)
			{
				if (this.mainOverlayConfig.getOpacity() < 0)
				{
					this.mainOverlayConfig.setOpacity(100);
				}
			}
			for(ObjectConfig obj: mainOverlayConfig.getObjects())
			{
				obj.oImage = this;
			}
			overlayConfig.setImage(this);
		}

	}

	public boolean overlayEncode(String encodeName, String outputStreamName)
	{
		return EncodeTargets.matches(overlayEncodes, encodeName, outputStreamName);
	}
	public void start()
	{
		if(mainOverlayConfig != null)
		{
			if(mainOverlayConfig.getRefreshRate() > 0)
			{
				if(refreshTimer != null)
				{
					refreshTimer.cancel(false);
				}
				long ms = Math.max(1, (long)(mainOverlayConfig.getRefreshRate() * 1000.0));
				refreshTimer = REFRESH.scheduleAtFixedRate(new RefreshTimer(), ms, ms, TimeUnit.MILLISECONDS);
			}
		}
	}

	public void stop()
	{
		if(refreshTimer != null)
		{
			refreshTimer.cancel(false);
			refreshTimer = null;
		}
	}

	// Stop refreshing and release every object's resources (headless browsers, reload timers). Call when this image is dropped.
	public void dispose()
	{
		stop();
		List<ObjectConfig> objects = new ArrayList<>();
		synchronized(imageLock)
		{
			if(this.mainOverlayConfig != null && this.mainOverlayConfig.getObjects() != null)
			{
				objects.addAll(this.mainOverlayConfig.getObjects());
			}
		}
		retire(objects);
	}

	public class RefreshTimer implements Runnable
	{

		public RefreshTimer()
		{
		}

		// a refresh redraws only when a page has a frame not drawn yet; the rest of an overlay changes only on save,
		// which redraws by itself
		@Override
		public void run()
		{
			OverlayConfig c = mainOverlayConfig;
			if(c != null && c.hasNewFrame())
			{
				c.resetUuid();
			}
		}
	}

	public void mergeOverlayConfig(OverlayConfig overlayConfig)
	{
		List<ObjectConfig> replacedObjs = new ArrayList<>();
		synchronized(imageLock)
		{
			LinkedHashMap<String, ObjectConfig> overlayObjects =  new LinkedHashMap<>();
			if(this.mainOverlayConfig.getObjects() != null)
			{
				for (ObjectConfig obj : this.mainOverlayConfig.getObjects())
				{
					if(obj.getType() == null)
					{
						log.warn("Type is required for id: "+obj.getId());
					}
					else
					{
						overlayObjects.put(obj.getId(), obj);
					}
				}
			}

			//merge new objs into old objs by id
			if(overlayConfig.getObjects() != null)
			{
				for (ObjectConfig obj : overlayConfig.getObjects())
				{
					obj.oImage = this;
					ObjectConfig replacedObj = overlayObjects.put(obj.getId(), obj);
					if(replacedObj != null && replacedObj != obj)
					{
						replacedObjs.add(replacedObj);
					}
				}
			}
			//create a new list of objs
			List<ObjectConfig> newObjs = new ArrayList<>();
			for(String key: overlayObjects.keySet())
			{
				newObjs.add(overlayObjects.get(key));
			}
			OverlayConfig.sortObjects(newObjs);

			int oldOpacity = this.mainOverlayConfig.getOpacity();
			this.mainOverlayConfig = overlayConfig;
			this.mainOverlayConfig.setObjects(newObjs);
			overlayConfig.setImage(this);
			if(overlayConfig.getOpacity() < 0)
			{
				this.mainOverlayConfig.setOpacity(oldOpacity);
			}
			if(this.mainOverlayConfig.getOpacity() < 0)
			{
				this.mainOverlayConfig.setOpacity(100);
			}
			overlayConfig.resetUuid();
		}
		retire(replacedObjs);
	}


	public UUID getCurrentImageUuid()
	{
		return mainOverlayConfig.getUuid();
	}

	public UUID getDrawnUuid(double scaled)
	{
		synchronized(imageLock)
		{
			Published p = published.get(scaled);
			return p != null ? p.uuid() : new UUID(0L,0L);
		}
	}

	public void setWidth(int width)
	{
		if(this.width <= 0)
		{
			this.width = width;
		}
	}

	public void setHeight(int height)
	{
		if(this.height <= 0)
		{
			this.height = height;
		}
	}

	public int getOverlayIndex()
	{
		if(mainOverlayConfig == null)
		{
			return 0;
		}
		return mainOverlayConfig.getOverlayIndex();
	}

	/** Whether a background draw is running. */
	boolean isDrawing()
	{
		return drawingImage.get();
	}

	public long getMaxDrawingTime()
	{
		return maxDrawingTime;
	}

	public byte[] getCurrentImageBuffer(double scaled)
	{
		Published p = current(scaled);
		return p == null ? null : p.buffer();
	}

	/**
	 * The record for this scale when it shows the current content; otherwise starts a draw (in the background, or now
	 * for an inline overlay) and returns null until a background one is published.
	 */
	public Published current(double scaled)
	{
		if (mainOverlayConfig == null)
		{
			return null;
		}
		synchronized(imageLock)
		{
			Published p = published.get(scaled);
			if(p != null && p.uuid().equals(mainOverlayConfig.getUuid()))
			{
				return p;
			}
		}

		try
		{
			if(threadDrawing)
			{
				//don't block the transcoder with complicated drawing
				if (drawingImage.compareAndSet(false, true))
				{
					try
					{
						DRAWS.execute(new DrawImageThread(mainOverlayConfig, scaled));
					}
					catch(RuntimeException | Error e)
					{
						drawingImage.set(false);
						throw e;
					}
				}
			}
			else
			{
				//drawing the image during transcoder session, careful not to take too long
				drawImage(mainOverlayConfig, scaled);
				synchronized(imageLock)
				{
					return published.get(scaled);
				}
			}
		}
		catch (Exception e)
		{
			log.error(e);
		}
		return null;
	}

	public class DrawImageThread implements Runnable
	{
		private final OverlayConfig overlayConfig;
		private double scaled=1;

		public DrawImageThread(OverlayConfig overlayConfig, double scaled)
		{
			this.overlayConfig = overlayConfig;
			this.scaled = scaled;
		}

		@Override
		public void run()
		{
			try
			{
				drawImage(overlayConfig, scaled);
			}
			catch(RuntimeException e)
			{
				// the next frame tries again; a failed draw must not stop every later one
				log.error("OverlayStudio: drawing the overlay for " + streamName + " failed: " + e, e);
			}
			finally
			{
				drawingImage.set(false);
			}
		}

	}

	public byte[] drawImage(OverlayConfig overlayConfig, double scaled)
	{
		long drawStart = System.nanoTime();
		// what this draw shows, taken together: an edit or a refresh that lands while it runs causes the next one
		UUID drawing;
		List<ObjectConfig> objects;
		synchronized(imageLock)
		{
			drawing = overlayConfig.getUuid();
			objects = overlayConfig.getObjects();
			drawsInFlight++;
		}
		try
		{
			return drawImage(overlayConfig, scaled, drawing, objects, drawStart);
		}
		finally
		{
			List<ObjectConfig> done = null;
			synchronized(imageLock)
			{
				if(--drawsInFlight == 0 && !retired.isEmpty())
				{
					done = new ArrayList<>(retired);
					retired.clear();
				}
			}
			if(done != null)
			{
				done.forEach(ObjectConfig::dispose);
			}
		}
	}

	private byte[] drawImage(OverlayConfig overlayConfig, double scaled, UUID drawing, List<ObjectConfig> objects, long drawStart)
	{
		ObjectConfig.CAPTURE_WAIT_NS.set(0L);
		BufferedImage bImage = getBufferedImage(scaled);
		Graphics2D g = bImage.createGraphics();
		if(mainOverlayConfig.getBackgroundImage(scaled) == null)
		{
			//clear the old image out
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, getWidth(scaled), getHeight(scaled));
		}
		else
		{
			//put the background one the image
			g.setComposite(AlphaComposite.SrcOver);
			g.drawImage(mainOverlayConfig.getBackgroundImage(scaled), 0, 0, null);
		}
		g.setComposite(AlphaComposite.SrcOver);

		if (overlayConfig.getOpacity() > 0.0) //don't bother if invisible
		{
			log.debug("Drawing images");
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			// the list this draw took; an edit swaps in a new one and never changes this one
			if (objects != null)
			{
				for (ObjectConfig obj : objects)
				{
					obj.draw(g, scaled, evenCoords);
				}
			}
		}

		// Draw into the spare buffer, then swap: WSE copies the published buffer when it takes the overlay frame, and a
		// draw writing into that same array meanwhile would tear it.
		byte[] retVal;
		synchronized(imageLock)
		{
			retVal = spareByteImageMap.remove(scaled);
			if(retVal == null)
			{
				retVal = new byte[getWidth(scaled) * getHeight(scaled) * 4];
			}
		}
		transferImage(bImage, retVal, overlayConfig.getOpacity());
		Published next = new Published(retVal, getWidth(scaled), getHeight(scaled), getxPos(scaled), getyPos(scaled), drawing);
		synchronized(imageLock)
		{
			Published old = published.put(scaled, next);
			if(old != null && old.buffer().length == retVal.length)
			{
				spareByteImageMap.put(scaled, old.buffer());
			}
		}
		g.dispose();
		long waitNs = ObjectConfig.CAPTURE_WAIT_NS.get();
		recordDraw((System.nanoTime() - drawStart - waitNs) / 1_000_000);
		return retVal;
	}

	// The burn-in is what this draw costs itself, without any wait for a page's frame; the screenshot time is the
	// capture's own timing.
	private void recordDraw(long ms)
	{
		synchronized(statsLock)
		{
			lastDrawMs = ms;
			drawCount++;
			drawTotalMs += ms;
			lastDrawAt = java.time.Instant.now();
			if(maxDrawingTime > 0 && ms > maxDrawingTime)
			{
				slowCount++;
			}
		}
	}

	/** Draw timing for the status endpoint. */
	public java.util.Map<String, Object> drawStats()
	{
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		synchronized(statsLock)
		{
			m.put("lastMs", lastDrawMs < 0 ? null : lastDrawMs);
			m.put("avgMs", drawCount == 0 ? null : drawTotalMs / drawCount);
			m.put("count", drawCount);
			m.put("maxDrawingTimeMs", maxDrawingTime);
			m.put("slowCount", slowCount);
			m.put("lastDrawAt", lastDrawAt == null ? null : lastDrawAt.toString());
		}
		return m;
	}

	public int getHeight(double scaled)
	{
		return (int)(height * scaled);
	}

	public int getWidth(double scaled)
	{
		return (int)(width * scaled);
	}

	public int getxPos(double scaled)
	{
		return (int)(xPos * scaled);
	}

	public int getyPos(double scaled)
	{
		return (int)(yPos * scaled);
	}

	public BufferedImage getBufferedImage(double scaled)
	{
		BufferedImage retVal;
		synchronized (bufferedImageMap) {	
			if (!bufferedImageMap.containsKey(scaled))
			{
				bufferedImageMap.put(scaled,
						new BufferedImage(getWidth(scaled), getHeight(scaled), BufferedImage.TYPE_4BYTE_ABGR));
			}
			retVal = bufferedImageMap.get(scaled);
		}
		return retVal;
	}


	private static void transferImage(BufferedImage sourceImg, byte[] destImgBytes, int opacity)
	{
		// the image's own buffer; getData() would copy the whole frame first
		byte[] sourceImgBytes = ((DataBufferByte) sourceImg.getRaster().getDataBuffer()).getData();
		int len = sourceImgBytes.length;
		for(int x = 0; x < len; x+=4)
		{
			int alpha = sourceImgBytes[x]&0x0FF;
			if(opacity < 100)
				alpha = (alpha * opacity) / 100;

			destImgBytes[x + 0] = sourceImgBytes[x + 1];
			destImgBytes[x + 1] = sourceImgBytes[x + 2];
			destImgBytes[x + 2] = sourceImgBytes[x + 3];
			destImgBytes[x + 3] = (byte)alpha;
		}


	}
}
