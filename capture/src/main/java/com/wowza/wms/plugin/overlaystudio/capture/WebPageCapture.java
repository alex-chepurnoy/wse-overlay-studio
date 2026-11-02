/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.awt.Color;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;
import com.wowza.wms.plugin.overlaystudio.egress.EgressRules;
import com.wowza.wms.plugin.overlaystudio.egress.EgressGuard;

/**
 * One webPage overlay object's page, in a Chrome it shares with up to pagesPerBrowser others (ChromeGroups), driven over
 * DevTools. Opened lazily, it loads the url through its own EgressGuard proxy, keeps its latest frame fresh by screencast
 * or by screenshots, whichever costs less for how the page paints, optionally reloads, and reports its health. A page
 * that is lost (its renderer crashed, or its Chrome exited) is closed and reopened with exponential backoff (1, 2, 4 ...
 * 60 s). Once closed it never opens a page again.
 */
public class WebPageCapture
{
	public static final double MIN_RELOAD_RATE = 1.0;
	static final long MAX_BACKOFF_MS = 60_000;
	private static final long BLOCKED_RETRY_MS = 30_000;

	// a hung page must not hold a reload thread for long
	static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
	/** The longest any one DevTools call for this page may take, other than a page load. */
	static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
	/** How often a running page's memory and address are checked. */
	static final long MEMORY_CHECK_MS = 10_000;

	/** How a page's frames are taken. */
	public enum Mode { screenshots, screencast }

	// not final: tests shorten them
	/** How long a probe counts the page's repaints, without a cap. */
	static long PROBE_MS = 2000;
	/** How often a page is probed again; pages change how they paint. */
	static final long PROBE_EVERY_MS = 5 * 60_000;
	/** A page that repaints more often than this per capture interval is captured by screenshots. */
	static final double CAST_MAX_REPAINTS_PER_INTERVAL = 1.5;
	/** A screencast that has sent nothing this long after it started falls back to screenshots. */
	static long CAST_FIRST_FRAME_MS = 5000;

	public enum State { idle, starting, running, reloading, error, blocked, closed }

	/**
	 * What identifies a capture; a change to any of these means a new browser. The label is only for logs and is
	 * left out of equality, so layers on different streams showing the same page share one browser.
	 */
	public record Settings(String url, int viewportWidth, int viewportHeight, Color keyColor, boolean paintBackground, String label)
	{
		String backgroundCss()
		{
			return "rgb(" + keyColor.getRed() + "," + keyColor.getGreen() + "," + keyColor.getBlue() + ")";
		}

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

	// screencast acknowledgments: tiny sends, on a scheduler of their own so nothing slow ever delays them
	private static final ScheduledExecutorService ACKS = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "WebPageAck");
		thread.setDaemon(true);
		return thread;
	});

	private static final ScheduledExecutorService reloadExecutor = Executors.newScheduledThreadPool(2, r -> {
		Thread thread = new Thread(r, "WebPageReload");
		thread.setDaemon(true);
		return thread;
	});

	// page loads for reloads: each can block for the page load timeout, so they get threads of their own, as many as
	// pages are reloading (at most one per page)
	private static final java.util.concurrent.ExecutorService NAVIGATE = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r, "WebPageNavigate");
		thread.setDaemon(true);
		return thread;
	});

	/** What one browser is for: the page, and the egress rules of the WSE that asked for it. */
	public record Key(Settings settings, EgressRules rules)
	{
	}

	/** Open captures by page and rules; a page left by its last user stays open this long in case it is used again. */
	public static final long SHARE_GRACE_MS = 15000;
	public static final CapturePool<Key, WebPageCapture> POOL = new CapturePool<>(k -> new WebPageCapture(k.settings(), k.rules().rules(), () -> -1, null).sharedOn(k.rules()),
			WebPageCapture::close, SHARE_GRACE_MS, reloadExecutor);

	private final Log log = Log.get();
	private final Object lock = new Object();
	private final Settings settings;
	private final AddressRules rules;
	// what this page's Chrome is shared on; the rules themselves unless the pool says what they were built from
	private volatile Object rulesKey;
	private volatile IntSupplier pageDelay;
	private final java.util.List<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final String captureId = UUID.randomUUID().toString().substring(0, 8);

	// the page in its Chrome, while it is open
	private PageDriver webDriver = null;
	private ChromeGroups.Group group = null;
	private EgressGuard guard = null;
	private boolean slotHeld = false;
	// how frames are taken now, and the probe that decides it
	private Mode mode = Mode.screenshots;
	private boolean probeDue = false;
	private boolean probing = false;
	private long probeEndsAt = 0;
	private int probeFrames = 0;
	private long nextProbeAt = 0;
	private double repaintsPerSecond = -1;
	private long castStartedAt = 0;
	// a screencast is running on the page
	private boolean casting = false;
	private boolean skipFirstProbeFrame = false;
	// a probe frame not passed on yet, because one was passed on less than an interval ago; kept at the probe's end
	private byte[] probeHeld = null;
	private long probeStartedAt = 0;
	// finishReload is stopping the screencast; a new one waits until that is sent
	private boolean stoppingCast = false;
	private boolean castHasFrame = false;
	// the capped screencast's acknowledgment: one per interval, for the latest frame
	private boolean ackPending = false;
	private int latestFrameSession = 0;
	private long lastAckAt = 0;
	// a thread is starting Chrome outside the lock; frames meanwhile are the last one
	private boolean opening = false;
	private volatile long memoryCheckedAt = 0;
	private int memoryRestarts = 0;
	private long overSince = 0;
	private final java.util.List<Long> restartTimes = new java.util.ArrayList<>();
	// when a browser found off its page last caused a reload
	private long offPageReloadAt = 0;
	// set while a screenshot is taken outside the lock
	private boolean shooting = false;

	// the latest screenshot as Chrome encoded it, sent to the module as it came; never decoded here
	private byte[] lastPng = null;
	// one more for every new screenshot, so the module asks only for frames it has not seen
	private long seq = 0;
	private long capturedAtMs = 0;
	// how often the background loop takes a screenshot; the module sends its overlay's refresh
	private volatile long intervalMs = 1000;
	private Thread loop = null;
	// between navigating and the page settling, captures return the last frame instead of the page's own background
	private boolean reloading = false;
	private boolean closed = false;
	private boolean sizeWarned = false;
	private double reloadRate = 0.0;
	private ScheduledFuture<?> reloadFuture = null;

	private State state = State.idle;
	private Instant lastCaptureAt = null;
	private long lastCaptureMs = -1;
	private String lastError = null;
	private Instant lastErrorAt = null;
	private int failures = 0;
	private long nextOpenAt = 0;
	private Map<String, Object> lastBlock = null;

	public WebPageCapture(Settings settings, AddressRules rules, IntSupplier pageDelay, Runnable onReloaded)
	{
		this.settings = settings;
		this.rules = rules;
		this.rulesKey = rules;
		this.pageDelay = pageDelay;
		if(onReloaded != null)
		{
			reloadListeners.add(onReloaded);
		}
	}

	/**
	 * Shares a Chrome only with pages whose egress settings equal these. The pool passes the module's settings record,
	 * which compares by value; the AddressRules built from it do not.
	 */
	WebPageCapture sharedOn(Object key)
	{
		rulesKey = key;
		return this;
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

	/** Default form: the key color is rgb(1,2,3) and the page background is painted with it. */
	public WebPageCapture(String url, int width, int height, IntSupplier pageDelay, Runnable onReloaded)
	{
		this(new Settings(url, width, height, new Color(1, 2, 3), true, url), AddressRules.defaults(), pageDelay, onReloaded);
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
	 * @return a screenshot of the page, opening the browser on first use; the last good capture while reloading or
	 * while waiting to reopen a failed browser; null once closed, or when nothing has been captured yet
	 */
	public byte[] capture() throws Exception
	{
		return capture(0);
	}

	/**
	 * As capture(), but returns the last screenshot when it is younger than maxAgeMs, so layers sharing this page on
	 * several streams take one screenshot per interval between them, not one each.
	 */
	public byte[] capture(long maxAgeMs) throws Exception
	{
		boolean open = false;
		PageDriver driver = null;
		synchronized(lock)
		{
			if(closed)
			{
				return null;
			}
			if(maxAgeMs > 0 && webDriver != null && lastPng != null && lastCaptureAt != null
					&& java.time.Duration.between(lastCaptureAt, Instant.now()).toMillis() < maxAgeMs)
			{
				return lastPng;
			}
			if(reloading)
			{
				return lastPng;
			}
			if(webDriver == null)
			{
				if(opening || System.currentTimeMillis() < nextOpenAt)
				{
					return lastPng;
				}
				AddressRules.Decision d = precheck();
				if(!d.allowed())
				{
					state = State.blocked;
					setError("Blocked: " + d.rule() + " " + d.address());
					nextOpenAt = System.currentTimeMillis() + BLOCKED_RETRY_MS;
					return null;
				}
				opening = true;
				state = State.starting;
				open = true;
			}
			if(!open)
			{
				// a screencast keeps the frame fresh by itself; one screenshot at a time, and another caller meanwhile gets
				// the last one
				if(mode == Mode.screencast || probing || shooting)
				{
					return lastPng;
				}
				shooting = true;
				driver = webDriver;
			}
		}
		if(open)
		{
			return openThenCapture(maxAgeMs);
		}
		// Chrome is asked outside the lock: a screenshot takes 50 to 300 ms, and frame answers, status and closing
		// must not wait on it
		try
		{
			return screenshot(driver);
		}
		finally
		{
			synchronized(lock)
			{
				shooting = false;
			}
		}
	}

	/**
	 * Checks a running page every MEMORY_CHECK_MS: that it is still on its own address, and that its Chrome is within its
	 * memory limit. Runs on the capture's loop, outside the lock.
	 */
	private void checkPage()
	{
		PageDriver driver;
		ChromeGroups.Group g;
		synchronized(lock)
		{
			long now = System.currentTimeMillis();
			if(closed || webDriver == null || reloading || now - memoryCheckedAt < MEMORY_CHECK_MS)
			{
				return;
			}
			memoryCheckedAt = now;
			driver = webDriver;
			g = group;
		}
		Object href;
		try
		{
			href = driver.evaluate("location.href", CALL_TIMEOUT.toMillis());
		}
		catch(java.util.concurrent.TimeoutException e)
		{
			// a page that does not answer is hung: a screencast would only keep showing its last frame
			synchronized(lock)
			{
				if(webDriver == driver && !closed)
				{
					fail(new java.util.concurrent.TimeoutException("The page did not answer in " + CALL_TIMEOUT.toSeconds() + " s and was opened again"));
				}
			}
			return;
		}
		catch(Exception e)
		{
			href = null;
		}
		String off = href instanceof String u ? offPageAddress(u) : null;
		if(off != null)
		{
			reloadNow("the browser was on " + off + ", not the page");
		}
		ChromeBrowser chrome = g == null ? null : g.chrome();
		if(chrome == null)
		{
			return;
		}
		// measured outside the lock: listing processes can take a second on Windows
		long rss = CaptureService.get().treeRss(chrome.process());
		ChromeGroups groups = ChromeGroups.get();
		int pages = Math.max(1, groups.pages(g).size());
		WebPageCapture charged = groups.memoryVictim(g, rss, WebPageCapture::heapBytes);
		// the page charged with a Chrome over its limit is measured against one page's share of it, the others not at all
		checkMemory(charged == this ? rss / pages : -1L);
	}

	/** This page's JavaScript heap in bytes, or -1. */
	private long heapBytes()
	{
		PageDriver driver;
		synchronized(lock)
		{
			driver = webDriver;
		}
		return driver == null ? -1 : driver.heapBytes();
	}

	/** The address when it is not a web page (http or https), else null. */
	static String offPageAddress(String url)
	{
		if(url == null)
		{
			return null;
		}
		String u = url.trim().toLowerCase(java.util.Locale.ROOT);
		return u.startsWith("http://") || u.startsWith("https://") ? null : (url.isBlank() ? "an empty page" : url.trim());
	}

	/** Not reloaded again for this long after a reload of a browser found off its page, so a page that keeps leaving does not loop. */
	static final long OFF_PAGE_RETRY_MS = 30_000;

	/**
	 * Loads the page's address again now, whatever the reload rate: when the browser is found off its page, or when
	 * asked from the page. Viewers keep the last picture until the page has loaded and waited its delay.
	 */
	public void reloadNow(String why)
	{
		synchronized(lock)
		{
			if(closed || webDriver == null || reloading)
			{
				return;
			}
			long now = System.currentTimeMillis();
			if(why.startsWith("the browser was on") && now - offPageReloadAt < OFF_PAGE_RETRY_MS)
			{
				return;
			}
			if(why.startsWith("the browser was on"))
			{
				offPageReloadAt = now;
			}
			cancelReload();
		}
		log.warn("OverlayStudio capture: reloading " + settings.label() + " now: " + why);
		reloadExecutor.execute(() -> startReload(true));
	}

	/** Why a browser using rssBytes must be restarted under a limit of capMb, or null when it is within it or unknown. */
	static String overMemory(long rssBytes, int capMb)
	{
		if(rssBytes < 0 || rssBytes <= capMb * 1024L * 1024L)
		{
			return null;
		}
		return "Chrome used " + (rssBytes / (1024 * 1024)) + " MB, over the " + ServiceConfig.MAX_BROWSER_MB + " limit of " + capMb
				+ " MB, and was restarted";
	}

	/** How long a browser must stay over the limit before it is restarted, so one spike does not restart it. */
	static final long OVER_MEMORY_MS = 20_000;
	/** At most this many memory restarts in RESTART_WINDOW_MS; after that the browser keeps running and it is reported. */
	static final int MAX_MEMORY_RESTARTS = 3;
	static final long RESTART_WINDOW_MS = 10 * 60_000;

	/** Restarts the browser when it uses more memory than allowed. @return true when it was restarted */
	boolean checkMemory(long rssBytes)
	{
		return checkMemory(rssBytes, System.currentTimeMillis());
	}

	boolean checkMemory(long rssBytes, long now)
	{
		int cap = CaptureService.get().maxBrowserMb();
		String why = overMemory(rssBytes, cap);
		synchronized(lock)
		{
			if(why == null)
			{
				overSince = 0;
				return false;
			}
			if(closed || webDriver == null)
			{
				return false;
			}
			if(overSince == 0)
			{
				overSince = now;
			}
			if(now - overSince < OVER_MEMORY_MS)
			{
				return false;
			}
			restartTimes.removeIf(t -> now - t > RESTART_WINDOW_MS);
			if(restartTimes.size() >= MAX_MEMORY_RESTARTS)
			{
				// restarting again would only loop: the page needs more than the limit
				String msg = "Chrome uses " + (rssBytes / (1024 * 1024)) + " MB, over the " + ServiceConfig.MAX_BROWSER_MB + " limit of " + cap
						+ " MB, after " + MAX_MEMORY_RESTARTS + " restarts in 10 minutes; it is left running. Raise " + ServiceConfig.MAX_BROWSER_MB
						+ " in capture.properties or use a lighter page.";
				if(!msg.equals(lastError))
				{
					log.warn("OverlayStudio capture: " + msg + " for " + settings.label());
					setError(msg);
				}
				return false;
			}
			log.warn("OverlayStudio capture: " + why + " for " + settings.label());
			if(group != null)
			{
				// the page opens again in another Chrome; this one closes once its other pages have moved too, which frees
				// whatever its browser process holds that belongs to no page
				ChromeGroups.get().drain(group, "over its memory limit");
			}
			shutdownBrowser();
			memoryRestarts++;
			restartTimes.add(now);
			overSince = 0;
			setError(why);
			state = State.error;
			// reopened at the next draw; the stream keeps the last picture meanwhile
			nextOpenAt = now + 1000;
		}
		return true;
	}

	/** Stands in a page for a browser's, for tests of what happens to a running page. It is probed at once. */
	void attachForTest(PageDriver driver)
	{
		synchronized(lock)
		{
			webDriver = driver;
			watch(driver);
		}
	}

	/** As attachForTest, for a page kept on screenshots: no probe. */
	void attachForScreenshots(PageDriver driver)
	{
		synchronized(lock)
		{
			webDriver = driver;
			watch(driver);
			nextProbeAt = Long.MAX_VALUE;
		}
	}

	// Caller must hold lock.
	// Takes a screenshot of driver without the lock, then records it under the lock, unless the browser was closed or
	// replaced meanwhile.
	private byte[] screenshot(PageDriver driver)
	{
		long t0 = System.nanoTime();
		byte[] png;
		try
		{
			png = driver.screenshot(CALL_TIMEOUT.toMillis());
		}
		catch(Exception e)
		{
			synchronized(lock)
			{
				if(webDriver == driver && !closed)
				{
					fail(e);
				}
				return lastPng;
			}
		}
		synchronized(lock)
		{
			if(webDriver != driver || closed)
			{
				return lastPng;
			}
			lastCaptureMs = (System.nanoTime() - t0) / 1_000_000;
			keep(png);
			return lastPng;
		}
	}

	// Caller must hold lock. Records a new frame, from a screenshot or the screencast.
	private void keep(byte[] png)
	{
		{
			lastCaptureAt = Instant.now();
			lastPng = png;
			seq++;
			capturedAtMs = System.currentTimeMillis();
			lock.notifyAll();
			failures = 0;
			state = State.running;
			int[] size = pngSize(png);
			int w = settings.viewportWidth(), h = settings.viewportHeight();
			if(size != null && w > 0 && h > 0 && !sizeWarned && (size[0] != w || size[1] != h))
			{
				sizeWarned = true;
				log.warn("OverlayStudio capture: capture is " + size[0] + "x" + size[1] + ", expected " + w + "x" + h
						+ "; it will be scaled to fit for " + settings.label());
			}
		}
	}

	/** The rate the page reloads at now, in seconds; 0 when it does not reload. */
	double reloadRate()
	{
		synchronized(lock)
		{
			return reloadRate;
		}
	}

	/**
	 * Starts Chrome and loads the page without holding the lock, so status, close and the layers sharing this page are
	 * not held up for the launch and page load, then takes the first screenshot.
	 */
	private byte[] openThenCapture(long maxAgeMs) throws Exception
	{
		Browser b;
		try
		{
			b = openBrowser();
		}
		catch(Exception e)
		{
			synchronized(lock)
			{
				opening = false;
				if(closed)
				{
					return null;
				}
				fail(e);
				if(e instanceof IllegalStateException)
				{
					// configuration problems (no driver, browser limit) are reported, not retried in a tight loop
					throw e;
				}
				return lastPng;
			}
		}
		synchronized(lock)
		{
			opening = false;
			if(!closed)
			{
				webDriver = b.driver;
				group = b.group;
				guard = b.guard;
				slotHeld = b.slotHeld;
				watch(b.driver);
				// the first frame is a screenshot; the probe then picks how the frames after it are taken
				mode = Mode.screenshots;
				probeDue = true;
				b = null;
			}
		}
		if(b != null)
		{
			// closed while Chrome started
			release(b);
			return null;
		}
		return capture(maxAgeMs);
	}

	// Caller must hold lock. A page that is lost (its renderer crashed, or its Chrome went away) fails, which closes it
	// and opens it again after the backoff. Handled off the DevTools thread that reports it.
	private void watch(PageDriver driver)
	{
		driver.onLost(why -> NAVIGATE.execute(() -> {
			synchronized(lock)
			{
				if(webDriver == driver && !closed)
				{
					fail(new IllegalStateException(why));
				}
			}
		}));
	}

	/**
	 * Reload the url every rate seconds, measured from when the previous reload's page settled (after pageDelay).
	 * A rate of 0 or less stops reloading; a positive rate below MIN_RELOAD_RATE is raised to it.
	 */
	public void reloadEvery(double rate)
	{
		synchronized(lock)
		{
			if(closed)
			{
				return;
			}
			double newRate = rate > 0 ? Math.max(rate, MIN_RELOAD_RATE) : 0.0;
			if(newRate == reloadRate)
			{
				return;
			}
			if(newRate != rate && rate > 0)
			{
				log.warn("OverlayStudio capture: reloadRate " + rate + " is below the minimum, using " + MIN_RELOAD_RATE + " for " + settings.label());
			}
			reloadRate = newRate;
			cancelReload();
			// an in-flight reload re-arms itself with the new rate when it finishes
			if(reloadRate > 0 && !reloading)
			{
				scheduleReload();
			}
		}
	}

	public void close()
	{
		synchronized(lock)
		{
			if(closed)
			{
				return;
			}
			closed = true;
			cancelReload();
			shutdownBrowser();
			lastPng = null;
			lock.notifyAll();
			state = State.closed;
		}
	}

	// ---- status ---------------------------------------------------------------------------------------------------

	public Map<String, Object> status()
	{
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		ChromeGroups.Group g;
		synchronized(lock)
		{
			m.put("state", state.name());
			m.put("lastCaptureAt", lastCaptureAt == null ? null : lastCaptureAt.toString());
			m.put("lastCaptureMs", lastCaptureMs < 0 ? null : lastCaptureMs);
			m.put("lastError", lastError);
			m.put("lastErrorAt", lastErrorAt == null ? null : lastErrorAt.toString());
			m.put("lastBlocked", lastBlock);
			m.put("failures", failures);
			m.put("retryInMs", webDriver == null && nextOpenAt > System.currentTimeMillis() ? nextOpenAt - System.currentTimeMillis() : null);
			m.put("sandbox", "on");
			m.put("memoryRestarts", memoryRestarts);
			putMode(m);
			g = group;
		}
		Map<String, Object> chrome = new java.util.LinkedHashMap<>();
		ChromeBrowser browser = g == null ? null : g.chrome();
		boolean alive = browser != null && browser.alive();
		chrome.put("state", alive ? "alive" : (g == null ? "none" : "exited"));
		chrome.put("pid", alive ? browser.process().pid() : null);
		long rss = alive ? CaptureService.get().treeRss(browser.process()) : -1L;
		int pages = g == null ? 1 : Math.max(1, ChromeGroups.get().pages(g).size());
		// this page's even share of the Chrome it is in, and the whole of it
		chrome.put("rssBytes", rss < 0 ? null : rss / pages);
		chrome.put("browserRssBytes", rss < 0 ? null : rss);
		chrome.put("pagesInBrowser", g == null ? null : pages);
		m.put("chrome", chrome);
		return m;
	}

	// Caller must hold lock.
	private void putMode(Map<String, Object> m)
	{
		m.put("captureMode", webDriver == null ? null : probing ? "probing" : mode.name());
		m.put("repaintsPerSecond", repaintsPerSecond < 0 ? null : Math.round(repaintsPerSecond * 10) / 10.0);
	}

	/** The last screenshot as Chrome encoded it, or null. */
	public byte[] lastPng()
	{
		synchronized(lock)
		{
			return lastPng;
		}
	}

	/** Width and height from a PNG's header, without decoding it; null when it is not a PNG. */
	static int[] pngSize(byte[] png)
	{
		if(png == null || png.length < 24 || (png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G')
		{
			return null;
		}
		java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(png, 16, 8);
		return new int[] { b.getInt(), b.getInt() };
	}

	/** The latest frame's number; 0 before the first. */
	public long seq()
	{
		synchronized(lock)
		{
			return seq;
		}
	}

	/** When the latest frame was taken, in epoch milliseconds. */
	public long capturedAtMs()
	{
		synchronized(lock)
		{
			return capturedAtMs;
		}
	}

	/** How long the latest screenshot took Chrome, in milliseconds, or -1. */
	public long captureMs()
	{
		synchronized(lock)
		{
			return lastCaptureMs;
		}
	}

	/**
	 * Waits up to waitMs for a frame newer than since. @return the latest frame's number, which is still since when
	 * nothing new came, or the capture closed
	 */
	public long awaitFrame(long since, long waitMs) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
		synchronized(lock)
		{
			long left;
			while(seq <= since && !closed && (left = deadline - System.currentTimeMillis()) > 0)
			{
				lock.wait(left);
			}
			return seq;
		}
	}

	/** How often the page's frame is renewed, from the overlay's refresh; at least 50 ms. */
	public void interval(long ms)
	{
		long v = Math.max(50, ms);
		synchronized(lock)
		{
			if(v != intervalMs && webDriver != null)
			{
				// which mode costs less depends on the interval
				probeDue = true;
			}
			intervalMs = v;
		}
	}

	/**
	 * Starts the loop that keeps this page's latest frame fresh, once. Frames are taken here, never inside a request,
	 * so a request for a frame only hands over bytes.
	 */
	public void startLoop()
	{
		synchronized(lock)
		{
			if(loop != null || closed)
			{
				return;
			}
			loop = Thread.ofVirtual().name("capture-" + captureId).start(this::loop);
		}
	}

	private void loop()
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
			long t0 = System.currentTimeMillis();
			try
			{
				capture(0);
				tick();
				checkPage();
			}
			catch(Exception e)
			{
				// recorded in status by capture; the next attempt waits for the backoff
			}
			long rest = intervalMs - (System.currentTimeMillis() - t0);
			synchronized(lock)
			{
				// a probe ends on time, whatever the interval
				if(probing)
				{
					rest = Math.min(rest, probeEndsAt - System.currentTimeMillis());
				}
			}
			try
			{
				Thread.sleep(Math.max(10, rest));
			}
			catch(InterruptedException e)
			{
				return;
			}
		}
	}

	/**
	 * Moves the page's capture mode along, once per loop: starts a probe when one is due, ends it and picks the mode,
	 * falls back to screenshots when a screencast sends nothing, and keeps a running screencast's frame current: when it
	 * sends nothing the page has not changed. DevTools is asked outside the lock.
	 */
	private void tick()
	{
		PageDriver driver;
		boolean start = false, stop = false;
		int w = settings.viewportWidth(), h = settings.viewportHeight();
		synchronized(lock)
		{
			driver = webDriver;
			long now = System.currentTimeMillis();
			if(closed || driver == null || reloading)
			{
				return;
			}
			if(probing)
			{
				if(now < probeEndsAt)
				{
					return;
				}
				probing = false;
				if(probeHeld != null)
				{
					keep(probeHeld);
					probeHeld = null;
				}
				// over the time the probe really ran, which a slow loop can stretch past PROBE_MS
				repaintsPerSecond = probeFrames * 1000.0 / Math.max(PROBE_MS, now - probeStartedAt);
				nextProbeAt = now + PROBE_EVERY_MS;
				Mode was = mode;
				if(repaintsPerSecond * intervalMs / 1000.0 <= CAST_MAX_REPAINTS_PER_INTERVAL)
				{
					// castHasFrame and castStartedAt stay as the screencast left them: a page that does not change sends no
					// frame during a probe of a screencast already running, and that is no reason to fall back
					mode = Mode.screencast;
					lastAckAt = now;
					lastCaptureMs = -1;
				}
				else
				{
					mode = Mode.screenshots;
					stop = true;
					casting = false;
				}
				if(mode != was)
				{
					log.info("OverlayStudio capture: " + settings.label() + " repaints " + Math.round(repaintsPerSecond * 10) / 10.0 + " times a second; capturing it by "
							+ mode + " every " + intervalMs + " ms");
				}
			}
			else if(probeDue || now >= nextProbeAt)
			{
				if(stoppingCast)
				{
					return;
				}
				probeDue = false;
				probing = true;
				probeFrames = 0;
				probeHeld = null;
				probeStartedAt = now;
				probeEndsAt = now + PROBE_MS;
				if(!casting)
				{
					casting = true;
					start = true;
					castStartedAt = now;
					castHasFrame = false;
					// a new screencast sends one frame at once, whether or not the page repaints
					skipFirstProbeFrame = true;
				}
			}
			else if(mode == Mode.screencast)
			{
				if(!castHasFrame && now - castStartedAt >= CAST_FIRST_FRAME_MS)
				{
					log.warn("OverlayStudio capture: the screencast of " + settings.label() + " sent nothing in " + CAST_FIRST_FRAME_MS / 1000 + " s; using screenshots");
					mode = Mode.screenshots;
					casting = false;
					stop = true;
				}
				else if(castHasFrame)
				{
					// the last frame is still the page: a screencast sends one whenever it changes
					lastCaptureAt = Instant.now();
					state = State.running;
				}
			}
		}
		if(stop)
		{
			driver.stopScreencast();
		}
		if(start)
		{
			try
			{
				driver.startScreencast(w, h, f -> onFrame(driver, f));
			}
			catch(Exception e)
			{
				synchronized(lock)
				{
					if(webDriver == driver && !closed)
					{
						// screenshots work without it
						log.warn("OverlayStudio capture: no screencast for " + settings.label() + " (" + summarize(e) + "); using screenshots");
						casting = false;
						probing = false;
						mode = Mode.screenshots;
						nextProbeAt = System.currentTimeMillis() + PROBE_EVERY_MS;
					}
				}
			}
		}
	}

	/**
	 * A screencast frame. It is kept unless the page is reloading. While probing every frame is acknowledged at once, so
	 * Chrome sends one for every repaint; after that one acknowledgment per interval, for the latest frame, caps the rate.
	 */
	private void onFrame(PageDriver driver, PageDriver.Frame f)
	{
		int ackNow = -1;
		synchronized(lock)
		{
			if(closed || webDriver != driver || !casting)
			{
				return;
			}
			long now = System.currentTimeMillis();
			// while probing, frames come as fast as the page repaints; at most one per interval is kept, so the module is
			// not sent more than it asked for
			if(!reloading)
			{
				if(!probing || !castHasFrame || now - capturedAtMs >= intervalMs)
				{
					keep(f.png());
					lastCaptureMs = -1;
					castHasFrame = true;
					probeHeld = null;
				}
				else
				{
					// the page's latest picture is never thrown away: a page that settles during the probe sends no more
					probeHeld = f.png();
				}
			}
			if(probing || mode != Mode.screencast)
			{
				if(skipFirstProbeFrame)
				{
					skipFirstProbeFrame = false;
				}
				else
				{
					probeFrames++;
				}
				ackNow = f.sessionId();
			}
			else
			{
				latestFrameSession = f.sessionId();
				if(!ackPending)
				{
					ackPending = true;
					long wait = Math.max(0, intervalMs - (System.currentTimeMillis() - lastAckAt));
					ACKS.schedule(() -> ack(driver), wait, TimeUnit.MILLISECONDS);
				}
			}
		}
		if(ackNow >= 0)
		{
			driver.ackFrame(ackNow);
		}
	}

	private void ack(PageDriver driver)
	{
		int sid;
		synchronized(lock)
		{
			ackPending = false;
			if(closed || webDriver != driver || !casting)
			{
				return;
			}
			sid = latestFrameSession;
			lastAckAt = System.currentTimeMillis();
		}
		driver.ackFrame(sid);
	}

	/** Status without looking up Chrome's processes, for every frame answer: that lookup can take a second on Windows. */
	public Map<String, Object> quickStatus()
	{
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		synchronized(lock)
		{
			m.put("state", state.name());
			m.put("lastCaptureAt", lastCaptureAt == null ? null : lastCaptureAt.toString());
			m.put("lastCaptureMs", lastCaptureMs < 0 ? null : lastCaptureMs);
			m.put("lastError", lastError);
			m.put("lastErrorAt", lastErrorAt == null ? null : lastErrorAt.toString());
			m.put("lastBlocked", lastBlock);
			m.put("failures", failures);
			m.put("retryInMs", webDriver == null && nextOpenAt > System.currentTimeMillis() ? nextOpenAt - System.currentTimeMillis() : null);
			m.put("sandbox", "on");
			m.put("memoryRestarts", memoryRestarts);
			m.put("intervalMs", intervalMs);
			putMode(m);
		}
		return m;
	}

	public Instant lastCaptureAt()
	{
		synchronized(lock)
		{
			return lastCaptureAt;
		}
	}

	public State state()
	{
		synchronized(lock)
		{
			return state;
		}
	}

	// ---- browser ------------------------------------------------------------------------------------------------

	private AddressRules.Decision precheck()
	{
		String host;
		try
		{
			URI u = new URI(settings.url());
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			if(!scheme.equals("http") && !scheme.equals("https"))
			{
				return new AddressRules.Decision(false, "scheme", scheme);
			}
			host = u.getHost();
			int port = u.getPort() > 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
			AddressRules.Decision pd = rules.checkPort(port);
			if(host != null && !pd.allowed())
			{
				onBlocked(host, pd);
				return pd;
			}
		}
		catch(Exception e)
		{
			return new AddressRules.Decision(false, "bad-url", settings.url());
		}
		if(host == null)
		{
			return new AddressRules.Decision(false, "bad-url", settings.url());
		}
		AddressRules.Decision d = rules.check(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
		if(!d.allowed())
		{
			onBlocked(host, d);
		}
		return d;
	}

	// One open page with what keeps it running: its Chrome group, egress proxy and page slot.
	private static final class Browser
	{
		PageDriver driver;
		ChromeGroups.Group group;
		EgressGuard guard;
		boolean slotHeld;
	}

	// Opens the page in a Chrome with room for it and loads it; on failure frees whatever it had taken. Runs without the
	// lock.
	private Browser openBrowser() throws Exception
	{
		String chrome = CaptureSettings.requireChrome();
		CaptureService service = CaptureService.get();
		if(!service.tryAcquire())
		{
			throw new IllegalStateException("Browser limit reached: " + service.maxCaptures() + " sessions (" + ServiceConfig.MAX_CAPTURES + " in the capture service's configuration)");
		}
		Browser b = new Browser();
		b.slotHeld = true;
		try
		{
			b.guard = new EgressGuard(rules, captureId, this::onBlocked);
			int proxyPort = b.guard.start();
			log.info("OverlayStudio capture: opening " + settings.label() + " (" + captureId + ")");
			b.group = ChromeGroups.get().join(this, rules, rulesKey, g -> startChrome(g, chrome));
			BrowserPage page;
			try
			{
				page = b.group.chrome().openPage(proxyPort, settings.viewportWidth(), settings.viewportHeight());
			}
			catch(Exception e)
			{
				// a Chrome that cannot open a page is no use to the next one either
				ChromeGroups.get().drain(b.group, "it could not open a page: " + summarize(e));
				throw e;
			}
			b.driver = page;
			int w = settings.viewportWidth(), h = settings.viewportHeight();
			if(w > 0 && h > 0)
			{
				page.viewport(w, h);
			}
			page.navigate(settings.url(), PAGE_LOAD_TIMEOUT.toMillis());
			int delay = pageDelay.getAsInt();
			if(delay > 0)
			{
				Thread.sleep(delay);
			}
			applyTransparentBackground(page);
			return b;
		}
		catch(Exception e)
		{
			release(b);
			throw e;
		}
	}

	/**
	 * Starts the Chrome for a new group: its own profile, and a browser-wide guard with the group's rules for Chrome's own
	 * traffic. A Chrome that does not start is reported as the sandbox problem it almost always is.
	 */
	private ChromeBrowser startChrome(ChromeGroups.Group g, String chrome) throws Exception
	{
		CaptureService service = CaptureService.get();
		g.guard = new EgressGuard(g.rules, "chrome-" + g.id, (host, d) -> {
			if(AddressRules.RULE_CHROME_SERVICE.equals(d.rule()))
			{
				service.countChromeServiceBlock();
			}
			else
			{
				service.recordBlock("Chrome " + g.id, host, d);
			}
		});
		int proxyPort = g.guard.start();
		g.profileDir = service.newProfileDir("b-" + g.id);
		log.info("OverlayStudio capture: starting Chrome " + g.id);
		try
		{
			List<String> switches = chromeArguments(settings, proxyPort, g.profileDir, CaptureService.isLinux());
			if(CaptureSettings.isHeadlessShell(chrome))
			{
				// chrome-headless-shell is headless by itself and has no other mode
				switches.remove("--headless=new");
			}
			ChromeBrowser b = ChromeBrowser.start(chrome, switches, g.profileDir);
			service.sandboxStarted(null);
			return b;
		}
		catch(Exception e)
		{
			String why = CaptureService.sandboxStartFailure(summarize(e), CaptureService.isLinux(), CaptureService.restrictUserns());
			service.sandboxStarted(why);
			throw new IllegalStateException(why, e);
		}
	}

	/**
	 * The switches for a Chrome holding pages; proxyPort is its browser-wide guard, for Chrome's own traffic. Each page's
	 * own traffic goes through its own guard, set on its browser context. There is never a --no-sandbox: the service has
	 * no way to ask for one.
	 */
	static List<String> chromeArguments(Settings s, int proxyPort, Path profileDir, boolean linux)
	{
		List<String> a = new ArrayList<>();
		a.add("--headless=new");
		if(s.viewportWidth() > 0 && s.viewportHeight() > 0)
		{
			a.add("--window-size=" + s.viewportWidth() + "," + s.viewportHeight());
		}
		a.add("--hide-scrollbars");
		a.add("--disable-infobars");
		a.add("--disable-gpu");
		a.add("--disable-extensions");
		a.add("--no-first-run");
		a.add("--no-default-browser-check");
		a.add("--disable-background-networking");
		a.add("--disable-sync");
		a.add("--disable-component-update");
		a.add("--metrics-recording-only");
		// with only the switches above, Chrome still contacts Google update, sign-in, time,
		// optimization-guide and component-download hosts on its own. These turn off the features behind them.
		a.add("--disable-features=Translate,MediaRouter,OptimizationHints,OptimizationHintsFetching,OptimizationGuideModelDownloading,OptimizationTargetPrediction,AutofillServerCommunication,CertificateTransparencyComponentUpdater");
		a.add("--disable-client-side-phishing-detection");
		a.add("--disable-component-extensions-with-background-pages");
		a.add("--disable-default-apps");
		a.add("--disable-domain-reliability");
		a.add("--disable-breakpad");
		a.add("--no-pings");
		// a capture browser is never in front; keep its timers and rendering at full rate
		a.add("--disable-background-timer-throttling");
		a.add("--disable-renderer-backgrounding");
		a.add("--disable-backgrounding-occluded-windows");
		if(profileDir != null)
		{
			a.add("--user-data-dir=" + profileDir);
		}
		a.add("--proxy-server=http://127.0.0.1:" + proxyPort);
		// Chrome sends loopback requests directly unless this removes the implicit bypass
		a.add("--proxy-bypass-list=<-loopback>");
		a.add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
		if(linux)
		{
			a.add("--disable-dev-shm-usage");
		}
		return a;
	}

	private void onBlocked(String host, AddressRules.Decision d)
	{
		if(AddressRules.RULE_CHROME_SERVICE.equals(d.rule()))
		{
			// Chrome's own background traffic, not the page: counted only, so it does not crowd out real blocks
			CaptureService.get().countChromeServiceBlock();
			return;
		}
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("at", Instant.now().toString());
		m.put("host", host);
		m.put("address", d.address());
		m.put("rule", d.rule());
		synchronized(lock)
		{
			lastBlock = m;
		}
		CaptureService.get().recordBlock(settings.label(), host, d);
	}

	// Caller must hold lock.
	private void fail(Exception e)
	{
		failures++;
		setError(summarize(e));
		state = State.error;
		long backoff = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(failures - 1, 16));
		nextOpenAt = System.currentTimeMillis() + backoff;
		log.warn("OverlayStudio capture: " + lastError + " for " + settings.label() + "; retrying in " + backoff + " ms");
		shutdownBrowser();
	}

	// Caller must hold lock.
	private void setError(String message)
	{
		lastError = message;
		lastErrorAt = Instant.now();
	}

	// Caller must hold lock.
	private void shutdownBrowser()
	{
		Browser b = new Browser();
		b.driver = webDriver;
		b.group = group;
		b.guard = guard;
		b.slotHeld = slotHeld;
		webDriver = null;
		group = null;
		guard = null;
		slotHeld = false;
		reloading = false;
		casting = false;
		probing = false;
		probeDue = false;
		ackPending = false;
		probeHeld = null;
		stoppingCast = false;
		mode = Mode.screenshots;
		release(b);
	}

	/** Closes the page (its context and renderer), leaves its Chrome group, closes its guard, frees the slot. */
	private void release(Browser b)
	{
		if(b.driver != null)
		{
			try
			{
				b.driver.close();
			}
			catch(Exception e)
			{
				log.warn("OverlayStudio capture: error closing the page " + settings.label() + ": " + summarize(e));
			}
			b.driver = null;
		}
		if(b.group != null)
		{
			ChromeGroups.get().leave(b.group, this);
			b.group = null;
		}
		if(b.guard != null)
		{
			b.guard.close();
			b.guard = null;
		}
		if(b.slotHeld)
		{
			CaptureService.get().release();
			b.slotHeld = false;
		}
	}

	private void applyTransparentBackground(PageDriver driver) throws Exception
	{
		if(settings.paintBackground())
		{
			// keying removes this color, so the page background becomes transparent
			driver.evaluate("document.getElementsByTagName('body')[0].style.backgroundColor = '" + settings.backgroundCss() + "';", CALL_TIMEOUT.toMillis());
		}
	}

	// Caller must hold lock.
	private void scheduleReload()
	{
		reloadFuture = reloadExecutor.schedule(() -> startReload(false), (long)(reloadRate * 1000.0), TimeUnit.MILLISECONDS);
	}

	// Caller must hold lock.
	private void cancelReload()
	{
		if(reloadFuture != null)
		{
			reloadFuture.cancel(false);
			reloadFuture = null;
		}
	}

	// now: a reload asked for at once, which runs even when the page does not reload on its own
	private void startReload(boolean now)
	{
		PageDriver driver;
		synchronized(lock)
		{
			reloadFuture = null;
			if(closed || (reloadRate <= 0 && !now) || reloading)
			{
				return;
			}
			if(webDriver == null)
			{
				if(!now)
				{
					scheduleReload();
				}
				return;
			}
			driver = webDriver;
			reloading = true;
			state = State.reloading;
			// a probe under way would count the load's repaints; finishReload starts a new one
			probing = false;
		}
		// navigate outside the lock so requests are not blocked; they get the last frame until finishReload. A load
		// can take up to the page load timeout, so it runs on its own thread, never on the scheduler that times every
		// page's reloads and closes
		NAVIGATE.execute(() -> {
			try
			{
				log.info("OverlayStudio capture: reloading " + settings.label());
				driver.navigate(settings.url(), PAGE_LOAD_TIMEOUT.toMillis());
			}
			catch(Exception e)
			{
				boolean replaced;
				synchronized(lock)
				{
					replaced = closed || webDriver != driver;
				}
				if(replaced)
				{
					// the page was lost or closed under the reload, which is reported where that happened
					log.info("OverlayStudio capture: the reload of " + settings.label() + " ended with its page: " + summarize(e));
				}
				else
				{
					log.warn("OverlayStudio capture: the reload of " + settings.label() + " failed: " + summarize(e));
				}
			}
			reloadExecutor.schedule(this::finishReload, Math.max(pageDelay.getAsInt(), 0), TimeUnit.MILLISECONDS);
		});
	}

	private void finishReload()
	{
		PageDriver driver;
		synchronized(lock)
		{
			if(closed || webDriver == null)
			{
				reloading = false;
				return;
			}
			driver = webDriver;
		}
		// asked outside the lock; frames stay the last one until it is done
		try
		{
			applyTransparentBackground(driver);
		}
		catch(Exception e)
		{
			log.error("OverlayStudio capture: error preparing reloaded page " + settings.label(), e);
		}
		boolean stop = false;
		synchronized(lock)
		{
			reloading = false;
			if(closed || webDriver != driver)
			{
				return;
			}
			state = State.running;
			// the page may paint differently now: a new screencast and a new probe
			stop = casting;
			stoppingCast = stop;
			casting = false;
			probing = false;
			ackPending = false;
			probeDue = true;
			if(reloadRate > 0 && reloadFuture == null)
			{
				scheduleReload();
			}
		}
		if(stop)
		{
			try
			{
				driver.stopScreencast();
			}
			finally
			{
				synchronized(lock)
				{
					stoppingCast = false;
				}
			}
		}
		for(Runnable r: reloadListeners)
		{
			r.run();
		}
	}

	static String summarize(Throwable e)
	{
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		int nl = msg.indexOf('\n');
		if(nl > 0)
		{
			msg = msg.substring(0, nl);
		}
		return msg.length() > 600 ? msg.substring(0, 600) : msg;
	}
}
