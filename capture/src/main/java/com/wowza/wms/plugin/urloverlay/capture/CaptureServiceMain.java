/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The URL Overlay capture service: java -jar wse-url-overlay-capture-&lt;version&gt;.jar --config &lt;capture.properties&gt;.
 * Runs Chrome, always in its sandbox, under the service's own account, and answers the module over a Unix socket.
 */
public final class CaptureServiceMain
{
	private CaptureServiceMain()
	{
	}

	public static void main(String[] args) throws Exception
	{
		Path config = null;
		for(int i = 0; i < args.length; i++)
		{
			if(args[i].equals("--config") && i + 1 < args.length)
			{
				config = Path.of(args[++i]);
			}
		}
		if(config == null)
		{
			fail("Usage: java -jar wse-url-overlay-capture-" + CaptureVersion.VERSION + ".jar --config <capture.properties>");
		}
		String refusal = AccountCheck.current();
		if(refusal != null)
		{
			fail(refusal);
		}
		Properties p = new Properties();
		try(InputStream in = Files.newInputStream(config))
		{
			p.load(in);
		}
		catch(IOException e)
		{
			fail("Cannot read " + config + ": " + e.getMessage());
		}
		ServiceConfig c;
		try
		{
			c = ServiceConfig.of(p);
		}
		catch(IllegalArgumentException e)
		{
			fail(e.getMessage());
			return;
		}
		if(c.logFile() != null)
		{
			Log.toFile(c.logFile());
		}
		Log log = Log.get();
		c.warnings().forEach(log::warn);
		log.info("UrlOverlay capture service " + CaptureVersion.VERSION + " starting as " + System.getProperty("user.name") + ", config " + config);

		CaptureSettings.configure(c.chrome());
		CaptureService service = CaptureService.configure(c.workDir(), c.maxCaptures(), c.maxBrowserMb());
		service.pagesPerBrowser(c.pagesPerBrowser());
		service.reapOrphans();
		BrowserEnvironment.refreshInBackground();

		CaptureHandler handler = new CaptureHandler(CaptureHandler.browsers(), h -> serviceStatus(service), service::setMaxCaptures);
		ScheduledExecutorService sweep = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("capture-idle").unstarted(r));
		sweep.scheduleWithFixedDelay(() -> {
			int n = handler.closeIdle(System.currentTimeMillis());
			if(n > 0)
			{
				log.info("UrlOverlay capture: closed " + n + " capture(s) nobody asked a frame of for a minute");
			}
		}, 15, 15, TimeUnit.SECONDS);
		CaptureServer server = new CaptureServer(c.socket(), handler);
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try
			{
				server.close();
			}
			catch(IOException e)
			{
				// stopping anyway
			}
			for(WebPageCapture w: WebPageCapture.POOL.all())
			{
				w.close();
			}
			ChromeGroups.get().closeAll();
		}, "capture-shutdown"));
	}

	/** What the module's Server view shows of the service. */
	static Map<String, Object> serviceStatus(CaptureService s)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", CaptureVersion.VERSION);
		m.put("user", System.getProperty("user.name"));
		m.put("chrome", BrowserEnvironment.chrome());
		m.put("sandbox", "on");
		m.put("sandboxProblem", s.sandboxProblem());
		m.put("active", s.activeCaptures());
		m.put("limit", s.maxCaptures());
		m.put("startLimit", s.startMaxCaptures());
		m.put("limitCeiling", s.ceiling());
		m.put("memoryMaxMB", s.memoryMaxMb() < 0 ? null : s.memoryMaxMb());
		m.put("cpuLimit", s.cpuLimit() < 0 ? null : s.cpuLimit());
		m.put("maxBrowserMemoryMB", s.maxBrowserMb());
		m.put("pagesPerBrowser", s.pagesPerBrowser());
		m.put("browsers", ChromeGroups.get().all().size());
		m.put("recentBlocks", s.recentBlocks());
		m.put("chromeServiceBlocks", s.chromeServiceBlocks());
		return m;
	}

	private static void fail(String message)
	{
		System.err.println(message);
		System.exit(2);
	}
}
