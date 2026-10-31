/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One headless Chrome, started by the service and driven over DevTools. It holds pages, each in a browser context of
 * its own with its own egress proxy, opened as a window of its own. Chrome's own traffic goes through the browser-wide
 * proxy it is started with.
 */
final class ChromeBrowser
{
	/** How long Chrome may take to start and say where DevTools listens. */
	static final long START_TIMEOUT_MS = 30_000;
	/** The longest any browser-level DevTools call may take. */
	static final long CALL_TIMEOUT_MS = 15_000;
	private static final Pattern LISTENING = Pattern.compile("DevTools listening on (ws://\\S+)");
	private static final int STDERR_LINES = 20;

	private final Process process;
	private final Path profileDir;
	private final DevTools browser;
	private final int port;
	private final List<Consumer<String>> exitListeners = new CopyOnWriteArrayList<>();
	private volatile String exitedWhy = null;

	private ChromeBrowser(Process process, Path profileDir, DevTools browser, int port)
	{
		this.process = process;
		this.profileDir = profileDir;
		this.browser = browser;
		this.port = port;
	}

	/**
	 * Starts Chrome with these switches and connects to it. Chrome that exits first, or does not say where DevTools
	 * listens within START_TIMEOUT_MS, is killed, and the exception carries the last lines it wrote.
	 */
	static ChromeBrowser start(String chrome, List<String> switches, Path profileDir) throws Exception
	{
		List<String> cmd = new ArrayList<>();
		cmd.add(chrome);
		cmd.addAll(switches);
		cmd.add("--remote-debugging-port=0");
		cmd.add("about:blank");
		Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		CompletableFuture<String> address = new CompletableFuture<>();
		Deque<String> lastLines = new ArrayDeque<>();
		// Chrome writes to standard error for its whole life; it is read to the end so Chrome never blocks on a full pipe
		Thread.ofVirtual().name("chrome-stderr-" + p.pid()).start(() -> {
			try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
			{
				for(String line; (line = r.readLine()) != null;)
				{
					if(!address.isDone())
					{
						Matcher m = LISTENING.matcher(line);
						if(m.find())
						{
							address.complete(m.group(1));
							continue;
						}
						synchronized(lastLines)
						{
							lastLines.addLast(line);
							if(lastLines.size() > STDERR_LINES)
							{
								lastLines.removeFirst();
							}
						}
					}
				}
			}
			catch(Exception e)
			{
				// Chrome closed its end
			}
			address.complete(null);
		});
		String ws;
		try
		{
			ws = address.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch(java.util.concurrent.TimeoutException e)
		{
			ws = null;
		}
		if(ws == null)
		{
			CaptureService.killTree(p.toHandle());
			String said;
			synchronized(lastLines)
			{
				said = String.join(" | ", lastLines);
			}
			throw new IllegalStateException("Chrome did not start" + (p.isAlive() ? " within " + START_TIMEOUT_MS / 1000 + " s" : " (exit code " + exitCode(p) + ")")
					+ (said.isBlank() ? "" : ": " + said));
		}
		DevTools d;
		try
		{
			d = DevTools.connect(ws, "Chrome " + p.pid(), CALL_TIMEOUT_MS);
		}
		catch(Exception e)
		{
			CaptureService.killTree(p.toHandle());
			throw e;
		}
		ChromeBrowser b = new ChromeBrowser(p, profileDir, d, java.net.URI.create(ws).getPort());
		d.onClose(why -> b.exited("lost its DevTools connection"));
		p.onExit().thenRun(() -> b.exited("exited with code " + exitCode(p)));
		return b;
	}

	private static String exitCode(Process p)
	{
		try
		{
			return Integer.toString(p.exitValue());
		}
		catch(IllegalThreadStateException e)
		{
			return "unknown";
		}
	}

	private void exited(String why)
	{
		synchronized(this)
		{
			if(exitedWhy != null)
			{
				return;
			}
			exitedWhy = "Chrome " + why;
		}
		browser.close(exitedWhy);
		for(Consumer<String> l: exitListeners)
		{
			try
			{
				l.accept(exitedWhy);
			}
			catch(RuntimeException e)
			{
				Log.get().error("UrlOverlay capture: error after Chrome exited", e);
			}
		}
	}

	/** Calls listener once, with the reason, when Chrome exits or its connection is lost. @return what removes it again */
	Runnable onExit(Consumer<String> listener)
	{
		exitListeners.add(listener);
		String why = exitedWhy;
		if(why != null)
		{
			listener.accept(why);
		}
		return () -> exitListeners.remove(listener);
	}

	boolean alive()
	{
		return exitedWhy == null && process.isAlive();
	}

	ProcessHandle process()
	{
		return process.toHandle();
	}

	Path profileDir()
	{
		return profileDir;
	}

	/**
	 * Opens a page: a browser context whose traffic goes through the proxy on proxyPort, loopback included, and a window
	 * in it at width x height. On failure the context is disposed again.
	 */
	BrowserPage openPage(int proxyPort, int width, int height) throws Exception
	{
		Map<String, Object> ctx = new LinkedHashMap<>();
		ctx.put("proxyServer", "http://127.0.0.1:" + proxyPort);
		// without this Chrome sends loopback requests directly, around the guard
		ctx.put("proxyBypassList", "<-loopback>");
		ctx.put("disposeOnDetach", false);
		String contextId = (String)browser.call("Target.createBrowserContext", ctx, CALL_TIMEOUT_MS).get("browserContextId");
		try
		{
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("url", "about:blank");
			t.put("browserContextId", contextId);
			// a window of its own: a background tab is hidden, and a hidden page sends no screencast frames
			t.put("newWindow", true);
			if(width > 0 && height > 0)
			{
				t.put("width", width);
				t.put("height", height);
			}
			String targetId = (String)browser.call("Target.createTarget", t, CALL_TIMEOUT_MS).get("targetId");
			DevTools page = DevTools.connect("ws://127.0.0.1:" + port + "/devtools/page/" + targetId, "page " + targetId.substring(0, Math.min(8, targetId.length())),
					CALL_TIMEOUT_MS);
			return new BrowserPage(this, contextId, page);
		}
		catch(Exception e)
		{
			disposeContext(contextId);
			throw e;
		}
	}

	/** Closes a page's context, which ends its renderer. Not waited for: callers may hold a capture's lock. */
	void disposeContext(String contextId)
	{
		if(alive())
		{
			browser.send("Target.disposeBrowserContext", Map.of("browserContextId", contextId));
		}
	}

	/** Ends Chrome and every process it started, and waits for them to be gone, so its profile can be removed. */
	void close()
	{
		exited("was closed");
		List<ProcessHandle> all = new ArrayList<>(process.descendants().toList());
		all.add(process.toHandle());
		CaptureService.killTree(process.toHandle());
		long end = System.currentTimeMillis() + 5000;
		for(ProcessHandle h: all)
		{
			try
			{
				h.onExit().get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
			}
			catch(Exception e)
			{
				// still going after 5 s; the profile is removed at the next service start
			}
		}
	}
}
