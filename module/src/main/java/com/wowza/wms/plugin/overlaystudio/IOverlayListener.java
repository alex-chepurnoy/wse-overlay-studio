/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import org.apache.logging.log4j.Level;

public interface IOverlayListener
{
	public abstract void onOverlayMessage (String streamName, Level level, String message);

}
