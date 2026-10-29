/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import com.wowza.wms.plugin.urloverlay.config.XmlScan;
import com.wowza.wms.plugin.urloverlay.config.TextFile;
import com.wowza.wms.plugin.urloverlay.config.ConfigEdits;

/** A WSE install on disk: where it is, its version, its virtual hosts and applications. */
final class Wse
{
	static final int[] MIN_VERSION = { 4, 11, 1 };

	private Wse()
	{
	}

	static final class Failure extends Exception
	{
		final int exit;

		Failure(int exit, String message)
		{
			super(message);
			this.exit = exit;
		}
	}

	/** --home, else -Dcom.wowza.wms.ConfigHome, else WMSAPP_HOME, else the one install found in the standard places. */
	static Path home(String option, List<Path> candidatesForTest) throws Failure
	{
		// tests pass their own candidates and must not pick up this machine's WSE through the environment
		String given = option != null || candidatesForTest != null ? option : System.getProperty("com.wowza.wms.ConfigHome", System.getenv("WMSAPP_HOME"));
		if(given != null && !given.isBlank())
		{
			Path p = Paths.get(given);
			if(!Files.isRegularFile(p.resolve("conf").resolve("Server.xml")))
			{
				throw new Failure(2, "No conf/Server.xml under " + p);
			}
			return p;
		}
		List<Path> found = candidatesForTest != null ? candidatesForTest : candidates();
		found.removeIf(p -> !Files.isRegularFile(p.resolve("conf").resolve("Server.xml")));
		if(found.isEmpty())
		{
			throw new Failure(2, "No Wowza Streaming Engine install found. Pass --home <install folder>.");
		}
		if(found.size() > 1)
		{
			StringBuilder b = new StringBuilder("Several Wowza Streaming Engine installs found; pass --home with one of:");
			for(Path p: found)
			{
				b.append(System.lineSeparator()).append("  ").append(p);
			}
			throw new Failure(2, b.toString());
		}
		return found.get(0);
	}

	/** The installs found in the standard places that hold a conf/Server.xml, for the window-based setup to offer. */
	static List<Path> found()
	{
		List<Path> out = new ArrayList<>(candidates());
		out.removeIf(p -> !Files.isRegularFile(p.resolve("conf").resolve("Server.xml")));
		return out;
	}

	// Linux /usr/local/WowzaStreamingEngine (the symlink to the current one), else the versioned folders;
	// Windows: the installs under Program Files and Program Files (x86)
	private static List<Path> candidates()
	{
		List<Path> out = new ArrayList<>();
		Path linux = Paths.get("/usr/local/WowzaStreamingEngine");
		if(Files.isDirectory(linux))
		{
			out.add(linux);
			return out;
		}
		File local = new File("/usr/local");
		File[] versioned = local.listFiles((d, n) -> n.startsWith("WowzaStreamingEngine-"));
		if(versioned != null && versioned.length > 0)
		{
			Arrays.sort(versioned, Comparator.comparing(File::getName));
			out.add(versioned[versioned.length - 1].toPath());
			return out;
		}
		for(String root: new String[] { System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files") + "\\Wowza Media Systems",
				System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)") + "\\Wowza Media Systems" })
		{
			File[] installs = new File(root).listFiles((d, n) -> n.startsWith("Wowza Streaming Engine"));
			if(installs != null)
			{
				Arrays.sort(installs, Comparator.comparing(File::getName));
				for(File f: installs)
				{
					out.add(f.toPath());
				}
			}
		}
		return out;
	}

	/** The version in lib/wms-server.jar's manifest (Specification-Version, for example 4.12.1+15). */
	static String version(Path home) throws IOException
	{
		Path jar = home.resolve("lib").resolve("wms-server.jar");
		if(!Files.isRegularFile(jar))
		{
			return null;
		}
		try(JarFile j = new JarFile(jar.toFile()))
		{
			Manifest m = j.getManifest();
			return m == null ? null : m.getMainAttributes().getValue("Specification-Version");
		}
	}

	/** True when version is at least MIN_VERSION; null (unknown) counts as acceptable, the report says so. */
	static boolean supported(String version)
	{
		if(version == null)
		{
			return true;
		}
		String[] parts = version.split("[^0-9]+");
		for(int k = 0; k < MIN_VERSION.length; k++)
		{
			int v = k < parts.length && !parts[k].isEmpty() ? Integer.parseInt(parts[k]) : 0;
			if(v != MIN_VERSION[k])
			{
				return v > MIN_VERSION[k];
			}
		}
		return true;
	}

	/** An application found on disk. */
	record App(String vhost, String name, Path xml, String streamType, String transcoder, String templates, List<String> others, boolean installed)
	{
		String kind()
		{
			if(streamType == null || streamType.isEmpty())
			{
				return "unknown";
			}
			if(streamType.startsWith("live"))
			{
				return "live";
			}
			return streamType.equals("default") ? "vod" : "unknown";
		}

		boolean selectable()
		{
			return kind().equals("live");
		}
	}

	/** Virtual host name to its config folder, from conf/VHosts.xml; ${com.wowza.wms.ConfigHome} is the install root. */
	static Map<String, Path> vhosts(Path home) throws IOException
	{
		Map<String, Path> out = new LinkedHashMap<>();
		Path f = home.resolve("conf").resolve("VHosts.xml");
		if(!Files.isRegularFile(f))
		{
			out.put("_defaultVHost_", home);
			return out;
		}
		String t = TextFile.read(f).text();
		for(XmlScan.Span v: XmlScan.find(t, "/Root/VHosts/VHost"))
		{
			String inner = t.substring(v.contentStart(), v.close());
			String name = tagText(inner, "Name");
			String dir = tagText(inner, "ConfigDir");
			if(name == null)
			{
				continue;
			}
			String d = dir == null ? "${com.wowza.wms.ConfigHome}" : dir;
			d = d.replace("${com.wowza.wms.ConfigHome}", home.toString()).replace("${com.wowza.wms.AppHome}", home.toString());
			out.put(name, Paths.get(d));
		}
		if(out.isEmpty())
		{
			out.put("_defaultVHost_", home);
		}
		return out;
	}

	/** Every application in every virtual host: a folder under the vhost's conf with an Application.xml. */
	static List<App> apps(Path home) throws IOException
	{
		List<App> out = new ArrayList<>();
		for(Map.Entry<String, Path> v: vhosts(home).entrySet())
		{
			File[] dirs = v.getValue().resolve("conf").toFile().listFiles(File::isDirectory);
			if(dirs == null)
			{
				continue;
			}
			Arrays.sort(dirs, Comparator.comparing(File::getName));
			for(File d: dirs)
			{
				Path xml = d.toPath().resolve("Application.xml");
				if(!Files.isRegularFile(xml))
				{
					continue;
				}
				String t = TextFile.read(xml).text();
				out.add(new App(v.getKey(), d.getName(), xml, element(t, "/Root/Application/Streams/StreamType"),
						element(t, "/Root/Application/Transcoder/LiveStreamTranscoder"), element(t, "/Root/Application/Transcoder/Templates"),
						otherPlugins(t), ConfigEdits.hasModule(t)));
			}
		}
		return out;
	}

	private static String element(String t, String path)
	{
		try
		{
			XmlScan.Span s = XmlScan.first(t, path);
			return s == null ? null : t.substring(s.contentStart(), s.close()).trim();
		}
		catch(IllegalArgumentException e)
		{
			return null;
		}
	}

	// modules from other plugins worth naming in the table, by class
	private static List<String> otherPlugins(String t)
	{
		List<String> out = new ArrayList<>();
		if(t.contains("com.wowza.wms.plugin.videointelligence"))
		{
			out.add("VIF");
		}
		if(t.contains("com.wowza.wms.plugin.overlays"))
		{
			out.add("overlays");
		}
		return out;
	}

	private static String tagText(String inner, String tag)
	{
		int a = inner.indexOf("<" + tag + ">");
		int b = inner.indexOf("</" + tag + ">");
		return a < 0 || b < a ? null : inner.substring(a + tag.length() + 2, b).trim();
	}

	/** Where the Manager serves plugin pages from: manager/wse-plugins on 4.12 and later, else the exploded webapp. */
	static Map.Entry<Path, Boolean> pluginDir(Path home, Path wsemHome)
	{
		Path m = wsemHome != null ? wsemHome : home.resolve("manager");
		Path persistent = m.resolve("wse-plugins");
		if(Files.isDirectory(persistent))
		{
			return Map.entry(persistent.resolve("server").resolve("url-overlay"), true);
		}
		return Map.entry(m.resolve("temp").resolve("webapps").resolve("enginemanager").resolve("wse-plugins").resolve("server").resolve("url-overlay"), false);
	}
}
