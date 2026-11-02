/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.FileNotFoundException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * Errors in plain words for the console and the setup window: never a Java class name. The full detail, with the stack
 * trace, goes to the run's log only, through {@link #detail}, which Main and the setup window point at the log buffer.
 */
final class Plain
{
	/** Where the full detail of a failure goes: the log only. */
	static volatile PrintStream detail = new PrintStream(OutputStream.nullOutputStream());
	/**
	 * Set once a run has changed anything on disk or in the system (a download started, the capture service, the WSE
	 * files), so a failure says "nothing was changed" only when that is true.
	 */
	static volatile boolean changed;

	private Plain()
	{
	}

	/** What went wrong, in words; the exception itself is written to the log. */
	static String say(Throwable e)
	{
		PrintStream d = detail;
		d.println("Detail:");
		e.printStackTrace(d);
		return words(e);
	}

	static String words(Throwable e)
	{
		Throwable t = e;
		while((t instanceof java.io.UncheckedIOException || t instanceof java.util.concurrent.ExecutionException || t instanceof java.lang.reflect.InvocationTargetException)
				&& t.getCause() != null)
		{
			t = t.getCause();
		}
		if(t instanceof Installer.Failed || t instanceof CaptureSetup.Failed)
		{
			return t.getMessage();
		}
		if(t instanceof AccessDeniedException a)
		{
			return "access was denied to " + a.getFile() + (a.getOtherFile() == null ? "" : " (moving it to " + a.getOtherFile() + ")")
					+ ". Another program, often an antivirus scanner, may be holding it, or this run lacks administrator rights";
		}
		if(t instanceof NoSuchFileException n)
		{
			return n.getFile() + " does not exist";
		}
		if(t instanceof FileAlreadyExistsException f)
		{
			return f.getFile() + " is already there";
		}
		if(t instanceof DirectoryNotEmptyException d)
		{
			return d.getFile() + " is not empty";
		}
		if(t instanceof FileSystemException f)
		{
			return f.getFile() + (f.getReason() == null ? " could not be changed" : ": " + f.getReason());
		}
		if(t instanceof FileNotFoundException)
		{
			return "a file could not be opened: " + text(t);
		}
		if(t instanceof UnknownHostException)
		{
			return "the address " + text(t) + " could not be found (no network or no DNS)";
		}
		if(t instanceof ConnectException)
		{
			return "the connection was refused or could not be made";
		}
		if(t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException)
		{
			return "the server did not answer in time";
		}
		if(t instanceof InterruptedIOException || t instanceof InterruptedException)
		{
			return "it was stopped before it finished";
		}
		if(t instanceof java.nio.channels.UnresolvedAddressException)
		{
			return "an address could not be found (no network or no DNS)";
		}
		String m = text(t);
		return m.isEmpty() ? "an unexpected problem occurred; the log has the details" : m;
	}

	// the message without any class name the JDK may have put in front
	private static String text(Throwable t)
	{
		String m = t.getMessage();
		if(m == null)
		{
			return "";
		}
		return m.replaceAll("\\b(?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error)\\b:?\\s*", "").trim();
	}
}
