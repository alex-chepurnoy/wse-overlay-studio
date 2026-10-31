/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.util.function.Consumer;

/** What a capture asks of the page it shows. BrowserPage is Chrome's; tests stand in their own. */
interface PageDriver
{
	/** A screencast frame: the PNG as Chrome encoded it, and the number its acknowledgment needs. */
	record Frame(byte[] png, int sessionId)
	{
	}

	/** Loads url and waits for its load event. @throws java.util.concurrent.TimeoutException when it does not load in time */
	void navigate(String url, long timeoutMs) throws Exception;

	/** A PNG of the page as it is now. */
	byte[] screenshot(long timeoutMs) throws Exception;

	/** Runs a script in the page; its result as JSON gives it, or null. */
	Object evaluate(String script, long timeoutMs) throws Exception;

	/** Starts a screencast at the page's size; frames go to sink, and each must be acknowledged before the next comes. */
	void startScreencast(int width, int height, Consumer<Frame> sink) throws Exception;

	void ackFrame(int sessionId);

	void stopScreencast();

	/** The page's JavaScript heap in use, in bytes, or -1 when it cannot be read. */
	long heapBytes();

	/** Calls listener once, with the reason, when the page is lost: its renderer crashed, or Chrome went away. */
	void onLost(Consumer<String> listener);

	/** Closes the page; it ends its renderer. */
	void close();
}
