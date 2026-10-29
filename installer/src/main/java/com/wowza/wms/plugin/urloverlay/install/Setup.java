/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The Windows setup program's memory of which WSE install it changed: a file next to its launchers, written after a
 * successful install and read by the removal step of the setup program, which runs without a person to ask.
 */
final class Setup
{
	static final String RECORD = "wse-home.txt";

	private Setup()
	{
	}

	/** The setup program's folder when running from its launcher (jpackage sets jpackage.app-path), else null. */
	static Path appDir()
	{
		String launcher = System.getProperty("jpackage.app-path");
		return launcher == null ? null : Paths.get(launcher).toAbsolutePath().getParent();
	}

	/** The install the setup program last changed, or null. */
	static Path recorded()
	{
		Path dir = appDir();
		if(dir == null || !Files.isRegularFile(dir.resolve(RECORD)))
		{
			return null;
		}
		try
		{
			String s = Files.readString(dir.resolve(RECORD), StandardCharsets.UTF_8).trim();
			return s.isEmpty() ? null : Paths.get(s);
		}
		catch(IOException e)
		{
			return null;
		}
	}

	static void recordHome(Path home)
	{
		Path dir = appDir();
		if(dir == null)
		{
			return;
		}
		try
		{
			Files.writeString(dir.resolve(RECORD), home.toAbsolutePath().toString() + System.lineSeparator(), StandardCharsets.UTF_8);
		}
		catch(IOException e)
		{
			// the removal step then finds the install the usual way
		}
	}

	/** Forgets the recorded install when it is this one. */
	static void forgetHome(Path home)
	{
		Path r = recorded();
		if(r != null && r.toAbsolutePath().normalize().equals(home.toAbsolutePath().normalize()))
		{
			try
			{
				Files.deleteIfExists(appDir().resolve(RECORD));
			}
			catch(IOException e)
			{
				// harmless: it names an install with nothing left to remove
			}
		}
	}
}
