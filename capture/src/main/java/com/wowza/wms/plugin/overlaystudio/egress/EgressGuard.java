/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.egress;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal HTTP proxy on 127.0.0.1 that one captured page's Chrome is forced to use. Every connection is checked
 * against AddressRules before it is opened: plain HTTP requests in absolute form, and CONNECT tunnels for HTTPS
 * and WebSockets. The host is resolved once and the proxy connects to an address it checked, so a DNS answer
 * that changes between check and connect cannot redirect it.
 */
public final class EgressGuard implements Closeable
{
	public interface BlockListener
	{
		void blocked(String host, AddressRules.Decision decision);
	}

	private static final int MAX_HEAD = 64 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 10000;
	// diagnostic: -Doverlaystudio.egress.trace=true prints every request line the proxy receives to stderr
	private static final boolean TRACE = Boolean.getBoolean("overlaystudio.egress.trace");
	/** Where trace lines go; the server points it at the WSE log, so they land in the access log. */
	public static volatile java.util.function.Consumer<String> traceSink = System.err::println;

	private final String name;
	private final AddressRules rules;
	private final BlockListener listener;
	private final ExecutorService pool;
	private ServerSocket server;
	private volatile boolean closed = false;

	public EgressGuard(AddressRules rules, String name, BlockListener listener)
	{
		this.name = name;
		this.rules = rules;
		this.listener = listener;
		// a page opens many connections, each needing two threads while it lasts (one per direction); virtual threads
		// make that cheap, where platform threads ran to hundreds for a page that polls
		this.pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("OverlayStudioEgress-" + name + "-", 0).factory());
	}

	/** Binds to an ephemeral port on 127.0.0.1 and starts accepting. @return the port */
	public synchronized int start() throws IOException
	{
		server = new ServerSocket();
		server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
		pool.execute(this::acceptLoop);
		return server.getLocalPort();
	}

	public int port()
	{
		return server == null ? -1 : server.getLocalPort();
	}

	@Override
	public void close()
	{
		closed = true;
		try
		{
			if(server != null)
			{
				server.close();
			}
		}
		catch(IOException e)
		{
			// closing anyway
		}
		pool.shutdownNow();
	}

	private void acceptLoop()
	{
		while(!closed)
		{
			try
			{
				Socket client = server.accept();
				if(!client.getInetAddress().isLoopbackAddress())
				{
					client.close();
					continue;
				}
				pool.execute(() -> handle(client));
			}
			catch(IOException e)
			{
				if(closed)
				{
					return;
				}
			}
		}
	}

	private void handle(Socket client)
	{
		try(client)
		{
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			byte[][] split = readHead(in);
			if(split == null)
			{
				return;
			}
			String head = new String(split[0], StandardCharsets.ISO_8859_1);
			if(TRACE)
			{
				int e0 = head.indexOf('\r');
				traceSink.accept("OverlayStudio egress trace [" + name + "] " + (e0 >= 0 ? head.substring(0, e0) : head));
			}
			byte[] rest = split[1];
			int eol = head.indexOf("\r\n");
			String requestLine = eol >= 0 ? head.substring(0, eol) : head;
			String[] parts = requestLine.split(" ");
			if(parts.length != 3)
			{
				respond(out, 400, "Bad Request");
				return;
			}
			if(parts[0].equalsIgnoreCase("CONNECT"))
			{
				tunnel(client, in, out, parts[1]);
			}
			else
			{
				forward(client, in, out, parts[0], parts[1], parts[2], head.substring(eol + 2), rest);
			}
		}
		catch(IOException e)
		{
			// client or upstream went away
		}
	}

	private void tunnel(Socket client, InputStream in, OutputStream out, String authority) throws IOException
	{
		int colon = authority.lastIndexOf(':');
		if(colon <= 0)
		{
			respond(out, 400, "Bad Request");
			return;
		}
		String host = stripBrackets(authority.substring(0, colon));
		int port = parsePort(authority.substring(colon + 1));
		Socket upstream = open(out, host, port);
		if(upstream == null)
		{
			return;
		}
		try(upstream)
		{
			out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			pipe(client, in, out, upstream);
		}
	}

	private void forward(Socket client, InputStream in, OutputStream out, String method, String target, String version,
			String headers, byte[] rest) throws IOException
	{
		URI uri;
		try
		{
			uri = new URI(target);
		}
		catch(Exception e)
		{
			respond(out, 400, "Bad Request");
			return;
		}
		if(!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
		{
			respond(out, 400, "Bad Request");
			return;
		}
		String host = stripBrackets(uri.getHost());
		int port = uri.getPort() > 0 ? uri.getPort() : 80;
		Socket upstream = open(out, host, port);
		if(upstream == null)
		{
			return;
		}
		try(upstream)
		{
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			if(uri.getRawQuery() != null)
			{
				path += "?" + uri.getRawQuery();
			}
			StringBuilder sb = new StringBuilder();
			sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
			for(String line: headers.split("\r\n"))
			{
				String lower = line.toLowerCase(Locale.ROOT);
				if(line.isEmpty() || lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:") || lower.startsWith("connection:"))
				{
					continue;
				}
				sb.append(line).append("\r\n");
			}
			// one request per connection keeps the proxy stateless
			sb.append("Connection: close\r\n\r\n");
			OutputStream up = upstream.getOutputStream();
			up.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
			up.write(rest);
			up.flush();
			pipe(client, in, out, upstream);
		}
	}

	/** Resolves, checks and connects. Answers 403 or 502 itself and returns null when it does not connect. */
	private Socket open(OutputStream out, String host, int port) throws IOException
	{
		if(port <= 0)
		{
			respond(out, 400, "Bad Request");
			return null;
		}
		// a port that is not allowed is refused before the name is looked up
		AddressRules.Decision pd = rules.checkPort(port);
		if(!pd.allowed())
		{
			if(listener != null)
			{
				listener.blocked(host, pd);
			}
			respond(out, 403, "Blocked by Overlay Studio: port " + port);
			return null;
		}
		InetAddress[] addresses;
		try
		{
			addresses = InetAddress.getAllByName(host);
		}
		catch(UnknownHostException e)
		{
			addresses = new InetAddress[0];
		}
		AddressRules.Decision d = rules.check(host, addresses);
		if(!d.allowed())
		{
			if(listener != null)
			{
				listener.blocked(host, d);
			}
			respond(out, 403, "Blocked by Overlay Studio: " + d.rule() + " " + d.address());
			return null;
		}
		Socket upstream = new Socket();
		try
		{
			upstream.connect(new InetSocketAddress(AddressRules.normalize(addresses[0]), port), CONNECT_TIMEOUT_MS);
			return upstream;
		}
		catch(IOException e)
		{
			upstream.close();
			respond(out, 502, "Bad Gateway");
			return null;
		}
	}

	private void pipe(Socket client, InputStream in, OutputStream out, Socket upstream) throws IOException
	{
		InputStream upIn = upstream.getInputStream();
		OutputStream upOut = upstream.getOutputStream();
		pool.execute(() -> {
			try
			{
				in.transferTo(upOut);
			}
			catch(IOException e)
			{
				// one side closed
			}
			finally
			{
				try
				{
					upstream.shutdownOutput();
				}
				catch(IOException e)
				{
					// already closed
				}
			}
		});
		try
		{
			upIn.transferTo(out);
		}
		finally
		{
			out.flush();
			client.shutdownOutput();
		}
	}

	/** @return {head without the blank line, bytes read past it}, or null if the head is missing or too large */
	static byte[][] readHead(InputStream in) throws IOException
	{
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		while(buf.size() < MAX_HEAD)
		{
			int n = in.read(chunk);
			if(n < 0)
			{
				return null;
			}
			buf.write(chunk, 0, n);
			byte[] b = buf.toByteArray();
			for(int i = 0; i + 3 < b.length; i++)
			{
				if(b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n')
				{
					byte[] head = java.util.Arrays.copyOfRange(b, 0, i);
					byte[] rest = java.util.Arrays.copyOfRange(b, i + 4, b.length);
					return new byte[][] { head, rest };
				}
			}
		}
		return null;
	}

	private static void respond(OutputStream out, int code, String message) throws IOException
	{
		byte[] body = message.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + code + " " + (code == 403 ? "Forbidden" : code == 502 ? "Bad Gateway" : "Bad Request") + "\r\n"
				+ "Content-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	private static String stripBrackets(String host)
	{
		return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
	}

	private static int parsePort(String s)
	{
		try
		{
			int p = Integer.parseInt(s.trim());
			return p > 0 && p < 65536 ? p : -1;
		}
		catch(NumberFormatException e)
		{
			return -1;
		}
	}
}
