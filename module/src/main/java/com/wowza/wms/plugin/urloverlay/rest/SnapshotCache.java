/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.rest;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Output snapshots, one per output and width, kept until the output's last key frame changes, so a repeated request
 * decodes nothing. Decodes run on their own threads, at most cap at once across the server; a caller waits at most
 * timeoutMs for a permit and the picture, and a decode that outlives its caller still finishes, fills the cache and
 * holds its permit until then, so the cap counts what the native decoders are really doing.
 */
// Spike for the snapshot decision (docs/ux/spec.md 5.5); may be removed once that decision is made.
public final class SnapshotCache
{
	/** Server.xml property: decodes at once. */
	public static final String PROP_MAX_DECODES = "UrlOverlaySnapshotMaxDecodes";
	/** Server.xml property: how long a request waits for a permit and the picture, in milliseconds. */
	public static final String PROP_TIMEOUT = "UrlOverlaySnapshotTimeoutMs";
	public static final int DEFAULT_MAX_DECODES = 2;
	public static final long DEFAULT_TIMEOUT_MS = 3000;
	static final int MAX_ENTRIES = 32;

	/** One decoded picture and what it cost. */
	public record Shot(long keyFrameTimecode, int width, int height, byte[] jpeg, long decodeMs, long cpuMs, long encodeMs, long madeAt)
	{
	}

	/** Makes the picture for the key frame the request saw. */
	public interface Decode
	{
		Shot run() throws Exception;
	}

	private static volatile SnapshotCache server = new SnapshotCache(DEFAULT_MAX_DECODES, DEFAULT_TIMEOUT_MS, daemonPool());

	/** The server's cache; replaced by configure. */
	public static SnapshotCache server()
	{
		return server;
	}

	/** Sets the server's cap and timeout, from Server.xml at server start. */
	public static void configure(int maxDecodes, long timeoutMs)
	{
		server = new SnapshotCache(Math.max(1, maxDecodes), Math.max(100, timeoutMs), daemonPool());
	}

	private static Executor daemonPool()
	{
		AtomicInteger n = new AtomicInteger();
		return Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "urloverlay-snapshot-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	private final int cap;
	private final long timeoutMs;
	private final Executor executor;
	private final Semaphore permits;
	private final Map<String, Shot> shots = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Shot>> inFlight = new ConcurrentHashMap<>();
	private final AtomicInteger decoding = new AtomicInteger();
	private final AtomicInteger peak = new AtomicInteger();

	SnapshotCache(int cap, long timeoutMs, Executor executor)
	{
		this.cap = cap;
		this.timeoutMs = timeoutMs;
		this.executor = executor;
		this.permits = new Semaphore(cap, true);
	}

	public int cap()
	{
		return cap;
	}

	public long timeoutMs()
	{
		return timeoutMs;
	}

	/** Decodes running now. */
	public int decoding()
	{
		return decoding.get();
	}

	/** The most decodes that ever ran at once. */
	public int peak()
	{
		return peak.get();
	}

	/**
	 * The picture of key frame keyFrameTimecode for this output and width: from the cache when it holds that key
	 * frame, otherwise decoded. The map holds shot (the Shot), cached, waitMs, and when no picture came, busy (no
	 * permit within the timeout) or timedOut (the decode is still running) or error.
	 */
	public Map<String, Object> get(String key, long keyFrameTimecode, Decode decode)
	{
		long start = System.nanoTime();
		Map<String, Object> m = new LinkedHashMap<>();
		Shot have = shots.get(key);
		if(have != null && have.keyFrameTimecode() == keyFrameTimecode)
		{
			m.put("shot", have);
			m.put("cached", true);
			m.put("waitMs", 0L);
			return m;
		}
		m.put("cached", false);
		String flight = key + "@" + keyFrameTimecode;
		CompletableFuture<Shot> f = inFlight.get(flight);
		try
		{
			if(f == null)
			{
				if(!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS))
				{
					m.put("busy", true);
					m.put("waitMs", ms(start));
					return m;
				}
				// a decode of this key frame may have finished while this caller waited for the permit
				Shot now = shots.get(key);
				if(now != null && now.keyFrameTimecode() == keyFrameTimecode)
				{
					permits.release();
					m.put("shot", now);
					m.put("cached", true);
					m.put("waitMs", ms(start));
					return m;
				}
				CompletableFuture<Shot> mine = new CompletableFuture<>();
				f = inFlight.putIfAbsent(flight, mine);
				if(f != null)
				{
					permits.release();
				}
				else
				{
					f = mine;
					start(key, flight, mine, decode);
				}
			}
			long left = timeoutMs - ms(start);
			Shot s = f.get(Math.max(1, left), TimeUnit.MILLISECONDS);
			m.put("shot", s);
		}
		catch(TimeoutException e)
		{
			m.put("timedOut", true);
		}
		catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			m.put("error", "interrupted");
		}
		catch(ExecutionException e)
		{
			Throwable c = e.getCause() == null ? e : e.getCause();
			m.put("error", c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
		}
		m.put("waitMs", ms(start));
		return m;
	}

	private void start(String key, String flight, CompletableFuture<Shot> mine, Decode decode)
	{
		try
		{
			executor.execute(() -> {
				int now = decoding.incrementAndGet();
				peak.accumulateAndGet(now, Math::max);
				try
				{
					Shot s = decode.run();
					if(s == null)
					{
						throw new IllegalStateException("The thumbnailer returned no picture");
					}
					put(key, s);
					mine.complete(s);
				}
				catch(Throwable t)
				{
					mine.completeExceptionally(t);
				}
				finally
				{
					decoding.decrementAndGet();
					inFlight.remove(flight, mine);
					permits.release();
				}
			});
		}
		catch(RuntimeException e)
		{
			inFlight.remove(flight, mine);
			permits.release();
			mine.completeExceptionally(e);
		}
	}

	private void put(String key, Shot s)
	{
		shots.put(key, s);
		while(shots.size() > MAX_ENTRIES)
		{
			shots.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().madeAt())).ifPresent(e -> shots.remove(e.getKey(), e.getValue()));
		}
	}

	private static long ms(long startNanos)
	{
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}
}
