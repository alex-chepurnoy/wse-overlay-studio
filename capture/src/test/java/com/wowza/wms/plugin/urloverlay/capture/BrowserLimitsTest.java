/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

/** One browser's memory cap and how long a single chromedriver call may hold a capture. */
class BrowserLimitsTest
{
	static final long MB = 1024L * 1024L;

	@Test
	void aBrowserOverTheCapIsClosedAndReportedThenReopens()
	{
		AtomicInteger quits = new AtomicInteger();
		WebDriver fake = (WebDriver)Proxy.newProxyInstance(WebDriver.class.getClassLoader(), new Class<?>[] { WebDriver.class }, (p, m, a) -> {
			if(m.getName().equals("quit"))
			{
				quits.incrementAndGet();
			}
			return null;
		});
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			c.attachForTest(fake);
			assertFalse(c.checkMemory(200 * MB), "within the default cap");
			assertEquals(0, quits.get());
			long over = (CaptureService.DEFAULT_MAX_BROWSER_MB + 1) * MB, now = System.currentTimeMillis();
			assertFalse(c.checkMemory(over, now - WebPageCapture.OVER_MEMORY_MS), "one reading over the limit is not enough");
			assertEquals(0, quits.get());
			assertTrue(c.checkMemory(over, now), "still over the limit after the grace period");
			assertEquals(1, quits.get(), "the driver was quit");
			Map<String, Object> st = c.status();
			assertEquals("error", st.get("state"));
			assertTrue(String.valueOf(st.get("lastError")).contains("was restarted"), String.valueOf(st.get("lastError")));
			assertEquals(1, st.get("memoryRestarts"));
			assertTrue(((Number)st.get("retryInMs")).longValue() <= 1000, "reopened at the next draw");
			assertFalse(c.checkMemory(5000 * MB), "nothing left to restart");
			// a page that needs more than the limit is restarted a few times, then left running and reported
			for(int i = 1; i < WebPageCapture.MAX_MEMORY_RESTARTS; i++)
			{
				c.attachForTest(fake);
				c.checkMemory(over, now + i * 60_000);
				assertTrue(c.checkMemory(over, now + i * 60_000 + WebPageCapture.OVER_MEMORY_MS), "restart " + (i + 1));
			}
			c.attachForTest(fake);
			long later = now + 10 * 60_000 - 1;
			c.checkMemory(over, later - WebPageCapture.OVER_MEMORY_MS);
			assertFalse(c.checkMemory(over, later), "no restart loop");
			assertEquals(WebPageCapture.MAX_MEMORY_RESTARTS, quits.get());
			assertTrue(String.valueOf(c.status().get("lastError")).contains("left running"), String.valueOf(c.status().get("lastError")));
		}
		finally
		{
			c.close();
		}
		// what counts as over the cap
		{
			assertNull(WebPageCapture.overMemory(-1, 1024), "unknown is not over");
			assertNull(WebPageCapture.overMemory(1024 * MB, 1024), "at the cap is within it");
			String why = WebPageCapture.overMemory(1500 * MB, 1024);
			assertTrue(why.contains("1500 MB") && why.contains("maxBrowserMemoryMB") && why.contains("1024 MB"), why);
		}
	}

	@Test
	void noChromedriverCallWaitsForMinutes()
	{
		Duration read = WebPageCapture.clientConfig().readTimeout();
		assertEquals(WebPageCapture.CALL_TIMEOUT, read);
		assertTrue(read.compareTo(Duration.ofMinutes(1)) < 0, "Selenium's own default is 3 minutes");
		assertTrue(read.compareTo(WebPageCapture.PAGE_LOAD_TIMEOUT) > 0, "a slow page load still ends with chromedriver's own timeout");
	}
}
