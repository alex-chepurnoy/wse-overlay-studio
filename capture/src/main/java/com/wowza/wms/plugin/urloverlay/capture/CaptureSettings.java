/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.File;

/**
 * Where Chrome and chromedriver are, from the capture service's configuration. The chromedriver path is always
 * explicit: without it no browser is opened, so Selenium Manager never runs and never downloads a driver.
 */
public final class CaptureSettings
{
	public static final String PROP_CHROMEDRIVER_PATH = ServiceConfig.CHROMEDRIVER;

	private static volatile String chromeDriverPath = null;
	private static volatile String chromePath = null;

	private CaptureSettings()
	{
	}

	public static void configure(String driverPath, String browserPath)
	{
		chromeDriverPath = blankToNull(driverPath);
		chromePath = blankToNull(browserPath);
	}

	/**
	 * @return the chromedriver executable to use
	 * @throws IllegalStateException if none is configured or the file does not exist
	 */
	public static File requireChromeDriver()
	{
		String path = chromeDriverPath;
		if(path == null)
		{
			throw new IllegalStateException("No chromedriver configured. Set " + PROP_CHROMEDRIVER_PATH + " in the capture service's configuration to the chromedriver matching its Chrome.");
		}
		File file = new File(path);
		if(!file.isFile())
		{
			throw new IllegalStateException("chromedriver not found at " + path + " (" + PROP_CHROMEDRIVER_PATH + " in the capture service's configuration)");
		}
		return file;
	}

	/**
	 * @return the Chrome binary to use, or null to let ChromeDriver find the installed Chrome
	 */
	public static String chromePath()
	{
		return chromePath;
	}

	private static String blankToNull(String s)
	{
		return s == null || s.isBlank() ? null : s.trim();
	}
}
