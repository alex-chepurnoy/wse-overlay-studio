/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.wowza.wms.plugin.urloverlay.capture.Wire;
import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;
import com.wowza.wms.plugin.urloverlay.egress.AddressRules;
import com.wowza.wms.plugin.urloverlay.egress.EgressRules;

/**
 * The module's side of the capture service: a few connections to its Unix socket, each used for one request at a time,
 * so a slow page on one does not hold up frames for the others. While the service does not answer, calls fail at once
 * for a growing pause (1 s, doubling to 30 s) instead of each draw waiting on a dead socket.
 */
public final class CaptureClient
{
	public static final String PROP_SOCKET = "UrlOverlayCaptureSocket";
	/**
	 * Connections kept open between calls. Every page's fetcher holds one through its long poll, so this is sized for
	 * the pages a server runs at once (the capture service allows at most 32) plus the calls in between; fewer would close
	 * and reopen connections every frame.
	 */
	static final int MAX_IDLE_CONNECTIONS = 40;
	static final long MAX_BACKOFF_MS = 30_000;

	/** The service did not answer, or answers with a protocol this module does not speak. */
	public static final class ServiceDown extends IOException
	{
		private static final long serialVersionUID = 1L;

		ServiceDown(String message)
		{
			super(message);
		}
	}

	private static volatile CaptureClient instance = new CaptureClient(Path.of(defaultSocket(isWindows())), EgressRules.defaults(), 1000);
	// every call arms a timeout and cancels it when the answer comes: cancelled ones leave the queue at once, instead
	// of thousands a minute waiting out their delay holding a connection
	private static final ScheduledExecutorService TIMEOUTS;
	static
	{
		java.util.concurrent.ScheduledThreadPoolExecutor t = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
			Thread th = new Thread(r, "UrlOverlayCaptureTimeouts");
			th.setDaemon(true);
			return th;
		});
		t.setRemoveOnCancelPolicy(true);
		TIMEOUTS = t;
	}

	private final Path socket;
	private final EgressRules egress;
	private final AddressRules rules;
	private final long firstBackoffMs;
	private final Deque<SocketChannel> idle = new ArrayDeque<>();
	private final Object lock = new Object();
	private long downUntil = 0;
	private long backoffMs;
	private String downMessage = null;
	private String serviceVersion = null;

	CaptureClient(Path socket, EgressRules egress, long firstBackoffMs)
	{
		this.socket = socket;
		this.egress = egress;
		this.rules = egress.rules();
		this.firstBackoffMs = firstBackoffMs;
		this.backoffMs = firstBackoffMs;
	}

	public static CaptureClient get()
	{
		return instance;
	}

	/** Called at server start with the Server.xml values. */
	public static CaptureClient configure(String socket, EgressRules egress)
	{
		CaptureClient c = new CaptureClient(Path.of(socket == null || socket.isBlank() ? defaultSocket(isWindows()) : socket.trim()), egress, 1000);
		instance = c;
		return c;
	}

	static void use(CaptureClient c)
	{
		instance = c;
	}

	public static String defaultSocket(boolean windows)
	{
		return windows ? "C:\\ProgramData\\WowzaURLOverlay\\capture.sock" : "/run/wse-url-overlay/capture.sock";
	}

	static boolean isWindows()
	{
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	public Path socket()
	{
		return socket;
	}

	/** WSE's egress rules, checked here when a page is saved and sent to the service with every capture. */
	public AddressRules rules()
	{
		return rules;
	}

	public EgressRules egress()
	{
		return egress;
	}

	/** What a page says while the service does not answer: what is wrong and how to start it. */
	public String notRunning()
	{
		return "The URL Overlay capture service is not running (nothing answers on " + socket + "). Start it with "
				+ "\"sudo systemctl start WowzaURLOverlayCapture\" on Linux or \"net start WowzaURLOverlayCapture\" as an administrator on Windows. "
				+ "Streams keep the last picture meanwhile.";
	}

	/**
	 * One request and its answer, within timeoutMs. A ServiceDown when the service does not answer or speaks another
	 * protocol; the answer itself when it says ok false.
	 */
	public Message call(Map<String, Object> header, long timeoutMs) throws ServiceDown
	{
		SocketChannel kept;
		synchronized(lock)
		{
			kept = idle.pollFirst();
		}
		if(kept != null)
		{
			try
			{
				return exchange(kept, header, timeoutMs);
			}
			catch(IOException e)
			{
				// a kept connection the service closed, for example on a restart: try once on a new one
				closeQuietly(kept);
			}
		}
		try
		{
			return exchange(borrow(), header, timeoutMs);
		}
		catch(ServiceDown e)
		{
			throw e;
		}
		catch(IOException e)
		{
			throw down(notRunning());
		}
	}

	private Message exchange(SocketChannel c, Map<String, Object> header, long timeoutMs) throws IOException
	{
		ScheduledFuture<?> timer = TIMEOUTS.schedule(() -> closeQuietly(c), timeoutMs, TimeUnit.MILLISECONDS);
		try
		{
			Wire.write(Channels.newOutputStream(c), header, null);
			Message m = Wire.read(Channels.newInputStream(c));
			if(m == null)
			{
				throw new IOException("closed");
			}
			timer.cancel(false);
			giveBack(c);
			return m;
		}
		catch(IOException e)
		{
			timer.cancel(false);
			closeQuietly(c);
			throw e;
		}
	}

	/** The service's status for the Server view, or running false and the reason. */
	public Map<String, Object> serviceStatus()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		try
		{
			Message s = call(Wire.header(Wire.STATUS), 5000);
			m.putAll(s.header());
			m.remove("ok");
			m.put("running", true);
		}
		catch(ServiceDown e)
		{
			m.put("running", false);
			m.put("message", e.getMessage());
		}
		m.put("socket", socket.toString());
		return m;
	}

	/**
	 * Sets the number of pages the service captures at once and keeps it for its next start.
	 * @return the service's answer: ok and maxCaptures, or ok false and the error
	 */
	public Message setLimit(int maxCaptures) throws ServiceDown
	{
		Map<String, Object> h = Wire.header(Wire.LIMITS);
		h.put("maxCaptures", maxCaptures);
		return call(h, 5000);
	}

	public String serviceVersion()
	{
		synchronized(lock)
		{
			return serviceVersion;
		}
	}

	private SocketChannel borrow() throws ServiceDown
	{
		synchronized(lock)
		{
			if(System.currentTimeMillis() < downUntil)
			{
				throw new ServiceDown(downMessage);
			}
		}
		return connect();
	}

	private SocketChannel connect() throws ServiceDown
	{
		SocketChannel c = null;
		try
		{
			c = SocketChannel.open(StandardProtocolFamily.UNIX);
			c.connect(UnixDomainSocketAddress.of(socket));
			Map<String, Object> h = Wire.header(Wire.HELLO);
			h.put("protocol", Wire.PROTOCOL);
			h.put("version", ReleaseInfo.getVersion());
			OutputStream out = Channels.newOutputStream(c);
			InputStream in = Channels.newInputStream(c);
			Wire.write(out, h, null);
			Message answer = Wire.read(in);
			if(answer == null)
			{
				throw new IOException("closed");
			}
			String mismatch = Wire.protocolMismatch(answer.header().get("protocol"), "capture service");
			if(mismatch != null || !answer.ok())
			{
				closeQuietly(c);
				throw down(mismatch != null ? mismatch : String.valueOf(answer.header().get("error")));
			}
			synchronized(lock)
			{
				serviceVersion = answer.str("version");
				downUntil = 0;
				downMessage = null;
				backoffMs = firstBackoffMs;
			}
			resendLimit(answer.str("serverTag"), out, in);
			return c;
		}
		catch(ServiceDown e)
		{
			throw e;
		}
		catch(IOException | RuntimeException e)
		{
			closeQuietly(c);
			throw down(notRunning());
		}
	}

	/**
	 * A service that started again reads capture.properties, so the limit set from the page goes back to it, once per
	 * service start (its serverTag; an older service has none and is given it on every connection). Under
	 * CaptureLimit.LOCK, so a change from the page cannot cross an older value sent here. A refusal is kept for the
	 * page and logged once; the service then runs at its capture.properties value.
	 */
	private void resendLimit(String tag, OutputStream out, InputStream in) throws IOException
	{
		synchronized(CaptureLimit.LOCK)
		{
			Integer limit = CaptureLimit.wanted();
			if(limit == null || (tag != null && tag.equals(CaptureLimit.sentTo())))
			{
				return;
			}
			Map<String, Object> l = Wire.header(Wire.LIMITS);
			l.put("maxCaptures", limit);
			Wire.write(out, l, null);
			Message a = Wire.read(in);
			if(a == null)
			{
				throw new IOException("closed");
			}
			CaptureLimit.sent(tag, a.ok() ? null : String.valueOf(a.header().get("error")));
		}
	}

	private ServiceDown down(String message)
	{
		synchronized(lock)
		{
			downMessage = message;
			downUntil = System.currentTimeMillis() + backoffMs;
			backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
			while(!idle.isEmpty())
			{
				closeQuietly(idle.pollFirst());
			}
			return new ServiceDown(message);
		}
	}

	private void giveBack(SocketChannel c)
	{
		synchronized(lock)
		{
			if(idle.size() < MAX_IDLE_CONNECTIONS && c.isOpen())
			{
				idle.addFirst(c);
				return;
			}
		}
		closeQuietly(c);
	}

	/** Drops every connection; for tests and shutdown. */
	void reset()
	{
		synchronized(lock)
		{
			while(!idle.isEmpty())
			{
				closeQuietly(idle.pollFirst());
			}
			downUntil = 0;
			backoffMs = firstBackoffMs;
		}
	}

	private static void closeQuietly(SocketChannel c)
	{
		try
		{
			if(c != null)
			{
				c.close();
			}
		}
		catch(IOException e)
		{
			// closing anyway
		}
	}
}
