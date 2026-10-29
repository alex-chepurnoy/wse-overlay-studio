/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * On Windows, runs the installer again with administrator rights when it cannot write to the WSE install, through the
 * standard UAC prompt. A Start menu shortcut, the setup program's last step and install.cmd all start without them.
 */
final class Elevate
{
	private Elevate()
	{
	}

	/** True when this run changes files, cannot write to the install, and has not been raised already. */
	static boolean needed(Main.Options o, Path home)
	{
		boolean writes = ("Install".equals(o.command) || "Uninstall".equals(o.command)) && !o.dryRun && !o.listApps;
		return Preflight.WINDOWS && writes && !o.elevated && !Preflight.notWritable(List.of(home.resolve("conf"))).isEmpty();
	}

	/** Starts this program again, elevated, with the same arguments plus --elevated; returns its exit code. */
	static int relaunch(String[] args)
	{
		return relaunch(args, true);
	}

	/**
	 * As {@link #relaunch(String[])}; without wait it returns 0 once the elevated copy has started, for the window-based
	 * setup, which hands over to it.
	 */
	static int relaunch(String[] args, boolean wait)
	{
		List<String> cmd = self();
		List<String> rest = new ArrayList<>(cmd.subList(1, cmd.size()));
		rest.addAll(List.of(args));
		rest.add("--elevated");
		String ps = "$p = Start-Process -FilePath " + psQuote(cmd.get(0)) + " -ArgumentList " + argumentList(rest) + " -Verb RunAs"
				+ (wait ? " -Wait -PassThru; exit $p.ExitCode" : "; exit 0");
		try
		{
			Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps).inheritIO().start();
			return p.waitFor();
		}
		catch(Exception e)
		{
			System.out.println("Could not ask for administrator rights: " + e.getMessage() + ". Run this again from an elevated prompt.");
			return 3;
		}
	}

	/** The arguments as one PowerShell string array, each quoted for the new process's command line. */
	static String argumentList(List<String> args)
	{
		StringBuilder list = new StringBuilder();
		for(String a: args)
		{
			if(list.length() > 0)
			{
				list.append(',');
			}
			list.append(psQuote(quote(a)));
		}
		return list.toString();
	}

	private static String psQuote(String s)
	{
		return "'" + s.replace("'", "''") + "'";
	}

	/**
	 * One argument quoted for a Windows command line, by the rules programs use to split it: backslashes are literal
	 * except before a quote, so the ones before an embedded quote and before the closing quote are doubled. A path such
	 * as C:\Program Files\Wowza\ then reaches the program as it was typed.
	 */
	static String quote(String a)
	{
		StringBuilder b = new StringBuilder("\"");
		int slashes = 0;
		for(char c: a.toCharArray())
		{
			if(c == '\\')
			{
				slashes++;
				continue;
			}
			b.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes)).append(c);
			slashes = 0;
		}
		return b.append("\\".repeat(slashes * 2)).append('"').toString();
	}

	// the jpackage launcher when running from the setup program, else java -jar <this jar>
	static List<String> self()
	{
		String launcher = System.getProperty("jpackage.app-path");
		if(launcher != null)
		{
			return List.of(launcher);
		}
		try
		{
			Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path java = Paths.get(System.getProperty("java.home"), "bin", Preflight.WINDOWS ? "java.exe" : "java");
			return List.of(java.toString(), "-jar", jar.toString());
		}
		catch(Exception e)
		{
			throw new IllegalStateException("Cannot find this installer's own path", e);
		}
	}
}
