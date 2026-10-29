/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * Plain keying: a pixel exactly equal to the key color becomes fully transparent, every other pixel becomes
 * fully opaque with its color kept. Expected ARGB values are written out by hand.
 */
class KeyColorTest
{
	private static final Color KEY = new Color(1, 2, 3);

	@Test
	void exactKeyBecomesTransparentAndNeighboursStay()
	{
		BufferedImage in = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB);
		in.setRGB(0, 0, 0xFF010203); // the key
		in.setRGB(1, 0, 0xFF010204); // blue off by one
		in.setRGB(2, 0, 0xFFFFFFFF); // white
		in.setRGB(3, 0, 0xFF000000); // black

		BufferedImage out = new ObjectConfig().removeColors(in, KEY);

		assertEquals(0x00010203, out.getRGB(0, 0), "key pixel: alpha 0, rgb kept");
		assertEquals(0xFF010204, out.getRGB(1, 0), "near miss is not keyed at tolerance 0");
		assertEquals(0xFFFFFFFF, out.getRGB(2, 0));
		assertEquals(0xFF000000, out.getRGB(3, 0));
	}

}
