/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import com.wowza.wms.plugin.urloverlay.capture.CapturePool;
import com.wowza.wms.plugin.urloverlay.capture.PageRequest;
import com.wowza.wms.plugin.urloverlay.capture.Wire;
import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;

/**
 * One page as the module sees it: a handle on a capture the capture service runs. Chrome never runs inside WSE; this
 * asks the service for frames over its socket, keeps the last good one while the service restarts or is missing, and
 * opens the capture again when the service has forgotten it.
 */
public class WebPageCapture
{
	public static final double MIN_RELOAD_RATE = 1.0;
	static final long CALL_TIMEOUT_MS = 60_000;
	public static final String SERVICE_NOT_RUNNING = "service not running";

	/**
	 * What identifies a capture; a change to any of these means a new browser. The label is only for logs and is
	 * left out of equality, so layers on different streams showing the same page share one browser.
	 */
	public record Settings(String url, int viewportWidth, int viewportHeight, Color keyColor, boolean paintBackground, String label)
	{
		@Override
		public boolean equals(Object o)
		{
			return o instanceof Settings x && java.util.Objects.equals(url, x.url) && viewportWidth == x.viewportWidth
					&& viewportHeight == x.viewportHeight && java.util.Objects.equals(keyColor, x.keyColor) && paintBackground == x.paintBackground;
		}

		@Override
		public int hashCode()
		{
			return java.util.Objects.hash(url, viewportWidth, viewportHeight, keyColor, paintBackground);
		}
	}

	private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "UrlOverlayCapturePool");
		t.setDaemon(true);
		return t;
	});

	/** Open captures by page; a page left by its last layer stays open this long in case it is used again. */
	public static final long SHARE_GRACE_MS = 15000;
	public static final CapturePool<Settings, WebPageCapture> POOL = new CapturePool<>(s -> new WebPageCapture(s, () -> -1, null), WebPageCapture::close,
			SHARE_GRACE_MS, timer);

	private final Settings settings;
	private final Supplier<CaptureClient> client;
	private volatile IntSupplier pageDelay;
	private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();
	// the reload rate each layer sharing this page asked for; the page reloads at the shortest
	private final Map<Object, Double> requestedRates = new ConcurrentHashMap<>();

	private final Object lock = new Object();
	private String remoteId = null;
	private int sentDelay = Integer.MIN_VALUE;
	private double sentRate = -1;
	private long sentInterval = -1;
	// how often the service takes a frame: the overlay's refresh, learned from the draws' maxAgeMs
	private volatile long intervalMs = 1000;
	private long reloadsSeen = 0;
	private BufferedImage lastImage = null;
	private Instant lastCaptureAt = null;
	private Map<String, Object> status = Map.of("state", "idle");
	private boolean closed = false;
	private Thread fetcher = null;

	// the latest frame's number and when the service took it; -1 before the first
	private long frameSeq = -1;
	private long frameCapturedAtMs = 0;
	// timings for the status endpoint, so cadence can be measured on a server
	private long screenshotMs = -1, deliveryMs = -1, decodeMs = -1, frameBytes = 0, framesReceived = 0;
	private long draws = 0, drawWindowStart = System.currentTimeMillis(), drawWindowCount = 0, ageWindowTotal = 0, ageWindowCount = 0;
	private double drawsPerSecond = 0;
	private long frameAgeAtDrawMs = -1;

	public WebPageCapture(Settings settings, IntSupplier pageDelay, Runnable onReloaded)
	{
		this(settings, CaptureClient::get, pageDelay, onReloaded);
	}

	WebPageCapture(Settings settings, Supplier<CaptureClient> client, IntSupplier pageDelay, Runnable onReloaded)
	{
		this.settings = settings;
		this.client = client;
		this.pageDelay = pageDelay;
		if(onReloaded != null)
		{
			reloadListeners.add(onReloaded);
		}
	}

	/** Default form: the key color is rgb(1,2,3) and the page background is painted with it. */
	public WebPageCapture(String url, int width, int height, IntSupplier pageDelay, Runnable onReloaded)
	{
		this(new Settings(url, width, height, new Color(1, 2, 3), true, url), pageDelay, onReloaded);
	}

	/** The wait after load used by the layer that set it last; layers sharing a page share its timing. */
	public void usePageDelay(IntSupplier pageDelay)
	{
		this.pageDelay = pageDelay;
	}

	public void addReloadListener(Runnable r)
	{
		reloadListeners.add(r);
	}

	public void removeReloadListener(Runnable r)
	{
		reloadListeners.remove(r);
	}

	public boolean isFor(Settings other)
	{
		return settings.equals(other);
	}

	public Settings settings()
	{
		return settings;
	}

	/**
	 * One layer's reload rate for a page that several layers may share. The page reloads at the shortest positive rate
	 * any layer asks for, so layers asking for different rates do not keep resetting each other's timer.
	 */
	public void reloadEvery(Object layer, double rate)
	{
		if(rate > 0)
		{
			requestedRates.put(layer, rate);
		}
		else
		{
			requestedRates.remove(layer);
		}
	}

	/** A layer that stops using this page no longer asks for reloads. */
	public void forgetReload(Object layer)
	{
		requestedRates.remove(layer);
	}

	/** The rate the page reloads at, in seconds; 0 when it does not reload. */
	double reloadRate()
	{
		double r = requestedRates.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
		return r > 0 ? Math.max(r, MIN_RELOAD_RATE) : 0.0;
	}

	public BufferedImage capture() throws Exception
	{
		return capture(0);
	}

	/**
	 * The latest frame, at once: never waits on the socket, Chrome or decoding, which the fetcher does in the
	 * background. Null before the first frame and once closed. maxAgeMs is most of the overlay's refresh interval; the
	 * service takes frames at that refresh.
	 */
	public BufferedImage capture(long maxAgeMs) throws Exception
	{
		if(maxAgeMs > 0)
		{
			// the draws use 80% of the refresh as maxAgeMs; the service takes a frame once per refresh
			intervalMs = Math.max(50, Math.round(maxAgeMs / 0.8));
		}
		start();
		synchronized(lock)
		{
			if(closed)
			{
				return null;
			}
			long now = System.currentTimeMillis();
			draws++;
			drawWindowCount++;
			if(lastImage != null)
			{
				ageWindowTotal += now - frameCapturedAtMs;
				ageWindowCount++;
			}
			// both over the last five seconds, so a server shows how it runs now
			if(now - drawWindowStart >= 5000)
			{
				drawsPerSecond = drawWindowCount * 1000.0 / (now - drawWindowStart);
				frameAgeAtDrawMs = ageWindowCount == 0 ? -1 : ageWindowTotal / ageWindowCount;
				drawWindowStart = now;
				drawWindowCount = 0;
				ageWindowTotal = 0;
				ageWindowCount = 0;
			}
			return lastImage;
		}
	}

	/** As capture(), but waits up to waitMs for a first frame; for previews asked for over REST. */
	public BufferedImage captureWaiting(long maxAgeMs, long waitMs) throws Exception
	{
		BufferedImage img = capture(maxAgeMs);
		long deadline = System.currentTimeMillis() + waitMs;
		synchronized(lock)
		{
			long left;
			while(lastImage == null && !closed && SERVICE_NOT_RUNNING.equals(status.get("state")) == false && !"blocked".equals(status.get("state"))
					&& !"error".equals(status.get("state")) && (left = deadline - System.currentTimeMillis()) > 0)
			{
				lock.wait(left);
			}
			return closed ? null : lastImage != null ? lastImage : img;
		}
	}

	/** The latest frame's number, so a layer keys a frame once; -1 before the first. */
	public long frameSeq()
	{
		synchronized(lock)
		{
			return frameSeq;
		}
	}

	private void start()
	{
		synchronized(lock)
		{
			if(fetcher != null || closed)
			{
				return;
			}
			fetcher = new Thread(this::fetchLoop, "UrlOverlayFetch-" + settings.label());
			fetcher.setDaemon(true);
			fetcher.start();
		}
	}

	/** How long the service holds a frame request open waiting for a new frame. */
	static final long WAIT_MS = 1000;

	// Asks the service for each new frame as it is taken, decodes it here and publishes it for the draws.
	private void fetchLoop()
	{
		while(true)
		{
			synchronized(lock)
			{
				if(closed)
				{
					return;
				}
			}
			CaptureClient c = client.get();
			long pause = 0;
			try
			{
				Message m = fetch(c);
				if(!m.ok() && "Unknown capture".equals(m.str("error")))
				{
					// the service restarted and no longer knows this capture: open it again
					synchronized(lock)
					{
						remoteId = null;
					}
					continue;
				}
				if(took(m))
				{
					for(Runnable r: reloadListeners)
					{
						r.run();
					}
				}
				if(!m.ok())
				{
					pause = intervalMs;
				}
			}
			catch(CaptureClient.ServiceDown e)
			{
				synchronized(lock)
				{
					remoteId = null;
					Map<String, Object> s = new LinkedHashMap<>();
					s.put("state", SERVICE_NOT_RUNNING);
					s.put("lastError", e.getMessage());
					status = s;
					lock.notifyAll();
				}
				// the client fails fast while it backs off; no need to ask more often than that
				pause = 500;
			}
			catch(RuntimeException e)
			{
				pause = 1000;
			}
			if(pause > 0)
			{
				try
				{
					Thread.sleep(pause);
				}
				catch(InterruptedException e)
				{
					return;
				}
			}
		}
	}

	// opens the capture when needed, sends a changed delay, rate or interval, then asks for the next frame
	private Message fetch(CaptureClient c) throws CaptureClient.ServiceDown
	{
		String id;
		long since;
		int delay = pageDelay.getAsInt();
		double rate = reloadRate();
		long interval = intervalMs;
		synchronized(lock)
		{
			id = remoteId;
			since = frameSeq;
		}
		if(id == null)
		{
			PageRequest r = new PageRequest(settings.url(), settings.viewportWidth(), settings.viewportHeight(),
					settings.keyColor() == null ? 0x010203 : settings.keyColor().getRGB() & 0xffffff, settings.paintBackground(), settings.label(), delay, rate,
					interval, c.egress());
			Map<String, Object> h = Wire.header(Wire.OPEN);
			h.putAll(r.toMap());
			Message open = c.call(h, CALL_TIMEOUT_MS);
			if(!open.ok())
			{
				return open;
			}
			id = open.str("capture");
			synchronized(lock)
			{
				remoteId = id;
				sentDelay = delay;
				sentRate = rate;
				sentInterval = interval;
				reloadsSeen = 0;
				// a new capture numbers its frames from the start
				frameSeq = -1;
				since = -1;
			}
		}
		else if(delay != sentDelay || rate != sentRate || interval != sentInterval)
		{
			Map<String, Object> h = Wire.header(Wire.UPDATE);
			h.put("capture", id);
			h.put("pageDelayMs", delay);
			h.put("reloadRate", rate);
			h.put("intervalMs", interval);
			Message u = c.call(h, CALL_TIMEOUT_MS);
			if(!u.ok())
			{
				return u;
			}
			synchronized(lock)
			{
				sentDelay = delay;
				sentRate = rate;
				sentInterval = interval;
			}
		}
		Map<String, Object> h = Wire.header(Wire.FRAME);
		h.put("capture", id);
		h.put("since", since);
		h.put("waitMs", WAIT_MS);
		return c.call(h, WAIT_MS + CALL_TIMEOUT_MS);
	}

	/** Keeps what a frame answer brought, decoding a new frame here. @return whether the page reloaded since the last answer */
	@SuppressWarnings("unchecked")
	private boolean took(Message m)
	{
		BufferedImage img = null;
		long decode = -1;
		if(m.ok() && m.body().length > 0)
		{
			long t0 = System.nanoTime();
			try
			{
				img = ImageIO.read(new ByteArrayInputStream(m.body()));
			}
			catch(java.io.IOException e)
			{
				img = null;
			}
			decode = (System.nanoTime() - t0) / 1_000_000;
		}
		synchronized(lock)
		{
			Map<String, Object> s = new LinkedHashMap<>();
			if(m.header().get("status") instanceof Map<?, ?> st)
			{
				s.putAll((Map<String, Object>)st);
			}
			if(!m.ok())
			{
				s.put("state", "error");
				s.put("lastError", m.str("error"));
			}
			status = s;
			if(img != null)
			{
				lastImage = img;
				lastCaptureAt = Instant.now();
				frameSeq = m.num("seq", frameSeq + 1);
				frameCapturedAtMs = m.num("capturedAt", System.currentTimeMillis());
				screenshotMs = m.num("captureMs", -1);
				deliveryMs = System.currentTimeMillis() - frameCapturedAtMs;
				decodeMs = decode;
				frameBytes = m.body().length;
				framesReceived++;
			}
			lock.notifyAll();
			long reloads = m.num("reloads", reloadsSeen);
			boolean reloaded = reloads > reloadsSeen;
			reloadsSeen = reloads;
			return reloaded;
		}
	}

	public void close()
	{
		String id;
		synchronized(lock)
		{
			if(closed)
			{
				return;
			}
			closed = true;
			lock.notifyAll();
			id = remoteId;
			remoteId = null;
			lastImage = null;
			status = Map.of("state", "closed");
		}
		if(id != null)
		{
			Map<String, Object> h = Wire.header(Wire.CLOSE);
			h.put("capture", id);
			try
			{
				client.get().call(h, 5000);
			}
			catch(CaptureClient.ServiceDown e)
			{
				// the service closes captures nobody asks frames of
			}
		}
	}

	/** Capture health for the status endpoint, as the service last reported it, or why it cannot be reached. */
	public Map<String, Object> status()
	{
		synchronized(lock)
		{
			Map<String, Object> m = new LinkedHashMap<>(status);
			m.put("lastCaptureAt", lastCaptureAt == null ? m.get("lastCaptureAt") : lastCaptureAt.toString());
			// where a frame's time goes, service to draw
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("intervalMs", intervalMs);
			t.put("screenshotMs", screenshotMs < 0 ? null : screenshotMs);
			t.put("deliveryMs", deliveryMs < 0 ? null : deliveryMs);
			t.put("decodeMs", decodeMs < 0 ? null : decodeMs);
			t.put("frameBytes", frameBytes);
			t.put("framesReceived", framesReceived);
			t.put("frameSeq", frameSeq);
			t.put("draws", draws);
			t.put("drawsPerSecond", Math.round(drawsPerSecond * 10) / 10.0);
			t.put("frameAgeAtDrawMs", frameAgeAtDrawMs < 0 ? null : frameAgeAtDrawMs);
			m.put("timing", t);
			return m;
		}
	}

	public BufferedImage lastRawImage()
	{
		synchronized(lock)
		{
			return lastImage;
		}
	}

	public Instant lastCaptureAt()
	{
		synchronized(lock)
		{
			return lastCaptureAt;
		}
	}
}
