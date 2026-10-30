/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.urloverlay.CaptureClientTest.FakeService;
import com.wowza.wms.plugin.urloverlay.capture.Wire;
import com.wowza.wms.plugin.urloverlay.egress.EgressRules;
import com.wowza.wms.plugin.urloverlay.rest.CaptureLimitConfig;
import com.wowza.wms.plugin.urloverlay.templates.TemplateFiles;

/**
 * The page limit set from the page: given to the service again once per service start, refusals kept for the page,
 * the REST change checked and put back when the service does not take it, and a change never crossed by an older
 * value sent on a connection opened meanwhile.
 */
class CaptureLimitSendTest
{
	/** Calls the resource's POST as WSE would after its access check. */
	static final class Post extends CaptureLimitConfig
	{
		Object post(Object n) throws Exception
		{
			Map<String, Object> b = new java.util.HashMap<>();
			b.put("maxCaptures", n);
			return doPost(Map.of(), b);
		}
	}

	@AfterEach
	void forget() throws Exception
	{
		CaptureLimit.dir = null;
		for(String f: new String[] { "loaded", "wanted", "sentTo", "refused" })
		{
			Field x = CaptureLimit.class.getDeclaredField(f);
			x.setAccessible(true);
			x.set(null, f.equals("loaded") ? Boolean.FALSE : null);
		}
		CaptureClient.use(new CaptureClient(Path.of(CaptureClient.defaultSocket(CaptureClient.isWindows())), EgressRules.defaults(), 1000));
	}

	private static Wire.Message status(CaptureClient c) throws Exception
	{
		return c.call(Wire.header(Wire.STATUS), 2000);
	}

	@Test
	void theLimitIsGivenOncePerServiceStartAndARefusalIsKept(@TempDir Path dir) throws Exception
	{
		Path sock = CaptureClientTest.socketDir(dir).resolve("l.sock");
		CaptureLimit.dir = dir;
		CaptureLimit.save(3);
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL))
		{
			CaptureClient c = new CaptureClient(sock, EgressRules.defaults(), 50);
			status(c);
			assertEquals(List.of(3), s.limits, "a new connection to a service gives it the limit");
			c.reset();
			status(c);
			assertEquals(List.of(3), s.limits, "the same service start is not given it again");
			s.tag = "start-2";
			c.reset();
			status(c);
			assertEquals(List.of(3, 3), s.limits, "a service that started again is");
			assertNull(CaptureLimit.refused());

			s.refuseAbove = 2;
			s.tag = "start-3";
			c.reset();
			status(c);
			assertTrue(String.valueOf(CaptureLimit.refused()).contains("memory cap"), "the refusal is kept for the page: " + CaptureLimit.refused());

			// an older service has no tag: it is given the limit on each new connection
			s.refuseAbove = Integer.MAX_VALUE;
			s.tag = null;
			c.reset();
			status(c);
			c.reset();
			status(c);
			assertEquals(List.of(3, 3, 3, 3, 3), s.limits);
		}
	}

	@Test
	void aChangeIsCheckedAndPutBackWhenTheServiceDoesNotTakeIt(@TempDir Path dir) throws Exception
	{
		Path sock = CaptureClientTest.socketDir(dir).resolve("p.sock");
		CaptureLimit.dir = dir;
		CaptureLimit.save(4);
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL))
		{
			CaptureClient.use(new CaptureClient(sock, EgressRules.defaults(), 50));
			Post p = new Post();
			for(Object bad: new Object[] { 0, 33, 2.5, 4294967297L, "8", null })
			{
				TemplateFiles.Refused r = org.junit.jupiter.api.Assertions.assertThrows(TemplateFiles.Refused.class, () -> p.post(bad), String.valueOf(bad));
				assertEquals(400, r.status(), String.valueOf(bad));
			}
			assertEquals(4, CaptureLimit.wanted(), "a refused request keeps nothing");

			p.post(6);
			assertEquals(6, CaptureLimit.wanted());
			assertEquals(6, s.limits.get(s.limits.size() - 1));

			s.refuseAbove = 6;
			TemplateFiles.Refused r = org.junit.jupiter.api.Assertions.assertThrows(TemplateFiles.Refused.class, () -> p.post(8));
			assertEquals(409, r.status());
			assertEquals(6, CaptureLimit.wanted(), "the service did not take 8, so 6 is kept");
		}
		// the service is gone: nothing changes
		TemplateFiles.Refused down = org.junit.jupiter.api.Assertions.assertThrows(TemplateFiles.Refused.class, () -> new Post().post(5));
		assertEquals(503, down.status());
		assertEquals(6, CaptureLimit.wanted());
		assertTrue(Files.readString(dir.resolve(CaptureLimit.FILE)).contains("6"));
	}

	@Test
	void aConnectionOpenedDuringAChangeNeverSendsTheOlderValue(@TempDir Path dir) throws Exception
	{
		Path sock = CaptureClientTest.socketDir(dir).resolve("r.sock");
		CaptureLimit.dir = dir;
		CaptureLimit.save(4);
		try(FakeService s = new FakeService(sock, Color.RED, Wire.PROTOCOL))
		{
			s.tag = null;
			CaptureClient c = new CaptureClient(sock, EgressRules.defaults(), 50);
			CaptureClient.use(c);
			status(c);
			s.limits.clear();
			// the change's answer is slow; meanwhile another caller needs a connection of its own
			s.limitsDelayMs = 400;
			AtomicReference<Exception> failed = new AtomicReference<>();
			Thread change = new Thread(() -> { try { new Post().post(9); } catch(Exception e) { failed.set(e); } });
			change.start();
			CaptureClientTest.sleep(100);
			List<Thread> others = new ArrayList<>();
			for(int i = 0; i < 3; i++)
			{
				Thread t = new Thread(() -> { try { status(c); } catch(Exception e) { failed.compareAndSet(null, e); } });
				t.start();
				others.add(t);
			}
			change.join(10000);
			for(Thread t: others)
			{
				t.join(10000);
			}
			assertNull(failed.get());
			assertFalse(s.limits.isEmpty());
			assertEquals(9, s.limits.get(s.limits.size() - 1), "the last value the service got is the change: " + s.limits);
			assertFalse(s.limits.contains(4), "no connection sent the older value: " + s.limits);
		}
	}
}
