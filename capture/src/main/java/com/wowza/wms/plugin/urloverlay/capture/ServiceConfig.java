/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** capture.properties: where the socket, Chrome, chromedriver and work files are, and the limits. */
public record ServiceConfig(Path socket, String chrome, String chromedriver, Path workDir, int maxCaptures, int maxBrowserMb, Path logFile,
		List<String> warnings)
{
	public static final String SOCKET = "socket";
	public static final String CHROME = "chrome";
	public static final String CHROMEDRIVER = "chromedriver";
	public static final String WORK_DIR = "workDir";
	public static final String MAX_CAPTURES = "maxCaptures";
	public static final String MAX_BROWSER_MB = "maxBrowserMemoryMB";
	public static final String LOG_FILE = "logFile";
	/** Read by the installer's daily Chrome update (UpdateChrome), not by the service: false turns the update off. */
	public static final String AUTO_UPDATE = "autoUpdate";
	static final Set<String> KEYS = Set.of(SOCKET, CHROME, CHROMEDRIVER, WORK_DIR, MAX_CAPTURES, MAX_BROWSER_MB, LOG_FILE, AUTO_UPDATE);

	/** The configuration in these properties; IllegalArgumentException naming what is missing or wrong. */
	public static ServiceConfig of(Properties p)
	{
		List<String> warnings = new ArrayList<>();
		for(String k: p.stringPropertyNames())
		{
			if(!KEYS.contains(k))
			{
				warnings.add("Unknown setting " + k + " is ignored");
			}
		}
		String socket = value(p, SOCKET);
		if(socket == null)
		{
			throw new IllegalArgumentException("The capture service's configuration has no " + SOCKET + " (the Unix socket the module connects to)");
		}
		String work = value(p, WORK_DIR);
		if(work == null)
		{
			throw new IllegalArgumentException("The capture service's configuration has no " + WORK_DIR + " (where Chrome profiles go)");
		}
		String log = value(p, LOG_FILE);
		return new ServiceConfig(Path.of(socket), value(p, CHROME), value(p, CHROMEDRIVER), Path.of(work),
				number(p, MAX_CAPTURES, CaptureService.DEFAULT_MAX_CAPTURES, warnings), number(p, MAX_BROWSER_MB, CaptureService.DEFAULT_MAX_BROWSER_MB, warnings),
				log == null ? null : Path.of(log), List.copyOf(warnings));
	}

	private static String value(Properties p, String key)
	{
		String v = p.getProperty(key);
		return v == null || v.isBlank() ? null : v.trim();
	}

	private static int number(Properties p, String key, int dflt, List<String> warnings)
	{
		String v = value(p, key);
		if(v == null)
		{
			return dflt;
		}
		try
		{
			int n = Integer.parseInt(v);
			if(n > 0)
			{
				return n;
			}
		}
		catch(NumberFormatException e)
		{
			// reported below
		}
		warnings.add(key + "=" + v + " is not a positive whole number; using " + dflt);
		return dflt;
	}
}
