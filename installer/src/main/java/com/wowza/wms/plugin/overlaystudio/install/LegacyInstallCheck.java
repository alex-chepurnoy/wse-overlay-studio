/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Finds URL Overlay 1.x, which 2.0 refuses to install beside (exit code 9): 2.0 does not read 1.x's overlays or
 * templates, and the two would add a module to the same applications and draw into the same transcoder slots. Only
 * installing runs it; uninstall and the daily Chrome update do not, so 2.0 can always be removed.
 *
 * 1.x's install manifest (conf.modules/urloverlay) is not a signal: its uninstall leaves it behind when a template was
 * kept or edited, and it would then block 2.0 forever. The jar, the listener and the module entry cover a live 1.x;
 * every 1.x install also set up its capture service, so the machine-wide signals catch one installed in another WSE.
 * The .deb's preinst and the setup program's launch condition check the same things before their own installs.
 */
final class LegacyInstallCheck
{
	static final int EXIT = 9;
	static final String SERVICE = "WowzaURLOverlayCapture";
	static final String PACKAGE = "wse-url-overlay";

	/** The machine-wide places and a command runner, for tests; null on a real machine. */
	static volatile Machine testMachine;

	/** Paths whose existence shows 1.x, and a runner that returns a command's output or null when it fails. */
	record Machine(List<Path> paths, Function<List<String>, String> run)
	{
	}

	private LegacyInstallCheck()
	{
	}

	/** The message for the install to stop with, or null when there is no sign of 1.x. */
	static String problem(Path home, Path managerHome) throws IOException
	{
		String f = found(home, managerHome);
		return f == null ? null : message(f);
	}

	static String message(String found)
	{
		return "URL Overlay 1.x is installed (found: " + found + "). Uninstall it first. "
				+ "Overlay Studio does not read its overlays or templates.";
	}

	/** What shows 1.x is installed, or null. */
	static String found(Path home, Path managerHome) throws IOException
	{
		try(DirectoryStream<Path> jars = Files.newDirectoryStream(home.resolve("lib"), "wse-plugin-url-overlay-*.jar"))
		{
			for(Path j: jars)
			{
				return j.toString();
			}
		}
		catch(java.nio.file.NoSuchFileException e)
		{
			// no lib folder: nothing of 1.x there either
		}
		Path server = home.resolve("conf").resolve("Server.xml");
		if(contains(server, "UrlOverlayServer"))
		{
			return server + " (UrlOverlayServer)";
		}
		Path conf = home.resolve("conf");
		if(Files.isDirectory(conf))
		{
			try(DirectoryStream<Path> apps = Files.newDirectoryStream(conf, Files::isDirectory))
			{
				for(Path a: apps)
				{
					Path xml = a.resolve("Application.xml");
					if(contains(xml, "UrlOverlayModule"))
					{
						return xml + " (UrlOverlayModule)";
					}
				}
			}
		}
		Path m = managerHome != null ? managerHome : home.resolve("manager");
		for(Path p: List.of(m.resolve("wse-plugins/server/url-overlay"), m.resolve("temp/webapps/enginemanager/wse-plugins/server/url-overlay"),
				m.resolve("temp/com/wowza/wms/webapp/urloverlay")))
		{
			if(Files.exists(p))
			{
				return p.toString();
			}
		}
		Machine mc = machine();
		for(Path p: mc.paths())
		{
			if(Files.exists(p))
			{
				return p.toString();
			}
		}
		if(Preflight.WINDOWS)
		{
			if(mc.run().apply(List.of("reg", "query", "HKLM\\SYSTEM\\CurrentControlSet\\Services\\" + SERVICE)) != null)
			{
				return "the " + SERVICE + " service";
			}
		}
		else
		{
			// installed, not a removed-but-not-purged "rc" entry
			String s = mc.run().apply(List.of("dpkg-query", "-W", "-f=${db:Status-Abbrev}", PACKAGE));
			if(s != null && s.startsWith("ii"))
			{
				return "the " + PACKAGE + " package";
			}
		}
		return null;
	}

	private static boolean contains(Path f, String s) throws IOException
	{
		return Files.isRegularFile(f) && new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains(s);
	}

	private static Machine machine()
	{
		Machine t = testMachine;
		if(t != null)
		{
			return t;
		}
		List<Path> paths = new ArrayList<>();
		if(Preflight.WINDOWS)
		{
			paths.add(Paths.get(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "WowzaURLOverlay"));
			paths.add(Paths.get(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Wowza URL Overlay Service"));
		}
		else
		{
			paths.add(Paths.get("/etc/systemd/system/" + SERVICE + ".service"));
			paths.add(Paths.get("/opt/wse-url-overlay"));
			paths.add(Paths.get("/etc/wse-url-overlay"));
		}
		return new Machine(paths, cmd -> {
			CaptureSetup.Result r = CaptureSetup.sys().run(cmd);
			return r.code() == 0 ? r.output() : null;
		});
	}
}
