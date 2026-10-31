/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.awt.Color;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;


import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

import com.wowza.wms.plugin.urloverlay.egress.AddressRules;
import com.wowza.wms.plugin.urloverlay.egress.EgressRules;
import com.wowza.wms.plugin.urloverlay.egress.EgressGuard;

/**
 * One headless Chrome for one webPage overlay object. Opened lazily, it loads the url through its own EgressGuard
 * proxy, captures screenshots, optionally reloads, and reports its health. A browser that dies is closed and
 * reopened with exponential backoff (1, 2, 4 ... 60 s). Once closed it never opens a browser again.
 */
public class WebPageCapture
{
	public static final double MIN_RELOAD_RATE = 1.0;
	static final long MAX_BACKOFF_MS = 60_000;
	private static final long BLOCKED_RETRY_MS = 30_000;

	// the default of 300s would let a hung page hold a shared reload thread for 5 minutes
	static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * The longest any one call to chromedriver may take, a screenshot included. Selenium 4.21's default read timeout is
	 * 3 minutes (ClientConfig.defaultConfig), long enough for a hung page to hold this capture's lock and stall every
	 * layer showing it. Longer than PAGE_LOAD_TIMEOUT, so a slow load still ends with chromedriver's own timeout.
	 */
	static final Duration CALL_TIMEOUT = Duration.ofSeconds(40);
	/** How often a running browser's memory is measured against maxBrowserMemoryMB. */
	static final long MEMORY_CHECK_MS = 10_000;

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

	private static final ScheduledExecutorService reloadExecutor = Executors.newScheduledThreadPool(2, r -> {
		Thread thread = new Thread(r, "WebPageReload");
		thread.setDaemon(true);
		return thread;
	});

	/** What one browser is for: the page, and the egress rules of the WSE that asked for it. */
	public record Key(Settings settings, EgressRules rules)
	{
	}

	/** Open captures by page and rules; a page left by its last user stays open this long in case it is used again. */
	public static final long SHARE_GRACE_MS = 15000;
	public static final CapturePool<Key, WebPageCapture> POOL = new CapturePool<>(k -> new WebPageCapture(k.settings(), k.rules().rules(), () -> -1, null),
			WebPageCapture::close, SHARE_GRACE_MS, reloadExecutor);

	private final Log log = Log.get();
	private final Object lock = new Object();
	private final Settings settings;
	private final AddressRules rules;
	private volatile IntSupplier pageDelay;
	private final java.util.List<Runnable> reloadListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final String captureId = UUID.randomUUID().toString().substring(0, 8);

	// a WebDriver, kept as Object like the rest of the plugin so the selenium jar is only needed when a webPage is drawn
	private Object webDriver = null;
	private Object driverService = null;
	private EgressGuard guard = null;
	private Path profileDir = null;
	private boolean slotHeld = false;
	// a thread is starting Chrome outside the lock; frames meanwhile are the last one
	private boolean opening = false;
	private volatile long memoryCheckedAt = 0;
	private int memoryRestarts = 0;
	private long overSince = 0;
	private final java.util.List<Long> restartTimes = new java.util.ArrayList<>();
	// where the browser was found at the last check when it was not on the page, and when that last caused a reload
	private String offPage = null;
	private long offPageReloadAt = 0;

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
		this.pageDelay = pageDelay;
		if(onReloaded != null)
		{
			reloadListeners.add(onReloaded);
		}
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
				byte[] shot = screenshot();
				if(System.currentTimeMillis() - memoryCheckedAt < MEMORY_CHECK_MS)
				{
					return shot;
				}
				memoryCheckedAt = System.currentTimeMillis();
				offPage = offPage(webDriver);
			}
		}
		if(open)
		{
			return openThenCapture(maxAgeMs);
		}
		String off;
		synchronized(lock)
		{
			off = offPage;
			offPage = null;
		}
		if(off != null)
		{
			reloadNow("the browser was on " + off + ", not the page");
		}
		// measured outside the lock: listing processes can take a second on Windows
		Path profile;
		synchronized(lock)
		{
			profile = profileDir;
		}
		CaptureService service = CaptureService.get();
		checkMemory(service.findBrowser(profile).map(service::treeRss).orElse(-1L));
		return lastPng();
	}

	/**
	 * Where the browser is when it is not on a web page: Chrome's new tab page, a blank or error page, or anything else
	 * that is not http or https. A page that redirects to another web address is still on a web page. Null when it is,
	 * or when the address cannot be read. Caller holds the lock.
	 */
	private String offPage(Object driver)
	{
		if(driver == null || reloading)
		{
			return null;
		}
		try
		{
			return offPageAddress(((WebDriver)driver).getCurrentUrl());
		}
		catch(Exception e)
		{
			return null;
		}
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
		log.warn("UrlOverlay capture: reloading " + settings.label() + " now: " + why);
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
					log.warn("UrlOverlay capture: " + msg + " for " + settings.label());
					setError(msg);
				}
				return false;
			}
			log.warn("UrlOverlay capture: " + why + " for " + settings.label());
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

	/** Stands in a driver for one, for tests of what happens to a running browser. */
	void attachForTest(Object driver)
	{
		synchronized(lock)
		{
			webDriver = driver;
		}
	}

	// Caller must hold lock.
	private byte[] screenshot()
	{
		try
		{
			long t0 = System.nanoTime();
			byte[] png = ((TakesScreenshot)webDriver).getScreenshotAs(OutputType.BYTES);
			lastCaptureMs = (System.nanoTime() - t0) / 1_000_000;
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
				log.warn("UrlOverlay capture: capture is " + size[0] + "x" + size[1] + ", expected " + w + "x" + h
						+ "; it will be scaled to fit for " + settings.label());
			}
			return lastPng;
		}
		catch(Exception e)
		{
			fail(e);
			return lastPng;
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
				driverService = b.service;
				guard = b.guard;
				profileDir = b.profileDir;
				slotHeld = b.slotHeld;
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
				log.warn("UrlOverlay capture: reloadRate " + rate + " is below the minimum, using " + MIN_RELOAD_RATE + " for " + settings.label());
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
		Path profile;
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
			profile = profileDir;
		}
		Map<String, Object> chrome = new java.util.LinkedHashMap<>();
		Optional<ProcessHandle> browser = CaptureService.get().findBrowser(profile);
		chrome.put("state", browser.isPresent() ? "alive" : (profile == null ? "none" : "exited"));
		chrome.put("pid", browser.map(ProcessHandle::pid).orElse(null));
		long rss = browser.map(p -> CaptureService.get().treeRss(p)).orElse(-1L);
		chrome.put("rssBytes", rss < 0 ? null : rss);
		m.put("chrome", chrome);
		return m;
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

	/** How often the background loop takes a screenshot, from the overlay's refresh; at least 50 ms. */
	public void interval(long ms)
	{
		intervalMs = Math.max(50, ms);
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
			}
			catch(Exception e)
			{
				// recorded in status by capture; the next attempt waits for the backoff
			}
			long rest = intervalMs - (System.currentTimeMillis() - t0);
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

	// One Chrome with what keeps it running: its driver service, egress proxy, profile and browser slot.
	private static final class Browser
	{
		Object driver;
		Object service;
		EgressGuard guard;
		Path profileDir;
		boolean slotHeld;
	}

	// Starts Chrome and loads the page; on failure frees whatever it had taken. Runs without the lock.
	private Browser openBrowser() throws Exception
	{
		// Resolve the driver before anything else, so a missing one fails here instead of falling through to
		// Selenium Manager, which would download a driver.
		File driver = CaptureSettings.requireChromeDriver();
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
			b.profileDir = service.newProfileDir(captureId);
			log.info("UrlOverlay capture: opening browser for " + settings.label() + " (" + captureId + ")");
			try
			{
				launch(b, driver, proxyPort);
				service.sandboxStarted(null);
			}
			catch(Exception e)
			{
				String why = CaptureService.sandboxStartFailure(summarize(e), CaptureService.isLinux(), CaptureService.restrictUserns());
				service.sandboxStarted(why);
				throw new IllegalStateException(why, e);
			}
			((WebDriver)b.driver).get(settings.url());
			int delay = pageDelay.getAsInt();
			if(delay > 0)
			{
				Thread.sleep(delay);
			}
			applyTransparentBackground(b.driver);
			return b;
		}
		catch(Exception e)
		{
			release(b);
			throw e;
		}
	}

	private void launch(Browser b, File driverFile, int proxyPort)
	{
		ChromeOptions options = new ChromeOptions();
		if(CaptureSettings.chromePath() != null)
		{
			options.setBinary(CaptureSettings.chromePath());
		}
		options.addArguments(chromeArguments(settings, proxyPort, b.profileDir, CaptureService.isLinux()));
		ChromeDriverService service = new ChromeDriverService.Builder().usingDriverExecutable(driverFile).build();
		b.service = service;
		ChromeDriver chromeDriver = new ChromeDriver(service, options, clientConfig());
		b.driver = chromeDriver;
		chromeDriver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
		chromeDriver.manage().timeouts().scriptTimeout(Duration.ofSeconds(10));
		int w = settings.viewportWidth(), h = settings.viewportHeight();
		if(w > 0 && h > 0)
		{
			// --window-size sizes the window, not the page: headless Chrome's viewport comes out smaller, and display
			// scaling can double it. Pin the viewport itself so a capture is exactly width x height.
			Map<String, Object> metrics = new HashMap<>();
			metrics.put("width", w);
			metrics.put("height", h);
			metrics.put("deviceScaleFactor", 1);
			metrics.put("mobile", false);
			chromeDriver.executeCdpCommand("Emulation.setDeviceMetricsOverride", metrics);
		}
	}

	/** How the service talks to chromedriver: no call waits longer than CALL_TIMEOUT. */
	static org.openqa.selenium.remote.http.ClientConfig clientConfig()
	{
		return org.openqa.selenium.remote.http.ClientConfig.defaultConfig().readTimeout(CALL_TIMEOUT);
	}

	/** The Chrome switches for one capture. There is never a --no-sandbox: the service has no way to ask for one. */
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
		log.warn("UrlOverlay capture: " + lastError + " for " + settings.label() + "; retrying in " + backoff + " ms");
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
		b.service = driverService;
		b.guard = guard;
		b.profileDir = profileDir;
		b.slotHeld = slotHeld;
		webDriver = null;
		driverService = null;
		guard = null;
		profileDir = null;
		slotHeld = false;
		reloading = false;
		release(b);
	}

	/** Quits the driver, kills anything left in the profile's process tree, removes the profile, frees the slot. */
	private void release(Browser b)
	{
		if(b.driver != null)
		{
			try
			{
				((WebDriver)b.driver).quit();
			}
			catch(Exception e)
			{
				log.warn("UrlOverlay capture: error closing browser for " + settings.label() + ": " + summarize(e));
			}
			b.driver = null;
		}
		stopService(b);
		if(b.profileDir != null)
		{
			CaptureService.get().findBrowser(b.profileDir).ifPresent(CaptureService::killTree);
			CaptureService.deleteTree(b.profileDir);
			b.profileDir = null;
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

	private static void stopService(Browser b)
	{
		if(b.service != null)
		{
			try
			{
				((ChromeDriverService)b.service).stop();
			}
			catch(Exception e)
			{
				// already stopped
			}
			b.service = null;
		}
	}

	private void applyTransparentBackground(Object driver)
	{
		if(settings.paintBackground())
		{
			// keying removes this color, so the page background becomes transparent
			((JavascriptExecutor)driver).executeScript("document.getElementsByTagName('body')[0].style.backgroundColor = '" + settings.backgroundCss() + "';");
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
		Object driver;
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
		}
		// navigate outside the lock so requests are not blocked; they get the last frame until finishReload
		try
		{
			log.info("UrlOverlay capture: reloading " + settings.label());
			((WebDriver)driver).get(settings.url());
		}
		catch(Exception e)
		{
			log.error("UrlOverlay capture: error reloading " + settings.label(), e);
		}
		reloadExecutor.schedule(this::finishReload, Math.max(pageDelay.getAsInt(), 0), TimeUnit.MILLISECONDS);
	}

	private void finishReload()
	{
		synchronized(lock)
		{
			reloading = false;
			if(closed || webDriver == null)
			{
				return;
			}
			state = State.running;
			try
			{
				applyTransparentBackground(webDriver);
			}
			catch(Exception e)
			{
				log.error("UrlOverlay capture: error preparing reloaded page " + settings.label(), e);
			}
			if(reloadRate > 0 && reloadFuture == null)
			{
				scheduleReload();
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
