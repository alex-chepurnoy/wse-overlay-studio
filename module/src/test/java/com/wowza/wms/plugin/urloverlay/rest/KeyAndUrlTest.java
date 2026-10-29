/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.urloverlay.egress.AddressRules;

/** Key tolerance and webPage url rules. Expected pixels and messages are written out by hand. */
class KeyAndUrlTest
{
	@Test
	void toleranceIsPerChannelAndInclusive()
	{
		BufferedImage in = new BufferedImage(4, 1, BufferedImage.TYPE_INT_RGB);
		in.setRGB(0, 0, 0x030405); // each channel 2 away from (1,2,3): keyed at tolerance 2
		in.setRGB(1, 0, 0x040203); // red 3 away: not keyed at tolerance 2
		in.setRGB(2, 0, 0x010203); // exact
		in.setRGB(3, 0, 0x000000); // each channel 1..3 away; blue 3 away: not keyed
		BufferedImage out = ObjectConfig.keyOut(in, new Color(1, 2, 3), 2);
		assertEquals(0x00030405, out.getRGB(0, 0));
		assertEquals(0xFF040203, out.getRGB(1, 0));
		assertEquals(0x00010203, out.getRGB(2, 0));
		assertEquals(0xFF000000, out.getRGB(3, 0));
		// a custom key color
		{
			BufferedImage in2 = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
			in2.setRGB(0, 0, 0x00FF00);
			in2.setRGB(1, 0, 0x01FE00);
			BufferedImage out2 = ObjectConfig.keyOut(in2, Color.GREEN, 0);
			assertEquals(0x0000FF00, out2.getRGB(0, 0));
			assertEquals(0xFF01FE00, out2.getRGB(1, 0));
		}
	}

	@Test
	void urlRules()
	{
		AddressRules r = AddressRules.defaults();
		assertNull(ObjectConfig.validateUrl("http://192.0.2.10/page", r), "public literal address");
		assertTrue(ObjectConfig.validateUrl("https://192.0.2.10:8443/x?y=1", r).startsWith("Blocked port: 8443"), "80 and 443 only by default");
		assertNull(ObjectConfig.validateUrl("https://192.0.2.10:8443/x?y=1", new AddressRules(false, null, null, true, "8443")));
		assertTrue(ObjectConfig.validateUrl("file:///C:/page.html", r).startsWith("only http and https"));
		assertTrue(ObjectConfig.validateUrl("javascript:alert(1)", r).startsWith("only http and https"));
		assertTrue(ObjectConfig.validateUrl("http://user:pass@192.0.2.10/", r).contains("user name or password"));
		assertTrue(ObjectConfig.validateUrl("http://10.0.0.12/status", r).startsWith("Blocked host: 10.0.0.12"));
		assertTrue(ObjectConfig.validateUrl("http://169.254.169.254/latest/meta-data/", r).contains("cloud-metadata"));
		assertTrue(ObjectConfig.validateUrl("", r).startsWith("url is required"));
		assertNull(ObjectConfig.validateUrl("http://127.0.0.1:8899/page.html", new AddressRules(false, "127.0.0.1", null, true, "8899")), "allowed by entries");
		assertTrue(ObjectConfig.validateUrl("http://127.0.0.1:8899/page.html", new AddressRules(false, "127.0.0.1", null)).startsWith("Blocked port"));
	}
}
