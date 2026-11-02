/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One DevTools connection to Chrome, over the JDK's WebSocket client: calls wait for their answer by id, with a timeout
 * each; events go to the listeners registered for their method. When the connection closes or is failed, every call
 * still waiting fails at once.
 */
final class DevTools implements WebSocket.Listener
{
	/** Chrome answered a call with an error, or the connection ended before it answered. */
	static final class Failure extends Exception
	{
		Failure(String message)
		{
			super(message);
		}
	}

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final String name;
	private final AtomicInteger next = new AtomicInteger();
	private final Map<Integer, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
	private final Map<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();
	private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
	// only the listener thread uses it; replaced after a large message so its capacity is not kept
	private StringBuilder part = new StringBuilder();
	// frames are up to a few MB each and come many times a second: the buffer is kept up to this, larger is let go
	private static final int KEEP_CAPACITY = 8 * 1024 * 1024;
	private final Object sending = new Object();
	// the last message queued to go out; the next one is sent once it has
	private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
	static final long SEND_TIMEOUT_MS = 10_000;
	private volatile WebSocket ws;
	private volatile String closedWhy = null;

	private DevTools(String name)
	{
		this.name = name;
	}

	/** Connects to a DevTools WebSocket address, waiting at most timeoutMs. */
	static DevTools connect(String url, String name, long timeoutMs) throws Exception
	{
		DevTools d = new DevTools(name);
		// large frames (a screenshot is up to a few MB of base64) arrive in parts; onText joins them
		d.ws = HTTP.newWebSocketBuilder().connectTimeout(java.time.Duration.ofMillis(timeoutMs)).buildAsync(URI.create(url), d)
				.get(timeoutMs, TimeUnit.MILLISECONDS);
		return d;
	}

	/** Calls a method and waits for its result. @throws Failure on an error answer or a closed connection */
	Map<String, Object> call(String method, Map<String, Object> params, long timeoutMs) throws Exception
	{
		String why = closedWhy;
		if(why != null)
		{
			throw new Failure(name + ": " + why);
		}
		int id = next.incrementAndGet();
		CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
		waiting.put(id, f);
		try
		{
			write(id, method, params);
			why = closedWhy;
			if(why != null)
			{
				// closed while sending: the close may have run before this call was registered
				throw new Failure(name + ": " + why);
			}
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch(TimeoutException e)
		{
			throw new TimeoutException(method + " got no answer from Chrome in " + timeoutMs + " ms");
		}
		catch(java.util.concurrent.ExecutionException e)
		{
			throw e.getCause() instanceof Exception x ? x : e;
		}
		finally
		{
			waiting.remove(id);
		}
	}

	/** Calls a method without waiting for its answer; nothing is reported when it fails. */
	void send(String method, Map<String, Object> params)
	{
		if(closedWhy != null)
		{
			return;
		}
		// queued behind whatever is going out, never waited for: an acknowledgment or a stop must not hold its caller
		queue(next.incrementAndGet(), method, params);
	}

	private void write(int id, String method, Map<String, Object> params) throws Exception
	{
		try
		{
			queue(id, method, params).get(SEND_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
		}
		catch(TimeoutException e)
		{
			throw new Failure(name + ": " + method + " could not be sent to Chrome in " + SEND_TIMEOUT_MS / 1000 + " s");
		}
		catch(java.util.concurrent.ExecutionException e)
		{
			Throwable c = e.getCause();
			String why = closedWhy;
			// a connection closed under the send says why it closed, not just that the send failed
			throw new Failure(name + ": " + (why != null ? why
					: method + " could not be sent to Chrome" + (c instanceof TimeoutException ? " in " + SEND_TIMEOUT_MS / 1000 + " s" : ": " + c)));
		}
	}

	/**
	 * Sends a message after every message queued before it: the JDK's WebSocket refuses a send while another is still
	 * going out. A message that does not go out within SEND_TIMEOUT_MS ends the connection, since every later send would
	 * wait behind it.
	 */
	private CompletableFuture<?> queue(int id, String method, Map<String, Object> params)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("method", method);
		m.put("params", params == null ? Map.of() : params);
		String text = Json.write(m);
		CompletableFuture<?> sent;
		synchronized(sending)
		{
			sent = tail.thenCompose(x -> closedWhy != null ? CompletableFuture.<java.net.http.WebSocket>completedFuture(null) : ws.sendText(text, true))
					.orTimeout(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			// the next message waits for this stage, which closes the connection first when this one failed, so nothing is
			// sent behind a send still pending, and the reason given is this message's
			tail = sent.handle((x, e) -> {
				if(e != null)
				{
					Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
					close(method + (c instanceof TimeoutException ? " could not be sent to Chrome in " + SEND_TIMEOUT_MS / 1000 + " s" : " could not be sent to Chrome: " + c));
				}
				return null;
			});
		}
		return sent;
	}

	/** Calls listener with the params of every event of this method. */
	void on(String method, Consumer<Map<String, Object>> listener)
	{
		listeners.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(listener);
	}

	/** Calls listener once, with the reason, when the connection ends for any reason. */
	void onClose(Consumer<String> listener)
	{
		closeListeners.add(listener);
		String why = closedWhy;
		if(why != null)
		{
			listener.accept(why);
		}
	}

	boolean isOpen()
	{
		return closedWhy == null;
	}

	/** Ends the connection; calls still waiting fail with why. */
	void close(String why)
	{
		if(end(why))
		{
			try
			{
				ws.abort();
			}
			catch(Exception e)
			{
				// gone already
			}
		}
	}

	// Fails every waiting call and tells the close listeners, once. @return true the first time
	private boolean end(String why)
	{
		synchronized(this)
		{
			if(closedWhy != null)
			{
				return false;
			}
			closedWhy = why;
		}
		Failure f = new Failure(name + ": " + why);
		waiting.values().forEach(x -> x.completeExceptionally(f));
		waiting.clear();
		for(Consumer<String> l: closeListeners)
		{
			try
			{
				l.accept(why);
			}
			catch(RuntimeException e)
			{
				Log.get().error("OverlayStudio capture: error after a DevTools connection closed", e);
			}
		}
		return true;
	}

	@Override
	public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last)
	{
		part.append(data);
		if(last)
		{
			String text = part.toString();
			if(part.capacity() > KEEP_CAPACITY)
			{
				part = new StringBuilder();
			}
			else
			{
				part.setLength(0);
			}
			try
			{
				dispatch(Json.object(text));
			}
			catch(RuntimeException e)
			{
				Log.get().error("OverlayStudio capture: unreadable message from Chrome on " + name, e);
			}
		}
		w.request(1);
		return null;
	}

	@SuppressWarnings("unchecked")
	private void dispatch(Map<String, Object> msg)
	{
		Object id = msg.get("id");
		if(id instanceof Number n)
		{
			CompletableFuture<Map<String, Object>> f = waiting.remove(n.intValue());
			if(f == null)
			{
				return;
			}
			if(msg.get("error") instanceof Map<?, ?> err)
			{
				f.completeExceptionally(new Failure(String.valueOf(err.get("message"))));
			}
			else
			{
				f.complete(msg.get("result") instanceof Map<?, ?> r ? (Map<String, Object>)r : Map.of());
			}
			return;
		}
		Object method = msg.get("method");
		List<Consumer<Map<String, Object>>> ls = method == null ? null : listeners.get(method.toString());
		if(ls == null)
		{
			return;
		}
		Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>)p : Map.of();
		for(Consumer<Map<String, Object>> l: ls)
		{
			try
			{
				l.accept(params);
			}
			catch(RuntimeException e)
			{
				Log.get().error("OverlayStudio capture: error handling " + method + " on " + name, e);
			}
		}
	}

	@Override
	public CompletionStage<?> onClose(WebSocket w, int status, String reason)
	{
		end("the DevTools connection closed (" + status + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")");
		return null;
	}

	@Override
	public void onError(WebSocket w, Throwable error)
	{
		end("the DevTools connection failed: " + error);
	}
}
