/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each sign of URL Overlay 1.x alone is found; what 1.x leaves after its own uninstall is not. */
class LegacyInstallCheckTest
{
	@TempDir
	Path tmp;
	Path home;
	Path manager;
	/** The commands the check ran, and what the stand-in dpkg-query or reg answers. */
	final List<List<String>> ran = new ArrayList<>();
	String answer;

	@BeforeEach
	void setUp() throws IOException
	{
		home = Files.createDirectories(tmp.resolve("wse"));
		Files.createDirectories(home.resolve("lib"));
		Files.createDirectories(home.resolve("conf/live"));
		Files.writeString(home.resolve("conf/Server.xml"), "<Root><Server><ServerListeners></ServerListeners></Server></Root>");
		Files.writeString(home.resolve("conf/live/Application.xml"), "<Root><Application><Modules></Modules></Application></Root>");
		manager = home.resolve("manager");
		LegacyInstallCheck.testMachine = new LegacyInstallCheck.Machine(List.of(tmp.resolve("sys/opt/wse-url-overlay"), tmp.resolve("sys/etc/wse-url-overlay")),
				cmd -> {
					ran.add(cmd);
					return answer;
				});
	}

	@AfterEach
	void tearDown()
	{
		LegacyInstallCheck.testMachine = null;
	}

	String found() throws IOException
	{
		return LegacyInstallCheck.found(home, manager);
	}

	@Test
	void aCleanServerHasNoSign() throws IOException
	{
		assertNull(found());
		assertNull(LegacyInstallCheck.problem(home, manager));
	}

	@Test
	void theModuleJar() throws IOException
	{
		Path jar = Files.createFile(home.resolve("lib/wse-plugin-url-overlay-1.15.1.jar"));
		assertEquals(jar.toString(), found());
	}

	@Test
	void theServerListener() throws IOException
	{
		Files.writeString(home.resolve("conf/Server.xml"),
				"<Root><Server><ServerListeners><ServerListener><BaseClass>com.wowza.wms.plugin.urloverlay.UrlOverlayServer</BaseClass></ServerListener></ServerListeners></Server></Root>");
		assertTrue(found().endsWith("Server.xml (UrlOverlayServer)"), found());
	}

	@Test
	void theApplicationModule() throws IOException
	{
		Files.writeString(home.resolve("conf/live/Application.xml"),
				"<Root><Application><Modules><Module><Name>UrlOverlayModule</Name></Module></Modules></Application></Root>");
		assertTrue(found().endsWith("Application.xml (UrlOverlayModule)"), found());
	}

	@Test
	void theManagerPluginInEitherPlaceAndTheBridge() throws IOException
	{
		for(String p: List.of("wse-plugins/server/url-overlay", "temp/webapps/enginemanager/wse-plugins/server/url-overlay", "temp/com/wowza/wms/webapp/urloverlay"))
		{
			Path d = Files.createDirectories(manager.resolve(p));
			assertEquals(d.toString(), found());
			Files.delete(d);
		}
		assertNull(found());
	}

	@Test
	void aSeparateManagerHomeIsSearched() throws IOException
	{
		manager = Files.createDirectories(tmp.resolve("wsem"));
		Path d = Files.createDirectories(manager.resolve("wse-plugins/server/url-overlay"));
		assertEquals(d.toString(), found());
	}

	@Test
	void theCaptureServiceFolders() throws IOException
	{
		Path d = Files.createDirectories(tmp.resolve("sys/etc/wse-url-overlay"));
		assertEquals(d.toString(), found());
	}

	@Test
	void theServiceOrPackageButNotARemovedPackage() throws IOException
	{
		answer = Preflight.WINDOWS ? "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\WowzaURLOverlayCapture" : "ii ";
		assertTrue(found().startsWith("the "), found());
		assertTrue(String.join(" ", ran.get(0)).contains(Preflight.WINDOWS ? "WowzaURLOverlayCapture" : "wse-url-overlay"), ran.toString());
		if(!Preflight.WINDOWS)
		{
			answer = "rc ";
			assertNull(found(), "removed but not purged");
		}
		answer = null;
		assertNull(found(), "the command failed: no service, or no dpkg");
	}

	@Test
	void theManifestThat1xUninstallLeavesIsNotASign() throws IOException
	{
		Files.createDirectories(home.resolve("conf.modules/urloverlay"));
		Files.writeString(home.resolve("conf.modules/urloverlay/install-manifest.json"), "{}");
		Files.createDirectories(home.resolve("content/urloverlay"));
		assertNull(found());
	}

	@Test
	void theMessageNamesWhatWasFound() throws IOException
	{
		Files.createFile(home.resolve("lib/wse-plugin-url-overlay-1.15.1.jar"));
		String m = LegacyInstallCheck.problem(home, manager);
		assertTrue(m.startsWith("URL Overlay 1.x is installed (found: "), m);
		assertTrue(m.contains("wse-plugin-url-overlay-1.15.1.jar). Uninstall it first (README, Before you install)."), m);
	}
}
