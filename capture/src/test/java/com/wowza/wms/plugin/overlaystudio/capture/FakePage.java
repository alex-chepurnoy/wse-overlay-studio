/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A page that stands in for Chrome's: screenshots and screencast frames on demand, and counts of what was asked. */
class FakePage implements PageDriver
{
	volatile byte[] png;
	final AtomicInteger shots = new AtomicInteger();
	final AtomicInteger closes = new AtomicInteger();
	final AtomicInteger casts = new AtomicInteger();
	final AtomicInteger stops = new AtomicInteger();
	final List<Integer> acks = new CopyOnWriteArrayList<>();
	volatile String address = "http://example.com/";
	// a hung renderer: scripts never answer
	volatile boolean hung = false;
	volatile Consumer<Frame> sink;
	private final List<Consumer<String>> lost = new CopyOnWriteArrayList<>();
	private final AtomicInteger session = new AtomicInteger();

	FakePage(byte[] png)
	{
		this.png = png;
	}

	@Override
	public void navigate(String url, long timeoutMs)
	{
		address = url;
	}

	@Override
	public byte[] screenshot(long timeoutMs)
	{
		shots.incrementAndGet();
		return png;
	}

	@Override
	public Object evaluate(String script, long timeoutMs) throws Exception
	{
		if(hung)
		{
			throw new java.util.concurrent.TimeoutException("Runtime.evaluate got no answer from Chrome in " + timeoutMs + " ms");
		}
		return script.equals("location.href") ? address : null;
	}

	@Override
	public void startScreencast(int width, int height, Consumer<Frame> sink)
	{
		casts.incrementAndGet();
		this.sink = sink;
	}

	/** Sends a screencast frame, as Chrome does when the page repaints. @return its session number */
	int frame(byte[] frame)
	{
		int s = session.incrementAndGet();
		Consumer<Frame> k = sink;
		if(k != null)
		{
			k.accept(new Frame(frame, s));
		}
		return s;
	}

	@Override
	public void ackFrame(int sessionId)
	{
		acks.add(sessionId);
	}

	@Override
	public void stopScreencast()
	{
		stops.incrementAndGet();
		sink = null;
	}

	@Override
	public long heapBytes()
	{
		return 1;
	}

	@Override
	public void onLost(Consumer<String> listener)
	{
		lost.add(listener);
	}

	/** As Chrome does when the page's renderer crashes. */
	void crash()
	{
		lost.forEach(l -> l.accept("the page's renderer crashed"));
	}

	@Override
	public void close()
	{
		closes.incrementAndGet();
	}
}
