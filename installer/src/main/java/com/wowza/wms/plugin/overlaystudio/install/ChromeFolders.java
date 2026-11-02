/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which Chrome for Testing folder the capture service gets, without ever touching one it may be using. Install,
 * upgrade, repair and the daily update all go through {@link #ensure}:
 * <ol>
 * <li>a complete folder of the version (its marker written last) is used as it is: no download;</li>
 * <li>a folder whose Chrome reports the version but has no marker (an older run, or a marker lost) is adopted: the
 * marker is written, no download;</li>
 * <li>otherwise the service is stopped first, and Chrome is unpacked into the version folder when nothing uses it and
 * it can be cleared, else into a fresh sibling, &lt;version&gt;-2 and so on.</li>
 * </ol>
 * A folder is in use when capture.properties points into it or a process runs from it. A folder from an earlier version
 * of the installer holds chrome-linux64/chrome and chromedriver-linux64 on Linux, not the headless shell: it is neither
 * complete nor adopted for the headless shell, so the headless shell goes into a sibling while capture.properties
 * still points into it, and the daily update deletes it later with the other old versions.
 */
final class ChromeFolders
{
	/** Tests only: the version a program reports, in place of asking it. */
	static volatile Function<Path, String> testVersionOf;

	private ChromeFolders()
	{
	}

	/** What ensure found or made, and whether it downloaded. */
	record Result(ChromeDownload.Pair pair, boolean downloaded, boolean adopted)
	{
	}

	/** The version a folder holds: its name, without a -N suffix of a sibling. */
	static String version(String folder)
	{
		return folder.replaceFirst("-\\d+$", "");
	}

	/** The version folder and its siblings, in that order: 156.0.8078.73, 156.0.8078.73-2, ... */
	static List<Path> candidates(Path root, String version)
	{
		List<Path> out = new ArrayList<>();
		out.add(root.resolve(version));
		if(Files.isDirectory(root))
		{
			try(var s = Files.list(root))
			{
				s.filter(p -> p.getFileName().toString().matches(java.util.regex.Pattern.quote(version) + "-\\d+")).sorted(java.util.Comparator.comparingInt(
						p -> Integer.parseInt(p.getFileName().toString().substring(version.length() + 1)))).forEach(out::add);
			}
			catch(IOException e)
			{
				// only the version folder itself, then
			}
		}
		return out;
	}

	static ChromeDownload.Pair pairIn(Path dir, String version, String platform)
	{
		return new ChromeDownload.Pair(dir, ChromeDownload.chromeIn(dir, platform), version);
	}

	/** A complete folder of this version: marker and Chrome. */
	static ChromeDownload.Pair complete(Path root, String version, String platform)
	{
		for(Path d: candidates(root, version))
		{
			ChromeDownload.Pair p = pairIn(d, version, platform);
			if(Files.isRegularFile(d.resolve(ChromeDownload.COMPLETE)) && Files.isRegularFile(p.chrome()))
			{
				return p;
			}
		}
		return null;
	}

	/**
	 * A folder of this version without its marker whose Chrome reports exactly this version: by the
	 * &lt;version&gt;.manifest file Chrome for Testing keeps next to chrome.exe on Windows (no window opens), and by
	 * chrome-headless-shell --version on Linux. The marker is written, and the folder used.
	 */
	static ChromeDownload.Pair adopt(Path root, String version, String platform, PrintStream out)
	{
		for(Path d: candidates(root, version))
		{
			ChromeDownload.Pair p = pairIn(d, version, platform);
			if(!Files.isRegularFile(p.chrome()))
			{
				continue;
			}
			if(version.equals(versionOf(p.chrome())))
			{
				try
				{
					Files.writeString(d.resolve(ChromeDownload.COMPLETE), version + System.lineSeparator(), StandardCharsets.UTF_8);
					out.println("Chrome for Testing " + version + " is already in " + d + "; it is used as it is.");
					return p;
				}
				catch(IOException e)
				{
					out.println(d + " could not be marked complete: " + Plain.say(e));
				}
			}
		}
		return null;
	}

	static String versionOf(Path chrome)
	{
		var t = testVersionOf;
		if(t != null)
		{
			return t.apply(chrome);
		}
		return Preflight.chromeVersion(chrome);
	}

	/** True when capture.properties points into dir, or a process runs from it. */
	static boolean inUse(Path dir, Path configuredChrome, CaptureSetup.Sys sys)
	{
		Path d = dir.toAbsolutePath().normalize();
		if(configuredChrome != null && configuredChrome.toAbsolutePath().normalize().startsWith(d))
		{
			return true;
		}
		return !sys.processesUnder(d).isEmpty();
	}

	/** The folder to unpack into: the version folder when absent, or unused and cleared; else the first free sibling. */
	static Path target(Path root, String version, Predicate<Path> inUse, PrintStream out) throws IOException
	{
		for(int n = 1; n <= 20; n++)
		{
			Path d = n == 1 ? root.resolve(version) : root.resolve(version + "-" + n);
			if(!Files.exists(d))
			{
				return d;
			}
			if(inUse.test(d))
			{
				out.println(d + " is in use and does not hold this Chrome; it is left as it is, and Chrome goes next to it.");
				continue;
			}
			try
			{
				ChromeDownload.deleteTree(d);
				return d;
			}
			catch(IOException e)
			{
				out.println(d + " is incomplete and could not be cleared (" + Plain.say(e) + "); it is left as it is.");
			}
		}
		throw new IOException("no free folder for Chrome for Testing " + version + " in " + root);
	}

	/**
	 * The complete Chrome of this build under root, found, adopted or downloaded (see the class comment).
	 * @param configuredChrome what capture.properties names now, or null
	 * @param beforeChange called once before a Chrome folder is cleared or written: stops the service
	 */
	static Result ensure(Path root, ChromeDownload.Build b, Path configuredChrome, CaptureSetup.Sys sys, HttpClient http, PrintStream out,
			ChromeDownload.Progress progress, BooleanSupplier cancelled, Runnable beforeChange) throws IOException, InterruptedException
	{
		ChromeDownload.Pair done = complete(root, b.version(), b.platform());
		if(done != null)
		{
			return new Result(done, false, false);
		}
		ChromeDownload.Pair adopted = adopt(root, b.version(), b.platform(), out);
		if(adopted != null)
		{
			return new Result(adopted, false, true);
		}
		beforeChange.run();
		Path dir = target(root, b.version(), d -> inUse(d, configuredChrome, sys), out);
		return new Result(ChromeDownload.fetch(http, dir, b, out, progress, cancelled), true, false);
	}

	/** Removes version folders that are incomplete and that nothing uses, left by earlier runs; best effort. */
	static void cleanIncomplete(Path root, Path configuredChrome, CaptureSetup.Sys sys)
	{
		if(!Files.isDirectory(root))
		{
			return;
		}
		try(var s = Files.list(root))
		{
			for(Path d: (Iterable<Path>)s::iterator)
			{
				if(Files.isDirectory(d) && !Files.isRegularFile(d.resolve(ChromeDownload.COMPLETE)) && !inUse(d, configuredChrome, sys))
				{
					try
					{
						ChromeDownload.deleteTree(d);
					}
					catch(IOException e)
					{
						// left for a later run
					}
				}
			}
		}
		catch(IOException e)
		{
			// nothing to clean, then
		}
	}
}
