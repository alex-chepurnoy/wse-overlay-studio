/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.install;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wowza.wms.plugin.urloverlay.config.ConfigEdits;
import com.wowza.wms.plugin.urloverlay.config.TextFile;

/**
 * One install, uninstall or remove-app against one WSE install, in the steps both the command line (Main) and the
 * window-based setup (SetupWindow) take: read the install, decide about Chrome, check that the run can go ahead, plan,
 * apply. Each step prints to the session's output; none of them asks a question.
 */
final class InstallSession
{
	/** Default values of the server-level settings; existing values are always kept. */
	static final Map<String, String> DEFAULTS = Map.of("UrlOverlayServer", "authorized", "UrlOverlayAllowPrivateNetworks", "false", "UrlOverlayBlockChromeServices",
			"true");

	final Path home;
	final String wseVersion;
	final Payload payload;
	final List<Wse.App> apps;
	final Path pluginDir;
	final boolean persistentPluginDir;
	/** The Manager's folder, and whether the REST bridge can go into it. */
	final Path managerHome;
	final ManagerBridge.Gate bridgeGate;
	final Ownership own;
	final Installer inst;
	final PrintStream out;
	/** Where the capture service, its Chrome and its configuration go, and the system it is set up on. */
	final CaptureSetup.Layout capture;
	final CaptureSetup.Sys sys;

	private InstallSession(Path home, Path wsemHome, PrintStream out) throws IOException
	{
		this.home = home;
		this.out = out;
		this.wseVersion = Wse.version(home);
		this.payload = new Payload();
		this.apps = Wse.apps(home);
		Map.Entry<Path, Boolean> plugin = Wse.pluginDir(home, wsemHome);
		this.pluginDir = plugin.getKey();
		this.persistentPluginDir = plugin.getValue();
		this.managerHome = wsemHome != null ? wsemHome : home.resolve("manager");
		this.bridgeGate = ManagerBridge.check(managerHome);
		this.own = Ownership.forHome(home);
		this.inst = new Installer(home, payload, out, own);
		this.capture = CaptureSetup.layout();
		this.sys = CaptureSetup.sys();
	}

	static InstallSession open(Path home, Path wsemHome, PrintStream out) throws IOException
	{
		return new InstallSession(home, wsemHome, out);
	}

	boolean supported()
	{
		return Wse.supported(wseVersion);
	}

	/** Applications the module can still be added to: live, and without it. */
	List<Wse.App> offerable()
	{
		return apps.stream().filter(a -> !a.kind().equals("vod") && !a.installed()).toList();
	}

	// ---- Chrome and the capture service ------------------------------------------------------------------------------

	/**
	 * The Chrome the capture service's configuration names now. One outside the folder this installer downloads into is
	 * an admin's choice and is kept; one inside it is this installer's own.
	 */
	record ServerChrome(String chrome, String driver, boolean own)
	{
		/** True when an admin named a chromedriver: nothing is downloaded then. */
		boolean set()
		{
			return driver != null && !driver.isEmpty() && !own;
		}
	}

	ServerChrome serverChrome() throws IOException
	{
		if(!Files.isRegularFile(capture.config()))
		{
			return new ServerChrome(null, null, false);
		}
		java.util.Properties p = new java.util.Properties();
		try(var in = Files.newBufferedReader(capture.config(), java.nio.charset.StandardCharsets.UTF_8))
		{
			p.load(in);
		}
		String chrome = p.getProperty("chrome"), driver = p.getProperty("chromedriver");
		// settings naming programs that are not there (a setup program's removal deleted them) mean no Chrome at all
		if(!exists(chrome) || !exists(driver))
		{
			return new ServerChrome(null, null, false);
		}
		// this installer's own: under the Chrome folder in use now, or where an earlier version kept it
		List<Path> roots = new ArrayList<>(List.of(capture.chromeRoot()));
		Map<String, Object> rec = captureRecord();
		if(rec != null && rec.get("chromeRoot") != null)
		{
			roots.add(Path.of(String.valueOf(rec.get("chromeRoot"))));
		}
		Path d = Path.of(driver).toAbsolutePath().normalize();
		boolean own = roots.stream().anyMatch(r -> d.startsWith(r.toAbsolutePath().normalize()));
		return new ServerChrome(chrome, driver, own);
	}

	private static boolean exists(String path)
	{
		try
		{
			return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path.trim()));
		}
		catch(RuntimeException e)
		{
			return false;
		}
	}

	/** The Server.xml properties to add when absent: the defaults and where the module finds the capture service. */
	Map<String, String> properties()
	{
		Map<String, String> props = new LinkedHashMap<>();
		for(String k: List.of("UrlOverlayServer", "UrlOverlayAllowPrivateNetworks", "UrlOverlayBlockChromeServices"))
		{
			props.put(k, DEFAULTS.get(k));
		}
		props.put("UrlOverlayCaptureSocket", capture.socket().toString());
		return props;
	}

	/**
	 * The capture service's settings: this Chrome, and the limits its settings file already holds (an admin may have
	 * changed them), or the defaults: four browsers at once, 1024 MB each.
	 */
	CaptureSetup.Config captureConfig(Path chrome, Path driver) throws IOException
	{
		java.util.Properties p = new java.util.Properties();
		if(Files.isRegularFile(capture.config()))
		{
			try(var in = Files.newBufferedReader(capture.config(), java.nio.charset.StandardCharsets.UTF_8))
			{
				p.load(in);
			}
		}
		return new CaptureSetup.Config(chrome, driver, number(p.getProperty("maxCaptures"), 4), number(p.getProperty("maxBrowserMemoryMB"), 1024));
	}

	private static int number(String v, int fallback)
	{
		try
		{
			return v == null ? fallback : Math.max(1, Integer.parseInt(v.trim()));
		}
		catch(NumberFormatException e)
		{
			return fallback;
		}
	}

	CaptureSetup captureSetup()
	{
		return new CaptureSetup(capture, sys, home, payload, out);
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> captureRecord() throws IOException
	{
		Object r = inst.manifest().get("capture");
		return r instanceof Map ? (Map<String, Object>)r : null;
	}

	private boolean stoppedForChrome;

	/** The Chrome capture.properties names now, or null. */
	Path configuredChrome() throws IOException
	{
		ServerChrome sc = serverChrome();
		return sc.chrome() == null || sc.chrome().isBlank() ? null : Path.of(sc.chrome());
	}

	/** Before a Chrome folder is changed: the service is stopped and its Chrome gone (bounded wait, then killed). */
	void stopCaptureForChrome()
	{
		try
		{
			if(captureRecord() == null && !CaptureSetup.running(capture, sys))
			{
				return;
			}
		}
		catch(IOException e)
		{
			// stop it anyway
		}
		out.println("Stopping the capture service before its Chrome folder changes...");
		List<Long> left = CaptureSetup.stopAndWait(capture, sys);
		if(!left.isEmpty())
		{
			out.println("  " + left.size() + " Chrome process(es) still run from " + capture.chromeRoot() + "; their folder is left alone.");
		}
		stoppedForChrome = true;
	}

	/** After a failure: the service runs again if this run stopped it. */
	void startCaptureIfStopped()
	{
		if(stoppedForChrome && !CaptureSetup.running(capture, sys))
		{
			CaptureSetup.startAgain(capture.windows(), sys);
		}
		stoppedForChrome = false;
	}

	/** The Chrome folder the capture service's record names, which uninstall deletes; empty without a record. */
	List<Path> downloads() throws IOException
	{
		Map<String, Object> r = captureRecord();
		return r == null || r.get("chromeRoot") == null ? List.of() : List.of(Path.of(String.valueOf(r.get("chromeRoot"))));
	}

	/** True when the capture service has to be installed or updated. */
	boolean captureNeeded(CaptureSetup.Config c) throws IOException
	{
		return captureSetup().needed(c, captureRecord());
	}

	// ---- checks -----------------------------------------------------------------------------------------------------

	/** A reason the run cannot go ahead, and its exit code. */
	record Problem(String message, int exit)
	{
	}

	/**
	 * What stops an install, checked before anything is downloaded or written: folders that cannot be written, a WSE
	 * that is running (Linux, where it does not lock its jars) and module or dependency jars a running WSE holds open
	 * (Windows). Null when the install can go ahead.
	 */
	Problem installBlocker(List<Wse.App> chosen, boolean download) throws IOException
	{
		List<Path> dirs = new ArrayList<>(List.of(home.resolve("conf"), home.resolve("lib"), home.resolve("transcoder").resolve("templates"), pluginDir));
		for(Path v: Wse.vhosts(home).values())
		{
			dirs.add(v.resolve("conf"));
		}
		for(Wse.App a: chosen)
		{
			dirs.add(a.xml().getParent());
		}
		dirs.add(capture.chromeRoot());
		dirs.add(capture.program());
		dirs.add(capture.configDir());
		if(capture.unit() != null)
		{
			dirs.add(capture.unit().getParent());
		}
		if(bridgeGate.ok())
		{
			dirs.add(ManagerBridge.classDir(managerHome));
		}
		List<String> bad = Preflight.notWritable(dirs);
		if(!bad.isEmpty())
		{
			return new Problem("Cannot write to: " + String.join(", ", bad) + ". Run as Administrator (Windows) or as root with sudo (Linux).", 3);
		}
		Problem running = running();
		if(running != null)
		{
			return running;
		}
		// the jars this install would replace or remove, planned without output and without the Chrome paths
		Installer quiet = new Installer(home, payload, new PrintStream(OutputStream.nullOutputStream()), Ownership.NONE);
		List<Installer.Op> planned = new ArrayList<>(quiet.planInstall(chosen, properties(), pluginDir));
		planned.addAll(quiet.planBridge(ManagerBridge.temp(managerHome), bridgeGate.ok()));
		for(Installer.Op op: planned)
		{
			if(jarInUse(op))
			{
				return new Problem(op.path.getFileName() + " is in use: WSE is running. Stop the Wowza Streaming Engine service and run the installer again.", 3);
			}
		}
		return null;
	}

	/** What stops an uninstall or a remove-app: a running WSE, or a jar it holds open. */
	Problem changeBlocker(List<Installer.Op> ops, String again)
	{
		Problem running = running();
		if(running != null)
		{
			return running;
		}
		for(Installer.Op op: ops)
		{
			if(jarInUse(op))
			{
				return new Problem(op.path.getFileName() + " is in use: WSE is running. Stop the Wowza Streaming Engine service and run the " + again + " again.", 3);
			}
		}
		return null;
	}

	private Problem running()
	{
		if(Preflight.wseRunning(home))
		{
			return new Problem("Wowza Streaming Engine is running from " + home + ". Stop it first (Linux: sudo systemctl stop WowzaStreamingEngine;"
					+ " Windows: the Wowza Streaming Engine service), then run this again.", 3);
		}
		return null;
	}

	private static boolean jarInUse(Installer.Op op)
	{
		String n = op.path.getFileName().toString();
		boolean jar = n.endsWith(".jar") && op.path.getParent() != null && op.path.getParent().getFileName().toString().equals("lib");
		return jar && (op.kind.equals("retire") || op.kind.equals("add") || op.kind.equals("delete")) && Preflight.inUse(op.path);
	}

	// ---- plan and apply ---------------------------------------------------------------------------------------------

	/** The install plan, with the Manager bridge when this Manager can take it. */
	List<Installer.Op> planInstall(List<Wse.App> chosen, Map<String, String> props) throws IOException
	{
		return plan(inst, chosen, props);
	}

	/** As planInstall, printing nothing. */
	List<Installer.Op> planQuietly(List<Wse.App> chosen, Map<String, String> props) throws IOException
	{
		return plan(new Installer(home, payload, new PrintStream(OutputStream.nullOutputStream()), Ownership.NONE), chosen, props);
	}

	private List<Installer.Op> plan(Installer i, List<Wse.App> chosen, Map<String, String> props) throws IOException
	{
		List<Installer.Op> ops = new ArrayList<>(i.planInstall(chosen, props, pluginDir));
		ops.addAll(i.planBridge(ManagerBridge.temp(managerHome), bridgeGate.ok()));
		return ops;
	}

	/** True when the plan adds, replaces or removes a bridge file, so the Manager must restart. */
	boolean touchesBridge(List<Installer.Op> ops)
	{
		Path dir = ManagerBridge.classDir(managerHome).toAbsolutePath().normalize();
		return ops.stream().anyMatch(o -> o.path.toAbsolutePath().normalize().startsWith(dir));
	}

	/** What the CLI and the setup window say when the bridge is left out. */
	String bridgeSkipped()
	{
		return "The Manager bridge was not installed: this WSE Manager is not one it was built for (" + bridgeGate.reason() + "). The URL Overlay page "
				+ "then reaches WSE only in a browser on the server itself, or over an SSH tunnel or VPN to it.";
	}

	// the bridge's package folders under manager/temp that do not exist yet, deepest first
	private List<Path> missingBridgeDirs()
	{
		List<Path> out = new ArrayList<>();
		Path temp = ManagerBridge.temp(managerHome).toAbsolutePath().normalize();
		for(Path d = ManagerBridge.classDir(managerHome).toAbsolutePath().normalize(); d != null && !d.equals(temp) && d.startsWith(temp); d = d.getParent())
		{
			if(!Files.exists(d))
			{
				out.add(d);
			}
		}
		return out;
	}

	/** What an install did beyond its file changes: whether the capture service runs, in words when it does not. */
	record Installed(List<String> backups, boolean captureChanged, String serviceProblem)
	{
	}

	/**
	 * Applies an install plan: first the capture service (program, Chrome's access, configuration, account and service,
	 * not yet started), recorded in the manifest before it starts; then the WSE changes. A failure of either puts back
	 * both. Last, the service is started, and whether it runs is reported, not treated as a failed install.
	 */
	Installed applyInstall(List<Installer.Op> ops, List<Wse.App> chosen, CaptureSetup.Config c) throws Installer.Failed, IOException
	{
		// the bridge's package folders this run creates, so uninstall takes out exactly those
		List<Path> created = touchesBridge(ops) ? missingBridgeDirs() : List.of();
		CaptureSetup cs = captureSetup();
		Map<String, Object> before = captureRecord();
		boolean captureChanged = cs.needed(c, before);
		if(captureChanged)
		{
			Plain.changed = true;
			try
			{
				cs.install(c);
			}
			catch(CaptureSetup.Failed f)
			{
				throw new Installer.Failed(f.getMessage(), f, f.undone);
			}
			// the Chrome this installer downloaded: recorded as installed now, so the Server view shows its age
			Path root = capture.chromeRoot().toAbsolutePath().normalize();
			if(c.chrome() != null && c.chrome().toAbsolutePath().normalize().startsWith(root) && !c.chrome().toAbsolutePath().normalize().equals(root))
			{
				ChromeUpdate.recordInstalled(capture, sys, root.relativize(c.chrome().toAbsolutePath().normalize()).getName(0).toString());
			}
		}
		Map<String, Object> rec = captureChanged ? merged(before, cs.record()) : before;
		List<String> backups;
		try
		{
			Plain.changed = true;
			backups = inst.apply(ops, true, man -> {
				List<Object> dirs = new ArrayList<>(Installer.list(man, "createdDirs"));
				for(Path d: created)
				{
					String rel = home.relativize(d).toString().replace('\\', '/');
					if(!dirs.contains(rel))
					{
						dirs.add(rel);
					}
				}
				if(!dirs.isEmpty())
				{
					man.put("createdDirs", dirs);
				}
				if(rec != null)
				{
					man.put("capture", rec);
				}
				List<Object> list = new ArrayList<>(Installer.list(man, "apps"));
				for(Wse.App a: chosen)
				{
					String id = a.vhost() + "/" + a.name();
					if(!list.contains(id))
					{
						list.add(id);
					}
				}
				man.put("apps", list);
			});
		}
		catch(Installer.Failed f)
		{
			boolean undone = !captureChanged || cs.undo();
			throw new Installer.Failed(f.getMessage(), f, f.rolledBack && undone);
		}
		Setup.recordHome(home);
		String problem = captureChanged || stoppedForChrome || !CaptureSetup.running(capture, sys) ? cs.start() : null;
		stoppedForChrome = false;
		// folders an earlier run left incomplete, now that nothing uses them, and the program and Chrome where an earlier
		// version kept them (on Windows, inside the setup program's folder)
		ChromeFolders.cleanIncomplete(capture.chromeRoot(), c.chrome(), sys);
		if(before != null)
		{
			for(String k: List.of("program", "chromeRoot"))
			{
				Object old = before.get(k);
				Path now = k.equals("program") ? capture.program() : capture.chromeRoot();
				if(old != null && !Path.of(String.valueOf(old)).toAbsolutePath().normalize().equals(now.toAbsolutePath().normalize()))
				{
					try
					{
						ChromeDownload.deleteTree(Path.of(String.valueOf(old)));
						Installer.deleteEmptyDirs(Path.of(String.valueOf(old)).getParent());
					}
					catch(IOException e)
					{
						out.println("The earlier " + (k.equals("program") ? "program" : "Chrome") + " folder " + old + " could not be removed: " + Plain.say(e));
					}
				}
			}
		}
		return new Installed(backups, captureChanged, problem);
	}

	// a user created by an earlier run stays recorded as created by this installer, so uninstall still removes it
	private static Map<String, Object> merged(Map<String, Object> before, Map<String, Object> now)
	{
		Map<String, Object> m = new LinkedHashMap<>(now);
		if(before != null)
		{
			for(String k: List.of("createdUser", "groupMember", "createdService", "apparmor", "unit"))
			{
				if(before.get(k) != null && m.get(k) == null)
				{
					m.put(k, before.get(k));
				}
			}
		}
		return m;
	}

	/** The result of an uninstall: what was left behind and could not be deleted, for the report. */
	record Uninstalled(List<String> notDeleted, List<Path> keptOverlays)
	{
	}

	List<Installer.Op> planUninstall(boolean keepTemplates, boolean purge) throws IOException
	{
		return inst.planUninstall(keepTemplates, purge);
	}

	/**
	 * Applies an uninstall plan: the capture service is stopped first, so nothing it runs holds a file; then the WSE
	 * changes (the service starts again if they fail); then the service and everything recorded with it are removed. What
	 * cannot be removed is reported, not treated as a failed uninstall.
	 */
	Uninstalled applyUninstall(List<Installer.Op> ops) throws Installer.Failed, IOException, Blocked
	{
		Map<String, Object> rec = captureRecord();
		List<Path> createdDirs = new ArrayList<>();
		for(Object d: Installer.list(inst.manifest(), "createdDirs"))
		{
			createdDirs.add(home.resolve(String.valueOf(d)));
		}
		if(rec != null)
		{
			CaptureSetup.stop(rec, sys);
		}
		try
		{
			Plain.changed = true;
			inst.apply(ops, false, null);
		}
		catch(Installer.Failed f)
		{
			if(rec != null)
			{
				CaptureSetup.startAgain(rec, sys);
			}
			throw f;
		}
		// deepest first, and only when empty: a folder something else also uses stays
		createdDirs.sort(java.util.Comparator.comparingInt(Path::getNameCount).reversed());
		for(Path d: createdDirs)
		{
			Installer.deleteEmptyDirs(d);
		}
		Main.deleteEmptyTree(pluginDir);
		List<String> notDeleted = rec == null ? new ArrayList<>() : new ArrayList<>(CaptureSetup.uninstall(rec, sys, out));
		List<Path> kept = new ArrayList<>();
		for(Path vhostDir: Wse.vhosts(home).values())
		{
			Path root = vhostDir.resolve(Installer.MODULE_DIR);
			Main.deleteEmptyTree(root);
			Installer.deleteEmptyDirs(root.getParent());
			if(Files.isDirectory(root))
			{
				kept.add(root);
			}
		}
		Setup.forgetHome(home);
		return new Uninstalled(notDeleted, kept);
	}

	/** A check failed before anything was changed. */
	static final class Blocked extends Exception
	{
		final Problem problem;

		Blocked(Problem p)
		{
			super(p.message());
			this.problem = p;
		}
	}
}
