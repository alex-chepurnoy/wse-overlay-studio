/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.urloverlay.templates.ModuleFiles;

/**
 * The application state's revision changes when what the page shows changes, whoever changed it, and only then
 *.
 */
class AppStateTest
{
	@TempDir
	Path vhost;
	Path overlay, template;

	@BeforeEach
	void tree() throws Exception
	{
		Path app = ModuleFiles.appDir(vhost, "live");
		Files.createDirectories(app);
		overlay = app.resolve("Scoreboard.json");
		Files.writeString(overlay, "{\"objects\":[]}");
		Files.createDirectories(vhost.resolve("conf/live"));
		Files.writeString(vhost.resolve("conf/live/Application.xml"), "<Root/>");
		Files.createDirectories(vhost.resolve("transcoder/templates"));
		template = vhost.resolve("transcoder/templates/scoreboard.xml");
		Files.writeString(template, "<Root><Transcode><Encodes/></Transcode></Root>");
	}

	private String rev(List<String> publishing, String showing) throws Exception
	{
		return AppStateConfig.revision(vhost, "live", publishing, showing);
	}

	private static void touch(Path p, long ms) throws Exception
	{
		Files.setLastModifiedTime(p, FileTime.fromMillis(ms));
	}

	@Test
	void anEditByHandChangesIt() throws Exception
	{
		touch(overlay, 1_000_000L);
		String r0 = rev(List.of(), "");
		Files.writeString(overlay, "{\"objects\":[{}]}");
		touch(overlay, 2_000_000L);
		assertNotEquals(r0, rev(List.of(), ""), "an overlay edited outside the page");

		String r1 = rev(List.of(), "");
		touch(template, 3_000_000L);
		assertNotEquals(r1, rev(List.of(), ""), "a template edited outside the page");

		String r2 = rev(List.of(), "");
		Files.writeString(overlay.resolveSibling("Lower.json"), "{}");
		assertNotEquals(r2, rev(List.of(), ""), "an overlay added");

		String r3 = rev(List.of(), "");
		Path stream = ModuleFiles.streamFile(vhost, "live", "_definst_", "cam1");
		Files.createDirectories(stream.getParent());
		Files.writeString(stream, "{}");
		assertNotEquals(r3, rev(List.of(), ""), "a stream's own overlay file written");
		// the baseline: an unchanged tree keeps its revision
		{
			assertEquals(rev(List.of("cam1"), "Scoreboard=[]"), rev(List.of("cam1"), "Scoreboard=[]"));
		}
	}

	@Test
	void streamsStartingAndOverlaysShowingChangeIt() throws Exception
	{
		String r0 = rev(List.of(), "Scoreboard=[]");
		assertNotEquals(r0, rev(List.of("cam1"), "Scoreboard=[]"), "a stream started publishing");
		assertNotEquals(r0, rev(List.of(), "Scoreboard=[cam1]"), "a transcoder started drawing the overlay");
	}

	@Test
	void unrelatedFilesDoNotChangeIt() throws Exception
	{
		String r0 = rev(List.of(), "");
		Files.writeString(vhost.resolve("conf/live/notes.txt"), "x");
		Files.createDirectories(ModuleFiles.appDir(vhost, "other"));
		Files.writeString(ModuleFiles.appDir(vhost, "other").resolve("Other.json"), "{}");
		assertEquals(r0, rev(List.of(), ""), "another application's overlay is not this application's state");
	}

	@Test
	void templatesAreListedWithTheirValidation() throws Exception
	{
		List<Map<String, Object>> t = AppStateConfig.templates(vhost.resolve("transcoder/templates"));
		assertEquals(1, t.size());
		assertEquals("scoreboard", t.get(0).get("name"));
		// the fixture template has no encode, so it cannot carry an overlay, and the problem says so
		assertEquals(false, t.get(0).get("usable"), t.get(0).toString());
		assertTrue(String.valueOf(t.get(0).get("problems")).toLowerCase().contains("encode"), t.get(0).toString());
	}
}
