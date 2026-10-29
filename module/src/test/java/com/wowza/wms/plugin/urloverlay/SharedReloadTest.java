/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Layers sharing one page each ask for a reload rate; the page reloads at the shortest and keeps it steady. */
class SharedReloadTest
{
	@Test
	void theShortestRequestedRateWins()
	{
		WebPageCapture c = new WebPageCapture("http://example.com/", 320, 180, () -> 0, null);
		try
		{
			Object a = new Object(), b = new Object();
			c.reloadEvery(a, 5);
			assertEquals(5.0, c.reloadRate());
			for(int i = 0; i < 3; i++)
			{
				// draws alternate between the layers; the rate does not flip between them
				c.reloadEvery(b, 10);
				assertEquals(5.0, c.reloadRate());
				c.reloadEvery(a, 5);
				assertEquals(5.0, c.reloadRate());
			}
			c.forgetReload(a);
			assertEquals(10.0, c.reloadRate(), "the remaining layer's rate");
			c.reloadEvery(b, -1);
			assertEquals(0.0, c.reloadRate(), "nobody asks for reloads");
			c.reloadEvery(b, 0.2);
			assertEquals(WebPageCapture.MIN_RELOAD_RATE, c.reloadRate(), "raised to the minimum");
		}
		finally
		{
			c.close();
		}
	}
}
