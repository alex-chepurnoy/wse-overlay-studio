/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;
import com.wowza.wms.plugin.overlaystudio.egress.EgressGuard;

/**
 * The Chromes the service runs, each holding up to pagesPerBrowser pages. Only pages with the same egress rules share
 * one, since the browser-wide guard that filters Chrome's own traffic is built from them. A Chrome is started for the
 * first page that finds no room, and closed with its last page.
 */
final class ChromeGroups
{
	/** One Chrome and what keeps it running: its browser-wide guard and its profile. */
	static final class Group
	{
		final String id = UUID.randomUUID().toString().substring(0, 8);
		final AddressRules rules;
		// what pages must have in common to share this Chrome: their egress rules, compared by value
		final Object rulesKey;
		// done once Chrome has started, or failed to
		final CompletableFuture<ChromeBrowser> started = new CompletableFuture<>();
		final List<WebPageCapture> pages = new ArrayList<>();
		EgressGuard guard;
		Path profileDir;
		volatile boolean dead = false;
		// takes no new pages: its Chrome is to be replaced, and closes when its last page leaves
		volatile boolean draining = false;
		// the page restarted when the group is over its memory limit; kept until the group is back under it
		WebPageCapture memoryVictim;

		Group(AddressRules rules)
		{
			this(rules, rules);
		}

		Group(AddressRules rules, Object rulesKey)
		{
			this.rules = rules;
			this.rulesKey = rulesKey;
		}

		ChromeBrowser chrome()
		{
			return started.getNow(null);
		}
	}

	/** Starts the Chrome for a new group, setting its guard and profile. */
	interface Starter
	{
		ChromeBrowser start(Group g) throws Exception;
	}

	private static final ChromeGroups INSTANCE = new ChromeGroups();
	private static final java.util.concurrent.ExecutorService CLOSER = java.util.concurrent.Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "ChromeClose");
		t.setDaemon(true);
		return t;
	});
	private final Object lock = new Object();
	private final List<Group> groups = new ArrayList<>();

	static ChromeGroups get()
	{
		return INSTANCE;
	}

	/**
	 * A place for page in a Chrome with these rules: in one with room, else in a new one, started here. Two pages that
	 * find no room at once share the Chrome the first one starts. @throws on a Chrome that does not start
	 */
	Group join(WebPageCapture page, AddressRules rules, Starter starter) throws Exception
	{
		return join(page, rules, rules, starter);
	}

	/**
	 * As join, matching groups on rulesKey: the module's egress settings (EgressRules, a record), equal for every page of
	 * one WSE. The AddressRules built from them are a new object each time and are not compared by value.
	 */
	Group join(WebPageCapture page, AddressRules rules, Object rulesKey, Starter starter) throws Exception
	{
		Group g = null;
		boolean starting = false;
		synchronized(lock)
		{
			int max = CaptureService.get().pagesPerBrowser();
			for(Group x: groups)
			{
				if(!x.dead && !x.draining && x.rulesKey.equals(rulesKey) && x.pages.size() < max && !x.started.isCompletedExceptionally())
				{
					g = x;
					break;
				}
			}
			if(g == null)
			{
				g = new Group(rules, rulesKey);
				groups.add(g);
				starting = true;
			}
			g.pages.add(page);
		}
		if(starting)
		{
			try
			{
				ChromeBrowser chrome = starter.start(g);
				Group started = g;
				g.started.complete(chrome);
				chrome.onExit(why -> exited(started, why));
			}
			catch(Exception e)
			{
				g.started.completeExceptionally(e);
			}
		}
		try
		{
			// as long as the starter itself may take: Chrome's start, then its DevTools connection
			g.started.get(ChromeBrowser.START_TIMEOUT_MS + ChromeBrowser.CALL_TIMEOUT_MS + 10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		catch(Exception e)
		{
			leave(g, page);
			Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
			throw cause instanceof Exception x ? x : new IllegalStateException(cause);
		}
		return g;
	}

	/** Takes page out of its group; the last page out closes the group's Chrome. */
	void leave(Group g, WebPageCapture page)
	{
		boolean last;
		synchronized(lock)
		{
			if(!g.pages.remove(page))
			{
				return;
			}
			if(g.memoryVictim == page)
			{
				g.memoryVictim = null;
			}
			last = g.pages.isEmpty();
			if(last)
			{
				groups.remove(g);
				g.dead = true;
			}
		}
		if(last)
		{
			// Chrome takes a few seconds to end and its profile to go; the page leaving does not wait for that
			try
			{
				CLOSER.execute(() -> close(g));
			}
			catch(java.util.concurrent.RejectedExecutionException e)
			{
				// the service is stopping and no longer closes in the background
				close(g);
			}
		}
	}

	/**
	 * Marks a group's Chrome to be replaced: it takes no new pages, and closes when its last page has left. For a Chrome
	 * whose memory a page restart did not bring down, or that could not open a page.
	 */
	void drain(Group g, String why)
	{
		synchronized(lock)
		{
			if(g.draining || g.dead)
			{
				return;
			}
			g.draining = true;
		}
		Log.get().warn("OverlayStudio capture: Chrome " + g.id + " takes no new pages (" + why + "); it closes when its last page leaves");
	}

	private void exited(Group g, String why)
	{
		boolean closing;
		synchronized(lock)
		{
			closing = !g.dead;
			g.dead = true;
			groups.remove(g);
		}
		if(closing)
		{
			Log.get().warn("OverlayStudio capture: " + why + "; its pages open again in a new Chrome");
		}
		// each page hears of it through its own BrowserPage and opens again with its backoff
	}

	private static void close(Group g)
	{
		ChromeBrowser c = g.chrome();
		if(c != null)
		{
			c.close();
		}
		// Windows keeps a dead process's files locked for a moment
		for(int i = 0; i < 5 && g.profileDir != null && java.nio.file.Files.exists(g.profileDir); i++)
		{
			CaptureService.deleteTree(g.profileDir);
			if(java.nio.file.Files.exists(g.profileDir))
			{
				try
				{
					Thread.sleep(300);
				}
				catch(InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		if(g.guard != null)
		{
			g.guard.close();
		}
	}

	/** Every group now, for the memory check and status. */
	List<Group> all()
	{
		synchronized(lock)
		{
			return new ArrayList<>(groups);
		}
	}

	/** The pages in a group now. */
	List<WebPageCapture> pages(Group g)
	{
		synchronized(lock)
		{
			return new ArrayList<>(g.pages);
		}
	}

	/**
	 * The group's memory over its limit: maxBrowserMemoryMB for each page it holds. Returns the page to charge with it,
	 * the one with the largest JavaScript heap, picked once and kept until the group is back under the limit; null when
	 * the group is within it or its memory is unknown.
	 */
	WebPageCapture memoryVictim(Group g, long rssBytes, java.util.function.ToLongFunction<WebPageCapture> heap)
	{
		List<WebPageCapture> ps;
		synchronized(lock)
		{
			long cap = (long)CaptureService.get().maxBrowserMb() * 1024 * 1024 * Math.max(1, g.pages.size());
			if(rssBytes < 0 || rssBytes <= cap || g.pages.isEmpty())
			{
				g.memoryVictim = null;
				return null;
			}
			// a page out of restarts stays charged: it is reported and left running, and no other page pays for it
			if(g.memoryVictim != null)
			{
				return g.memoryVictim;
			}
			ps = new ArrayList<>(g.pages);
		}
		WebPageCapture biggest = ps.get(0);
		long most = -1;
		for(WebPageCapture p: ps)
		{
			long h = ps.size() == 1 ? 0 : heap.applyAsLong(p);
			if(h > most)
			{
				most = h;
				biggest = p;
			}
		}
		synchronized(lock)
		{
			if(g.memoryVictim == null && g.pages.contains(biggest))
			{
				g.memoryVictim = biggest;
			}
			return g.memoryVictim;
		}
	}

	/** Stops every Chrome, and waits for those already being closed; for service shutdown. */
	void closeAll()
	{
		List<Group> all;
		synchronized(lock)
		{
			all = new ArrayList<>(groups);
			groups.clear();
			all.forEach(g -> g.dead = true);
		}
		all.forEach(ChromeGroups::close);
		CLOSER.shutdown();
		try
		{
			CLOSER.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
