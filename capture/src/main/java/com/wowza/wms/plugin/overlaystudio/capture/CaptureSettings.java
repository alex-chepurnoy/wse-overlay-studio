/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.io.File;

/**
 * Where Chrome is, from the capture service's configuration. The service starts it itself and drives it over DevTools;
 * the chromedriver setting of earlier versions is still accepted and no longer used.
 */
public final class CaptureSettings
{
	private static volatile String chromePath = null;

	private CaptureSettings()
	{
	}

	public static void configure(String browserPath)
	{
		chromePath = blankToNull(browserPath);
	}

	/**
	 * @return the Chrome binary to start: Chrome for Testing, or chrome-headless-shell
	 * @throws IllegalStateException if none is configured or the file does not exist
	 */
	public static String requireChrome()
	{
		String path = chromePath;
		if(path == null)
		{
			throw new IllegalStateException("No Chrome configured. Set " + ServiceConfig.CHROME + " in the capture service's configuration to the Chrome it runs.");
		}
		if(!new File(path).isFile())
		{
			throw new IllegalStateException("Chrome not found at " + path + " (" + ServiceConfig.CHROME + " in the capture service's configuration)");
		}
		return path;
	}

	/** The configured Chrome binary, or null. */
	public static String chromePath()
	{
		return chromePath;
	}

	/** True for chrome-headless-shell, which is always headless and takes no --headless switch. */
	static boolean isHeadlessShell(String path)
	{
		return path != null && new File(path).getName().toLowerCase(java.util.Locale.ROOT).startsWith("chrome-headless-shell");
	}

	private static String blankToNull(String s)
	{
		return s == null || s.isBlank() ? null : s.trim();
	}
}
