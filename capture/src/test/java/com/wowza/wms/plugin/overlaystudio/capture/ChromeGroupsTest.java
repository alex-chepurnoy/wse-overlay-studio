/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wowza.wms.plugin.overlaystudio.egress.AddressRules;

/** Which page a Chrome over its memory limit charges, and a Chrome that does not start. */
class ChromeGroupsTest
{
	static final long MB = 1024L * 1024L;

	@Test
	void theLargestHeapIsChargedAndKeptUntilTheChromeIsBackUnder()
	{
		ChromeGroups.Group g = new ChromeGroups.Group(AddressRules.defaults());
		WebPageCapture a = new WebPageCapture("http://a.example/", 16, 16, () -> 0, null), b = new WebPageCapture("http://b.example/", 16, 16, () -> 0, null);
		g.pages.add(a);
		g.pages.add(b);
		Map<WebPageCapture, Long> heap = new java.util.HashMap<>(Map.of(a, 10 * MB, b, 300 * MB));
		ChromeGroups groups = ChromeGroups.get();
		long cap = CaptureService.get().maxBrowserMb() * MB * 2;
		assertNull(groups.memoryVictim(g, cap, heap::get), "at the limit of two pages' worth, nothing is charged");
		assertNull(groups.memoryVictim(g, -1, heap::get), "unknown is not over");
		assertSame(b, groups.memoryVictim(g, cap + 1, heap::get), "the page with the largest heap");
		heap.put(a, 900 * MB);
		assertSame(b, groups.memoryVictim(g, cap + 1, heap::get), "kept while the Chrome stays over, so its 20 s count is not reset");
		assertNull(groups.memoryVictim(g, cap, heap::get), "back under");
		assertSame(a, groups.memoryVictim(g, cap + 1, heap::get), "picked again the next time");
		heap.put(b, 5000 * MB);
		assertSame(a, groups.memoryVictim(g, cap + 1, heap::get), "the charged page stays charged, out of restarts or not: no other page pays for it");
	}

	@Test
	void pagesWithEqualRulesBuiltSeparatelyShareAChrome() throws Exception
	{
		// what the module sends with every capture: a settings record; each request builds its own AddressRules from it
		com.wowza.wms.plugin.overlaystudio.egress.EgressRules e1 = new com.wowza.wms.plugin.overlaystudio.egress.EgressRules(false, "", "", true, "80,443,9");
		com.wowza.wms.plugin.overlaystudio.egress.EgressRules e2 = new com.wowza.wms.plugin.overlaystudio.egress.EgressRules(false, "", "", true, "80,443,9");
		WebPageCapture a = new WebPageCapture("http://a.example/", 16, 16, () -> 0, null), b = new WebPageCapture("http://b.example/", 16, 16, () -> 0, null);
		AtomicInteger starts = new AtomicInteger();
		java.util.concurrent.CompletableFuture<ChromeBrowser> never = new java.util.concurrent.CompletableFuture<>();
		ChromeGroups.Starter starter = g -> {
			starts.incrementAndGet();
			// a Chrome that is still starting: the second page must wait for this one, not start its own
			return never.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
		};
		Thread t = Thread.ofVirtual().start(() -> assertThrows(Exception.class, () -> ChromeGroups.get().join(a, e1.rules(), e1, starter)));
		Thread.sleep(100);
		assertThrows(Exception.class, () -> ChromeGroups.get().join(b, e2.rules(), e2, starter));
		t.join();
		assertEquals(1, starts.get(), "one Chrome was started for both pages");
	}

	@Test
	void aChromeThatDoesNotStartIsLeftByEveryPageAndClosed() throws Exception
	{
		AddressRules rules = new AddressRules(false, "", "", true, "80,443,7");
		WebPageCapture a = new WebPageCapture("http://a.example/", 16, 16, () -> 0, null);
		AtomicInteger starts = new AtomicInteger();
		Exception e = assertThrows(IllegalStateException.class, () -> ChromeGroups.get().join(a, rules, g -> {
			starts.incrementAndGet();
			throw new IllegalStateException("Chrome did not start (exit code 1)");
		}));
		assertTrue(e.getMessage().contains("did not start"), e.getMessage());
		assertTrue(ChromeGroups.get().all().stream().noneMatch(g -> g.rules.equals(rules)), "the failed group is gone");
		assertEquals(1, starts.get());
	}
}
