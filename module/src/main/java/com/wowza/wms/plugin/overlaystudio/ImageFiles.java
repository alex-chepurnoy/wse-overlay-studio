/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;

import com.wowza.wms.plugin.overlaystudio.templates.ImageLibrary;

/**
 * Decoded pictures for image layers (plan 3.5), shared by every stream, the draft render and thumbnails: one JVM-wide
 * cache keyed by file, modification time and size, so 12 streams on one overlay hold one decoded copy. Entries count
 * the layers holding them. An entry no layer holds is kept for reuse and evicted, least recently used first, once the
 * cache passes 512 MB; one a layer holds is never evicted.
 */
public final class ImageFiles
{
	/** Files larger than this are not decoded (the library's listing limit). */
	public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
	public static final long KEEP_BYTES = 512L * 1024 * 1024;
	// what unheld entries may fill before eviction; tests lower it
	static volatile long keepBytes = KEEP_BYTES;
	/** /info warns past this much held decoded memory. */
	public static final long WARN_BYTES = 1024L * 1024 * 1024;

	/** The content folder of a virtual host; tests replace it. */
	public static volatile Function<String, Path> contentDirOf = vhost -> {
		String dir = vhost == null ? null : com.wowza.wms.rest.vhosts.VHostsConfig.getVHostDir(vhost);
		return dir == null ? null : ImageLibrary.contentDir(Path.of(dir));
	};

	/** Which version of a file a decoded picture is: its path, modification time and size. */
	public record Key(Path file, long modified, long size)
	{
	}

	private static final class Entry
	{
		final BufferedImage image;
		final long bytes;
		int holders;

		Entry(BufferedImage image)
		{
			this.image = image;
			this.bytes = 4L * image.getWidth() * image.getHeight();
		}
	}

	// access order: the least recently used unheld entry is evicted first
	private static final LinkedHashMap<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true);
	private static long totalBytes = 0;

	private ImageFiles()
	{
	}

	/** The file's current key, or null when it does not exist or is not a regular file. */
	public static Key keyOf(Path file)
	{
		try
		{
			BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
			return a.isRegularFile() ? new Key(file, a.lastModifiedTime().toMillis(), a.size()) : null;
		}
		catch(IOException e)
		{
			return null;
		}
	}

	/** Why the file at key cannot be drawn, or null: too large in bytes or pixels, not an image. Decodes nothing. */
	static String check(Key key) throws IOException
	{
		if(key.size() > MAX_FILE_BYTES)
		{
			return key.file().getFileName() + " is over " + (MAX_FILE_BYTES >> 20) + " MB";
		}
		int[] size = ImageLibrary.dimensions(Files.readAllBytes(key.file()));
		if(size == null)
		{
			return key.file().getFileName() + " is not a PNG, JPEG, GIF or BMP image";
		}
		if(size[0] > ImageLibrary.MAX_SIDE || size[1] > ImageLibrary.MAX_SIDE)
		{
			return key.file().getFileName() + " is " + size[0] + " x " + size[1] + "; the most is " + ImageLibrary.MAX_SIDE + " pixels a side";
		}
		return null;
	}

	/** Takes a hold on the decoded picture for key, decoding it on first use. Throws IOException with the reason when it cannot be drawn. */
	public static BufferedImage acquire(Key key) throws IOException
	{
		synchronized(CACHE)
		{
			Entry e = CACHE.get(key);
			if(e != null)
			{
				e.holders++;
				return e.image;
			}
		}
		// decoded outside the lock: a large file must not stall every other layer's lookup
		String problem = check(key);
		if(problem != null)
		{
			throw new IOException(problem);
		}
		BufferedImage img = ImageIO.read(key.file().toFile());
		if(img == null)
		{
			throw new IOException(key.file().getFileName() + " could not be decoded");
		}
		synchronized(CACHE)
		{
			Entry e = CACHE.get(key);
			if(e == null)
			{
				e = new Entry(img);
				CACHE.put(key, e);
				totalBytes += e.bytes;
			}
			e.holders++;
			evict();
			return e.image;
		}
	}

	/** Takes another hold on an entry already held (a copy of a layer takes its own). */
	public static void hold(Key key)
	{
		synchronized(CACHE)
		{
			Entry e = CACHE.get(key);
			if(e != null)
			{
				e.holders++;
			}
		}
	}

	/** Lets go of one hold; the entry stays for reuse until the cache needs the room. */
	public static void release(Key key)
	{
		if(key == null)
		{
			return;
		}
		synchronized(CACHE)
		{
			Entry e = CACHE.get(key);
			if(e != null && e.holders > 0)
			{
				e.holders--;
				evict();
			}
		}
	}

	// caller holds CACHE
	private static void evict()
	{
		for(Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator(); it.hasNext() && totalBytes > keepBytes; )
		{
			Map.Entry<Key, Entry> e = it.next();
			if(e.getValue().holders == 0)
			{
				totalBytes -= e.getValue().bytes;
				it.remove();
			}
		}
	}

	/** For /info: decoded bytes in the cache, those layers hold, entries, and a warning past WARN_BYTES held. */
	public static Map<String, Object> stats()
	{
		synchronized(CACHE)
		{
			long held = 0;
			int heldEntries = 0;
			for(Entry e: CACHE.values())
			{
				if(e.holders > 0)
				{
					held += e.bytes;
					heldEntries++;
				}
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("decodedBytes", totalBytes);
			m.put("heldBytes", held);
			m.put("entries", CACHE.size());
			m.put("heldEntries", heldEntries);
			m.put("warning", held > WARN_BYTES ? "Image layers hold " + (held >> 20) + " MB of decoded pictures" : null);
			return m;
		}
	}

	/** Holders of key, for tests. */
	static int holders(Key key)
	{
		synchronized(CACHE)
		{
			Entry e = CACHE.get(key);
			return e == null ? -1 : e.holders;
		}
	}

	/** Empties the cache, for tests. */
	static void clearForTest()
	{
		synchronized(CACHE)
		{
			CACHE.clear();
			totalBytes = 0;
		}
	}
}
