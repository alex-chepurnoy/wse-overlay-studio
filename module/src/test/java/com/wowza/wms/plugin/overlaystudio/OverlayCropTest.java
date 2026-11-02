/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Expected regions and bytes are worked out by hand from the frame and overlay geometry, not computed from
 * the code under test.
 */
class OverlayCropTest
{
	@Test
	void overhangRightAndBottomIsTrimmed()
	{
		// 30x20 at (80,40) in 100x50: x runs 80..110, frame ends at 100 -> 20 wide; y runs 40..60, frame ends at 50 -> 10 high
		assertEquals(new OverlayCrop.Region(80, 40, 20, 10), OverlayCrop.visible(100, 50, 30, 20, 80, 40));
		// fully inside is unchanged
		{
			// 20x10 at (5,5) in 100x50: nothing reaches an edge
			assertEquals(new OverlayCrop.Region(5, 5, 20, 10), OverlayCrop.visible(100, 50, 20, 10, 5, 5));
		}
		// exactly filling the frame is kept
		{
			assertEquals(new OverlayCrop.Region(0, 0, 1920, 1080), OverlayCrop.visible(1920, 1080, 1920, 1080, 0, 0));
		}
	}

	@Test
	void negativeOriginIsClampedToZero()
	{
		// 30x20 at (-10,-5): visible x 0..20, y 0..15
		assertEquals(new OverlayCrop.Region(0, 0, 20, 15), OverlayCrop.visible(100, 50, 30, 20, -10, -5));
	}

	@Test
	void entirelyOutsideIsNull()
	{
		assertNull(OverlayCrop.visible(100, 50, 20, 10, 100, 0), "starts on the right edge");
		assertNull(OverlayCrop.visible(100, 50, 30, 10, -30, 0), "ends on the left edge");
		assertNull(OverlayCrop.visible(100, 50, 20, 10, 0, 50), "starts on the bottom edge");
	}

	@Test
	void cropCopiesTheVisibleColumns()
	{
		// 3x2 overlay, pixel (col,row) = bytes {row*16+col, 0xA0, 0xB0, 0xC0}, placed at (-1,0) in a 10x10 frame.
		// Column 0 falls off the left, so columns 1 and 2 of both rows remain.
		byte[] buffer = {
				0x00, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x01, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x02, (byte)0xA0, (byte)0xB0, (byte)0xC0,
				0x10, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x11, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x12, (byte)0xA0, (byte)0xB0, (byte)0xC0,
		};
		OverlayCrop.Region visible = OverlayCrop.visible(10, 10, 3, 2, -1, 0);
		assertEquals(new OverlayCrop.Region(0, 0, 2, 2), visible);

		byte[] expected = {
				0x01, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x02, (byte)0xA0, (byte)0xB0, (byte)0xC0,
				0x11, (byte)0xA0, (byte)0xB0, (byte)0xC0,  0x12, (byte)0xA0, (byte)0xB0, (byte)0xC0,
		};
		assertArrayEquals(expected, OverlayCrop.crop(buffer, 3, -1, 0, visible));
	}

	@Test
	void cropCopiesTheVisibleRows()
	{
		// Same 3x2 overlay placed at (8,9) in a 10x10 frame: visible is columns 0..1 of row 0 only.
		byte[] buffer = {
				0x00, 0, 0, 0,  0x01, 0, 0, 0,  0x02, 0, 0, 0,
				0x10, 0, 0, 0,  0x11, 0, 0, 0,  0x12, 0, 0, 0,
		};
		OverlayCrop.Region visible = OverlayCrop.visible(10, 10, 3, 2, 8, 9);
		assertEquals(new OverlayCrop.Region(8, 9, 2, 1), visible);
		assertArrayEquals(new byte[] { 0x00, 0, 0, 0, 0x01, 0, 0, 0 }, OverlayCrop.crop(buffer, 3, 8, 9, visible));
	}
}
