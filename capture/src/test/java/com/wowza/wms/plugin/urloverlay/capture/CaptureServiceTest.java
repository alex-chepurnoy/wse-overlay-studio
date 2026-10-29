/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class CaptureServiceTest
{
	/** A stand-in for Chrome: a process that waits, with a profile in its command line. */
	public static final class Sleeper
	{
		public static void main(String[] a) throws Exception
		{
			Thread.sleep(120000);
		}
	}

	@Test
	void sessionLimitIsEnforcedAndReleased()
	{
		CaptureService s = new CaptureService(null, 2, 0);
		assertTrue(s.tryAcquire());
		assertTrue(s.tryAcquire());
		assertFalse(s.tryAcquire(), "third session over a limit of 2");
		s.release();
		assertTrue(s.tryAcquire());
		assertEquals(2, s.activeCaptures());
		// no limit given, or a nonsense one, falls back to four browsers
		assertEquals(4, new CaptureService(null, 0, 0).maxCaptures());
	}

	@Test
	void aChromeThatCannotStartSandboxedSaysWhatToFix()
	{
		String userns = CaptureService.sandboxStartFailure("session not created", true, "1");
		assertTrue(userns.contains("session not created") && userns.contains("apparmor_restrict_unprivileged_userns") && userns.contains("AppArmor"), userns);
		assertTrue(userns.contains("never runs Chrome without its sandbox"), userns);
		String linux = CaptureService.sandboxStartFailure("x", true, null);
		assertFalse(linux.contains("AppArmor"), linux);
		String windows = CaptureService.sandboxStartFailure("x", false, null);
		assertTrue(windows.contains("NT SERVICE\\WowzaURLOverlayCapture"), windows);
		assertFalse(windows.contains("=off"), "no setting turns the sandbox off: " + windows);
	}

	/**
	 * A process whose command line names a profile from another server tag is an orphan of an earlier run and is
	 * killed; one with the current tag is left alone. Stand-ins are java processes that sleep with the profile in
	 * their arguments, the way Chrome carries --user-data-dir. They run from the test classes, so nothing is compiled.
	 */
	@Test
	void reaperKillsOnlyEarlierRuns(@TempDir Path work) throws Exception
	{
		CaptureService s = new CaptureService(work, 1, 0);
		Path profiles = work.resolve("profiles");
		Path stale = Files.createDirectories(profiles.resolve("deadbeef-11111111"));
		Path current = s.newProfileDir("22222222");

		String java = ProcessHandle.current().info().command().orElse("java");
		String cp = System.getProperty("java.class.path");
		Process orphan = new ProcessBuilder(java, "-Xshare:auto", "-cp", cp, Sleeper.class.getName(), "--user-data-dir=" + stale).start();
		Process mine = new ProcessBuilder(java, "-Xshare:auto", "-cp", cp, Sleeper.class.getName(), "--user-data-dir=" + current).start();
		try
		{
			// long enough for the operating system to list both command lines
			Thread.sleep(500);
			int killed = s.reapOrphans();
			assertTrue(orphan.waitFor(20, TimeUnit.SECONDS), "orphan was not killed");
			assertTrue(killed >= 1);
			assertTrue(mine.isAlive(), "process of the current run was killed");
			assertFalse(Files.exists(stale), "stale profile not removed");
			assertTrue(Files.exists(current), "current profile removed");
		}
		finally
		{
			orphan.destroyForcibly();
			mine.destroyForcibly();
		}
		// a profile directory carries the server tag
		{
			CaptureService s2 = new CaptureService(work, 1, 0);
			Path p = s2.newProfileDir("abc12345");
			assertEquals(work.resolve("profiles").resolve(s2.serverTag() + "-abc12345"), p);
			assertTrue(Files.isDirectory(p));
		}
	}
}
