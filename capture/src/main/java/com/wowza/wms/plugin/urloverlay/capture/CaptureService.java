/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.wowza.wms.plugin.urloverlay.egress.AddressRules;

/**
 * Service-wide state for page captures: the session limit, the memory cap, the tagged profile directory every Chrome
 * is started with, reaping of Chrome left behind by an earlier run, process lookups for status, and recent blocks.
 * Egress rules are per capture: the module sends the ones in WSE's Server.xml with each capture it opens.
 *
 * Every Chrome gets --user-data-dir=<workDir>/profiles/<serverTag>-<captureId>. The server tag changes at each service
 * start, so on startup any process whose command line names a profile with another tag belongs to a run that ended
 * without cleaning up, and is killed.
 */
public final class CaptureService
{
	public static final int DEFAULT_MAX_CAPTURES = 4;
	/**
	 * Memory one Chrome may use, all its processes together, before it is restarted. A headless Chrome showing an
	 * overlay page sits in the low hundreds of MB; a page that leaks grows without bound. 1 GB leaves room for a heavy
	 * page and keeps the default four browsers within 4 GB.
	 */
	public static final int DEFAULT_MAX_BROWSER_MB = 1024;
	private static final int MAX_RECENT_BLOCKS = 50;
	private static final long RSS_CACHE_MS = 5000;
	// status polls every few seconds; a WMI query on Windows takes about a second
	private static final long CMD_CACHE_MS = 3000;

	private static final Log log = Log.get();
	private static volatile CaptureService instance = new CaptureService(null, DEFAULT_MAX_CAPTURES, DEFAULT_MAX_BROWSER_MB);

	private final Path workDir;
	private final Path profilesDir;
	private final String serverTag;
	private final int maxCaptures;
	private final int maxBrowserMb;
	private final Object lock = new Object();
	private int active = 0;
	private final Deque<Map<String, Object>> recentBlocks = new ArrayDeque<>();
	private final java.util.concurrent.atomic.AtomicLong chromeServiceBlocks = new java.util.concurrent.atomic.AtomicLong();
	private volatile String sandboxProblem = null;
	private Map<Long, Long> rssCache = new HashMap<>();
	private long rssCacheAt = 0;
	private Map<Long, String> cmdCache = new HashMap<>();
	private long cmdCacheAt = 0;

	CaptureService(Path workDir, int maxCaptures, int maxBrowserMb)
	{
		this.maxBrowserMb = maxBrowserMb > 0 ? maxBrowserMb : DEFAULT_MAX_BROWSER_MB;
		this.workDir = workDir;
		this.profilesDir = workDir == null ? null : workDir.resolve("profiles");
		this.serverTag = UUID.randomUUID().toString().substring(0, 8);
		this.maxCaptures = maxCaptures > 0 ? maxCaptures : DEFAULT_MAX_CAPTURES;
	}

	public static CaptureService get()
	{
		return instance;
	}

	public int maxBrowserMb()
	{
		return maxBrowserMb;
	}

	/** Called once at service start with the capture.properties values. */
	public static CaptureService configure(Path workDir, int maxCaptures, int maxBrowserMb)
	{
		CaptureService s = new CaptureService(workDir.toAbsolutePath().normalize(), maxCaptures, maxBrowserMb);
		instance = s;
		return s;
	}

	public String serverTag()
	{
		return serverTag;
	}

	public int maxCaptures()
	{
		return maxCaptures;
	}

	public int activeCaptures()
	{
		synchronized(lock)
		{
			return active;
		}
	}

	/** @return true unless the session limit is reached */
	public boolean tryAcquire()
	{
		synchronized(lock)
		{
			if(active >= maxCaptures)
			{
				return false;
			}
			active++;
			return true;
		}
	}

	public void release()
	{
		synchronized(lock)
		{
			if(active > 0)
			{
				active--;
			}
		}
	}

	/** @return a fresh profile directory for one capture, or null when no work directory is configured (tests) */
	public Path newProfileDir(String captureId) throws IOException
	{
		if(profilesDir == null)
		{
			return null;
		}
		Path p = profilesDir.resolve(serverTag + "-" + captureId);
		Files.createDirectories(p);
		return p;
	}

	/** Why the last sandboxed Chrome did not start, for status; null once one has started. */
	public String sandboxProblem()
	{
		return sandboxProblem;
	}

	void sandboxStarted(String problem)
	{
		sandboxProblem = problem;
	}

	/**
	 * The message for a Chrome that did not start in its sandbox. The service never starts one without it; this says
	 * what to fix instead.
	 */
	static String sandboxStartFailure(String why, boolean linux, String restrictUserns)
	{
		String fix = linux
				? "1".equals(restrictUserns)
						? "This system restricts unprivileged user namespaces (kernel.apparmor_restrict_unprivileged_userns = 1); the URL Overlay installer adds an AppArmor profile that allows them for its Chrome. Run the installer again, or check that the profile is loaded (aa-status)."
						: "Check that the service does not run as root and that Chrome's sandbox can create user namespaces."
				: "Check that the service runs under its own account (NT SERVICE\\WowzaURLOverlayCapture) and that the account can read the Chrome folder.";
		return "Chrome did not start in its sandbox (" + why + "). Captures stay off until it does; the service never runs Chrome without its sandbox. " + fix;
	}

	/** kernel.apparmor_restrict_unprivileged_userns on Linux, or null where it does not exist. */
	static String restrictUserns()
	{
		try
		{
			return Files.readString(Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns")).trim();
		}
		catch(IOException | RuntimeException e)
		{
			return null;
		}
	}

	public void recordBlock(String label, String host, AddressRules.Decision d)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("at", Instant.now().toString());
		m.put("capture", label);
		m.put("host", host);
		m.put("address", d.address());
		m.put("rule", d.rule());
		synchronized(recentBlocks)
		{
			recentBlocks.addFirst(m);
			while(recentBlocks.size() > MAX_RECENT_BLOCKS)
			{
				recentBlocks.removeLast();
			}
		}
		log.warn("UrlOverlay capture: blocked " + host + " (" + d.address() + ", " + d.rule() + ") for " + label);
	}

	public void countChromeServiceBlock()
	{
		chromeServiceBlocks.incrementAndGet();
	}

	public long chromeServiceBlocks()
	{
		return chromeServiceBlocks.get();
	}

	public List<Map<String, Object>> recentBlocks()
	{
		synchronized(recentBlocks)
		{
			return new ArrayList<>(recentBlocks);
		}
	}

	// ---- processes ----------------------------------------------------------------------------------------------

	/** Kills Chrome and chromedriver left by earlier runs and removes their profiles. @return processes killed */
	public int reapOrphans()
	{
		if(profilesDir == null)
		{
			return 0;
		}
		String marker = profilesDir.toString();
		int killed = 0;
		for(Map.Entry<Long, String> e: commandLines(true).entrySet())
		{
			String cmd = e.getValue();
			int at = indexOfIgnoreCase(cmd, marker);
			if(at < 0 || cmd.regionMatches(true, at + marker.length() + 1, serverTag + "-", 0, serverTag.length() + 1))
			{
				continue;
			}
			Optional<ProcessHandle> ph = ProcessHandle.of(e.getKey());
			if(ph.isEmpty())
			{
				continue;
			}
			Optional<ProcessHandle> parent = ph.get().parent();
			killed += killTree(ph.get());
			if(parent.isPresent() && parent.get().info().command().map(c -> c.toLowerCase(Locale.ROOT).contains("chromedriver")).orElse(false))
			{
				killed += killTree(parent.get());
			}
		}
		File[] stale = profilesDir.toFile().listFiles(f -> f.isDirectory() && !f.getName().startsWith(serverTag + "-"));
		if(stale != null)
		{
			for(File f: stale)
			{
				deleteTree(f.toPath());
			}
		}
		if(killed > 0)
		{
			log.warn("UrlOverlay capture: killed " + killed + " Chrome or chromedriver process(es) left by an earlier run");
		}
		return killed;
	}

	/** @return the main Chrome process using this profile, if it is running */
	public Optional<ProcessHandle> findBrowser(Path profileDir)
	{
		if(profileDir == null)
		{
			return Optional.empty();
		}
		String marker = profileDir.toString();
		for(Map.Entry<Long, String> e: commandLines(false).entrySet())
		{
			String cmd = e.getValue();
			if(indexOfIgnoreCase(cmd, marker) >= 0 && !cmd.contains("--type="))
			{
				Optional<ProcessHandle> ph = ProcessHandle.of(e.getKey());
				if(ph.isPresent() && ph.get().isAlive())
				{
					return ph;
				}
			}
		}
		return Optional.empty();
	}

	/** Resident memory of a process and all its descendants, in bytes, or -1 if unknown. */
	public long treeRss(ProcessHandle root)
	{
		Map<Long, Long> rss = rssByPid();
		if(rss.isEmpty())
		{
			return -1;
		}
		long total = rss.getOrDefault(root.pid(), 0L);
		for(ProcessHandle d: (Iterable<ProcessHandle>)root.descendants()::iterator)
		{
			total += rss.getOrDefault(d.pid(), 0L);
		}
		return total;
	}

	public static int killTree(ProcessHandle root)
	{
		int n = 0;
		List<ProcessHandle> all = new ArrayList<>(root.descendants().toList());
		all.add(root);
		for(ProcessHandle p: all)
		{
			if(p.isAlive() && p.destroyForcibly())
			{
				n++;
			}
		}
		return n;
	}

	public static void deleteTree(Path p)
	{
		if(p == null || !Files.exists(p))
		{
			return;
		}
		try(Stream<Path> walk = Files.walk(p))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(q -> q.toFile().delete());
		}
		catch(IOException e)
		{
			log.warn("UrlOverlay capture: could not delete " + p + ": " + e.getMessage());
		}
	}

	/**
	 * Command lines of every visible process. The JDK reports them on Linux; on Windows it often returns nothing for
	 * other processes, so there the WMI query is used.
	 */
	Map<Long, String> commandLines(boolean fresh)
	{
		synchronized(lock)
		{
			long now = System.currentTimeMillis();
			if(!fresh && now - cmdCacheAt < CMD_CACHE_MS)
			{
				return cmdCache;
			}
		}
		Map<Long, String> out = new HashMap<>();
		ProcessHandle.allProcesses().forEach(p -> p.info().commandLine().ifPresent(c -> out.put(p.pid(), c)));
		if(isWindows())
		{
			out.putAll(windowsCommandLines());
		}
		synchronized(lock)
		{
			cmdCache = out;
			cmdCacheAt = System.currentTimeMillis();
		}
		return out;
	}

	private static Map<Long, String> windowsCommandLines()
	{
		Map<Long, String> out = new HashMap<>();
		// -EncodedCommand (UTF-16LE, base64) avoids Windows command-line quoting of the script entirely
		String ps = "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine } | "
				+ "ForEach-Object { [string]$_.ProcessId + [char]9 + $_.CommandLine }";
		String encoded = java.util.Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
		List<String> lines = run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), 20);
		for(String line: lines)
		{
			int tab = line.indexOf('\t');
			if(tab > 0)
			{
				try
				{
					out.put(Long.parseLong(line.substring(0, tab).trim()), line.substring(tab + 1));
				}
				catch(NumberFormatException e)
				{
					// skip
				}
			}
		}
		return out;
	}

	private Map<Long, Long> rssByPid()
	{
		synchronized(lock)
		{
			long now = System.currentTimeMillis();
			if(now - rssCacheAt < RSS_CACHE_MS)
			{
				return rssCache;
			}
			Map<Long, Long> m = new HashMap<>();
			if(isWindows())
			{
				// "Image","PID","Session","Session#","Mem Usage" with Mem Usage like "212,532 K"
				for(String line: run(List.of("tasklist", "/FO", "CSV", "/NH"), 20))
				{
					String[] f = line.split("\",\"");
					if(f.length >= 5)
					{
						try
						{
							long pid = Long.parseLong(f[1].replace("\"", "").trim());
							long kb = Long.parseLong(f[4].replaceAll("[^0-9]", ""));
							m.put(pid, kb * 1024);
						}
						catch(NumberFormatException e)
						{
							// skip
						}
					}
				}
			}
			else
			{
				// PSS, not RSS: Chrome's processes share the browser binary and the zygote's pages, so summing RSS counts
				// them once per process (a scoreboard page summed to 1.6 GB). PSS splits shared pages between their users.
				ProcessHandle.allProcesses().forEach(p -> {
					String pid = Long.toString(p.pid());
					try
					{
						Long kb = field(Path.of("/proc", pid, "smaps_rollup"), "Pss:");
						if(kb == null)
						{
							kb = field(Path.of("/proc", pid, "status"), "VmRSS:");
						}
						if(kb != null)
						{
							m.put(p.pid(), kb * 1024);
						}
					}
					catch(IOException | NumberFormatException e)
					{
						// process gone or not readable
					}
				});
			}
			rssCache = m;
			rssCacheAt = now;
			return m;
		}
	}

	/** The kB value of a "Name:   123 kB" line in a /proc file, or null when the file has no such line. */
	static Long field(Path file, String name) throws IOException
	{
		if(!Files.isReadable(file))
		{
			return null;
		}
		for(String l: Files.readAllLines(file))
		{
			if(l.startsWith(name))
			{
				return Long.parseLong(l.replaceAll("[^0-9]", ""));
			}
		}
		return null;
	}

	private static List<String> run(List<String> cmd, int timeoutSeconds)
	{
		List<String> out = new ArrayList<>();
		try
		{
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
			{
				String l;
				while((l = r.readLine()) != null)
				{
					out.add(l);
				}
			}
			if(!p.waitFor(timeoutSeconds, TimeUnit.SECONDS))
			{
				p.destroyForcibly();
			}
		}
		catch(IOException e)
		{
			log.warn("UrlOverlay capture: could not run " + cmd.get(0) + ": " + e.getMessage());
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		return out;
	}

	static int indexOfIgnoreCase(String s, String part)
	{
		return s.toLowerCase(Locale.ROOT).indexOf(part.toLowerCase(Locale.ROOT));
	}

	static boolean isWindows()
	{
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	static boolean isLinux()
	{
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
	}
}
