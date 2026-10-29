/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** The capture service's log: one line per event, to stdout (systemd and nssm keep it) or to the configured file. */
public final class Log
{
	private static volatile PrintStream out = System.out;
	private static final Log INSTANCE = new Log();

	private Log()
	{
	}

	public static Log get()
	{
		return INSTANCE;
	}

	/** Appends to this file from now on. */
	public static void toFile(Path file) throws IOException
	{
		if(file.getParent() != null)
		{
			Files.createDirectories(file.getParent());
		}
		out = new PrintStream(new FileOutputStream(file.toFile(), true), true, StandardCharsets.UTF_8);
	}

	public void info(String msg)
	{
		line("INFO", msg, null);
	}

	public void warn(String msg)
	{
		line("WARN", msg, null);
	}

	public void error(String msg, Throwable t)
	{
		line("ERROR", msg, t);
	}

	private static synchronized void line(String level, String msg, Throwable t)
	{
		out.println(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " " + level + " " + msg + (t == null ? "" : ": " + t));
	}
}
