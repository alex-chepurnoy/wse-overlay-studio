/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daily Chrome update (ChromeUpdate): checks, downloads, switches, checks the service, rolls back, keeps two. */
class ChromeUpdateTest
{
	static final String OLD = "156.0.8078.73", NEW = "157.0.8100.12", OLDER = "155.0.7000.1";

	@TempDir
	Path tmp;
	CaptureSetup.Layout l;
	InstallerTest.FakeSys sys;
	com.sun.net.httpserver.HttpServer cft;
	final AtomicInteger zips = new AtomicInteger();
	final List<String> fetched = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	volatile String stable = NEW;
	volatile boolean zip404;
	/** The Chrome the stand-in service reports: what its settings name, unless told it cannot find that one. */
	volatile boolean serviceFindsNew = true;
	String output;

	@BeforeEach
	void setUp() throws IOException
	{
		Path home = tmp.resolve("wse");
		Files.createDirectories(home);
		l = InstallerTest.testLayout(tmp, home);
		sys = new InstallerTest.FakeSys();
		sys.serviceExists = true;
		sys.running = true;
		CaptureSetup.testSys = sys;
		CaptureSetup.testLayout = l;
		String plat = ChromeDownload.platform();
		boolean win = plat.startsWith("win");
		// the Chrome for this OS at /chrome.zip (Windows) or /shell.zip (Linux); chromedriver is listed and never fetched
		Map<String, byte[]> served = Map.of("/chrome.zip", InstallerTest.zip("chrome-" + plat + "/" + (win ? "chrome.exe" : "chrome"), "new chrome"),
				"/shell.zip", InstallerTest.zip("chrome-headless-shell-" + plat + "/chrome-headless-shell" + (win ? ".exe" : ""), "new chrome"),
				"/driver.zip", InstallerTest.zip("chromedriver-" + plat + "/chromedriver", "new driver"));
		cft = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		String base = "http://127.0.0.1:" + cft.getAddress().getPort();
		cft.createContext("/list.json", x -> send(x, InstallerTest.list(plat, base, stable).getBytes(StandardCharsets.UTF_8)));
		served.forEach((path, body) -> cft.createContext(path, x -> {
			zips.incrementAndGet();
			fetched.add(path);
			send(x, zip404 ? null : body);
		}));
		cft.start();
		ChromeDownload.source = new ChromeDownload.Source(base + "/list.json", InstallerTest::standIn, InstallerTest::standIn);
		ChromeUpdate.testProbe = socket -> {
			Properties p = settings();
			String chrome = p.getProperty("chrome");
			boolean found = serviceFindsNew || !chrome.contains(NEW);
			return Map.of("ok", true, "chrome", Map.of("found", found, "path", chrome));
		};
		// what the installer left: the Chrome in use, an older one, and the settings pointing at the one in use, with the
		// chromedriver line an earlier version wrote, which the service ignores
		version(OLD);
		version(OLDER);
		Files.createDirectories(l.configDir());
		Files.writeString(l.config(), "# Written by the URL Overlay installer\nsocket=" + esc(l.socket()) + "\nchrome=" + esc(chromeIn(OLD)) + "\nchromedriver="
				+ esc(l.chromeRoot().resolve(OLD).resolve("chromedriver-" + plat + "/chromedriver")) + "\nmaxCaptures=6\n");
	}

	@AfterEach
	void tearDown()
	{
		cft.stop(0);
		ChromeDownload.source = ChromeDownload.GOOGLE;
		ChromeUpdate.testProbe = null;
		CaptureSetup.testSys = null;
		CaptureSetup.testLayout = null;
	}

	static void send(com.sun.net.httpserver.HttpExchange x, byte[] body) throws IOException
	{
		if(body == null)
		{
			x.sendResponseHeaders(404, -1);
		}
		else
		{
			x.sendResponseHeaders(200, body.length);
			x.getResponseBody().write(body);
		}
		x.close();
	}

	static String esc(Path p)
	{
		return p.toString().replace("\\", "\\\\");
	}

	Path chromeIn(String v)
	{
		return ChromeDownload.chromeIn(l.chromeRoot().resolve(v), ChromeDownload.platform());
	}

	void version(String v) throws IOException
	{
		Files.createDirectories(chromeIn(v).getParent());
		Files.writeString(chromeIn(v), "chrome " + v);
		Files.writeString(l.chromeRoot().resolve(v).resolve(ChromeDownload.COMPLETE), v);
	}

	Properties settings() throws IOException
	{
		Properties p = new Properties();
		p.load(new StringReader(Files.readString(l.config())));
		return p;
	}

	Map<String, Object> state()
	{
		return ChromeUpdate.readState(l.configDir().resolve(ChromeUpdate.STATE));
	}

	int update()
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int code = Main.run(new String[] { "UpdateChrome" }, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false, null);
		output = b.toString(StandardCharsets.UTF_8);
		InstallerTest.assertNoClassNames(output);
		return code;
	}

	boolean restarted()
	{
		return sys.ran.stream().anyMatch(c -> String.join(" ", c).contains(" start " + CaptureSetup.SERVICE));
	}

	@Test
	void versionsCompareAsNumbers()
	{
		assertTrue(ChromeUpdate.compare("156.0.8078.100", "156.0.8078.73") > 0, "100 is newer than 73, though it sorts first as text");
		assertTrue(ChromeUpdate.compare("157.0.0.0", "156.9.9999.9") > 0);
		assertEquals(0, ChromeUpdate.compare(OLD, OLD));
		assertTrue(ChromeUpdate.compare("156.0.8078", OLD) < 0);
		assertTrue(ChromeUpdate.compare(OLD, NEW) < 0);
	}

	@Test
	void aNewerStableIsDownloadedTakenIntoUseCheckedAndTheOldestRemoved() throws Exception
	{
		assertEquals(0, update(), output);
		assertEquals(List.of(InstallerTest.expectedZip()), fetched, "one zip, the Chrome for this OS; never chromedriver");
		Properties p = settings();
		assertEquals(chromeIn(NEW).toString(), p.getProperty("chrome"), "the settings point at the new Chrome");
		assertNull(p.getProperty("chromedriver"), "the chromedriver line of an earlier version is dropped");
		assertEquals("6", p.getProperty("maxCaptures"), "every other setting stays");
		assertEquals(l.socket().toString(), p.getProperty("socket"));
		assertTrue(restarted(), sys.ran.toString());
		assertTrue(Files.isRegularFile(l.chromeRoot().resolve(NEW).resolve(ChromeDownload.COMPLETE)));
		assertTrue(Files.isDirectory(l.chromeRoot().resolve(OLD)), "the previous version is kept");
		assertFalse(Files.exists(l.chromeRoot().resolve(OLDER)), "older ones go");
		Map<String, Object> s = state();
		assertEquals("updated", s.get("result"));
		assertEquals(NEW, s.get("version"));
		assertEquals(OLD, s.get("previous"));
		assertTrue(s.get("checked") != null && s.get("installed") != null, s.toString());
		assertTrue(Files.isRegularFile(l.logs().resolve("chrome-update.log")), "the run is logged");
		if(!l.windows())
		{
			assertEquals("rwxr-xr-x root:root", sys.modes.get(l.chromeRoot().resolve(NEW)), "the new Chrome is root's, read and execute for the service");
		}
		else
		{
			assertTrue(sys.ran("icacls " + l.chromeRoot() + " /grant " + CaptureSetup.WINDOWS_ACCOUNT + ":(OI)(CI)RX"), sys.ran.toString());
		}
	}

	@Test
	void nothingHappensWhenChromeIsCurrent() throws Exception
	{
		stable = OLD;
		byte[] config = Files.readAllBytes(l.config());
		assertEquals(0, update(), output);
		assertTrue(output.contains("is the newest Stable"), output);
		assertEquals(0, zips.get());
		assertArrayEquals(config, Files.readAllBytes(l.config()));
		assertFalse(restarted());
		assertTrue(Files.isDirectory(l.chromeRoot().resolve(OLDER)), "nothing is removed either");
		assertEquals("current", state().get("result"));
		assertEquals(OLD, state().get("version"));
	}

	@Test
	void aFailedDownloadChangesNothing() throws Exception
	{
		zip404 = true;
		byte[] config = Files.readAllBytes(l.config());
		assertEquals(5, update());
		assertArrayEquals(config, Files.readAllBytes(l.config()));
		assertFalse(Files.exists(l.chromeRoot().resolve(NEW)), "no half-downloaded folder stays");
		assertFalse(restarted(), "the service was never touched");
		Map<String, Object> s = state();
		assertEquals("failed", s.get("result"));
		assertTrue(String.valueOf(s.get("message")).contains("could not be downloaded") && String.valueOf(s.get("message")).contains(OLD + " stays in use"), s.toString());
	}

	@Test
	void aServiceThatDoesNotTakeTheNewChromeGetsTheOldOneBack() throws Exception
	{
		serviceFindsNew = false;
		byte[] config = Files.readAllBytes(l.config());
		assertEquals(5, update(), output);
		assertArrayEquals(config, Files.readAllBytes(l.config()), "capture.properties is put back byte for byte");
		assertFalse(Files.exists(l.chromeRoot().resolve(NEW)), "the version that failed is removed, so the next run tries it fresh");
		assertTrue(Files.isDirectory(l.chromeRoot().resolve(OLD)));
		long restarts = sys.ran.stream().filter(c -> String.join(" ", c).contains(" start " + CaptureSetup.SERVICE)).count();
		assertEquals(2, restarts, "restarted on the new Chrome, then on the old one");
		Map<String, Object> s = state();
		assertEquals("failed", s.get("result"));
		assertEquals(OLD, s.get("version"));
		assertTrue(String.valueOf(s.get("message")).contains("does not find the new Chrome") && String.valueOf(s.get("message")).contains("was put back"), s.toString());
	}

	@Test
	void autoUpdateFalseIsRespected() throws Exception
	{
		Files.writeString(l.config(), Files.readString(l.config()) + "autoUpdate=false\n");
		byte[] config = Files.readAllBytes(l.config());
		assertEquals(0, update());
		assertTrue(output.contains("autoUpdate=false"), output);
		assertEquals(0, zips.get());
		assertArrayEquals(config, Files.readAllBytes(l.config()));
		assertEquals("off", state().get("result"));
	}

	@Test
	void aChromeChosenByHandIsLeftAlone() throws Exception
	{
		Files.writeString(l.config(), "chrome=" + esc(tmp.resolve("own/chrome.exe")) + "\nchromedriver=" + esc(tmp.resolve("own/chromedriver.exe")) + "\n");
		assertEquals(0, update());
		assertEquals(0, zips.get());
		assertEquals("own", state().get("result"));
	}

	@Test
	void theSettingsKeepEveryOtherLineAndLoseChromedriver()
	{
		String t = "# comment\nsocket=s\nchrome=a\nchromedriver=b\nautoUpdate=true\n";
		assertEquals("# comment\nsocket=s\nchrome=x\nautoUpdate=true\n", ChromeUpdate.withChrome(t, Path.of("x")));
		// spaced, indented or last, and without a chrome line to replace
		assertEquals("socket=s\n  # kept\npagesPerBrowser=2\nchrome=x\n", ChromeUpdate.withChrome("socket=s\n  # kept\n  chromedriver = b\npagesPerBrowser=2\nchromedriver=c\n", Path.of("x")));
		assertEquals("socket=s\nchrome=x\n", ChromeUpdate.withChrome("socket=s", Path.of("x")));
	}

	@Test
	void aLinuxInstallStillOnChromeForTestingMovesEvenWhenItsVersionIsTheNewest() throws Exception
	{
		// what an earlier installer left on Linux: chrome-linux64/chrome and chromedriver in the version folder in use. The
		// test runs on either OS, so the folder names the layout the installer no longer puts there.
		stable = OLD;
		Path old = l.chromeRoot().resolve(OLD);
		ChromeDownload.deleteTree(old);
		Path oldChrome = old.resolve("chrome-linux64/chrome");
		Files.createDirectories(oldChrome.getParent());
		Files.createDirectories(old.resolve("chromedriver-linux64"));
		Files.writeString(oldChrome, "chrome " + OLD);
		Files.writeString(old.resolve("chromedriver-linux64/chromedriver"), "driver " + OLD);
		Files.writeString(old.resolve(ChromeDownload.COMPLETE), OLD);
		Files.writeString(l.config(), "socket=" + esc(l.socket()) + "\nchrome=" + esc(oldChrome) + "\nchromedriver=" + esc(old.resolve("chromedriver-linux64/chromedriver"))
				+ "\nmaxCaptures=6\n");

		assertEquals(0, update(), output);
		assertEquals(List.of(InstallerTest.expectedZip()), fetched, output);
		Path moved = ChromeDownload.chromeIn(l.chromeRoot().resolve(OLD + "-2"), ChromeDownload.platform());
		Properties p = settings();
		assertEquals(moved.toString(), p.getProperty("chrome"), "beside the folder that was in use, never into it");
		assertNull(p.getProperty("chromedriver"));
		assertEquals("6", p.getProperty("maxCaptures"));
		assertTrue(restarted(), sys.ran.toString());
		assertTrue(Files.isRegularFile(oldChrome), "the Chrome it ran before is kept as the previous version");
		assertFalse(Files.exists(l.chromeRoot().resolve(OLDER)), "older ones go");
		assertEquals("updated", state().get("result"));

		// the next run finds it current: nothing more
		fetched.clear();
		assertEquals(0, update(), output);
		assertTrue(output.contains("is the newest Stable"), output);
		assertEquals(List.of(), fetched);
	}

	@Test
	void aMoveThatFailedIsNotTriedAgainForTheSameVersion() throws Exception
	{
		stable = OLD;
		Path old = l.chromeRoot().resolve(OLD);
		ChromeDownload.deleteTree(old);
		Path oldChrome = old.resolve("chrome-linux64/chrome");
		Files.createDirectories(oldChrome.getParent());
		Files.writeString(oldChrome, "chrome " + OLD);
		Files.writeString(old.resolve(ChromeDownload.COMPLETE), OLD);
		Files.writeString(l.config(), "socket=" + esc(l.socket()) + "\nchrome=" + esc(oldChrome) + "\nmaxCaptures=6\n");
		// what a run that could not take the headless shell into use left in the state file
		Files.writeString(l.configDir().resolve(ChromeUpdate.STATE), "{\"" + ChromeUpdate.MOVE_FAILED + "\":\"" + OLD + "\"}");

		assertEquals(0, update(), output);
		assertEquals(List.of(), fetched, "nothing downloaded again: " + output);
		assertFalse(restarted(), "and the service is not restarted: " + sys.ran);
		assertEquals(oldChrome.toString(), settings().getProperty("chrome"));

		assertEquals("failed", state().get("result"), "the Server view says it did not move, not that Chrome is current");

		// behind Stable, and the move to that Stable failed too: still nothing, every day
		stable = NEW;
		Files.writeString(l.configDir().resolve(ChromeUpdate.STATE), "{\"" + ChromeUpdate.MOVE_FAILED + "\":\"" + NEW + "\"}");
		assertEquals(0, update(), output);
		assertEquals(List.of(), fetched, "a Chrome behind Stable is not moved again for a version that failed: " + output);
		assertFalse(restarted(), sys.ran.toString());

		// a newer Stable than the one that failed is tried
		Files.writeString(l.configDir().resolve(ChromeUpdate.STATE), "{\"" + ChromeUpdate.MOVE_FAILED + "\":\"" + OLD + "\"}");
		assertEquals(0, update(), output);
		assertEquals(List.of(InstallerTest.expectedZip()), fetched, output);
	}

	@Test
	void theStateFileRecordsTheInstallersOwnChrome()
	{
		ChromeUpdate.recordInstalled(l, sys, OLD);
		Map<String, Object> s = state();
		assertEquals("installed", s.get("result"));
		assertEquals(OLD, s.get("version"));
		String installed = String.valueOf(s.get("installed"));
		ChromeUpdate.recordInstalled(l, sys, OLD);
		assertEquals(installed, state().get("installed"), "a reinstall of the same version keeps its age");
		assertNull(state().get("message"));
	}

	@Test
	void theTimerAndTheTaskAreCreatedAndRemoved() throws Exception
	{
		Path jar = tmp.resolve("installer.jar");
		Files.writeString(jar, "jar");
		CaptureSetup.testSelfJar = jar;
		try
		{
			Path home = tmp.resolve("wse");
			Files.createDirectories(home.resolve("conf"));
			Files.writeString(home.resolve("conf/Server.xml"), "<Root/>");
			CaptureSetup.Config c = new CaptureSetup.Config(chromeIn(OLD), 4, 1024);

			CaptureSetup.Layout lx = CaptureSetup.linux(tmp.resolve("linux"));
			CaptureSetup cs = new CaptureSetup(lx, sys, home, new Payload(), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
			cs.install(c);
			assertTrue(Files.isRegularFile(lx.program().resolve(ChromeUpdate.JAR)), "the updater sits in the program folder");
			String service = Files.readString(ChromeUpdate.serviceUnit(lx)), timer = Files.readString(ChromeUpdate.timerUnit(lx));
			assertTrue(service.contains("Type=oneshot") && service.contains("ExecStart=" + cs.java() + " -jar " + lx.program().resolve(ChromeUpdate.JAR) + " UpdateChrome"), service);
			for(String line: List.of("OnCalendar=daily", "RandomizedDelaySec=4h", "Persistent=true", "WantedBy=timers.target"))
			{
				assertTrue(timer.lines().anyMatch(line::equals), line + " in\n" + timer);
			}
			assertTrue(sys.ran("systemctl enable --now WowzaURLOverlayChromeUpdate.timer"), sys.ran.toString());
			CaptureSetup.uninstall(InstallerTest.castMap((Map<?, ?>)Json.parse(Json.write(cs.record()))), sys, System.out);
			assertTrue(sys.ran("systemctl disable --now WowzaURLOverlayChromeUpdate.timer"), sys.ran.toString());
			assertFalse(Files.exists(ChromeUpdate.serviceUnit(lx)) || Files.exists(ChromeUpdate.timerUnit(lx)));

			CaptureSetup.Layout lw = CaptureSetup.windows(tmp.resolve("win/Program Files/Wowza URL Overlay"), tmp.resolve("win/ProgramData/WowzaURLOverlay"), tmp.resolve("runtime"));
			sys.serviceExists = false;
			Files.createDirectories(home.resolve("bin"));
			Files.writeString(home.resolve("bin/nssm_x64.exe"), "nssm");
			CaptureSetup ws = new CaptureSetup(lw, sys, home, new Payload(), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
			ws.install(c);
			byte[] xml = Files.readAllBytes(ChromeUpdate.taskXml(lw));
			assertEquals((byte)0xFF, xml[0], "UTF-16 with its byte order mark, as Task Scheduler reads it");
			String task = new String(xml, 2, xml.length - 2, StandardCharsets.UTF_16LE);
			for(String part: List.of("<RandomDelay>PT4H</RandomDelay>", "<DaysInterval>1</DaysInterval>", "<UserId>S-1-5-18</UserId>",
					"<Command>" + ws.java() + "</Command>", "<Arguments>-jar \"" + lw.program().resolve(ChromeUpdate.JAR) + "\" UpdateChrome</Arguments>"))
			{
				assertTrue(task.contains(part), part + " in\n" + task);
			}
			assertTrue(sys.ran.contains(List.of("schtasks", "/Create", "/TN", "WowzaURLOverlayChromeUpdate", "/XML", ChromeUpdate.taskXml(lw).toString(), "/F")), sys.ran.toString());
			CaptureSetup.uninstall(InstallerTest.castMap((Map<?, ?>)Json.parse(Json.write(ws.record()))), sys, System.out);
			assertTrue(sys.ran.contains(List.of("schtasks", "/Delete", "/TN", "WowzaURLOverlayChromeUpdate", "/F")), sys.ran.toString());
		}
		finally
		{
			CaptureSetup.testSelfJar = null;
		}
	}
}
