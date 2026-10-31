/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One page in a ChromeBrowser: its browser context, and the DevTools connection to its window. */
final class BrowserPage implements PageDriver
{
	private final ChromeBrowser chrome;
	private final String contextId;
	private final DevTools page;
	private final List<Consumer<String>> lostListeners = new CopyOnWriteArrayList<>();
	private volatile String lostWhy = null;
	private volatile boolean closed = false;
	// the load event the current navigation waits for
	private volatile CompletableFuture<Void> loaded = null;
	private volatile Consumer<Frame> frames = null;
	private final Runnable stopWatchingChrome;

	static final String NO_DIALOGS = "window.alert=function(){};window.confirm=function(){return false};window.prompt=function(){return null};";

	BrowserPage(ChromeBrowser chrome, String contextId, DevTools page) throws Exception
	{
		this.chrome = chrome;
		this.contextId = contextId;
		this.page = page;
		page.on("Page.loadEventFired", p -> {
			CompletableFuture<Void> f = loaded;
			if(f != null)
			{
				f.complete(null);
			}
		});
		page.on("Page.screencastFrame", p -> {
			Consumer<Frame> sink = frames;
			Object data = p.get("data"), session = p.get("sessionId");
			if(sink != null && data instanceof String s && session instanceof Number n)
			{
				sink.accept(new Frame(Base64.getDecoder().decode(s), n.intValue()));
			}
		});
		// an alert, confirm or prompt blocks the page, its scripts and its load until answered; it is dismissed, as
		// chromedriver did. A beforeunload dialog is accepted, since dismissing it would cancel the navigation under way.
		page.on("Page.javascriptDialogOpening", p -> page.send("Page.handleJavaScriptDialog", Map.of("accept", "beforeunload".equals(p.get("type")))));
		page.on("Inspector.targetCrashed", p -> lost("the page's renderer crashed"));
		page.on("Inspector.detached", p -> lost("the page was detached (" + p.get("reason") + ")"));
		page.onClose(why -> lost(why));
		stopWatchingChrome = chrome.onExit(this::lost);
		long t = ChromeBrowser.CALL_TIMEOUT_MS;
		try
		{
			page.call("Page.enable", null, t);
			page.call("Inspector.enable", null, t);
			// a dialog the old document opens while a reload replaces it cannot be answered ("Not attached to an active
			// page") and blocks the renderer for good, so pages get no alert, confirm or prompt at all: each returns what
			// a dismissed one does, in every document and frame before its own scripts run. The handler above stays for
			// whatever still opens one.
			page.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", NO_DIALOGS), t);
		}
		catch(Exception e)
		{
			stopWatchingChrome.run();
			page.close("the page could not be set up: " + e.getMessage());
			throw e;
		}
	}

	private void lost(String why)
	{
		synchronized(this)
		{
			if(lostWhy != null || closed)
			{
				return;
			}
			lostWhy = why;
		}
		// whatever was waiting on the page fails now rather than at its timeout
		page.close(why);
		CompletableFuture<Void> f = loaded;
		if(f != null)
		{
			f.completeExceptionally(new IllegalStateException(why));
		}
		for(Consumer<String> l: lostListeners)
		{
			l.accept(why);
		}
	}

	@Override
	public void onLost(Consumer<String> listener)
	{
		lostListeners.add(listener);
		String why = lostWhy;
		if(why != null)
		{
			listener.accept(why);
		}
	}

	/** Sets the page's own size; the window's size is not the page's in headless Chrome, and display scaling can double it. */
	void viewport(int width, int height) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("width", width);
		m.put("height", height);
		m.put("deviceScaleFactor", 1);
		m.put("mobile", false);
		page.call("Emulation.setDeviceMetricsOverride", m, ChromeBrowser.CALL_TIMEOUT_MS);
	}

	@Override
	public void navigate(String url, long timeoutMs) throws Exception
	{
		CompletableFuture<Void> f = new CompletableFuture<>();
		loaded = f;
		long t0 = System.currentTimeMillis();
		Map<String, Object> r = page.call("Page.navigate", Map.of("url", url), timeoutMs);
		// an address that cannot be reached still loads Chrome's error page, as chromedriver's get did; the capture finds it
		// off its page and loads the address again later
		if(r.get("errorText") != null)
		{
			Log.get().info("UrlOverlay capture: " + url + " did not load: " + r.get("errorText"));
		}
		else if(r.get("loaderId") == null)
		{
			// the page is already at this address and only its #fragment moved: nothing loads, so it is reloaded instead
			page.call("Page.reload", null, timeoutMs);
		}
		long left = timeoutMs - (System.currentTimeMillis() - t0);
		try
		{
			f.get(Math.max(1, left), TimeUnit.MILLISECONDS);
		}
		catch(TimeoutException e)
		{
			throw new TimeoutException(url + " did not finish loading in " + timeoutMs / 1000 + " s");
		}
		catch(java.util.concurrent.ExecutionException e)
		{
			throw e.getCause() instanceof Exception x ? x : e;
		}
		finally
		{
			loaded = null;
		}
	}

	@Override
	public byte[] screenshot(long timeoutMs) throws Exception
	{
		Object data = page.call("Page.captureScreenshot", Map.of("format", "png"), timeoutMs).get("data");
		if(!(data instanceof String s))
		{
			throw new IllegalStateException("Chrome sent no screenshot");
		}
		return Base64.getDecoder().decode(s);
	}

	@Override
	public Object evaluate(String script, long timeoutMs) throws Exception
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("expression", script);
		m.put("returnByValue", true);
		Map<String, Object> r = page.call("Runtime.evaluate", m, timeoutMs);
		if(r.get("exceptionDetails") instanceof Map<?, ?> x)
		{
			throw new IllegalStateException("The script failed in the page: " + x.get("text"));
		}
		return r.get("result") instanceof Map<?, ?> v ? v.get("value") : null;
	}

	@Override
	public void startScreencast(int width, int height, Consumer<Frame> sink) throws Exception
	{
		frames = sink;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "png");
		if(width > 0 && height > 0)
		{
			m.put("maxWidth", width);
			m.put("maxHeight", height);
		}
		m.put("everyNthFrame", 1);
		page.call("Page.startScreencast", m, ChromeBrowser.CALL_TIMEOUT_MS);
	}

	@Override
	public void ackFrame(int sessionId)
	{
		page.send("Page.screencastFrameAck", Map.of("sessionId", sessionId));
	}

	@Override
	public void stopScreencast()
	{
		frames = null;
		page.send("Page.stopScreencast", null);
	}

	@Override
	public long heapBytes()
	{
		try
		{
			Object used = page.call("Runtime.getHeapUsage", null, 5000).get("usedSize");
			return used instanceof Number n ? n.longValue() : -1;
		}
		catch(Exception e)
		{
			return -1;
		}
	}

	ChromeBrowser chrome()
	{
		return chrome;
	}

	@Override
	public void close()
	{
		synchronized(this)
		{
			if(closed)
			{
				return;
			}
			closed = true;
		}
		frames = null;
		stopWatchingChrome.run();
		page.close("the page was closed");
		CompletableFuture<Void> f = loaded;
		if(f != null)
		{
			f.completeExceptionally(new IllegalStateException("the page was closed"));
		}
		chrome.disposeContext(contextId);
	}
}
