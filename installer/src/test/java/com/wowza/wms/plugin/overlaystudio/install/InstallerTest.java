/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.wowza.wms.plugin.overlaystudio.config.XmlScan;
import com.wowza.wms.plugin.overlaystudio.config.TextFile;
import com.wowza.wms.plugin.overlaystudio.config.ConfigEdits;

/** Runs the installer against copies of a real WSE 4.12 conf tree (tests/fixtures/installer/wse412). */
class InstallerTest
{
	@TempDir
	Path tmp;
	Path home;
	String output;

	// a stand-in for Google's Chrome for Testing service: the list and small zips with the real layout
	com.sun.net.httpserver.HttpServer cft;
	final java.util.concurrent.atomic.AtomicInteger zipRequests = new java.util.concurrent.atomic.AtomicInteger();
	/** The zips fetched, by path on the stand-in service. */
	final List<String> zipsFetched = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	static final String CFT_VERSION = "156.0.8078.73";
	FakeSys sys;
	/** Set when a Chrome zip was fetched while the stand-in capture service ran. */
	volatile boolean serviceRanDuringDownload;

	/** The capture service's places for this platform, under tmp/sys, and the WSE service helper and runtime it copies. */
	static CaptureSetup.Layout testLayout(Path tmp, Path home) throws IOException
	{
		Path runtime = tmp.resolve("runtime");
		Files.createDirectories(runtime.resolve("bin"));
		Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"21\"\n");
		Files.writeString(runtime.resolve("bin/java.exe"), "java");
		Files.createDirectories(home.resolve("bin"));
		Files.writeString(home.resolve("bin/nssm_x64.exe"), "nssm");
		Path root = tmp.resolve("sys");
		return Preflight.WINDOWS ? CaptureSetup.windows(root.resolve("Program Files/Wowza Overlay Studio"), root.resolve("ProgramData/WowzaOverlayStudio"), runtime)
				: CaptureSetup.linux(root);
	}

	/** Records every command; answers as a system where nothing of Overlay Studio is installed yet. */
	static final class FakeSys implements CaptureSetup.Sys
	{
		final List<List<String>> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
		final Map<Path, String> modes = new java.util.concurrent.ConcurrentHashMap<>();
		boolean apparmor, abi4, serviceExists, running;
		/** A command to fail, by its first two words, for the undo tests. */
		String failing;
		/** Processes by pid and the program each runs, standing in for the service's Chrome and chromedriver. */
		final Map<Long, Path> processes = new java.util.concurrent.ConcurrentHashMap<>();
		/** Whether stopping the service ends its processes, as nssm and systemd do; false leaves them for kill. */
		boolean stopEndsProcesses = true;
		final List<Long> killed = new java.util.concurrent.CopyOnWriteArrayList<>();
		/** The service's registration as Windows and nssm keep it: ImagePath, Start (2 auto, 3 demand, 4 disabled), ObjectName, and nssm's values. */
		final Map<String, String> registration = new java.util.concurrent.ConcurrentHashMap<>();

		static boolean missing(String path)
		{
			return path != null && !Files.isRegularFile(Path.of(path.replace("\"", "")));
		}

		@Override
		public CaptureSetup.Result run(List<String> cmd)
		{
			ran.add(cmd);
			String c = String.join(" ", cmd);
			if(failing != null && c.startsWith(failing))
			{
				return new CaptureSetup.Result(1, "failed on purpose");
			}
			if(cmd.get(0).endsWith("nssm.exe") && cmd.get(1).equals("install"))
			{
				serviceExists = true;
				registration.clear();
				registration.put("ImagePath", cmd.get(0));
				registration.put("Application", cmd.get(3));
				registration.put("Start", "2");
				registration.put("ObjectName", "LocalSystem");
			}
			if(cmd.get(0).endsWith("nssm.exe") && cmd.get(1).equals("set"))
			{
				String v = String.join(" ", cmd.subList(4, cmd.size()));
				registration.put(cmd.get(3), cmd.get(3).equals("Start") ? (v.equals("SERVICE_AUTO_START") ? "2" : "3") : v);
			}
			if(cmd.get(0).equals("sc.exe") && cmd.get(1).equals("config"))
			{
				for(int k = 3; k + 1 < cmd.size(); k += 2)
				{
					String v = cmd.get(k + 1);
					switch(cmd.get(k))
					{
						case "binPath=" -> registration.put("ImagePath", v);
						case "obj=" -> registration.put("ObjectName", v);
						case "start=" -> registration.put("Start", v.equals("auto") ? "2" : v.equals("disabled") ? "4" : "3");
						default -> {
						}
					}
				}
			}
			if(cmd.get(0).equals("reg") && cmd.get(1).equals("add"))
			{
				registration.put(cmd.get(cmd.indexOf("/v") + 1), cmd.get(cmd.indexOf("/d") + 1));
			}
			if(cmd.get(0).equals("sc.exe") && cmd.get(1).equals("start"))
			{
				// Windows refuses a disabled service, and one whose program is not on disk does not start
				if("4".equals(registration.get("Start")))
				{
					return new CaptureSetup.Result(1058, "The service cannot be started because it is disabled");
				}
				if(missing(registration.get("ImagePath")) || missing(registration.get("Application")))
				{
					running = false;
					return new CaptureSetup.Result(1053, "The service did not respond");
				}
			}
			if(cmd.get(0).endsWith("nssm.exe") && cmd.get(1).equals("remove"))
			{
				serviceExists = false;
				running = false;
				registration.clear();
			}
			boolean starts = cmd.size() > 1 && (cmd.get(1).equals("start") || cmd.get(1).equals("restart"));
			boolean stops = cmd.size() > 1 && (cmd.get(1).equals("stop") || cmd.contains("--now") && cmd.get(1).equals("disable"));
			if((cmd.get(0).equals("sc.exe") || cmd.get(0).equals("systemctl") || cmd.get(0).endsWith("nssm.exe")) && cmd.stream().anyMatch(x -> x.startsWith(CaptureSetup.SERVICE)))
			{
				if(starts)
				{
					running = true;
				}
				if(stops)
				{
					running = false;
					if(stopEndsProcesses)
					{
						processes.clear();
					}
				}
			}
			return switch(cmd.get(0))
			{
				case "getent" -> new CaptureSetup.Result(2, "");
				case "sc.exe" -> cmd.get(1).equals("qc") ? (serviceExists ? new CaptureSetup.Result(0, "        BINARY_PATH_NAME   : " + registration.getOrDefault("ImagePath", "")
						+ "\n        START_TYPE         : " + registration.getOrDefault("Start", "3") + "   AUTO_START\n        SERVICE_START_NAME : "
						+ registration.getOrDefault("ObjectName", "LocalSystem") + "\n") : new CaptureSetup.Result(1060, ""))
						: cmd.get(1).equals("query") ? new CaptureSetup.Result(serviceExists ? 0 : 1060, !serviceExists ? "" : running ? "STATE : 4 RUNNING" : "STATE : 1 STOPPED")
						: new CaptureSetup.Result(0, "");
				case "systemctl" -> new CaptureSetup.Result(0, cmd.get(1).equals("is-active") ? (running ? "active\n" : "inactive\n") : "");
				case "id" -> new CaptureSetup.Result(0, "wowza\n");
				case "reg" -> {
					String name = cmd.get(cmd.indexOf("/v") + 1);
					String v = registration.get(name);
					yield cmd.get(1).equals("query") && v != null ? new CaptureSetup.Result(0, "\n    " + name + "    REG_EXPAND_SZ    " + v + "\n")
							: new CaptureSetup.Result(cmd.get(1).equals("query") ? 1 : 0, "");
				}
				default -> new CaptureSetup.Result(0, "");
			};
		}

		@Override
		public void posix(Path p, String mode, String owner, String group)
		{
			modes.put(p, mode + " " + owner + ":" + group);
		}

		@Override
		public List<Long> processesUnder(Path dir)
		{
			Path d = dir.toAbsolutePath().normalize();
			return processes.entrySet().stream().filter(e -> e.getValue().toAbsolutePath().normalize().startsWith(d)).map(Map.Entry::getKey).toList();
		}

		@Override
		public void kill(long pid)
		{
			killed.add(pid);
			processes.remove(pid);
		}

		@Override
		public boolean apparmor()
		{
			return apparmor;
		}

		@Override
		public boolean apparmorAbi4()
		{
			return abi4;
		}

		boolean ran(String start)
		{
			return ran.stream().anyMatch(c -> String.join(" ", c).startsWith(start));
		}
	}

	Path chromeRoot()
	{
		return CaptureSetup.testLayout.chromeRoot();
	}

	String captureConfig() throws IOException
	{
		return Files.readString(CaptureSetup.testLayout.config());
	}

	@BeforeEach
	void copyTree() throws IOException
	{
		// a server sized for four pages, the number earlier versions always set
		CaptureSetup.Machine.fixed = new CaptureSetup.Machine(3, 4096);
		home = tmp.resolve("wse");
		copy(Paths.get(System.getProperty("overlaystudio.fixtures"), "wse412"), home);
		fakeServerJar(home, "4.12.1+15");
		fakeManager(home, "6.2.19", "10.1.59");
		ManagerBridge.testRunner = cmd -> 0;
		// the capture service goes under a temporary system root, and its commands are recorded, not run
		sys = new FakeSys();
		CaptureSetup.testSys = sys;
		CaptureSetup.testLayout = testLayout(tmp, home);
		// no WSE is running for the test trees, whatever runs on this machine
		Preflight.testCommandLines = Stream::empty;
		// and no 1.x, whatever is installed on this machine
		LegacyInstallCheck.testMachine = new LegacyInstallCheck.Machine(List.of(), cmd -> null);
		startCft();
	}

	@org.junit.jupiter.api.AfterEach
	void stopCft()
	{
		cft.stop(0);
		ChromeDownload.source = ChromeDownload.GOOGLE;
		Preflight.testCommandLines = null;
		LegacyInstallCheck.testMachine = null;
		Installer.testAfterOp = null;
		Installer.testBeforeManifestMove = null;
		Ownership.testFactory = null;
		ManagerBridge.testRunner = null;
		CaptureSetup.testSys = null;
		CaptureSetup.testLayout = null;
		ChromeFolders.testVersionOf = null;
		System.clearProperty("jpackage.app-path");
	}

	void startCft() throws IOException
	{
		String plat = ChromeDownload.platform();
		cft = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		String base = "http://127.0.0.1:" + cft.getAddress().getPort();
		// the three downloads Google's list names per platform; only the one for this OS may be fetched
		serve("/list.json", list(plat, base, CFT_VERSION).getBytes(StandardCharsets.UTF_8));
		serve("/chrome.zip", zip("chrome-" + plat + "/" + (plat.startsWith("win") ? "chrome.exe" : "chrome"), "fake chrome"));
		serve("/shell.zip", zip("chrome-headless-shell-" + plat + "/chrome-headless-shell" + (plat.startsWith("win") ? ".exe" : ""), "fake chrome"));
		serve("/driver.zip", zip("chromedriver-" + plat + "/chromedriver", "fake driver"));
		cft.start();
		ChromeDownload.source = new ChromeDownload.Source(base + "/list.json", ChromeDownload.GOOGLE.listAllowed().or(InstallerTest::standIn),
				ChromeDownload.GOOGLE.downloadAllowed().or(InstallerTest::standIn));
	}

	/** Google's list as it is shaped: chrome, chrome-headless-shell and chromedriver, each at base/&lt;chrome|shell|driver&gt;.zip. */
	static String list(String plat, String base, String version)
	{
		return "{\"timestamp\":\"t\",\"channels\":{\"Stable\":{\"channel\":\"Stable\",\"version\":\"" + version + "\",\"downloads\":{"
				+ "\"chrome\":[{\"platform\":\"" + plat + "\",\"url\":\"" + base + "/chrome.zip\"}],"
				+ "\"chrome-headless-shell\":[{\"platform\":\"" + plat + "\",\"url\":\"" + base + "/shell.zip\"}],"
				+ "\"chromedriver\":[{\"platform\":\"" + plat + "\",\"url\":\"" + base + "/driver.zip\"}]}}}}";
	}

	/** Which zip the capture service gets on this OS: Chrome for Testing on Windows, the headless shell elsewhere. */
	static String expectedZip()
	{
		return Preflight.WINDOWS ? "/chrome.zip" : "/shell.zip";
	}

	/** The stand-in service's own address, the only one besides Google's that the tests allow. */
	static boolean standIn(java.net.URI u)
	{
		return "127.0.0.1".equals(u.getHost());
	}

	void serve(String path, byte[] body)
	{
		cft.createContext(path, x -> {
			if(path.endsWith(".zip"))
			{
				zipRequests.incrementAndGet();
				zipsFetched.add(path);
				serviceRanDuringDownload |= sys.running;
			}
			x.sendResponseHeaders(200, body.length);
			x.getResponseBody().write(body);
			x.close();
		});
	}

	static byte[] zip(String entry, String content) throws IOException
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		try(java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b))
		{
			z.putNextEntry(new java.util.zip.ZipEntry(entry));
			z.write(content.getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
		}
		return b.toByteArray();
	}

	@Test
	void downloadsChromeForTestingOnceAndRemovesItOnUninstall() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Path chrome = ChromeDownload.chromeIn(dir, ChromeDownload.platform());
		assertTrue(Files.isRegularFile(chrome), output);
		assertTrue(Files.isRegularFile(dir.resolve(ChromeDownload.COMPLETE)), "the marker is written last");
		assertEquals(List.of(expectedZip()), zipsFetched, "one zip, the Chrome for this OS; never chromedriver");
		try(Stream<Path> s = Files.list(dir))
		{
			assertEquals(1, s.filter(Files::isDirectory).count(), "no chromedriver folder");
		}
		String config = captureConfig();
		assertTrue(config.contains("chrome=" + chrome.toString().replace("\\", "\\\\")), "the capture service is pointed at it: " + config);
		assertFalse(config.contains("chromedriver"), config);
		assertFalse(text("conf/Server.xml").contains("OverlayStudioChromeDriverPath"), "WSE itself no longer starts Chrome");

		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(1, zipRequests.get(), "a second run downloads nothing");
		assertTrue(output.contains("No changes."), output);
		assertTrue(output.contains("Chrome:       " + chrome), "the report names the Chrome the service uses: " + output);
		assertFalse(output.contains("chromedriver"), output);
		try(Stream<Path> s = Files.list(home.resolve("logs")))
		{
			assertEquals(2, s.count(), "both runs keep their log, even within one second");
		}

		assertEquals(0, run("Uninstall", "--yes"));
		assertFalse(Files.exists(chromeRoot()), "the download is removed");
		assertArrayEquals(server0, bytes("conf/Server.xml"));
	}

	@Test
	void aChromeFolderWithoutItsMarkerIsCleanedAndUnpackedAgain() throws Exception
	{
		// what an interrupted run, or a scanner that held a file, leaves: the version folder without its marker
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Path chrome = ChromeDownload.chromeIn(dir, ChromeDownload.platform());
		Files.createDirectories(chrome.getParent());
		Files.writeString(chrome, "half written");
		Files.writeString(dir.resolve("leftover.zip.download"), "x");
		assertNull(ChromeDownload.existing(chromeRoot(), CFT_VERSION, ChromeDownload.platform()), "no marker, not complete");

		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(1, zipRequests.get(), "unpacked again");
		assertEquals("fake chrome", Files.readString(chrome));
		assertFalse(Files.exists(dir.resolve("leftover.zip.download")), "the incomplete folder was cleaned first");
		assertNotNull(ChromeDownload.existing(chromeRoot(), CFT_VERSION, ChromeDownload.platform()));
		try(Stream<Path> s = Files.list(chromeRoot()))
		{
			assertEquals(List.of(dir), s.toList(), "unpacked in place: no .partial folder, nothing renamed");
		}
	}

	@Test
	void noDownloadWhenPathsAreGivenOrRefusedOrOffline() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertEquals(0, zipRequests.get());
		assertEquals(0, run("Uninstall", "--yes"));

		assertEquals(0, run("Install", "--appName", "live", "--yes", "--chrome", "C:/somewhere/chrome.exe"));
		assertEquals(0, zipRequests.get(), "a given Chrome is used, nothing downloaded");
		assertTrue(captureConfig().contains("chrome=" + Paths.get("C:/somewhere/chrome.exe").toString().replace("\\", "\\\\")), captureConfig());
		assertEquals(0, run("Uninstall", "--yes"));

		// what scripts for earlier versions pass: accepted, said to be ignored, and Chrome is downloaded as without it
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--chromedriver", "C:/somewhere/chromedriver.exe"));
		assertTrue(output.contains("--chromedriver is ignored"), output);
		assertEquals(List.of(expectedZip()), zipsFetched);
		assertFalse(captureConfig().contains("chromedriver"), captureConfig());
		assertEquals(0, run("Uninstall", "--yes"));
		zipRequests.set(0);

		cft.stop(0);
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("Chrome was not downloaded"), "offline: the install goes on and says how to finish: " + output);
		assertNoClassNames(output);
		assertTrue(ConfigEdits.hasModule(text("conf/live/Application.xml")));
		assertFalse(Files.exists(chromeRoot().resolve(CFT_VERSION)));
	}

	@Test
	void unzipRefusesEntriesOutsideTheTarget() throws Exception
	{
		Path z = tmp.resolve("evil.zip");
		Files.write(z, zip("../outside.txt", "x"));
		Path target = tmp.resolve("t");
		Files.createDirectories(target);
		org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> ChromeDownload.unzip(z, target));
		assertFalse(Files.exists(tmp.resolve("outside.txt")));
	}

	static void copy(Path from, Path to) throws IOException
	{
		try(Stream<Path> s = Files.walk(from))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				Path t = to.resolve(from.relativize(p).toString());
				if(Files.isDirectory(p))
				{
					Files.createDirectories(t);
				}
				else if(!p.getFileName().toString().equals(".keep") && !p.getFileName().toString().equals("README.md"))
				{
					Files.copy(p, t);
				}
			}
		}
	}

	/**
	 * A stand-in for the Manager files the bridge's version gate reads: WMSManager.war with spring-webmvc at the given
	 * version, the Manager's Tomcat jar, the start script's --tempDirectory, and the version line the service name comes from.
	 */
	static void fakeManager(Path home, String spring, String tomcat) throws IOException
	{
		Path m = home.resolve("manager");
		Files.createDirectories(m.resolve("lib"));
		Files.createDirectories(m.resolve("bin"));
		try(java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(m.resolve("lib/WMSManager.war"))))
		{
			for(String e: List.of("WEB-INF/wowzaapp-servlet.xml", "WEB-INF/lib/spring-webmvc-" + spring + ".jar", "WEB-INF/lib/spring-security-core-6.5.9.jar"))
			{
				z.putNextEntry(new java.util.zip.ZipEntry(e));
				z.closeEntry();
			}
		}
		try(Stream<Path> s = Files.list(m.resolve("lib")))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				if(p.getFileName().toString().startsWith("wms-tomcat-"))
				{
					Files.delete(p);
				}
			}
		}
		Files.write(m.resolve("lib/wms-tomcat-" + tomcat + ".jar"), new byte[0]);
		Files.writeString(m.resolve("bin/startmgr.sh"), "CMD=\"$_EXECJAVA -cp $CLASSPATH launch.Main --prefix=/enginemanager --tempDirectory=$WMSMGR_HOME/temp  --pluginsDirectory=$WMSMGR_HOME/wse-plugins\"\n");
		Files.writeString(m.resolve("bin/wowzamgrversion.bat"), "\r\nset WowzaMgrVersion=Wowza Streaming Engine Manager 4.12.1+15\r\n");
	}

	Path bridgeDir()
	{
		return ManagerBridge.classDir(home.resolve("manager"));
	}

	static void fakeServerJar(Path home, String version) throws IOException
	{
		Manifest m = new Manifest();
		m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		m.getMainAttributes().putValue("Specification-Version", version);
		Files.createDirectories(home.resolve("lib"));
		try(JarOutputStream j = new JarOutputStream(Files.newOutputStream(home.resolve("lib").resolve("wms-server.jar")), m))
		{
			// manifest only
		}
	}

	int run(String... args)
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		String[] all = new String[args.length + 2];
		all[0] = "--home";
		all[1] = home.toString();
		System.arraycopy(args, 0, all, 2, args.length);
		int code = Main.run(all, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false, null);
		output = b.toString(StandardCharsets.UTF_8);
		return code;
	}

	/** Every file under home except logs, with its hash. */
	Map<String, String> tree() throws IOException
	{
		Map<String, String> m = new TreeMap<>();
		try(Stream<Path> s = Files.walk(home))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				String rel = home.relativize(p).toString().replace('\\', '/');
				if(Files.isRegularFile(p) && !rel.startsWith("logs/"))
				{
					m.put(rel, TextFile.sha256(p));
				}
			}
		}
		return m;
	}

	byte[] bytes(String rel) throws IOException
	{
		return Files.readAllBytes(home.resolve(rel));
	}

	String text(String rel) throws IOException
	{
		return TextFile.read(home.resolve(rel)).text();
	}

	static void wellFormed(byte[] xml) throws Exception
	{
		DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
	}

	long backups() throws IOException
	{
		try(Stream<Path> s = Files.walk(home))
		{
			return s.filter(p -> p.getFileName().toString().contains(".overlaystudio-") && p.getFileName().toString().endsWith(".bak")).count();
		}
	}

	@Test
	void dryRunWritesNothing() throws Exception
	{
		Map<String, String> before = tree();
		assertEquals(0, run("Install", "--appName", "live", "--dry-run"));
		assertTrue(output.contains("Would change"), output);
		assertTrue(output.contains("+ <BaseClass>com.wowza.wms.plugin.overlaystudio.OverlayStudioServer</BaseClass>"), output);
		assertEquals(before, tree());
		try(Stream<Path> s = Files.list(home.resolve("logs")))
		{
			assertEquals(0, s.count(), "a dry run writes no log either");
		}
	}

	@Test
	void installThenSecondRunChangesNothingThenUninstallRestoresBytes() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml"), live0 = bytes("conf/live/Application.xml"), rtc0 = bytes("conf/webrtc/Application.xml"), vhost0 = bytes("conf/VHost.xml");
		Map<String, String> tree0 = tree();

		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		String server = text("conf/Server.xml"), live = text("conf/live/Application.xml");
		assertTrue(server.contains("<BaseClass>" + ConfigEdits.SERVER_CLASS + "</BaseClass>"));
		assertTrue(ConfigEdits.hasProperty(server, "/Root/Server/Properties", "OverlayStudioServer"));
		assertTrue(ConfigEdits.hasModule(live));
		assertTrue(live.contains("com.wowza.wms.plugin.videointelligence"), "VIF's module is still there");
		assertArrayEquals(rtc0, bytes("conf/webrtc/Application.xml"), "an app not chosen is not touched");
		wellFormed(bytes("conf/Server.xml"));
		wellFormed(bytes("conf/live/Application.xml"));
		try(Stream<Path> s = Files.list(home.resolve("lib")))
		{
			assertTrue(s.map(p -> p.getFileName().toString()).anyMatch(n -> n.matches("wse-plugin-overlay-studio-\\d[\\d.]*(-SNAPSHOT)?\\.jar")), "the module jar is installed");
		}
		for(String jar: new Payload().under("lib/"))
		{
			assertTrue(Files.exists(home.resolve(jar)), "every jar the installer carries is in lib: " + jar);
		}
		assertTrue(Files.exists(home.resolve("transcoder/templates/overlaystudio-cpu.xml")));
		assertTrue(Files.exists(home.resolve("manager/wse-plugins/server/overlay-studio/config.json")));
		// the page plays HLS with its own hls.js, so it works on a WSE without VIF (whose page carries another copy)
		assertTrue(Files.size(home.resolve("manager/wse-plugins/server/overlay-studio/vendor/hls.min.js")) > 100_000);
		assertTrue(Files.readString(home.resolve("manager/wse-plugins/server/overlay-studio/vendor/hls.js-LICENSE.txt")).contains("Apache License"), "hls.js goes with its license");
		String index = text("manager/wse-plugins/server/overlay-studio/index.html");
		assertTrue(index.contains("wse-plugins/server/overlay-studio/vendor/hls.min.js") && !index.contains("/vif/"), "loads its own copy, nothing of VIF's");
		java.util.regex.Matcher scripts = java.util.regex.Pattern.compile("wse-plugins/server/overlay-studio/(js/[^\"?]+)").matcher(index);
		while(scripts.find())
		{
			assertTrue(Files.exists(home.resolve("manager/wse-plugins/server/overlay-studio/" + scripts.group(1))), "every script the page loads is installed: " + scripts.group(1));
		}
		assertTrue(Files.exists(home.resolve(Installer.MANIFEST)));
		// no REST relay on the streaming ports any more: the page goes through the Manager bridge
		assertArrayEquals(vhost0, bytes("conf/VHost.xml"));
		assertTrue(Files.isRegularFile(bridgeDir().resolve("OverlayStudioBridge.class")), "the bridge is in the Manager's temp folder");
		assertEquals(2, backups());
		try(Stream<Path> s = Files.list(home.resolve("logs")))
		{
			assertEquals(1, s.count(), "the run is logged");
		}

		Map<String, String> tree1 = tree();
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("No changes."), output);
		assertEquals(tree1, tree(), "a second run writes nothing");
		assertEquals(2, backups(), "and takes no backup");

		assertEquals(0, run("Uninstall", "--yes"));
		assertArrayEquals(server0, bytes("conf/Server.xml"));
		assertArrayEquals(live0, bytes("conf/live/Application.xml"));
		assertTrue(output.contains("original bytes"), output);
		assertFalse(Files.exists(home.resolve(Installer.MANIFEST)));
		assertFalse(Files.exists(home.resolve("manager/wse-plugins/server/overlay-studio")));
		Map<String, String> tree2 = tree();
		tree2.keySet().removeIf(k -> k.endsWith(".bak"));
		assertEquals(tree0, tree2, "back to the original tree, apart from the kept backups");
	}

	@Test
	void uninstallKeepsSavedOverlaysUnlessPurge() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		Path moduleLive = home.resolve("conf.modules/overlaystudio/live");
		Files.createDirectories(moduleLive);
		Files.writeString(moduleLive.resolve("Scoreboard.json"), "{\"objects\":[]}");

		assertEquals(0, run("Uninstall", "--yes"));
		assertTrue(Files.exists(moduleLive.resolve("Scoreboard.json")), "uninstall keeps the saved overlays");
		assertTrue(output.contains("--purge"), output);
		assertFalse(Files.exists(home.resolve(Installer.MANIFEST)));

		assertEquals(0, run("Install", "--yes", "--no-download"));
		assertEquals(0, run("Uninstall", "--yes", "--purge"));
		assertFalse(Files.exists(home.resolve("conf.modules/overlaystudio")), "--purge deletes them");
	}

	@Test
	void uninstallAfterSomeoneElseEditedTakesOutOnlyItsOwnLines() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		// another tool edits Server.xml after the install
		String t = text("conf/Server.xml").replace("</Root>", "<!-- added by another tool -->" + TextFile.read(home.resolve("conf/Server.xml")).eol() + "</Root>");
		Files.write(home.resolve("conf/Server.xml"), TextFile.read(home.resolve("conf/Server.xml")).with(t).bytes());

		assertEquals(0, run("Uninstall", "--yes"));
		assertTrue(output.contains("surgically reverted"), output);
		String after = text("conf/Server.xml");
		assertFalse(after.contains("OverlayStudio"), "every line it added is gone");
		assertTrue(after.contains("<!-- added by another tool -->"), "the other tool's edit stays");
		String original = TextFile.of(server0).text();
		assertEquals(original.replace("</Root>", "<!-- added by another tool -->" + TextFile.of(server0).eol() + "</Root>"), after);
	}

	@Test
	void removeAppTakesOutOneApplicationOnly() throws Exception
	{
		byte[] rtc0 = bytes("conf/webrtc/Application.xml");
		assertEquals(0, run("Install", "--appName", "live,webrtc", "--yes"));
		assertTrue(output.contains("transcoder off"), "webrtc's transcoder is off: " + output);
		assertTrue(ConfigEdits.hasModule(text("conf/webrtc/Application.xml")));
		assertEquals(0, run("Install", "--remove-app", "webrtc", "--yes"));
		assertArrayEquals(rtc0, bytes("conf/webrtc/Application.xml"));
		assertTrue(ConfigEdits.hasModule(text("conf/live/Application.xml")));
		String man = Files.readString(home.resolve(Installer.MANIFEST));
		assertTrue(man.contains("_defaultVHost_/live") && !man.contains("_defaultVHost_/webrtc"), man);
	}

	@Test
	void refusals() throws Exception
	{
		Map<String, String> before = tree();
		assertEquals(1, run("Install", "--appName", "nosuchapp", "--yes"));
		assertTrue(output.contains("Applications found"), output);
		assertEquals(1, run("Install", "--appName", "vod", "--yes"));
		assertTrue(output.contains("not live"), output);
		assertEquals(1, run("Install", "--appName", "live", "--all-live-apps"));
		assertEquals(1, run("Frobnicate"));
		assertEquals(before, tree(), "nothing written on a refusal");

		fakeServerJar(home, "4.10.2");
		assertEquals(4, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("older than 4.11.1"), output);
	}

	@Test
	void severalInstallsWithoutHomeIsAmbiguous() throws Exception
	{
		Path other = tmp.resolve("wse2");
		copy(home, other);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int code = Main.run(new String[] { "--list-apps" }, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false,
				List.of(home, other));
		assertEquals(2, code);
		assertTrue(b.toString(StandardCharsets.UTF_8).contains(other.toString()));
		code = Main.run(new String[] { "--list-apps" }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
				new BufferedReader(new StringReader("")), false, List.of(tmp.resolve("nothing-here")));
		assertEquals(2, code);
	}

	@Test
	void failureMidwayPutsEverythingBack() throws Exception
	{
		// a file where the plugin page folder must go: creating the folder fails after the config edits were written
		Files.writeString(home.resolve("manager/wse-plugins/server/overlay-studio"), "in the way");
		Map<String, String> before = tree();
		assertEquals(5, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("put back"), output);
		Map<String, String> after = tree();
		after.keySet().removeIf(k -> k.endsWith(".bak"));
		assertEquals(before, after, "every file as it was");
		assertFalse(Files.exists(home.resolve(Installer.MANIFEST)));
		assertFalse(Files.exists(CaptureSetup.testLayout.program()), "the capture service's program is taken out again");
		assertFalse(Files.exists(CaptureSetup.testLayout.config()), "and its settings");
		assertNoClassNames(output);
	}

	@Test
	void upgradeRetiresTheOlderJarAndSkipsDependenciesWseHas() throws Exception
	{
		Files.writeString(home.resolve("lib/wse-plugin-overlay-studio-0.9.0.jar"), "old");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertFalse(Files.exists(home.resolve("lib/wse-plugin-overlay-studio-0.9.0.jar")), "an older version is not left where WSE would load it");
		try(Stream<Path> s = Files.list(home.resolve("lib")))
		{
			assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("wse-plugin-overlay-studio-0.9.0.jar.overlaystudio-")), "kept as a backup");
		}
	}

	@Test
	void upgradeReplacesItsOwnOlderJarWithoutABackup() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Path lib = home.resolve("lib");
		Path jar;
		try(Stream<Path> s = Files.list(lib))
		{
			jar = s.filter(p -> p.getFileName().toString().matches("wse-plugin-overlay-studio-\\d.*\\.jar")).findFirst().orElseThrow();
		}
		// as if the previous install had been version 0.8.0: same bytes, recorded in the manifest under that name
		Path old = lib.resolve("wse-plugin-overlay-studio-0.8.0.jar");
		Files.move(jar, old);
		Path man = home.resolve(Installer.MANIFEST);
		Files.writeString(man, Files.readString(man).replace(jar.getFileName().toString(), old.getFileName().toString()));
		assertEquals(0, run("Install", "--yes"));
		assertFalse(Files.exists(old), "the older version is gone");
		assertTrue(Files.exists(jar), "the new one is in place");
		try(Stream<Path> s = Files.list(lib))
		{
			assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".bak")), "no backup of a jar the installer put there");
		}
	}

	@Test
	void existingValuesAreKeptAndNoAppIsGuessed() throws Exception
	{
		String s = text("conf/Server.xml");
		XmlScan.Span p = XmlScan.first(s, "/Root/Server/Properties");
		TextFile f = TextFile.read(home.resolve("conf/Server.xml"));
		String withProp = s.substring(0, p.close()) + "<Property><Name>OverlayStudioAllowPrivateNetworks</Name><Value>true</Value><Type>Boolean</Type></Property>" + f.eol() + "\t\t" + s.substring(p.close());
		Files.write(home.resolve("conf/Server.xml"), f.with(withProp).bytes());
		byte[] live0 = bytes("conf/live/Application.xml");

		assertEquals(0, run("Install", "--yes"));
		assertTrue(output.contains("No application was changed"), output);
		assertArrayEquals(live0, bytes("conf/live/Application.xml"), "--yes without --appName changes no application");
		String after = text("conf/Server.xml");
		assertEquals(1, after.split("<Name>OverlayStudioAllowPrivateNetworks</Name>", -1).length - 1, "not added twice");
		assertTrue(after.contains("<Name>OverlayStudioAllowPrivateNetworks</Name><Value>true</Value>"), "the existing value is kept");
		for(String gone: List.of("OverlayStudioMaxCaptures", "OverlayStudioChromePath", "OverlayStudioChromeDriverPath", "OverlayStudioChromeSandbox", "OverlayStudioWorkDir"))
		{
			assertFalse(after.contains("<Name>" + gone + "</Name>"), "the module no longer reads " + gone);
		}
	}

	@Test
	void restInterfaceIsNeverTouchedAndAnEarlierRecordedEditThereStillComesOut() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml");
		String rest0 = restInterface(text("conf/Server.xml"));
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertEquals(rest0, restInterface(text("conf/Server.xml")), "no CORS headers, no IPWhiteList change: REST is reached only through the bridge");

		// as an earlier version left it: CORS headers inserted and IPWhiteList replaced, both recorded in the manifest
		TextFile f = TextFile.read(home.resolve("conf/Server.xml"));
		List<ConfigEdits.Change> earlier = new java.util.ArrayList<>();
		TextFile t = ConfigEdits.insert(f, "/Root/Server/RESTInterface/Properties", earlier, "CORS headers for the plugin page",
				ConfigEdits.property("restUserHTTPHeaders", "Access-Control-Allow-Origin:*", "String"));
		XmlScan.Span ip = XmlScan.first(t.text(), "/Root/Server/RESTInterface/IPWhiteList");
		String cur = t.text().substring(ip.contentStart(), ip.close());
		t = t.with(t.text().substring(0, ip.contentStart()) + "0.0.0.0/0" + t.text().substring(ip.close()));
		earlier.add(new ConfigEdits.Change("replace", "/Root/Server/RESTInterface/IPWhiteList", null, cur, "0.0.0.0/0", "IPWhiteList"));
		Files.write(home.resolve("conf/Server.xml"), t.bytes());
		Path mf = home.resolve(Installer.MANIFEST);
		@SuppressWarnings("unchecked")
		Map<String, Object> m = (Map<String, Object>)Json.parse(Files.readString(mf));
		Map<String, Object> e = Installer.find(Installer.list(m, "edited"), "conf/Server.xml");
		earlier.forEach(c -> Installer.list(e, "changes").add(c.toMap()));
		e.put("installed", TextFile.sha256(t.bytes()));
		Files.writeString(mf, Json.write(m));
		// and another tool edits the file, so uninstall has to take out each recorded change by itself
		String eol = f.eol();
		Files.write(home.resolve("conf/Server.xml"), TextFile.read(home.resolve("conf/Server.xml")).with(withComment(text("conf/Server.xml"), eol)).bytes());

		assertEquals(0, run("Uninstall", "--yes"));
		assertTrue(output.contains("surgically reverted"), output);
		assertEquals(withComment(TextFile.of(server0).text(), eol), text("conf/Server.xml"), "the recorded CORS and IPWhiteList edits come out like any other");
	}

	private static String restInterface(String server)
	{
		XmlScan.Span s = XmlScan.first(server, "/Root/Server/RESTInterface");
		assertNotNull(s);
		return server.substring(s.contentStart(), s.close());
	}

	@Test
	void interactivePromptPicksByNumber() throws Exception
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int code = Main.run(new String[] { "--home", home.toString(), "Install" }, new PrintStream(b, true, StandardCharsets.UTF_8),
				new BufferedReader(new StringReader("2\n")), true, null);
		assertEquals(0, code, b.toString(StandardCharsets.UTF_8));
		assertTrue(ConfigEdits.hasModule(text("conf/webrtc/Application.xml")), "number 2 is webrtc (vod is not numbered)");
		assertFalse(ConfigEdits.hasModule(text("conf/live/Application.xml")));
		assertFalse(b.toString(StandardCharsets.UTF_8).contains("No application was changed"), "an application picked at the prompt was changed");
		String asked = b.toString(StandardCharsets.UTF_8);
		assertTrue(asked.contains("  1  live") && asked.contains("  2  webrtc"), asked);
		assertTrue(asked.contains("vod (VOD, cannot carry an overlay)"), asked);
		assertFalse(asked.contains("Other plugins"), "the question shows the short list, not the --list-apps table");

		assertEquals(0, run("Install", "--all-live-apps", "--yes"));
		b = new ByteArrayOutputStream();
		assertEquals(0, Main.run(new String[] { "--home", home.toString(), "Install" }, new PrintStream(b, true, StandardCharsets.UTF_8),
				new BufferedReader(new StringReader("")), true, null));
		String out = b.toString(StandardCharsets.UTF_8);
		assertTrue(out.contains("Every live application already has Overlay Studio"), out);
		assertFalse(out.contains("Add Overlay Studio to which applications?"), "nothing left to ask");
	}

	@Test
	void scannerFindsElementsAndSkipsComments()
	{
		String x = "<Root><!-- <Properties> --><Server a=\"x>y\"><Properties><Property/></Properties></Server><![CDATA[</Server>]]></Root>";
		XmlScan.Span s = XmlScan.first(x, "/Root/Server/Properties");
		assertNotNull(s);
		assertEquals("<Property/>", x.substring(s.contentStart(), s.close()));
		assertEquals(1, XmlScan.find(x, "/Root/Server/Properties/Property").size());
		assertTrue(XmlScan.find(x, "/Root/Properties").isEmpty(), "the commented-out tag is not an element");
	}

	@Test
	void jsonRoundTrip()
	{
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("text", "line\n\"quoted\" \\ tab\t");
		m.put("list", List.of(1L, true, "x"));
		m.put("empty", Map.of());
		// as the manifest holds them: non-ASCII names, control characters, Windows paths, nested lists of maps, numbers
		m.put("unicode", "Überblick été 中文 😀");
		m.put("control", "\u0001\u001f end");
		m.put("windows", "C:\\Program Files\\Wowza Media Systems\\Wowza Streaming Engine 4.12.0\\conf\\Server.xml");
		List<Object> edited = List.of(Map.of("path", "conf/Server.xml", "changes", List.of(Map.of("kind", "insert", "inserted", "\r\n\t\t<Property>\r\n"))));
		m.put("edited", edited);
		m.put("numbers", List.of(0L, -3L, 1234567890123L));
		assertEquals(m, Json.parse(Json.write(m)));
		assertEquals("é", Json.parse("\"\\u00e9\""), "an escaped character reads back");
		assertEquals("😀", Json.parse("\"\\ud83d\\ude00\""), "a surrogate pair reads back");
	}

	// ---- recovery, upgrades and safety ------------------------------------------------------------------------------

	/** Takes a property block out of Server.xml by hand. */
	void removeProperty(String name) throws IOException
	{
		String t = text("conf/Server.xml");
		XmlScan.Span props = XmlScan.first(t, "/Root/Server/Properties");
		for(XmlScan.Span p: XmlScan.find(t, "/Root/Server/Properties/Property"))
		{
			if(p.open() > props.contentStart() && t.substring(p.contentStart(), p.close()).contains("<Name>" + name + "</Name>"))
			{
				t = t.substring(0, XmlScan.lineStart(t, p.open())) + t.substring(t.indexOf('\n', p.end()) + 1);
				break;
			}
		}
		Files.write(home.resolve("conf/Server.xml"), TextFile.read(home.resolve("conf/Server.xml")).with(t).bytes());
	}

	String withComment(String text, String eol)
	{
		return text.replace("</Root>", "<!-- added by another tool -->" + eol + "</Root>");
	}

	@Test
	void anotherToolsEditBetweenTwoInstallsSurvivesUninstall() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml");
		String eol = TextFile.of(server0).eol();
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		// another tool edits Server.xml, then a later install has a setting to add there (stood in for by one taken out)
		Files.write(home.resolve("conf/Server.xml"), TextFile.read(home.resolve("conf/Server.xml")).with(withComment(text("conf/Server.xml"), eol)).bytes());
		removeProperty("OverlayStudioBlockChromeServices");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(ConfigEdits.hasProperty(text("conf/Server.xml"), "/Root/Server/Properties", "OverlayStudioBlockChromeServices"), output);

		assertEquals(0, run("Uninstall", "--yes"));
		assertTrue(output.contains("surgically reverted"), "the old backup no longer stands for the file: " + output);
		assertEquals(withComment(TextFile.of(server0).text(), eol), text("conf/Server.xml"), "the other tool's edit is kept, every Overlay Studio line is gone");
	}

	@Test
	void aRunKilledPartwayCanStillBeUndone() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml"), vhost0 = bytes("conf/VHost.xml"), live0 = bytes("conf/live/Application.xml");
		Path killed = tmp.resolve("killed");
		// the disk as it is when the process dies right after its second change
		Installer.testAfterOp = (inst, k) -> {
			if(k == 1)
			{
				try
				{
					copy(home, killed);
				}
				catch(IOException e)
				{
					throw new java.io.UncheckedIOException(e);
				}
			}
		};
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Installer.testAfterOp = null;
		assertTrue(Files.readString(killed.resolve(Installer.MANIFEST)).contains("\"pending\""), "the manifest was written before the first change");
		assertFalse(Arrays.equals(server0, Files.readAllBytes(killed.resolve("conf/Server.xml"))), "the killed run had changed Server.xml");

		home = killed;
		assertEquals(0, run("Uninstall", "--yes"));
		assertTrue(output.contains("earlier run stopped"), output);
		assertArrayEquals(server0, bytes("conf/Server.xml"));
		assertArrayEquals(vhost0, bytes("conf/VHost.xml"));
		assertArrayEquals(live0, bytes("conf/live/Application.xml"));
		assertFalse(Files.exists(chromeRoot().resolve(CFT_VERSION)), "the capture service recorded as pending goes too, with its Chrome");
	}

	@Test
	void aRunKilledPartwayIsCompletedByTheNextOne() throws Exception
	{
		byte[] server0 = bytes("conf/Server.xml");
		Path killed = tmp.resolve("killed");
		Installer.testAfterOp = (inst, k) -> {
			if(k == 0)
			{
				try
				{
					copy(home, killed);
				}
				catch(IOException e)
				{
					throw new java.io.UncheckedIOException(e);
				}
			}
		};
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Installer.testAfterOp = null;
		home = killed;
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertFalse(Files.readString(home.resolve(Installer.MANIFEST)).contains("\"pending\""), "a finished run clears the mark");
		assertEquals(0, run("Uninstall", "--yes"));
		assertArrayEquals(server0, bytes("conf/Server.xml"));
	}

	@Test
	void aStopRequestPutsEverythingBack() throws Exception
	{
		Map<String, String> before = tree();
		// what the shutdown hook does on Ctrl+C or a closed console window, asked for after the third change
		Installer.testAfterOp = (inst, k) -> {
			if(k == 2)
			{
				inst.requestStop();
			}
		};
		assertEquals(5, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(output.contains("Stopped before the end") && output.contains("put back"), output);
		assertEquals(before, tree(), "every file as it was, and no backup or manifest left behind");
	}

	@Test
	void aFailureBetweenWritingAndReplacingTheManifestLeavesTheOldOne() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		byte[] manifest = bytes(Installer.MANIFEST);
		Map<String, String> before = tree();
		// the new manifest is written in full, then the run fails before it replaces the old one
		java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
		Installer.testBeforeManifestMove = () -> {
			if(once.compareAndSet(false, true))
			{
				throw new java.io.UncheckedIOException(new IOException("disk full"));
			}
		};
		assertEquals(5, run("Install", "--appName", "webrtc", "--yes", "--no-download"));
		assertTrue(once.get(), "the failure was injected");
		assertArrayEquals(manifest, bytes(Installer.MANIFEST), "the old manifest is intact");
		assertNotNull(Json.parse(Files.readString(home.resolve(Installer.MANIFEST))));
		Map<String, String> after = tree();
		after.keySet().removeIf(k -> k.endsWith(".tmp"));
		assertEquals(before, after, "and nothing else changed");
		assertEquals(0, run("Uninstall", "--yes"), "uninstall still works from it");
	}

	@Test
	void theSeleniumJarsAnEarlierVersionPutInWseLibGo() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		Path lib = home.resolve("lib");
		// as an earlier version left lib/: Selenium recorded as its own, and WSE's own guava beside it
		Files.writeString(lib.resolve("selenium-api-4.21.0.jar"), "ours");
		Files.writeString(lib.resolve("guava-31.0-jre.jar"), "wse's own");
		Path man = home.resolve(Installer.MANIFEST);
		@SuppressWarnings("unchecked")
		Map<String, Object> m = (Map<String, Object>)Json.parse(Files.readString(man));
		Installer.list(m, "added").add(new java.util.LinkedHashMap<>(Map.of("path", "lib/selenium-api-4.21.0.jar", "sha256", TextFile.sha256("ours".getBytes(StandardCharsets.UTF_8)))));
		Files.writeString(man, Json.write(m));

		assertEquals(0, run("Install", "--yes", "--no-download"));
		assertFalse(Files.exists(lib.resolve("selenium-api-4.21.0.jar")), "Selenium is the capture service's now: " + output);
		assertTrue(Files.exists(lib.resolve("guava-31.0-jre.jar")), "a jar that was never ours stays");
		for(String p: new Payload().under("lib/"))
		{
			assertTrue(p.substring(4).startsWith(Installer.MODULE_JAR_PREFIX), "only the module goes into WSE's lib: " + p);
		}
	}

	@Test
	void a1xInstallStopsInstallWithExit9BeforeAnythingIsDownloadedOrWritten() throws Exception
	{
		Map<String, String> before = tree();
		Path leftover = Files.createDirectories(tmp.resolve("sys-1x"));
		LegacyInstallCheck.testMachine = new LegacyInstallCheck.Machine(List.of(leftover), cmd -> null);
		assertEquals(9, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("1.x is installed (found: " + leftover + ")"), output);
		assertEquals(0, zipRequests.get(), "nothing is downloaded for a run that cannot finish");
		assertEquals(before, tree());
		assertEquals(0, run("Uninstall", "--yes"), "uninstall does not run the check: " + output);
	}

	@Test
	void aRunningWseStopsInstallAndUninstallBeforeAnythingIsDownloadedOrWritten() throws Exception
	{
		Map<String, String> before = tree();
		String wse = "java -Dcom.wowza.wms.AppHome=" + home + " -Dcom.wowza.wms.ConfigHome=" + home + " -cp " + home + "/bin/wms-bootstrap.jar com.wowza.wms.bootstrap.Bootstrap start";
		Preflight.testCommandLines = () -> Stream.of("/usr/bin/bash", wse);
		assertEquals(3, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("is running"), output);
		assertEquals(0, zipRequests.get(), "nothing is downloaded for a run that cannot finish");
		assertEquals(before, tree());

		// another install's WSE, or a WSE tool that is not the server, does not count
		Preflight.testCommandLines = () -> Stream.of(wse.replace(home.toString(), tmp.resolve("other").toString()),
				"java -cp x com.wowza.wms.bootstrap.Bootstrap outputTuningCommandLine");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));

		Preflight.testCommandLines = () -> Stream.of(wse);
		byte[] server = bytes("conf/Server.xml");
		assertEquals(3, run("Uninstall", "--yes"));
		assertArrayEquals(server, bytes("conf/Server.xml"));
	}

	@Test
	void aChromeFileUninstallCannotDeleteIsReportedInPlainWords() throws Exception
	{
		org.junit.jupiter.api.Assumptions.assumeTrue(Preflight.WINDOWS, "an open file blocks deleting it on Windows only");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Path chrome = ChromeDownload.chromeIn(chromeRoot().resolve(CFT_VERSION), ChromeDownload.platform());
		try(java.io.RandomAccessFile open = new java.io.RandomAccessFile(chrome.toFile(), "r"))
		{
			assertEquals(0, run("Uninstall", "--yes"), "the rest of the uninstall goes on");
		}
		assertTrue(output.contains("Could not delete") && output.contains(chromeRoot().toString()), output);
		assertNoClassNames(output);
		assertFalse(Files.exists(home.resolve(Installer.MANIFEST)));
	}

	@Test
	void theSetupProgramsRemovalStepUsesTheRecordedInstall() throws Exception
	{
		Path app = tmp.resolve("app");
		Files.createDirectories(app);
		System.setProperty("jpackage.app-path", app.resolve("Wowza Overlay Studio.exe").toString());
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(home.toString(), Files.readString(app.resolve(Setup.RECORD)).trim(), "a successful install records its home");

		ByteArrayOutputStream b = new ByteArrayOutputStream();
		String wse = "java -Dcom.wowza.wms.AppHome=" + home + " com.wowza.wms.bootstrap.Bootstrap start";
		Preflight.testCommandLines = () -> Stream.of(wse);
		assertEquals(3, Main.run(new String[] { "--setup-uninstall" }, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false,
				List.of(tmp.resolve("nothing-here"))), "a running WSE stops the removal");
		Preflight.testCommandLines = Stream::empty;
		assertEquals(0, Main.run(new String[] { "--setup-uninstall" }, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false,
				List.of(tmp.resolve("nothing-here"))), b.toString(StandardCharsets.UTF_8));
		assertFalse(ConfigEdits.hasModule(text("conf/live/Application.xml")), "taken out of the recorded install, not looked for elsewhere");
		assertFalse(Files.exists(app.resolve(Setup.RECORD)));
		assertEquals(0, Main.run(new String[] { "--setup-uninstall" }, new PrintStream(b, true, StandardCharsets.UTF_8), new BufferedReader(new StringReader("")), false,
				List.of(tmp.resolve("nothing-here"))), "nothing recorded: the removal goes on");
	}

	@Test
	void elevationQuotesArgumentsAsWindowsSplitsThem()
	{
		assertEquals("\"C:\\Program Files\\Wowza\\\\\"", Elevate.quote("C:\\Program Files\\Wowza\\"), "a trailing backslash does not escape the closing quote");
		assertEquals("\"a\\\"b\"", Elevate.quote("a\"b"));
		assertEquals("\"a\\\\\\\"b\"", Elevate.quote("a\\\"b"));
		assertEquals("\"C:\\a\\b\"", Elevate.quote("C:\\a\\b"), "backslashes elsewhere are left alone");
		assertEquals("'\"it''s\"'", Elevate.argumentList(List.of("it's")));
	}

	@Test
	void chromeComesOnlyFromGoogleOverHttps() throws Exception
	{
		java.util.function.Predicate<java.net.URI> dl = ChromeDownload.GOOGLE.downloadAllowed(), list = ChromeDownload.GOOGLE.listAllowed();
		assertTrue(dl.test(java.net.URI.create("https://storage.googleapis.com/chrome-for-testing-public/156.0.8078.73/win64/chrome-win64.zip")));
		assertFalse(dl.test(java.net.URI.create("http://storage.googleapis.com/x.zip")), "not over plain HTTP");
		assertFalse(dl.test(java.net.URI.create("https://storage.googleapis.com.example.net/x.zip")));
		assertFalse(dl.test(java.net.URI.create("https://evil.example/x.zip")));
		assertFalse(dl.test(java.net.URI.create("https://user@storage.googleapis.com/x.zip")));
		assertTrue(list.test(java.net.URI.create(ChromeDownload.LIST)));
		assertFalse(list.test(java.net.URI.create("https://storage.googleapis.com/list.json")));
	}

	@Test
	void aListPointingElsewhereOrARedirectElsewhereIsRefused() throws Exception
	{
		String plat = ChromeDownload.platform();
		int port = cft.getAddress().getPort();
		// the zip URL names another host
		String other = list(plat, "http://localhost:" + port, CFT_VERSION);
		serve("/other.json", other.getBytes(StandardCharsets.UTF_8));
		// the zip URL is allowed but redirects to another host
		String redirect = other.replace("localhost:" + port + expectedZip(), "127.0.0.1:" + port + "/moved.zip");
		serve("/redirect.json", redirect.getBytes(StandardCharsets.UTF_8));
		cft.createContext("/moved.zip", x -> {
			x.getResponseHeaders().add("Location", "http://localhost:" + port + "/chrome.zip");
			x.sendResponseHeaders(302, -1);
			x.close();
		});
		String base = "http://127.0.0.1:" + port;
		for(String l: List.of("/other.json", "/redirect.json"))
		{
			ChromeDownload.source = new ChromeDownload.Source(base + l, InstallerTest::standIn, InstallerTest::standIn);
			assertEquals(0, run("Install", "--appName", "live", "--yes"));
			assertTrue(output.contains("Chrome was not downloaded") && output.contains("Refused"), l + ": " + output);
			assertFalse(Files.exists(chromeRoot().resolve(CFT_VERSION)), l);
			assertEquals(0, run("Uninstall", "--yes"));
		}
		assertEquals(0, zipRequests.get(), "nothing was fetched from the other host");
	}

	@Test
	void newFilesGetTheOwnerOfServerXml() throws Exception
	{
		java.nio.file.attribute.UserPrincipal wowza = () -> "wowza";
		java.nio.file.attribute.GroupPrincipal group = () -> "wowza";
		List<Path> owned = new java.util.concurrent.CopyOnWriteArrayList<>();
		Ownership.testFactory = h -> new Ownership(wowza, group, (p, o, g) -> {
			assertEquals(wowza, o);
			owned.add(p.toAbsolutePath().normalize());
		});
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Path moduleJar;
		try(Stream<Path> s = Files.list(home.resolve("lib")))
		{
			moduleJar = s.filter(p -> p.getFileName().toString().startsWith(Installer.MODULE_JAR_PREFIX)).findFirst().orElseThrow();
		}
		for(Path p: List.of(home.resolve(Installer.MANIFEST), home.resolve(Installer.MANIFEST).getParent(), moduleJar,
				home.resolve("manager/wse-plugins/server/overlay-studio/index.html"), home.resolve("manager/wse-plugins/server/overlay-studio"),
				home.resolve("transcoder/templates/overlaystudio-cpu.xml")))
		{
			assertTrue(owned.contains(p.toAbsolutePath().normalize()), "given the install's owner: " + p);
		}
		try(Stream<Path> s = Files.list(home.resolve("logs")))
		{
			assertTrue(s.allMatch(l -> owned.contains(l.toAbsolutePath().normalize())), "the log too");
		}
		try(Stream<Path> s = Files.list(home.resolve("conf")))
		{
			assertTrue(s.filter(p -> p.getFileName().toString().endsWith(".bak")).allMatch(b -> owned.contains(b.toAbsolutePath().normalize())), "and the backups");
		}
		assertFalse(owned.contains(home.resolve("conf/Server.xml").toAbsolutePath().normalize()), "a file edited in place keeps its owner");
		assertFalse(owned.contains(home.resolve("conf").toAbsolutePath().normalize()), "a folder that was there is not touched");
	}

	@Test
	void aFailureBeforeAnyChangeSaysSo() throws Exception
	{
		String t = text("conf/live/Application.xml");
		XmlScan.Span mods = XmlScan.first(t, "/Root/Application/Modules");
		Files.writeString(home.resolve("conf/live/Application.xml"), t.substring(0, mods.open()) + t.substring(mods.end()));
		Map<String, String> before = tree();
		assertEquals(7, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(output.contains("Nothing was changed"), output);
		assertEquals(before, tree());
	}

	@Test
	void theApplicationListOffersLiveApplicationsWithoutIt() throws Exception
	{
		assertEquals(0, run("--list-apps"));
		assertTrue(output.contains("live ") && output.contains("VIF, overlays"), output);
		assertTrue(output.matches("(?s).*-\\s+vod\\s.*not applicable.*"), output);
		assertTrue(output.contains("live-lowlatency"), output);

		List<SetupWindow.AppRow> rows = SetupWindow.Controller.appRows(Wse.apps(home), List.of());
		SetupWindow.AppRow vod = rows.stream().filter(r -> r.name().equals("vod")).findFirst().orElseThrow();
		assertFalse(vod.enabled());
		SetupWindow.AppRow live = rows.stream().filter(r -> r.name().equals("live")).findFirst().orElseThrow();
		assertTrue(live.enabled() && live.note().contains("Video Intelligence"), live.toString());
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		live = SetupWindow.Controller.appRows(Wse.apps(home), List.of()).stream().filter(r -> r.name().equals("live")).findFirst().orElseThrow();
		assertFalse(live.enabled(), "already on");
		assertTrue(SetupWindow.Controller.friendly(new InstallSession.Problem("x.jar is in use: WSE is running.", 3)).contains("Check again"));
	}

	@Test
	void theBridgeGoesIntoTheManagersTempFolderAndUninstallTakesItOut() throws Exception
	{
		// the Manager already uses part of the package path: that part stays
		Path managersOwn = home.resolve("manager/temp/com/wowza/wms/other/Keep.class");
		Files.createDirectories(managersOwn.getParent());
		Files.writeString(managersOwn, "x");
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(Files.isRegularFile(bridgeDir().resolve("OverlayStudioBridge.class")), output);
		assertTrue(Files.isRegularFile(bridgeDir().resolve("BridgeVersion.class")), output);
		assertTrue(output.contains(ManagerBridge.restartCommand(home.resolve("manager"))), "the CLI says how to restart the Manager: " + output);
		String man = Files.readString(home.resolve(Installer.MANIFEST));
		assertTrue(man.contains("manager/temp/com/wowza/wms/webapp/overlaystudio/OverlayStudioBridge.class") && man.contains("\"createdDirs\""), man);

		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(output.contains("No changes."), "a second run leaves the bridge alone: " + output);

		assertEquals(0, run("Uninstall", "--yes"));
		assertFalse(Files.exists(bridgeDir()), output);
		assertFalse(Files.exists(home.resolve("manager/temp/com/wowza/wms/webapp")), "the folders it created go");
		assertTrue(Files.exists(managersOwn), "the Manager's own files stay");
		assertTrue(output.contains("keeps the bridge loaded until it restarts"), output);
	}

	@Test
	void aManagerTheBridgeWasNotBuiltForGetsNoBridge() throws Exception
	{
		Map<String, Runnable> unfit = new java.util.LinkedHashMap<>();
		unfit.put("Spring 5", () -> uncheck(() -> fakeManager(home, "5.3.39", "10.1.59")));
		unfit.put("Spring 7", () -> uncheck(() -> fakeManager(home, "7.0.1", "10.1.59")));
		unfit.put("Tomcat 9", () -> uncheck(() -> fakeManager(home, "6.2.19", "9.0.98")));
		unfit.put("Tomcat 11", () -> uncheck(() -> fakeManager(home, "6.2.19", "11.0.2")));
		unfit.put("temp folder moved", () -> uncheck(() -> Files.writeString(home.resolve("manager/bin/startmgr.sh"), "CMD=\"launch.Main --tempDirectory=/opt/elsewhere\"\n")));
		unfit.put("no war", () -> uncheck(() -> Files.delete(home.resolve("manager/lib/WMSManager.war"))));
		for(Map.Entry<String, Runnable> u: unfit.entrySet())
		{
			fakeManager(home, "6.2.19", "10.1.59");
			u.getValue().run();
			assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"), u.getKey());
			assertFalse(Files.exists(bridgeDir()), u.getKey() + ": " + output);
			assertTrue(output.contains("bridge was not installed") && output.contains("SSH tunnel"), u.getKey() + ": " + output);
			assertTrue(ConfigEdits.hasModule(text("conf/live/Application.xml")), "the rest installs as usual");
			assertEquals(0, run("Uninstall", "--yes"));
		}

		// a bridge installed before is taken out once the Manager no longer fits it
		fakeManager(home, "6.2.19", "10.1.59");
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(Files.exists(bridgeDir().resolve("OverlayStudioBridge.class")));
		fakeManager(home, "7.0.1", "11.0.2");
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertFalse(Files.exists(bridgeDir().resolve("OverlayStudioBridge.class")), output);
	}

	interface Io
	{
		void run() throws IOException;
	}

	static void uncheck(Io io)
	{
		try
		{
			io.run();
		}
		catch(IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}

	@Test
	void theManagersServiceNameAndRestart() throws Exception
	{
		Path m = home.resolve("manager");
		assertEquals("WowzaStreamingEngineManager4121+15", ManagerBridge.windowsService(m), "as the Manager's service installer builds it");
		List<List<String>> ran = new java.util.ArrayList<>();
		ManagerBridge.testRunner = cmd -> {
			ran.add(cmd);
			return 0;
		};
		assertNull(ManagerBridge.restart(m));
		assertTrue(String.join(" ", ran.get(0)).contains(Preflight.WINDOWS ? "WowzaStreamingEngineManager4121+15" : "systemctl restart WowzaStreamingEngineManager"), ran.toString());
		ManagerBridge.testRunner = cmd -> 1;
		assertTrue(ManagerBridge.restart(m).contains("code 1"));
	}

	// ---- the capture service --------------------------------------------------------------------------------------

	/** No Java class name, such as AccessDeniedException or java.io.IOException, in what a person reads. */
	static void assertNoClassNames(String text)
	{
		assertFalse(java.util.regex.Pattern.compile("\\b[A-Z][A-Za-z]*(Exception|Error)\\b|\\bjava\\.[a-z]+\\.").matcher(text).find(), "a class name in: " + text);
	}

	@Test
	void theCaptureServiceIsInstalledStartedAndTakenOutAgain() throws Exception
	{
		CaptureSetup.Layout l = CaptureSetup.testLayout;
		// what an earlier version left in the program folder: Selenium's jars, which the service no longer has
		Path selenium = l.program().resolve("lib/selenium-api-4.25.0.jar");
		Files.createDirectories(selenium.getParent());
		Files.writeString(selenium, "old library");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("Capture service: running."), output);
		Path jar;
		try(Stream<Path> s = Files.list(l.program()))
		{
			jar = s.filter(p -> p.getFileName().toString().startsWith("wse-overlay-studio-capture-") && p.toString().endsWith(".jar")).findFirst().orElseThrow();
		}
		assertFalse(Files.exists(selenium), "a library the payload no longer carries is removed");
		String config = captureConfig();
		for(String key: List.of("socket=", "chrome=", "workDir=", "maxCaptures=4", "maxBrowserMemoryMB=1024", "logFile="))
		{
			assertTrue(config.contains(key), key + " in " + config);
		}
		assertFalse(config.contains("chromedriver") || config.contains("pagesPerBrowser"), "no chromedriver; pagesPerBrowser left to the service: " + config);
		assertEquals(l.socket().toString(), ConfigEdits.propertyValue(text("conf/Server.xml"), "/Root/Server/Properties", "OverlayStudioCaptureSocket"),
				"the module finds the service through Server.xml");
		if(l.windows())
		{
			assertTrue(sys.ran(l.program().resolve("nssm.exe") + " install " + CaptureSetup.SERVICE), sys.ran.toString());
			assertTrue(sys.ran("sc.exe config " + CaptureSetup.SERVICE + " obj= " + CaptureSetup.WINDOWS_ACCOUNT), sys.ran.toString());
			assertTrue(sys.ran("sc.exe start " + CaptureSetup.SERVICE), sys.ran.toString());
			assertTrue(sys.ran(l.program().resolve("nssm.exe") + " set " + CaptureSetup.SERVICE + " ImagePath " + l.program().resolve("nssm.exe")), "the service runs this folder's nssm");
			assertTrue(Files.isRegularFile(l.program().resolve("runtime/bin/java.exe")), "the service runs on its own copy of the runtime");
			assertTrue(Files.isRegularFile(l.program().resolve("nssm.exe")));
		}
		else
		{
			assertTrue(sys.ran("useradd --system --user-group"), sys.ran.toString());
			assertTrue(sys.ran("systemctl enable " + CaptureSetup.SERVICE) && sys.ran("systemctl restart " + CaptureSetup.SERVICE), sys.ran.toString());
			assertTrue(Files.isRegularFile(l.unit()));
		}
		String man = Files.readString(home.resolve(Installer.MANIFEST));
		assertTrue(man.contains("\"capture\"") && man.contains(CaptureSetup.SERVICE.isEmpty() ? "" : "\"program\""), man);

		int before = sys.ran.size();
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("No changes.") && output.contains("Capture service: running."), output);
		assertFalse(sys.ran.subList(before, sys.ran.size()).stream().anyMatch(c -> String.join(" ", c).contains(" install ") || c.get(0).equals("useradd")),
				"a second run sets nothing up again");

		assertEquals(0, run("Uninstall", "--yes"));
		for(Path p: List.of(l.program(), l.chromeRoot(), l.configDir(), l.work()))
		{
			assertFalse(Files.exists(p), "removed: " + p);
		}
		assertFalse(Files.exists(jar));
		if(l.windows())
		{
			assertTrue(sys.ran(l.program().resolve("nssm.exe") + " remove " + CaptureSetup.SERVICE + " confirm"), sys.ran.toString());
		}
		else
		{
			assertTrue(sys.ran("systemctl disable " + CaptureSetup.SERVICE) && sys.ran("systemctl stop " + CaptureSetup.SERVICE) && sys.ran("userdel " + CaptureSetup.USER), sys.ran.toString());
		}
	}

	CaptureSetup setup(CaptureSetup.Layout l) throws IOException
	{
		return new CaptureSetup(l, sys, home, new Payload(), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
	}

	static final CaptureSetup.Config CONFIG = new CaptureSetup.Config(
			Paths.get("/opt/wse-overlay-studio/chrome-for-testing/1.0/chrome-headless-shell-linux64/chrome-headless-shell"), 4, 1024);

	@Test
	void linuxUnitAppArmorProfileAndSettings() throws Exception
	{
		CaptureSetup.Layout l = CaptureSetup.linux(tmp.resolve("linux"));
		sys.apparmor = true;
		sys.abi4 = true;
		CaptureSetup cs = setup(l);
		cs.install(CONFIG);

		String unit = Files.readString(l.unit());
		for(String line: List.of("User=wse-overlay-studio", "Group=wse-overlay-studio", "RuntimeDirectory=wse-overlay-studio", "RuntimeDirectoryMode=0770", "UMask=0007",
				"Restart=on-failure", "MemoryMax=4864M", "CPUQuota=200%", "TasksMax=1024", "ProtectSystem=strict", "ReadWritePaths=" + l.work() + " " + l.logs(),
				"ProtectHome=true", "PrivateTmp=true", "NoNewPrivileges=true", "WantedBy=multi-user.target", "ExecStart=" + cs.java() + " -jar " + cs.jar() + " --config " + l.config()))
		{
			assertTrue(unit.lines().anyMatch(line::equals), line + " in\n" + unit);
		}
		for(String breaksChrome: List.of("RestrictNamespaces", "PrivateUsers", "MemoryDenyWriteExecute", "SystemCallFilter"))
		{
			assertFalse(unit.contains(breaksChrome), "would break Chrome's sandbox or JIT: " + breaksChrome);
		}
		String profile = Files.readString(l.apparmor());
		assertTrue(profile.contains("abi <abi/4.0>,") && profile.contains("  userns,"), profile);
		assertTrue(profile.contains("profile wse-overlay-studio-chrome " + l.chromeRoot()
				+ "/*/{chrome-headless-shell-linux64/chrome-headless-shell,chrome-linux64/chrome} flags=(unconfined) {"),
				"the profile names the headless shell the installer puts on Linux, and the Chrome of earlier installs, still used when an upgrade's download fails: "
						+ profile);
		assertFalse(profile.contains("chromedriver"), profile);
		Path shell = ChromeDownload.chromeIn(l.chromeRoot().resolve("156.0.8078.73"), "linux64");
		assertEquals(l.chromeRoot().resolve("156.0.8078.73/chrome-headless-shell-linux64/chrome-headless-shell"), shell, "where the download unpacks it");
		assertTrue(sys.ran("apparmor_parser -r " + l.apparmor()), sys.ran.toString());
		assertEquals("rw-r----- root:wse-overlay-studio", sys.modes.get(l.config()), "the settings are readable by the service, not by everyone");
		assertTrue(sys.modes.entrySet().stream().filter(e -> e.getKey().startsWith(l.program())).allMatch(e -> e.getValue().endsWith(" root:root")
				&& !e.getValue().startsWith("rwxrwx") && !e.getValue().matches("^.{4}w.*") && !e.getValue().matches("^.{7}w.*")), "the program is root's, read-only to others: " + sys.modes);
		String props = Files.readString(l.config());
		java.util.Properties read = new java.util.Properties();
		read.load(new java.io.StringReader(props));
		assertEquals(l.socket().toString(), read.getProperty("socket"));
		assertEquals(l.work().toString(), read.getProperty("workDir"));
		assertTrue(sys.ran("useradd --system --user-group --home-dir " + l.work() + " --no-create-home --shell /usr/sbin/nologin wse-overlay-studio"), sys.ran.toString());

		Map<String, Object> rec = Json.parse(Json.write(cs.record())) instanceof Map<?, ?> m ? castMap(m) : null;
		List<String> left = CaptureSetup.uninstall(rec, sys, System.out);
		assertTrue(left.isEmpty(), left.toString());
		assertTrue(sys.ran("systemctl disable WowzaOverlayStudioCapture.service") && sys.ran("systemctl stop WowzaOverlayStudioCapture.service") && sys.ran("apparmor_parser -R " + l.apparmor()) && sys.ran("userdel wse-overlay-studio"),
				sys.ran.toString());
		for(Path p: List.of(l.program(), l.unit(), l.apparmor(), l.config(), l.work()))
		{
			assertFalse(Files.exists(p), "removed: " + p);
		}

		// without AppArmor 4 there is no restriction to lift, and no profile
		sys.ran.clear();
		sys.abi4 = false;
		CaptureSetup older = setup(CaptureSetup.linux(tmp.resolve("linux2")));
		older.install(CONFIG);
		assertFalse(Files.exists(older.l.apparmor()));
		assertFalse(sys.ran("apparmor_parser"));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> castMap(Map<?, ?> m)
	{
		return (Map<String, Object>)m;
	}

	@Test
	void windowsServiceRegistrationAccountAndAccess() throws Exception
	{
		CaptureSetup.Layout l = CaptureSetup.windows(tmp.resolve("win/Program Files/Wowza Overlay Studio"), tmp.resolve("win/ProgramData/WowzaOverlayStudio"), tmp.resolve("runtime"));
		CaptureSetup cs = setup(l);
		cs.install(new CaptureSetup.Config(Paths.get("C:\\Program Files\\Wowza Overlay Studio\\chrome-for-testing\\1.0\\chrome-win64\\chrome.exe"), 2, 512));
		String nssm = l.program().resolve("nssm.exe").toString();
		assertTrue(sys.ran.contains(List.of(nssm, "install", CaptureSetup.SERVICE, cs.java().toString())), sys.ran.toString());
		for(List<String> c: List.of(List.of(nssm, "set", CaptureSetup.SERVICE, "AppParameters", "-Djdk.net.unixdomain.tmpdir=" + l.work() + " -Djava.io.tmpdir=" + l.work()
				+ " -jar " + cs.jar().getFileName() + " --config " + l.config()),
				List.of(nssm, "set", CaptureSetup.SERVICE, "AppDirectory", l.program().toString()),
				List.of(nssm, "set", CaptureSetup.SERVICE, "AppExit", "Default", "Restart"), List.of(nssm, "set", CaptureSetup.SERVICE, "Start", "SERVICE_AUTO_START"),
				List.of(nssm, "set", CaptureSetup.SERVICE, "AppStdout", l.logs().resolve("service.log").toString()),
				List.of("sc.exe", "config", CaptureSetup.SERVICE, "obj=", "NT SERVICE\\WowzaOverlayStudioCapture"),
				List.of("icacls", l.configDir().toString(), "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", "NT SERVICE\\WowzaOverlayStudioCapture:(OI)(CI)M"),
				List.of("icacls", l.work().toString(), "/inheritance:r", "/grant:r", "NT SERVICE\\WowzaOverlayStudioCapture:(OI)(CI)F"),
				List.of("icacls", l.chromeRoot().toString(), "/grant", "NT SERVICE\\WowzaOverlayStudioCapture:(OI)(CI)RX")))
		{
			assertTrue(sys.ran.contains(c), c + " in " + sys.ran);
		}
		assertTrue(sys.ran.indexOf(List.of(nssm, "install", CaptureSetup.SERVICE, cs.java().toString())) < sys.ran.indexOf(CaptureSetup.virtualAccount()),
				"the service, and so its account, exists before the account is used");
		String props = Files.readString(l.config());
		assertTrue(props.contains("chrome=C:\\\\Program Files\\\\Wowza Overlay Studio"), "backslashes doubled for java.util.Properties: " + props);
		java.util.Properties read = new java.util.Properties();
		read.load(new java.io.StringReader(props));
		assertEquals("C:\\Program Files\\Wowza Overlay Studio\\chrome-for-testing\\1.0\\chrome-win64\\chrome.exe", read.getProperty("chrome"));
		assertEquals(l.socket().toString(), read.getProperty("socket"));

		List<String> left = CaptureSetup.uninstall(castMap((Map<?, ?>)Json.parse(Json.write(cs.record()))), sys, System.out);
		assertTrue(left.isEmpty(), left.toString());
		assertTrue(sys.ran.contains(List.of(nssm, "remove", CaptureSetup.SERVICE, "confirm")), sys.ran.toString());
		assertTrue(sys.ran("takeown /F " + l.work()), "an administrator takes over the service's own folder before deleting it");
		assertFalse(Files.exists(l.program()) || Files.exists(l.configDir()));
	}

	@Test
	void aFailedStepPutsBackWhatTheCaptureSetupDidAndSaysSoPlainly() throws Exception
	{
		CaptureSetup.Layout l = CaptureSetup.linux(tmp.resolve("linux"));
		sys.failing = "systemctl daemon-reload";
		CaptureSetup cs = setup(l);
		CaptureSetup.Failed f = org.junit.jupiter.api.Assertions.assertThrows(CaptureSetup.Failed.class, () -> cs.install(CONFIG));
		assertTrue(f.undone, "everything was put back");
		assertTrue(f.getMessage().contains("systemd could not read the new service unit"), f.getMessage());
		assertNoClassNames(f.getMessage());
		assertFalse(Files.exists(l.program()) || Files.exists(l.unit()) || Files.exists(l.config()), "nothing left");
		assertTrue(sys.ran("userdel wse-overlay-studio"), "the user it created is removed again");

		// and in an install, the WSE changes do not happen either
		sys.failing = Preflight.WINDOWS ? "sc.exe config" : "systemctl daemon-reload";
		Map<String, String> before = tree();
		assertEquals(5, run("Install", "--appName", "live", "--yes", "--no-download"));
		assertTrue(output.contains("Everything this run changed was put back."), output);
		assertNoClassNames(output);
		Map<String, String> after = tree();
		after.keySet().removeIf(k -> k.endsWith(".bak"));
		assertEquals(before, after);
	}

	@Test
	void errorsAreSaidInPlainWords() throws Exception
	{
		for(Throwable t: List.of(new java.nio.file.AccessDeniedException("C:\\x\\156.partial", "C:\\x\\156", null), new java.nio.file.NoSuchFileException("/opt/x"),
				new java.net.UnknownHostException("storage.googleapis.com"), new java.net.ConnectException("Connection refused"),
				new java.io.IOException("java.nio.file.FileSystemException: C:\\x: The process cannot access the file"), new RuntimeException(),
				new java.io.UncheckedIOException(new java.nio.file.AccessDeniedException("C:\\y")), new java.net.http.HttpTimeoutException("request timed out")))
		{
			String said = Plain.words(t);
			assertFalse(said.isBlank(), t.toString());
			assertNoClassNames(said);
		}
		assertTrue(Plain.words(new java.nio.file.AccessDeniedException("C:\\x\\156.partial", "C:\\x\\156", null)).contains("antivirus"));
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		Plain.detail = new PrintStream(log, true, StandardCharsets.UTF_8);
		Plain.say(new java.nio.file.NoSuchFileException("/opt/x"));
		assertTrue(log.toString(StandardCharsets.UTF_8).contains("NoSuchFileException"), "the full detail goes to the log");
	}

	// ---- Chrome folders in use ------------------------------------------------------------------------------------

	@Test
	void aCompleteChromeIsNeverDownloadedAgainAndOneWithoutItsMarkerIsAdopted() throws Exception
	{
		// Chrome of the right version, no marker: as an earlier run, or a setup program's removal, left it
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Path chrome = ChromeDownload.chromeIn(dir, ChromeDownload.platform());
		Files.createDirectories(chrome.getParent());
		Files.writeString(chrome, "chrome");
		ChromeFolders.testVersionOf = p -> CFT_VERSION;
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(0, zipRequests.get(), "adopted, not downloaded: " + output);
		assertTrue(Files.isRegularFile(dir.resolve(ChromeDownload.COMPLETE)), "the marker is written");
		assertTrue(captureConfig().contains(chrome.getFileName().toString()) && output.contains("used as it is"), output);

		// the same version again: nothing downloaded, and the service is not stopped for Chrome
		int before = sys.ran.size();
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(0, zipRequests.get());
		assertFalse(output.contains("Stopping the capture service"), output);
		assertTrue(sys.ran.size() >= before);

		// a version that does not match is not adopted
		ChromeFolders.testVersionOf = p -> "155.0.1.1";
		Files.delete(dir.resolve(ChromeDownload.COMPLETE));
		assertNull(ChromeFolders.adopt(chromeRoot(), CFT_VERSION, ChromeDownload.platform(), System.out));
	}

	@Test
	void theDamagedInstallIsRepairedWithoutTouchingTheFolderInUse() throws Exception
	{
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Path chrome = ChromeDownload.chromeIn(dir, ChromeDownload.platform());
		// a marker lost (Chrome's version cannot be read from the stand-in, so it is not adopted), and the running
		// service's Chrome holds its files (and on Windows keeps them from being deleted)
		Files.delete(dir.resolve(ChromeDownload.COMPLETE));
		sys.processes.put(4242L, chrome);
		sys.stopEndsProcesses = false;
		serviceRanDuringDownload = false;
		java.io.RandomAccessFile held = Preflight.WINDOWS ? new java.io.RandomAccessFile(chrome.toFile(), "r") : null;
		try
		{
			assertEquals(0, run("Install", "--appName", "live", "--yes"), output);
		}
		finally
		{
			if(held != null)
			{
				held.close();
			}
		}
		assertEquals(2, zipRequests.get(), "the complete version is fetched again: " + output);
		assertFalse(serviceRanDuringDownload, "the service was stopped before its Chrome folder changed");
		assertTrue(sys.killed.contains(4242L), "a Chrome left running after the stop is ended before anything is touched");
		Path fresh = chromeRoot().resolve(CFT_VERSION + "-2");
		assertTrue(Files.isRegularFile(fresh.resolve(ChromeDownload.COMPLETE)), "unpacked into a fresh sibling, marker last: " + output);
		assertTrue(Files.isRegularFile(ChromeDownload.chromeIn(fresh, ChromeDownload.platform())));
		java.util.Properties p = new java.util.Properties();
		p.load(new java.io.StringReader(captureConfig()));
		assertEquals(ChromeDownload.chromeIn(fresh, ChromeDownload.platform()).toString(), p.getProperty("chrome"), "the service is pointed at it");
		assertNull(p.getProperty("chromedriver"));
		assertTrue(sys.running, "and started again");
		assertTrue(output.contains("Capture service: running."), output);
		assertNoClassNames(output);

		// a later run finds the fresh folder complete and downloads nothing
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertEquals(2, zipRequests.get());
		assertFalse(Files.exists(dir), "the broken folder, no longer used, is cleaned up");
	}

	@Test
	void aFolderInUseIsNeverTheTarget() throws Exception
	{
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("partial"), "x");
		sys.processes.put(7L, dir.resolve("chrome-win64/chrome.exe"));
		Path t = ChromeFolders.target(chromeRoot(), CFT_VERSION, d -> ChromeFolders.inUse(d, null, sys), System.out);
		assertEquals(chromeRoot().resolve(CFT_VERSION + "-2"), t);
		assertTrue(Files.exists(dir.resolve("partial")), "the folder in use is left as it is");
		sys.processes.clear();
		assertEquals(dir, ChromeFolders.target(chromeRoot(), CFT_VERSION, d -> ChromeFolders.inUse(d, null, sys), System.out), "once free, it is cleared and used");
		assertFalse(Files.exists(dir.resolve("partial")));
		assertEquals(CFT_VERSION, ChromeFolders.version(CFT_VERSION + "-2"));
	}

	@Test
	void linuxGetsTheHeadlessShellAndWindowsChromeForTesting() throws Exception
	{
		String base = "http://127.0.0.1:" + cft.getAddress().getPort();
		for(String plat: List.of("linux64", "win64"))
		{
			serve("/list-" + plat + ".json", list(plat, base, CFT_VERSION).getBytes(StandardCharsets.UTF_8));
			ChromeDownload.source = new ChromeDownload.Source(base + "/list-" + plat + ".json", InstallerTest::standIn, InstallerTest::standIn);
			ChromeDownload.Build b = ChromeDownload.stable(ChromeDownload.client(), plat);
			assertEquals(CFT_VERSION, b.version());
			assertEquals(base + (plat.equals("linux64") ? "/shell.zip" : "/chrome.zip"), b.chromeUrl(), "the download chosen for " + plat);
		}
		assertEquals(Paths.get("v", "chrome-headless-shell-linux64", "chrome-headless-shell"), ChromeDownload.chromeIn(Paths.get("v"), "linux64"));
		assertEquals(Paths.get("v", "chrome-win64", "chrome.exe"), ChromeDownload.chromeIn(Paths.get("v"), "win64"));

		// a list without the headless shell is refused, not answered with Chrome for Testing or chromedriver
		serve("/no-shell.json", list("linux64", base, CFT_VERSION).replace("\"chrome-headless-shell\"", "\"something-else\"").getBytes(StandardCharsets.UTF_8));
		ChromeDownload.source = new ChromeDownload.Source(base + "/no-shell.json", InstallerTest::standIn, InstallerTest::standIn);
		IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> ChromeDownload.stable(ChromeDownload.client(), "linux64"));
		assertTrue(e.getMessage().contains("chrome-headless-shell"), e.getMessage());
	}

	@Test
	void aLinuxInstallFromBeforeTheHeadlessShellGetsItBesideTheFolderInUse() throws Exception
	{
		// what an earlier installer left on Linux: Chrome for Testing and chromedriver, complete, and in use
		Path old = chromeRoot().resolve(CFT_VERSION);
		Path oldChrome = old.resolve("chrome-linux64/chrome");
		Files.createDirectories(oldChrome.getParent());
		Files.createDirectories(old.resolve("chromedriver-linux64"));
		Files.writeString(oldChrome, "chrome");
		Files.writeString(old.resolve("chromedriver-linux64/chromedriver"), "driver");
		Files.writeString(old.resolve(ChromeDownload.COMPLETE), CFT_VERSION);
		assertNull(ChromeFolders.complete(chromeRoot(), CFT_VERSION, "linux64"), "complete for Chrome for Testing, not for the headless shell");
		ChromeFolders.testVersionOf = p -> CFT_VERSION;
		assertNull(ChromeFolders.adopt(chromeRoot(), CFT_VERSION, "linux64", System.out), "nothing to adopt: it holds no headless shell");

		serve("/linux-shell.zip", zip("chrome-headless-shell-linux64/chrome-headless-shell", "shell"));
		String base = "http://127.0.0.1:" + cft.getAddress().getPort();
		ChromeDownload.Build b = new ChromeDownload.Build(CFT_VERSION, "linux64", base + "/linux-shell.zip");
		ChromeFolders.Result r = ChromeFolders.ensure(chromeRoot(), b, oldChrome, sys, ChromeDownload.client(), System.out, null, () -> false, () -> {
		});
		assertTrue(r.downloaded());
		assertEquals(List.of("/linux-shell.zip"), zipsFetched);
		assertEquals(chromeRoot().resolve(CFT_VERSION + "-2/chrome-headless-shell-linux64/chrome-headless-shell"), r.pair().chrome(),
				"beside the folder in use, never into it");
		assertTrue(Files.isRegularFile(oldChrome) && Files.isRegularFile(old.resolve(ChromeDownload.COMPLETE)), "the folder in use is left as it is");
		assertEquals(r.pair(), ChromeFolders.complete(chromeRoot(), CFT_VERSION, "linux64"), "and found complete from then on");
	}

	@Test
	void anUpgradeKeepsAChromeForTestingThatIsCompleteAndDropsChromedriverFromTheSettings() throws Exception
	{
		// what an earlier installer left: the version folder with Chrome and chromedriver, and settings naming both
		CaptureSetup.Layout l = CaptureSetup.testLayout;
		String plat = ChromeDownload.platform();
		Path dir = chromeRoot().resolve(CFT_VERSION);
		Path chrome = dir.resolve(plat.startsWith("win") ? "chrome-win64/chrome.exe" : "chrome-linux64/chrome");
		Path driver = dir.resolve("chromedriver-" + plat + "/chromedriver" + (plat.startsWith("win") ? ".exe" : ""));
		Files.createDirectories(chrome.getParent());
		Files.createDirectories(driver.getParent());
		Files.writeString(chrome, "chrome");
		Files.writeString(driver, "driver");
		Files.writeString(dir.resolve(ChromeDownload.COMPLETE), CFT_VERSION);
		Files.createDirectories(l.configDir());
		Files.writeString(l.config(), "chrome=" + chrome.toString().replace("\\", "\\\\") + "\nchromedriver=" + driver.toString().replace("\\", "\\\\")
				+ "\nmaxCaptures=6\npagesPerBrowser=2\nautoUpdate=false\n");

		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		java.util.Properties p = new java.util.Properties();
		p.load(new java.io.StringReader(captureConfig()));
		assertNull(p.getProperty("chromedriver"), "the service needs no chromedriver: " + captureConfig());
		assertEquals("6", p.getProperty("maxCaptures"), "the admin's limits are kept");
		assertEquals("2", p.getProperty("pagesPerBrowser"));
		assertEquals("false", p.getProperty("autoUpdate"), "an admin who turned the daily update off keeps it off");
		if(Preflight.WINDOWS)
		{
			assertEquals(0, zipRequests.get(), "Windows keeps Chrome for Testing: the complete folder is used as it is: " + output);
			assertEquals(chrome.toString(), p.getProperty("chrome"));
		}
		else
		{
			assertEquals(List.of("/shell.zip"), zipsFetched, "Linux moves to the headless shell: " + output);
			assertEquals(ChromeDownload.chromeIn(chromeRoot().resolve(CFT_VERSION + "-2"), plat).toString(), p.getProperty("chrome"));
		}
	}

	@AfterEach
	void realMachine()
	{
		CaptureSetup.Machine.fixed = null;
	}

	@Test
	void pagesAtOnceFollowTheServersCoresAndMemory()
	{
		assertEquals(2, CaptureSetup.recommendedPages(1, 2048), "never below two");
		assertEquals(4, CaptureSetup.recommendedPages(3, 4096));
		assertEquals(12, CaptureSetup.recommendedPages(8, 15781), "the 8-core, 16 GB test server: the cores decide");
		assertEquals(6, CaptureSetup.recommendedPages(8, 3072), "little memory decides");
		assertEquals(25, CaptureSetup.recommendedPages(16, 65536));
		assertEquals(32, CaptureSetup.recommendedPages(64, 262144), "never above the service's own ceiling");
		assertEquals(2, CaptureSetup.recommendedPages(4, 0), "memory not known: the least");
	}

	@Test
	void theUnitLeavesRoomForTheServersSize() throws Exception
	{
		CaptureSetup cs = setup(CaptureSetup.linux(tmp.resolve("linux")));
		String unit = cs.unit(new CaptureSetup.Config(CONFIG.chrome(), 4, 1024, null, null, 12));
		assertTrue(unit.lines().anyMatch("MemoryMax=13056M"::equals), unit);
		assertTrue(unit.lines().anyMatch("CPUQuota=200%"::equals), unit);
		unit = cs.unit(new CaptureSetup.Config(CONFIG.chrome(), 25, 1024, null, null, 12));
		assertTrue(unit.lines().anyMatch("MemoryMax=26368M"::equals), "room is never below the number set: " + unit);
		assertTrue(unit.lines().anyMatch("CPUQuota=400%"::equals), unit);
	}

	@Test
	void pagesOptionIsAutoOrOneToThirtyTwo()
	{
		assertEquals("auto", Main.parse(new String[] { "Install", "--pages", "AUTO" }).pages);
		assertEquals("32", Main.parse(new String[] { "Install", "--pages", "32" }).pages);
		for(String bad: List.of("0", "33", "-1", "many", "2.5"))
		{
			try
			{
				Main.parse(new String[] { "Install", "--pages", bad });
				throw new AssertionError(bad + " accepted");
			}
			catch(IllegalArgumentException e)
			{
				assertTrue(e.getMessage().contains("--pages"), e.getMessage());
			}
		}
	}

	@Test
	void aNewInstallSizesPagesAndAReinstallKeepsThemUnlessAsked() throws Exception
	{
		CaptureSetup.Machine.fixed = new CaptureSetup.Machine(8, 16384);
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		java.util.Properties p = new java.util.Properties();
		p.load(new StringReader(captureConfig()));
		assertEquals("12", p.getProperty("maxCaptures"), "a new install is sized from the server: " + captureConfig());
		assertTrue(output.contains("Pages:        12 at once. This server (8 cores, 16 GB) is sized for 12"), output);

		assertEquals(0, run("Install", "--appName", "live", "--yes", "--pages", "6"));
		p.load(new StringReader(captureConfig()));
		assertEquals("6", p.getProperty("maxCaptures"));
		assertTrue(output.contains("Settings in the page can raise it to 11"), "room is held to three quarters of the memory: " + output);

		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		p.load(new StringReader(captureConfig()));
		assertEquals("6", p.getProperty("maxCaptures"), "a reinstall keeps the number set");

		assertEquals(0, run("Install", "--appName", "live", "--yes", "--pages", "auto"));
		p.load(new StringReader(captureConfig()));
		assertEquals("12", p.getProperty("maxCaptures"), "auto sizes it again");
	}

	@Test
	void theUnitsMemoryCapStaysWithinThreeQuartersOfTheServer() throws Exception
	{
		CaptureSetup.Machine.fixed = new CaptureSetup.Machine(16, 16384);
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--pages", "4", "--dry-run"));
		assertTrue(output.contains("is sized for 25; Settings in the page can raise it to 11"), output);
		assertFalse(output.contains("Note: "), output);
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--pages", "20", "--dry-run"));
		assertTrue(output.contains("Note: 20 pages at up to 1024 MB each may use more than three quarters of this server's memory."), output);
	}

	@Test
	void pagesReplacesTheValueSetInThePagesSettings() throws Exception
	{
		Path limit = home.resolve(Installer.MODULE_DIR).resolve("capture-limit.json");
		Files.createDirectories(limit.getParent());
		Files.writeString(limit, "{\"maxCaptures\":16}");
		assertEquals(0, run("Install", "--appName", "live", "--yes"));
		assertTrue(output.contains("Pages:        16 at once, as set in the page's Settings, which WSE uses over maxCaptures 4; --pages replaces it"), output);
		assertTrue(Files.exists(limit), "without --pages the Settings value stays");
		assertEquals(0, run("Install", "--appName", "live", "--yes", "--pages", "4"));
		assertFalse(Files.exists(limit), "--pages replaces it");
		assertTrue(output.contains("The page's earlier Settings value for pages at once is removed; WSE uses 4 from its next start."), output);
	}

	// ---- the Windows upgrade as it happens: the setup program's removal, then the install --------------------------

	/** The Windows layout under the test system root: in the setup program's folder (1.10.3 and before) or its own. */
	CaptureSetup.Layout windowsLayout(String base)
	{
		return CaptureSetup.windows(tmp.resolve("sys/Program Files/" + base), tmp.resolve("sys/ProgramData/WowzaOverlayStudio"), tmp.resolve("runtime"));
	}

	static final String OLD_BASE = "Wowza Overlay Studio", NEW_BASE = "Wowza Overlay Studio Service";

	/** What the MSI's util:RemoveFolderEx does to the setup program's folder on an upgrade: deletes everything but files in use. */
	static void msiRemoves(Path folder, List<Path> inUse) throws IOException
	{
		try(Stream<Path> s = Files.walk(folder))
		{
			for(Path p: (Iterable<Path>)s.sorted(java.util.Comparator.reverseOrder())::iterator)
			{
				if(inUse.stream().anyMatch(u -> u.startsWith(p)))
				{
					continue;
				}
				Files.delete(p);
			}
		}
	}

	/** An install as 1.10.3 and before left it, with the service running from the setup program's folder. */
	CaptureSetup.Layout olderInstall() throws Exception
	{
		CaptureSetup.Layout old = windowsLayout(OLD_BASE);
		CaptureSetup.testLayout = old;
		assertEquals(0, run("Install", "--appName", "live", "--yes"), output);
		assertEquals(old.program().resolve("nssm.exe").toString(), sys.registration.get("ImagePath"));
		assertTrue(sys.running);
		return old;
	}

	void assertWorksFromTheNewPlace(CaptureSetup.Layout now) throws IOException
	{
		assertEquals(now.program().resolve("nssm.exe").toString(), sys.registration.get("ImagePath"), "the service runs the new folder's nssm");
		assertEquals(now.program().resolve("runtime/bin/java.exe").toString(), sys.registration.get("Application"));
		assertTrue(sys.registration.get("AppParameters").contains("--config " + now.config()), sys.registration.toString());
		assertEquals(CaptureSetup.WINDOWS_ACCOUNT, sys.registration.get("ObjectName"));
		assertEquals("2", sys.registration.get("Start"));
		assertTrue(sys.running, "the service runs: " + output);
		assertTrue(output.contains("Capture service: running."), output);
		Path chrome = ChromeDownload.chromeIn(now.chromeRoot().resolve(CFT_VERSION), ChromeDownload.platform());
		assertTrue(Files.isRegularFile(now.chromeRoot().resolve(CFT_VERSION).resolve(ChromeDownload.COMPLETE)), "Chrome is downloaded to the new place: " + output);
		java.util.Properties p = new java.util.Properties();
		p.load(new java.io.StringReader(Files.readString(now.config())));
		assertEquals(chrome.toString(), p.getProperty("chrome"));
		assertNoClassNames(output);
	}

	@Test
	void theWindowsUpgradeFromAnInstallInsideTheSetupFolderEndsWorking() throws Exception
	{
		CaptureSetup.Layout old = olderInstall();
		assertEquals(1, zipRequests.get());
		// the MSI upgrade removes the old version's folder, all but the files the running service holds: its nssm.exe
		// and the Chrome it runs; capture.properties still points at that Chrome
		Path oldChrome = ChromeDownload.chromeIn(old.chromeRoot().resolve(CFT_VERSION), ChromeDownload.platform());
		msiRemoves(old.base(), List.of(old.program().resolve("nssm.exe"), oldChrome));
		assertTrue(Files.exists(oldChrome) && !Files.exists(old.program().resolve("runtime")));

		CaptureSetup.Layout now = windowsLayout(NEW_BASE);
		CaptureSetup.testLayout = now;
		assertEquals(0, run("Install", "--appName", "live", "--yes"), output);
		assertEquals(2, zipRequests.get(), "the Chrome left behind is incomplete, so it is downloaded again");
		assertWorksFromTheNewPlace(now);
		assertFalse(Files.exists(old.program()), "the leftovers of the old place are cleaned up");
	}

	@Test
	void anInstallWithNothingOnDiskAndTheServicePointingAtAMissingProgramEndsWorking() throws Exception
	{
		CaptureSetup.Layout old = olderInstall();
		CaptureSetup.Layout now = windowsLayout(NEW_BASE);
		// the state 1.10.4's failed run left: nothing of the program or Chrome anywhere, the service stopped and registered
		// to the new folder's nssm.exe, which is not there
		msiRemoves(old.base(), List.of());
		sys.registration.put("ImagePath", now.program().resolve("nssm.exe").toString());
		sys.registration.put("Application", now.program().resolve("runtime/bin/java.exe").toString());
		sys.running = false;

		CaptureSetup.testLayout = now;
		assertEquals(0, run("Install", "--appName", "live", "--yes"), output);
		assertWorksFromTheNewPlace(now);
	}

	@Test
	void aFailedUpdateOfAWorkingServicePutsItsRegistrationBackExactly() throws Exception
	{
		CaptureSetup.Layout old = olderInstall();
		Map<String, String> before = new java.util.TreeMap<>(sys.registration);
		CaptureSetup.Layout now = windowsLayout(NEW_BASE);
		CaptureSetup.testLayout = now;
		sys.failing = "schtasks /Create";
		assertEquals(5, run("Install", "--appName", "live", "--yes"), output);
		assertEquals(before, new java.util.TreeMap<>(sys.registration), "every value of the registration as it was");
		assertTrue(sys.running, "and the service runs on it again");
		assertTrue(output.contains("runs as it did before"), output);
		assertFalse(Files.exists(now.program()), "this run's program folder is gone again");
		assertTrue(Files.isRegularFile(old.program().resolve("nssm.exe")));
		assertNoClassNames(output);
	}

	@Test
	void aFailedRunWhoseEarlierProgramIsGoneLeavesTheServiceDisabledOnFilesThatExist() throws Exception
	{
		CaptureSetup.Layout old = olderInstall();
		msiRemoves(old.base(), List.of());
		CaptureSetup.Layout now = windowsLayout(NEW_BASE);
		CaptureSetup.testLayout = now;
		sys.failing = "schtasks /Create";
		assertEquals(6, run("Install", "--appName", "live", "--yes"), output);
		assertEquals("4", sys.registration.get("Start"), "disabled");
		assertFalse(sys.running);
		assertFalse(FakeSys.missing(sys.registration.get("ImagePath")) || FakeSys.missing(sys.registration.get("Application")),
				"the registration never names a program that is not there: " + sys.registration);
		assertTrue(output.contains("stopped and disabled") && output.contains("Run the setup again"), output);
		assertNoClassNames(output);

		// and the setup run again repairs it
		sys.failing = null;
		assertEquals(0, run("Install", "--appName", "live", "--yes"), output);
		assertWorksFromTheNewPlace(now);
	}
}
