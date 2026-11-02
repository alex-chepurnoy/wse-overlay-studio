/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.overlaystudio.capture.Wire.Message;
import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;
import com.wowza.wms.plugin.overlaystudio.egress.EgressRules;

/** The frames between the module and the service: what goes in comes out, PNG bodies untouched, versions checked. */
class ProtocolTest
{
	static Message roundTrip(Map<String, Object> header, byte[] body) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Wire.write(out, header, body);
		return Wire.read(new ByteArrayInputStream(out.toByteArray()));
	}

	@Test
	void aLargeFrameComesBackByteForByte() throws Exception
	{
		byte[] png = new byte[12 * 1024 * 1024];
		new Random(7).nextBytes(png);
		Map<String, Object> h = Wire.ok();
		h.put("reloads", 3);
		h.put("status", Map.of("state", "running", "lastError", "line one\nline \"two\" é✓"));
		Message m = roundTrip(h, png);
		assertArrayEquals(png, m.body(), "no base64, no change");
		assertTrue(m.ok());
		assertEquals(3, m.num("reloads", -1));
		assertEquals("line one\nline \"two\" é✓", ((Map<?, ?>)m.header().get("status")).get("lastError"));
		assertNull(Wire.read(new ByteArrayInputStream(new byte[0])), "a clean end is not an error");
	}

	@Test
	void garbageAndOversizeAreRefused()
	{
		assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".getBytes())));
		byte[] huge = { 0x55, 0x4f, 0x43, 0x31, 0x7f, 0, 0, 0, 0, 0, 0, 0 };
		assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream(huge)));
	}

	@Test
	void pageSettingsAndEgressRulesArriveIntact() throws Exception
	{
		EgressRules rules = new EgressRules(true, "10.20.0.0/16, scoreboard.internal", "ads.example", false, "8080,8443");
		PageRequest sent = new PageRequest("https://scores.example/bug?x=1&y=é", 1280, 720, 0x010203, false, "cam1 score", 2500, 5.5, 100, rules);
		Map<String, Object> h = Wire.header(Wire.OPEN);
		h.putAll(sent.toMap());
		PageRequest got = PageRequest.fromMap(roundTrip(h, null).header());
		assertEquals(sent, got);
		AddressRules a = got.rules().rules();
		assertTrue(a.allowPrivate());
		assertEquals(List.of(80, 443, 8080, 8443), a.allowedPorts());
		assertEquals(List.of("10.20.0.0/16", "scoreboard.internal"), a.allowEntries());
		assertEquals(List.of("ads.example"), a.blockEntries());
		assertFalse(a.blockChromeServices());
		assertEquals(EgressRules.defaults(), EgressRules.fromMap(null), "no rules sent means the safe defaults");
	}

	@Test
	void anotherProtocolIsRefusedWithAMessage()
	{
		Map<String, Object> h = Wire.header(Wire.HELLO);
		h.put("protocol", Wire.PROTOCOL + 1);
		Message answer = CaptureHandler.hello(new Message(h));
		assertFalse(answer.ok());
		assertTrue(answer.str("error").contains("Install the same Overlay Studio version"), answer.str("error"));
		assertEquals((long)Wire.PROTOCOL, answer.num("protocol", -1));
		h.put("protocol", Wire.PROTOCOL);
		assertTrue(CaptureHandler.hello(new Message(h)).ok());
		assertNotNull(Wire.protocolMismatch(null, "module"));
	}

	@Test
	void jsonKeepsTypesAndOrder()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("b", 1);
		m.put("a", List.of(true, 2.5, "x"));
		m.put("n", null);
		assertEquals("{\"b\":1,\"a\":[true,2.5,\"x\"],\"n\":null}", Json.write(m));
		Map<String, Object> back = Json.object(Json.write(m));
		assertEquals(1L, back.get("b"));
		assertEquals(List.of(true, 2.5, "x"), back.get("a"));
		assertTrue(back.containsKey("n"));
		assertThrows(IllegalArgumentException.class, () -> Json.object("{\"a\":}"));
	}
}
