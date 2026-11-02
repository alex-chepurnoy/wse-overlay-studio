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
import java.util.Random;

import com.sun.net.httpserver.HttpServer;
import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;

/**
 * A long run of the capture service's page path against a real Chrome, with the trouble a server sees over days pressed
 * into an hour: reloads every 10 s, a Chrome killed every 2 minutes, a renderer killed every 5, a page closed and another
 * opened every 3. Every minute it prints what would show a leak: pages running, Chromes, Chrome memory, the JVM's heap,
 * threads and open files, profiles on disk. At the end it closes everything and checks nothing is left.
 *
 * java -cp <main>:<test> com.wowza.wms.plugin.overlaystudio.capture.Soak <chrome> <minutes> [pages]
 */
public class Soak
{
	static final Map<String, String> PAGES = Map.of(
			"fast", "<body style='background:#036;color:white;font:80px monospace'><div id=t></div><script>setInterval(function(){t.textContent=(performance.now()/1000).toFixed(1)},100)</script>",
			"anim", "<body style='background:#036;margin:0'><div style='width:200px;height:200px;background:orange;animation:s 2s linear infinite'></div><style>@keyframes s{to{transform:translateX(400px) rotate(360deg)}}</style>",
			"still", "<body style='background:#036;color:white;font:60px sans-serif'>Final score 3 - 1</body>",
			"clock", "<body style='background:#036;color:white;font:80px monospace'><div id=t></div><script>setInterval(function(){t.textContent=new Date().toLocaleTimeString()},1000)</script>",
			"dialog", "<body style='background:#036;color:white;font:60px sans-serif'><div id=t>0</div><script>var n=0;setInterval(function(){t.textContent=++n},1000);setInterval(function(){confirm('still there?')},20000)</script>",
			"heavy", "<body style='margin:0'><canvas id=c width=1280 height=720></canvas><script>var x=c.getContext('2d'),p=[];for(var i=0;i<300;i++)p.push([Math.random()*1280,Math.random()*720,Math.random()*4-2,Math.random()*4-2]);function f(){x.fillStyle='#036';x.fillRect(0,0,1280,720);x.fillStyle='white';p.forEach(function(q){q[0]=(q[0]+q[2]+1280)%1280;q[1]=(q[1]+q[3]+720)%720;x.fillRect(q[0],q[1],6,6)});requestAnimationFrame(f)}f()</script>");
	static final String[] KINDS = { "fast", "anim", "still", "clock", "dialog", "heavy" };
	static final long[] INTERVALS = { 100, 250, 500, 1000 };

	public static void main(String[] args) throws Exception
	{
		String chrome = args[0];
		int minutes = Integer.parseInt(args[1]), n = args.length > 2 ? Integer.parseInt(args[2]) : 8;
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", ex -> {
			byte[] body = PAGES.getOrDefault(ex.getRequestURI().getPath().substring(1), "<body>none</body>").getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		int port = server.getAddress().getPort();
		Path work = Files.createTempDirectory("soak");
		CaptureSettings.configure(chrome);
		CaptureService service = CaptureService.configure(work, 32, 1024);
		service.pagesPerBrowser(4);
		AddressRules rules = new AddressRules(false, "127.0.0.1", "", true, "80,443," + port);
		Random rnd = new Random(42);
		List<WebPageCapture> caps = new ArrayList<>();
		int opened = 0;
		for(int i = 0; i < n; i++)
		{
			caps.add(open(i, port, rules, opened++));
		}
		long start = System.currentTimeMillis(), end = start + minutes * 60_000L;
		long nextReload = start + 10_000, nextKill = start + 120_000, nextRenderer = start + 300_000, nextChurn = start + 180_000, nextReport = start + 65_000;
		long[] seq0 = new long[n];
		int kills = 0, rendererKills = 0, churns = 0, reloads = 0;
		System.out.println("minute pages-running chromes chrome-MB heap-MB threads fds profiles frames-per-min modes");
		while(System.currentTimeMillis() < end)
		{
			Thread.sleep(1000);
			long now = System.currentTimeMillis();
			if(now >= nextReload)
			{
				nextReload = now + 10_000;
				caps.get(rnd.nextInt(n)).reloadNow("soak");
				reloads++;
			}
			if(now >= nextKill)
			{
				nextKill = now + 120_000;
				List<ChromeGroups.Group> gs = ChromeGroups.get().all();
				if(!gs.isEmpty() && gs.get(rnd.nextInt(gs.size())).chrome() instanceof ChromeBrowser c)
				{
					CaptureService.killTree(c.process());
					kills++;
				}
			}
			if(now >= nextRenderer)
			{
				nextRenderer = now + 300_000;
				List<ChromeGroups.Group> gs = ChromeGroups.get().all();
				if(!gs.isEmpty() && gs.get(rnd.nextInt(gs.size())).chrome() instanceof ChromeBrowser c)
				{
					var r = c.process().descendants().filter(h -> commandLine(h).contains("--type=renderer")).findFirst();
					if(r.isPresent())
					{
						r.get().destroyForcibly();
						rendererKills++;
					}
					else
					{
						System.out.println("no renderer found among " + c.process().descendants().count() + " processes of Chrome " + c.process().pid());
					}
				}
			}
			if(now >= nextChurn)
			{
				nextChurn = now + 180_000;
				int i = rnd.nextInt(n);
				caps.get(i).close();
				caps.set(i, open(i, port, rules, opened++));
				churns++;
			}
			if(now >= nextReport)
			{
				nextReport = now + 60_000;
				int running = 0;
				long frames = 0;
				StringBuilder modes = new StringBuilder();
				for(int i = 0; i < n; i++)
				{
					WebPageCapture c = caps.get(i);
					Map<String, Object> st = c.quickStatus();
					if("running".equals(st.get("state")))
					{
						running++;
					}
					long s = c.seq();
					frames += Math.max(0, s - seq0[i]);
					seq0[i] = s;
					// state then mode: r running, l reloading, e error, s starting, b blocked; C screencast, S screenshots, P probing
					String state = String.valueOf(st.get("state")), mode = String.valueOf(st.get("captureMode"));
					modes.append(state.equals("reloading") ? 'l' : state.charAt(0)).append(mode.equals("screencast") ? 'C' : mode.equals("screenshots") ? 'S'
							: mode.equals("probing") ? 'P' : '-').append(' ');
				}
				long chromeMb = 0;
				for(ChromeGroups.Group g: ChromeGroups.get().all())
				{
					if(g.chrome() instanceof ChromeBrowser c && c.alive())
					{
						chromeMb += Math.max(0, service.treeRss(c.process())) / (1024 * 1024);
					}
				}
				Runtime rt = Runtime.getRuntime();
				long heap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
				long fds = count(Path.of("/proc/self/fd"));
				long profiles = Math.max(0, count(work.resolve("profiles")));
				System.out.printf("%3d %d/%d %d %d %d %d %d %d %d %s%n", (now - start) / 60_000, running, n, ChromeGroups.get().all().size(), chromeMb, heap,
						Thread.getAllStackTraces().size(), fds, profiles, frames, modes);
			}
		}
		System.out.println("reloads " + reloads + ", Chromes killed " + kills + ", renderers killed " + rendererKills + ", pages replaced " + churns + ", pages opened " + opened);
		for(WebPageCapture c: caps)
		{
			c.close();
		}
		Thread.sleep(8000);
		long left = ProcessHandle.allProcesses().filter(h -> h.info().commandLine().orElse("").contains(work.toString())).count();
		long profiles = Math.max(0, count(work.resolve("profiles")));
		System.out.println("after closing: Chromes " + ChromeGroups.get().all().size() + ", processes on the work folder " + left + ", profiles " + profiles + ", slots in use "
				+ service.activeCaptures());
		server.stop(0);
		CaptureService.deleteTree(work);
		System.exit(left == 0 && profiles == 0 && service.activeCaptures() == 0 ? 0 : 1);
	}

	/**
	 * A process's command line: /proc's on Linux, the JDK's elsewhere. Chrome rewrites its child processes' arguments, and
	 * the JDK then gives only the program's path for them.
	 */
	static String commandLine(ProcessHandle h)
	{
		String c = "";
		{
			try
			{
				c = new String(Files.readAllBytes(Path.of("/proc", Long.toString(h.pid()), "cmdline")), java.nio.charset.StandardCharsets.ISO_8859_1).replace('\0', ' ');
			}
			catch(Exception e)
			{
				// gone, or not Linux
			}
		}
		return c.isEmpty() ? h.info().commandLine().orElse("") : c;
	}

	/** Entries in a folder, or -1 when it is not there; the listing is closed, so counting open files adds none. */
	static long count(Path dir) throws java.io.IOException
	{
		if(!Files.isDirectory(dir))
		{
			return -1;
		}
		try(var s = Files.list(dir))
		{
			return s.count();
		}
	}

	static WebPageCapture open(int i, int port, AddressRules rules, int serial)
	{
		String kind = KINDS[serial % KINDS.length];
		long every = INTERVALS[serial % INTERVALS.length];
		WebPageCapture c = new WebPageCapture(new WebPageCapture.Settings("http://127.0.0.1:" + port + "/" + kind, 1280, 720, new Color(1, 2, 3), false,
				kind + "@" + every + "#" + serial), rules, () -> 200, null);
		c.interval(every);
		c.startLoop();
		return c;
	}
}
