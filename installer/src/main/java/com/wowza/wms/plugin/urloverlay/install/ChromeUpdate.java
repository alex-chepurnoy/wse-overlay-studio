/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import com.wowza.wms.plugin.urloverlay.capture.Wire;

/**
 * Keeps the capture service's Chrome for Testing current: "UpdateChrome", run daily as root or SYSTEM by a systemd timer
 * or a scheduled task that the installer creates. When Google's Stable list names a newer build, it downloads it next to
 * the one in use (same host and redirect checks, same completion marker), points capture.properties at it, restarts the
 * service and checks that it runs and reports that Chrome. Any failure puts capture.properties back, restarts the service
 * on the previous Chrome and exits non-zero. The current and the previous version are kept; older ones are deleted. Each
 * run leaves chrome-update.json next to capture.properties, which the module shows on the Server view.
 * <p>
 * Integrity: Google publishes no checksums for Chrome for Testing, so the files are trusted for coming over HTTPS from
 * googlechromelabs.github.io (the list) and storage.googleapis.com (the zips) only, the rule ChromeDownload enforces.
 */
final class ChromeUpdate
{
	static final String TASK = "WowzaURLOverlayChromeUpdate";
	static final String STATE = "chrome-update.json";
	/** The installer jar, copied into the program folder, so the timer or task always has the updater at hand. */
	static final String JAR = "chrome-update.jar";
	/** How long the service gets to start and report the new Chrome. */
	static final int VERIFY_SECONDS = 90;

	/** Asks the running service for its status; a stand-in in the tests. */
	interface Probe
	{
		Map<String, Object> status(Path socket) throws IOException;
	}

	/** Tests only. */
	static volatile Probe testProbe;
	static volatile java.util.function.Supplier<Instant> testNow;

	private final CaptureSetup.Layout l;
	private final CaptureSetup.Sys sys;
	private final PrintStream out;
	private final HttpClient http;
	private final Probe probe;

	ChromeUpdate(CaptureSetup.Layout l, CaptureSetup.Sys sys, PrintStream out, HttpClient http)
	{
		this.l = l;
		this.sys = sys;
		this.out = out;
		this.http = http;
		this.probe = testProbe != null ? testProbe : ChromeUpdate::askService;
	}

	static Instant now()
	{
		var t = testNow;
		return t != null ? t.get() : Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	/** Compares two Chrome versions part by part as numbers: 156.0.8078.100 is newer than 156.0.8078.73. */
	static int compare(String a, String b)
	{
		String[] x = a.split("\\."), y = b.split("\\.");
		for(int k = 0; k < Math.max(x.length, y.length); k++)
		{
			long p = k < x.length ? number(x[k]) : 0, q = k < y.length ? number(y[k]) : 0;
			if(p != q)
			{
				return Long.compare(p, q);
			}
		}
		return 0;
	}

	private static long number(String s)
	{
		try
		{
			return Long.parseLong(s.trim());
		}
		catch(NumberFormatException e)
		{
			return -1;
		}
	}

	// ---- scheduling, created by the installer ----------------------------------------------------------------------

	static Path serviceUnit(CaptureSetup.Layout l)
	{
		return l.unit().resolveSibling(TASK + ".service");
	}

	static Path timerUnit(CaptureSetup.Layout l)
	{
		return l.unit().resolveSibling(TASK + ".timer");
	}

	static Path taskXml(CaptureSetup.Layout l)
	{
		return l.configDir().resolve("chrome-update-task.xml");
	}

	static String linuxService(Path java, Path jar)
	{
		return String.join("\n",
				"# Written by the URL Overlay installer: installs a newer Chrome for Testing for the capture service, if there is one.",
				"[Unit]",
				"Description=Wowza URL Overlay: newer Chrome for Testing for the capture service",
				"Wants=network-online.target",
				"After=network-online.target",
				"",
				"[Service]",
				"Type=oneshot",
				"ExecStart=" + java + " -jar " + jar + " UpdateChrome",
				"");
	}

	static String linuxTimer()
	{
		return String.join("\n",
				"# Written by the URL Overlay installer: once a day, at a random time, and at boot when a day was missed.",
				"[Unit]",
				"Description=Daily check for a newer Chrome for Testing for Wowza URL Overlay",
				"",
				"[Timer]",
				"OnCalendar=daily",
				"RandomizedDelaySec=4h",
				"Persistent=true",
				"",
				"[Install]",
				"WantedBy=timers.target",
				"");
	}

	/**
	 * The scheduled task, as Task Scheduler XML: daily, with a random delay of up to four hours (schtasks /Create has no
	 * random delay of its own, so the task is created from this file), as SYSTEM, at most an hour per run.
	 */
	static String windowsTask(Path java, Path jar)
	{
		return String.join("\r\n",
				"<?xml version=\"1.0\" encoding=\"UTF-16\"?>",
				"<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">",
				"  <RegistrationInfo>",
				"    <Description>Installs a newer Chrome for Testing for the Wowza URL Overlay capture service, if there is one.</Description>",
				"  </RegistrationInfo>",
				"  <Triggers>",
				"    <CalendarTrigger>",
				"      <StartBoundary>2026-01-01T03:00:00</StartBoundary>",
				"      <Enabled>true</Enabled>",
				"      <RandomDelay>PT4H</RandomDelay>",
				"      <ScheduleByDay>",
				"        <DaysInterval>1</DaysInterval>",
				"      </ScheduleByDay>",
				"    </CalendarTrigger>",
				"  </Triggers>",
				"  <Principals>",
				"    <Principal id=\"Author\">",
				"      <UserId>S-1-5-18</UserId>",
				"      <RunLevel>HighestAvailable</RunLevel>",
				"    </Principal>",
				"  </Principals>",
				"  <Settings>",
				"    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>",
				"    <StartWhenAvailable>true</StartWhenAvailable>",
				"    <RunOnlyIfNetworkAvailable>true</RunOnlyIfNetworkAvailable>",
				"    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>",
				"    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>",
				"    <ExecutionTimeLimit>PT1H</ExecutionTimeLimit>",
				"    <Enabled>true</Enabled>",
				"  </Settings>",
				"  <Actions Context=\"Author\">",
				"    <Exec>",
				"      <Command>" + xml(java.toString()) + "</Command>",
				"      <Arguments>-jar \"" + xml(jar.toString()) + "\" UpdateChrome</Arguments>",
				"    </Exec>",
				"  </Actions>",
				"</Task>",
				"");
	}

	/** Task Scheduler reads the XML file as UTF-16, the encoding its own exports use. */
	static byte[] windowsTaskBytes(Path java, Path jar)
	{
		byte[] body = windowsTask(java, jar).getBytes(StandardCharsets.UTF_16LE);
		byte[] out = new byte[body.length + 2];
		out[0] = (byte)0xFF;
		out[1] = (byte)0xFE;
		System.arraycopy(body, 0, out, 2, body.length);
		return out;
	}

	static List<String> createTask(Path xml)
	{
		return List.of("schtasks", "/Create", "/TN", TASK, "/XML", xml.toString(), "/F");
	}

	static List<String> deleteTask()
	{
		return List.of("schtasks", "/Delete", "/TN", TASK, "/F");
	}

	private static String xml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	// ---- the state file ---------------------------------------------------------------------------------------------

	Map<String, Object> readState()
	{
		return readState(l.configDir().resolve(STATE));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> readState(Path f)
	{
		try
		{
			return Files.isRegularFile(f) ? new LinkedHashMap<>((Map<String, Object>)Json.parse(Files.readString(f, StandardCharsets.UTF_8))) : new LinkedHashMap<>();
		}
		catch(IOException | RuntimeException e)
		{
			return new LinkedHashMap<>();
		}
	}

	/**
	 * Writes the state: when this ran, what came of it, the version in use and the one before, the newest Stable seen,
	 * and when the version in use was installed (its age on the Server view).
	 */
	void writeState(String result, String message, String version, String previous, String latest, Instant installed)
	{
		Map<String, Object> s = readState();
		s.put("checked", now().toString());
		s.put("result", result);
		s.put("message", message);
		if(version != null)
		{
			s.put("version", version);
		}
		if(previous != null)
		{
			s.put("previous", previous);
		}
		if(latest != null)
		{
			s.put("latest", latest);
		}
		if(installed != null)
		{
			s.put("installed", installed.toString());
		}
		Path f = l.configDir().resolve(STATE);
		try
		{
			Path tmp = f.resolveSibling(STATE + ".tmp");
			Files.writeString(tmp, Json.write(s), StandardCharsets.UTF_8);
			Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			sys.posix(f, "rw-r--r--", "root", "root");
		}
		catch(IOException e)
		{
			out.println("The update state could not be written: " + Plain.say(e));
		}
	}

	/** The installer's first Chrome: recorded as installed now, so its age shows from the start. */
	static void recordInstalled(CaptureSetup.Layout l, CaptureSetup.Sys sys, String version)
	{
		ChromeUpdate u = new ChromeUpdate(l, sys, new PrintStream(OutputStream.nullOutputStream()), null);
		Map<String, Object> s = u.readState();
		if(version.equals(s.get("version")) && s.get("installed") != null)
		{
			return;
		}
		u.writeState("installed", null, version, null, version, now());
	}

	// ---- the update -------------------------------------------------------------------------------------------------

	/** Runs one check and, when there is a newer Stable, one update. @return the exit code: 0 done or nothing to do, 3 cannot run here, 5 failed and put back, 6 failed and not fully put back */
	int run()
	{
		if(!Files.isRegularFile(l.config()))
		{
			out.println("The URL Overlay capture service is not installed here (no " + l.config() + ").");
			return 3;
		}
		if(!Preflight.notWritable(List.of(l.configDir(), l.chromeRoot())).isEmpty())
		{
			out.println("Run this as root (Linux) or as an administrator or SYSTEM (Windows): it changes " + l.chromeRoot() + " and " + l.config() + ".");
			return 3;
		}
		Properties p = new Properties();
		byte[] config;
		try
		{
			config = Files.readAllBytes(l.config());
			p.load(new StringReader(new String(config, StandardCharsets.UTF_8)));
		}
		catch(IOException e)
		{
			out.println(l.config() + " could not be read: " + Plain.say(e));
			return 3;
		}
		if("false".equalsIgnoreCase(p.getProperty("autoUpdate", "").trim()))
		{
			out.println("Chrome updates are off: capture.properties says autoUpdate=false. Nothing was changed.");
			writeState("off", "Updates are off (autoUpdate=false in capture.properties).", null, null, null, null);
			return 0;
		}
		Path chrome = Paths.get(p.getProperty("chrome", "").trim());
		Path root = l.chromeRoot().toAbsolutePath().normalize();
		Path abs = chrome.toAbsolutePath().normalize();
		if(p.getProperty("chrome", "").isBlank() || !abs.startsWith(root) || abs.equals(root))
		{
			out.println("The capture service uses a Chrome chosen by hand (" + chrome + "); it is not updated. Nothing was changed.");
			writeState("own", "The capture service uses a Chrome chosen by hand; it is not updated.", null, null, null, null);
			return 0;
		}
		// the folder in use: a version, or a sibling of one (156.0.8078.73-2)
		String currentFolder = root.relativize(abs).getName(0).toString();
		String current = ChromeFolders.version(currentFolder);
		String platform = ChromeDownload.platform();
		if(platform == null)
		{
			return failed("Chrome for Testing has no build for this system. Nothing was changed.", current, null);
		}
		ChromeDownload.Build b;
		try
		{
			b = ChromeDownload.stable(http, platform);
		}
		catch(IOException | RuntimeException e)
		{
			return failed("Google's list of Chrome versions could not be read: " + Plain.say(e) + ". Nothing was changed.", current, null);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return failed("The check was interrupted. Nothing was changed.", current, null);
		}
		if(compare(b.version(), current) <= 0)
		{
			out.println("Chrome " + current + " is the newest Stable. Nothing was changed.");
			writeState("current", null, current, null, b.version(), null);
			return 0;
		}
		out.println("Chrome " + current + " is in use; Stable is " + b.version() + ". Getting it...");
		ChromeDownload.Pair pair;
		boolean fetched;
		try
		{
			// never into the folder in use: the new version goes into its own folder, or a fresh sibling of it
			ChromeFolders.Result r = ChromeFolders.ensure(l.chromeRoot(), b, chrome, sys, http, out, null, () -> false, () -> {
			});
			pair = r.pair();
			fetched = r.downloaded();
		}
		catch(IOException | RuntimeException e)
		{
			return failed("Chrome " + b.version() + " could not be downloaded: " + Plain.say(e) + ". Chrome " + current + " stays in use.", current, b.version());
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return failed("The download was interrupted. Chrome " + current + " stays in use.", current, b.version());
		}
		String problem = switchTo(pair, config, p);
		if(problem != null)
		{
			String back = putBack(config);
			if(fetched)
			{
				quietDelete(pair.dir());
			}
			String msg = "Chrome " + b.version() + " was not taken into use: " + problem + ". "
					+ (back == null ? "Chrome " + current + " was put back and the service runs on it." : "Putting Chrome " + current + " back also failed: " + back + ".");
			out.println(msg);
			writeState("failed", msg, current, null, b.version(), null);
			return back == null ? 5 : 6;
		}
		List<String> removed = keepTwo(pair.dir().getFileName().toString(), currentFolder);
		String msg = "Chrome " + b.version() + " is in use; " + current + " is kept as the previous version" + (removed.isEmpty() ? "." : ", and " + String.join(", ", removed) + " removed.");
		out.println(msg);
		writeState("updated", null, b.version(), current, b.version(), now());
		return 0;
	}

	private int failed(String msg, String current, String latest)
	{
		out.println(msg);
		writeState("failed", msg, current, null, latest, null);
		return 5;
	}

	/** Gives the new Chrome the installer's ownership and access, points the settings at it, restarts and checks the service. @return null, or what went wrong */
	private String switchTo(ChromeDownload.Pair pair, byte[] config, Properties p)
	{
		try
		{
			if(l.windows())
			{
				CaptureSetup.Result r = sys.run(List.of("icacls", l.chromeRoot().toString(), "/grant", CaptureSetup.WINDOWS_ACCOUNT + ":(OI)(CI)RX"));
				if(r.code() != 0)
				{
					return "the service account could not be given access to the new Chrome";
				}
			}
			else
			{
				try(var s = Files.walk(pair.dir()))
				{
					for(Path f: (Iterable<Path>)s::iterator)
					{
						sys.posix(f, Files.isDirectory(f) || Files.isExecutable(f) ? "rwxr-xr-x" : "rw-r--r--", "root", "root");
					}
				}
			}
			Files.write(l.config(), withChrome(new String(config, StandardCharsets.UTF_8), pair.chrome(), pair.driver()).getBytes(StandardCharsets.UTF_8));
		}
		catch(IOException | RuntimeException e)
		{
			return Plain.say(e);
		}
		restart();
		return verify(pair.chrome());
	}

	/** The settings text with chrome and chromedriver replaced, every other line as it was. */
	static String withChrome(String text, Path chrome, Path driver)
	{
		StringBuilder b = new StringBuilder();
		boolean c = false, d = false;
		for(String line: text.split("\n", -1))
		{
			String t = line.stripLeading();
			if(t.startsWith("chrome=") || t.startsWith("chrome ="))
			{
				line = "chrome=" + escape(chrome);
				c = true;
			}
			else if(t.startsWith("chromedriver=") || t.startsWith("chromedriver ="))
			{
				line = "chromedriver=" + escape(driver);
				d = true;
			}
			b.append(line).append('\n');
		}
		String s = b.substring(0, b.length() - 1);
		if(!c)
		{
			s += (s.endsWith("\n") ? "" : "\n") + "chrome=" + escape(chrome) + "\n";
		}
		if(!d)
		{
			s += (s.endsWith("\n") ? "" : "\n") + "chromedriver=" + escape(driver) + "\n";
		}
		return s;
	}

	private static String escape(Path p)
	{
		return p.toString().replace("\\", "\\\\");
	}

	private void restart()
	{
		// stopped, and its Chrome processes gone, before it starts on the other folder
		CaptureSetup.stopAndWait(l, sys);
		CaptureSetup.startAgain(l.windows(), sys);
	}

	/** Waits for the service to run and to report this Chrome as found. @return null, or what it reported instead */
	private String verify(Path chrome)
	{
		String last = "the capture service did not start";
		for(int k = 0; k < VERIFY_SECONDS; k++)
		{
			if(CaptureSetup.running(l, sys))
			{
				try
				{
					Map<String, Object> st = probe.status(l.socket());
					Object c = st.get("chrome");
					if(c instanceof Map<?, ?> m)
					{
						boolean found = Boolean.TRUE.equals(m.get("found"));
						String path = String.valueOf(m.get("path"));
						if(found && Paths.get(path).toAbsolutePath().normalize().equals(chrome.toAbsolutePath().normalize()))
						{
							return null;
						}
						last = found ? "the capture service reports another Chrome (" + path + ")" : "the capture service does not find the new Chrome";
					}
					else
					{
						last = "the capture service does not report its Chrome yet";
					}
				}
				catch(IOException | RuntimeException e)
				{
					last = "the capture service does not answer (" + Plain.words(e) + ")";
				}
			}
			else
			{
				last = "the capture service is not running";
			}
			pause();
		}
		return last;
	}

	private String putBack(byte[] config)
	{
		try
		{
			Files.write(l.config(), config);
		}
		catch(IOException e)
		{
			return "capture.properties could not be written back (" + Plain.say(e) + ")";
		}
		restart();
		for(int k = 0; k < VERIFY_SECONDS; k++)
		{
			if(CaptureSetup.running(l, sys))
			{
				return null;
			}
			pause();
		}
		return "the capture service did not start again";
	}

	/** Deletes every version folder but these two, and any folder left incomplete. @return the versions removed */
	private List<String> keepTwo(String now, String previous)
	{
		List<String> removed = new ArrayList<>();
		try(var s = Files.list(l.chromeRoot()))
		{
			for(Path d: (Iterable<Path>)s::iterator)
			{
				String n = d.getFileName().toString();
				if(Files.isDirectory(d) && !n.equals(now) && !n.equals(previous) && sys.processesUnder(d).isEmpty())
				{
					if(quietDelete(d))
					{
						removed.add(n);
					}
				}
			}
		}
		catch(IOException e)
		{
			out.println("Older Chrome versions could not be listed: " + Plain.say(e));
		}
		return removed;
	}

	private boolean quietDelete(Path d)
	{
		try
		{
			ChromeDownload.deleteTree(d);
			return true;
		}
		catch(IOException e)
		{
			out.println(d + " could not be removed: " + Plain.say(e));
			return false;
		}
	}

	private static void pause()
	{
		if(testProbe != null)
		{
			return;
		}
		try
		{
			Thread.sleep(1000);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/** The service's status over its socket: hello, then status, as the module asks. */
	static Map<String, Object> askService(Path socket) throws IOException
	{
		try(SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX))
		{
			c.connect(UnixDomainSocketAddress.of(socket));
			OutputStream o = Channels.newOutputStream(c);
			InputStream in = Channels.newInputStream(c);
			Map<String, Object> hello = Wire.header(Wire.HELLO);
			hello.put("protocol", Wire.PROTOCOL);
			hello.put("version", "chrome-update");
			Wire.write(o, hello, null);
			Wire.Message h = Wire.read(in);
			if(h == null || !h.ok())
			{
				throw new IOException(h == null ? "the service closed the connection" : String.valueOf(h.header().get("error")));
			}
			Wire.write(o, Wire.header(Wire.STATUS), null);
			Wire.Message s = Wire.read(in);
			if(s == null)
			{
				throw new IOException("the service closed the connection");
			}
			return s.header();
		}
	}
}
