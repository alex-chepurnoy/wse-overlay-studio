/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.ImageFiles;
import com.wowza.wms.plugin.overlaystudio.WebPageCapture;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;

/** The draft render and thumbnails (plan 3.8): no capture opened, width, frozen clock, image files, placeholders, cache. */
class RenderTest
{
	static final long NOW = 1_767_225_600_000L;

	@TempDir
	Path content;

	@BeforeEach
	void useTempContent()
	{
		ImageFiles.contentDirOf = vhost -> content;
		NamedOverlayThumbnailConfig.clearForTest();
	}

	@AfterEach
	void restore()
	{
		NamedOverlayThumbnailConfig.clearForTest();
	}

	@SafeVarargs
	static Map<String, Object> overlay(Map<String, Object>... layers)
	{
		Map<String, Object> o = new LinkedHashMap<>();
		List<Map<String, Object>> l = new ArrayList<>();
		for(Map<String, Object> m: layers)
		{
			l.add(new LinkedHashMap<>(m));
		}
		o.put("objects", l);
		return o;
	}

	static Map<String, Object> page()
	{
		return Map.of("id", "page", "type", "webPage", "url", "https://scores.example.com/live", "x", 200, "y", 0, "w", 200, "h", 100, "opacity", 100);
	}

	static Map<String, Object> bar()
	{
		return Map.of("id", "bar", "type", "rectangle", "x", 100, "y", 100, "w", 200, "h", 100, "fillColor", "rgb(255,0,0)", "color", "rgb(255,0,0)", "opacity", 100);
	}

	static int alpha(BufferedImage img, int x, int y)
	{
		return img.getRGB(x, y) >>> 24;
	}

	static boolean blank(BufferedImage img)
	{
		for(int y = 0; y < img.getHeight(); y++)
		{
			for(int x = 0; x < img.getWidth(); x++)
			{
				if(alpha(img, x, y) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	static int[] pixels(BufferedImage img)
	{
		return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
	}

	@Test
	void theDraftLeavesPagesOutAndOpensNoCapture()
	{
		int open = WebPageCapture.POOL.size();
		OverlayRender.Result r = OverlayRender.draw(overlay(page(), bar()), null, 1920, 1080, 1920, NOW, NOW, false);
		assertEquals(open, WebPageCapture.POOL.size(), "no capture opened");
		assertTrue(alpha(r.image(), 150, 150) > 200, "the shape is drawn");
		assertEquals(0, alpha(r.image(), 300, 50), "the page is left out");
		assertFalse(r.pages());
	}

	@Test
	void eachDrawnLayerSaysWhereItDrawsAtSourceScale()
	{
		Map<String, Object> text = Map.of("id", "score", "type", "string", "x", 40, "y", 300, "text", "HOME 2 - 1", "fontSize", 48, "color", "white", "opacity", 100);
		Map<String, Object> later = Map.of("id", "later", "type", "rectangle", "x", 0, "y", 0, "w", 10, "h", 10, "showFrom", "stream+60", "opacity", 100);
		// drawn at a third of the source's width: the boxes are still in source pixels
		OverlayRender.Result r = OverlayRender.draw(overlay(page(), bar(), text, later), null, 1920, 1080, 640, NOW, NOW, false);
		List<String> ids = r.boxes().stream().map(b -> (String)b.get("id")).toList();
		assertEquals(List.of("bar", "score"), ids.stream().sorted().toList(), "no page, and no layer outside its show window");
		Map<String, Object> bar = r.boxes().stream().filter(b -> "bar".equals(b.get("id"))).findFirst().orElseThrow();
		assertEquals(List.of(100.0, 100.0, 200.0, 100.0), List.of(bar.get("x"), bar.get("y"), bar.get("w"), bar.get("h")));
		assertNull(bar.get("advance"));
		Map<String, Object> score = r.boxes().stream().filter(b -> "score".equals(b.get("id"))).findFirst().orElseThrow();
		double adv = (Double)score.get("advance"), top = (Double)score.get("y");
		assertEquals(40.0, (Double)score.get("x"), 0.01, "a free text starts at its x");
		assertTrue(adv > 150 && adv < 400, "a 48 px line of ten characters: " + adv);
		assertEquals(adv, (Double)score.get("w"), 0.01, "no padding");
		assertTrue(top < 300 && top > 300 - 48, "the first baseline is an ascent below its top: " + top);
	}

	@Test
	void widthIsCappedAtTheSourceAndScalesTheLayers()
	{
		BufferedImage full = OverlayRender.draw(overlay(bar()), null, 1920, 1080, 4000, NOW, NOW, false).image();
		assertEquals(1920, full.getWidth());
		assertEquals(1080, full.getHeight());
		BufferedImage half = OverlayRender.draw(overlay(bar()), null, 1920, 1080, 960, NOW, NOW, false).image();
		assertEquals(960, half.getWidth());
		assertEquals(540, half.getHeight());
		assertTrue(alpha(half, 60, 60) > 200 && alpha(half, 160, 60) == 0, "at half the source scale the bar is at 50,50 to 150,100");
	}

	@Test
	void theFrozenNowAndStreamStartAreHonored()
	{
		Map<String, Object> clock = Map.of("id", "clock", "type", "string", "x", 20, "y", 60, "text", "{time:HH:mm:ss}", "timeZone", "UTC", "fontSize", 48, "color", "white", "opacity", 100);
		int[] a = pixels(OverlayRender.draw(overlay(clock), null, 400, 100, 400, NOW, NOW, false).image());
		assertArrayEquals(a, pixels(OverlayRender.draw(overlay(clock), null, 400, 100, 400, NOW, NOW, false).image()), "the same now draws the same");
		assertFalse(java.util.Arrays.equals(a, pixels(OverlayRender.draw(overlay(clock), null, 400, 100, 400, NOW + 1000, NOW, false).image())), "a second later differs");

		Map<String, Object> since = Map.of("id", "st", "type", "string", "x", 20, "y", 60, "text", "{streamtime:mm:ss}", "fontSize", 48, "color", "white", "opacity", 100);
		assertFalse(java.util.Arrays.equals(pixels(OverlayRender.draw(overlay(since), null, 400, 100, 400, NOW, NOW, false).image()),
				pixels(OverlayRender.draw(overlay(since), null, 400, 100, 400, NOW, NOW - 65_000, false).image())), "streamStart moves the stream time");

		Map<String, Object> blink = new LinkedHashMap<>(bar());
		blink.put("blinkSeconds", 1);
		boolean on = !blank(OverlayRender.draw(overlay(blink), null, 400, 300, 400, NOW, NOW, false).image());
		boolean next = !blank(OverlayRender.draw(overlay(blink), null, 400, 300, 400, NOW + 1000, NOW, false).image());
		assertNotEquals(on, next, "blink follows now");
	}

	void picture(String name, int w, int h) throws Exception
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, w, h);
		g.dispose();
		ImageIO.write(img, "png", content.resolve(name).toFile());
	}

	@Test
	void imageLayersAreDrawnFromTheirFilesAtNativeSizeWithNoSize() throws Exception
	{
		picture("logo.png", 40, 20);
		for(Map<String, Object> m: List.of(Map.<String, Object>of("id", "logo", "type", "image", "imageFile", "logo.png", "x", 10, "y", 10, "opacity", 100),
				Map.<String, Object>of("id", "logo", "type", "image", "imageFile", "logo.png", "x", 10, "y", 10, "w", 0, "h", 0, "opacity", 100)))
		{
			BufferedImage r = OverlayRender.draw(overlay(m), "_defaultVHost_", 200, 100, 200, NOW, NOW, false).image();
			assertTrue(alpha(r, 48, 28) > 200 && alpha(r, 51, 31) == 0, "40 x 20 at 10,10: " + m);
		}
		assertEquals(0, ImageFiles.stats().get("heldEntries"), "the render let go of its holds");
	}

	@Test
	void aThumbnailDrawsAPlaceholderForAPageNotOnAirAndOpensNoCapture()
	{
		int open = WebPageCapture.POOL.size();
		OverlayRender.Result r = OverlayRender.draw(overlay(page(), bar()), null, 1920, 1080, 640, NOW, NOW, true);
		assertEquals(open, WebPageCapture.POOL.size(), "no capture opened");
		int x = (int)(300 / 3.0), y = (int)(20 / 3.0);
		assertTrue(alpha(r.image(), x, y) > 150, "the gray box where the page goes");
		assertFalse(r.pages(), "no page frame was drawn");
	}

	@Test
	void aCountdownWithNoTargetShowsTheFadedSample()
	{
		Map<String, Object> cd = Map.of("id", "cd", "type", "string", "x", 10, "y", 60, "text", "Starts in {countdown:m:ss}", "endText", "", "fontSize", 40, "color", "white",
				"opacity", 100);
		assertTrue(OverlayRender.untargetedCounter(new LinkedHashMap<>(cd)));
		BufferedImage thumb = OverlayRender.draw(overlay(cd), null, 600, 100, 600, NOW, NOW, true).image();
		int max = 0;
		for(int y = 0; y < thumb.getHeight(); y++)
		{
			for(int px = 0; px < thumb.getWidth(); px++)
			{
				max = Math.max(max, alpha(thumb, px, y));
			}
		}
		assertTrue(max > 60 && max < 200, "drawn, faded: " + max);
		Map<String, Object> targeted = new LinkedHashMap<>(cd);
		targeted.put("target", "2027-01-01T00:00:00Z");
		assertFalse(OverlayRender.untargetedCounter(targeted));
	}

	@Test
	void thumbnailWidthsSnap()
	{
		assertEquals(320, OverlayRender.thumbWidth(null));
		assertEquals(160, OverlayRender.thumbWidth(100));
		assertEquals(320, OverlayRender.thumbWidth(320));
		assertEquals(640, OverlayRender.thumbWidth(321));
		assertEquals(640, OverlayRender.thumbWidth(5000));
	}

	@Test
	void thumbnailsAreCachedUntilTheFileChanges() throws Exception
	{
		Path dir = Files.createDirectories(content.resolve("overlays"));
		OverlayStore.write(dir, "Score", overlay(bar()), u -> null);
		Map<String, Object> first = NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Score", dir, content, 320, NOW);
		assertEquals(false, first.get("cached"));
		assertEquals(320, first.get("width"));
		assertEquals(180, first.get("height"));
		assertEquals(true, NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Score", dir, content, 320, NOW + 10).get("cached"));
		Path f = OverlayStore.file(dir, "Score");
		Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
		assertEquals(false, NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Score", dir, content, 320, NOW + 2000).get("cached"), "the file changed");

		// a page layer's thumbnail goes stale after 60 s, so a frame opened since is picked up
		OverlayStore.write(dir, "Page", overlay(page()), u -> null);
		NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Page", dir, content, 160, NOW + 3000);
		assertEquals(true, NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Page", dir, content, 160, NOW + 4000).get("cached"));
		assertEquals(false, NamedOverlayThumbnailConfig.thumbnail("vh", "live", "Page", dir, content, 160, NOW + 70_000).get("cached"));
	}

	@Test
	void drawsPastTheRateAnswer429()
	{
		OverlayRender.Gate gate = new OverlayRender.Gate(4);
		long[] t = { NOW };
		gate.clock = () -> t[0];
		for(int i = 0; i < 4; i++)
		{
			assertDoesNotThrow(() -> gate.admit("alex", "renders"));
		}
		assertEquals(429, assertThrows(TemplateFiles.Refused.class, () -> gate.admit("alex", "renders")).status());
		assertDoesNotThrow(() -> gate.admit("someone else", "renders"));
		t[0] += 1000;
		assertDoesNotThrow(() -> gate.admit("alex", "renders"));
	}

	static void assertDoesNotThrow(org.junit.jupiter.api.function.Executable e)
	{
		org.junit.jupiter.api.Assertions.assertDoesNotThrow(e);
	}
}
