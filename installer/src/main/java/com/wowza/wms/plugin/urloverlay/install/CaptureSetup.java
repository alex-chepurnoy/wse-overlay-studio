/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The separate capture service (docs/DESIGN.md, The capture service): its program, Chrome, configuration, account and
 * service registration, on Linux (systemd) and Windows (nssm). One install is a list of steps, each with its undo, so a
 * failure, or a later failure of the WSE changes, puts back what this class did. Every system command goes through
 * {@link Sys}, so the tests check the plan and the command lines without a real system.
 */
final class CaptureSetup
{
	static final String SERVICE = "WowzaURLOverlayCapture";
	static final String USER = "wse-url-overlay";
	static final String WINDOWS_ACCOUNT = "NT SERVICE\\" + SERVICE;
	static final String PAYLOAD = "capture/";
	/** SYSTEM and Administrators by SID, so the commands work in every Windows language. */
	static final String SYSTEM_SID = "*S-1-5-18", ADMINISTRATORS_SID = "*S-1-5-32-544";

	/** Where everything goes; the table in docs/DESIGN.md, The capture service. */
	record Layout(boolean windows, Path program, Path chromeRoot, Path configDir, Path config, Path work, Path socket, Path logs, Path unit, Path apparmor,
			Path runtimeSource)
	{
		/** The folder the program, Chrome and the runtime sit in. */
		Path base()
		{
			return program.getParent();
		}
	}

	/** Tests only: the layout under a temporary folder. */
	static volatile Layout testLayout;
	/** Tests only: records commands instead of running them. */
	static volatile Sys testSys;

	static Layout layout()
	{
		Layout t = testLayout;
		if(t != null)
		{
			return t;
		}
		if(!Preflight.WINDOWS)
		{
			return linux(Paths.get("/"));
		}
		// Not the setup program's own folder: jpackage's MSI removes that folder recursively (util:RemoveFolderEx) when the
		// program is removed, and an upgrade removes the old version first, which deleted the service's Chrome and program
		// files that were not locked at the time. This folder belongs to the installer alone.
		Path base = Paths.get(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Wowza URL Overlay Service");
		Path data = Paths.get(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "WowzaURLOverlay");
		return windows(base, data, Paths.get(System.getProperty("java.home")));
	}

	static Layout linux(Path root)
	{
		Path opt = root.resolve("opt/wse-url-overlay");
		return new Layout(false, opt.resolve("capture"), opt.resolve("chrome-for-testing"), root.resolve("etc/wse-url-overlay"),
				root.resolve("etc/wse-url-overlay/capture.properties"), root.resolve("var/lib/wse-url-overlay"), root.resolve("run/wse-url-overlay/capture.sock"),
				root.resolve("var/log/wse-url-overlay"), root.resolve("etc/systemd/system/" + SERVICE + ".service"), root.resolve("etc/apparmor.d/wse-url-overlay-chrome"), null);
	}

	static Layout windows(Path base, Path data, Path runtime)
	{
		return new Layout(true, base.resolve("capture"), base.resolve("chrome-for-testing"), data, data.resolve("capture.properties"), data.resolve("work"),
				data.resolve("capture.sock"), data.resolve("logs"), null, null, runtime);
	}

	/** A command's exit code and output. */
	record Result(int code, String output)
	{
	}

	/** The system: commands, ownership and modes, AppArmor. */
	interface Sys
	{
		Result run(List<String> cmd);

		/** Linux: mode as rwxr-xr-x, owner and group by name. Does nothing on Windows. */
		void posix(Path p, String mode, String owner, String group) throws IOException;

		/** Linux: AppArmor is enabled and apparmor_parser is there. */
		boolean apparmor();

		/** Linux: the AppArmor policy knows abi 4.0, which the userns rule needs (AppArmor 4, Ubuntu 23.10 and later). */
		boolean apparmorAbi4();

		/** The processes whose program file lies under dir: a Chrome of the capture service, or an earlier version's chromedriver. */
		default List<Long> processesUnder(Path dir)
		{
			Path d = dir.toAbsolutePath().normalize();
			return ProcessHandle.allProcesses().filter(h -> h.info().command().map(c -> {
				try
				{
					return Paths.get(c).toAbsolutePath().normalize().startsWith(d);
				}
				catch(RuntimeException e)
				{
					return false;
				}
			}).orElse(false)).map(ProcessHandle::pid).toList();
		}

		default void kill(long pid)
		{
			ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
		}
	}

	static Sys sys()
	{
		Sys t = testSys;
		return t != null ? t : REAL;
	}

	private static final Sys REAL = new Sys()
	{
		@Override
		public Result run(List<String> cmd)
		{
			try
			{
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				ByteArrayOutputStream b = new ByteArrayOutputStream();
				try(InputStream in = p.getInputStream())
				{
					in.transferTo(b);
				}
				if(!p.waitFor(120, TimeUnit.SECONDS))
				{
					p.destroyForcibly();
					return new Result(-1, "timed out");
				}
				return new Result(p.exitValue(), b.toString(Preflight.WINDOWS ? java.nio.charset.Charset.defaultCharset() : StandardCharsets.UTF_8));
			}
			catch(IOException e)
			{
				return new Result(-1, Plain.say(e));
			}
			catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return new Result(-1, "interrupted");
			}
		}

		@Override
		public void posix(Path p, String mode, String owner, String group) throws IOException
		{
			PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if(v == null)
			{
				return;
			}
			UserPrincipalLookupService look = p.getFileSystem().getUserPrincipalLookupService();
			UserPrincipal u = look.lookupPrincipalByName(owner);
			GroupPrincipal g = look.lookupPrincipalByGroupName(group);
			v.setOwner(u);
			v.setGroup(g);
			v.setPermissions(PosixFilePermissions.fromString(mode));
		}

		@Override
		public boolean apparmor()
		{
			return Files.isDirectory(Paths.get("/sys/kernel/security/apparmor"))
					&& (Files.isExecutable(Paths.get("/sbin/apparmor_parser")) || Files.isExecutable(Paths.get("/usr/sbin/apparmor_parser")));
		}

		@Override
		public boolean apparmorAbi4()
		{
			return Files.exists(Paths.get("/etc/apparmor.d/abi/4.0"));
		}
	};

	/**
	 * What the service is told: Chrome and the limits. pagesPerBrowser and autoUpdate are null unless capture.properties
	 * already sets them, and are then kept: the service has its own default, and the daily update is on unless an admin
	 * turned it off. room is the most pages at once the systemd unit leaves memory and CPU for, at least maxCaptures, so
	 * Settings in the page can raise the number up to it without running the installer again.
	 */
	record Config(Path chrome, int maxCaptures, int maxBrowserMemoryMB, Integer pagesPerBrowser, String autoUpdate, int room)
	{
		Config(Path chrome, int maxCaptures, int maxBrowserMemoryMB)
		{
			this(chrome, maxCaptures, maxBrowserMemoryMB, null, null);
		}

		Config(Path chrome, int maxCaptures, int maxBrowserMemoryMB, Integer pagesPerBrowser)
		{
			this(chrome, maxCaptures, maxBrowserMemoryMB, pagesPerBrowser, null);
		}

		Config(Path chrome, int maxCaptures, int maxBrowserMemoryMB, Integer pagesPerBrowser, String autoUpdate)
		{
			this(chrome, maxCaptures, maxBrowserMemoryMB, pagesPerBrowser, autoUpdate, maxCaptures);
		}

		Config
		{
			room = Math.max(room, maxCaptures);
		}
	}

	/** The most pages the capture service takes at once (CaptureService.MAX_CAPTURES_CEILING). */
	static final int MAX_PAGES = 32;
	/** What the unit leaves for the service itself on top of its pages (CaptureService.SERVICE_OWN_MB). */
	static final int SERVICE_OWN_MB = 768;

	/**
	 * Pages at once for a server of this size, for a new install and --pages auto. WSE's own transcoding comes first, so
	 * the capture service is given a quarter of the cores at 16% of a core a page and a quarter of the memory at 128 MB
	 * a page; the smaller wins, from 2 to MAX_PAGES. Measured with 1.15.0 on an 8-core server, 12 pages at 1920 x 1080
	 * (docs/USAGE.md, "Pages at once"): a page that changes once a second costs 4.5% of a core, one that changes every
	 * 0.1 s at the Fast rate 40 to 46%, and 105 to 124 MB each. So the count suits overlays that change now and then;
	 * pages that animate all the time are held back by the unit's CPUQuota and get fewer frames.
	 */
	static int recommendedPages(int cores, long memoryMb)
	{
		long byCpu = (long)Math.max(1, cores) * 25 / 16;
		long byMemory = Math.max(0, memoryMb) / 4 / 128;
		return (int)Math.max(2, Math.min(MAX_PAGES, Math.min(byCpu, byMemory)));
	}

	/** This server's cores and memory, as Java sees them; memory 0 when it cannot be read. */
	record Machine(int cores, long memoryMb)
	{
		/** Set by tests, so what they expect does not depend on the machine they run on. */
		static volatile Machine fixed;

		static Machine here()
		{
			if(fixed != null)
			{
				return fixed;
			}
			long mb = 0;
			if(java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
			{
				mb = os.getTotalMemorySize() / (1024 * 1024);
			}
			return new Machine(Runtime.getRuntime().availableProcessors(), mb);
		}

		int pages()
		{
			return recommendedPages(cores, memoryMb);
		}
	}

	/** The capture service could not be set up; the message is plain words. */
	static final class Failed extends Exception
	{
		final boolean undone;

		Failed(String message, boolean undone)
		{
			super(message);
			this.undone = undone;
		}
	}

	private interface Undo
	{
		void run() throws IOException;
	}

	final Layout l;
	final Sys sys;
	final Path home;
	final Payload payload;
	final PrintStream out;
	private final List<Undo> undo = new ArrayList<>();
	private final Map<String, Object> record = new LinkedHashMap<>();

	CaptureSetup(Layout l, Sys sys, Path home, Payload payload, PrintStream out)
	{
		this.l = l;
		this.sys = sys;
		this.home = home;
		this.payload = payload;
		this.out = out;
	}

	// ---- what gets written ------------------------------------------------------------------------------------------

	/** The service's jar, as the payload carries it: capture/wse-url-overlay-capture-&lt;version&gt;.jar. */
	Path jar()
	{
		for(String p: payload.under(PAYLOAD))
		{
			String rest = p.substring(PAYLOAD.length());
			if(!rest.contains("/") && rest.endsWith(".jar"))
			{
				return l.program.resolve(rest);
			}
		}
		throw new IllegalStateException("This installer carries no capture service");
	}

	/** Linux: WSE's own Java; Windows: the runtime copied next to the program. */
	Path java()
	{
		if(l.windows)
		{
			return l.program.resolve("runtime").resolve("bin").resolve("java.exe");
		}
		Path wse = home.resolve("java").resolve("bin").resolve("java");
		return Files.isRegularFile(wse) ? wse : Paths.get(System.getProperty("java.home"), "bin", "java");
	}

	String properties(Config c)
	{
		StringBuilder b = new StringBuilder("# Written by the URL Overlay installer and read by the capture service when it starts.\n");
		Map<String, String> v = new LinkedHashMap<>();
		v.put("socket", l.socket.toString());
		v.put("chrome", c.chrome() == null ? "" : c.chrome().toString());
		v.put("workDir", l.work.toString());
		v.put("maxCaptures", String.valueOf(c.maxCaptures()));
		v.put("maxBrowserMemoryMB", String.valueOf(c.maxBrowserMemoryMB()));
		if(c.pagesPerBrowser() != null)
		{
			v.put("pagesPerBrowser", String.valueOf(c.pagesPerBrowser()));
		}
		v.put("logFile", l.logs.resolve("capture.log").toString());
		if(c.autoUpdate() != null)
		{
			v.put("autoUpdate", c.autoUpdate());
		}
		// java.util.Properties reads a backslash as an escape, so each one in a Windows path is doubled
		v.forEach((k, x) -> b.append(k).append('=').append(x.replace("\\", "\\\\")).append('\n'));
		return b.toString();
	}

	/**
	 * The systemd unit. The hardening leaves Chrome's own sandbox working: its namespace sandbox needs no setuid helper,
	 * so NoNewPrivileges does not stop it (Chrome sets no_new_privs itself for its seccomp filter); /dev/shm, which Chrome
	 * needs, stays writable under ProtectSystem=strict; and the settings that would break it (RestrictNamespaces,
	 * PrivateUsers, MemoryDenyWriteExecute, which breaks V8's JIT, and SystemCallFilter) are left out on purpose.
	 */
	String unit(Config c)
	{
		long memory = (long)c.maxBrowserMemoryMB() * Math.max(1, c.room()) + SERVICE_OWN_MB;
		// 200% as before, or 16% of a core for each page of room at the Fast rate, in whole cores
		int cpu = Math.max(200, (c.room() * 16 + 99) / 100 * 100);
		return String.join("\n",
				"# Written by the URL Overlay installer. URL Overlay's capture service: draws web pages with Chrome for WSE.",
				"[Unit]",
				"Description=Wowza URL Overlay capture service",
				"After=network.target",
				"",
				"[Service]",
				"Type=simple",
				"User=" + USER,
				"Group=" + USER,
				"ExecStart=" + java() + " -jar " + jar() + " --config " + l.config,
				"WorkingDirectory=" + l.work,
				"Environment=HOME=" + l.work,
				"StateDirectory=wse-url-overlay",
				"StateDirectoryMode=0750",
				"LogsDirectory=wse-url-overlay",
				"LogsDirectoryMode=0750",
				"RuntimeDirectory=wse-url-overlay",
				"RuntimeDirectoryMode=0770",
				"# the socket is group read-write, so WSE's user, a member of the group, can connect",
				"UMask=0007",
				"Restart=on-failure",
				"RestartSec=5",
				"MemoryMax=" + memory + "M",
				"CPUQuota=" + cpu + "%",
				"TasksMax=1024",
				"ProtectSystem=strict",
				"ReadWritePaths=" + l.work + " " + l.logs,
				"ProtectHome=true",
				"PrivateTmp=true",
				"NoNewPrivileges=true",
				"",
				"[Install]",
				"WantedBy=multi-user.target",
				"");
	}

	/**
	 * Lets chrome-headless-shell make the user namespaces its sandbox needs where Ubuntu restricts them
	 * (kernel.apparmor_restrict_unprivileged_userns = 1), in the shape of Ubuntu's own profile for Chrome. The headless
	 * shell is one program: its zygote and renderers are the same file started again, so they run under this profile too.
	 * Written again on every install, so an install from before the headless shell, whose profile named
	 * chrome-linux64/chrome, gets this one. That Chrome stays named too: when the headless shell's download fails during
	 * an upgrade, capture.properties still points at it until the daily update moves it, and its sandbox must still start.
	 */
	String apparmorProfile()
	{
		return String.join("\n",
				"# Written by the URL Overlay installer: lets the capture service's Chrome create the user namespaces its sandbox needs.",
				"abi <abi/4.0>,",
				"include <tunables/global>",
				"",
				"profile wse-url-overlay-chrome " + l.chromeRoot + "/*/{chrome-headless-shell-linux64/chrome-headless-shell,chrome-linux64/chrome} flags=(unconfined) {",
				"  userns,",
				"",
				"  include if exists <local/wse-url-overlay-chrome>",
				"}",
				"");
	}

	/** A path for nssm's AppParameters, which are passed without quotes. */
	static String noSpaces(Path p)
	{
		if(p.toString().indexOf(' ') >= 0)
		{
			throw new IllegalStateException("The capture service cannot be set up with a space in " + p + ".");
		}
		return p.toString();
	}

	/** nssm's settings for the service, one command each, after "nssm install SERVICE &lt;java&gt;". */
	List<List<String>> nssmSettings(Path nssm)
	{
		String n = nssm.toString();
		Path log = l.logs.resolve("service.log");
		List<List<String>> c = new ArrayList<>();
		// the service runs this program folder's nssm.exe: set again on every install, since an earlier version
		// registered the service with an nssm.exe under the setup program's folder
		c.add(List.of(n, "set", SERVICE, "ImagePath", n));
		c.add(List.of(n, "set", SERVICE, "Application", java().toString()));
		// Java's Unix domain sockets and other temporary files go in the service's own work folder, since
		// the default temporary folder of a virtual account fails for them
		// no quotes: Java does not escape quotes inside an argument on Windows, so nssm received the value with them stripped
		// and java read "C:\Program" as the jar. The jar is named relative to AppDirectory; the ProgramData paths have no spaces.
		c.add(List.of(n, "set", SERVICE, "AppParameters", "-Djdk.net.unixdomain.tmpdir=" + noSpaces(l.work) + " -Djava.io.tmpdir=" + noSpaces(l.work)
				+ " -jar " + noSpaces(jar().getFileName()) + " --config " + noSpaces(l.config)));
		c.add(List.of(n, "set", SERVICE, "AppDirectory", l.program.toString()));
		c.add(List.of(n, "set", SERVICE, "DisplayName", "Wowza URL Overlay capture"));
		c.add(List.of(n, "set", SERVICE, "Description", "Draws web pages with Chrome for URL Overlay in Wowza Streaming Engine."));
		c.add(List.of(n, "set", SERVICE, "Start", "SERVICE_AUTO_START"));
		c.add(List.of(n, "set", SERVICE, "AppStdout", log.toString()));
		c.add(List.of(n, "set", SERVICE, "AppStderr", log.toString()));
		c.add(List.of(n, "set", SERVICE, "AppExit", "Default", "Restart"));
		c.add(List.of(n, "set", SERVICE, "AppNoConsole", "1"));
		return c;
	}

	/** The service's own account: a virtual account, set with sc.exe, which needs no password for one. */
	static List<String> virtualAccount()
	{
		return List.of("sc.exe", "config", SERVICE, "obj=", WINDOWS_ACCOUNT);
	}

	/** Access on Windows: the data folder for SYSTEM, Administrators and the service; the work folder for the service only. */
	List<List<String>> acls()
	{
		List<List<String>> c = new ArrayList<>();
		c.add(List.of("icacls", l.configDir.toString(), "/inheritance:r", "/grant:r", SYSTEM_SID + ":(OI)(CI)F", ADMINISTRATORS_SID + ":(OI)(CI)F",
				WINDOWS_ACCOUNT + ":(OI)(CI)M"));
		c.add(List.of("icacls", l.work.toString(), "/inheritance:r", "/grant:r", WINDOWS_ACCOUNT + ":(OI)(CI)F"));
		c.add(List.of("icacls", l.program.toString(), "/grant", WINDOWS_ACCOUNT + ":(OI)(CI)RX"));
		c.add(List.of("icacls", l.chromeRoot.toString(), "/grant", WINDOWS_ACCOUNT + ":(OI)(CI)RX"));
		return c;
	}

	/** Tests only: stands in for the installer jar, which a test run from class folders does not have. */
	static volatile Path testSelfJar;

	/** This installer's own jar, which the Chrome update timer or task runs; null when it does not run from a jar. */
	static Path selfJar()
	{
		if(testSelfJar != null)
		{
			return testSelfJar;
		}
		try
		{
			Path p = Paths.get(CaptureSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
		}
		catch(Exception e)
		{
			return null;
		}
	}

	/** The updater in the program folder: a copy of this installer's jar. */
	Path updaterJar()
	{
		return l.program.resolve(ChromeUpdate.JAR);
	}

	/** Every file the program folder holds, with its bytes: the payload's capture/ tree and the updater. */
	Map<Path, byte[]> programFiles() throws IOException
	{
		Map<Path, byte[]> m = new TreeMap<>();
		for(String p: payload.under(PAYLOAD))
		{
			m.put(l.program.resolve(p.substring(PAYLOAD.length())), payload.bytes(p));
		}
		Path self = selfJar();
		if(self != null)
		{
			m.put(updaterJar(), Files.readAllBytes(self));
		}
		if(l.windows)
		{
			Path nssm = home.resolve("bin").resolve("nssm_x64.exe");
			if(Files.isRegularFile(nssm))
			{
				m.put(l.program.resolve("nssm.exe"), Files.readAllBytes(nssm));
			}
		}
		return m;
	}

	/** True when anything of the service differs from what this run would write, or it was never recorded as installed. */
	boolean needed(Config c, Map<String, Object> recorded) throws IOException
	{
		if(recorded == null || !payload.version().equals(recorded.get("version")))
		{
			return true;
		}
		Map<Path, byte[]> want = new LinkedHashMap<>(programFiles());
		want.put(l.config, properties(c).getBytes(StandardCharsets.UTF_8));
		if(!l.windows)
		{
			want.put(l.unit, unit(c).getBytes(StandardCharsets.UTF_8));
			want.put(ChromeUpdate.serviceUnit(l), ChromeUpdate.linuxService(java(), updaterJar()).getBytes(StandardCharsets.UTF_8));
			want.put(ChromeUpdate.timerUnit(l), ChromeUpdate.linuxTimer().getBytes(StandardCharsets.UTF_8));
		}
		else
		{
			want.put(ChromeUpdate.taskXml(l), ChromeUpdate.windowsTaskBytes(java(), updaterJar()));
		}
		for(Map.Entry<Path, byte[]> e: want.entrySet())
		{
			if(!Files.isRegularFile(e.getKey()) || !java.util.Arrays.equals(Files.readAllBytes(e.getKey()), e.getValue()))
			{
				return true;
			}
		}
		return l.windows && !Files.isRegularFile(java());
	}

	// ---- install ----------------------------------------------------------------------------------------------------

	/** The record the manifest keeps, so uninstall reverses exactly this. */
	Map<String, Object> record()
	{
		return record;
	}

	/** Installs or updates everything but starting the service. On a failure puts back what it did and throws. */
	void install(Config c) throws Failed
	{
		record.put("os", l.windows ? "windows" : "linux");
		record.put("version", payload.version());
		record.put("program", l.program.toString());
		record.put("chromeRoot", l.chromeRoot.toString());
		record.put("configDir", l.configDir.toString());
		record.put("work", l.work.toString());
		record.put("logs", l.logs.toString());
		try
		{
			if(l.windows)
			{
				installWindows(c);
			}
			else
			{
				installLinux(c);
			}
		}
		catch(Failed f)
		{
			boolean undone = undo() && !keepProgram;
			throw new Failed(f.getMessage() + (serviceLeft == null ? "" : " " + serviceLeft), undone);
		}
		catch(IOException | RuntimeException e)
		{
			String why = Plain.say(e);
			boolean undone = undo() && !keepProgram;
			throw new Failed("The capture service could not be set up: " + why + "." + (serviceLeft == null ? "" : " " + serviceLeft), undone);
		}
	}

	private void installLinux(Config c) throws IOException, Failed
	{
		out.println("  capture service: stopping it if it runs, then installing it as " + SERVICE + ".service");
		stopAndWait(l, sys);
		undo.add(() -> sys.run(List.of("systemctl", "start", SERVICE + ".service")));
		if(sys.run(List.of("getent", "passwd", USER)).code() != 0)
		{
			must(List.of("useradd", "--system", "--user-group", "--home-dir", l.work.toString(), "--no-create-home", "--shell", "/usr/sbin/nologin", USER),
					"the system user " + USER + " could not be created");
			record.put("createdUser", true);
			undo.add(() -> sys.run(List.of("userdel", USER)));
		}
		// the socket's folder is group wse-url-overlay, mode 0770: WSE's user joins the group, unless WSE runs as root
		String wseUser = wseUser();
		if(wseUser != null && !wseUser.equals("root") && !sys.run(List.of("id", "-nG", wseUser)).output().matches("(?s).*\\b" + USER + "\\b.*"))
		{
			must(List.of("usermod", "-a", "-G", USER, wseUser), "WSE's user " + wseUser + " could not be added to the group " + USER);
			record.put("groupMember", wseUser);
			undo.add(() -> sys.run(List.of("gpasswd", "-d", wseUser, USER)));
		}
		writeTree(programFiles(), "rw-r--r--", "rwxr-xr-x");
		removeStale(l.program, programFiles().keySet());
		mkdir(l.configDir, "rwxr-xr-x", "root", "root");
		write(l.config, properties(c).getBytes(StandardCharsets.UTF_8), "rw-r-----", "root", USER);
		chromeReadOnly();
		mkdir(l.unit.getParent(), "rwxr-xr-x", "root", "root");
		write(l.unit, unit(c).getBytes(StandardCharsets.UTF_8), "rw-r--r--", "root", "root");
		record.put("unit", l.unit.toString());
		// the daily Chrome update: a oneshot service and its timer
		write(ChromeUpdate.serviceUnit(l), ChromeUpdate.linuxService(java(), updaterJar()).getBytes(StandardCharsets.UTF_8), "rw-r--r--", "root", "root");
		write(ChromeUpdate.timerUnit(l), ChromeUpdate.linuxTimer().getBytes(StandardCharsets.UTF_8), "rw-r--r--", "root", "root");
		record.put("updateTimer", ChromeUpdate.timerUnit(l).toString());
		must(List.of("systemctl", "daemon-reload"), "systemd could not read the new service unit");
		must(List.of("systemctl", "enable", "--now", ChromeUpdate.TASK + ".timer"), "the daily Chrome update could not be scheduled");
		undo.add(() -> sys.run(List.of("systemctl", "disable", "--now", ChromeUpdate.TASK + ".timer")));
		if(sys.apparmor())
		{
			if(sys.apparmorAbi4())
			{
				mkdir(l.apparmor.getParent(), "rwxr-xr-x", "root", "root");
				write(l.apparmor, apparmorProfile().getBytes(StandardCharsets.UTF_8), "rw-r--r--", "root", "root");
				record.put("apparmor", l.apparmor.toString());
				must(List.of("apparmor_parser", "-r", l.apparmor.toString()), "the AppArmor profile for Chrome could not be loaded");
				undo.add(() -> sys.run(List.of("apparmor_parser", "-R", l.apparmor.toString())));
			}
			else
			{
				out.println("  capture service: AppArmor is older than 4.0, which does not restrict user namespaces; no profile is needed");
			}
		}
	}

	/** WSE's user: the owner of conf/Server.xml, which WSE writes. */
	private String wseUser()
	{
		try
		{
			return Files.getOwner(home.resolve("conf").resolve("Server.xml")).getName();
		}
		catch(IOException | UnsupportedOperationException e)
		{
			return null;
		}
	}

	// Chrome for Testing as unpacked: root-owned, read and execute for everyone, programs executable
	private void chromeReadOnly() throws IOException
	{
		if(!Files.isDirectory(l.chromeRoot))
		{
			return;
		}
		try(var s = Files.walk(l.chromeRoot))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				boolean exec = Files.isDirectory(p) || Files.isExecutable(p);
				sys.posix(p, exec ? "rwxr-xr-x" : "rw-r--r--", "root", "root");
			}
		}
	}

	/** Where nssm keeps the program it runs and its arguments, under the service's registry key. */
	static final String PARAMETERS = "HKLM\\SYSTEM\\CurrentControlSet\\Services\\" + SERVICE + "\\Parameters";
	static final List<String> NSSM_VALUES = List.of("Application", "AppDirectory", "AppParameters");

	/**
	 * The service's registration as Windows and nssm hold it: ImagePath (the nssm.exe the service runs), start type,
	 * account, and nssm's Application, AppDirectory and AppParameters. Read before a run changes anything, so a rollback
	 * puts back exactly this.
	 */
	record Registration(String imagePath, String start, String account, Map<String, String> values)
	{
		/** Both programs it names are on disk: the nssm.exe and the java it starts. */
		boolean usable()
		{
			return exists(imagePath) && exists(values.get("Application"));
		}

		private static boolean exists(String path)
		{
			if(path == null || path.isBlank())
			{
				return false;
			}
			String p = path.trim();
			if(p.startsWith("\""))
			{
				p = p.substring(1, p.indexOf('"', 1) < 0 ? p.length() : p.indexOf('"', 1));
			}
			try
			{
				return Files.isRegularFile(Paths.get(p));
			}
			catch(RuntimeException e)
			{
				return false;
			}
		}
	}

	Registration readRegistration()
	{
		Result qc = sys.run(List.of("sc.exe", "qc", SERVICE));
		if(qc.code() != 0)
		{
			return null;
		}
		String image = field(qc.output(), "BINARY_PATH_NAME");
		String start = field(qc.output(), "START_TYPE");
		String account = field(qc.output(), "SERVICE_START_NAME");
		Map<String, String> v = new LinkedHashMap<>();
		for(String name: NSSM_VALUES)
		{
			Result r = sys.run(List.of("reg", "query", PARAMETERS, "/v", name));
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*" + name + "\\s+REG_\\w+\\s+(.*?)\\s*$").matcher(r.output());
			v.put(name, r.code() == 0 && m.find() ? m.group(1) : null);
		}
		return new Registration(image, start == null ? null : start.split("\\s+")[0], account, v);
	}

	private static String field(String text, String name)
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*" + name + "\\s*:\\s*(.*?)\\s*$").matcher(text);
		return m.find() ? m.group(1) : null;
	}

	private static String startWord(String code)
	{
		return switch(code == null ? "" : code)
		{
			case "2" -> "auto";
			case "4" -> "disabled";
			default -> "demand";
		};
	}

	/** Puts a registration back as it was read. */
	void restore(Registration r)
	{
		sys.run(List.of("sc.exe", "config", SERVICE, "binPath=", r.imagePath(), "start=", startWord(r.start()), "obj=", r.account()));
		for(String name: NSSM_VALUES)
		{
			if(r.values().get(name) != null)
			{
				sys.run(List.of("reg", "add", PARAMETERS, "/v", name, "/t", "REG_EXPAND_SZ", "/d", r.values().get(name), "/f"));
			}
		}
	}

	/** What a failed run left the service as, in words, for the console and the setup window; null when it ran as before. */
	private String serviceLeft;

	String serviceLeft()
	{
		return serviceLeft;
	}

	/**
	 * Windows, in an order where a failure can always be put back: every file first (program, nssm, runtime, Chrome
	 * folder, settings, task file), then the folder access for an existing service, and only then the service's
	 * registration and the scheduled task. A rollback restores the registration read at the start; when that one names
	 * programs no longer on disk (a setup program's removal deleted them), there is nothing to go back to: the new
	 * program files stay, the registration points at them, and the service is left stopped and disabled.
	 */
	private void installWindows(Config c) throws IOException, Failed
	{
		Path nssm = l.program.resolve("nssm.exe");
		if(!Files.isRegularFile(home.resolve("bin").resolve("nssm_x64.exe")))
		{
			throw new Failed("The capture service could not be set up: WSE's service helper, " + home.resolve("bin").resolve("nssm_x64.exe") + ", is missing", true);
		}
		boolean exists = sys.run(List.of("sc.exe", "query", SERVICE)).code() == 0;
		Registration before = exists ? readRegistration() : null;
		boolean usable = before != null && before.usable();
		out.println("  capture service: " + (exists ? "stopping it, then updating it" : "installing it as the Windows service " + SERVICE));
		if(exists)
		{
			stopAndWait(l, sys);
			if(usable)
			{
				// runs last on a rollback: the registration as it was, and the service started on it
				undo.add(() -> {
					restore(before);
					sys.run(List.of("sc.exe", "start", SERVICE));
					serviceLeft = "The capture service runs as it did before this run.";
				});
			}
			else
			{
				// the earlier program is gone: keep this run's files so the registration never names a missing program
				keepProgram = true;
				undo.add(() -> {
					sys.run(List.of("sc.exe", "config", SERVICE, "start=", "disabled"));
					serviceLeft = "The capture service is stopped and disabled: the program it ran before is no longer on this computer. Run the setup again to set it up.";
				});
			}
		}
		// files
		writeTree(programFiles(), null, null);
		removeStale(l.program, programFiles().keySet());
		copyRuntime();
		mkdir(l.chromeRoot, null, null, null);
		mkdir(l.configDir, null, null, null);
		mkdir(l.work, null, null, null);
		mkdir(l.logs, null, null, null);
		write(l.config, properties(c).getBytes(StandardCharsets.UTF_8), null, null, null);
		write(ChromeUpdate.taskXml(l), ChromeUpdate.windowsTaskBytes(java(), updaterJar()), null, null, null);
		if(!Files.isRegularFile(java()) || !Files.isRegularFile(nssm))
		{
			throw new Failed("The capture service could not be set up: its program files are incomplete (" + (Files.isRegularFile(nssm) ? java() : nssm) + ").", false);
		}
		// access, while the service, and so its account, already exists
		if(exists)
		{
			for(List<String> cmd: acls())
			{
				must(cmd, "access to " + cmd.get(1) + " could not be set");
			}
		}
		// the registration, last
		if(!exists)
		{
			must(List.of(nssm.toString(), "install", SERVICE, java().toString()), "the Windows service " + SERVICE + " could not be registered");
			record.put("createdService", true);
			undo.add(() -> {
				sys.run(List.of(nssm.toString(), "remove", SERVICE, "confirm"));
				serviceLeft = "The capture service was not installed.";
			});
		}
		for(List<String> cmd: nssmSettings(nssm))
		{
			must(cmd, "the Windows service " + SERVICE + " could not be configured");
		}
		must(virtualAccount(), "the service account " + WINDOWS_ACCOUNT + " could not be set");
		if(!exists)
		{
			for(List<String> cmd: acls())
			{
				must(cmd, "access to " + cmd.get(1) + " could not be set");
			}
		}
		// the daily Chrome update, as SYSTEM
		must(ChromeUpdate.createTask(ChromeUpdate.taskXml(l)), "the daily Chrome update could not be scheduled");
		record.put("updateTask", true);
		undo.add(() -> sys.run(ChromeUpdate.deleteTask()));
	}

	// the runtime the setup program carries, copied next to the service, so an update of the setup program never
	// replaces files under a running service
	private void copyRuntime() throws IOException
	{
		Path to = l.program.resolve("runtime");
		Path from = l.runtimeSource;
		if(from == null || !Files.isDirectory(from))
		{
			throw new IOException("no Java runtime to copy from " + from);
		}
		byte[] want = Files.isRegularFile(from.resolve("release")) ? Files.readAllBytes(from.resolve("release")) : new byte[0];
		Path have = to.resolve("release");
		if(Files.isRegularFile(java()) && Files.isRegularFile(have) && java.util.Arrays.equals(Files.readAllBytes(have), want))
		{
			return;
		}
		boolean existed = Files.exists(to);
		Map<Path, byte[]> files = new TreeMap<>();
		try(var s = Files.walk(from))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				if(Files.isRegularFile(p))
				{
					files.put(to.resolve(from.relativize(p).toString()), Files.readAllBytes(p));
				}
			}
		}
		writeTree(files, null, null);
		if(!existed)
		{
			undo.add(() -> {
				if(!kept(to))
				{
					ChromeDownload.deleteTree(to);
				}
			});
		}
	}

	/** Starts the service and says whether it runs: null when it does, else what to do, in words. */
	String start()
	{
		if(l.windows)
		{
			sys.run(List.of("sc.exe", "start", SERVICE));
			for(int k = 0; k < 10; k++)
			{
				if(sys.run(List.of("sc.exe", "query", SERVICE)).output().contains("RUNNING"))
				{
					return null;
				}
				pause();
			}
			return "the capture service did not start. Its log is " + l.logs.resolve("service.log") + "; start it in Windows Services (" + SERVICE + ")";
		}
		sys.run(List.of("systemctl", "enable", SERVICE + ".service"));
		sys.run(List.of("systemctl", "restart", SERVICE + ".service"));
		for(int k = 0; k < 10; k++)
		{
			String state = sys.run(List.of("systemctl", "is-active", SERVICE + ".service")).output().trim();
			if(state.equals("active"))
			{
				return null;
			}
			if(!state.equals("activating"))
			{
				break;
			}
			pause();
		}
		return "the capture service did not start. See: journalctl -u " + SERVICE + "; start it with: sudo systemctl start " + SERVICE;
	}

	/**
	 * Stops the service and waits, at most about 30 seconds, until nothing runs from the Chrome folder: Chrome, or the
	 * chromedriver of an earlier version (nssm and systemd end the whole process tree on stop; this checks it). What
	 * still runs then is killed.
	 * @return the processes still running from the folder afterwards; empty normally
	 */
	static List<Long> stopAndWait(Layout l, Sys sys)
	{
		return stopAndWait(l.windows(), l.chromeRoot(), sys);
	}

	static List<Long> stopAndWait(boolean windows, Path chromeRoot, Sys sys)
	{
		if(windows)
		{
			if(sys.run(List.of("sc.exe", "query", SERVICE)).code() == 0)
			{
				sys.run(List.of("sc.exe", "stop", SERVICE));
				for(int k = 0; k < 30 && !sys.run(List.of("sc.exe", "query", SERVICE)).output().contains("STOPPED"); k++)
				{
					pause();
				}
			}
		}
		else
		{
			sys.run(List.of("systemctl", "stop", SERVICE + ".service"));
		}
		if(chromeRoot == null)
		{
			return List.of();
		}
		for(int k = 0; k < 30; k++)
		{
			if(sys.processesUnder(chromeRoot).isEmpty())
			{
				return List.of();
			}
			pause();
		}
		for(long pid: sys.processesUnder(chromeRoot))
		{
			sys.kill(pid);
		}
		for(int k = 0; k < 10; k++)
		{
			if(sys.processesUnder(chromeRoot).isEmpty())
			{
				return List.of();
			}
			pause();
		}
		return sys.processesUnder(chromeRoot);
	}

	/** True when the service runs now. */
	static boolean running(Layout l, Sys sys)
	{
		return l.windows() ? sys.run(List.of("sc.exe", "query", SERVICE)).output().contains("RUNNING")
				: sys.run(List.of("systemctl", "is-active", SERVICE + ".service")).output().trim().equals("active");
	}

	private static void pause()
	{
		if(testSys != null)
		{
			return;
		}
		try
		{
			Thread.sleep(500);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/** Puts back what install did, newest first; true when all of it worked. */
	boolean undo()
	{
		boolean ok = true;
		for(int k = undo.size() - 1; k >= 0; k--)
		{
			try
			{
				undo.get(k).run();
			}
			catch(IOException | RuntimeException e)
			{
				ok = false;
				out.println("  could not put back: " + Plain.say(e));
			}
		}
		undo.clear();
		return ok;
	}

	// ---- uninstall --------------------------------------------------------------------------------------------------

	/** Stops the service, before uninstall changes WSE; it is started again if that fails. */
	static void stop(Map<String, Object> rec, Sys sys)
	{
		stopAndWait("windows".equals(rec.get("os")), path(rec, "chromeRoot"), sys);
	}

	static void startAgain(Map<String, Object> rec, Sys sys)
	{
		startAgain("windows".equals(rec.get("os")), sys);
	}

	static void startAgain(boolean windows, Sys sys)
	{
		sys.run(windows ? List.of("sc.exe", "start", SERVICE) : List.of("systemctl", "start", SERVICE + ".service"));
	}

	/**
	 * Removes everything the record names: the service, its unit or registration, the AppArmor profile, the program,
	 * Chrome, the configuration, work files and logs, the group membership and the user, if this installer added them.
	 * @return what could not be removed, in words; empty when all of it went
	 */
	static List<String> uninstall(Map<String, Object> rec, Sys sys, PrintStream out)
	{
		List<String> left = new ArrayList<>();
		Path program = path(rec, "program");
		if("windows".equals(rec.get("os")))
		{
			String nssm = program.resolve("nssm.exe").toString();
			stopAndWait(true, path(rec, "chromeRoot"), sys);
			Result r = sys.run(List.of(nssm, "remove", SERVICE, "confirm"));
			if(r.code() != 0 && sys.run(List.of("sc.exe", "query", SERVICE)).code() == 0)
			{
				left.add("the Windows service " + SERVICE + " (remove it with: sc.exe delete " + SERVICE + ")");
			}
			if(Boolean.TRUE.equals(rec.get("updateTask")))
			{
				sys.run(ChromeUpdate.deleteTask());
			}
			// the work folder belongs to the service account alone: an administrator takes it over before deleting it
			Path work = path(rec, "work");
			if(work != null && Files.exists(work))
			{
				sys.run(List.of("takeown", "/F", work.toString(), "/R", "/A", "/D", "Y"));
				sys.run(List.of("icacls", work.toString(), "/grant", ADMINISTRATORS_SID + ":(OI)(CI)F", "/T", "/C"));
			}
		}
		else
		{
			sys.run(List.of("systemctl", "disable", SERVICE + ".service"));
			stopAndWait(false, path(rec, "chromeRoot"), sys);
			Path timer = path(rec, "updateTimer");
			if(timer != null)
			{
				sys.run(List.of("systemctl", "disable", "--now", ChromeUpdate.TASK + ".timer"));
				delete(timer, left);
				delete(timer.resolveSibling(ChromeUpdate.TASK + ".service"), left);
			}
			Path unit = path(rec, "unit");
			if(unit != null)
			{
				delete(unit, left);
				sys.run(List.of("systemctl", "daemon-reload"));
			}
			Path profile = path(rec, "apparmor");
			if(profile != null)
			{
				sys.run(List.of("apparmor_parser", "-R", profile.toString()));
				delete(profile, left);
			}
		}
		for(String k: List.of("program", "chromeRoot", "work", "logs", "configDir"))
		{
			Path p = path(rec, k);
			if(p != null)
			{
				delete(p, left);
			}
		}
		if(program != null)
		{
			Installer.deleteEmptyDirs(program.getParent());
		}
		if(rec.get("groupMember") != null)
		{
			sys.run(List.of("gpasswd", "-d", String.valueOf(rec.get("groupMember")), USER));
		}
		if(Boolean.TRUE.equals(rec.get("createdUser")))
		{
			if(sys.run(List.of("userdel", USER)).code() != 0)
			{
				left.add("the system user " + USER + " (remove it with: sudo userdel " + USER + ")");
			}
			sys.run(List.of("groupdel", USER));
		}

		return left;
	}

	private static Path path(Map<String, Object> rec, String key)
	{
		Object v = rec.get(key);
		return v == null ? null : Paths.get(String.valueOf(v));
	}

	private static void delete(Path p, List<String> left)
	{
		try
		{
			if(Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
			{
				ChromeDownload.deleteTree(p);
			}
			else
			{
				Files.deleteIfExists(p);
			}
		}
		catch(IOException e)
		{
			left.add(p + " (" + Plain.say(e) + ")");
		}
	}

	// ---- steps with their undo --------------------------------------------------------------------------------------

	private void must(List<String> cmd, String what) throws Failed
	{
		Result r = sys.run(cmd);
		if(r.code() != 0)
		{
			Plain.detail.println("Command failed (" + r.code() + "): " + String.join(" ", cmd) + System.lineSeparator() + r.output());
			throw new Failed("The capture service could not be set up: " + what + ".", false);
		}
	}

	/** Set when the earlier program is gone: a rollback then keeps this run's program files (see installWindows). */
	private boolean keepProgram;

	private boolean kept(Path p)
	{
		Path a = p.toAbsolutePath().normalize(), prog = l.program.toAbsolutePath().normalize();
		return keepProgram && (a.startsWith(prog) || prog.startsWith(a));
	}

	private void mkdir(Path dir, String mode, String owner, String group) throws IOException
	{
		List<Path> missing = new ArrayList<>();
		for(Path p = dir; p != null && !Files.exists(p); p = p.getParent())
		{
			missing.add(0, p);
		}
		Files.createDirectories(dir);
		for(Path p: missing)
		{
			if(mode != null)
			{
				sys.posix(p, mode, owner, group);
			}
		}
		if(!missing.isEmpty())
		{
			Path top = missing.get(0);
			undo.add(() -> {
				if(!kept(top))
				{
					ChromeDownload.deleteTree(top);
				}
			});
		}
	}

	private void write(Path p, byte[] bytes, String mode, String owner, String group) throws IOException
	{
		mkdir(p.getParent(), mode == null ? null : "rwxr-xr-x", "root", "root");
		byte[] before = Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		undo.add(() -> {
			if(kept(p))
			{
				return;
			}
			if(before != null)
			{
				Files.write(p, before);
			}
			else
			{
				Files.deleteIfExists(p);
			}
		});
		Files.write(p, bytes);
		if(mode != null)
		{
			sys.posix(p, mode, owner, group);
		}
	}

	private void writeTree(Map<Path, byte[]> files, String fileMode, String dirMode) throws IOException
	{
		for(Map.Entry<Path, byte[]> e: files.entrySet())
		{
			if(dirMode != null)
			{
				mkdir(e.getKey().getParent(), dirMode, "root", "root");
			}
			write(e.getKey(), e.getValue(), fileMode, "root", "root");
		}
	}

	// a file an earlier version put in the program folder that this one does not carry
	private void removeStale(Path dir, java.util.Set<Path> keep) throws IOException
	{
		if(!Files.isDirectory(dir))
		{
			return;
		}
		List<Path> stale = new ArrayList<>();
		try(var s = Files.walk(dir))
		{
			s.filter(Files::isRegularFile).filter(p -> !keep.contains(p) && !p.startsWith(dir.resolve("runtime"))).forEach(stale::add);
		}
		for(Path p: stale)
		{
			byte[] before = Files.readAllBytes(p);
			undo.add(() -> Files.write(p, before));
			Files.delete(p);
		}
	}
}
