/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.templates;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Image files for the transcoder's own image overlays. WSE loads an overlay image as
 * a local file with ImageIO (TranscoderVideoOverlayFrame); templates name it from the virtual host's content folder,
 * ${com.wowza.wms.context.VHostConfigHome}/content. Uploads go to content/overlaystudio/, the only place this deletes from.
 */
public final class ImageLibrary
{
	public static final String UPLOADS = "overlaystudio";
	public static final long MAX_BYTES = 2L * 1024 * 1024;
	public static final int MAX_SIDE = 4096;
	private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.(png|jpg|jpeg|gif|bmp)", Pattern.CASE_INSENSITIVE);

	private ImageLibrary()
	{
	}

	public static Path contentDir(Path vhostDir)
	{
		return vhostDir.resolve("content");
	}

	/** The images in content/ and content/overlaystudio/, as file (relative to content), size and a small preview. */
	public static List<Map<String, Object>> list(Path vhostDir)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		Path content = contentDir(vhostDir);
		for(Path dir: new Path[] { content, content.resolve(UPLOADS) })
		{
			File[] files = dir.toFile().listFiles(f -> f.isFile() && FILE.matcher(f.getName()).matches() && f.length() <= MAX_BYTES * 4);
			if(files == null)
			{
				continue;
			}
			java.util.Arrays.sort(files);
			for(File f: files)
			{
				try
				{
					Listed l = listed(f);
					if(l == null)
					{
						continue;
					}
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("file", dir.equals(content) ? f.getName() : UPLOADS + "/" + f.getName());
					m.put("width", l.width());
					m.put("height", l.height());
					m.put("bytes", f.length());
					m.put("uploaded", !dir.equals(content));
					m.put("preview", l.preview());
					out.add(m);
				}
				catch(Exception e)
				{
					// not an image ImageIO reads, so WSE could not load it either
				}
			}
		}
		return out;
	}

	// one listed image: its size and preview, kept while the file's size and time are unchanged
	private record Listed(long stamp, long bytes, int width, int height, String preview)
	{
	}

	private static final Map<String, Listed> LISTED = new java.util.concurrent.ConcurrentHashMap<>();

	private static Listed listed(File f) throws IOException
	{
		String key = f.getAbsolutePath();
		Listed l = LISTED.get(key);
		if(l != null && l.stamp() == f.lastModified() && l.bytes() == f.length())
		{
			return l;
		}
		int[] size = dimensions(Files.readAllBytes(f.toPath()));
		if(size == null)
		{
			return null;
		}
		String preview = null;
		// an image larger than an upload may be is listed, but not decoded for a preview
		if(size[0] <= MAX_SIDE && size[1] <= MAX_SIDE)
		{
			BufferedImage img = ImageIO.read(f);
			if(img == null)
			{
				return null;
			}
			preview = "data:image/png;base64," + Base64.getEncoder().encodeToString(thumbnail(img, 160));
		}
		l = new Listed(f.lastModified(), f.length(), size[0], size[1], preview);
		if(LISTED.size() > 1000)
		{
			LISTED.clear();
		}
		LISTED.put(key, l);
		return l;
	}

	/** An image's width and height from its header, without decoding it; null when ImageIO has no reader for it. */
	public static int[] dimensions(byte[] bytes) throws IOException
	{
		try(javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			java.util.Iterator<javax.imageio.ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
			if(readers == null || !readers.hasNext())
			{
				return null;
			}
			javax.imageio.ImageReader r = readers.next();
			try
			{
				r.setInput(in, true, true);
				return new int[] { r.getWidth(0), r.getHeight(0) };
			}
			finally
			{
				r.dispose();
			}
		}
	}

	/**
	 * One image as a data URL with its size, for the editor's canvas: {file, width, height, data}. 404 when it is not
	 * there; refused past the size an image layer draws (ImageFiles), so a page never pulls a huge file.
	 */
	public static Map<String, Object> data(Path vhostDir, String file) throws TemplateFiles.Refused, IOException
	{
		String f = safeFile(file);
		Path p = contentDir(vhostDir).resolve(f);
		if(!Files.isRegularFile(p))
		{
			throw new TemplateFiles.Refused(404, "No image '" + f + "' in the content folder");
		}
		if(Files.size(p) > com.wowza.wms.plugin.overlaystudio.ImageFiles.MAX_FILE_BYTES)
		{
			throw new TemplateFiles.Refused(413, f + " is over " + (com.wowza.wms.plugin.overlaystudio.ImageFiles.MAX_FILE_BYTES >> 20) + " MB");
		}
		byte[] bytes = Files.readAllBytes(p);
		int[] size = dimensions(bytes);
		if(size == null)
		{
			throw new TemplateFiles.Refused(415, f + " is not a PNG, JPEG, GIF or BMP image");
		}
		String lower = f.toLowerCase(java.util.Locale.ROOT);
		String type = lower.endsWith(".png") ? "png" : lower.endsWith(".gif") ? "gif" : lower.endsWith(".bmp") ? "bmp" : "jpeg";
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("file", f);
		m.put("width", size[0]);
		m.put("height", size[1]);
		m.put("data", "data:image/" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes));
		return m;
	}

	/** Checks a file name relative to content/: a plain name in content/ or in content/overlaystudio/. */
	public static String safeFile(String file) throws TemplateFiles.Refused
	{
		String f = file == null ? "" : file.trim();
		String name = f.startsWith(UPLOADS + "/") ? f.substring(UPLOADS.length() + 1) : f;
		if(!FILE.matcher(name).matches() || name.contains(".."))
		{
			throw new TemplateFiles.Refused(400, "Not a usable image name: '" + file + "'. A .png, .jpg, .gif or .bmp file in the content folder.");
		}
		return f.startsWith(UPLOADS + "/") ? UPLOADS + "/" + name : name;
	}

	/** Saves an upload in content/overlaystudio/ after ImageIO has read it, as WSE will. @return the file, relative to content */
	public static String save(Path vhostDir, String name, String base64) throws TemplateFiles.Refused, IOException
	{
		String n = safeFile(name);
		if(n.startsWith(UPLOADS + "/"))
		{
			n = n.substring(UPLOADS.length() + 1);
		}
		byte[] bytes;
		try
		{
			String b = base64 == null ? "" : base64.replaceFirst("^data:[^,]*,", "");
			bytes = Base64.getDecoder().decode(b);
		}
		catch(IllegalArgumentException e)
		{
			throw new TemplateFiles.Refused(400, "The image data could not be read.");
		}
		if(bytes.length == 0 || bytes.length > MAX_BYTES)
		{
			throw new TemplateFiles.Refused(400, "An image is at most 2 MB.");
		}
		// the size from the header first: a small file can declare a frame too large to decode in memory
		int[] size;
		try
		{
			size = dimensions(bytes);
		}
		catch(IOException e)
		{
			size = null;
		}
		if(size == null)
		{
			throw new TemplateFiles.Refused(400, "Not an image WSE can load (PNG, JPEG, GIF or BMP).");
		}
		if(size[0] > MAX_SIDE || size[1] > MAX_SIDE)
		{
			throw new TemplateFiles.Refused(400, "An image is at most " + MAX_SIDE + " pixels on a side.");
		}
		// a readable header does not prove the image data decodes
		BufferedImage img;
		try
		{
			img = ImageIO.read(new ByteArrayInputStream(bytes));
		}
		catch(IOException e)
		{
			img = null;
		}
		if(img == null)
		{
			throw new TemplateFiles.Refused(400, "Not an image WSE can load (PNG, JPEG, GIF or BMP).");
		}
		Path dir = contentDir(vhostDir).resolve(UPLOADS);
		Path target = dir.resolve(n).normalize();
		if(!target.getParent().equals(dir.normalize()))
		{
			throw new TemplateFiles.Refused(400, "Image name leaves the upload folder: " + n);
		}
		Files.createDirectories(dir);
		Files.write(target, bytes);
		return UPLOADS + "/" + n;
	}

	/** Deletes an uploaded image; images elsewhere in content/ are not this module's to delete. */
	public static void delete(Path vhostDir, String file) throws TemplateFiles.Refused, IOException
	{
		String f = safeFile(file);
		if(!f.startsWith(UPLOADS + "/"))
		{
			throw new TemplateFiles.Refused(409, file + " was not uploaded here; it is not deleted.");
		}
		Path p = contentDir(vhostDir).resolve(f).normalize();
		if(!Files.deleteIfExists(p))
		{
			throw new TemplateFiles.Refused(404, "No image " + file);
		}
	}

	private static byte[] thumbnail(BufferedImage img, int max) throws IOException
	{
		double s = Math.min(1.0, (double)max / Math.max(img.getWidth(), img.getHeight()));
		int w = Math.max(1, (int)Math.round(img.getWidth() * s)), h = Math.max(1, (int)Math.round(img.getHeight() * s));
		BufferedImage t = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = t.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, w, h, null);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(t, "png", out);
		return out.toByteArray();
	}

	static String lower(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}
}
