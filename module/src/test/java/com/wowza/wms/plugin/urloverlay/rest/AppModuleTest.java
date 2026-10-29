/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.plugin.urloverlay.config.ConfigEdits;
import com.wowza.wms.plugin.urloverlay.config.TextFile;

/**
 * Adding the module to an application from the plugin page: the edit is the installer's, and it is recorded in the
 * install manifest so the installer's uninstall can take it out again, byte for byte.
 */
class AppModuleTest
{
	@TempDir
	Path home;
	String before;
	Path xml;

	@BeforeEach
	void tree() throws Exception
	{
		before = System.setProperty("com.wowza.wms.AppHome", home.toString());
		xml = home.resolve("conf/live2/Application.xml");
		Files.createDirectories(xml.getParent());
		Files.writeString(xml, "<Root>\r\n\t<Application>\r\n\t\t<Streams>\r\n\t\t\t<StreamType>live</StreamType>\r\n\t\t</Streams>\r\n\t\t<Modules>\r\n"
				+ "\t\t\t<Module>\r\n\t\t\t\t<Name>base</Name>\r\n\t\t\t\t<Class>com.wowza.wms.module.ModuleCore</Class>\r\n\t\t\t</Module>\r\n\t\t</Modules>\r\n\t</Application>\r\n</Root>\r\n");
		Files.createDirectories(home.resolve("conf.modules/urloverlay"));
		Files.writeString(home.resolve(AppModuleConfig.MANIFEST), "{\"edited\":[],\"added\":[],\"retired\":[],\"apps\":[\"_defaultVHost_/live\"],\"version\":\"1.1.0\"}");
	}

	@AfterEach
	void restore()
	{
		if(before == null)
		{
			System.clearProperty("com.wowza.wms.AppHome");
		}
		else
		{
			System.setProperty("com.wowza.wms.AppHome", before);
		}
	}

	@Test
	void liveApplicationsOnly()
	{
		assertTrue(AppModuleConfig.isLive("<Root><Application><Streams><StreamType>live-lowlatency</StreamType></Streams></Application></Root>"));
		assertFalse(AppModuleConfig.isLive("<Root><Application><Streams><StreamType>default</StreamType></Streams></Application></Root>"), "VOD");
		assertFalse(AppModuleConfig.isLive("<Root><Application/></Root>"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void theEditIsRecordedSoUninstallRestoresTheOriginalBytes() throws Exception
	{
		byte[] original = Files.readAllBytes(xml);
		ConfigEdits.Result r = ConfigEdits.application(TextFile.read(xml));
		Path backup = xml.resolveSibling("Application.xml.urloverlay-test.bak");
		Files.write(backup, original);
		Files.write(xml, r.file().bytes());
		assertTrue(ConfigEdits.hasModule(Files.readString(xml)));
		assertTrue(Files.readString(xml).contains("\r\n\t\t\t<Module>\r\n\t\t\t\t<Name>UrlOverlayModule</Name>"), "inserted with the file's own indentation and line ending");

		assertTrue(AppModuleConfig.record("_defaultVHost_", "live2", xml, backup, original, r));
		Map<String, Object> man = new ObjectMapper().readValue(home.resolve(AppModuleConfig.MANIFEST).toFile(), Map.class);
		Map<String, Object> e = ((List<Map<String, Object>>)man.get("edited")).get(0);
		assertEquals("conf/live2/Application.xml", e.get("path"));
		assertEquals("conf/live2/Application.xml.urloverlay-test.bak", e.get("backup"));
		assertEquals(TextFile.sha256(original), e.get("original"));
		assertEquals(TextFile.sha256(r.file().bytes()), e.get("installed"));
		assertEquals(List.of("_defaultVHost_/live", "_defaultVHost_/live2"), man.get("apps"));
		assertEquals("1.1.0", man.get("version"), "the rest of the manifest is kept");

		// what the installer's uninstall does when the file was edited since: take out exactly the recorded lines
		Map.Entry<TextFile, List<String>> undone = ConfigEdits.undo(TextFile.read(xml), (List<Map<String, Object>>)e.get("changes"));
		assertTrue(undone.getValue().isEmpty());
		assertArrayEquals(original, undone.getKey().bytes());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> entry(String rel) throws Exception
	{
		Map<String, Object> man = new ObjectMapper().readValue(home.resolve(AppModuleConfig.MANIFEST).toFile(), Map.class);
		return ((List<Map<String, Object>>)man.get("edited")).stream().filter(e -> rel.equals(e.get("path"))).findFirst().orElseThrow();
	}

	@Test
	void anEditBySomethingElseSinceIsFlaggedForTheInstaller() throws Exception
	{
		byte[] original = Files.readAllBytes(xml);
		ConfigEdits.Result r = ConfigEdits.application(TextFile.read(xml));
		Path backup = xml.resolveSibling("Application.xml.urloverlay-test.bak");
		Files.write(backup, original);
		Files.write(xml, r.file().bytes());
		AppModuleConfig.record("_defaultVHost_", "live2", xml, backup, original, r);
		assertNull(entry("conf/live2/Application.xml").get("editedByOthers"));

		// recorded again over the file as URL Overlay left it: nothing else touched it
		AppModuleConfig.record("_defaultVHost_", "live2", xml, backup, Files.readAllBytes(xml), r);
		assertNull(entry("conf/live2/Application.xml").get("editedByOthers"));
		assertEquals(1, ((List<?>)entry("conf/live2/Application.xml").get("changes")).size(), "a change is recorded once");

		// someone edits the file, then the page adds the module again
		Files.writeString(xml, Files.readString(xml) + "<!-- edited by hand -->\r\n");
		AppModuleConfig.record("_defaultVHost_", "live2", xml, backup, Files.readAllBytes(xml), r);
		assertEquals(Boolean.TRUE, entry("conf/live2/Application.xml").get("editedByOthers"));
	}

	@Test
	void aTemplateEditedBySomethingElseSinceIsFlaggedForTheInstaller() throws Exception
	{
		Path t = home.resolve("conf/transcoder/templates/mine.xml");
		Files.createDirectories(t.getParent());
		byte[] original = "<Root/>".getBytes();
		Files.writeString(t, "<Root><!-- URL Overlay --></Root>");
		Path backup = t.resolveSibling("mine.xml.bak");
		Files.write(backup, original);
		assertTrue(TemplateUseConfig.record(t, backup, original));
		assertNull(entry("conf/transcoder/templates/mine.xml").get("editedByOthers"));
		assertEquals(TextFile.sha256(Files.readAllBytes(t)), entry("conf/transcoder/templates/mine.xml").get("installed"));

		Files.writeString(t, "<Root><!-- URL Overlay --><!-- by hand --></Root>");
		byte[] handEdited = Files.readAllBytes(t);
		Files.writeString(t, "<Root><!-- URL Overlay again --></Root>");
		TemplateUseConfig.record(t, backup, handEdited);
		assertEquals(Boolean.TRUE, entry("conf/transcoder/templates/mine.xml").get("editedByOthers"));
		assertEquals(TextFile.sha256(Files.readAllBytes(t)), entry("conf/transcoder/templates/mine.xml").get("installed"), "the hash follows the module's write");
	}

	@Test
	void aManifestWriteThatFailsLeavesTheOldOneWhole() throws Exception
	{
		Path man = home.resolve(AppModuleConfig.MANIFEST);
		byte[] before = Files.readAllBytes(man);
		AppModuleConfig.beforeMove = tmp -> {
			throw new java.io.IOException("stopped between write and move");
		};
		try
		{
			org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
					() -> AppModuleConfig.writeManifest(man, Map.of("version", "9.9.9", "edited", List.of())));
		}
		finally
		{
			AppModuleConfig.beforeMove = tmp -> {
			};
		}
		assertArrayEquals(before, Files.readAllBytes(man), "the old manifest is untouched");
		assertEquals("1.1.0", new ObjectMapper().readValue(man.toFile(), Map.class).get("version"));
		AppModuleConfig.writeManifest(man, Map.of("version", "9.9.9", "edited", List.of()));
		assertEquals("9.9.9", new ObjectMapper().readValue(man.toFile(), Map.class).get("version"), "and a write that completes replaces it");
	}

	@Test
	void withoutAManifestNothingIsRecorded() throws Exception
	{
		Files.delete(home.resolve(AppModuleConfig.MANIFEST));
		ConfigEdits.Result r = ConfigEdits.application(TextFile.read(xml));
		assertFalse(AppModuleConfig.record("_defaultVHost_", "live2", xml, xml, Files.readAllBytes(xml), r));
	}
}
