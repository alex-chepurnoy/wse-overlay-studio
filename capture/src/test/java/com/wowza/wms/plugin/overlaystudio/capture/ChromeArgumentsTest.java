/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The switches every capture's Chrome gets, written out by hand. */
class ChromeArgumentsTest
{
	private static final WebPageCapture.Settings S = new WebPageCapture.Settings("https://example.com/", 640, 360, new Color(1, 2, 3), true, "page");

	@Test
	void everyRequestGoesThroughTheGuard()
	{
		List<String> a = WebPageCapture.chromeArguments(S, 41234, Path.of("p"), false);
		assertTrue(a.contains("--proxy-server=http://127.0.0.1:41234"));
		// without this Chrome sends loopback requests directly, around the guard
		assertTrue(a.contains("--proxy-bypass-list=<-loopback>"));
		assertTrue(a.contains("--force-webrtc-ip-handling-policy=disable_non_proxied_udp"));
	}

	@Test
	void neverWithoutTheSandbox()
	{
		assertFalse(WebPageCapture.chromeArguments(S, 1, null, false).contains("--no-sandbox"));
		assertFalse(WebPageCapture.chromeArguments(S, 1, null, true).contains("--no-sandbox"));
	}

	@Test
	void backgroundCssFromKeyColor()
	{
		assertEquals("rgb(1,2,3)", S.backgroundCss());
		assertEquals("rgb(0,255,10)", new WebPageCapture.Settings("u", 1, 1, new Color(0, 255, 10), true, "x").backgroundCss());
	}
}
