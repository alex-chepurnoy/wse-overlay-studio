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
	void theLimitChangesWhileRunningWithinItsCeiling()
	{
		CaptureService s = new CaptureService(null, 1, 0);
		assertTrue(s.tryAcquire());
		assertFalse(s.tryAcquire());
		assertEquals(3, s.setMaxCaptures(3));
		assertTrue(s.tryAcquire(), "a raised limit applies at once");
		assertEquals(1, s.startMaxCaptures(), "capture.properties' value is kept for the status");
		// lowered below what is open: the open pages stay, new ones wait
		s.setMaxCaptures(1);
		assertEquals(2, s.activeCaptures());
		assertFalse(s.tryAcquire());
		for(int bad: new int[] { 0, -1, CaptureService.MAX_CAPTURES_CEILING + 1 })
		{
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> s.setMaxCaptures(bad));
		}
		assertEquals(1, s.maxCaptures());

		CaptureHandler h = new CaptureHandler(null, x -> java.util.Map.of(), s::setMaxCaptures);
		java.util.Map<String, Object> req = Wire.header(Wire.LIMITS);
		req.put("maxCaptures", 5);
		Wire.Message a = h.handle(new Wire.Message(req));
		assertTrue(a.ok(), String.valueOf(a.header()));
		assertEquals(5, a.num("maxCaptures", 0));
		req.put("maxCaptures", 2.5);
		assertFalse(h.handle(new Wire.Message(req)).ok(), "a fraction is refused");
		req.put("maxCaptures", 99);
		assertFalse(h.handle(new Wire.Message(req)).ok(), "over the fixed ceiling is refused, not clamped");
		req.put("maxCaptures", 4294967297L);
		assertFalse(h.handle(new Wire.Message(req)).ok(), "a huge number does not wrap into range");
		assertEquals(5, s.maxCaptures());
		assertFalse(new CaptureHandler(null, x -> java.util.Map.of()).handle(new Wire.Message(req)).ok(), "a handler without a setter refuses");
	}

	@Test
	void theServicesOwnMemoryCapBoundsTheLimit()
	{
		// the installer's unit for four 1 GB browsers: 4 x 1024 + 768 MB, two cores
		CaptureService s = new CaptureService(null, 4, 1024);
		s.limits(String.valueOf((4L * 1024 + 768) * 1024 * 1024), "200000 100000");
		assertEquals(4, s.ceiling());
		assertEquals(4864, s.memoryMaxMb());
		assertEquals(2.0, s.cpuLimit());
		assertEquals(3, s.setMaxCaptures(3));
		IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> s.setMaxCaptures(5));
		assertTrue(e.getMessage().contains("MemoryMax") && e.getMessage().contains("installer"), e.getMessage());
		// no cap, or none readable: the fixed ceiling
		CaptureService free = new CaptureService(null, 4, 1024);
		free.limits("max", "max 100000");
		assertEquals(CaptureService.MAX_CAPTURES_CEILING, free.ceiling());
		assertEquals(-1, free.cpuLimit());
		// a cap smaller than capture.properties asks for never lowers the start value
		CaptureService tight = new CaptureService(null, 4, 1024);
		tight.limits(String.valueOf(1024L * 1024 * 1024), null);
		assertEquals(4, tight.ceiling());
		// and a start value past the fixed ceiling does not raise it: the ceiling is one the service accepts
		CaptureService big = new CaptureService(null, 40, 1024);
		big.limits(String.valueOf(64L * 1024 * 1024 * 1024), null);
		assertEquals(CaptureService.MAX_CAPTURES_CEILING, big.ceiling());
		// the memory cap, not the 1 to 32 range, refuses here
		CaptureService under = new CaptureService(null, 2, 1024);
		under.limits(String.valueOf((6L * 1024 + 768) * 1024 * 1024), null);
		assertEquals(6, under.ceiling());
		assertEquals(6, under.setMaxCaptures(6));
		IllegalArgumentException over = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> under.setMaxCaptures(7));
		assertTrue(over.getMessage().contains("MemoryMax"), over.getMessage());
	}

	@Test
	void aBrowserOffItsPageIsNoticed()
	{
		// what Chrome shows when it is not on the page: its new tab page, a blank page, its error page
		for(String off: new String[] { "chrome://new-tab-page/", "chrome://newtab/", "about:blank", "chrome-error://chromewebdata/", "data:text/html,x" })
		{
			assertEquals(off, WebPageCapture.offPageAddress(off), off);
		}
		assertEquals("an empty page", WebPageCapture.offPageAddress(""));
		// a web page, including one the address redirected to, is on the page
		for(String on: new String[] { "https://digitalclock.live/", "http://10.0.0.5:8080/score", "HTTPS://Example.com/x" })
		{
			assertNull(WebPageCapture.offPageAddress(on), on);
		}
		assertNull(WebPageCapture.offPageAddress(null), "unknown is not off");
	}

	@Test
	void theCgroupLimitsAreReadOnV2V1AndHybridHosts()
	{
		java.util.Map<String, String> files = new java.util.HashMap<>();
		java.util.function.Function<Path, String> fs = p -> files.get(p.toString().replace('\\', '/'));
		// v2: the unit's own folder
		files.put("/sys/fs/cgroup/system.slice/cap.service/memory.max", "5100273664\n");
		files.put("/sys/fs/cgroup/system.slice/cap.service/cpu.max", "200000 100000\n");
		String[] v2 = CaptureService.cgroupLimits("0::/system.slice/cap.service\n", fs);
		assertEquals("5100273664", v2[0]);
		assertEquals("200000 100000", v2[1]);
		// v1 and hybrid: the memory and cpu controllers' folders, in v2's words
		files.clear();
		files.put("/sys/fs/cgroup/memory/system.slice/cap.service/memory.limit_in_bytes", "5100273664");
		files.put("/sys/fs/cgroup/cpu,cpuacct/system.slice/cap.service/cpu.cfs_quota_us", "200000");
		files.put("/sys/fs/cgroup/cpu,cpuacct/system.slice/cap.service/cpu.cfs_period_us", "100000");
		String v1self = "12:pids:/system.slice/cap.service\n7:memory:/system.slice/cap.service\n4:cpu,cpuacct:/system.slice/cap.service\n0::/system.slice/cap.service\n";
		String[] v1 = CaptureService.cgroupLimits(v1self, fs);
		assertEquals("5100273664", v1[0]);
		assertEquals("200000 100000", v1[1]);
		CaptureService s = new CaptureService(null, 4, 1024);
		s.limits(v1[0], v1[1]);
		assertEquals(4, s.ceiling());
		assertEquals(2.0, s.cpuLimit());
		// v1 with no limit set reads a huge number and a quota of -1
		files.put("/sys/fs/cgroup/memory/system.slice/cap.service/memory.limit_in_bytes", "9223372036854771712");
		files.put("/sys/fs/cgroup/cpu,cpuacct/system.slice/cap.service/cpu.cfs_quota_us", "-1");
		String[] none = CaptureService.cgroupLimits(v1self, fs);
		assertEquals("max", none[0]);
		assertEquals("max 100000", none[1]);
		// no /proc (Windows), or nothing readable
		String[] win = CaptureService.cgroupLimits(null, fs);
		assertNull(win[0]);
		assertNull(win[1]);
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
