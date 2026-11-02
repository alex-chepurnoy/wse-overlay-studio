/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wowza.wms.plugin.overlaystudio.config.TextFile;
import com.wowza.wms.plugin.overlaystudio.config.ConfigEdits;

/**
 * Install, uninstall and remove-app (docs/DESIGN.md, Installer). Every change is planned first, as the bytes each
 * file will hold; a dry run prints the plan. A real run first writes the manifest with everything it is about to
 * change (marked pending), so a run that is killed partway can still be undone; then it backs a file up before its
 * first write, applies the plan in order, and on any failure, or when the process is told to stop, puts back what it
 * wrote. A second run changes nothing, and uninstall restores original bytes, or takes out only its own lines when
 * something else has edited the file since.
 */
final class Installer
{
	/** The module's folder under conf.modules, beside VIF's. */
	static final String MODULE_DIR = "conf.modules/overlaystudio";
	static final String MANIFEST = MODULE_DIR + "/install-manifest.json";
	static final String MODULE_JAR_PREFIX = "wse-plugin-overlay-studio-";

	/** Tests only: called after each applied change, with its index, to stand in for a kill or a stop request there. */
	static volatile java.util.function.BiConsumer<Installer, Integer> testAfterOp;
	/** Tests only: runs after a new manifest is written to its temporary file and before it replaces the old one. */
	static volatile Runnable testBeforeManifestMove;

	private final Path home;
	private final Payload payload;
	private final PrintStream out;
	private final Ownership own;
	private final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
	/** Held while one change is written, so a stop request rolls back between changes, never inside one. */
	private final Object lock = new Object();
	/** While apply runs: rolls back and marks the run stopped; what the shutdown hook calls. */
	private volatile Runnable stopRequest;

	Installer(Path home, Payload payload, PrintStream out)
	{
		this(home, payload, out, Ownership.NONE);
	}

	Installer(Path home, Payload payload, PrintStream out, Ownership own)
	{
		this.home = home;
		this.payload = payload;
		this.out = out;
		this.own = own;
	}

	/** One planned change. */
	static final class Op
	{
		final String kind; // edit, add, retire, delete, restore
		final Path path;
		final byte[] bytes;
		final List<ConfigEdits.Change> changes;
		final String note;

		Op(String kind, Path path, byte[] bytes, List<ConfigEdits.Change> changes, String note)
		{
			this.kind = kind;
			this.path = path;
			this.bytes = bytes;
			this.changes = changes;
			this.note = note;
		}
	}

	static final class Failed extends Exception
	{
		final boolean rolledBack;

		Failed(String message, Throwable cause, boolean rolledBack)
		{
			super(message, cause);
			this.rolledBack = rolledBack;
		}
	}

	// ---- manifest ---------------------------------------------------------------------------------------------------

	static Path manifestFile(Path home)
	{
		return home.resolve(MANIFEST);
	}

	@SuppressWarnings("unchecked")
	Map<String, Object> manifest() throws IOException
	{
		Path f = manifestFile(home);
		if(!Files.isRegularFile(f))
		{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("edited", new ArrayList<>());
			m.put("added", new ArrayList<>());
			m.put("apps", new ArrayList<>());
			return m;
		}
		return (Map<String, Object>)Json.parse(Files.readString(f, StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> list(Map<String, Object> m, String key)
	{
		return (List<Map<String, Object>>)(List<?>)m.computeIfAbsent(key, k -> new ArrayList<>());
	}

	private String rel(Path p)
	{
		return home.relativize(p).toString().replace('\\', '/');
	}

	private Path abs(Object rel)
	{
		return home.resolve(String.valueOf(rel));
	}

	static Map<String, Object> find(List<Map<String, Object>> l, String path)
	{
		for(Map<String, Object> e: l)
		{
			if(path.equals(e.get("path")))
			{
				return e;
			}
		}
		return null;
	}

	// ---- planning: install ------------------------------------------------------------------------------------------

	/**
	 * @param apps applications to add the module to, as vhost/app
	 * @param serverProps Server.xml properties to add when absent
	 */
	List<Op> planInstall(List<Wse.App> apps, Map<String, String> serverProps, Path pluginDir) throws IOException
	{
		List<Op> ops = new ArrayList<>();
		Map<String, Object> man = manifest();
		List<Map<String, Object>> added = list(man, "added");

		Path server = home.resolve("conf").resolve("Server.xml");
		TextFile sf = TextFile.read(server);
		ConfigEdits.Result sr = ConfigEdits.server(sf, serverProps);
		if(!sr.changes().isEmpty())
		{
			ops.add(new Op("edit", server, sr.file().bytes(), sr.changes(), "Server.xml"));
		}
		for(Wse.App a: apps)
		{
			ConfigEdits.Result ar = ConfigEdits.application(TextFile.read(a.xml()));
			if(!ar.changes().isEmpty())
			{
				ops.add(new Op("edit", a.xml(), ar.file().bytes(), ar.changes(), a.vhost() + "/" + a.name() + "/Application.xml"));
			}
		}

		// lib: the module jar replaces any other version; a dependency replaces its own older version and is skipped when WSE
		// has that artifact itself at any version; a dependency this installer added that the new version no longer carries goes
		Path lib = home.resolve("lib");
		java.util.Set<String> payloadJars = new java.util.HashSet<>();
		java.util.Set<Path> going = new java.util.HashSet<>();
		for(String p: payload.under("lib/"))
		{
			payloadJars.add(p.substring(4));
		}
		for(String p: payload.under("lib/"))
		{
			String name = p.substring(4);
			byte[] b = payload.bytes(p);
			Path target = lib.resolve(name);
			if(name.startsWith(MODULE_JAR_PREFIX))
			{
				for(Path old: jars(lib))
				{
					String n = old.getFileName().toString();
					if(n.startsWith(MODULE_JAR_PREFIX) && !n.equals(name) && !n.startsWith("wse-plugin-overlay-studio-installer"))
					{
						// one this installer put there is replaced outright (kept in memory for a rollback); any other is
						// set aside as a backup, since someone else placed it
						ops.add(new Op(ours(added, old) ? "delete" : "retire", old, null, null, "older module jar " + n));
						going.add(old);
					}
				}
				addIfChanged(ops, target, b, "module jar", added, true);
				continue;
			}
			String artifact = artifact(name);
			if(!Files.exists(target))
			{
				boolean wseHasIt = false;
				for(Path other: jars(lib))
				{
					String n = other.getFileName().toString();
					if(!n.equals(name) && !payloadJars.contains(n) && artifact(n).equals(artifact))
					{
						if(ours(added, other))
						{
							ops.add(new Op("delete", other, null, null, "older dependency " + n));
							going.add(other);
						}
						else
						{
							wseHasIt = true;
						}
					}
				}
				if(wseHasIt)
				{
					out.println("  lib: " + name + " skipped, WSE already has " + artifact);
					continue;
				}
			}
			addIfChanged(ops, target, b, "dependency " + name, added, false);
		}
		for(Map<String, Object> a: added)
		{
			String rel = String.valueOf(a.get("path"));
			Path p = abs(rel);
			String n = p.getFileName().toString();
			if(rel.startsWith("lib/") && n.endsWith(".jar") && !n.startsWith(MODULE_JAR_PREFIX) && !payloadJars.contains(n) && !going.contains(p)
					&& Files.isRegularFile(p) && ours(added, p))
			{
				ops.add(new Op("delete", p, null, null, "dependency no longer used " + n));
			}
		}

		// starter templates: only when absent
		Path templates = home.resolve("transcoder").resolve("templates");
		for(String p: payload.under("templates/"))
		{
			Path target = templates.resolve(p.substring(10));
			if(!Files.exists(target))
			{
				ops.add(new Op("add", target, payload.bytes(p), null, "starter template " + target.getFileName()));
			}
		}

		// the Manager plugin page: ours, so replaced when it differs, and a page file the new version no longer carries removed
		java.util.Set<Path> pageFiles = new java.util.HashSet<>();
		for(String p: payload.under("ui/"))
		{
			Path target = pluginDir.resolve(p.substring(3));
			pageFiles.add(target.toAbsolutePath().normalize());
			addIfChanged(ops, target, payload.bytes(p), "plugin page " + p.substring(3), added, true);
		}
		Path page = pluginDir.toAbsolutePath().normalize();
		for(Map<String, Object> a: added)
		{
			Path p = abs(a.get("path")).toAbsolutePath().normalize();
			if(p.startsWith(page) && !pageFiles.contains(p) && Files.isRegularFile(p) && ours(added, p))
			{
				ops.add(new Op("delete", p, null, null, "plugin page file no longer used " + page.relativize(p)));
			}
		}
		return ops;
	}

	private static List<Path> jars(Path lib) throws IOException
	{
		if(!Files.isDirectory(lib))
		{
			return List.of();
		}
		try(var s = Files.list(lib))
		{
			return s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
		}
	}

	/** A jar's name without its version: selenium-api-4.21.0.jar is selenium-api. */
	static String artifact(String jar)
	{
		return jar.replaceAll("-\\d[\\w.\\-]*\\.jar$", "").replaceAll("\\.jar$", "");
	}

	/** True when this installer added the file and it still holds what it wrote. */
	private boolean ours(List<Map<String, Object>> added, Path p) throws IOException
	{
		Map<String, Object> mine = find(added, rel(p));
		return mine != null && TextFile.sha256(p).equals(mine.get("sha256"));
	}

	/**
	 * The Manager bridge's class files into &lt;manager&gt;/temp (ManagerBridge), replacing an older bridge this installer put
	 * there; or, when this Manager is not one the bridge was built for, taking out a bridge this installer put there before.
	 */
	List<Op> planBridge(Path managerTemp, boolean install) throws IOException
	{
		List<Op> ops = new ArrayList<>();
		List<Map<String, Object>> added = list(manifest(), "added");
		java.util.Set<Path> carried = new java.util.HashSet<>();
		if(install)
		{
			for(String p: payload.under(ManagerBridge.PAYLOAD))
			{
				Path target = managerTemp.resolve(p.substring(ManagerBridge.PAYLOAD.length()));
				carried.add(target.toAbsolutePath().normalize());
				addIfChanged(ops, target, payload.bytes(p), "Manager bridge " + target.getFileName(), added, true);
			}
		}
		Path dir = managerTemp.resolve(ManagerBridge.PACKAGE).toAbsolutePath().normalize();
		for(Map<String, Object> a: added)
		{
			Path p = abs(a.get("path")).toAbsolutePath().normalize();
			if(p.startsWith(dir) && !carried.contains(p) && Files.isRegularFile(p) && ours(added, p))
			{
				ops.add(new Op("delete", p, null, null, install ? "Manager bridge file no longer used" : "Manager bridge, not for this Manager"));
			}
		}
		return ops;
	}

	/** Every file in the module's folders under conf.modules, except the manifest, which apply handles. */
	List<Op> planPurge() throws IOException
	{
		List<Op> ops = new ArrayList<>();
		Path manifest = home.resolve(MANIFEST);
		for(Path vhostDir: Wse.vhosts(home).values())
		{
			Path root = vhostDir.resolve(MODULE_DIR);
			if(!Files.isDirectory(root))
			{
				continue;
			}
			try(var s = Files.walk(root))
			{
				for(Path p: (Iterable<Path>)s.sorted()::iterator)
				{
					if(Files.isRegularFile(p) && !p.equals(manifest))
					{
						ops.add(new Op("delete", p, null, null, "saved overlay file (--purge)"));
					}
				}
			}
		}
		return ops;
	}

	// adds a file, or replaces it when the installer added it before (or replaceAnyway) and the bytes differ
	private void addIfChanged(List<Op> ops, Path target, byte[] b, String note, List<Map<String, Object>> added, boolean ours) throws IOException
	{
		if(Files.exists(target))
		{
			if(TextFile.sha256(target).equals(TextFile.sha256(b)))
			{
				return;
			}
			if(!ours && find(added, rel(target)) == null)
			{
				out.println("  " + note + ": a different " + target.getFileName() + " is already there; left as it is");
				return;
			}
		}
		ops.add(new Op("add", target, b, null, note));
	}

	// ---- planning: uninstall and remove-app -------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	List<Op> planUndoEdit(Map<String, Object> e) throws IOException
	{
		List<Op> ops = new ArrayList<>();
		Path p = abs(e.get("path"));
		if(!Files.exists(p))
		{
			out.println("  " + e.get("path") + ": gone, nothing to restore");
			return ops;
		}
		String now = TextFile.sha256(p);
		Path backup = abs(e.get("backup"));
		boolean clean = now.equals(e.get("installed")) && !Boolean.TRUE.equals(e.get("editedByOthers"));
		if(clean && Files.isRegularFile(backup) && TextFile.sha256(backup).equals(e.get("original")))
		{
			ops.add(new Op("restore", p, Files.readAllBytes(backup), null, e.get("path") + ": restored from " + backup.getFileName() + ", original bytes"));
			return ops;
		}
		if(((List<?>)e.getOrDefault("changes", List.of())).isEmpty())
		{
			// a template whose overlays the page switched on in place: no surgical undo, only the backup
			out.println("  " + e.get("path") + ": kept, it changed after Overlay Studio edited it; the original is in " + e.get("backup"));
			return ops;
		}
		Map.Entry<TextFile, List<String>> u = ConfigEdits.undo(TextFile.read(p), (List<Map<String, Object>>)(List<?>)e.get("changes"));
		String why = clean ? "its backup is missing or changed"
				: now.equals(e.get("installed")) ? "something else edited it between two installs" : "it was edited after the install";
		ops.add(new Op("restore", p, u.getKey().bytes(), null, e.get("path") + ": surgically reverted (" + why + ")"
				+ (u.getValue().isEmpty() ? "" : "; " + String.join("; ", u.getValue()))));
		return ops;
	}

	List<Op> planUninstall(boolean keepTemplates, boolean purge) throws IOException
	{
		List<Op> ops = new ArrayList<>();
		if(purge)
		{
			ops.addAll(planPurge());
		}
		Map<String, Object> man = manifest();
		for(Map<String, Object> e: list(man, "edited"))
		{
			ops.addAll(planUndoEdit(e));
		}
		for(Map<String, Object> a: list(man, "added"))
		{
			Path p = abs(a.get("path"));
			if(!Files.exists(p))
			{
				continue;
			}
			boolean template = String.valueOf(a.get("path")).startsWith("transcoder/");
			if(template && keepTemplates)
			{
				out.println("  " + a.get("path") + ": kept (--keep-templates)");
				continue;
			}
			if(template && !TextFile.sha256(p).equals(a.get("sha256")))
			{
				out.println("  " + a.get("path") + ": kept, it was edited after the install");
				continue;
			}
			ops.add(new Op("delete", p, null, null, String.valueOf(a.get("path"))));
		}
		return ops;
	}

	// ---- applying ---------------------------------------------------------------------------------------------------

	static void print(PrintStream out, List<Op> ops, boolean dry)
	{
		if(ops.isEmpty())
		{
			out.println("No changes.");
			return;
		}
		out.println(dry ? "Would change:" : "Changes:");
		for(Op o: ops)
		{
			out.println("  " + o.kind + "  " + o.path + "  (" + o.note + ")");
			if(o.changes != null)
			{
				for(ConfigEdits.Change c: o.changes)
				{
					if(c.inserted() != null)
					{
						for(String l: c.inserted().split("\r?\n"))
						{
							if(!l.isBlank())
							{
								out.println("      + " + l.strip());
							}
						}
					}
					else
					{
						out.println("      " + c.path() + ": " + c.before() + " -> " + c.after());
					}
				}
			}
		}
	}

	/** What one apply has done so far. */
	private static final class Run
	{
		final List<Runnable> undo = new ArrayList<>();
		final List<Path> backups = new ArrayList<>();
		boolean finished, rolledBack, stopped, ok;
	}

	/**
	 * Applies the plan, recording it in the manifest. The manifest is written first, marked pending, with every change
	 * the run is about to make, and again at the end without the mark; each write replaces the file in one step. On a
	 * failure, or when the process is told to stop (Ctrl+C, the console window closed, a shutdown), puts back what this
	 * run wrote and the manifest as it was, and throws Failed. rolledBack is false when that did not fully succeed; the
	 * pending manifest and the backups then stay, so uninstall can still find every change.
	 * @param manifestUpdate called with the manifest to record applications and downloads; may be null
	 */
	List<String> apply(List<Op> ops, boolean install, java.util.function.Consumer<Map<String, Object>> manifestUpdate) throws Failed, IOException
	{
		Path mf = home.resolve(MANIFEST);
		byte[] prior = Files.isRegularFile(mf) ? Files.readAllBytes(mf) : null;
		Map<String, Object> man = manifest();
		Map<Op, Path> backupFor = new java.util.IdentityHashMap<>();
		for(Op o: ops)
		{
			if(o.kind.equals("edit") || o.kind.equals("retire"))
			{
				backupFor.put(o, backupOf(o.path));
			}
		}
		Map<String, Object> pending = copy(man);
		for(Op o: ops)
		{
			record(pending, o, backupFor.get(o), o.kind.equals("edit") ? Files.readAllBytes(o.path) : null, true);
		}
		if(manifestUpdate != null)
		{
			manifestUpdate.accept(pending);
		}
		pending.put("pending", stamp);

		Run r = new Run();
		Runnable stop = () -> {
			synchronized(lock)
			{
				if(!r.finished && !r.rolledBack)
				{
					r.stopped = true;
					out.println("Stopped before the end: putting back what this run changed.");
					rollback(r, prior, mf);
				}
			}
		};
		Thread hook = new Thread(stop, "overlaystudio-rollback");
		boolean hooked = false;
		try
		{
			Runtime.getRuntime().addShutdownHook(hook);
			hooked = true;
		}
		catch(IllegalStateException e)
		{
			// already shutting down; nothing is written below then
		}
		stopRequest = stop;
		try
		{
			synchronized(lock)
			{
				writeManifest(mf, pending);
			}
			for(int k = 0; k < ops.size(); k++)
			{
				synchronized(lock)
				{
					if(r.stopped)
					{
						throw new IOException("stopped");
					}
					execute(ops.get(k), man, backupFor.get(ops.get(k)), r);
				}
				var t = testAfterOp;
				if(t != null)
				{
					t.accept(this, k);
				}
			}
			synchronized(lock)
			{
				if(r.stopped)
				{
					throw new IOException("stopped");
				}
				if(manifestUpdate != null)
				{
					manifestUpdate.accept(man);
				}
				man.remove("pending");
				boolean empty = list(man, "edited").isEmpty() && list(man, "added").isEmpty();
				if(!install && empty)
				{
					Files.deleteIfExists(mf);
					deleteEmptyDirs(mf.getParent());
					deleteEmptyDirs(mf.getParent().getParent());
				}
				else
				{
					man.put("version", payload.version());
					man.put("updated", stamp);
					writeManifest(mf, man);
				}
				r.finished = true;
			}
			List<String> backups = new ArrayList<>();
			r.backups.forEach(b -> backups.add(b.toString()));
			return backups;
		}
		catch(Exception e)
		{
			boolean ok;
			synchronized(lock)
			{
				ok = r.rolledBack ? r.ok : rollback(r, prior, mf);
			}
			throw new Failed(r.stopped ? "the run was stopped before it finished" : Plain.say(e), e, ok);
		}
		finally
		{
			stopRequest = null;
			if(hooked)
			{
				try
				{
					Runtime.getRuntime().removeShutdownHook(hook);
				}
				catch(IllegalStateException e)
				{
					// the JVM is shutting down; the hook has run or runs now
				}
			}
		}
	}

	/** Asks a running apply to stop and put everything back, as the shutdown hook does. */
	void requestStop()
	{
		Runnable s = stopRequest;
		if(s != null)
		{
			s.run();
		}
	}

	// runs the undo list backwards; when all of it worked, also puts the manifest back as it was and removes this run's
	// backups. Called with lock held.
	private boolean rollback(Run r, byte[] prior, Path mf)
	{
		boolean ok = true;
		for(int k = r.undo.size() - 1; k >= 0; k--)
		{
			try
			{
				r.undo.get(k).run();
			}
			catch(RuntimeException x)
			{
				ok = false;
				out.println("  could not put back: " + x.getMessage());
			}
		}
		if(ok)
		{
			try
			{
				if(prior != null)
				{
					writeManifest(mf, prior);
				}
				else
				{
					Files.deleteIfExists(mf);
					deleteEmptyDirs(mf.getParent());
					deleteEmptyDirs(mf.getParent().getParent());
				}
				for(Path b: r.backups)
				{
					Files.deleteIfExists(b);
				}
			}
			catch(IOException x)
			{
				ok = false;
				out.println("  could not put back the manifest: " + x.getMessage());
			}
		}
		r.rolledBack = true;
		r.ok = ok;
		return ok;
	}

	private void execute(Op o, Map<String, Object> man, Path backup, Run r) throws IOException
	{
		switch(o.kind)
		{
			case "edit" -> {
				byte[] before = Files.readAllBytes(o.path);
				Files.write(backup, before, java.nio.file.StandardOpenOption.CREATE_NEW);
				r.backups.add(backup);
				own.fix(backup);
				r.undo.add(() -> write(o.path, before));
				Files.write(o.path, o.bytes);
				record(man, o, backup, before, false);
			}
			case "add" -> {
				boolean existed = Files.exists(o.path);
				byte[] before = existed ? Files.readAllBytes(o.path) : null;
				own.createDirectories(o.path.getParent());
				r.undo.add(() -> {
					if(before != null)
					{
						write(o.path, before);
					}
					else
					{
						delete(o.path);
					}
				});
				Files.write(o.path, o.bytes);
				if(!existed)
				{
					own.fix(o.path);
				}
				record(man, o, null, null, false);
			}
			case "retire" -> {
				Files.move(o.path, backup);
				r.undo.add(() -> move(backup, o.path));
				r.backups.add(backup);
				record(man, o, backup, null, false);
			}
			case "restore" -> {
				byte[] before = Files.readAllBytes(o.path);
				r.undo.add(() -> write(o.path, before));
				Files.write(o.path, o.bytes);
				record(man, o, null, null, false);
			}
			case "delete" -> {
				byte[] before = Files.readAllBytes(o.path);
				r.undo.add(() -> write(o.path, before));
				Files.delete(o.path);
				record(man, o, null, null, false);
			}
			default -> throw new IllegalStateException(o.kind);
		}
	}

	/**
	 * Records one change in a manifest. pending: the change is about to be made, so an edit keeps its old post-install
	 * hash and a delete or restore keeps its entry until it is done.
	 * @param before an edited file's bytes before this run's change
	 */
	private void record(Map<String, Object> m, Op o, Path backup, byte[] before, boolean pending)
	{
		String rel = rel(o.path);
		switch(o.kind)
		{
			case "edit" -> {
				List<Map<String, Object>> edited = list(m, "edited");
				Map<String, Object> e = find(edited, rel);
				if(e == null)
				{
					e = new LinkedHashMap<>();
					e.put("path", rel);
					e.put("original", TextFile.sha256(before));
					e.put("backup", rel(backup));
					e.put("changes", new ArrayList<>());
					edited.add(e);
				}
				else if(e.get("installed") != null && !TextFile.sha256(before).equals(e.get("installed")))
				{
					// something else changed the file since this installer last wrote it: its backup no longer stands
					// for "everything but Overlay Studio", so uninstall must take out only its own lines
					e.put("editedByOthers", true);
				}
				List<Map<String, Object>> changes = list(e, "changes");
				for(ConfigEdits.Change c: o.changes)
				{
					if(!changes.contains(c.toMap()))
					{
						changes.add(c.toMap());
					}
				}
				if(!pending)
				{
					e.put("installed", TextFile.sha256(o.bytes));
				}
			}
			case "add" -> {
				Map<String, Object> a = find(list(m, "added"), rel);
				if(a == null)
				{
					a = new LinkedHashMap<>();
					a.put("path", rel);
					list(m, "added").add(a);
				}
				a.put("sha256", TextFile.sha256(o.bytes));
			}
			case "retire", "delete" -> {
				if(!pending)
				{
					list(m, "added").remove(find(list(m, "added"), rel));
				}
			}
			case "restore" -> {
				if(!pending)
				{
					list(m, "edited").remove(find(list(m, "edited"), rel));
				}
			}
			default -> {
				// nothing else is recorded
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> copy(Map<String, Object> m)
	{
		return (Map<String, Object>)Json.parse(Json.write(m));
	}

	/** Writes the manifest through a temporary file renamed over it, so it is never left half written. */
	void writeManifest(Path mf, Map<String, Object> m) throws IOException
	{
		writeManifest(mf, Json.write(m).getBytes(StandardCharsets.UTF_8));
	}

	private void writeManifest(Path mf, byte[] bytes) throws IOException
	{
		own.createDirectories(mf.getParent());
		Path tmp = mf.resolveSibling(mf.getFileName() + ".tmp");
		Files.write(tmp, bytes);
		Runnable t = testBeforeManifestMove;
		if(t != null)
		{
			t.run();
		}
		Files.move(tmp, mf, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		own.fix(mf);
	}

	private Path backupOf(Path p)
	{
		Path b = p.resolveSibling(p.getFileName() + ".overlaystudio-" + stamp + ".bak");
		int n = 2;
		while(Files.exists(b))
		{
			b = p.resolveSibling(p.getFileName() + ".overlaystudio-" + stamp + "-" + n++ + ".bak");
		}
		return b;
	}

	static void deleteEmptyDirs(Path dir)
	{
		try
		{
			if(dir != null && Files.isDirectory(dir))
			{
				try(var s = Files.list(dir))
				{
					if(s.findAny().isPresent())
					{
						return;
					}
				}
				Files.delete(dir);
			}
		}
		catch(IOException e)
		{
			// leaving an empty folder is harmless
		}
	}

	private static void write(Path p, byte[] b)
	{
		try
		{
			Files.write(p, b);
		}
		catch(IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static void delete(Path p)
	{
		try
		{
			Files.deleteIfExists(p);
		}
		catch(IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static void move(Path a, Path b)
	{
		try
		{
			Files.move(a, b, StandardCopyOption.REPLACE_EXISTING);
		}
		catch(IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}
}
