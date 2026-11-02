/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The template builder and the two ways to give an existing template an overlay.
 */
class TemplateBuilderTest
{
	@TempDir
	Path dir;

	// shaped like WSE's transrate.xml: CRLF, no EnableDynamicOverlays element at all, a pass-through source
	private static final String STOCK = ("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<Root version=\"1\">\n\t<Transcode>\n\t\t<Description>Default transrate.xml file</Description>\n"
			+ "\t\t<Encodes>\n\t\t\t<Encode>\n\t\t\t\t<Name>source</Name>\n\t\t\t\t<Enable>true</Enable>\n\t\t\t\t<StreamName>mp4:${SourceStreamName}_source</StreamName>\n"
			+ "\t\t\t\t<Video>\n\t\t\t\t\t<Codec>PassThru</Codec>\n\t\t\t\t</Video>\n\t\t\t\t<Audio>\n\t\t\t\t\t<Codec>PassThru</Codec>\n\t\t\t\t</Audio>\n\t\t\t</Encode>\n"
			+ "\t\t\t<Encode>\n\t\t\t\t<Name>360p</Name>\n\t\t\t\t<Enable>true</Enable>\n\t\t\t\t<StreamName>mp4:${SourceStreamName}_360p</StreamName>\n"
			+ "\t\t\t\t<Video>\n\t\t\t\t\t<Codec>H.264</Codec>\n\t\t\t\t\t<Implementation>default</Implementation>\n\t\t\t\t\t<Bitrate>365000</Bitrate>\n\t\t\t\t</Video>\n"
			+ "\t\t\t\t<Audio>\n\t\t\t\t\t<Codec>PassThru</Codec>\n\t\t\t\t</Audio>\n\t\t\t</Encode>\n\t\t</Encodes>\n"
			+ "\t\t<Decode>\n\t\t\t<Video>\n\t\t\t\t<Implementation>default</Implementation>\n\t\t\t</Video>\n\t\t</Decode>\n\t</Transcode>\n</Root>\n").replace("\n", "\r\n");

	private static Map<String, Object> out(String name, String kind, int height, long bitrate, boolean overlay)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("kind", kind);
		m.put("height", height);
		m.put("videoBitrate", bitrate);
		m.put("codec", "H.264");
		m.put("profile", "high");
		m.put("audio", "aac");
		m.put("audioBitrate", 96000);
		m.put("overlay", overlay);
		m.put("enabled", true);
		return m;
	}

	private static Map<String, Object> body(String hardware, String overlay, List<Map<String, Object>> outputs)
	{
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("hardware", hardware);
		b.put("overlay", overlay);
		b.put("outputs", new ArrayList<>(outputs));
		return b;
	}

	private static TemplateValidator.Result validate(String xml)
	{
		return TemplateValidator.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void aBuiltTemplateIsUsableAndDrawsOnceWhenEveryOutputShowsTheOverlay() throws Exception
	{
		TemplateBuilder.Spec s = TemplateBuilder.parse("score", body("cpu", "Scoreboard",
				List.of(out("720p", "video", 720, 3000000, true), out("360p", "video", 360, 365000, true))));
		String xml = TemplateBuilder.xml(s);
		TemplateValidator.Result r = validate(xml);
		assertTrue(r.usable(), r.problems().toString());
		assertTrue(r.managed());
		assertTrue(r.dynamicOverlays());
		assertEquals("Scoreboard", r.overlay());
		assertEquals(List.of(), r.overlayEncodes(), "every output: drawn on the source, before scaling");
		assertEquals("3000000", r.encodes().get(0).get("bitrate"));
		assertEquals("720", r.encodes().get(0).get("height"));
		assertEquals("high", r.encodes().get(0).get("profile"));
		assertTrue(xml.contains("<Name>abr</Name>") && xml.contains("${SourceStreamName}_abr"), "the adaptive group");
		assertTrue(xml.indexOf("<Encodes>") < xml.indexOf("<StreamNameGroups>") && xml.indexOf("<StreamNameGroups>") < xml.indexOf("<Properties>"), "WSE's order");
	}

	@Test
	void someOutputsWithTheOverlayAreListed() throws Exception
	{
		TemplateBuilder.Spec s = TemplateBuilder.parse("score", body("cpu", "Scoreboard",
				List.of(out("source", "passthrough", 0, 0, false), out("720p", "video", 720, 3000000, true), out("360p", "video", 360, 365000, false))));
		TemplateValidator.Result r = validate(TemplateBuilder.xml(s));
		assertTrue(r.usable(), r.problems().toString());
		assertEquals(List.of("720p"), r.overlayEncodes());
		assertEquals("PassThru", r.encodes().get(0).get("codec"));
	}

	@Test
	void audioOnlyAndHardware() throws Exception
	{
		String cpu = TemplateBuilder.xml(TemplateBuilder.parse("a", body("cpu", null, List.of(out("aac", "audio", 0, 0, false)))));
		assertTrue(cpu.contains("<Codec>Disable</Codec>"), "audio only: video Disable, as WSE reads it");
		assertFalse(cpu.contains("StreamNameGroups"), "one output: no adaptive group");
		String gpu = TemplateBuilder.xml(TemplateBuilder.parse("g", body("nvidia", "X", List.of(out("720p", "video", 720, 3000000, true)))));
		assertTrue(gpu.contains("NVCUVID_EVA") && gpu.contains("CUDA_EVA") && gpu.contains("NVENC_EVA"), "VIF's EVA names");
		assertTrue(validate(gpu).usable());
		Map<String, Object> adv = body("nvidia", "X", List.of(out("720p", "video", 720, 3000000, true)));
		adv.put("deinterlace", true);
		adv.put("gpuId", 1);
		adv.put("keyInterval", 48);
		TemplateValidator.Result ra = validate(TemplateBuilder.xml(TemplateBuilder.parse("g", adv)));
		assertTrue(ra.deinterlace());
		assertEquals("1", ra.gpuId());
		assertEquals("48", ra.encodes().get(0).get("keyInterval"), "Advanced settings round-trip through the validator, which the page reads back");
		assertFalse(gpu.contains("StreamNameGroups"), "one video output: nothing to switch between");
	}

	@Test
	void studioOutputsRoundTripThroughTheValidator() throws Exception
	{
		Map<String, Object> hd = out("720p", "video", 720, 3000000, true);
		hd.put("rateControl", "vbr");
		hd.put("peakBitrate", 4500000);
		hd.put("speed", "quality");
		hd.put("skip", 1);
		hd.put("fit", "letterbox");
		hd.put("width", 1280);
		hd.put("region", Map.of("x", 192, "y", 108, "w", 1536, "h", 864));
		hd.put("channels", 1);
		hd.put("sampleRate", 44100);
		hd.put("captions", false);
		hd.put("params", List.of(Map.of("name", "mainconcept.adaptive_b_frames", "value", "1")));
		Map<String, Object> h265 = out("360p", "video", 360, 365000, true);
		h265.put("codec", "H.265");
		Map<String, Object> aud = out("audio", "audio", 0, 0, false);
		Map<String, Object> b = body("nvidia", "Scoreboard", List.of(hd, h265, aud));
		b.put("groups", List.of(Map.of("name", "all", "members", List.of(Map.of("output", "720p"), Map.of("output", "360p"))),
				Map.of("name", "radio", "members", List.of(Map.of("output", "audio", "only", "audio")))));
		String xml = TemplateBuilder.xml(TemplateBuilder.parse("studio", b));
		TemplateValidator.Result r = validate(xml);
		assertTrue(r.usable(), r.problems().toString());
		assertEquals("nvidia", r.hardware(), "read back from the implementation names");
		Map<String, Object> e = r.encodes().get(0);
		assertEquals("vbr", e.get("rateControl"));
		assertEquals("4500000", e.get("peakBitrate"));
		assertEquals("quality", e.get("speed"));
		assertEquals("1", e.get("skipFrameCount"));
		assertEquals("letterbox", e.get("fitMode"));
		assertEquals("1280", e.get("width"));
		assertEquals("192,108,1536,864", e.get("sourceRectangle"));
		assertEquals("1", e.get("audioChannels"));
		assertEquals("44100", e.get("audioSampleRate"));
		assertEquals(false, e.get("captions"));
		assertEquals("1", ((Map<?, ?>)e.get("parameters")).get("mainconcept.adaptive_b_frames"));
		assertTrue(xml.contains("<Implementation>NVENC_EVA</Implementation>") && xml.contains("<Codec>H.265</Codec>"), "H.265 on NVIDIA through EVA");
		// the elements WSE itself reads, not the validator's reading of them: a shared misunderstanding would pass above
		assertTrue(java.util.regex.Pattern.compile("<Name>mainconcept\\.bit_rate_mode</Name>\\s*<Value>2</Value>").matcher(xml).find(), "variable bitrate is mode 2");
		assertTrue(java.util.regex.Pattern.compile("<Name>mainconcept\\.max_bit_rate</Name>\\s*<Value>4500000</Value>").matcher(xml).find(), "the peak");
		assertTrue(xml.contains("<SkipFrameCount>1</SkipFrameCount>"));
		assertTrue(java.util.regex.Pattern.compile("<Resample>\\s*<Enable>true</Enable>\\s*<SampleRate>44100</SampleRate>\\s*<Channels>1</Channels>").matcher(xml).find(), "resampled to mono 44.1 kHz");
		assertEquals(2, r.groups().size());
		assertEquals("audio", ((List<?>)r.groups().get(1).get("members")).stream().map(x -> ((Map<?, ?>)x).get("only")).findFirst().orElse(null));
		Map<String, Object> e2 = r.encodes().get(1);
		assertEquals("default", e2.get("rateControl"), "nothing written for H.265");
		assertEquals(true, e2.get("captions"), "captions kept unless switched off");
		assertEquals(null, e2.get("audioChannels"), "no Resample unless asked");
	}

	@Test
	void imageLayersAndTheFrameRateFloorRoundTrip() throws Exception
	{
		Map<String, Object> b = body("cpu", "Scoreboard", List.of(out("720p", "video", 720, 3000000, true), out("360p", "video", 360, 365000, true),
				out("aud", "audio", 0, 0, false)));
		b.put("minFrameRate", 25);
		b.put("images", List.of(
				Map.of("file", "overlaystudio/logo.png", "where", "all", "x", 20, "y", 10, "align", "right,top", "opacity", 80),
				Map.of("file", "bug.png", "where", "outputs", "outputs", List.of("720p", "360p"), "width", 120, "height", 60, "align", "left,bottom", "refresh", true)));
		String xml = TemplateBuilder.xml(TemplateBuilder.parse("img", b));
		TemplateValidator.Result r = validate(xml);
		assertTrue(r.usable(), r.problems().toString());
		assertEquals("25.0", r.minFrameRate());
		assertEquals(2, r.images().size(), "the per-output layer is one layer on two outputs");
		Map<String, Object> logo = r.images().get(0), bug = r.images().get(1);
		assertEquals("overlaystudio/logo.png", logo.get("file"));
		assertEquals("all", logo.get("where"));
		assertEquals("right,top", logo.get("align"));
		assertEquals("0", logo.get("width"), "the image's own size");
		assertEquals("80", logo.get("opacity"));
		assertEquals(List.of("720p", "360p"), bug.get("outputs"));
		assertEquals("120", bug.get("width"));
		assertEquals(true, bug.get("refresh"));
		assertTrue(xml.contains("<ImagePath>${com.wowza.wms.context.VHostConfigHome}/content/overlaystudio/logo.png</ImagePath>"));
		assertTrue(xml.contains("<Width>${ImageWidth}</Width>"), "WSE reads a $ value as the image's size");
		assertTrue(xml.indexOf("<Index>1</Index>") < xml.indexOf("<Encodes>"), "an every-output layer is on the decoder");
		assertFalse(xml.contains("<Index>" + 16 + "</Index>"), "never the overlay's default index");
	}

	@Test
	void unusableImageLayersAreRefused()
	{
		List<Map<String, Object>> outs = List.of(out("720p", "video", 720, 3000000, true), out("aud", "audio", 0, 0, false));
		for(Map<String, Object> bad: List.of(Map.<String, Object>of("file", "../conf/Server.xml"), Map.<String, Object>of("file", "logo.svg"),
				Map.<String, Object>of("file", "logo.png", "align", "middle,top"), Map.<String, Object>of("file", "logo.png", "where", "outputs", "outputs", List.of("1080p")),
				Map.<String, Object>of("file", "logo.png", "where", "outputs", "outputs", List.of("aud")), Map.<String, Object>of("file", "logo.png", "where", "outputs"),
				Map.<String, Object>of("file", "logo.png", "opacity", 150)))
		{
			Map<String, Object> b = body("cpu", null, outs);
			b.put("images", List.of(bad));
			assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", b), bad.toString());
		}
		Map<String, Object> b = body("cpu", null, outs);
		b.put("images", java.util.Collections.nCopies(TemplateBuilder.MAX_IMAGES + 1, Map.of("file", "logo.png")));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", b), "too many layers would reach the overlay's index");
	}

	@Test
	void groupsMustNameRealOutputs()
	{
		Map<String, Object> b = body("cpu", null, List.of(out("720p", "video", 720, 3000000, true)));
		b.put("groups", List.of(Map.of("name", "all", "members", List.of(Map.of("output", "1080p")))));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", b), "a member that is not an output");
		b.put("groups", List.of(Map.of("name", "720p", "members", List.of(Map.of("output", "720p")))));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", b), "a group named like an output");
	}

	@Test
	void unusableSpecsAreRefusedWithAReason()
	{
		Map<String, Object> vp9 = out("720p", "video", 720, 3000000, true);
		vp9.put("codec", "VP9");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("nvidia", "X", List.of(vp9))), "VP8 and VP9 are encoded on the CPU");
		Map<String, Object> h265rc = out("720p", "video", 720, 3000000, true);
		h265rc.put("codec", "H.265");
		h265rc.put("rateControl", "vbr");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", "X", List.of(h265rc))), "H.265 rate control values are not verified");
		// a parameter value is written into the XML as it is: no markup, and no line break to hide markup behind
		for(String value: new String[] { "1</Value><Name>x", "1\n</Value></Parameter></Parameters><Codec>PassThru</Codec>", "1\r<x", "a&b", "1\u0000x" })
		{
			Map<String, Object> o = out("720p", "video", 720, 3000000, true);
			o.put("params", List.of(Map.of("name", "mainconcept.adaptive_b_frames", "value", value)));
			assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", "X", List.of(o))), value);
		}
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", "X", List.of(out("720p", "video", 720, 3000000, false)))), "no output shows the overlay");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", null, List.of(out("a", "video", 720, 3000000, true), out("A", "video", 360, 365000, true)))), "duplicate names");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", null, List.of(out("a b", "video", 720, 3000000, true)))), "unsafe output name");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", null, List.of(out("a", "video", 60, 3000000, true)))), "height too small");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("cpu", null, List.of(out("a", "video", 720, 10, true)))), "bitrate too low");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("x", body("amd", null, List.of(out("a", "video", 720, 3000000, true)))), "unverified hardware");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateBuilder.parse("../x", body("cpu", null, List.of(out("a", "video", 720, 3000000, true)))), "unsafe template name");
	}

	@Test
	void aCopyGetsOverlaysAndTheOriginalStaysAsItIs() throws Exception
	{
		Path stock = dir.resolve("transrate.xml");
		Files.writeString(stock, STOCK);
		assertFalse(validate(STOCK).usable(), "WSE's own cannot carry an overlay as shipped");
		TemplateFiles.copyWithOverlay(dir, "transrate", "transrate-overlay", "Scoreboard", List.of());
		assertEquals(STOCK, Files.readString(stock), "original untouched");
		String copy = Files.readString(dir.resolve("transrate-overlay.xml"));
		TemplateValidator.Result r = validate(copy);
		assertTrue(r.usable(), r.problems().toString());
		assertTrue(r.managed());
		assertEquals("Scoreboard", r.overlay());
		assertTrue(copy.contains("\r\n"), "keeps the file's line endings");
		assertTrue(copy.contains("Default transrate.xml file"), "keeps its own description after the marker");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.copyWithOverlay(dir, "transrate", "transrate", "Scoreboard", List.of()), "never over an unmanaged file");
	}

	@Test
	void inPlaceIsOnlyForACustomersOwnTemplate() throws Exception
	{
		Files.writeString(dir.resolve("transrate.xml"), STOCK);
		assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.enableInPlace(dir, "transrate", "Scoreboard", List.of()), "WSE's own are copied");
		Files.writeString(dir.resolve("vifish.xml"), STOCK.replace("<Name>360p</Name>", "<Name>" + TemplateValidator.VIF_ENCODE + "</Name>"));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.enableInPlace(dir, "vifish", "Scoreboard", List.of()), "VIF's are not mixed");

		Files.writeString(dir.resolve("studio.xml"), STOCK);
		byte[] before = TemplateFiles.enableInPlace(dir, "studio", "Scoreboard", List.of("360p"));
		assertEquals(STOCK, new String(before, StandardCharsets.UTF_8), "the caller gets the original for its backup");
		String now = Files.readString(dir.resolve("studio.xml"));
		TemplateValidator.Result r = validate(now);
		assertTrue(r.usable(), r.problems().toString());
		assertEquals(List.of("360p"), r.overlayEncodes());
		assertTrue(now.contains("overlays switched on in place"), "TemplateSpecConfig refuses rebuilding it by this text");
	}

	@Test
	void replacingABuiltTemplateIsAllowedButNeverAnUnmanagedOne() throws Exception
	{
		TemplateBuilder.Spec s = TemplateBuilder.parse("transrate", body("cpu", null, List.of(out("a", "video", 720, 3000000, true))));
		Files.writeString(dir.resolve("transrate.xml"), STOCK);
		assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.writeBuilt(dir, s, true));
		TemplateBuilder.Spec mine = TemplateBuilder.parse("mine", body("cpu", null, List.of(out("a", "video", 720, 3000000, true))));
		TemplateFiles.writeBuilt(dir, mine, false);
		assertThrows(TemplateFiles.Refused.class, () -> TemplateFiles.writeBuilt(dir, mine, false), "exists: create refuses");
		TemplateFiles.writeBuilt(dir, mine, true);
	}
}
