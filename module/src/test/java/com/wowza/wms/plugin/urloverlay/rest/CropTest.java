/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cropping a captured page: only the cropped part is drawn and returned as the keyed capture. Expected pixels are
 * worked out by hand from a 100 x 60 page with a red block at 20,10 sized 30 x 20.
 */
class CropTest
{
	private static BufferedImage page()
	{
		BufferedImage img = new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, 100, 60);
		g.setColor(Color.RED);
		g.fillRect(20, 10, 30, 20);
		g.dispose();
		return img;
	}

	@Test
	void cropKeepsOnlyThatPart()
	{
		BufferedImage c = ObjectConfig.crop(page(), 20, 10, 30, 20);
		assertEquals(30, c.getWidth());
		assertEquals(20, c.getHeight());
		assertEquals(Color.RED.getRGB(), c.getRGB(0, 0));
		assertEquals(Color.RED.getRGB(), c.getRGB(29, 19));
		// no crop draws the whole page
		{
			BufferedImage img = page();
			assertSame(img, ObjectConfig.crop(img, 0, 0, 0, 0));
			assertSame(img, ObjectConfig.crop(img, 5, 5, 0, 40), "a crop without a width is no crop");
			assertSame(img, ObjectConfig.crop(img, 0, 0, 100, 60), "a crop of the whole page is the page");
		}
	}

	@Test
	void cropOutsideThePageIsClampedToIt()
	{
		BufferedImage c = ObjectConfig.crop(page(), 90, 50, 40, 40);
		assertEquals(10, c.getWidth(), "90 + 40 runs past the 100 px page");
		assertEquals(10, c.getHeight());
		BufferedImage d = ObjectConfig.crop(page(), -5, 200, 10, 10);
		assertEquals(10, d.getWidth());
		assertEquals(1, d.getHeight(), "a start below the page is moved to its last row");
	}

	@Test
	void cropFieldsRoundTripThroughJson() throws Exception
	{
		ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		ObjectConfig o = json.convertValue(Map.of("id", "score", "type", "webPage", "cropX", 20, "cropY", 10, "cropW", 30, "cropH", 20), ObjectConfig.class);
		BufferedImage c = o.cropped(page());
		assertEquals(30, c.getWidth());
		assertEquals(Color.RED.getRGB(), c.getRGB(0, 0));
		@SuppressWarnings("unchecked")
		Map<String, Object> back = json.convertValue(o, Map.class);
		assertEquals(20, back.get("cropX"));
		assertEquals(20, back.get("cropH"));
	}
}
