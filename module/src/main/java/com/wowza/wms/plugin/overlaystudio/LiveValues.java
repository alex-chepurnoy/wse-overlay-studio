/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.plugin.overlaystudio.templates.TemplateFiles;

/**
 * Live values on named overlays' layers (plan 3.6): text, colors, opacity and hidden set over REST by a score feed or
 * a ticker, drawn over the saved layer without rewriting the overlay file. One store per JVM, keyed by virtual host,
 * application and overlay, so every instance of an application sees the same values. Kept across a WSE restart in
 * a sidecar per overlay, .live/&lt;name&gt;.live in the application's overlay folder (never listed as an overlay),
 * written at most once a second, atomically.
 */
public final class LiveValues
{
	/** Writes allowed per overlay per second; more answer 429. */
	public static final int RATE = 20;
	static final long FLUSH_MS = 1000;
	private static final ObjectMapper JSON = new ObjectMapper();

	public record Key(String vhost, String app, String name)
	{
	}

	private static final class Entry
	{
		final Key key;
		final Path sidecar;
		// replaced on every write, never changed: readers take it whole
		volatile Map<String, Map<String, Object>> values;
		// times of the last RATE writes, oldest at next
		final long[] recent = new long[RATE];
		int next = 0;
		boolean dirty = false, flushPlanned = false;
		long lastFlush = 0;

		Entry(Key key, Path sidecar, Map<String, Map<String, Object>> values)
		{
			this.key = key;
			this.sidecar = sidecar;
			this.values = values;
			java.util.Arrays.fill(recent, Long.MIN_VALUE / 2);
		}
	}

	private static final Map<Key, Entry> STORE = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService FLUSH = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "OverlayStudio-live-values");
		t.setDaemon(true);
		return t;
	});
	// tests move time
	static volatile LongSupplier clock = System::currentTimeMillis;

	private LiveValues()
	{
	}

	/** The sidecar file of overlay name in the application's overlay folder dir. */
	public static Path sidecar(Path dir, String name)
	{
		return dir.resolve(".live").resolve(name + ".live");
	}

	@SuppressWarnings("unchecked")
	private static Entry entry(Key key, Path dir)
	{
		return STORE.computeIfAbsent(key, k -> {
			Path f = sidecar(dir, k.name());
			Map<String, Map<String, Object>> v = new LinkedHashMap<>();
			if(Files.isRegularFile(f))
			{
				try
				{
					Map<String, Object> read = JSON.readValue(f.toFile(), Map.class);
					for(Map.Entry<String, Object> e: read.entrySet())
					{
						if(e.getValue() instanceof Map)
						{
							v.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>)e.getValue())));
						}
					}
				}
				catch(IOException | RuntimeException e)
				{
					WMSLoggerFactory.getLogger(LiveValues.class).warn("OverlayStudio: could not read live values " + f + ": " + e);
				}
			}
			return new Entry(k, f, Collections.unmodifiableMap(v));
		});
	}

	/** The overlay's live values, {id: {field: value}}; empty when none are set. Never changes once returned. */
	public static Map<String, Map<String, Object>> get(Key key, Path dir)
	{
		return entry(key, dir).values;
	}

	/** Merges fields into the live value of layer id, already checked. 429 above RATE writes a second. */
	public static Map<String, Object> set(Key key, Path dir, String id, Map<String, Object> fields) throws TemplateFiles.Refused
	{
		Entry e = entry(key, dir);
		synchronized(e)
		{
			admit(e, key);
			Map<String, Map<String, Object>> n = new LinkedHashMap<>(e.values);
			Map<String, Object> v = new LinkedHashMap<>(n.getOrDefault(id, Map.of()));
			v.putAll(fields);
			Map<String, Object> merged = Collections.unmodifiableMap(v);
			n.put(id, merged);
			replace(e, n);
			return merged;
		}
	}

	/** Clears layer id's live value; false when it had none. */
	public static boolean clear(Key key, Path dir, String id) throws TemplateFiles.Refused
	{
		Entry e = entry(key, dir);
		synchronized(e)
		{
			admit(e, key);
			if(!e.values.containsKey(id))
			{
				return false;
			}
			Map<String, Map<String, Object>> n = new LinkedHashMap<>(e.values);
			n.remove(id);
			replace(e, n);
			return true;
		}
	}

	/** Clears every live value on the overlay. */
	public static void clearAll(Key key, Path dir) throws TemplateFiles.Refused
	{
		Entry e = entry(key, dir);
		synchronized(e)
		{
			admit(e, key);
			replace(e, new LinkedHashMap<>());
		}
	}

	/**
	 * After the overlay is saved: values move with renamed layers and go with layers that are gone; the rest stay.
	 * @return whether any value changed
	 */
	public static boolean afterSave(Key key, Path dir, Set<String> ids, Map<String, String> renamed)
	{
		Entry e = entry(key, dir);
		synchronized(e)
		{
			Map<String, Map<String, Object>> n = new LinkedHashMap<>();
			for(Map.Entry<String, Map<String, Object>> v: e.values.entrySet())
			{
				String id = renamed.getOrDefault(v.getKey(), v.getKey());
				if(ids.contains(id))
				{
					n.put(id, v.getValue());
				}
			}
			if(n.equals(e.values))
			{
				return false;
			}
			replace(e, n);
			return true;
		}
	}

	/** The overlay was deleted: its values and sidecar go with it. */
	public static void delete(Key key, Path dir) throws IOException
	{
		Entry e = STORE.remove(key);
		if(e != null)
		{
			synchronized(e)
			{
				e.dirty = false;
			}
		}
		Files.deleteIfExists(sidecar(dir, key.name()));
	}

	// caller holds e
	private static void admit(Entry e, Key key) throws TemplateFiles.Refused
	{
		long now = clock.getAsLong();
		if(now - e.recent[e.next] < 1000)
		{
			throw new TemplateFiles.Refused(429, "More than " + RATE + " live writes a second to overlay " + key.name() + "; slow the feed down");
		}
		e.recent[e.next] = now;
		e.next = (e.next + 1) % RATE;
	}

	// caller holds e
	private static void replace(Entry e, Map<String, Map<String, Object>> n)
	{
		e.values = Collections.unmodifiableMap(n);
		e.dirty = true;
		if(!e.flushPlanned)
		{
			e.flushPlanned = true;
			long wait = Math.max(0, e.lastFlush + FLUSH_MS - clock.getAsLong());
			FLUSH.schedule(() -> flush(e), wait, TimeUnit.MILLISECONDS);
		}
	}

	private static void flush(Entry e)
	{
		Map<String, Map<String, Object>> v;
		synchronized(e)
		{
			e.flushPlanned = false;
			// an overlay deleted (or a store forgotten) since this flush was planned writes nothing
			if(!e.dirty || STORE.get(e.key) != e)
			{
				return;
			}
			e.dirty = false;
			e.lastFlush = clock.getAsLong();
			v = e.values;
		}
		try
		{
			write(e.sidecar, v);
		}
		catch(IOException | RuntimeException x)
		{
			WMSLoggerFactory.getLogger(LiveValues.class).warn("OverlayStudio: could not write live values " + e.sidecar + ": " + x);
		}
	}

	private static void write(Path f, Map<String, Map<String, Object>> v) throws IOException
	{
		if(v.isEmpty())
		{
			Files.deleteIfExists(f);
			return;
		}
		Files.createDirectories(f.getParent());
		Path tmp = Files.createTempFile(f.getParent(), "." + f.getFileName(), ".tmp");
		try
		{
			Files.write(tmp, JSON.writeValueAsBytes(v));
			try
			{
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch(AtomicMoveNotSupportedException x)
			{
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(tmp);
		}
	}

	/** Writes every pending sidecar now, for tests. */
	static void flushForTest()
	{
		for(Entry e: STORE.values())
		{
			flush(e);
		}
	}

	/** Waits for a flush the flush thread is running to finish, for tests; one planned later finds its entry forgotten. */
	static void drainForTest() throws Exception
	{
		FLUSH.submit(() -> {
		}).get(5, TimeUnit.SECONDS);
	}

	/** Forgets every value held in memory (as a WSE restart does), for tests. */
	static void forgetForTest()
	{
		STORE.clear();
	}
}
