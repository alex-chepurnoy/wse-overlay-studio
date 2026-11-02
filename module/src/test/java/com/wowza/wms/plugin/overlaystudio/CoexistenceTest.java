/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import com.wowza.wms.rest.WMSConfig;

/**
 * Nothing in the built jar may reuse an identifier that the overlays plugin VIF installs (wse-plugin-overlays, "the
 * baseline" below) already owns
 * on the same server.
 */
class CoexistenceTest
{
	private static final String REST_PREFIX = "/v1/{server}/plugin/overlaystudio/";

	// Identifiers owned by wse-plugin-overlays. As class-file string constants they must not appear in our jar. The
	// bare word "Overlays" is not listed: it is also WSE's own template element (Decode/Video/Overlays,
	// Encode/Video/Overlays), which the Template step reads; the baseline's REST path, "/Overlays", stays guarded.
	private static final String[] BASELINE_STRINGS = {
			"OverlayServer", "OverlayModule", "OverlayDebugLogs", "OverlayInitTransparentOverlay",
			"OverlayMaxDebugOverlayLogs", "/Overlays",
	};

	// Their replacements, which must appear.
	private static final String[] OWN_STRINGS = {
			"OverlayStudioServer", "OverlayStudioModule", "OverlayStudioDebugLogs", "OverlayStudioInitTransparentOverlay",
			"OverlayStudioMaxDebugOverlayLogs",
	};

	@Test
	void noClassInTheBaselinePackages() throws Exception
	{
		for(String name: entryNames())
		{
			assertFalse(name.startsWith("com/wowza/wms/plugin/overlays/"), name);
			assertFalse(name.startsWith("com/wowza/wms/plugin/rest/overlays/"), name);
			if(name.endsWith(".class"))
			{
				assertTrue(name.startsWith("com/wowza/wms/plugin/overlaystudio/"), name);
			}
		}
	}

	@Test
	void everyRestResourceUsesTheOverlayStudioPrefix() throws Exception
	{
		List<String> found = new ArrayList<>();
		try(JarFile jar = new JarFile(jarPath().toFile()))
		{
			for(JarEntry e: jar.stream().toList())
			{
				String name = e.getName();
				if(!name.endsWith(".class") || name.contains("$"))
				{
					continue;
				}
				Class<?> c = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, getClass().getClassLoader());
				WMSConfig cfg = c.getAnnotation(WMSConfig.class);
				if(cfg != null)
				{
					assertTrue(cfg.restLocation().startsWith(REST_PREFIX), c.getName() + ": " + cfg.restLocation());
					found.add(c.getSimpleName());
				}
			}
		}
		// The three baseline resources (overlay, objects, object) and the twenty-six this module adds
		assertEquals(List.of("AppModuleConfig", "AppStateConfig", "AppStreamsConfig", "CaptureConfig", "CaptureLimitConfig", "FontsCheckConfig", "FontsConfig", "ImagesConfig", "InfoConfig", "NamedOverlayCaptureConfig", "NamedOverlayConfig",
				"NamedOverlayPreviewConfig", "ObjectConfig",
				"ObjectsConfig", "OverlayAppsConfig", "OverlayConfig", "OverlaysConfig", "PreviewConfig", "SnapshotConfig", "StatusConfig", "StreamRestartConfig", "TemplateAssignConfig",
				"TemplateCopiesConfig", "TemplateOrderConfig", "TemplateOverlayConfig", "TemplateSpecConfig", "TemplateStarterConfig", "TemplateStartersConfig", "TemplateUseConfig",
				"TemplateValidateConfig", "TemplatesBuildConfig"),
				found.stream().sorted().toList());
		// every annotated resource is registered, and nothing else is
		assertEquals(found.stream().sorted().toList(),
				java.util.Arrays.stream(OverlayStudioServer.REST_RESOURCES).map(Class::getSimpleName).sorted().toList());
	}

	@Test
	void noBaselineIdentifierStrings() throws Exception
	{
		byte[] all = allClassBytes();
		for(String s: BASELINE_STRINGS)
		{
			assertFalse(containsUtf8Constant(all, s), "baseline identifier present as a constant: " + s);
		}
		for(String s: OWN_STRINGS)
		{
			assertTrue(containsUtf8Constant(all, s), "own identifier missing: " + s);
		}
	}

	@Test
	void overlaySlotIsNotTheBaselineSlot()
	{
		// the overlays plugin VIF installs draws in slot 1; sharing it would draw over VIF's overlay
		org.junit.jupiter.api.Assertions.assertNotEquals(1, com.wowza.wms.plugin.overlaystudio.rest.OverlayConfig.DEFAULT_OVERLAY_INDEX);
	}

	private static Path jarPath()
	{
		String p = System.getProperty("overlaystudio.jar");
		assertTrue(p != null && Files.isRegularFile(Path.of(p)), "built jar not found: " + p);
		return Path.of(p);
	}

	private static List<String> entryNames() throws Exception
	{
		try(JarFile jar = new JarFile(jarPath().toFile()))
		{
			return jar.stream().map(JarEntry::getName).toList();
		}
	}

	private static byte[] allClassBytes() throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try(JarFile jar = new JarFile(jarPath().toFile()))
		{
			for(JarEntry e: jar.stream().filter(e -> e.getName().endsWith(".class")).toList())
			{
				try(InputStream in = jar.getInputStream(e))
				{
					in.transferTo(out);
				}
			}
		}
		return out.toByteArray();
	}

	/**
	 * A class-file CONSTANT_Utf8 entry is tag 1, a two-byte length, then the bytes. Matching the whole entry
	 * finds the exact string, so "OverlayStudioServer" does not count as "OverlayServer".
	 */
	private static boolean containsUtf8Constant(byte[] haystack, String s)
	{
		byte[] body = s.getBytes(StandardCharsets.UTF_8);
		byte[] needle = new byte[body.length + 3];
		needle[0] = 1;
		needle[1] = (byte)(body.length >> 8);
		needle[2] = (byte)body.length;
		System.arraycopy(body, 0, needle, 3, body.length);
		outer:
		for(int i = 0; i <= haystack.length - needle.length; i++)
		{
			for(int j = 0; j < needle.length; j++)
			{
				if(haystack[i + j] != needle[j])
				{
					continue outer;
				}
			}
			return true;
		}
		return false;
	}
}
