/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Captures and images must shrink with the frame they are drawn for: on a 1/3-scale encode a 640 x 360 capture covers
 * 213 x 120 encode pixels, not 640 x 360.
 * Expected sizes and positions are worked out by hand.
 */
class ScaledDrawTest
{
	@Test
	void webPageSizeFollowsObjectBoxAndScale()
	{
		// 640 x 360 object on a 1080p source, drawn for a 360p encode (scale 1/3): 213 x 120 after truncation
		assertArrayEquals(new int[] { 213, 120 }, ObjectConfig.webPageDrawSize(640, 360, 640, 360, 360.0 / 1080.0));
		// at source scale the capture is drawn 1:1
		assertArrayEquals(new int[] { 640, 360 }, ObjectConfig.webPageDrawSize(640, 360, 640, 360, 1.0));
		// no box set: fall back to the capture's own size, still scaled
		assertArrayEquals(new int[] { 400, 300 }, ObjectConfig.webPageDrawSize(0, 0, 800, 600, 0.5));
	}

	@Test
	void imageObjectIsScaledOnASmallerFrame() throws Exception
	{
		// 40 x 20 image, red 10 x 10 square at (10,5); object at (100,50); drawn at scale 0.5.
		// Expected red square: x = 100*0.5 + 10*0.5 = 55 .. 60, y = 50*0.5 + 5*0.5 = 27.5 .. 32.5
		BufferedImage src = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = src.createGraphics();
		sg.setColor(Color.RED);
		sg.fillRect(10, 5, 10, 10);
		sg.dispose();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(src, "png", png);

		ObjectConfig obj = new ObjectConfig();
		obj.setType("image");
		obj.setX(100);
		obj.setY(50);
		obj.setOpacity(100); // normally filled in from the overlay's defaults
		obj.base64Image = com.wowza.util.Base64.encodeBytes(png.toByteArray());

		BufferedImage canvas = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		obj.draw(g, 0.5, false);
		g.dispose();

		assertTrue(isRed(canvas.getRGB(57, 30)), "inside the scaled square");
		// where the unscaled square would have landed: (50+10, 25+5) .. (70, 40)
		assertEquals(0, canvas.getRGB(66, 37) >>> 24, "nothing drawn where the native-size square would be");
	}

	private static boolean isRed(int argb)
	{
		int a = argb >>> 24, r = (argb >> 16) & 0xff, gr = (argb >> 8) & 0xff, b = argb & 0xff;
		return a > 200 && r > 200 && gr < 60 && b < 60;
	}
}
