/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Without an explicit chromedriver no browser may be opened, so Selenium Manager never gets the chance to
 * download one.
 */
class CaptureSettingsTest
{
	@AfterEach
	void reset()
	{
		CaptureSettings.configure(null, null);
	}

	@Test
	void unsetDriverRefusesToOpenABrowser(@TempDir Path dir) throws Exception
	{
		CaptureSettings.configure(null, null);
		WebPageCapture capture = new WebPageCapture("http://192.0.2.10/", 16, 16, () -> 0, () -> {});
		IllegalStateException e = assertThrows(IllegalStateException.class, capture::capture);
		assertTrue(e.getMessage().contains("chromedriver"), e.getMessage());
		// a blank path is unset
		{
			CaptureSettings.configure("   ", null);
			assertThrows(IllegalStateException.class, CaptureSettings::requireChromeDriver);
		}
		// a path to nothing is refused
		{
			CaptureSettings.configure(dir.resolve("no-such-chromedriver.exe").toString(), null);
			IllegalStateException e2 = assertThrows(IllegalStateException.class, CaptureSettings::requireChromeDriver);
			assertTrue(e2.getMessage().contains("not found"), e2.getMessage());
		}
		// an existing file is used
		{
			Path fake = Files.createFile(dir.resolve("chromedriver.exe"));
			CaptureSettings.configure(" " + fake + " ", null);
			assertEquals(new File(fake.toString()), CaptureSettings.requireChromeDriver());
		}
	}

}
