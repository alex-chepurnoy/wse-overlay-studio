/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

/**
 * The service runs Chrome under its own unprivileged account, never as root or SYSTEM: a page that exploited Chrome
 * would otherwise hold the whole machine, and Chrome's sandbox does not start as root anyway.
 */
public final class AccountCheck
{
	/** The well-known SID of LocalSystem. */
	static final String SYSTEM_SID = "S-1-5-18";

	private AccountCheck()
	{
	}

	/**
	 * Why the service must not start as this account, or null when it may.
	 * @param windows whether this is Windows
	 * @param user the account name (user.name)
	 * @param sid the Windows account's SID, or null when unknown or not Windows
	 */
	public static String refusal(boolean windows, String user, String sid)
	{
		if(!windows && "root".equals(user))
		{
			return "The Overlay Studio capture service does not run as root. Run it under its own account (the installer creates wse-overlay-studio and "
					+ "the WowzaOverlayStudioCapture.service unit does this).";
		}
		if(windows && (SYSTEM_SID.equals(sid) || "SYSTEM".equalsIgnoreCase(user)))
		{
			return "The Overlay Studio capture service does not run as LocalSystem. Run it under its own account (the installer registers the "
					+ "WowzaOverlayStudioCapture service as NT SERVICE\\WowzaOverlayStudioCapture).";
		}
		return null;
	}

	/** The refusal for the account this process runs as. */
	public static String current()
	{
		boolean windows = CaptureService.isWindows();
		return refusal(windows, System.getProperty("user.name"), windows ? windowsSid() : null);
	}

	// the process token's user SID; read through the JDK's NT login module, which ships with every JDK
	private static String windowsSid()
	{
		try
		{
			Class<?> c = Class.forName("com.sun.security.auth.module.NTSystem");
			Object nt = c.getConstructor().newInstance();
			return (String)c.getMethod("getUserSID").invoke(nt);
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError e)
		{
			return null;
		}
	}
}
