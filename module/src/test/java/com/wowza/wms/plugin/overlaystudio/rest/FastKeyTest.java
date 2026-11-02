/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Keying a decoded PNG reads its bytes directly; the result is the same as through getRGB. Scaling is done once per frame. */
class FastKeyTest
{
	static BufferedImage of(int type, int[] rgb, int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, type);
		img.setRGB(0, 0, w, h, rgb, 0, w);
		return img;
	}

	@Test
	void byteImagesKeyLikeAnyOther()
	{
		int w = 37, h = 11;
		int[] rgb = new int[w * h];
		Random r = new Random(3);
		for(int i = 0; i < rgb.length; i++)
		{
			rgb[i] = i % 5 == 0 ? 0xff010203 : i % 7 == 0 ? 0xff020304 : 0xff000000 | r.nextInt(0xffffff);
		}
		Color key = new Color(1, 2, 3);
		int[] expected = ObjectConfig.keyOut(of(BufferedImage.TYPE_INT_RGB, rgb, w, h), key, 1).getRGB(0, 0, w, h, null, 0, w);
		for(int type: new int[] { BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR })
		{
			int[] got = ObjectConfig.keyOut(of(type, rgb, w, h), key, 1).getRGB(0, 0, w, h, null, 0, w);
			assertArrayEquals(expected, got, "type " + type);
		}
	}

	@Test
	void aFrameIsScaledOncePerSize()
	{
		ObjectConfig o = new ObjectConfig();
		BufferedImage keyed = new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB);
		BufferedImage a = o.scaledOnce(keyed, 128, 72);
		assertSame(a, o.scaledOnce(keyed, 128, 72), "the next draw of the same frame reuses it");
		assertSame(keyed, o.scaledOnce(keyed, 64, 36), "its own size is not scaled");
		BufferedImage next = new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB);
		org.junit.jupiter.api.Assertions.assertNotSame(a, o.scaledOnce(next, 128, 72), "a new frame is scaled again");
	}
}
