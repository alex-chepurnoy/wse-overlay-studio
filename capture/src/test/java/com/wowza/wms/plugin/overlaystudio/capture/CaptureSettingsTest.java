/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Without an explicit Chrome no page may be opened: the service never looks for one of its own. */
class CaptureSettingsTest
{
	@AfterEach
	void reset()
	{
		CaptureSettings.configure(null);
	}

	@Test
	void unsetChromeRefusesToOpenAPage(@TempDir Path dir) throws Exception
	{
		CaptureSettings.configure(null);
		WebPageCapture capture = new WebPageCapture("http://192.0.2.10/", 16, 16, () -> 0, () -> {});
		IllegalStateException e = assertThrows(IllegalStateException.class, capture::capture);
		assertTrue(e.getMessage().contains("No Chrome configured"), e.getMessage());
		// a blank path is unset
		{
			CaptureSettings.configure("   ");
			assertThrows(IllegalStateException.class, CaptureSettings::requireChrome);
		}
		// a path to nothing is refused
		{
			CaptureSettings.configure(dir.resolve("no-such-chrome.exe").toString());
			IllegalStateException e2 = assertThrows(IllegalStateException.class, CaptureSettings::requireChrome);
			assertTrue(e2.getMessage().contains("not found"), e2.getMessage());
		}
		// an existing file is used
		{
			Path fake = Files.createFile(dir.resolve("chrome.exe"));
			CaptureSettings.configure(" " + fake + " ");
			assertEquals(new File(fake.toString()).getPath(), CaptureSettings.requireChrome());
		}
	}

	@Test
	void theHeadlessShellIsKnownByItsName()
	{
		assertTrue(CaptureSettings.isHeadlessShell("/opt/wse-overlay-studio/chrome-for-testing/156.0.8078.80/chrome-headless-shell-linux64/chrome-headless-shell"));
		assertTrue(CaptureSettings.isHeadlessShell("C:/x/chrome-headless-shell.exe"));
		assertFalse(CaptureSettings.isHeadlessShell("/opt/wse-overlay-studio/chrome-for-testing/156.0.8078.80/chrome-linux64/chrome"));
		assertFalse(CaptureSettings.isHeadlessShell(null));
	}
}
