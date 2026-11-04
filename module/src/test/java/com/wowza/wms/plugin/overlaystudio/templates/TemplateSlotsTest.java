/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Up to four overlays per template (plan 3.9): read, written, added in the next free slot, found in any slot. */
class TemplateSlotsTest
{
	@TempDir
	Path dir;

	static TemplateValidator.Slot slot(int n, String name, String... encodes)
	{
		return new TemplateValidator.Slot(n, name, List.of(encodes));
	}

	TemplateValidator.Result validate(String template) throws Exception
	{
		try(InputStream in = Files.newInputStream(dir.resolve(template + ".xml")))
		{
			return TemplateValidator.validate(in);
		}
	}

	@Test
	void fourSlotsAreWrittenAndReadInSlotOrder() throws Exception
	{
		assertEquals(TemplateValidator.PROP_OVERLAY, TemplateValidator.propOverlay(1));
		assertEquals(TemplateValidator.PROP_ENCODES, TemplateValidator.propEncodes(1));
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "t", false);
		TemplateFiles.setOverlays(dir, "t", List.of(slot(4, "Ticker"), slot(1, "Score", "720p"), slot(2, "Logo")));
		TemplateValidator.Result r = validate("t");
		assertEquals(List.of(slot(1, "Score", "720p"), slot(2, "Logo"), slot(4, "Ticker")), r.overlays());
		assertEquals("Score", r.overlay(), "slot 1, as before");
		assertEquals(List.of("720p"), r.overlayEncodes());
		assertTrue(r.usable());
		assertEquals(List.of(Map.of("name", "Score", "encodes", List.of("720p"), "slot", 1), Map.of("name", "Logo", "encodes", List.of(), "slot", 2),
				Map.of("name", "Ticker", "encodes", List.of(), "slot", 4)), r.toMap().get("overlays"));

		TemplateFiles.setOverlays(dir, "t", List.of(slot(3, "Logo")));
		assertEquals(List.of(slot(3, "Logo")), validate("t").overlays(), "every slot replaced");
		assertNull(validate("t").overlay(), "nothing in slot 1");
		TemplateFiles.setOverlays(dir, "t", List.of());
		assertTrue(validate("t").overlays().isEmpty());
		assertFalse(Files.readString(dir.resolve("t.xml")).contains("overlayStudio"), "no properties left behind");
	}

	@Test
	void slotOneAloneKeepsTheOthers() throws Exception
	{
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "t", false);
		TemplateFiles.setOverlays(dir, "t", List.of(slot(1, "Score"), slot(2, "Logo")));
		TemplateFiles.setOverlay(dir, "t", "Clock", List.of("720p"));
		assertEquals(List.of(slot(1, "Clock", "720p"), slot(2, "Logo")), validate("t").overlays());
		TemplateFiles.setOverlay(dir, "t", null, List.of());
		assertEquals(List.of(slot(2, "Logo")), validate("t").overlays());
		assertEquals(400, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.setOverlay(dir, "t", "Logo", List.of())).status(),
				"an overlay already in slot 2 is not also put in slot 1");
	}

	@Test
	void anOverlayNamedTwiceInATemplateIsAnError() throws Exception
	{
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "t", false);
		TemplateFiles.setOverlays(dir, "t", List.of(slot(1, "Score"), slot(2, "Bravo")));
		Path f = dir.resolve("t.xml");
		// as a hand edit would leave it
		Files.writeString(f, Files.readString(f).replace("Bravo", "Score"));
		TemplateValidator.Result r = validate("t");
		assertFalse(r.usable());
		assertTrue(r.problems().stream().anyMatch(p -> p.level().equals("error") && p.key().endsWith("overlayStudio2")), r.problems().toString());
		assertEquals(List.of(slot(1, "Score")), r.overlays(), "the first is kept");
	}

	@Test
	void requestedSlotsAreChecked()
	{
		Object[] bad = { "Score", List.of(Map.of("name", "a"), Map.of("name", "b"), Map.of("name", "c"), Map.of("name", "d"), Map.of("name", "e")),
				List.of(Map.of("name", "a", "slot", 0)), List.of(Map.of("name", "a", "slot", 5)), List.of(Map.of("name", "a", "slot", 1.5)),
				List.of(Map.of("name", "a", "slot", 2), Map.of("name", "b", "slot", 2)), List.of(Map.of("name", "a"), Map.of("name", "a")),
				List.of(Map.of("name", "a", "encodes", "720p")), List.of(Map.of("encodes", List.of())), List.of(Map.of("name", "../x")) };
		for(Object b: bad)
		{
			TemplateFiles.Refused r = assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.slotsFrom(b), b.toString());
			assertEquals(400, r.status(), b.toString());
		}
		assertEquals(List.of(slot(1, "a"), slot(3, "b", "360p")), assertDoesNotThrow(List.of(Map.of("name", "a"), Map.of("name", "b", "slot", 3, "encodes", List.of("360p")))));
	}

	static List<TemplateValidator.Slot> assertDoesNotThrow(Object raw)
	{
		return org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> TemplateFiles.slotsFrom(raw));
	}

	@Test
	void aCopyKeepsTheSourcesOverlaysAndAddsTheNextSlot() throws Exception
	{
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "base", false);
		TemplateFiles.setOverlays(dir, "base", List.of(slot(1, "Score")));
		TemplateFiles.copyWithOverlay(dir, "base", "copy", "Logo", List.of("720p"));
		assertEquals(List.of(slot(1, "Score"), slot(2, "Logo", "720p")), validate("copy").overlays());
		assertEquals(List.of(slot(1, "Score")), validate("base").overlays(), "the source is as it was");

		TemplateFiles.copyWithOverlay(dir, "base", "again", "Score", List.of("360p"));
		assertEquals(List.of(slot(1, "Score", "360p")), validate("again").overlays(), "already there: its encodes change, no second slot");

		TemplateFiles.setOverlays(dir, "base", List.of(slot(1, "A"), slot(2, "B"), slot(3, "C"), slot(4, "D")));
		TemplateFiles.Refused full = assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.copyWithOverlay(dir, "base", "full", "E", List.of()));
		assertEquals(409, full.status());
		assertFalse(Files.exists(dir.resolve("full.xml")), "no copy is left behind");
	}

	@Test
	void inPlaceAddsTheOverlayInTheNextFreeSlot() throws Exception
	{
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "mine", false);
		Path f = dir.resolve("mine.xml");
		// a customer's own template: not written by the module
		Files.writeString(f, Files.readString(f).replace(TemplateValidator.MANAGED_MARKER, "Our 720p ladder"));
		TemplateFiles.enableInPlace(dir, "mine", "Score", List.of());
		TemplateFiles.enableInPlace(dir, "mine", "Logo", List.of("720p"));
		assertEquals(List.of(slot(1, "Score"), slot(2, "Logo", "720p")), validate("mine").overlays());
		TemplateFiles.setOverlays(dir, "mine", List.of(slot(2, "Logo")));
		TemplateFiles.enableInPlace(dir, "mine", "Clock", List.of());
		assertEquals(List.of(slot(1, "Clock"), slot(2, "Logo")), validate("mine").overlays(), "the lowest free slot");
	}

	@Test
	void templatesUsingFindsAnOverlayInAnySlot() throws Exception
	{
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "a", false);
		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "b", false);
		TemplateFiles.setOverlays(dir, "a", List.of(slot(3, "Ticker")));
		TemplateFiles.setOverlays(dir, "b", List.of(slot(1, "Score")));
		assertEquals(List.of("a"), TemplateFiles.templatesUsing(dir, "Ticker"));
		assertEquals(List.of("b"), TemplateFiles.templatesUsing(dir, "Score"));
	}

	@Test
	void theBuilderWritesEverySlot() throws Exception
	{
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", "720p");
		out.put("kind", "video");
		out.put("height", 720);
		out.put("videoBitrate", 3000000);
		out.put("codec", "H.264");
		out.put("profile", "high");
		out.put("overlay", true);
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("hardware", "cpu");
		b.put("outputs", new ArrayList<>(List.of(out)));
		b.put("overlays", List.of(Map.of("name", "Score"), Map.of("name", "Ticker", "slot", 3, "encodes", List.of("720p"))));
		String xml = TemplateBuilder.xml(TemplateBuilder.parse("built", b));
		TemplateValidator.Result r = TemplateValidator.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		assertEquals(List.of(slot(1, "Score"), slot(3, "Ticker", "720p")), r.overlays(), xml);

		b.put("overlays", List.of(Map.of("name", "Score"), Map.of("name", "Score", "slot", 2)));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("built", b));
		// slot 1 on the outputs it names, and an output it names that is not there shows it nowhere
		b.put("overlays", List.of(Map.of("name", "Score", "encodes", List.of("1080p"))));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("built", b), "no output shows Score");
	}
}
