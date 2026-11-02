/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wowza.wms.plugin.overlaystudio.templates.OverlayStore;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateValidator;

/**
 * Keeps the page tests' fake bridge (tests/ui) honest: the templates it serves are what the validator reads from the
 * starters, and the overlays it serves are documents OverlayStore keeps as they are. With OVS_WRITE_UI_FIXTURES=1 in
 * the environment it rewrites tests/ui/fixtures/templates.json from the starters instead of comparing.
 */
class UiFakeContractTest
{
	static final List<String> STARTERS = List.of("overlaystudio-cpu", "overlaystudio-gpu-eva", "overlaystudio-passthrough");
	static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	@TempDir
	Path tmp;

	static Path fixtures()
	{
		return Path.of(System.getProperty("overlaystudio.uiFixtures"));
	}

	@Test
	void templatesAreWhatTheValidatorReadsFromTheStarters() throws Exception
	{
		Map<String, Object> want = new LinkedHashMap<>();
		for(String s: STARTERS)
		{
			try(InputStream in = UiFakeContractTest.class.getResourceAsStream("/starters/" + s + ".xml"))
			{
				want.put(s, TemplateValidator.validate(in).toMap());
			}
		}
		Path f = fixtures().resolve("templates.json");
		if("1".equals(System.getenv("OVS_WRITE_UI_FIXTURES")))
		{
			Files.writeString(f, JSON.writeValueAsString(want) + "\n");
		}
		assertEquals(JSON.valueToTree(want), JSON.readTree(f.toFile()), "rerun with OVS_WRITE_UI_FIXTURES=1 after changing a starter or the validator");
	}

	@Test
	@SuppressWarnings("unchecked")
	void overlaysAreDocumentsTheStoreKeepsAsTheyAre() throws Exception
	{
		JsonNode all = JSON.readTree(fixtures().resolve("overlays.json").toFile());
		for(var it = all.fields(); it.hasNext();)
		{
			var e = it.next();
			Map<String, Object> doc = JSON.treeToValue(e.getValue(), Map.class);
			Map<String, Object> saved = OverlayStore.write(tmp, e.getKey(), doc, url -> url.startsWith("https://") ? null : "https only");
			assertEquals(e.getValue(), JSON.valueToTree(saved), e.getKey());
		}
	}
}
