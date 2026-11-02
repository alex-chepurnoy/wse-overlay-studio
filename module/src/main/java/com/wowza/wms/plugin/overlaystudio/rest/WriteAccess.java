/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.util.function.Predicate;

import org.restlet.Application;
import org.restlet.security.User;

import com.wowza.wms.plugin.overlaystudio.OverlayStudioServer;
import com.wowza.wms.rest.restserver.RESTConfig;
import com.wowza.wms.rest.restserver.RestletApplication;
import com.wowza.wms.rest.user.UsersConfig;

/**
 * Who may change anything through the module's REST resources: a user WSE REST authenticated who is in the admin group
 * of conf/admin.password, the group WSE Manager shows as "Administrator". Reads need only an authenticated user.
 * Without REST authentication there is no user to check, so every change is refused; reads stay open.
 */
public final class WriteAccess
{
	public static final String ADMIN_GROUP = "admin";

	/** What the page shows when it is read only; also the start of every refusal. */
	public static final String READ_ONLY = "Read only";

	// replaced by tests; WSE reads conf/admin.password again when it has changed
	static volatile Predicate<String> isAdmin = user -> UsersConfig.isMemberOf(user, ADMIN_GROUP);

	private WriteAccess()
	{
	}

	/**
	 * Why this caller may not change anything, or null when they may.
	 * @param authenticated whether WSE REST checks credentials for the module's resources
	 */
	public static String refusal(boolean authenticated, String user, Predicate<String> admin)
	{
		if(!authenticated)
		{
			return READ_ONLY + ": WSE REST authentication is off (Server.xml RESTInterface/AuthenticationMethod none, or "
					+ "OverlayStudioServer=unauthorized), so Overlay Studio cannot tell who is asking and accepts no changes. "
					+ "Set AuthenticationMethod to basic or digest to make changes.";
		}
		if(user == null || user.isEmpty())
		{
			return READ_ONLY + ": no signed-in user.";
		}
		if(!admin.test(user))
		{
			return READ_ONLY + ": " + user + " is not in the " + ADMIN_GROUP + " group; changes need a WSE administrator.";
		}
		return null;
	}

	/** Whether the module's resources sit behind WSE REST authentication in this application. */
	static boolean authenticated(Application app)
	{
		if(!"authorized".equals(OverlayStudioServer.accessMode()) || !(app instanceof RestletApplication ra))
		{
			return false;
		}
		RESTConfig c = ra.getRESTConfig();
		return c != null && c.isAuthenticationMethodEnabled()
				&& (c.isBasicAuthEnabled() || c.isDigestEnabled() || c.isDigestFileEnabled() || c.isRemoteAuthEnabled());
	}

	/** Why the request's user may not change anything, or null when they may. */
	static String refusal(Application app, User user)
	{
		return refusal(authenticated(app), user == null ? null : user.getName(), isAdmin);
	}

	/** Whether a verb changes something: every POST, PUT and DELETE does, GET does not. */
	static boolean isWrite(String verb)
	{
		return !"GET".equalsIgnoreCase(verb);
	}
}
