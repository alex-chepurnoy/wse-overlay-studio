/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One capture per distinct page, shared by every layer that shows it. A capture whose last user lets go stays open
 * for a grace period, so replacing an overlay (an edit, a template reassigned) reuses the open browser instead of
 * closing it and starting another.
 *
 * @param <K> what makes two captures the same page
 * @param <C> the capture
 */
public final class CapturePool<K, C>
{
	private final Function<K, C> open;
	private final Consumer<C> close;
	private final long graceMs;
	private final ScheduledExecutorService timer;
	private final Map<K, Entry<C>> byKey = new HashMap<>();
	private final Map<C, K> keys = new IdentityHashMap<>();

	private static final class Entry<C>
	{
		final C capture;
		int users;
		ScheduledFuture<?> closing;

		Entry(C capture)
		{
			this.capture = capture;
		}
	}

	public CapturePool(Function<K, C> open, Consumer<C> close, long graceMs, ScheduledExecutorService timer)
	{
		this.open = open;
		this.close = close;
		this.graceMs = graceMs;
		this.timer = timer;
	}

	/** The open capture for this page, or a new one. Every acquire needs a release. */
	public synchronized C acquire(K key)
	{
		Entry<C> e = byKey.get(key);
		if(e == null)
		{
			e = new Entry<>(open.apply(key));
			byKey.put(key, e);
			keys.put(e.capture, key);
		}
		if(e.closing != null)
		{
			e.closing.cancel(false);
			e.closing = null;
		}
		e.users++;
		return e.capture;
	}

	public void release(C capture)
	{
		C toClose = null;
		synchronized(this)
		{
			K key = keys.get(capture);
			Entry<C> e = key == null ? null : byKey.get(key);
			if(e == null || e.users <= 0)
			{
				return;
			}
			e.users--;
			if(e.users > 0)
			{
				return;
			}
			if(graceMs > 0 && timer != null)
			{
				Entry<C> idle = e;
				idle.closing = timer.schedule(() -> closeIfIdle(key, idle), graceMs, TimeUnit.MILLISECONDS);
				return;
			}
			forget(key);
			toClose = e.capture;
		}
		close.accept(toClose);
	}

	private void closeIfIdle(K key, Entry<C> e)
	{
		synchronized(this)
		{
			if(e.users > 0 || byKey.get(key) != e)
			{
				return;
			}
			forget(key);
		}
		close.accept(e.capture);
	}

	private void forget(K key)
	{
		Entry<C> e = byKey.remove(key);
		if(e != null)
		{
			keys.remove(e.capture);
		}
	}

	/** Layers using this capture now; 0 while it waits out the grace period. */
	public synchronized int users(C capture)
	{
		K key = keys.get(capture);
		Entry<C> e = key == null ? null : byKey.get(key);
		return e == null ? 0 : e.users;
	}

	/** Every capture open, including those in their grace period. */
	public synchronized java.util.List<C> all()
	{
		return new java.util.ArrayList<>(keys.keySet());
	}

	/** Distinct pages open, including those in their grace period. */
	public synchronized int size()
	{
		return byKey.size();
	}
}
