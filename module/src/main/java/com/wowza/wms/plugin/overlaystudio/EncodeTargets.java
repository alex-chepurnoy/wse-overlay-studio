/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which encodes an overlay goes on. Each entry of overlayEncodes is matched, as a whole-string regex, against the
 * encode's name from the template (for example "uo360") and against its output stream name (for example
 * "myStream_uo360"), so a list of encode names matches as well as a list of stream names.
 */
final class EncodeTargets
{
	private EncodeTargets()
	{
	}

	/**
	 * @return true if the overlay belongs on this encode; an empty or null list, or "*", means every encode
	 */
	static boolean matches(List<String> targets, String encodeName, String outputStreamName)
	{
		if(targets == null || targets.isEmpty() || targets.contains("*"))
		{
			return true;
		}
		for(String target: targets)
		{
			if(target == null || target.isEmpty())
			{
				continue;
			}
			if(target.equals(encodeName) || target.equals(outputStreamName))
			{
				return true;
			}
			try
			{
				Pattern p = Pattern.compile(target);
				if((encodeName != null && p.matcher(encodeName).matches()) || (outputStreamName != null && p.matcher(outputStreamName).matches()))
				{
					return true;
				}
			}
			catch(PatternSyntaxException e)
			{
				// not a valid regex: only the exact comparison above applies
			}
		}
		return false;
	}
}
