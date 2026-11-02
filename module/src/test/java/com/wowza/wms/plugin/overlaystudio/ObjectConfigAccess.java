/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.lang.reflect.Method;

/** Reaches ObjectConfig's package-private date boundary from tests in this package. */
final class ObjectConfigAccess
{
	private ObjectConfigAccess()
	{
	}

	static long boundary(String pattern, long now)
	{
		try
		{
			Method m = com.wowza.wms.plugin.overlaystudio.rest.ObjectConfig.class.getDeclaredMethod("nextDateBoundary", String.class, long.class);
			m.setAccessible(true);
			return (long)m.invoke(null, pattern, now);
		}
		catch(ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
