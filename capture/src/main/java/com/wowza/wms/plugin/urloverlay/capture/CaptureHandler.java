/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;


import com.wowza.wms.plugin.urloverlay.capture.Wire.Message;

/**
 * What the service does for each request, apart from the socket. A capture the module opened is a handle on a pooled
 * browser: the same page with the same rules shares one Chrome. A handle nobody has asked a frame of for IDLE_MS is
 * closed, so captures of a WSE that stopped or restarted do not keep browsers open.
 */
public final class CaptureHandler
{
	static final long IDLE_MS = 60_000;

	/** What the handler needs of a browser; WebPageCapture in the service, a stand-in in tests. */
	public interface Page
	{
		/** Starts taking frames in the background at the interval, once. */
		void start();

		void interval(long ms);

		/** Waits up to waitMs for a frame newer than since; the latest frame's number. */
		long awaitFrame(long since, long waitMs) throws InterruptedException;

		/** The latest frame as Chrome encoded it, its number, when it was taken and how long the screenshot took. */
		byte[] lastPng();

		long seq();

		long capturedAtMs();

		long captureMs();

		/** Full status, with Chrome's processes: for the status operation only. */
		Map<String, Object> status();

		/** Status without the process lookup, for every frame answer. */
		Map<String, Object> quickStatus();

		void pageDelay(int ms);

		void reloadEvery(double rate);

		void addReloadListener(Runnable r);

		void removeReloadListener(Runnable r);

		/** Loads the page's address again now. */
		default void reloadNow(String why)
		{
		}
	}

	/** Opens or shares the browser for a request, and lets it go. */
	public interface Pages
	{
		Page acquire(PageRequest r);

		void release(Page p);
	}

	private record Handle(String id, PageRequest request, Page page, AtomicLong usedAt, AtomicLong reloads, Runnable onReload)
	{
	}

	private final Pages pages;
	private final Function<Map<String, Object>, Map<String, Object>> serviceStatus;
	private final java.util.function.IntUnaryOperator setLimit;
	private final Map<String, Handle> handles = new ConcurrentHashMap<>();

	public CaptureHandler(Pages pages, Function<Map<String, Object>, Map<String, Object>> serviceStatus)
	{
		this(pages, serviceStatus, null);
	}

	/** @param setLimit sets the number of pages captured at once and answers the limit in force; null refuses LIMITS */
	public CaptureHandler(Pages pages, Function<Map<String, Object>, Map<String, Object>> serviceStatus, java.util.function.IntUnaryOperator setLimit)
	{
		this.pages = pages;
		this.serviceStatus = serviceStatus;
		this.setLimit = setLimit;
	}

	/** The answer to one request. Never throws: a failure is an answer with ok false and a message. */
	public Message handle(Message m)
	{
		String op = m.str(Wire.OP);
		try
		{
			return switch(op == null ? "" : op)
			{
				case Wire.HELLO -> hello(m);
				case Wire.OPEN -> open(m);
				case Wire.UPDATE -> update(m);
				case Wire.FRAME -> frame(m);
				case Wire.STATUS -> status();
				case Wire.CLOSE -> close(m);
				case Wire.LIMITS -> limits(m);
				default -> new Message(Wire.error("Unknown operation " + op));
			};
		}
		catch(Exception e)
		{
			String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			return new Message(Wire.error(msg));
		}
	}

	static Message hello(Message m)
	{
		String mismatch = Wire.protocolMismatch(m.header().get("protocol"), "module");
		Map<String, Object> h = mismatch == null ? Wire.ok() : Wire.error(mismatch);
		h.put("protocol", Wire.PROTOCOL);
		h.put("version", CaptureVersion.VERSION);
		// new each time the service starts, so the module knows when to give it the page limit again
		h.put("serverTag", CaptureService.get().serverTag());
		return new Message(h);
	}

	private Message open(Message m)
	{
		PageRequest r = PageRequest.fromMap(m.header());
		Page p = pages.acquire(r);
		p.pageDelay(r.pageDelayMs());
		p.reloadEvery(r.reloadRate());
		p.interval(r.intervalMs());
		p.start();
		AtomicLong reloads = new AtomicLong();
		Runnable onReload = reloads::incrementAndGet;
		p.addReloadListener(onReload);
		String id = UUID.randomUUID().toString();
		handles.put(id, new Handle(id, r, p, new AtomicLong(System.currentTimeMillis()), reloads, onReload));
		Map<String, Object> h = Wire.ok();
		h.put("capture", id);
		return new Message(h);
	}

	private Handle find(Message m)
	{
		Handle h = handles.get(m.str("capture"));
		if(h == null)
		{
			// the module opens it again; this happens after a service restart
			throw new IllegalStateException("Unknown capture");
		}
		h.usedAt().set(System.currentTimeMillis());
		return h;
	}

	private Message update(Message m)
	{
		Handle h = find(m);
		if(m.header().get("pageDelayMs") instanceof Number n)
		{
			h.page().pageDelay(n.intValue());
		}
		if(m.header().get("reloadRate") instanceof Number n)
		{
			h.page().reloadEvery(n.doubleValue());
		}
		if(m.header().get("intervalMs") instanceof Number n)
		{
			h.page().interval(n.longValue());
		}
		if(Boolean.TRUE.equals(m.header().get("reload")))
		{
			h.page().reloadNow("asked from the page");
		}
		return new Message(Wire.ok());
	}

	/** The longest a frame request waits for a new frame. */
	static final long MAX_WAIT_MS = 5000;

	/**
	 * The latest frame when it is newer than "since", waiting up to "waitMs" for one; otherwise no body and "unchanged".
	 * Never takes a screenshot: the background loop does that.
	 */
	private Message frame(Message m) throws Exception
	{
		Handle h = find(m);
		long since = m.num("since", -1);
		long seq = h.page().awaitFrame(since, Math.min(MAX_WAIT_MS, m.num("waitMs", 0)));
		h.usedAt().set(System.currentTimeMillis());
		Map<String, Object> out = Wire.ok();
		byte[] png = null;
		if(seq > since)
		{
			png = h.page().lastPng();
		}
		out.put("seq", seq);
		out.put("unchanged", png == null);
		out.put("capturedAt", h.page().capturedAtMs());
		out.put("captureMs", h.page().captureMs());
		out.put("reloads", h.reloads().get());
		out.put("status", h.page().quickStatus());
		return new Message(out, png == null ? new byte[0] : png);
	}

	private Message limits(Message m)
	{
		Object n = m.header().get("maxCaptures");
		if(setLimit == null)
		{
			return new Message(Wire.error("This capture service does not change its limits"));
		}
		// checked as a double: a huge number must not wrap into range when it is made an int
		if(!(n instanceof Number num) || num.doubleValue() != Math.rint(num.doubleValue()) || num.doubleValue() < 1 || num.doubleValue() > CaptureService.MAX_CAPTURES_CEILING)
		{
			return new Message(Wire.error("maxCaptures must be a whole number from 1 to " + CaptureService.MAX_CAPTURES_CEILING));
		}
		Map<String, Object> h = Wire.ok();
		h.put("maxCaptures", setLimit.applyAsInt(num.intValue()));
		return new Message(h);
	}

	private Message status()
	{
		Map<String, Object> h = Wire.ok();
		List<Map<String, Object>> list = new ArrayList<>();
		for(Handle x: handles.values())
		{
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("label", x.request().label());
			c.putAll(x.page().status());
			list.add(c);
		}
		h.put("captures", list);
		h.putAll(serviceStatus.apply(h));
		return new Message(h);
	}

	private Message close(Message m)
	{
		Handle h = handles.remove(m.str("capture"));
		if(h != null)
		{
			release(h);
		}
		return new Message(Wire.ok());
	}

	private void release(Handle h)
	{
		h.page().removeReloadListener(h.onReload());
		pages.release(h.page());
	}

	/** Closes handles nobody asked a frame of for IDLE_MS. @return how many */
	public int closeIdle(long now)
	{
		int n = 0;
		for(Handle h: List.copyOf(handles.values()))
		{
			if(now - h.usedAt().get() > IDLE_MS && handles.remove(h.id(), h))
			{
				release(h);
				n++;
			}
		}
		return n;
	}

	public int open()
	{
		return handles.size();
	}

	/** The service's own pages: WebPageCapture through its pool. */
	public static Pages browsers()
	{
		return new Pages()
		{
			private final Map<Page, WebPageCapture> captures = new ConcurrentHashMap<>();

			@Override
			public Page acquire(PageRequest r)
			{
				WebPageCapture.Settings s = new WebPageCapture.Settings(r.url(), r.width(), r.height(), new Color(r.keyRgb()), r.paintBackground(), r.label());
				WebPageCapture c = WebPageCapture.POOL.acquire(new WebPageCapture.Key(s, r.rules()));
				Page p = adapt(c);
				captures.put(p, c);
				return p;
			}

			@Override
			public void release(Page p)
			{
				WebPageCapture c = captures.remove(p);
				if(c != null)
				{
					WebPageCapture.POOL.release(c);
				}
			}
		};
	}

	static Page adapt(WebPageCapture c)
	{
		return new Page()
		{
			@Override
			public void start()
			{
				c.startLoop();
			}

			@Override
			public void reloadNow(String why)
			{
				c.reloadNow(why);
			}

			@Override
			public void interval(long ms)
			{
				c.interval(ms);
			}

			@Override
			public long awaitFrame(long since, long waitMs) throws InterruptedException
			{
				return c.awaitFrame(since, waitMs);
			}

			@Override
			public byte[] lastPng()
			{
				return c.lastPng();
			}

			@Override
			public long seq()
			{
				return c.seq();
			}

			@Override
			public long capturedAtMs()
			{
				return c.capturedAtMs();
			}

			@Override
			public long captureMs()
			{
				return c.captureMs();
			}

			@Override
			public Map<String, Object> status()
			{
				return c.status();
			}

			@Override
			public Map<String, Object> quickStatus()
			{
				return c.quickStatus();
			}

			@Override
			public void pageDelay(int ms)
			{
				c.usePageDelay(() -> ms);
			}

			@Override
			public void reloadEvery(double rate)
			{
				c.reloadEvery(rate);
			}

			@Override
			public void addReloadListener(Runnable r)
			{
				c.addReloadListener(r);
			}

			@Override
			public void removeReloadListener(Runnable r)
			{
				c.removeReloadListener(r);
			}
		};
	}
}
