/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.wowza.wms.plugin.urloverlay.config.TextFile;

/** java -jar wse-plugin-url-overlay-installer-&lt;version&gt;.jar Install | Uninstall | Verify [options]. */
public final class Main
{
	static final String USAGE = String.join(System.lineSeparator(),
			"Wowza Streaming Engine URL Overlay installer",
			"",
			"  java -jar wse-plugin-url-overlay-installer-<version>.jar Install   [options]",
			"  java -jar wse-plugin-url-overlay-installer-<version>.jar Uninstall [options]",
			"  java -jar wse-plugin-url-overlay-installer-<version>.jar Verify    [options]",
			"  java -jar wse-plugin-url-overlay-installer-<version>.jar UpdateChrome",
			"    Installs a newer Chrome for Testing for the capture service, if there is one (as root or SYSTEM; run daily by the",
			"    timer or scheduled task the install creates).",
			"",
			"Options",
			"  --home <path>          WSE install folder. Default: -Dcom.wowza.wms.ConfigHome, WMSAPP_HOME, then the one install found",
			"  --wsem-home <path>     WSE Manager folder. Default: <home>/manager",
			"  --list-apps            Show the applications found and exit without changes",
			"  --appName <a,b>        Applications to add the module to (name, or vhost/name), no prompt",
			"  --all-live-apps        Add the module to every live application, no prompt",
			"  --remove-app <a,b>     Take the module out of these applications only",
			"  --chrome <path>        Chrome or Chromium for the capture service. Without it, Install downloads Chrome from Google's",
			"                         Chrome for Testing service into the capture service's Chrome folder (chrome-headless-shell on",
			"                         Linux, Chrome for Testing on Windows), unless its settings already name a Chrome chosen by hand",
			"  --chromedriver <path>  Accepted for scripts written for earlier versions, and ignored: the service needs no chromedriver",
			"  --no-download          Do not download Chrome; set the paths yourself",
			"  --dry-run              Print every change and write nothing",
			"  --yes                  No prompts. Without --appName or --all-live-apps no application is changed",
			"  --keep-templates       Uninstall leaves the starter templates",
			"  --purge                Uninstall also deletes the saved overlays in conf.modules/urloverlay",
			"  --help, --?            This text",
			"",
			"Exit codes: 0 done or nothing to do, 1 bad arguments, 2 WSE not found or ambiguous, 3 preflight failed (cannot write,",
			"or WSE is running), 4 WSE older than 4.11.1, 5 failed and rolled back, 6 failed and rollback incomplete,",
			"7 failed before anything was changed.");

	/** Parsed command line. */
	static final class Options
	{
		String command;
		String home, wsemHome, chrome, chromedriver;
		List<String> apps, removeApps;
		boolean listApps, allLive, dryRun, yes, keepTemplates, help, noDownload, elevated, purge, gui, setupUninstall;
	}

	static Options parse(String[] args)
	{
		Options o = new Options();
		for(int k = 0; k < args.length; k++)
		{
			String a = args[k];
			switch(a)
			{
				case "--help", "--?", "-h" -> o.help = true;
				case "--list-apps" -> o.listApps = true;
				case "--all-live-apps" -> o.allLive = true;
				case "--dry-run" -> o.dryRun = true;
				case "--yes" -> o.yes = true;
				case "--keep-templates" -> o.keepTemplates = true;
				case "--purge" -> o.purge = true;
				case "--no-download" -> o.noDownload = true;
				case "--elevated" -> o.elevated = true;
				// the window-based setup, and the setup program's removal step (main.wxs); neither is for people to type
				case "--gui" -> o.gui = true;
				case "--setup-uninstall" -> o.setupUninstall = true;
				case "--home", "--wsem-home", "--appName", "--remove-app", "--chrome", "--chromedriver" -> {
					if(k + 1 >= args.length)
					{
						throw new IllegalArgumentException(a + " needs a value");
					}
					String v = args[++k];
					switch(a)
					{
						case "--home" -> o.home = v;
						case "--wsem-home" -> o.wsemHome = v;
						case "--appName" -> o.apps = Arrays.asList(v.split("\\s*,\\s*"));
						case "--remove-app" -> o.removeApps = Arrays.asList(v.split("\\s*,\\s*"));
						case "--chrome" -> o.chrome = v;
						// earlier versions needed it; read so their scripts still run, and not used
						default -> o.chromedriver = v;
					}
				}
				default -> {
					if(a.startsWith("--"))
					{
						throw new IllegalArgumentException("Unknown option " + a);
					}
					if(o.command != null)
					{
						throw new IllegalArgumentException("Only one command: " + o.command + " or " + a);
					}
					o.command = a.equalsIgnoreCase("UpdateChrome") ? "UpdateChrome" : a.substring(0, 1).toUpperCase() + a.substring(1).toLowerCase();
				}
			}
		}
		if(o.command == null && !o.help && !o.listApps && !o.gui && !o.setupUninstall)
		{
			throw new IllegalArgumentException("Give a command: Install, Uninstall, Verify or UpdateChrome");
		}
		if(o.command != null && !List.of("Install", "Uninstall", "Verify", "UpdateChrome").contains(o.command))
		{
			throw new IllegalArgumentException("Unknown command " + o.command);
		}
		if(o.gui && o.command == null)
		{
			// the setup window, or Install when there is no screen to show it on
			o.command = "Install";
		}
		if(o.apps != null && o.allLive)
		{
			throw new IllegalArgumentException("Use --appName or --all-live-apps, not both");
		}
		return o;
	}

	public static void main(String[] args)
	{
		if(Arrays.asList(args).contains("--gui") && !java.awt.GraphicsEnvironment.isHeadless())
		{
			SetupWindow.main(args);
			return;
		}
		System.exit(run(args, System.out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.console() != null, null));
	}

	/**
	 * Runs one command; returns the exit code. Everything printed goes to out and, for a real run, to
	 * &lt;home&gt;/logs/urloverlay-install-&lt;timestamp&gt;.log.
	 * @param candidates install folders to choose from instead of the standard places (tests); null for the standard ones
	 */
	static int run(String[] args, PrintStream console, BufferedReader in, boolean interactive, List<Path> candidates)
	{
		ByteArrayOutputStream logged = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(new Tee(console, logged), true, StandardCharsets.UTF_8);
		// failure detail (stack traces) goes to the log only; the console gets plain words
		Plain.detail = new PrintStream(logged, true, StandardCharsets.UTF_8);
		Plain.changed = false;
		Options o;
		try
		{
			o = parse(args);
		}
		catch(IllegalArgumentException e)
		{
			out.println(e.getMessage());
			out.println();
			out.println(USAGE);
			return 1;
		}
		if(o.help)
		{
			out.println(USAGE);
			return 0;
		}
		if(o.setupUninstall)
		{
			return setupUninstall(o, console, in, candidates);
		}
		if("UpdateChrome".equals(o.command))
		{
			return updateChrome(out, logged);
		}
		Path home = null;
		int code;
		try
		{
			home = Wse.home(o.home, candidates == null ? null : new ArrayList<>(candidates));
			if(Elevate.needed(o, home))
			{
				out.println("Administrator rights are needed to change " + home + ". Windows asks for them now; the install continues in a new window.");
				return Elevate.relaunch(args);
			}
			code = command(o, home, out, in, interactive && !o.yes);
		}
		catch(Wse.Failure f)
		{
			out.println(f.getMessage());
			code = f.exit;
		}
		catch(IOException | RuntimeException e)
		{
			// a failure after a change is normally caught where it happens, with its rollback; this one was not, so
			// "nothing was changed" is said only when this run changed nothing at all
			String why = Plain.say(e);
			out.println("Failed: " + why + (Plain.changed ? ". Some changes may remain; the log lists what this run did." : ". Nothing was changed."));
			code = Plain.changed ? 6 : 7;
		}
		if(o.elevated && interactive)
		{
			// the elevated window closes when the process ends; keep the report readable
			console.print("Press Enter to close this window.");
			console.flush();
			try
			{
				in.readLine();
			}
			catch(IOException e)
			{
				// closing anyway
			}
		}
		if(home != null && !o.dryRun && !o.listApps && !"Verify".equals(o.command))
		{
			try
			{
				console.println("Log: " + writeLog(home, logged.toByteArray()));
			}
			catch(IOException e)
			{
				console.println("Could not write the log: " + e.getMessage());
			}
		}
		return code;
	}

	/**
	 * UpdateChrome: the daily Chrome update (ChromeUpdate), on the capture service's own folders, so no WSE install is
	 * needed. The run's report replaces chrome-update.log in the service's log folder.
	 */
	private static int updateChrome(PrintStream out, ByteArrayOutputStream logged)
	{
		CaptureSetup.Layout l = CaptureSetup.layout();
		int code;
		try
		{
			code = new ChromeUpdate(l, CaptureSetup.sys(), out, ChromeDownload.client()).run();
		}
		catch(RuntimeException e)
		{
			out.println("The Chrome update stopped: " + Plain.say(e) + ".");
			code = 5;
		}
		try
		{
			Files.createDirectories(l.logs());
			Files.write(l.logs().resolve("chrome-update.log"), logged.toByteArray());
		}
		catch(IOException e)
		{
			out.println("Could not write the log: " + Plain.words(e));
		}
		return code;
	}

	/** Writes a run's report to &lt;home&gt;/logs/urloverlay-install-&lt;timestamp&gt;.log; returns where. */
	static Path writeLog(Path home, byte[] report) throws IOException
	{
		String name = "urloverlay-install-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path log = home.resolve("logs").resolve(name + ".log");
		// two runs in the same second keep both logs
		for(int n = 2; Files.exists(log); n++)
		{
			log = home.resolve("logs").resolve(name + "-" + n + ".log");
		}
		Ownership own = Ownership.forHome(home);
		own.createDirectories(log.getParent());
		Files.write(log, report);
		own.fix(log);
		return log;
	}

	/**
	 * The setup program's removal step (main.wxs): Uninstall on the install the setup program recorded, with no one to
	 * ask. Only a running WSE stops the removal (exit 3); anything else is logged and the removal goes on, so a WSE that
	 * is gone or broken can never make the setup program impossible to remove.
	 */
	private static int setupUninstall(Options o, PrintStream console, BufferedReader in, List<Path> candidates)
	{
		Path home = o.home != null ? Paths.get(o.home) : Setup.recorded();
		if(home == null || !Files.isRegularFile(home.resolve("conf").resolve("Server.xml")))
		{
			console.println(home == null ? "No install recorded by the setup program; nothing to take out." : "No WSE at " + home + " any more; nothing to take out.");
			return 0;
		}
		int code = run(new String[] { "Uninstall", "--yes", "--home", home.toString() }, console, in, false, candidates);
		if(code == 3)
		{
			return 3;
		}
		if(code != 0)
		{
			console.println("URL Overlay could not be taken out of WSE (exit " + code + "); the setup program is removed anyway. README, Uninstall, says how to finish by hand.");
		}
		// the setup program is going; its folder must be left empty for Windows Installer to remove it
		try
		{
			Path dir = Setup.appDir();
			if(dir != null)
			{
				Files.deleteIfExists(dir.resolve(Setup.RECORD));
			}
		}
		catch(IOException e)
		{
			// a leftover file in the program folder, nothing worse
		}
		return 0;
	}

	private static int command(Options o, Path home, PrintStream out, BufferedReader in, boolean interactive) throws IOException, Wse.Failure
	{
		InstallSession s = InstallSession.open(home, o.wsemHome == null ? null : Path.of(o.wsemHome), out);
		out.println("URL Overlay " + s.payload.version() + ", WSE " + (s.wseVersion == null ? "version unknown" : s.wseVersion) + " at " + home);
		if(!s.supported())
		{
			out.println("WSE " + s.wseVersion + " is older than 4.11.1, which URL Overlay needs.");
			return 4;
		}
		if(o.listApps)
		{
			table(out, s.apps);
			return 0;
		}
		if(s.inst.manifest().containsKey("pending"))
		{
			out.println("Note: an earlier run stopped before it finished. What it changed is recorded, so this run completes it and Uninstall can take it all out.");
		}
		return switch(o.command)
		{
			case "Install" -> o.removeApps != null ? removeApps(o, s, out) : install(o, s, out, in, interactive);
			case "Uninstall" -> uninstall(o, s, out);
			default -> verify(s, out);
		};
	}

	// ---- Install ----------------------------------------------------------------------------------------------------

	private static int install(Options o, InstallSession s, PrintStream out, BufferedReader in, boolean interactive) throws IOException
	{
		Path home = s.home;
		List<Wse.App> chosen;
		try
		{
			chosen = select(o, s.apps, out, in, interactive);
		}
		catch(IllegalArgumentException e)
		{
			out.println(e.getMessage());
			return 1;
		}
		for(Wse.App a: chosen)
		{
			if(a.transcoder() == null || !a.transcoder().contains("transcoder"))
			{
				out.println("Note: application " + a.name() + " has its transcoder off; an overlay shows once a stream there has a template that carries it (plugin page, step 3).");
			}
		}
		Map<String, String> props = s.properties();

		// Chrome: a given path wins; else the Stable build, reused when already complete on disk, downloaded otherwise, unless
		// the capture service's settings already name a Chrome of the admin's own, or --no-download
		InstallSession.ServerChrome sc = s.serverChrome();
		boolean adminSet = sc.set();
		boolean download = o.chrome == null && !o.noDownload && !adminSet;
		if(o.chromedriver != null)
		{
			out.println("Note: --chromedriver is ignored; the capture service drives Chrome itself and needs no chromedriver. Without --chrome, Chrome is downloaded.");
		}
		String platform = download ? ChromeDownload.platform() : null;
		if(download && platform == null)
		{
			out.println("Chrome for Testing has no build for this OS and architecture; pass --chrome.");
		}
		// the checks come first, so nothing is downloaded for a run that cannot finish
		if(!o.dryRun)
		{
			InstallSession.Problem p = s.installBlocker(chosen, platform != null);
			if(p != null)
			{
				out.println(p.message());
				return p.exit();
			}
		}
		ChromeDownload.Pair pair = null;
		boolean fetched = false;
		if(download && platform != null)
		{
			java.net.http.HttpClient http = ChromeDownload.client();
			try
			{
				ChromeDownload.Build b = ChromeDownload.stable(http, platform);
				if(o.dryRun)
				{
					pair = ChromeFolders.complete(s.capture.chromeRoot(), b.version(), platform);
					if(pair == null)
					{
						out.println("Would download " + ChromeDownload.download(platform) + " " + b.version() + " from Chrome for Testing into " + s.capture.chromeRoot());
					}
				}
				else
				{
					ChromeFolders.Result r = ChromeFolders.ensure(s.capture.chromeRoot(), b, s.configuredChrome(), s.sys, http, out, null, () -> false, () -> {
						Plain.changed = true;
						s.stopCaptureForChrome();
						out.println("Downloading " + ChromeDownload.download(platform) + " " + b.version() + " from Chrome for Testing...");
					});
					pair = r.pair();
					fetched = r.downloaded();
				}
			}
			catch(IOException | RuntimeException e)
			{
				s.startCaptureIfStopped();
				out.println("Chrome was not downloaded: " + Plain.say(e) + ". Pages cannot be captured until Chrome is set:"
						+ " run the installer again when this server can reach googlechromelabs.github.io and storage.googleapis.com, or pass --chrome.");
			}
			catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				s.startCaptureIfStopped();
				throw new IOException("the Chrome download was interrupted", e);
			}
		}
		// what the capture service will use: the download, the given path, the one its settings name, or what is found
		// a path given or already set is kept as written, even before the file is there; otherwise what is found on this system
		Path chrome = pair != null ? pair.chrome() : o.chrome != null ? Paths.get(o.chrome) : !isEmpty(sc.chrome()) ? Paths.get(sc.chrome()) : Preflight.chrome(null);
		String cv = pair != null ? pair.version() : chrome != null && Files.isRegularFile(chrome) ? Preflight.chromeVersion(chrome) : null;
		if(adminSet && o.chrome == null)
		{
			out.println("The capture service's settings already name a Chrome chosen by hand; it is kept and nothing is downloaded.");
		}
		out.println("Chrome:       " + (chrome == null ? "not found (pages cannot be captured until it is installed; README, Chrome)"
				: chrome + (cv == null ? "" : " " + cv) + (Files.isRegularFile(chrome) ? "" : " (not there yet)")));
		Boolean fonts = Preflight.fonts();
		if(Boolean.FALSE.equals(fonts))
		{
			out.println("Fonts:        none found (fc-list); pages render without text. Install fontconfig and a font package.");
		}
		if(!s.bridgeGate.ok())
		{
			out.println(s.bridgeSkipped());
		}
		CaptureSetup.Config cfg = s.captureConfig(chrome);
		boolean captureNeeded = s.captureNeeded(cfg);
		List<Installer.Op> ops = s.planInstall(chosen, props);
		if(!s.persistentPluginDir)
		{
			out.println("Note: this Manager has no persistent plugin folder (WSE before 4.12); the page goes into its webapp and a Manager update removes it.");
		}
		Installer.print(out, ops, o.dryRun);
		if(captureNeeded)
		{
			out.println("  capture service: " + (o.dryRun ? "would install or update " : "install or update ") + CaptureSetup.SERVICE + ", program in " + s.capture.program()
					+ ", settings in " + s.capture.config() + ", running as " + (s.capture.windows() ? CaptureSetup.WINDOWS_ACCOUNT : "the system user " + CaptureSetup.USER));
		}
		if(o.dryRun)
		{
			return 0;
		}
		if(ops.isEmpty() && !fetched && !captureNeeded)
		{
			// folders an earlier run left incomplete and nothing uses any more
			ChromeFolders.cleanIncomplete(s.capture.chromeRoot(), s.configuredChrome(), s.sys);
			out.println("Capture service: " + (CaptureSetup.running(s.capture, s.sys) ? "running." : "not running. " + s.captureSetup().start()));
			nextSteps(out, false, chosen.isEmpty(), null, Preflight.wseRunning(s.home));
			return 0;
		}
		try
		{
			InstallSession.Installed done = s.applyInstall(ops, chosen, cfg);
			out.println("Installed.");
			if(!done.backups().isEmpty())
			{
				out.println("Backups:");
				done.backups().forEach(b -> out.println("  " + b));
			}
			out.println("Capture service: " + (done.serviceProblem() == null ? "running." : done.serviceProblem()));
			ownershipReport(s, out);
			nextSteps(out, true, chosen.isEmpty(), s.touchesBridge(ops) ? ManagerBridge.restartCommand(s.managerHome) : null, Preflight.wseRunning(s.home));
			return 0;
		}
		catch(Installer.Failed f)
		{
			out.println("Failed: " + f.getMessage());
			s.startCaptureIfStopped();
			boolean cleaned = true;
			if(fetched)
			{
				try
				{
					ChromeDownload.deleteTree(pair.dir());
				}
				catch(IOException e)
				{
					cleaned = false;
					out.println("The downloaded Chrome could not be removed: " + Plain.say(e) + ". The next run cleans it up.");
				}
			}
			boolean all = f.rolledBack && cleaned;
			out.println(all ? "Everything this run changed was put back." : "Some changes could not be put back; the log lists them, and the backups are listed above.");
			return all ? 5 : 6;
		}
	}

	private static boolean isEmpty(String s)
	{
		return s == null || s.isBlank();
	}

	static void ownershipReport(InstallSession s, PrintStream out)
	{
		if(!s.own.failures().isEmpty())
		{
			out.println("Could not give these the owner of conf/Server.xml; WSE may not be able to write to them: " + String.join(", ", s.own.failures()));
		}
	}

	private static List<Wse.App> select(Options o, List<Wse.App> apps, PrintStream out, BufferedReader in, boolean interactive) throws IOException
	{
		if(o.allLive)
		{
			return apps.stream().filter(Wse.App::selectable).toList();
		}
		if(o.apps != null)
		{
			List<Wse.App> out2 = new ArrayList<>();
			for(String want: o.apps)
			{
				Wse.App a = byName(apps, want);
				if(a == null)
				{
					StringBuilder b = new StringBuilder("No application '" + want + "'. Applications found:");
					apps.forEach(x -> b.append(" ").append(x.vhost()).append('/').append(x.name()));
					throw new IllegalArgumentException(b.toString());
				}
				if(a.kind().equals("vod"))
				{
					throw new IllegalArgumentException("Application '" + a.name() + "' is not live (StreamType " + a.streamType() + "); the overlay needs a live transcoder.");
				}
				out2.add(a);
			}
			return out2;
		}
		if(!interactive)
		{
			return List.of();
		}
		List<Wse.App> pickable = choices(out, apps);
		if(pickable.isEmpty())
		{
			return List.of();
		}
		out.println();
		out.println("Add URL Overlay to which applications? Type their numbers, such as 1 or 1,3, or \"all\".");
		out.print("Press Enter to skip; it can also be added later from the URL Overlay page in WSE Manager: ");
		out.flush();
		String line = in.readLine();
		if(line == null || line.isBlank() || line.trim().equalsIgnoreCase("none"))
		{
			return List.of();
		}
		if(line.trim().equalsIgnoreCase("all"))
		{
			return pickable;
		}
		List<Wse.App> chosen = new ArrayList<>();
		for(String part: line.split(","))
		{
			int n;
			try
			{
				n = Integer.parseInt(part.trim());
			}
			catch(NumberFormatException e)
			{
				throw new IllegalArgumentException("Not a number: " + part.trim());
			}
			if(n < 1 || n > pickable.size())
			{
				throw new IllegalArgumentException("No application number " + n);
			}
			chosen.add(pickable.get(n - 1));
		}
		return chosen;
	}

	static Wse.App byName(List<Wse.App> apps, String want)
	{
		for(Wse.App a: apps)
		{
			if(want.equals(a.vhost() + "/" + a.name()) || (want.equals(a.name()) && a.vhost().equals("_defaultVHost_")))
			{
				return a;
			}
		}
		Wse.App only = null;
		for(Wse.App a: apps)
		{
			if(want.equals(a.name()))
			{
				if(only != null)
				{
					return null;
				}
				only = a;
			}
		}
		return only;
	}

	/**
	 * The short list the install question shows: the applications that can still take the module, numbered, and the
	 * rest named with why they are not offered. The full table is --list-apps.
	 * @return the numbered applications, in number order
	 */
	static List<Wse.App> choices(PrintStream out, List<Wse.App> apps)
	{
		List<Wse.App> pickable = new ArrayList<>();
		List<String> others = new ArrayList<>();
		for(Wse.App a: apps)
		{
			String name = a.vhost().equals("_defaultVHost_") ? a.name() : a.vhost() + "/" + a.name();
			if(a.kind().equals("vod"))
			{
				others.add(name + " (VOD, cannot carry an overlay)");
			}
			else if(a.installed())
			{
				others.add(name + " (already has it)");
			}
			else
			{
				pickable.add(a);
			}
		}
		if(pickable.isEmpty())
		{
			out.println("Every live application already has URL Overlay" + (others.isEmpty() ? "." : ": " + String.join(", ", others) + "."));
			return pickable;
		}
		out.println("Applications that can have URL Overlay:");
		int n = 0;
		for(Wse.App a: pickable)
		{
			String name = a.vhost().equals("_defaultVHost_") ? a.name() : a.vhost() + "/" + a.name();
			out.println(String.format("  %d  %s%s", ++n, name, a.others().contains("VIF") ? "   (also runs VIF; streams use one or the other)" : ""));
		}
		if(!others.isEmpty())
		{
			out.println("Not offered: " + String.join(", ", others) + ".");
		}
		return pickable;
	}

	static void table(PrintStream out, List<Wse.App> apps)
	{
		out.println(String.format("  %-3s %-14s %-16s %-16s %-11s %-42s %-14s %s", "#", "App", "Vhost", "Type", "Transcoder", "Templates", "Other plugins", "URL Overlay"));
		int n = 0;
		for(Wse.App a: apps)
		{
			boolean pick = !a.kind().equals("vod");
			String type = a.kind().equals("vod") ? a.streamType() + " (VOD)" : a.kind().equals("unknown") ? a.streamType() + " (unknown type)" : a.streamType();
			out.println(String.format("  %-3s %-14s %-16s %-16s %-11s %-42s %-14s %s", pick ? String.valueOf(++n) : "-", a.name(), a.vhost(), type,
					a.transcoder() != null && a.transcoder().contains("transcoder") ? "on" : "off", a.templates() == null ? "" : a.templates(),
					a.others().isEmpty() ? "none" : String.join(", ", a.others()), a.kind().equals("vod") ? "not applicable" : a.installed() ? "yes" : "no"));
		}
	}

	/**
	 * What is still needed after a run, and only that: WSE is started when it is not running (the installer needs it
	 * stopped), and the Manager is restarted only when the bridge changed and nothing restarted it yet.
	 * @param managerRestart the command that restarts the Manager when it still has to, else null
	 */
	static void nextSteps(PrintStream out, boolean changed, boolean noApps, String managerRestart, boolean wseRunning)
	{
		out.println();
		out.println(changed ? "Next:" : "Nothing to change. Next:");
		if(!wseRunning)
		{
			out.println(Preflight.WINDOWS ? "  Start the Wowza Streaming Engine service (Windows Services; its name carries the WSE version)."
					: "  sudo systemctl start WowzaStreamingEngine");
		}
		if(managerRestart != null)
		{
			out.println("  WSE Manager loads the URL Overlay bridge when it starts, so restart it (people signed in to it are signed out): " + managerRestart);
		}
		out.println("  Open WSE Manager, URL Overlay in the top bar. Make an overlay, put it in a template, use the template on a stream.");
		out.println("  The page reaches WSE through the URL Overlay bridge in WSE Manager. A browser on another machine needs only the Manager's port (8088, over HTTPS or an SSH tunnel) and the streaming port (1935, for video).");
		if(noApps)
		{
			out.println("  No application was changed. Add one with: --appName <name>");
		}
	}

	// ---- Remove app -------------------------------------------------------------------------------------------------

	private static int removeApps(Options o, InstallSession s, PrintStream out) throws IOException
	{
		Map<String, Object> man = s.inst.manifest();
		List<Installer.Op> ops = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for(String want: o.removeApps)
		{
			Wse.App a = byName(s.apps, want);
			if(a == null)
			{
				out.println("No application '" + want + "'.");
				return 1;
			}
			Map<String, Object> e = Installer.find(Installer.list(man, "edited"), s.home.relativize(a.xml()).toString().replace('\\', '/'));
			if(e == null)
			{
				out.println(a.name() + ": the installer did not add the module there; nothing to take out.");
				continue;
			}
			ops.addAll(s.inst.planUndoEdit(e));
			ids.add(a.vhost() + "/" + a.name());
		}
		Installer.print(out, ops, o.dryRun);
		if(o.dryRun || ops.isEmpty())
		{
			return 0;
		}
		try
		{
			s.inst.apply(ops, true, m -> {
				List<Object> list = new ArrayList<>(Installer.list(m, "apps"));
				list.removeAll(ids);
				m.put("apps", list);
			});
			out.println("Removed from " + String.join(", ", ids) + ". Restart WSE.");
			return 0;
		}
		catch(Installer.Failed f)
		{
			out.println("Failed: " + f.getMessage());
			return f.rolledBack ? 5 : 6;
		}
	}

	// ---- Uninstall --------------------------------------------------------------------------------------------------

	private static int uninstall(Options o, InstallSession s, PrintStream out) throws IOException
	{
		if(!Files.isRegularFile(Installer.manifestFile(s.home)))
		{
			out.println("No install manifest at " + s.home.resolve(Installer.MANIFEST) + "; nothing to uninstall.");
			return 0;
		}
		List<Installer.Op> ops = s.planUninstall(o.keepTemplates, o.purge);
		List<Path> downloads = s.downloads();
		if(!o.dryRun)
		{
			InstallSession.Problem p = s.changeBlocker(ops, "uninstall");
			if(p != null)
			{
				out.println(p.message());
				return p.exit();
			}
		}
		Installer.print(out, ops, o.dryRun);
		for(Path d: downloads)
		{
			out.println("  " + (o.dryRun ? "would delete" : "delete") + "  " + d + "  (downloaded Chrome for Testing)");
		}
		if(o.dryRun)
		{
			return 0;
		}
		try
		{
			InstallSession.Uninstalled u = s.applyUninstall(ops);
			for(String n: u.notDeleted())
			{
				out.println("Could not delete " + n + "; delete it by hand.");
			}
			for(Path root: u.keptOverlays())
			{
				out.println("Kept the saved overlays in " + root + ". Uninstall again with --purge to delete them.");
			}
			out.println("Uninstalled. Backups are kept; delete them when you no longer need them. Restart WSE and the Manager.");
			if(s.touchesBridge(ops))
			{
				out.println("The Manager keeps the bridge loaded until it restarts: " + ManagerBridge.restartCommand(s.managerHome));
			}
			return 0;
		}
		catch(InstallSession.Blocked b)
		{
			out.println(b.problem.message());
			return b.problem.exit();
		}
		catch(Installer.Failed f)
		{
			out.println("Failed: " + f.getMessage());
			return f.rolledBack ? 5 : 6;
		}
	}

	static void deleteEmptyTree(Path dir)
	{
		if(dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		try(var s = Files.list(dir))
		{
			for(Path p: (Iterable<Path>)s::iterator)
			{
				if(Files.isDirectory(p))
				{
					deleteEmptyTree(p);
				}
			}
		}
		catch(IOException e)
		{
			return;
		}
		Installer.deleteEmptyDirs(dir);
	}

	// ---- Verify -----------------------------------------------------------------------------------------------------

	private static int verify(InstallSession s, PrintStream out) throws IOException
	{
		Map<String, Object> man = s.inst.manifest();
		if(!Files.isRegularFile(Installer.manifestFile(s.home)))
		{
			out.println("Not installed by this installer (no manifest).");
		}
		else
		{
			out.println("Installed version " + man.get("version") + ", updated " + man.get("updated"));
			for(Map<String, Object> e: Installer.list(man, "edited"))
			{
				Path p = s.home.resolve(String.valueOf(e.get("path")));
				String now = Files.exists(p) ? TextFile.sha256(p) : null;
				out.println("  " + e.get("path") + ": " + (now == null ? "MISSING" : now.equals(e.get("installed")) ? "as installed" : "changed since the install"));
			}
			int missing = 0;
			for(Map<String, Object> a: Installer.list(man, "added"))
			{
				if(!Files.exists(s.home.resolve(String.valueOf(a.get("path")))))
				{
					missing++;
					out.println("  missing: " + a.get("path"));
				}
			}
			out.println("  " + Installer.list(man, "added").size() + " files added, " + missing + " missing");
		}
		table(out, s.apps);
		return 0;
	}

	/** Writes to the console and to the log buffer. */
	static final class Tee extends OutputStream
	{
		private final OutputStream a, b;

		Tee(OutputStream a, OutputStream b)
		{
			this.a = a;
			this.b = b;
		}

		@Override
		public void write(int c) throws IOException
		{
			a.write(c);
			b.write(c);
		}

		@Override
		public void write(byte[] buf, int off, int len) throws IOException
		{
			a.write(buf, off, len);
			b.write(buf, off, len);
		}

		@Override
		public void flush() throws IOException
		{
			a.flush();
			b.flush();
		}
	}

	private Main()
	{
	}
}
