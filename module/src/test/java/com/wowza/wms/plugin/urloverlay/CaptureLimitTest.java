/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureLimitTest
{
	@AfterEach
	void forget() throws Exception
	{
		CaptureLimit.dir = null;
		set("loaded", false);
		set("wanted", null);
	}

	private static void set(String name, Object v) throws Exception
	{
		Field f = CaptureLimit.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(null, v);
	}

	@Test
	void theLimitSetFromThePageIsKeptForTheNextStart(@TempDir Path dir) throws Exception
	{
		CaptureLimit.dir = dir;
		set("loaded", false);
		assertNull(CaptureLimit.wanted(), "never set: capture.properties decides");
		CaptureLimit.save(12);
		assertEquals(12, CaptureLimit.wanted());
		assertTrue(Files.readString(dir.resolve(CaptureLimit.FILE), StandardCharsets.UTF_8).contains("12"));
		// a fresh read, as after a WSE restart, finds it again
		set("loaded", false);
		set("wanted", null);
		assertEquals(12, CaptureLimit.wanted());
	}

	@Test
	void anUnreadableFileLeavesTheLimitToCaptureProperties(@TempDir Path dir) throws Exception
	{
		Files.writeString(dir.resolve(CaptureLimit.FILE), "not json", StandardCharsets.UTF_8);
		CaptureLimit.dir = dir;
		set("loaded", false);
		assertNull(CaptureLimit.wanted());
	}
}
