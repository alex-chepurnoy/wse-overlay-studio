/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.templates.TemplateBuilder;

/**
 * What a running stream needs after its template changes: the overlay is applied at once, the outputs only on a
 * restart. structure() is the part that waits for a restart, so it must ignore the overlay and see everything else.
 */
class LiveTemplateTest
{
	@TempDir
	Path dir;

	private File write(String overlay, List<String> overlayOn, long bitrate, int outputs) throws Exception
	{
		List<Object> outs = new ArrayList<>();
		for(int i = 0; i < outputs; i++)
		{
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("name", i == 0 ? "720p" : "360p");
			o.put("height", i == 0 ? 720 : 360);
			o.put("videoBitrate", i == 0 ? bitrate : 365000);
			o.put("overlay", overlayOn.contains(i == 0 ? "720p" : "360p"));
			outs.add(o);
		}
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("overlay", overlay);
		b.put("outputs", outs);
		File f = dir.resolve("t.xml").toFile();
		Files.writeString(f.toPath(), TemplateBuilder.xml(TemplateBuilder.parse("t", b)));
		return f;
	}

	@Test
	void anOverlayChangeIsNotAnOutputChange() throws Exception
	{
		String a = OverlayStudioModule.structure(write("Scoreboard", List.of("720p", "360p"), 3000000, 2));
		String b = OverlayStudioModule.structure(write("clock", List.of("720p"), 3000000, 2));
		assertEquals(a, b, "another overlay, or other outputs carrying it, is applied live");
	}

	@Test
	void outputChangesWaitForARestart() throws Exception
	{
		String a = OverlayStudioModule.structure(write("clock", List.of("720p"), 3000000, 2));
		assertNotEquals(a, OverlayStudioModule.structure(write("clock", List.of("720p"), 2500000, 2)), "a bitrate");
		assertNotEquals(a, OverlayStudioModule.structure(write("clock", List.of("720p"), 3000000, 1)), "an output removed");
		assertNotEquals(a, OverlayStudioModule.structure(dir.resolve("gone.xml").toFile()), "a deleted file");
	}
}
