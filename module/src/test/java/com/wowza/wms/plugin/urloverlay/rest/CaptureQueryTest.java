/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** ?raw=true selects the unkeyed capture; it was once ignored and the keyed image returned. */
class CaptureQueryTest
{
	@Test
	void rawQuery()
	{
		assertTrue(CaptureConfig.isRaw("raw=true"));
		assertTrue(CaptureConfig.isRaw("a=1&raw=TRUE"));
		assertTrue(CaptureConfig.isRaw("raw=1"));
		assertFalse(CaptureConfig.isRaw(null));
		assertFalse(CaptureConfig.isRaw(""));
		assertFalse(CaptureConfig.isRaw("raw=false"));
		assertFalse(CaptureConfig.isRaw("draw=true"));
	}

	@Test
	void queryArrivesTheWayConfigBaseDeliversIt()
	{
		// ConfigBase.get(Request) builds a new resource, calls setQueryParam(query) on it, then loadObject()
		CaptureConfig fresh = new CaptureConfig();
		assertFalse(fresh.rawRequested());
		fresh.setQueryParam("raw=true");
		assertTrue(fresh.rawRequested());
	}
}
