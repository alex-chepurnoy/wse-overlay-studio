/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;

/** The application's template order: file names and the stream-name entry only, each once, never empty. */
class TemplateOrderTest
{
	@TempDir
	Path dir;

	@Test
	void entriesAreCheckedAndNormalized() throws Exception
	{
		assertEquals(List.of("${SourceStreamName}.xml", "studio.xml", "transrate.xml"),
				TemplateOrderConfig.check(List.of("${sourcestreamname}.xml", "studio", "transrate.xml")));
		assertThrows(TemplateFiles.Refused.class, () -> TemplateOrderConfig.check(List.of()), "WSE would transcode nothing");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateOrderConfig.check(List.of("a.xml", "A")), "twice");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateOrderConfig.check(List.of("../conf/Server")), "a path");
		assertThrows(TemplateFiles.Refused.class, () -> TemplateOrderConfig.check(List.of("http://example.com/t.xml")), "a URL is not offered here");
	}

	@Test
	@SuppressWarnings("unchecked")
	void eachEntrySaysWhetherItExists() throws Exception
	{
		Files.writeString(dir.resolve("transrate.xml"), "<Root/>");
		Map<String, Object> m = TemplateOrderConfig.describe(dir, "${SourceStreamName}.xml,missing.xml,transrate.xml");
		List<Map<String, Object>> e = (List<Map<String, Object>>)m.get("entries");
		assertEquals(true, e.get(0).get("matchStream"));
		assertEquals(false, e.get(1).get("exists"));
		assertEquals(true, e.get(2).get("exists"));
	}
}
