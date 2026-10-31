/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** A page's last capture time: a screencast of a page that does not change sends no frame, and the service says so. */
class CaptureTimeTest
{
	@Test
	void theLaterOfTheLastFrameAndTheServicesWordIsShown()
	{
		Instant received = Instant.parse("2026-10-31T10:00:00Z");
		assertEquals("2026-10-31T10:05:00Z", WebPageCapture.latest(received, "2026-10-31T10:05:00Z"), "the service confirmed the frame later");
		assertEquals("2026-10-31T10:00:00Z", WebPageCapture.latest(received, "2026-10-31T09:59:00Z"), "a newer frame here");
		assertEquals("2026-10-31T10:00:00Z", WebPageCapture.latest(received, null), "an older service says nothing");
		assertEquals("2026-10-31T10:00:00Z", WebPageCapture.latest(received, "not a time"));
		assertEquals("2026-10-31T10:05:00Z", WebPageCapture.latest(null, "2026-10-31T10:05:00Z"));
		assertNull(WebPageCapture.latest(null, null));
	}
}
