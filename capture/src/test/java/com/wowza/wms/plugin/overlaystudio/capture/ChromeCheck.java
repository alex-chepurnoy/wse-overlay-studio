/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.awt.Color;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;

/**
 * The capture service's page path against a real Chrome, outside the service: pages sharing Chromes, the mode each page
 * settles on, a Chrome killed under its pages, a reload, a blocked address, and that closing leaves no Chrome and no
 * profile. Not a unit test: it needs a Chrome.
 *
 * java -cp capture/build/classes/java/main;capture/build/classes/java/test com.wowza.wms.plugin.overlaystudio.capture.ChromeCheck <chrome>
 */
public class ChromeCheck
{
	static int failures = 0;

	static void check(boolean ok, String what)
	{
		System.out.println((ok ? "PASS " : "FAIL ") + what);
		if(!ok)
		{
			failures++;
		}
	}

	static final Map<String, String> PAGES = Map.of(
			"fast", "<body style='background:#036;color:white;font:80px monospace'><div id=t></div><script>setInterval(function(){t.textContent=(performance.now()/1000).toFixed(1)},100)</script>",
			"anim", "<body style='background:#036;margin:0'><div style='width:200px;height:200px;background:orange;animation:s 2s linear infinite'></div><style>@keyframes s{to{transform:translateX(400px) rotate(360deg)}}</style>",
			"still", "<body style='background:#036;color:white;font:60px sans-serif'>Final score 3 - 1</body>",
			// an alert during load and a confirm later: both are dismissed, and the page is not taken for hung
			"dialog", "<body style='background:#036;color:white;font:60px sans-serif'><div id=t>0</div><script>alert('loading');var n=0;"
					+ "setInterval(function(){t.textContent=++n},1000);setTimeout(function(){confirm('still there?')},3000)</script>",
			// two quick changes 30 ms apart, twice, then still: the last one must be the frame kept
			"burst", "<body style='margin:0;background:rgb(0,0,0)'><script>function c(v){document.body.style.background=v}"
					+ "setTimeout(function(){c('rgb(255,0,0)')},5000);setTimeout(function(){c('rgb(0,255,0)')},5030);"
					+ "setTimeout(function(){c('rgb(0,0,255)')},7000);setTimeout(function(){c('rgb(255,255,0)')},7030);</script>");

	public static void main(String[] args) throws Exception
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", ex -> {
			String name = ex.getRequestURI().getPath().substring(1);
			byte[] body = PAGES.getOrDefault(name, "<body>none</body>").getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		int port = server.getAddress().getPort();
		Path work = Files.createTempDirectory("chrome-check");
		CaptureSettings.configure(args[0]);
		CaptureService service = CaptureService.configure(work, 8, 1024);
		service.pagesPerBrowser(2);
		AddressRules rules = new AddressRules(false, "127.0.0.1", "", true, "80,443," + port);

		String[][] plan = { { "fast", "100" }, { "anim", "1000" }, { "still", "1000" }, { "fast", "1000" }, { "still", "100" } };
		String[] expected = { "screencast", "screenshots", "screencast", "screenshots", "screencast" };
		List<WebPageCapture> caps = new ArrayList<>();
		for(String[] p: plan)
		{
			WebPageCapture c = new WebPageCapture(new WebPageCapture.Settings("http://127.0.0.1:" + port + "/" + p[0], 640, 360, new Color(1, 2, 3), false,
					p[0] + "@" + p[1]), rules, () -> 200, null);
			c.interval(Long.parseLong(p[1]));
			c.startLoop();
			caps.add(c);
		}
		Thread.sleep(9000);
		check(ChromeGroups.get().all().size() == 3, "5 pages at 2 a Chrome run in 3 Chromes: " + ChromeGroups.get().all().size());
		for(int i = 0; i < caps.size(); i++)
		{
			Map<String, Object> st = caps.get(i).status();
			int[] size = WebPageCapture.pngSize(caps.get(i).lastPng());
			check("running".equals(st.get("state")) && size != null && size[0] == 640 && size[1] == 360,
					plan[i][0] + "@" + plan[i][1] + " has a 640x360 frame: " + st.get("state") + " " + (size == null ? null : size[0] + "x" + size[1]));
			check(expected[i].equals(st.get("captureMode")), plan[i][0] + "@" + plan[i][1] + " by " + expected[i] + ": " + st.get("captureMode") + ", "
					+ st.get("repaintsPerSecond") + " repaints a second");
			System.out.println("     chrome " + st.get("chrome"));
		}

		// frames keep coming at about the interval on the fast page by screencast
		WebPageCapture fast = caps.get(0);
		long s0 = fast.seq();
		Thread.sleep(2000);
		long n = fast.seq() - s0;
		check(n >= 14 && n <= 24, "the fast page at 100 ms gives about 20 frames in 2 s: " + n);
		WebPageCapture still = caps.get(2);
		long t0 = System.currentTimeMillis();
		Thread.sleep(1500);
		check(still.lastCaptureAt().toEpochMilli() >= t0, "the still page's frame stays current without new frames");

		// a burst then nothing: the screencast's last frame is the page's last state
		{
			WebPageCapture burst = new WebPageCapture(new WebPageCapture.Settings("http://127.0.0.1:" + port + "/burst", 640, 360, new Color(1, 2, 3), false, "burst"),
					rules, () -> 0, null);
			burst.interval(1000);
			burst.startLoop();
			Thread.sleep(11_000);
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(burst.lastPng()));
			int rgb = img.getRGB(320, 180) & 0xffffff;
			check(rgb == 0xffff00, "after a burst the kept frame is the page's last state (yellow): " + Integer.toHexString(rgb) + ", by "
					+ burst.status().get("captureMode"));
			burst.close();
		}

		// dialogs
		{
			WebPageCapture dialog = new WebPageCapture(new WebPageCapture.Settings("http://127.0.0.1:" + port + "/dialog", 640, 360, new Color(1, 2, 3), false, "dialog"),
					rules, () -> 0, null);
			dialog.interval(1000);
			dialog.startLoop();
			Thread.sleep(25_000);
			Map<String, Object> st = dialog.status();
			check("running".equals(st.get("state")) && ((Number)st.get("failures")).intValue() == 0 && st.get("lastError") == null,
					"a page with an alert and a confirm runs without failing: " + st.get("state") + ", " + st.get("lastError"));
			dialog.close();
		}

		// kill the Chrome under the first page; it and its neighbor open again in a new one
		long pid = ((Number)((Map<?, ?>)fast.status().get("chrome")).get("pid")).longValue();
		ProcessHandle.of(pid).ifPresent(CaptureService::killTree);
		Thread.sleep(500);
		check(!"running".equals(fast.status().get("state")) || ((Map<?, ?>)fast.status().get("chrome")).get("pid") == null
				|| ((Number)((Map<?, ?>)fast.status().get("chrome")).get("pid")).longValue() != pid, "the page noticed its Chrome is gone: " + fast.status().get("state"));
		long until = System.currentTimeMillis() + 15_000;
		while(System.currentTimeMillis() < until && !"running".equals(fast.status().get("state")))
		{
			Thread.sleep(200);
		}
		Object newPid = ((Map<?, ?>)fast.status().get("chrome")).get("pid");
		check("running".equals(fast.status().get("state")) && newPid != null && ((Number)newPid).longValue() != pid,
				"it runs again in a new Chrome: " + fast.status().get("state") + ", pid " + newPid + " after " + pid + ", last error " + fast.status().get("lastError"));
		long s1 = fast.seq();
		Thread.sleep(1500);
		check(fast.seq() > s1, "frames come again");

		// a reload: frames keep coming after it
		still.reloadNow("asked by the check");
		Thread.sleep(3000);
		check("running".equals(still.status().get("state")), "the reloaded page runs: " + still.status().get("state"));

		// a blocked address never opens a page
		WebPageCapture blocked = new WebPageCapture(new WebPageCapture.Settings("http://127.0.0.2:" + port + "/still", 640, 360, new Color(1, 2, 3), false, "blocked"), rules,
				() -> 0, null);
		blocked.capture();
		check("blocked".equals(blocked.status().get("state")), "a page at an address the rules block is blocked: " + blocked.status().get("state"));
		blocked.close();

		// the page's own traffic goes through its guard: a page trying to reach the DevTools port is refused
		long probes = service.recentBlocks().size();
		System.out.println("     recent blocks: " + probes);

		for(WebPageCapture c: caps)
		{
			c.close();
		}
		Thread.sleep(1500);
		check(ChromeGroups.get().all().isEmpty(), "no Chrome left once every page is closed: " + ChromeGroups.get().all().size());
		long left = ProcessHandle.allProcesses().filter(h -> h.info().commandLine().orElse("").contains(work.toString())).count();
		check(left == 0, "no process uses the work folder (as far as the JDK can see command lines): " + left);
		Path profiles = work.resolve("profiles");
		long dirs = Files.exists(profiles) ? Files.list(profiles).count() : 0;
		check(dirs == 0, "every profile is removed: " + dirs + " left");
		check(service.activeCaptures() == 0, "every page slot is free: " + service.activeCaptures());
		server.stop(0);
		CaptureService.deleteTree(work);
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
