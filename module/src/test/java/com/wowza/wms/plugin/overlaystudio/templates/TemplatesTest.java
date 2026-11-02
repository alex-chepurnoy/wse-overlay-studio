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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatesTest
{
	@TempDir
	Path dir;

	private static String template(String description, boolean dynamic, String... encodes)
	{
		StringBuilder b = new StringBuilder("<Root><Transcode><Description>" + description + "</Description><Decode><Video><Implementation>default</Implementation>"
				+ "<EnableDynamicOverlays>" + dynamic + "</EnableDynamicOverlays></Video></Decode><Scale><Implementation>default</Implementation></Scale><Encodes>");
		for(String e: encodes)
		{
			String[] p = e.split(":");
			b.append("<Encode><Name>" + p[0] + "</Name><Enable>" + p[1] + "</Enable><StreamName>s_" + p[0] + "</StreamName><Video><Codec>" + p[2]
					+ "</Codec><Implementation>default</Implementation></Video></Encode>");
		}
		return b.append("</Encodes></Transcode></Root>").toString();
	}

	private static TemplateValidator.Result validate(String xml)
	{
		return TemplateValidator.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	private static long errors(TemplateValidator.Result r)
	{
		return r.problems().stream().filter(p -> p.level().equals("error")).count();
	}

	@Test
	void validatorAcceptsDynamicWithOneVideoEncode()
	{
		TemplateValidator.Result r = validate(template("x", true, "a:true:H.264"));
		assertTrue(r.usable());
		assertEquals(0, r.problems().size());
		assertEquals(true, r.encodes().get(0).get("canCarryOverlay"));
	}

	@Test
	void validatorNeedsDynamicOverlays()
	{
		TemplateValidator.Result r = validate(template("x", false, "a:true:H.264"));
		assertFalse(r.usable());
		assertEquals("Decode/Video/EnableDynamicOverlays", r.problems().get(0).key());
	}

	@Test
	void validatorNeedsAnEnabledVideoEncode()
	{
		assertFalse(validate(template("x", true, "a:false:H.264")).usable());
		assertFalse(validate(template("x", true, "a:true:Disable")).usable());
		TemplateValidator.Result pass = validate(template("x", true, "a:true:PassThru"));
		assertFalse(pass.usable());
		// the PassThru warning and the no-carrier error
		assertEquals(2, pass.problems().size());
		assertEquals(1, errors(pass));
		// a pass-through output beside a carrier only warns
		{
			TemplateValidator.Result r = validate(template("x", true, "src:true:PassThru", "ov:true:H.264"));
			assertTrue(r.usable());
			assertEquals(1, r.problems().size());
			assertEquals("warning", r.problems().get(0).level());
		}
	}

	@Test
	void validatorReadsManagedAndRefusesDoctype()
	{
		assertTrue(validate(template("Managed by Overlay Studio: x", true, "a:true:H.264")).managed());
		assertFalse(validate(template("WSE default", true, "a:true:H.264")).managed());
		TemplateValidator.Result r = validate("<!DOCTYPE Root [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]><Root><Transcode>&e;</Transcode></Root>");
		assertFalse(r.usable());
		assertEquals("xml", r.problems().get(0).key());
	}

	@Test
	void everyStarterIsUsableAndManaged() throws Exception
	{
		for(Map<String, Object> s: TemplateFiles.describeStarters())
		{
			assertEquals(true, s.get("usable"), s.get("name") + " " + s.get("problems"));
			assertEquals(true, s.get("managed"), (String)s.get("name"));
		}
		assertEquals(3, TemplateFiles.describeStarters().size());
	}

	@Test
	void listForStreamPutsMatchFirstAndKeepsOneFallback()
	{
		TemplateLists.Change c = TemplateLists.forStream("vif-gpu-eva.xml");
		assertEquals("${SourceStreamName}.xml,vif-gpu-eva.xml", c.after());
		assertTrue(c.changed());
		assertNull(c.replacedFallback());
		assertFalse(TemplateLists.forStream("${SourceStreamName}.xml, vif-gpu-eva.xml").changed());
		assertEquals("${SourceStreamName}.xml", TemplateLists.forStream("").after());
		TemplateLists.Change many = TemplateLists.forStream("a.xml,${SourceStreamName}.xml,b.xml");
		assertEquals("${SourceStreamName}.xml,a.xml", many.after());
		assertEquals("b.xml", many.replacedFallback());
	}

	@Test
	void listForAppReplacesFallbackAndKeepsMatch()
	{
		TemplateLists.Change c = TemplateLists.forApp("${SourceStreamName}.xml,transrate.xml", "overlaystudio-cpu");
		assertEquals("${SourceStreamName}.xml,overlaystudio-cpu.xml", c.after());
		assertEquals("transrate.xml", c.replacedFallback());
		assertEquals("overlaystudio-cpu.xml", TemplateLists.forApp("transrate.xml", "overlaystudio-cpu.xml").after());
		assertNull(TemplateLists.forApp("overlaystudio-cpu.xml", "overlaystudio-cpu").replacedFallback());
	}

	@Test
	void safeNameRefusesPathsAndOddCharacters() throws Exception
	{
		assertEquals("cam-1.hd", TemplateFiles.safeName("cam-1.hd.xml"));
		for(String bad: List.of("../x", "..", "a/b", "a\\b", "", " ", ".hidden", "a..b", "c:x", "x y"))
		{
			assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.safeName(bad), bad);
		}
	}

	@Test
	void starterWriteNeverOverwritesUnmanaged() throws Exception
	{
		Files.writeString(dir.resolve("mine.xml"), template("hand made", true, "a:true:H.264"));
		TemplateFiles.Refused r = assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "mine", true));
		assertEquals(409, r.status());
		assertTrue(Files.readString(dir.resolve("mine.xml")).contains("hand made"));

		TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "ours", false);
		assertEquals(409, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.writeStarter(dir, "overlaystudio-cpu", "ours", false)).status());
		TemplateFiles.writeStarter(dir, "overlaystudio-gpu-eva", "ours", true);
		assertTrue(Files.readString(dir.resolve("ours.xml")).contains("NVENC_EVA"));
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.writeStarter(dir, "nope", "x", false)).status());
	}

	@Test
	void copyForStreamMarksDescription() throws Exception
	{
		Files.writeString(dir.resolve("base.xml"), template("WSE default", true, "a:true:H.264"));
		Path out = TemplateFiles.copyForStream(dir, "base", "cam1", false);
		TemplateValidator.Result r = TemplateValidator.validate(Files.newInputStream(out));
		assertTrue(r.managed());
		assertEquals("Managed by Overlay Studio: copy of base for stream cam1.", r.description());
		assertTrue(r.usable());
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.copyForStream(dir, "missing", "cam2", false)).status());
	}

	@Test
	void planForStreamCopiesAndSetsTranscoder() throws Exception
	{
		Files.writeString(dir.resolve("base.xml"), template("x", true, "a:true:H.264"));
		TemplateOps.Plan p = TemplateOps.plan(dir, "vif-gpu-eva.xml", "", "stream", "base", "cam1");
		assertEquals("cam1.xml", p.writeFile());
		assertEquals("${SourceStreamName}.xml,vif-gpu-eva.xml", p.list().after());
		assertEquals("transcoder", p.liveStreamTranscoderAfter());
		assertTrue(p.changesApp());
		assertEquals(true, p.toMap().get("restartRequired"));
		assertFalse(Files.exists(dir.resolve("cam1.xml")), "planning writes nothing");
		TemplateOps.writeFiles(dir, p);
		assertTrue(Files.exists(dir.resolve("cam1.xml")));
		// a stream with its own template is not copied again
		{
			Files.writeString(dir.resolve("cam1.xml"), template("hand made", true, "a:true:H.264"));
			TemplateOps.Plan p2 = TemplateOps.plan(dir, "${SourceStreamName}.xml,x.xml", "transcoder", "stream", "cam1", "cam1");
			assertNull(p2.writeFile());
			assertFalse(p2.changesApp());
		}
	}

	@Test
	void planRefusals() throws Exception
	{
		Files.writeString(dir.resolve("base.xml"), template("x", true, "a:true:H.264"));
		Files.writeString(dir.resolve("flat.xml"), template("x", false, "a:true:H.264"));
		Files.writeString(dir.resolve("cam1.xml"), template("hand made", true, "a:true:H.264"));
		assertEquals(404, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "", "app", "missing", null)).status());
		assertEquals(422, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "", "app", "flat", null)).status());
		assertEquals(409, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "transrate", "app", "base", null)).status());
		assertEquals(409, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "", "stream", "base", "cam1")).status());
		assertEquals(400, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "", "server", "base", null)).status());
		TemplateOps.Plan ok = TemplateOps.plan(dir, "transrate.xml", "transcoder,transrate", "app", "base", null);
		assertEquals("base.xml", ok.list().after());
		assertEquals("transcoder,transrate", ok.liveStreamTranscoderAfter());
	}

	@Test
	void vifTemplatesAreNeverMixedWithTheOverlay() throws Exception
	{
		String vif = template("", true, "videoIntelligence:true:H.264", "360p:false:H.264");
		TemplateValidator.Result r = validate(vif);
		assertTrue(r.vif());
		assertFalse(r.usable());
		assertFalse(validate(template("", true, "a:true:H.264")).vif());

		Files.writeString(dir.resolve("vif-gpu-eva.xml"), vif);
		Files.writeString(dir.resolve("base.xml"), template("x", true, "a:true:H.264"));
		// a VIF template is not a source for an overlay stream
		assertEquals(422, assertThrows(TemplateFiles.Refused.class, () -> TemplateOps.plan(dir, "", "", "stream", "vif-gpu-eva", "cam1")).status());
		assertFalse(Files.exists(dir.resolve("cam1.xml")));
		// app scope would put the overlay on every stream VIF runs on
		assertEquals(409, assertThrows(TemplateFiles.Refused.class,
				() -> TemplateOps.plan(dir, "${SourceStreamName}.xml,vif-gpu-eva.xml", "transcoder", "app", "base", null)).status());
		// per stream is allowed: that stream leaves VIF, the others keep it
		TemplateOps.Plan p = TemplateOps.plan(dir, "${SourceStreamName}.xml,vif-gpu-eva.xml", "transcoder", "stream", "base", "cam1");
		assertEquals("${SourceStreamName}.xml,vif-gpu-eva.xml", p.list().after());
	}

	@Test
	void scannerListsOnlyAppsWithTheModule() throws Exception
	{
		Path conf = dir.resolve("conf");
		Files.createDirectories(conf.resolve("live"));
		Files.createDirectories(conf.resolve("vod"));
		Files.createDirectories(conf.resolve("broken"));
		Files.writeString(conf.resolve("Application.xml"), "<Root/>");
		Files.writeString(conf.resolve("live/Application.xml"), "<Root><Application><Streams><StreamType>live</StreamType></Streams>"
				+ "<Transcoder><LiveStreamTranscoder>transcoder</LiveStreamTranscoder><Templates>${SourceStreamName}.xml,vif-gpu-eva.xml</Templates></Transcoder>"
				+ "<Modules><Module><Name>x</Name><Class> " + AppScanner.MODULE_CLASS + " </Class></Module></Modules></Application></Root>");
		Path streams = ModuleFiles.appDir(dir, "live").resolve("streams").resolve("_definst_");
		Files.createDirectories(streams);
		Files.writeString(streams.resolve("cam_1.json"), "{}");
		Files.writeString(conf.resolve("vod/Application.xml"), "<Root><Application><Modules><Module><Class>com.example.Other</Class></Module></Modules></Application></Root>");
		Files.writeString(conf.resolve("broken/Application.xml"), "<Root><Application>");
		List<Map<String, Object>> apps = AppScanner.scan(dir);
		assertEquals(3, apps.size(), "every application is listed, with or without the module");
		assertEquals("broken", apps.get(0).get("name"));
		assertTrue(apps.get(0).containsKey("error"));
		Map<String, Object> live = apps.get(1);
		assertEquals("live", live.get("name"));
		assertEquals(true, live.get("installed"));
		assertEquals("vod", apps.get(2).get("name"));
		assertEquals(false, apps.get(2).get("installed"), "the page offers to add the module to it");
		assertEquals("${SourceStreamName}.xml,vif-gpu-eva.xml", live.get("templates"));
		assertEquals(List.of(Map.of("instance", "_definst_", "stream", "cam_1")), live.get("savedStreams"));
	}
}
