/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A per-stream overlay file is bound to the stream it was found for, whatever context fields it holds. A file
 * without them used to reach getModule with a null stream name and fail inside the transcoder listener.
 */
class StreamFileLoadTest
{
	@Test
	void fileWithoutContextIsBoundToItsStream(@TempDir Path dir) throws Exception
	{
		Path f = dir.resolve("OverlayStudios__definst__m1test.json");
		Files.writeString(f, "{ \"refreshRate\": 0.5, \"objects\": [] }", StandardCharsets.UTF_8);

		OverlayConfig c = OverlayConfig.loadStreamFile(f.toFile(), "_defaultVHost_", "live", "_definst_", "m1test");

		assertEquals("_defaultVHost_", c.getBoundVHostName());
		assertEquals("live", c.getBoundAppName());
		assertEquals("_definst_", c.getBoundInstanceName());
		assertEquals("m1test", c.getBoundStreamName());
		assertEquals(0.5, c.getRefreshRate());
		// context written inside the file does not win
		{
			// a file copied from another stream still applies to the stream whose name it carries on disk
			Path f2 = dir.resolve("OverlayStudios__definst__m1test.json");
			Files.writeString(f2, "{ \"vhostName\": \"other\", \"appName\": \"other\", \"applicationInstance\": \"other\", \"streamName\": \"other\" }", StandardCharsets.UTF_8);

			OverlayConfig c2 = OverlayConfig.loadStreamFile(f2.toFile(), "_defaultVHost_", "live", "_definst_", "m1test");

			assertEquals("m1test", c2.getBoundStreamName());
			assertEquals("live", c2.getBoundAppName());
		}
	}

}
