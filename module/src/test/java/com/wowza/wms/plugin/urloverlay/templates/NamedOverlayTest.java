/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamedOverlayTest
{
	@TempDir
	Path dir;

	private static Map<String, Object> page(String id, String url)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("type", "webPage");
		m.put("url", url);
		m.put("x", 1200);
		m.put("y", 40);
		return m;
	}

	private static Map<String, Object> overlay(Object... objects)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("refreshRate", 0.5);
		m.put("overlaySource", false);
		m.put("overlayEncodes", List.of("ignored"));
		m.put("streamName", "cam1");
		List<Object> l = new ArrayList<>(List.of(objects));
		m.put("objects", l);
		return m;
	}

	private static String noPrivate(String url)
	{
		return url == null || !url.startsWith("https://") ? "only https in this test" : null;
	}

	@Test
	void filesLiveUnderConfModulesOneFolderPerApplication() throws Exception
	{
		assertEquals(dir.resolve("conf.modules").resolve("urloverlay").resolve("live"), OverlayStore.dir(dir, "live"));
		assertEquals(dir.resolve("conf.modules/urloverlay/live/streams/_definst_/cam1.json"), ModuleFiles.streamFile(dir, "live", "_definst_", "cam1"));
		// an underscore in the application name cannot collide with an overlay name, as a flat file name would
		assertFalse(OverlayStore.dir(dir, "live_2").startsWith(OverlayStore.dir(dir, "live")));
	}

	@Test
	void writeKeepsOverlayFieldsAndDropsTemplateAndStreamOnes() throws Exception
	{
		Path d = OverlayStore.dir(dir, "live");
		Map<String, Object> saved = OverlayStore.write(d, "Scoreboard", overlay(page("score", "https://scores.example/bug")), NamedOverlayTest::noPrivate);
		assertEquals(0.5, saved.get("refreshRate"));
		assertFalse(saved.containsKey("overlaySource"), "which encodes carry it belongs to the template");
		assertFalse(saved.containsKey("overlayEncodes"));
		assertFalse(saved.containsKey("streamName"));
		assertEquals(List.of("Scoreboard"), OverlayStore.list(d));
		assertEquals("https://scores.example/bug", ((Map<?, ?>)((List<?>)OverlayStore.read(d, "Scoreboard").get("objects")).get(0)).get("url"));
	}

	@Test
	void writeRefusals() throws Exception
	{
		Path d = OverlayStore.dir(dir, "live");
		assertEquals(400, assertThrows(TemplateFiles.Refused.class,
				() -> OverlayStore.write(d, "a", overlay(page("p", "http://10.0.0.1/")), NamedOverlayTest::noPrivate)).status());
		assertEquals(400, assertThrows(TemplateFiles.Refused.class,
				() -> OverlayStore.write(d, "a", overlay(page("p", "https://a/"), page("p", "https://b/")), NamedOverlayTest::noPrivate)).status());
		assertEquals(400, assertThrows(TemplateFiles.Refused.class,
				() -> OverlayStore.write(d, "../x", overlay(page("p", "https://a/")), NamedOverlayTest::noPrivate)).status());
		Map<String, Object> noObjects = overlay();
		noObjects.remove("objects");
		assertEquals(400, assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.write(d, "a", noObjects, NamedOverlayTest::noPrivate)).status());
		assertFalse(Files.exists(OverlayStore.file(d, "a")), "nothing written on a refusal");
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.read(d, "nope")).status());
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> OverlayStore.delete(d, "nope")).status());
	}

	@Test
	void templateCarriesOverlayAndItsCopyToo() throws Exception
	{
		TemplateFiles.writeStarter(dir, "urloverlay-cpu", "scoreboard-720p", false);
		String before = Files.readString(dir.resolve("scoreboard-720p.xml"));
		TemplateFiles.setOverlay(dir, "scoreboard-720p", "Scoreboard", List.of("720p"));
		TemplateValidator.Result r = validate(dir.resolve("scoreboard-720p.xml"));
		assertEquals("Scoreboard", r.overlay());
		assertEquals(List.of("720p"), r.overlayEncodes());
		assertTrue(r.usable());
		String after = Files.readString(dir.resolve("scoreboard-720p.xml"));
		assertTrue(after.contains("<!-- Required for URL overlays."), "comments kept");
		assertTrue(after.contains("\t\t<Properties>\n\t\t\t<Property>\n\t\t\t\t<Name>urlOverlay</Name>"), () -> "indented like the file: " + after.substring(Math.max(0, after.indexOf("</Encodes>"))).replace("\t", "<TAB>").replace("\r", "<CR>"));
		assertEquals(before.length() > 0, true);

		// the stream's copy carries the property, so cam1 gets the overlay without renaming anything
		TemplateFiles.copyForStream(dir, "scoreboard-720p", "cam1", false);
		assertEquals("Scoreboard", validate(dir.resolve("cam1.xml")).overlay());
		assertEquals(List.of("scoreboard-720p", "cam1").stream().sorted().toList(), TemplateFiles.templatesUsing(dir, "Scoreboard"));
		assertEquals(List.of(Map.of("template", "cam1", "stream", "cam1")), TemplateFiles.copiesOf(dir, "scoreboard-720p"));

		// setting it again replaces, it does not add a second property
		TemplateFiles.setOverlay(dir, "scoreboard-720p", "Lower", List.of());
		String twice = Files.readString(dir.resolve("scoreboard-720p.xml"));
		assertEquals(1, twice.split("<Name>urlOverlay</Name>", -1).length - 1);
		assertEquals(List.of(), validate(dir.resolve("scoreboard-720p.xml")).overlayEncodes());

		// and null takes it off
		TemplateFiles.setOverlay(dir, "scoreboard-720p", null, null);
		assertNull(validate(dir.resolve("scoreboard-720p.xml")).overlay());
	}

	@Test
	void overlayIsNeverWrittenIntoSomeoneElsesTemplate() throws Exception
	{
		Files.writeString(dir.resolve("transrate.xml"), "<Root><Transcode><Description>WSE</Description></Transcode></Root>");
		assertEquals(409, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.setOverlay(dir, "transrate", "Scoreboard", List.of())).status());
		assertFalse(Files.readString(dir.resolve("transrate.xml")).contains("urlOverlay"));
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.setOverlay(dir, "missing", "Scoreboard", List.of())).status());
	}

	@Test
	void validatorWarnsOnEncodesTheTemplateLacks() throws Exception
	{
		TemplateFiles.writeStarter(dir, "urloverlay-cpu", "t", false);
		TemplateFiles.setOverlay(dir, "t", "Scoreboard", List.of("1080p"));
		TemplateValidator.Result r = validate(dir.resolve("t.xml"));
		assertTrue(r.problems().stream().anyMatch(p -> p.level().equals("warning") && p.message().contains("1080p")));
	}

	@Test
	void resolveFollowsWseFirstMatch()
	{
		List<String> files = List.of("cam1.xml", "vif-gpu-eva.xml");
		assertEquals("cam1.xml", TemplateLists.resolve("${SourceStreamName}.xml,vif-gpu-eva.xml", "cam1", files::contains));
		assertEquals("vif-gpu-eva.xml", TemplateLists.resolve("${SourceStreamName}.xml,vif-gpu-eva.xml", "cam2", files::contains));
		assertNull(TemplateLists.resolve("${SourceStreamName}.xml", "cam2", files::contains));
		assertNull(TemplateLists.resolve("", "cam1", files::contains));
	}

	@Test
	void planReportsWhatChangesForOthers() throws Exception
	{
		TemplateFiles.writeStarter(dir, "urloverlay-cpu", "scoreboard-720p", false);
		Files.writeString(dir.resolve("vif-gpu-eva.xml"), "<Root><Transcode><Decode><Video><EnableDynamicOverlays>true</EnableDynamicOverlays></Video></Decode>"
				+ "<Encodes><Encode><Name>videoIntelligence</Name><Enable>true</Enable><Video><Codec>H.264</Codec></Video></Encode></Encodes></Transcode></Root>");
		TemplateOps.Plan p = TemplateOps.plan(dir, "${SourceStreamName}.xml,vif-gpu-eva.xml", "", "stream", "scoreboard-720p", "cam1");
		assertEquals("vif-gpu-eva.xml", p.currentTemplate());
		assertTrue(p.currentIsVif(), "cam1 leaves VIF");
		assertEquals(true, p.toMap().get("transcoderTurnsOn"), "every stream in the app starts transcoding");
		TemplateOps.Plan q = TemplateOps.plan(dir, "${SourceStreamName}.xml,vif-gpu-eva.xml", "transcoder", "stream", "scoreboard-720p", "cam1");
		assertEquals(false, q.toMap().get("transcoderTurnsOn"));
	}

	private static TemplateValidator.Result validate(Path f) throws Exception
	{
		try(InputStream in = Files.newInputStream(f))
		{
			return TemplateValidator.validate(in);
		}
	}
}
