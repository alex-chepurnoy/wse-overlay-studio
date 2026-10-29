/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

/**
 * Pure crop arithmetic for placing an ABGR overlay buffer inside a video frame. Kept free of WSE types so the
 * math can be tested on its own.
 */
final class OverlayCrop
{
	static final int BYTES_PER_PIXEL = 4;

	/** The part of an overlay that lands inside the frame, in frame coordinates. */
	record Region(int x, int y, int width, int height)
	{
	}

	private OverlayCrop()
	{
	}

	/**
	 * @return the visible part of a width x height overlay placed at (dstX,dstY) in a frameWidth x frameHeight
	 * frame, or null if none of it is visible
	 */
	static Region visible(int frameWidth, int frameHeight, int width, int height, int dstX, int dstY)
	{
		int x0 = Math.max(dstX, 0);
		int y0 = Math.max(dstY, 0);
		int x1 = Math.min(dstX + width, frameWidth);
		int y1 = Math.min(dstY + height, frameHeight);
		int cropWidth = x1 - x0;
		int cropHeight = y1 - y0;
		if(cropWidth <= 0 || cropHeight <= 0)
		{
			return null;
		}
		return new Region(x0, y0, cropWidth, cropHeight);
	}

	/**
	 * Copies the visible region out of a width-pixel-wide overlay buffer that was placed at (dstX,dstY).
	 */
	static byte[] crop(byte[] buffer, int width, int dstX, int dstY, Region visible)
	{
		int srcXOffset = visible.x() - dstX;
		int srcYOffset = visible.y() - dstY;
		byte[] cropped = new byte[visible.width() * visible.height() * BYTES_PER_PIXEL];
		int srcRowBytes = width * BYTES_PER_PIXEL;
		int cropRowBytes = visible.width() * BYTES_PER_PIXEL;
		for(int row = 0; row < visible.height(); row++)
		{
			int srcPos = (srcYOffset + row) * srcRowBytes + srcXOffset * BYTES_PER_PIXEL;
			System.arraycopy(buffer, srcPos, cropped, row * cropRowBytes, cropRowBytes);
		}
		return cropped;
	}
}
