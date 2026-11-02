/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.webapp.overlaystudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The Overlay Studio page's way to WSE REST: the browser calls /enginemanager/overlaystudio-api/... with its Manager session,
 * and this forwards the call, server-side, to the REST server the user signed in to, with that user's credentials.
 * Loaded by the Manager's own component scan of com.wowza.wms.webapp from manager/temp, which the Manager mounts as its
 * WEB-INF/classes (docs/DESIGN.md, The Manager bridge).
 * <p>
 * Rules: Spring and Jakarta types only, no Wowza classes, so a Manager change cannot break the class at load time;
 * the target comes only from the session's login details, never from the request; credentials are never logged or
 * returned. Only GET and POST are mapped, because the Manager's interceptor requires the session's security token and
 * the admin group on POST: the page sends PUT and DELETE as POST with ?method=PUT or ?method=DELETE, and only a POST
 * may carry that override.
 */
@Controller
@RequestMapping("/overlaystudio-api")
public class OverlayStudioBridge
{
	static final String TOKEN = "wowzaSecurityToken";
	static final String METHOD = "method";
	static final int MAX_BODY = 2 * 1024 * 1024;
	static final String DEFAULT_TARGET = "http://localhost:8087";
	static final int CONNECT_TIMEOUT_MS = 5_000;
	/** Captures of slow pages answer in tens of seconds. */
	static final int READ_TIMEOUT_MS = 60_000;
	private static final String PREFIX = "/overlaystudio-api/rest";
	private static final String SEGMENT = "/[A-Za-z0-9_.~%-]+";

	/** The module's own REST resources, for every method the page uses. */
	static final Pattern MODULE = Pattern.compile("^/v1/server/plugin/overlaystudio(" + SEGMENT + ")*$");
	/**
	 * The two WSE resources outside the module the page calls, each with one method. DELETE removes a transcoder template
	 * (ui/js/ovs-core.js V2 = /v2/servers/_defaultServer_/vhosts/_defaultVHost_; U.api.v2del('/transcoder/templates/'
	 * + name) in ovs-stream.js and ovs-template.js). PUT restarts an application, for a stream that cannot be restarted on
	 * its own, such as one a server listener publishes (U.restartApp in ovs-core.js). The page has no other v2 calls.
	 */
	static final Pattern TEMPLATE = Pattern.compile("^/v2/servers/_defaultServer_/vhosts/_defaultVHost_/transcoder/templates" + SEGMENT + "$");
	static final Pattern APP_RESTART = Pattern.compile("^/v2/servers/_defaultServer_/vhosts/_defaultVHost_/applications" + SEGMENT + "/actions/restart$");
	/** Request body types forwarded: the page's JSON, and raw image uploads. */
	static final Set<String> TYPES = Set.of("application/json", "text/plain", "application/octet-stream", "image/png", "image/jpeg", "image/gif",
			"image/webp");

	/** The bridge's version, so the page can tell a missing or older bridge. */
	@RequestMapping(value = "/status", method = RequestMethod.GET)
	public void status(HttpServletRequest req, HttpServletResponse res) throws IOException
	{
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		write(res, 200, "{\"bridge\":\"overlaystudio\",\"version\":\"" + json(BridgeVersion.VERSION) + "\",\"signedIn\":" + (a != null && a.isAuthenticated()) + "}");
	}

	/** The session's security token, which the Manager requires on every POST. Same origin only: no CORS headers. */
	@RequestMapping(value = "/token", method = RequestMethod.GET)
	public void token(HttpServletRequest req, HttpServletResponse res) throws IOException
	{
		HttpSession s = req.getSession(false);
		Object t = s == null ? null : s.getAttribute(TOKEN);
		write(res, t == null ? 404 : 200, "{\"token\":\"" + json(t == null ? "" : t.toString()) + "\"}");
	}

	@RequestMapping(value = "/rest/**", method = { RequestMethod.GET, RequestMethod.POST })
	public void forward(HttpServletRequest req, HttpServletResponse res) throws IOException
	{
		String path = restPath(req);
		String method = effectiveMethod(req);
		if(method == null)
		{
			write(res, 400, "{\"success\":false,\"message\":\"Only a POST may ask for PUT or DELETE\"}");
			return;
		}
		if(path == null || !allowed(method, path))
		{
			write(res, 403, "{\"success\":false,\"message\":\"Not a Overlay Studio path\"}");
			return;
		}
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		if(a == null || !a.isAuthenticated() || a.getCredentials() == null)
		{
			write(res, 401, "{\"success\":false,\"message\":\"Not signed in\"}");
			return;
		}
		byte[] body = null;
		String type = null;
		if(method.equals("POST") || method.equals("PUT"))
		{
			if(req.getContentLengthLong() > MAX_BODY)
			{
				write(res, 413, "{\"success\":false,\"message\":\"Request too large\"}");
				return;
			}
			if(req.getContentType() != null)
			{
				type = contentType(req.getContentType());
				if(type == null)
				{
					write(res, 415, "{\"success\":false,\"message\":\"Unsupported content type\"}");
					return;
				}
			}
			body = readCapped(req.getInputStream());
			if(body == null)
			{
				write(res, 413, "{\"success\":false,\"message\":\"Request too large\"}");
				return;
			}
		}
		String user = a.getName();
		char[] pass = a.getCredentials().toString().toCharArray();
		HttpURLConnection c;
		int status;
		try
		{
			c = (HttpURLConnection)url(a, path, forwardedQuery(req.getQueryString())).toURL().openConnection();
			c.setAuthenticator(new Authenticator()
			{
				@Override
				protected PasswordAuthentication getPasswordAuthentication()
				{
					return new PasswordAuthentication(user, pass);
				}
			});
			c.setInstanceFollowRedirects(false);
			c.setUseCaches(false);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setRequestMethod(method);
			c.setRequestProperty("Accept", req.getHeader("Accept") == null ? "application/json" : req.getHeader("Accept"));
			if(body != null)
			{
				c.setDoOutput(true);
				if(type != null)
				{
					c.setRequestProperty("Content-Type", type);
				}
				// buffered, not streamed: a 401 challenge from REST (basic or digest) is then answered by sending the body
				// again with the credentials; a streamed body cannot be resent
				try(OutputStream o = c.getOutputStream())
				{
					o.write(body);
				}
			}
			status = c.getResponseCode();
		}
		catch(IOException e)
		{
			// the message names only the kind of failure: never the URL's user part or any credential
			write(res, 502, "{\"success\":false,\"message\":\"WSE REST did not answer (" + json(e.getClass().getSimpleName()) + ")\"}");
			return;
		}
		res.setStatus(status);
		res.setContentType(c.getContentType() == null ? "application/json" : c.getContentType());
		res.setHeader("Cache-Control", "no-store");
		res.setHeader("X-Content-Type-Options", "nosniff");
		long length = c.getContentLengthLong();
		if(length >= 0)
		{
			res.setContentLengthLong(length);
		}
		// streamed as it arrives: capture previews are images; WWW-Authenticate is never passed on, so the browser
		// cannot be prompted for REST credentials
		try(InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream())
		{
			if(in != null)
			{
				in.transferTo(res.getOutputStream());
			}
		}
		finally
		{
			c.disconnect();
		}
	}

	/** The REST path after /overlaystudio-api/rest, as the browser sent it (still encoded), or null. */
	static String restPath(HttpServletRequest req)
	{
		String start = (req.getContextPath() == null ? "" : req.getContextPath()) + PREFIX;
		String uri = req.getRequestURI();
		return uri == null || !uri.startsWith(start + "/") ? null : uri.substring(start.length());
	}

	/**
	 * The method to forward: GET as GET; POST as POST, or as PUT or DELETE when its query says method=PUT or
	 * method=DELETE. Null for anything else, and for a GET that asks for another method, since the Manager checks the
	 * token and the admin group on POST only.
	 */
	static String effectiveMethod(HttpServletRequest req)
	{
		String asked = parameter(req.getQueryString(), METHOD);
		if("GET".equals(req.getMethod()))
		{
			return asked == null ? "GET" : null;
		}
		if("POST".equals(req.getMethod()))
		{
			if(asked == null)
			{
				return "POST";
			}
			return asked.equals("PUT") || asked.equals("DELETE") ? asked : null;
		}
		return null;
	}

	/** True when the page may call this path with this method. */
	static boolean allowed(String method, String path)
	{
		if(!clean(path))
		{
			return false;
		}
		if(MODULE.matcher(path).matches())
		{
			return true;
		}
		return TEMPLATE.matcher(path).matches() && method.equals("DELETE") || APP_RESTART.matcher(path).matches() && method.equals("PUT");
	}

	// no way out of the allowed prefix: no dot segments, no doubled or back slashes, no encoded dot, slash, backslash or NUL
	private static boolean clean(String path)
	{
		String p = path.toLowerCase(Locale.ROOT);
		if(p.contains("..") || p.contains("//") || p.contains("\\") || p.contains("%2e") || p.contains("%2f") || p.contains("%5c") || p.contains("%00")
				|| p.contains("%25"))
		{
			return false;
		}
		for(String s: p.split("/"))
		{
			if(s.equals("."))
			{
				return false;
			}
		}
		return true;
	}

	/** The query to forward: everything but the Manager's security token and the method override. */
	static String forwardedQuery(String q)
	{
		if(q == null || q.isEmpty())
		{
			return "";
		}
		StringBuilder b = new StringBuilder();
		for(String p: q.split("&"))
		{
			if(p.isEmpty())
			{
				continue;
			}
			String name = decode(p.contains("=") ? p.substring(0, p.indexOf('=')) : p);
			if(name.equals(TOKEN) || name.equals(METHOD))
			{
				continue;
			}
			b.append(b.length() == 0 ? "" : "&").append(p);
		}
		return b.toString();
	}

	private static String parameter(String q, String name)
	{
		if(q == null)
		{
			return null;
		}
		for(String p: q.split("&"))
		{
			int eq = p.indexOf('=');
			if(eq > 0 && decode(p.substring(0, eq)).equals(name))
			{
				return decode(p.substring(eq + 1));
			}
			if(eq < 0 && decode(p).equals(name))
			{
				return "";
			}
		}
		return null;
	}

	private static String decode(String s)
	{
		try
		{
			return URLDecoder.decode(s, StandardCharsets.UTF_8);
		}
		catch(IllegalArgumentException e)
		{
			return s;
		}
	}

	/** The REST server the user signed in to, from the login details; never taken from the request. */
	static String target(Authentication a)
	{
		String host = null;
		try
		{
			Object d = a == null ? null : a.getDetails();
			if(d != null)
			{
				Object h = d.getClass().getMethod("getHost").invoke(d);
				host = h == null ? null : h.toString();
			}
		}
		catch(ReflectiveOperationException | RuntimeException e)
		{
			host = null;
		}
		if(host == null || host.isBlank())
		{
			host = DEFAULT_TARGET;
		}
		host = host.trim();
		return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
	}

	static URI url(Authentication a, String path, String query)
	{
		return URI.create(target(a) + path + (query.isEmpty() ? "" : "?" + query));
	}

	/** The Content-Type to forward when its media type is one the page sends, else null. */
	static String contentType(String header)
	{
		String base = header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		return TYPES.contains(base) ? header.trim() : null;
	}

	/** The whole body, or null when it is larger than MAX_BODY. */
	static byte[] readCapped(InputStream in) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		for(int n; (n = in.read(buf)) >= 0;)
		{
			out.write(buf, 0, n);
			if(out.size() > MAX_BODY)
			{
				return null;
			}
		}
		return out.toByteArray();
	}

	static void write(HttpServletResponse res, int status, String body) throws IOException
	{
		res.setStatus(status);
		res.setContentType("application/json");
		res.setHeader("Cache-Control", "no-store");
		res.setHeader("X-Content-Type-Options", "nosniff");
		res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
	}

	static String json(String s)
	{
		StringBuilder b = new StringBuilder();
		for(char ch: s.toCharArray())
		{
			if(ch == '"' || ch == '\\')
			{
				b.append('\\').append(ch);
			}
			else if(ch < 0x20)
			{
				b.append(String.format("\\u%04x", (int)ch));
			}
			else
			{
				b.append(ch);
			}
		}
		return b.toString();
	}
}
