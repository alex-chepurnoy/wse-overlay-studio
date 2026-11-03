/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.webapp.overlaystudio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sun.net.httpserver.HttpServer;

class OverlayStudioBridgeTest
{
	static final String MODULE = "/v1/server/plugin/overlaystudio";
	static final String TEMPLATES = "/v2/servers/_defaultServer_/vhosts/_defaultVHost_/transcoder/templates";
	static final String PASSWORD = "s3cret-pass";

	/** Stands in for the Manager's login details, which carry the REST server the user signed in to. */
	public static final class Details
	{
		private final String host;

		Details(String host)
		{
			this.host = host;
		}

		public String getHost()
		{
			return host;
		}
	}

	/** What the stand-in REST server received. */
	record Seen(String method, String path, String query, String auth, String type, byte[] body)
	{
	}

	HttpServer rest;
	final List<Seen> seen = new ArrayList<>();
	String restBase;

	@BeforeEach
	void startRest() throws Exception
	{
		rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		rest.createContext("/", x -> {
			String auth = x.getRequestHeaders().getFirst("Authorization");
			if(auth == null)
			{
				// as WSE REST with basic authentication: a challenge first
				x.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Wowza\"");
				x.sendResponseHeaders(401, -1);
				x.close();
				return;
			}
			byte[] body = x.getRequestBody().readAllBytes();
			seen.add(new Seen(x.getRequestMethod(), x.getRequestURI().getRawPath(), x.getRequestURI().getRawQuery(), auth,
					x.getRequestHeaders().getFirst("Content-Type"), body));
			byte[] out = x.getRequestURI().getPath().endsWith("preview") ? new byte[] { (byte)0x89, 'P', 'N', 'G', 0, 1, 2 } : "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			x.getResponseHeaders().add("Content-Type", x.getRequestURI().getPath().endsWith("preview") ? "image/png" : "application/json");
			x.sendResponseHeaders(200, out.length);
			x.getResponseBody().write(out);
			x.close();
		});
		rest.start();
		restBase = "http://127.0.0.1:" + rest.getAddress().getPort();
		signIn(restBase);
	}

	@AfterEach
	void stopRest()
	{
		rest.stop(0);
		SecurityContextHolder.clearContext();
	}

	static void signIn(String host)
	{
		UsernamePasswordAuthenticationToken t = UsernamePasswordAuthenticationToken.authenticated("admin", PASSWORD, List.of(new SimpleGrantedAuthority("ROLE_admin")));
		t.setDetails(host == null ? null : new Details(host));
		SecurityContextHolder.getContext().setAuthentication(t);
	}

	static MockHttpServletRequest request(String method, String path, String query)
	{
		MockHttpServletRequest r = new MockHttpServletRequest(method, "/enginemanager/overlaystudio-api/rest" + path);
		r.setContextPath("/enginemanager");
		r.setQueryString(query);
		// the parameters as the servlet decodes them from the query
		if(query != null)
		{
			for(String p: query.split("&"))
			{
				int eq = p.indexOf('=');
				r.addParameter(java.net.URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8), eq < 0 ? "" : java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		// a Host the browser chose: never the target
		r.setServerName("evil.example");
		r.setServerPort(9);
		r.addHeader("Host", "evil.example:9");
		return r;
	}

	MockHttpServletResponse forward(MockHttpServletRequest r) throws Exception
	{
		MockHttpServletResponse res = new MockHttpServletResponse();
		new OverlayStudioBridge().forward(r, res);
		return res;
	}

	@Test
	void allowListTakesTheModulesPathsAndOnlyTheTemplateDeleteThePageUses()
	{
		for(String m: List.of("GET", "POST", "PUT", "DELETE"))
		{
			assertTrue(OverlayStudioBridge.allowed(m, MODULE), m);
			assertTrue(OverlayStudioBridge.allowed(m, MODULE + "/vhosts/_defaultVHost_/applications/live/overlays/Score%20board"), m);
		}
		assertTrue(OverlayStudioBridge.allowed("DELETE", TEMPLATES + "/live_overlay"));
		for(String m: List.of("GET", "POST", "PUT"))
		{
			assertFalse(OverlayStudioBridge.allowed(m, TEMPLATES + "/live_overlay"), "the page only deletes templates: " + m);
		}
		String apps = "/v2/servers/_defaultServer_/vhosts/_defaultVHost_/applications";
		assertTrue(OverlayStudioBridge.allowed("PUT", apps + "/live/actions/restart"));
		for(String m: List.of("GET", "POST", "DELETE"))
		{
			assertFalse(OverlayStudioBridge.allowed(m, apps + "/live/actions/restart"), "the page only restarts applications: " + m);
		}
		for(String p: List.of(apps + "/live/actions/shutdown", apps + "/live/actions", apps + "/live/actions/restart/x", apps + "/../actions/restart",
				apps + "/live/instances/_definst_/actions/restart", "/v2/servers/_defaultServer_/actions/restart"))
		{
			assertFalse(OverlayStudioBridge.allowed("PUT", p), p);
		}
		for(String p: List.of("/v1/server/plugin/overlaystudiox", "/v1/server/plugin", "/v1/server/plugin/other", MODULE + "/../../users", MODULE + "/%2e%2e/users",
				MODULE + "/%2E%2E/users", MODULE + "/a%2fb", MODULE + "/a%5cb", MODULE + "//x", MODULE + "/./x", MODULE + "/a%252e%252e", TEMPLATES,
				TEMPLATES + "/a/b", TEMPLATES + "/..", "/v2/servers/_defaultServer_/vhosts/_defaultVHost_/applications/live",
				"/v2/servers/_defaultServer_/vhosts/other/transcoder/templates/x", "/v2/servers/_defaultServer_/users/admin", "/v2/servers/_defaultServer_"))
		{
			assertFalse(OverlayStudioBridge.allowed("DELETE", p), p);
			assertFalse(OverlayStudioBridge.allowed("GET", p), p);
		}
	}

	// the parameters as the servlet decoded them, in order
	private static java.util.Map<String, String[]> params(String... kv)
	{
		java.util.Map<String, String[]> m = new java.util.LinkedHashMap<>();
		for(int i = 0; i < kv.length; i += 2)
		{
			m.put(kv[i], new String[] { kv[i + 1] });
		}
		return m;
	}

	@Test
	void theSecurityTokenAndTheMethodOverrideAreNotForwarded()
	{
		assertEquals("", OverlayStudioBridge.forwardedQuery(params()));
		assertEquals("a=1&b=2", OverlayStudioBridge.forwardedQuery(params("wowzaSecurityToken", "abc", "a", "1", "method", "DELETE", "b", "2")));
		assertEquals("methods=x&tokens=y", OverlayStudioBridge.forwardedQuery(params("methods", "x", "tokens", "y", "wowzaSecurityToken", "")));
	}

	@Test
	void eachValueIsEncodedOnceFromWhatTheServletDecoded()
	{
		// a space, a plus, a percent, an ampersand and a quote reach the module as the page sent them
		assertEquals("pattern=h%3Amm%3Ass+a&kind=time", OverlayStudioBridge.forwardedQuery(params("pattern", "h:mm:ss a", "kind", "time")));
		assertEquals("pattern=h%2Ba&x=100%25&y=a%26b%3Dc", OverlayStudioBridge.forwardedQuery(params("pattern", "h+a", "x", "100%", "y", "a&b=c")));
		assertEquals("pattern=HH+%27o%7Dclock%27&zone=Europe%2FParis", OverlayStudioBridge.forwardedQuery(params("pattern", "HH 'o}clock'", "zone", "Europe/Paris")));
		assertEquals("t=%C3%A9t%C3%A9", OverlayStudioBridge.forwardedQuery(params("t", "\u00e9t\u00e9")), "UTF-8");
		java.util.Map<String, String[]> two = new java.util.LinkedHashMap<>();
		two.put("a", new String[] { "1", "2" });
		assertEquals("a=1&a=2", OverlayStudioBridge.forwardedQuery(two), "a repeated name keeps every value");
	}

	@Test
	void onlyAPostMayAskForPutOrDelete()
	{
		assertEquals("GET", OverlayStudioBridge.effectiveMethod(request("GET", MODULE, null)));
		assertEquals("POST", OverlayStudioBridge.effectiveMethod(request("POST", MODULE, "wowzaSecurityToken=t")));
		assertEquals("DELETE", OverlayStudioBridge.effectiveMethod(request("POST", MODULE, "method=DELETE&wowzaSecurityToken=t")));
		assertEquals("PUT", OverlayStudioBridge.effectiveMethod(request("POST", MODULE, "method=PUT")));
		assertNull(OverlayStudioBridge.effectiveMethod(request("GET", MODULE, "method=DELETE")), "a GET carries no token check, so it cannot become a write");
		assertNull(OverlayStudioBridge.effectiveMethod(request("POST", MODULE, "method=PATCH")));
		assertNull(OverlayStudioBridge.effectiveMethod(request("POST", MODULE, "method=delete")));
		assertNull(OverlayStudioBridge.effectiveMethod(request("PUT", MODULE, null)));
	}

	@Test
	void theBodyIsCappedAtTwoMegabytes() throws Exception
	{
		assertEquals(OverlayStudioBridge.MAX_BODY, OverlayStudioBridge.readCapped(new ByteArrayInputStream(new byte[OverlayStudioBridge.MAX_BODY])).length);
		assertNull(OverlayStudioBridge.readCapped(new ByteArrayInputStream(new byte[OverlayStudioBridge.MAX_BODY + 1])));

		MockHttpServletRequest r = request("POST", MODULE + "/images", "wowzaSecurityToken=t");
		r.setContentType("image/png");
		r.setContent(new byte[OverlayStudioBridge.MAX_BODY + 1]);
		assertEquals(413, forward(r).getStatus());
		// a client that does not declare the length is cut off just the same
		r = new MockHttpServletRequest("POST", "/enginemanager/overlaystudio-api/rest" + MODULE + "/images")
		{
			@Override
			public long getContentLengthLong()
			{
				return -1;
			}
		};
		r.setContextPath("/enginemanager");
		r.setContentType("image/png");
		r.setContent(new byte[OverlayStudioBridge.MAX_BODY + 1]);
		assertEquals(413, forward(r).getStatus());
		assertTrue(seen.isEmpty(), "nothing reached REST");
	}

	@Test
	void theTargetComesFromTheLoginDetailsOnly() throws Exception
	{
		assertEquals("http://wse.internal:8087", OverlayStudioBridge.target(auth("http://wse.internal:8087/")));
		assertEquals(OverlayStudioBridge.DEFAULT_TARGET, OverlayStudioBridge.target(auth(null)));
		assertEquals(OverlayStudioBridge.DEFAULT_TARGET, OverlayStudioBridge.target(null));

		MockHttpServletResponse res = forward(request("GET", MODULE + "/info", null));
		assertEquals(200, res.getStatus(), res.getContentAsString());
		assertEquals(1, seen.size(), "the call went to the server in the login details, not to the Host the browser sent");
	}

	static UsernamePasswordAuthenticationToken auth(String host)
	{
		UsernamePasswordAuthenticationToken t = UsernamePasswordAuthenticationToken.authenticated("admin", PASSWORD, List.of());
		t.setDetails(host == null ? null : new Details(host));
		return t;
	}

	@Test
	void aDeleteGoesThroughAsDeleteWithoutTokenOrOverride() throws Exception
	{
		MockHttpServletResponse res = forward(request("POST", TEMPLATES + "/live_overlay", "method=DELETE&wowzaSecurityToken=t0k&x=1"));
		assertEquals(200, res.getStatus(), res.getContentAsString());
		Seen s = seen.get(0);
		assertEquals("DELETE", s.method());
		assertEquals(TEMPLATES + "/live_overlay", s.path());
		assertEquals("x=1", s.query());
		assertEquals("Basic " + Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8)), s.auth(), "the user's own credentials");
	}

	@Test
	void aPutCarriesTheRawBodyAndItsType() throws Exception
	{
		MockHttpServletRequest r = request("POST", MODULE + "/vhosts/_defaultVHost_/applications/live/overlays/a", "method=PUT&wowzaSecurityToken=t");
		r.setContentType("application/json;charset=UTF-8");
		r.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
		assertEquals(200, forward(r).getStatus());
		assertEquals("PUT", seen.get(0).method());
		assertEquals("application/json;charset=UTF-8", seen.get(0).type());
		assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), seen.get(0).body());
		assertNull(seen.get(0).query());
	}

	@Test
	void refusalsReachNothing() throws Exception
	{
		MockHttpServletRequest form = request("POST", MODULE + "/x", "wowzaSecurityToken=t");
		form.setContentType("application/x-www-form-urlencoded");
		form.setContent("a=1".getBytes(StandardCharsets.UTF_8));
		assertEquals(415, forward(form).getStatus());
		assertEquals(403, forward(request("GET", "/v2/servers/_defaultServer_/users", null)).getStatus());
		assertEquals(403, forward(request("POST", TEMPLATES + "/x", "wowzaSecurityToken=t")).getStatus(), "a template may only be deleted");
		assertEquals(400, forward(request("GET", TEMPLATES + "/x", "method=DELETE")).getStatus());
		SecurityContextHolder.clearContext();
		assertEquals(401, forward(request("GET", MODULE + "/info", null)).getStatus());
		assertTrue(seen.isEmpty());
	}

	/** Neither the password nor the Authorization value the bridge sent (Basic and its Base64) is in the body or a header. */
	static void noCredential(MockHttpServletResponse res) throws Exception
	{
		String base64 = Base64.getEncoder().encodeToString(("admin:" + PASSWORD).getBytes(StandardCharsets.UTF_8));
		List<String> seen = new ArrayList<>(List.of(res.getContentAsString()));
		for(String h: res.getHeaderNames())
		{
			seen.add(h);
			seen.addAll(res.getHeaders(h));
		}
		for(String v: seen)
		{
			assertFalse(v.contains(PASSWORD) || v.contains(base64) || v.contains("Basic "), "a credential leaked: " + v);
		}
	}

	@Test
	void binaryAnswersPassThroughAndNoCredentialIsReturned() throws Exception
	{
		MockHttpServletResponse res = forward(request("GET", MODULE + "/vhosts/_defaultVHost_/applications/live/preview", null));
		assertEquals("image/png", res.getContentType());
		assertArrayEquals(new byte[] { (byte)0x89, 'P', 'N', 'G', 0, 1, 2 }, res.getContentAsByteArray());
		assertEquals("no-store", res.getHeader("Cache-Control"));
		noCredential(res);

		// REST refusing the credentials: its challenge is not passed on, so the browser never prompts for them
		rest.createContext(MODULE + "/denied", x -> {
			x.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"Wowza\"");
			byte[] b = "{\"message\":\"denied\"}".getBytes(StandardCharsets.UTF_8);
			x.sendResponseHeaders(401, b.length);
			x.getResponseBody().write(b);
			x.close();
		});
		res = forward(request("GET", MODULE + "/denied", null));
		assertEquals(401, res.getStatus());
		assertNull(res.getHeader("WWW-Authenticate"));
		noCredential(res);

		rest.stop(0);
		res = forward(request("GET", MODULE + "/info", null));
		assertEquals(502, res.getStatus());
		noCredential(res);
	}

	@Test
	void theTokenIsForThisOriginAndThisSessionOnly() throws Exception
	{
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.addHeader("Origin", "https://evil.example");
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(OverlayStudioBridge.TOKEN, "abc");
		r.setSession(s);
		MockHttpServletResponse res = new MockHttpServletResponse();
		new OverlayStudioBridge().token(r, res);
		assertEquals("{\"token\":\"abc\"}", res.getContentAsString());
		assertNull(res.getHeader("Access-Control-Allow-Origin"), "no CORS: another site cannot read the token");
		assertNull(res.getHeader("Access-Control-Allow-Credentials"));

		res = new MockHttpServletResponse();
		new OverlayStudioBridge().token(new MockHttpServletRequest(), res);
		assertEquals(404, res.getStatus(), "no session, no token");
		assertFalse(res.getContentAsString().contains("abc"));
	}
}
