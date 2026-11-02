/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

/** The next boundary of a time pattern in the server's zone, as a {time:...} token wakes for it. */
final class ObjectConfigAccess
{
	private ObjectConfigAccess()
	{
	}

	static long boundary(String pattern, long now)
	{
		return TextTokens.nextTimeBoundary(pattern, now, java.time.ZoneId.systemDefault());
	}
}
