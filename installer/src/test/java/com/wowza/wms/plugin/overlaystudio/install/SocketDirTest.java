/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the installer has Java make its internal Unix socket: Windows' temp folder on Windows when nothing set it, so
 * security software that refuses sockets in the user's temp folder does not fail the install (exit 7, before 2.0.0).
 */
class SocketDirTest
{
	@TempDir
	Path dir;

	@Test
	void windowsUsesItsOwnTempFolder()
	{
		assertEquals(dir.toString(), Main.socketDir("Windows 11", null, dir));
		assertEquals(dir.toString(), Main.socketDir("Windows Server 2019", null, dir));
	}

	@Test
	void anythingElseKeepsJavasDefault()
	{
		assertNull(Main.socketDir("Linux", null, dir), "only Windows");
		assertNull(Main.socketDir("Windows 11", "C:\\Temp", dir), "a folder someone set wins");
		assertNull(Main.socketDir("Windows 11", null, dir.resolve("missing")), "a folder that is not there");
		assertNull(Main.socketDir("Windows 11", null, null), "no SystemRoot");
		assertNull(Main.socketDir(null, null, dir));
	}
}
