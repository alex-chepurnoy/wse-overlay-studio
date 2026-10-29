/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.wowza.wms.plugin.urloverlay.templates.ModuleFiles;
import com.wowza.wms.plugin.urloverlay.templates.OverlayStore;
import com.wowza.wms.rest.ConfigBase;
import com.wowza.wms.rest.WMSConfig;

/**
 * A path variable cannot climb out of the folder it names: WSE URL-decodes path variables, so %2F arrives as a slash.
 * Checked for every resource of the module's own REST base, and for the stream and named-overlay files.
 */
class PathNamesTest
{
	static final String[] CLIMBS = { "..%2F..%2Fconf", "..", "..%5C..%5Cconf", "a%2Fb", "%2E%2E" };

	static List<Class<?>> vhostResources() throws Exception
	{
		String pkg = PathNamesTest.class.getPackageName();
		URL dir = PathNamesTest.class.getClassLoader().getResource(pkg.replace('.', '/'));
		List<Class<?>> out = new ArrayList<>();
		for(URL u: List.of(dir, VHostResourceBase.class.getProtectionDomain().getCodeSource().getLocation()))
		{
			File base = new File(u.toURI());
			File d = base.isDirectory() && base.getName().equals("rest") ? base : new File(base, pkg.replace('.', '/'));
			File[] files = d.listFiles((x, n) -> n.endsWith(".class") && !n.contains("$"));
			for(File f: files == null ? new File[0] : files)
			{
				Class<?> c = Class.forName(pkg + "." + f.getName().replace(".class", ""));
				if(VHostResourceBase.class.isAssignableFrom(c) && c.getAnnotation(WMSConfig.class) != null && !out.contains(c))
				{
					out.add(c);
				}
			}
		}
		return out;
	}

	@Test
	void everyResourceRefusesAClimbingPathVariable() throws Exception
	{
		List<Class<?>> resources = vhostResources();
		assertTrue(resources.size() >= 15, "found " + resources);
		for(Class<?> c: resources)
		{
			String location = c.getAnnotation(WMSConfig.class).restLocation();
			Matcher m = Pattern.compile("\\{([^}]+)\\}").matcher(location);
			List<String> vars = new ArrayList<>();
			while(m.find())
			{
				vars.add(m.group(1));
			}
			for(String v: vars)
			{
				if(v.equals("server"))
				{
					continue;
				}
				for(String climb: CLIMBS)
				{
					String uri = "http://127.0.0.1:8087" + location.replace("{" + v + "}", climb).replaceAll("\\{[^}]+\\}", "ok");
					Map<String, String> path = ConfigBase.fillStringKeyMapFromUri(location, uri);
					assertThrows(ModuleFiles.BadName.class, () -> VHostResourceBase.checkPath(path), c.getSimpleName() + " " + v + "=" + climb);
				}
			}
			String plain = "http://127.0.0.1:8087" + location.replaceAll("\\{[^}]+\\}", "live");
			VHostResourceBase.checkPath(ConfigBase.fillStringKeyMapFromUri(location, plain));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "..", ".", "../conf", "a/b", "a\\b", "", " ", "x\ny" })
	void streamAndOverlayFilesStayInTheModuleFolder(String bad, @TempDir Path vhost)
	{
		assertThrows(ModuleFiles.BadName.class, () -> ModuleFiles.streamFile(vhost, "live", "_definst_", bad));
		assertThrows(ModuleFiles.BadName.class, () -> ModuleFiles.streamFile(vhost, "live", bad, "cam1"));
		assertThrows(ModuleFiles.BadName.class, () -> ModuleFiles.streamFile(vhost, bad, "_definst_", "cam1"));
		assertThrows(ModuleFiles.BadName.class, () -> OverlayStore.dir(vhost, bad));
	}

	@Test
	void streamNamesWseAllowsStillWork(@TempDir Path vhost)
	{
		for(String s: new String[] { "cam1", "my stream", "demo_720p", "cam.1", "_definst_" })
		{
			Path f = ModuleFiles.streamFile(vhost, "live", "_definst_", s);
			assertTrue(f.startsWith(ModuleFiles.root(vhost).toAbsolutePath().normalize()), s);
			assertEquals(s + ".json", f.getFileName().toString());
		}
		assertFalse(ModuleFiles.appDir(vhost, "live").toString().contains(".."));
	}
}
