/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Chrome and chromedriver are and which versions they are, for the info endpoint and for a clear error when
 * they do not match. Read once in the background at server start. Chrome's version is read from file metadata on
 * Windows, because running chrome.exe --version there opens a browser window instead of printing.
 */
public final class BrowserEnvironment
{
	private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");

	private static volatile Map<String, Object> chrome = Map.of("found", false, "state", "checking");
	private static volatile Map<String, Object> chromedriver = Map.of("found", false, "state", "checking");

	private BrowserEnvironment()
	{
	}

	public static void refreshInBackground()
	{
		Thread t = new Thread(BrowserEnvironment::refresh, "UrlOverlayBrowserCheck");
		t.setDaemon(true);
		t.start();
	}

	public static void refresh()
	{
		String chromePath = CaptureSettings.chromePath() != null ? CaptureSettings.chromePath() : findChrome();
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("path", chromePath);
		c.put("found", chromePath != null && new File(chromePath).isFile());
		c.put("version", chromePath == null ? null : chromeVersion(chromePath));
		Map<String, Object> d = new LinkedHashMap<>();
		String driverPath = null;
		try
		{
			driverPath = CaptureSettings.requireChromeDriver().getPath();
		}
		catch(IllegalStateException e)
		{
			d.put("error", e.getMessage());
		}
		d.put("path", driverPath);
		d.put("found", driverPath != null);
		String driverVersion = driverPath == null ? null : firstVersion(run(List.of(driverPath, "--version")));
		d.put("version", driverVersion);
		d.put("matchesChrome", sameMajor((String)c.get("version"), driverVersion));
		chrome = c;
		chromedriver = d;
	}

	public static Map<String, Object> chrome()
	{
		return chrome;
	}

	public static Map<String, Object> chromedriver()
	{
		return chromedriver;
	}

	/** @return true or false when both versions are known, null otherwise */
	static Boolean sameMajor(String a, String b)
	{
		if(a == null || b == null)
		{
			return null;
		}
		return a.split("\\.")[0].equals(b.split("\\.")[0]);
	}

	static String firstVersion(List<String> lines)
	{
		for(String l: lines)
		{
			Matcher m = VERSION.matcher(l);
			if(m.find())
			{
				return m.group();
			}
		}
		return null;
	}

	private static String findChrome()
	{
		List<String> candidates = new ArrayList<>();
		if(CaptureService.isWindows())
		{
			candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
			candidates.add("C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
		}
		else
		{
			candidates.add("/usr/bin/google-chrome");
			candidates.add("/usr/bin/google-chrome-stable");
			candidates.add("/usr/bin/chromium");
			candidates.add("/usr/bin/chromium-browser");
			candidates.add("/opt/google/chrome/chrome");
		}
		for(String c: candidates)
		{
			if(new File(c).isFile())
			{
				return c;
			}
		}
		return null;
	}

	private static String chromeVersion(String path)
	{
		if(CaptureService.isWindows())
		{
			String ps = "(Get-Item -LiteralPath '" + path.replace("'", "''") + "').VersionInfo.ProductVersion";
			String encoded = java.util.Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
			return firstVersion(run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)));
		}
		return firstVersion(run(List.of(path, "--version")));
	}

	private static List<String> run(List<String> cmd)
	{
		List<String> out = new ArrayList<>();
		try
		{
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
			{
				String l;
				while((l = r.readLine()) != null)
				{
					out.add(l);
				}
			}
			if(!p.waitFor(20, TimeUnit.SECONDS))
			{
				p.destroyForcibly();
			}
		}
		catch(Exception e)
		{
			// reported as an unknown version
		}
		return out;
	}
}
