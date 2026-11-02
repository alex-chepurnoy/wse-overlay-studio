/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Checks before installing. Blocking ones stop the install; the rest are reported. */
final class Preflight
{
	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

	private Preflight()
	{
	}

	/** Folders the install writes to that cannot be written. */
	static List<String> notWritable(List<Path> dirs)
	{
		List<String> out = new ArrayList<>();
		for(Path d: dirs)
		{
			Path probe = d;
			while(probe != null && !Files.isDirectory(probe))
			{
				probe = probe.getParent();
			}
			try
			{
				Path t = Files.createTempFile(probe, ".overlaystudio-", ".probe");
				Files.delete(t);
			}
			catch(Exception e)
			{
				out.add(d + " (" + e.getClass().getSimpleName() + ")");
			}
		}
		return out;
	}

	/**
	 * True when this jar cannot be renamed, which on Windows means a running WSE has it open. Replacing the module jar
	 * then fails, so WSE has to be stopped first.
	 */
	static boolean inUse(Path jar)
	{
		if(!Files.exists(jar))
		{
			return false;
		}
		Path tmp = jar.resolveSibling(jar.getFileName() + ".overlaystudio-lockcheck");
		try
		{
			Files.move(jar, tmp);
			Files.move(tmp, jar);
			return false;
		}
		catch(Exception e)
		{
			return true;
		}
	}

	/** Tests only: the command lines of the running processes, in place of the real ones. */
	static volatile java.util.function.Supplier<java.util.stream.Stream<String>> testCommandLines;

	/**
	 * True when a Wowza Streaming Engine for this install is running: a process started with WSE's bootstrap class
	 * (com.wowza.wms.bootstrap.Bootstrap start, as WSE's own startup scripts run it) whose AppHome is this install, or
	 * names none. The Linux check, where a running WSE does not lock its jars; on Windows it adds to the jar lock check,
	 * though there the command lines of service processes are often not readable.
	 */
	static boolean wseRunning(Path home)
	{
		java.util.Set<String> homes = new java.util.HashSet<>();
		homes.add(home.toAbsolutePath().normalize().toString());
		try
		{
			homes.add(home.toRealPath().toString());
		}
		catch(Exception e)
		{
			// the path as given is enough
		}
		var lines = testCommandLines;
		java.util.stream.Stream<String> all = lines != null ? lines.get()
				: ProcessHandle.allProcesses().map(p -> p.info().commandLine().orElse("")).filter(s -> !s.isEmpty());
		return all.anyMatch(c -> {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("com\\.wowza\\.wms\\.bootstrap\\.Bootstrap\\s+(\\S+)").matcher(c);
			if(!m.find() || !m.group(1).startsWith("start"))
			{
				return false;
			}
			java.util.regex.Matcher h = java.util.regex.Pattern.compile("-Dcom\\.wowza\\.wms\\.AppHome=\"?([^\"]+?)\"?(\\s+-|\\s+com\\.wowza|$)").matcher(c);
			if(!h.find())
			{
				return true;
			}
			Path p = Paths.get(h.group(1).trim()).toAbsolutePath().normalize();
			try
			{
				return homes.contains(p.toString()) || homes.contains(p.toRealPath().toString());
			}
			catch(Exception e)
			{
				return homes.contains(p.toString());
			}
		});
	}

	/** Chrome on its standard path for this OS, or the given path; null when none. */
	static Path chrome(String given)
	{
		if(given != null)
		{
			return Files.isRegularFile(Paths.get(given)) ? Paths.get(given) : null;
		}
		List<String> paths = WINDOWS
				? List.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files") + "\\Google\\Chrome\\Application\\chrome.exe",
						System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)") + "\\Google\\Chrome\\Application\\chrome.exe")
				: List.of("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser");
		for(String p: paths)
		{
			if(Files.isRegularFile(Paths.get(p)))
			{
				return Paths.get(p);
			}
		}
		return null;
	}

	/**
	 * Chrome's version without starting it: on Windows the version folder next to chrome.exe (starting chrome.exe
	 * opens a browser window there); elsewhere chrome --version.
	 */
	static String chromeVersion(Path chrome)
	{
		if(chrome == null)
		{
			return null;
		}
		if(WINDOWS)
		{
			File[] dirs = chrome.getParent().toFile().listFiles(f -> f.isDirectory() && f.getName().matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
			if(dirs == null || dirs.length == 0)
			{
				// Chrome for Testing marks its version with a <version>.manifest file next to chrome.exe instead
				File[] marks = chrome.getParent().toFile().listFiles(f -> f.isFile() && f.getName().matches("\\d+\\.\\d+\\.\\d+\\.\\d+\\.manifest"));
				return marks == null || marks.length == 0 ? null : marks[0].getName().replace(".manifest", "");
			}
			java.util.Arrays.sort(dirs, java.util.Comparator.comparing(File::getName, Preflight::compareVersions));
			return dirs[dirs.length - 1].getName();
		}
		return firstVersion(run(chrome.toString(), "--version"));
	}

	/** Linux only: fonts Chrome can render with. */
	static Boolean fonts()
	{
		if(WINDOWS)
		{
			return null;
		}
		String fc = run("fc-list");
		return fc != null && !fc.isBlank();
	}

	static String firstVersion(String s)
	{
		if(s == null)
		{
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(s);
		return m.find() ? m.group(1) : null;
	}

	private static int compareVersions(String a, String b)
	{
		String[] x = a.split("\\."), y = b.split("\\.");
		for(int k = 0; k < Math.max(x.length, y.length); k++)
		{
			int c = Integer.compare(k < x.length ? Integer.parseInt(x[k]) : 0, k < y.length ? Integer.parseInt(y[k]) : 0);
			if(c != 0)
			{
				return c;
			}
		}
		return 0;
	}

	private static String run(String... cmd)
	{
		try
		{
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			if(!p.waitFor(10, TimeUnit.SECONDS))
			{
				p.destroyForcibly();
				return null;
			}
			return new String(p.getInputStream().readAllBytes());
		}
		catch(Exception e)
		{
			return null;
		}
	}
}
