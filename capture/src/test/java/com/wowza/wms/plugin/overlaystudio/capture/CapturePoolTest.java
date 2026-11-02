/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

class CapturePoolTest
{
	static final class Fake
	{
		final String key;
		Fake(String key)
		{
			this.key = key;
		}
	}

	@Test
	void samePageIsSharedAndClosedAfterLastUser()
	{
		List<Fake> closed = new ArrayList<>();
		CapturePool<String, Fake> pool = new CapturePool<>(Fake::new, closed::add, 0, null);
		Fake a = pool.acquire("page");
		Fake b = pool.acquire("page");
		assertSame(a, b);
		assertEquals(2, pool.users(a));
		assertNotSame(a, pool.acquire("other"));
		pool.release(a);
		assertEquals(List.of(), closed, "one user left");
		pool.release(b);
		assertEquals(List.of(a), closed);
		assertNotSame(a, pool.acquire("page"), "a closed page is opened again, not reused");
		pool.release(a);
		assertEquals(1, closed.size(), "a release after close does nothing");
	}

	@Test
	void graceKeepsPageForTheNextUser() throws Exception
	{
		List<Fake> closed = new ArrayList<>();
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		try
		{
			CapturePool<String, Fake> pool = new CapturePool<>(Fake::new, closed::add, 300, timer);
			Fake a = pool.acquire("page");
			pool.release(a);
			Fake b = pool.acquire("page");
			assertSame(a, b, "reacquired within the grace period: same browser");
			pool.release(b);
			Thread.sleep(700);
			synchronized(closed)
			{
				assertEquals(List.of(a), closed);
			}
			assertEquals(0, pool.size());
		}
		finally
		{
			timer.shutdownNow();
		}
	}

	@Test
	void settingsLabelDoesNotSplitAPage()
	{
		WebPageCapture.Settings a = new WebPageCapture.Settings("https://x/score", 1280, 200, new Color(1, 2, 3), true, "score cam1");
		WebPageCapture.Settings b = new WebPageCapture.Settings("https://x/score", 1280, 200, new Color(1, 2, 3), true, "score cam2");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, new WebPageCapture.Settings("https://x/score", 1280, 201, new Color(1, 2, 3), true, "score cam1"));
		assertNotEquals(a, new WebPageCapture.Settings("https://x/score", 1280, 200, new Color(1, 2, 4), true, "score cam1"));
		assertNotEquals(a, new WebPageCapture.Settings("https://x/score", 1280, 200, new Color(1, 2, 3), false, "score cam1"));
		assertNotEquals(a, new WebPageCapture.Settings("https://x/clock", 1280, 200, new Color(1, 2, 3), true, "score cam1"));
	}
}
