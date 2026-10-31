/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.awt.Color;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import com.wowza.wms.plugin.urloverlay.egress.AddressRules;
import com.wowza.wms.plugin.urloverlay.egress.EgressGuard;

/**
 * Reloads a page that opens a confirm dialog every 300 ms, many times, and counts loads that never finish: a navigation
 * that meets an open dialog. Not a unit test: it needs a Chrome.
 *
 * java -cp capture/build/classes/java/main;capture/build/classes/java/test com.wowza.wms.plugin.urloverlay.capture.DialogRace <chrome> [reloads]
 */
public class DialogRace
{
	public static void main(String[] args) throws Exception
	{
		String page = "<body><div id=t>0</div><script>var n=0;setInterval(function(){t.textContent=++n;confirm('still there?')},300)</script>";
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", ex -> {
			byte[] body = page.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		int port = server.getAddress().getPort(), reloads = args.length > 1 ? Integer.parseInt(args[1]) : 100;
		EgressGuard guard = new EgressGuard(new AddressRules(false, "127.0.0.1", "", true, "80,443," + port), "race", (h, d) -> {});
		int proxy = guard.start();
		Path profile = Files.createTempDirectory("dialog-race");
		WebPageCapture.Settings s = new WebPageCapture.Settings("http://127.0.0.1:" + port + "/", 640, 360, new Color(1, 2, 3), false, "race");
		ChromeBrowser chrome = ChromeBrowser.start(args[0], WebPageCapture.chromeArguments(s, proxy, profile, CaptureService.isLinux()), profile);
		int stuck = 0, failed = 0;
		try
		{
			BrowserPage p = chrome.openPage(proxy, 640, 360);
			p.navigate(s.url(), 10000);
			for(int i = 0; i < reloads; i++)
			{
				Thread.sleep((long)(Math.random() * 300));
				long t0 = System.currentTimeMillis();
				try
				{
					p.navigate(s.url(), 5000);
				}
				catch(java.util.concurrent.TimeoutException e)
				{
					stuck++;
					System.out.println("reload " + i + " did not finish: " + e.getMessage());
				}
				catch(Exception e)
				{
					failed++;
					System.out.println("reload " + i + " failed after " + (System.currentTimeMillis() - t0) + " ms: " + e);
				}
			}
		}
		finally
		{
			chrome.close();
			guard.close();
			server.stop(0);
		}
		System.out.println(reloads + " reloads, " + stuck + " never finished loading, " + failed + " failed otherwise");
	}
}
