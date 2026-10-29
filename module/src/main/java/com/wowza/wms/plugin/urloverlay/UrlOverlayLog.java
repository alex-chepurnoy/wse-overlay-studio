/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.rest.ConfigBase;

/**
 * Log lines for changes made through the module's REST API. They go to WSE's root logger, which the stock
 * conf/log4j2-config.xml writes at INFO to logs/wowzastreamingengine_access.log (and WARN to the error log), so
 * "UrlOverlay" finds every module line there. URLs are logged without their query string.
 */
public final class UrlOverlayLog
{
	public static final String PREFIX = "UrlOverlay ";

	private UrlOverlayLog()
	{
	}

	private static WMSLogger log()
	{
		return WMSLoggerFactory.getLogger(null);
	}

	/** A change applied through REST, with the authenticated user when there is one. */
	public static void change(ConfigBase resource, String what)
	{
		log().info(PREFIX + "change: " + what + " (user " + user(resource) + ")");
	}

	/** A REST request the module refused. */
	public static void refused(ConfigBase resource, String request, int status, String message)
	{
		log().warn(PREFIX + "refused " + request + ": " + status + " " + message + " (user " + user(resource) + ")");
	}

	public static void info(String message)
	{
		log().info(PREFIX + message);
	}

	public static String stream(String vhost, String app, String instance, String stream)
	{
		return vhost + "/" + app + "/" + instance + "/" + stream;
	}

	private static String user(ConfigBase resource)
	{
		return resource == null || resource.getUser() == null ? "none" : resource.getUser().getIdentifier();
	}
}
