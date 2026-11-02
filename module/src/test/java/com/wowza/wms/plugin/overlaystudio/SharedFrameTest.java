/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;

/**
 * A page shown on many streams is keyed once and scaled once per size for all of them, and an overlay is redrawn on a
 * refresh only when a page has a frame not drawn yet.
 */
class SharedFrameTest
{
	private static WebPageCapture page()
	{
		return new WebPageCapture(new WebPageCapture.Settings("https://scores.example/", 64, 36, new Color(1, 2, 3), true, "score"), () -> -1, null);
	}

	@Test
	void aFrameIsKeyedOnceAndScaledOncePerSizeForEveryStream()
	{
		WebPageCapture p = page();
		BufferedImage frame = new BufferedImage(64, 36, BufferedImage.TYPE_3BYTE_BGR);
		AtomicInteger keyed = new AtomicInteger(), scaled = new AtomicInteger();
		// twenty streams draw the same frame at two output sizes
		BufferedImage k = null, s720 = null;
		for(int stream = 0; stream < 20; stream++)
		{
			BufferedImage kk = p.derived(frame, 1, "key", () -> { keyed.incrementAndGet(); return new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB); });
			BufferedImage a = p.derived(frame, 1, "key@32x18", () -> { scaled.incrementAndGet(); return new BufferedImage(32, 18, BufferedImage.TYPE_INT_ARGB); });
			p.derived(frame, 1, "key@16x9", () -> { scaled.incrementAndGet(); return new BufferedImage(16, 9, BufferedImage.TYPE_INT_ARGB); });
			if(k != null)
			{
				assertSame(k, kk);
				assertSame(s720, a);
			}
			k = kk;
			s720 = a;
		}
		assertEquals(1, keyed.get(), "keyed once for all twenty");
		assertEquals(2, scaled.get(), "scaled once per size");
		// a new frame drops what the old one made
		BufferedImage next = new BufferedImage(64, 36, BufferedImage.TYPE_3BYTE_BGR);
		assertNotSame(k, p.derived(next, 2, "key", () -> { keyed.incrementAndGet(); return new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB); }));
		assertEquals(2, keyed.get());
		// the kept set is bounded
		for(int i = 0; i < 50; i++)
		{
			p.derived(next, 2, "key@" + i, () -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
		}
		AtomicInteger again = new AtomicInteger();
		p.derived(next, 2, "key@0", () -> { again.incrementAndGet(); return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); });
		assertEquals(1, again.get(), "the oldest entries were dropped past " + WebPageCapture.DERIVED_MAX);
	}

	@Test
	void anOverlayWithNoPageNeverAsksForARedraw()
	{
		ObjectConfig line = new ObjectConfig();
		line.setType("line");
		OverlayConfig c = new OverlayConfig(new java.util.UUID(1, 1));
		c.setObjects(List.of(line));
		assertFalse(c.hasNewFrame(), "lines, text, shapes and images change only when the overlay is saved");
		ObjectConfig web = new ObjectConfig();
		web.setType("webPage");
		c.setObjects(List.of(line, web));
		assertTrue(c.hasNewFrame(), "a page not opened yet has its first frame to draw");
	}
}
