/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig;
import com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateValidator;
import com.wowza.wms.transcoder.model.TranscoderSessionVideoEncode;
import com.wowza.wms.transcoder.model.TranscoderStreamDestination;
import com.wowza.wms.transcoder.model.TranscoderStreamDestinationVideo;
import com.wowza.wms.transcoder.model.TranscoderStreamSourceVideo;
import com.wowza.wms.transcoder.model.TranscoderVideoOverlayFrame;

/**
 * Several overlays on one stream (plan 3.9), through the module's own draw loop with WSE's source and encode objects
 * recording what they are given: each slot at its own index, a higher slot later, clearing one slot alone, an
 * encode-only slot, the first-use transparent fill per slot, and the OverlayStudioIndex check.
 */
class SlotsDrawTest
{
	static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	final List<OverlayImage> images = new ArrayList<>();

	/** What WSE was told, in order: "add 16", "clear 18". */
	static final class Source extends TranscoderStreamSourceVideo
	{
		final List<String> calls = new ArrayList<>();

		@Override
		public void addOverlay(int index, TranscoderVideoOverlayFrame overlay)
		{
			calls.add("add " + index);
		}

		@Override
		public void clearOverlay(int index)
		{
			calls.add("clear " + index);
		}
	}

	static final class Encode extends TranscoderStreamDestinationVideo
	{
		final List<String> calls = new ArrayList<>();

		Encode()
		{
			super(new TranscoderStreamDestination());
			// a destination starts as pass-through, which carries no overlay
			setCodec(com.wowza.wms.vhost.IVHost.CODEC_VIDEO_H264);
		}

		@Override
		public void addOverlay(int index, TranscoderVideoOverlayFrame overlay)
		{
			calls.add("add " + index);
		}

		@Override
		public void clearOverlay(int index)
		{
			calls.add("clear " + index);
		}
	}

	static void set(Object o, String field, Object value) throws Exception
	{
		Field f = OverlayStudioModule.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, value);
	}

	OverlayStudioModule module(boolean fill) throws Exception
	{
		OverlayStudioModule m = new OverlayStudioModule();
		set(m, "logger", WMSLoggerFactory.getLogger(SlotsDrawTest.class));
		set(m, "initTransparentOverlay", fill);
		return m;
	}

	/** An overlay with one opaque bar, on the source or only on the encodes listed. */
	OverlayImage image(String... encodes)
	{
		OverlayConfig c = new OverlayConfig();
		c.setOpacity(100);
		c.setOverlaySource(encodes.length == 0);
		c.setOverlayEncodes(new ArrayList<>(List.of(encodes)));
		Map<String, Object> bar = new LinkedHashMap<>(Map.of("id", "bar", "type", "rectangle", "x", 10, "y", 10, "w", 100, "h", 20, "fillColor", "rgb(255,0,0)", "opacity", 100));
		c.addObject(JSON.convertValue(bar, ObjectConfig.class));
		c.applyDefaults();
		OverlayImage img = new OverlayImage(0, 0, 640, 360, c, false);
		// as OverlayConfig.saveNewObject does (with applyDefaults above): an overlay with no content id draws nothing
		c.resetUuid();
		images.add(img);
		return img;
	}

	@AfterEach
	void dispose()
	{
		images.forEach(OverlayImage::dispose);
	}

	@Test
	void eachSlotDrawsAtItsOwnIndexAndClearsAlone() throws Exception
	{
		OverlayStudioModule m = module(false);
		OverlayStudioModule.OverlayTranscoderVideoDecoderNotifier n = m.new OverlayTranscoderVideoDecoderNotifier("cam1", 640, 360);
		Source src = new Source();
		m.setOverlayImage("cam1", 1, image());
		m.setOverlayImage("cam1", 3, image());
		n.onBeforeScaleFrame(null, src, 1);
		assertEquals(List.of("add 16", "add 18"), src.calls, "slot 1 at 16, slot 3 at 18, in slot order so 18 is on top");

		src.calls.clear();
		n.onBeforeScaleFrame(null, src, 2);
		assertEquals(List.of(), src.calls, "nothing changed, nothing sent again");

		m.clearOverlayImage("cam1", 3);
		n.onBeforeScaleFrame(null, src, 3);
		assertEquals(List.of("clear 18"), src.calls, "taking slot 3 off leaves slot 1 as it is");
		assertNotNull(m.getOverlayImage("cam1", 1));
		assertNull(m.getOverlayImage("cam1", 3));
		assertTrue(m.getOverlayImage("cam1") == m.getOverlayImage("cam1", 1), "the per-stream REST overlay is slot 1");
	}

	@Test
	void aSlotOnOneEncodeDrawsThereAndNotOnTheSource() throws Exception
	{
		OverlayStudioModule m = module(false);
		OverlayStudioModule.OverlayTranscoderVideoDecoderNotifier n = m.new OverlayTranscoderVideoDecoderNotifier("cam1", 640, 360);
		Encode hd = new Encode(), sd = new Encode();
		n.addEncoder("720p", "cam1_720p", new TranscoderSessionVideoEncode("720p"), hd, 640, 360);
		n.addEncoder("360p", "cam1_360p", new TranscoderSessionVideoEncode("360p"), sd, 640, 360);
		Source src = new Source();
		m.setOverlayImage("cam1", 2, image("720p"));
		n.onBeforeScaleFrame(null, src, 1);
		assertEquals(List.of("add 17"), hd.calls);
		assertEquals(List.of(), sd.calls);
		assertEquals(List.of(), src.calls);
	}

	@Test
	void eachSlotGetsTheTransparentFillOnItsFirstUse() throws Exception
	{
		OverlayStudioModule m = module(true);
		OverlayStudioModule.OverlayTranscoderVideoDecoderNotifier n = m.new OverlayTranscoderVideoDecoderNotifier("cam1", 640, 360);
		Source src = new Source();
		n.onBeforeScaleFrame(null, src, 0);
		assertEquals(List.of("add 16"), src.calls, "slot 1 on the first frame, as before");
		src.calls.clear();
		m.setOverlayImage("cam1", 2, image());
		n.onBeforeScaleFrame(null, src, 50);
		assertEquals(List.of("add 17"), src.calls, "slot 2 added to a running stream: its fill first");
		src.calls.clear();
		n.onBeforeScaleFrame(null, src, 51);
		assertEquals(List.of("add 17"), src.calls, "then its overlay");
		src.calls.clear();
		n.onBeforeScaleFrame(null, src, 52);
		assertEquals(List.of(), src.calls, "and the fill only once");
	}

	@Test
	@SuppressWarnings("unchecked")
	void statusListsEverySlotAndWhichEncodesCarryEach() throws Exception
	{
		OverlayStudioModule m = module(false);
		OverlayStudioModule.OverlayTranscoderVideoDecoderNotifier n = m.new OverlayTranscoderVideoDecoderNotifier("cam1", 640, 360);
		n.addEncoder("720p", "cam1_720p", new TranscoderSessionVideoEncode("720p"), new Encode(), 640, 360);
		n.addEncoder("360p", "cam1_360p", new TranscoderSessionVideoEncode("360p"), new Encode(), 640, 360);
		m.setOverlayImage("cam1", 1, image());
		m.setOverlayImage("cam1", 2, image("720p"));
		Map<String, Object> st = m.streamStatus("cam1");
		List<Map<String, Object>> slots = (List<Map<String, Object>>)st.get("overlays");
		assertEquals(List.of(1, 2), slots.stream().map(x -> x.get("slot")).toList());
		assertEquals(List.of(16, 17), slots.stream().map(x -> x.get("index")).toList());
		assertEquals("stream", slots.get(0).get("source"), "no template here: set over per-stream REST");
		assertEquals(true, slots.get(1).get("configured"));
		List<Map<String, Object>> encodes = (List<Map<String, Object>>)((Map<String, Object>)st.get("transcoder")).get("encodes");
		assertEquals(List.of(1, 2), encodes.get(0).get("slots"), "720p carries both");
		assertEquals(List.of(1), encodes.get(1).get("slots"), "360p only the source overlay");
		assertEquals(true, encodes.get(1).get("overlay"));
	}

	@Test
	void overlayStudioIndexStaysClearOfTheTemplatesImages()
	{
		assertNull(OverlayStudioModule.indexProblem(0), "not set");
		assertNull(OverlayStudioModule.indexProblem(9));
		assertNull(OverlayStudioModule.indexProblem(16));
		for(int i: new int[] { 1, 5, 8 })
		{
			String p = OverlayStudioModule.indexProblem(i);
			assertNotNull(p, "index " + i);
			assertTrue(p.contains("1 to 8") && p.contains("using 16"), p);
		}
	}

	@Test
	void slotsAreReadFromTheTranscodersProperties()
	{
		Map<String, String> props = new LinkedHashMap<>();
		props.put("overlayStudio1", " Score ");
		props.put("overlayStudioEncodes1", "720p, 360p");
		props.put("overlayStudio3", "Ticker");
		props.put("overlayStudio4", "Score");
		assertEquals(List.of(new TemplateValidator.Slot(1, "Score", List.of("720p", "360p")), new TemplateValidator.Slot(3, "Ticker", List.of())),
				OverlayStudioModule.slotsFrom(props::get), "slot 4 names Score again and is skipped, as the validator does");
		assertTrue(OverlayStudioModule.slotsFrom(k -> null).isEmpty());
	}
}
