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
	// the scales with a background draw queued or running: one per output scale, so a slow draw at one scale never
	// holds up another (D13)
	private final java.util.Set<Double> drawingScales = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// the scales the overlay goes to on the current frame, set by the module; a page frame is new while one of them
	// has not published it
	private volatile java.util.Set<Double> activeScales = java.util.Set.of();

	/** Redraws are at most this often per overlay, whatever its layers ask for. */
	static final long MIN_REDRAW_MS = 100;
	/** The clock the scheduler reads; tests replace it. */
	static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
	// the scheduler: one wake-up per overlay, at the earliest change a layer reports or the next page frame check
	private final Object scheduleLock = new Object();
	private ScheduledFuture<?> wake = null;
	private boolean running = false;
	private volatile long changeDueAt = Long.MAX_VALUE;
	private volatile long lastChangeAt = Long.MIN_VALUE / 2;
	// when the stream's transcoder started, epoch ms, for showFrom and hideAt given as stream+N; 0 until known
	private volatile long streamStart = 0;

	/** When the stream's transcoder started (stream+N times count from it); replans the wake-up. */
	public void setStreamStart(long epochMs)
	{
		if(streamStart != epochMs)
		{
			streamStart = epochMs;
			reschedule();
		}
	}

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
		synchronized(scheduleLock)
		{
			running = true;
		}
		reschedule();
	}

	public void stop()
	{
		synchronized(scheduleLock)
		{
			running = false;
			if(wake != null)
			{
				wake.cancel(false);
				wake = null;
			}
		}
	}

	/** The scales the overlay goes to on this frame (see activeScales). */
	public void setActiveScales(java.util.Set<Double> scales)
	{
		if(!scales.equals(activeScales))
		{
			activeScales = java.util.Set.copyOf(scales);
		}
	}

	public java.util.Set<Double> activeScales()
	{
		return activeScales;
	}

	/**
	 * When the scheduler next wakes, after now: the earliest change a layer reports (D1: a clock ticks with no page on
	 * the overlay), or the next page frame check at refreshRate when the overlay has pages; never sooner than
	 * MIN_REDRAW_MS after the last change. Long.MAX_VALUE when nothing will change by itself.
	 */
	long nextWakeAt(long now)
	{
		OverlayConfig c = mainOverlayConfig;
		if(c == null)
		{
			return Long.MAX_VALUE;
		}
		long due = Long.MAX_VALUE;
		boolean pages = false;
		for(ObjectConfig o: c.getObjects())
		{
			due = Math.min(due, o.nextChangeAt(now, streamStart));
			pages |= "webPage".equals(o.getType());
		}
		changeDueAt = due;
		long next = due;
		if(pages && c.getRefreshRate() > 0)
		{
			next = Math.min(next, now + Math.max(1, (long)(c.getRefreshRate() * 1000.0)));
		}
		return next == Long.MAX_VALUE ? next : Math.max(next, lastChangeAt + MIN_REDRAW_MS);
	}

	/** One wake-up: a layer's change came due, or a page has a frame not drawn yet; either redraws. */
	void wakeUp(long now)
	{
		OverlayConfig c = mainOverlayConfig;
		if(c != null && (now >= changeDueAt || c.hasNewFrame()))
		{
			c.resetUuid();
			lastChangeAt = now;
		}
	}

	/** Plans the next wake-up from what the overlay holds now; called at start, after each wake-up and after edits. */
	public void reschedule()
	{
		synchronized(scheduleLock)
		{
			if(!running)
			{
				return;
			}
			if(wake != null)
			{
				wake.cancel(false);
				wake = null;
			}
			long now = clock.getAsLong();
			long at = nextWakeAt(now);
			if(at == Long.MAX_VALUE)
			{
				return;
			}
			wake = REFRESH.schedule(() -> {
				try
				{
					wakeUp(clock.getAsLong());
				}
				catch(RuntimeException e)
				{
					log.error("OverlayStudio: the redraw check for " + streamName + " failed: " + e, e);
				}
				reschedule();
			}, Math.max(1, at - now), TimeUnit.MILLISECONDS);
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
		reschedule();
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

	/** Whether a background draw is running at any scale. */
	boolean isDrawing()
	{
		return !drawingScales.isEmpty();
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
				if (drawingScales.add(scaled))
				{
					try
					{
						DRAWS.execute(new DrawImageThread(mainOverlayConfig, scaled));
					}
					catch(RuntimeException | Error e)
					{
						drawingScales.remove(scaled);
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
				drawingScales.remove(scaled);
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
		// Only the part of the canvas the layers can paint is allocated, cleared, drawn and converted (spike S2: a full
		// canvas redraw is almost all clearing and converting). The same layers give the same box, so a ticking clock
		// reuses its buffers.
		java.awt.Rectangle box = drawBox(objects, scaled);
		BufferedImage bImage = getBufferedImage(scaled, box.width, box.height);
		Graphics2D g = bImage.createGraphics();
		if(mainOverlayConfig.getBackgroundImage(scaled) == null)
		{
			//clear the old image out
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, box.width, box.height);
		}
		g.translate(-box.x, -box.y);
		if(mainOverlayConfig.getBackgroundImage(scaled) != null)
		{
			//put the background one the image (the box is then the whole canvas)
			g.setComposite(AlphaComposite.Src);
			g.drawImage(mainOverlayConfig.getBackgroundImage(scaled), 0, 0, null);
		}
		g.setComposite(AlphaComposite.SrcOver);

		if (overlayConfig.getOpacity() > 0.0) //don't bother if invisible
		{
			log.debug("Drawing images");
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			// spike S4: the browser lays text out closest to this, so the page's canvas and the video agree
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

			// the list this draw took; an edit swaps in a new one and never changes this one
			if (objects != null)
			{
				// one clock for the whole draw, so fades and blinks match across layers
				long now = clock.getAsLong();
				for (ObjectConfig obj : objects)
				{
					obj.draw(g, scaled, evenCoords, obj.timingAlpha(now, streamStart));
				}
			}
		}

		// Draw into the spare buffer, then swap: WSE copies the published buffer when it takes the overlay frame, and a
		// draw writing into that same array meanwhile would tear it.
		byte[] retVal;
		synchronized(imageLock)
		{
			retVal = spareByteImageMap.remove(scaled);
			if(retVal == null || retVal.length != box.width * box.height * 4)
			{
				retVal = new byte[box.width * box.height * 4];
			}
		}
		transferImage(bImage, retVal, overlayConfig.getOpacity());
		Published next = new Published(retVal, box.width, box.height, getxPos(scaled) + box.x, getyPos(scaled) + box.y, drawing);
		synchronized(imageLock)
		{
			Published old = published.put(scaled, next);
			if(old != null && old.buffer().length == retVal.length)
			{
				spareByteImageMap.put(scaled, old.buffer());
			}
		}
		// published: the page frames this draw used now count as drawn at this scale
		if (objects != null)
		{
			for (ObjectConfig obj : objects)
			{
				obj.commitDrawn(scaled);
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
		return getBufferedImage(scaled, getWidth(scaled), getHeight(scaled));
	}

	/** This scale's drawing surface at this size, kept while the size stays the same. */
	BufferedImage getBufferedImage(double scaled, int w, int h)
	{
		synchronized (bufferedImageMap) {
			BufferedImage retVal = bufferedImageMap.get(scaled);
			if (retVal == null || retVal.getWidth() != w || retVal.getHeight() != h)
			{
				retVal = new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
				bufferedImageMap.put(scaled, retVal);
			}
			return retVal;
		}
	}

	/**
	 * The part of the canvas this draw covers: the union of the layers' areas (ObjectConfig.drawBounds), clipped to
	 * the canvas, with an even origin and even size where the canvas allows; the whole canvas when a layer may paint
	 * anywhere or the overlay has a background image; 2 x 2 at the origin when nothing paints.
	 */
	java.awt.Rectangle drawBox(List<ObjectConfig> objects, double scaled)
	{
		java.awt.Rectangle canvas = new java.awt.Rectangle(0, 0, getWidth(scaled), getHeight(scaled));
		if(mainOverlayConfig.getBackgroundImage(scaled) != null)
		{
			return canvas;
		}
		java.awt.Rectangle u = null;
		if(objects != null)
		{
			for(ObjectConfig o: objects)
			{
				java.awt.Rectangle b = o.drawBounds(scaled);
				if(b == null)
				{
					return canvas;
				}
				if(!b.isEmpty())
				{
					u = u == null ? b : u.union(b);
				}
			}
		}
		java.awt.Rectangle r = u == null ? new java.awt.Rectangle() : u.intersection(canvas);
		if(r.isEmpty())
		{
			return new java.awt.Rectangle(0, 0, Math.min(2, canvas.width), Math.min(2, canvas.height));
		}
		int x0 = r.x & ~1, y0 = r.y & ~1;
		int x1 = Math.min(canvas.width, r.x + r.width + ((r.x + r.width - x0) & 1));
		int y1 = Math.min(canvas.height, r.y + r.height + ((r.y + r.height - y0) & 1));
		return new java.awt.Rectangle(x0, y0, x1 - x0, y1 - y0);
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
