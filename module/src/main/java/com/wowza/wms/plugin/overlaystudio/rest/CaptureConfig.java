/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import javax.imageio.ImageIO;

import org.restlet.resource.ResourceException;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioModule;
import com.wowza.wms.rest.WMSConfig;

/**
 * The last capture of one webPage object as base64 PNG inside JSON (the REST base class only answers JSON).
 * Keyed by default; ?raw=true gives the unkeyed capture, for picking a key color.
 */
@WMSConfig(restLocation = "/v1/{server}/plugin/overlaystudio/vhosts/{vhostName}/applications/{appName}/instances/{instance}/streams/{stream}/overlay/objects/{id}/capture", restVerbs = "GET", xmlFilename = "")
public class CaptureConfig extends StreamResourceBase
{
	protected String objectId = null;

	public CaptureConfig()
	{
		addFieldForKeyMap("id", "objectId");
	}

	@Override
	protected void doInit() throws ResourceException
	{
		super.doInit();
		addToStringKeyMapFromRequest("id");
		setPathMap();
	}

	// ConfigBase.get(Request) answers with a new instance of this class and passes the query string to it through
	// setQueryParam before calling loadObject, so the query is read from getQueryParam() in load(). Otherwise:
	// getQueryValue() and a field set in get(Request) both missed it.
	boolean rawRequested()
	{
		return isRaw(getQueryParam());
	}

	static boolean isRaw(String query)
	{
		if(query == null)
		{
			return false;
		}
		for(String part: query.split("&"))
		{
			if(part.equalsIgnoreCase("raw=true") || part.equalsIgnoreCase("raw=1"))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean load(OverlayStudioModule module, Map<String, Object> body)
	{
		ObjectConfig obj = module.findObject(streamName, objectId);
		if(obj == null || !"webPage".equals(obj.getType()))
		{
			return false;
		}
		boolean raw = rawRequested();
		BufferedImage img = raw ? obj.getLastRawImage() : obj.getLastKeyedImage();
		body.put("id", objectId);
		body.put("raw", raw);
		Object capturedAt = obj.getCaptureStatus().get("lastCaptureAt");
		body.put("capturedAt", capturedAt);
		body.put("width", img == null ? null : img.getWidth());
		body.put("height", img == null ? null : img.getHeight());
		body.put("png", img == null ? null : toBase64Png(img));
		return true;
	}

	/**
	 * The image scaled down to at most maxWidth pixels wide, keeping its shape; itself when it is narrower or maxWidth
	 * is 0. The plugin page asks for the size it shows, so a 1280 px page drawn 300 px wide is encoded and sent at
	 * 300 px: the encoding and the transfer, not Chrome, are most of a preview's time.
	 */
	static BufferedImage fit(BufferedImage img, int maxWidth)
	{
		if(img == null || maxWidth <= 0 || img.getWidth() <= maxWidth)
		{
			return img;
		}
		int h = Math.max(1, (int)Math.round((double)img.getHeight() * maxWidth / img.getWidth()));
		BufferedImage out = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, maxWidth, h, null);
		g.dispose();
		return out;
	}

	/** A width asked for in a query or body: a whole number from 16 to 7680, else 0 for full size. */
	static int width(Object v)
	{
		try
		{
			int w = v == null ? 0 : (int)Math.round(Double.parseDouble(String.valueOf(v)));
			return w >= 16 && w <= 7680 ? w : 0;
		}
		catch(NumberFormatException e)
		{
			return 0;
		}
	}

	/** How long a preview over REST waits for a page's first frame: a Chrome start and a page load. */
	public static final long FIRST_FRAME_WAIT_MS = 30_000;

	/** How old a screenshot may be when the page asks again: most of the capture interval, as for streams. */
	static long maxAgeMs(Object refreshRate)
	{
		double r;
		try
		{
			r = refreshRate == null ? 0.5 : Double.parseDouble(String.valueOf(refreshRate));
		}
		catch(NumberFormatException e)
		{
			r = 0.5;
		}
		return (long)(Math.max(0.1, r) * 800);
	}

	static String toBase64Png(BufferedImage img)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch(IOException e)
		{
			return null;
		}
	}
}
