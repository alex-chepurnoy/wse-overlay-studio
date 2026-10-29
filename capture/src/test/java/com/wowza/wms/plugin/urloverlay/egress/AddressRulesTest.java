/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Range membership written out by hand from the RFCs: 10/8, 172.16/12, 192.168/16 (RFC 1918), 100.64/10
 * (RFC 6598), 169.254/16 (RFC 3927), fc00::/7 (RFC 4193), fe80::/10, loopback, and the cloud metadata addresses.
 */
class AddressRulesTest
{
	private static AddressRules.Decision check(AddressRules r, String host, String... ips) throws Exception
	{
		InetAddress[] a = new InetAddress[ips.length];
		for(int i = 0; i < ips.length; i++)
		{
			a[i] = InetAddress.getByName(ips[i]);
		}
		return r.check(host, a);
	}

	@ParameterizedTest
	@CsvSource({
			"127.0.0.1, loopback",
			"127.255.0.9, loopback",
			"::1, loopback",
			"10.0.0.12, private",
			"172.16.0.1, private",
			"172.31.255.255, private",
			"192.168.1.1, private",
			"100.64.0.1, cgnat",
			"100.127.255.254, cgnat",
			"169.254.10.10, link-local",
			"fe80::1, link-local",
			"fc00::1, unique-local",
			"fd12:3456::1, unique-local",
			"0.0.0.0, unspecified",
			"224.0.0.1, multicast",
			"169.254.169.254, cloud-metadata",
			"fd00:ec2::254, cloud-metadata",
			"::ffff:10.1.2.3, private",
	})
	void specialAddressesAreBlockedByDefault(String ip, String rule) throws Exception
	{
		AddressRules.Decision d = check(AddressRules.defaults(), "h", ip);
		assertFalse(d.allowed(), ip);
		assertEquals(rule, d.rule(), ip);
	}

	@ParameterizedTest
	@CsvSource({ "198.51.100.8", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "2001:db8::8888", "11.0.0.1" })
	void publicAddressesAreAllowed(String ip) throws Exception
	{
		assertTrue(check(AddressRules.defaults(), "h", ip).allowed(), ip);
	}

	@Test
	void oneBlockedAddressBlocksTheHost() throws Exception
	{
		AddressRules.Decision d = check(AddressRules.defaults(), "mixed.example", "198.51.100.8", "10.0.0.1");
		assertFalse(d.allowed());
		assertEquals("10.0.0.1", d.address());
	}

	@Test
	void allowPrivateOpensRangesButNotMetadataNorThisHost() throws Exception
	{
		Set<InetAddress> mine = Set.of(InetAddress.getByName("10.0.0.5"));
		AddressRules r = new AddressRules(true, null, null).localAddresses(() -> mine);
		assertTrue(check(r, "h", "10.0.0.12").allowed());
		assertEquals("loopback", check(r, "h", "127.0.0.1").rule(), "loopback is this host, not a private network");
		assertEquals("loopback", check(r, "h", "::1").rule());
		assertEquals("link-local", check(r, "h", "169.254.10.10").rule());
		assertEquals("link-local", check(r, "h", "fe80::1").rule());
		assertEquals("unspecified", check(r, "h", "0.0.0.0").rule());
		assertEquals("this-host", check(r, "h", "10.0.0.5").rule(), "this host's own interface address");
		assertEquals("cloud-metadata", check(r, "h", "169.254.169.254").rule());
	}

	@Test
	void thisHostOpensOnlyForAnExactAddressEntry() throws Exception
	{
		Set<InetAddress> mine = Set.of(InetAddress.getByName("203.0.113.7"));
		assertEquals("this-host", check(new AddressRules(false, null, null).localAddresses(() -> mine), "h", "203.0.113.7").rule(),
				"the server's public address is blocked like loopback");
		assertEquals("this-host", check(new AddressRules(false, "203.0.113.0/24", null).localAddresses(() -> mine), "h", "203.0.113.7").rule(),
				"a range is not an exact entry");
		assertEquals("this-host", check(new AddressRules(false, "wse.example.com", null).localAddresses(() -> mine), "wse.example.com", "203.0.113.7").rule(),
				"a hostname entry is not an address entry");
		assertTrue(check(new AddressRules(false, "203.0.113.7", null).localAddresses(() -> mine), "h", "203.0.113.7").allowed());
		assertTrue(check(new AddressRules(false, "203.0.113.0/24", null).localAddresses(() -> mine), "h", "203.0.113.8").allowed(),
				"other machines in the range are opened by it");
		assertEquals("loopback", check(new AddressRules(true, "127.0.0.0/8", null), "h", "127.0.0.1").rule());
		assertTrue(check(new AddressRules(false, "127.0.0.1", null), "h", "127.0.0.1").allowed());
	}

	@Test
	void theInterfaceAddressesAreRead()
	{
		// loopback is refused by its range anyway; what matters is every other address an interface carries
		java.util.Set<InetAddress> expected = new java.util.HashSet<>();
		try
		{
			for(java.net.NetworkInterface n: java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
			{
				for(InetAddress a: java.util.Collections.list(n.getInetAddresses()))
				{
					if(!a.isLoopbackAddress())
					{
						expected.add(AddressRules.withoutScope(AddressRules.normalize(a)));
					}
				}
			}
		}
		catch(java.net.SocketException e)
		{
			org.junit.jupiter.api.Assumptions.abort("no interfaces to list: " + e);
		}
		org.junit.jupiter.api.Assumptions.assumeFalse(expected.isEmpty(), "this machine reports no address besides loopback");
		assertTrue(LocalAddresses.read().containsAll(expected), "read " + LocalAddresses.read() + ", expected " + expected);
	}

	@Test
	void onlyPorts80And443UnlessMoreAreAllowed()
	{
		AddressRules r = AddressRules.defaults();
		assertTrue(r.checkPort(80).allowed());
		assertTrue(r.checkPort(443).allowed());
		for(int p: new int[] { 22, 8080, 8087, 8088, 1935, 9515 })
		{
			assertEquals("port", r.checkPort(p).rule(), String.valueOf(p));
		}
		AddressRules more = new AddressRules(false, null, null, true, "8080, 8443,  x, 70000");
		assertTrue(more.checkPort(8080).allowed());
		assertTrue(more.checkPort(8443).allowed());
		assertFalse(more.checkPort(8087).allowed());
		assertEquals(java.util.List.of(80, 443, 8080, 8443), more.allowedPorts());
	}

	@Test
	void allowListByCidrAddressAndHost() throws Exception
	{
		AddressRules r = new AddressRules(false, "10.20.0.0/16, 127.0.0.1, scoreboard.internal", null);
		assertTrue(check(r, "a", "10.20.3.4").allowed());
		assertFalse(check(r, "a", "10.21.0.1").allowed(), "outside the /16");
		assertTrue(check(r, "a", "127.0.0.1").allowed());
		assertTrue(check(r, "scoreboard.internal", "192.168.5.5").allowed(), "host entry allows its addresses");
		assertEquals("cloud-metadata", check(r, "scoreboard.internal", "169.254.169.254").rule(), "a host entry never unblocks metadata");
		// metadata opens for an explicit address entry
		{
			AddressRules r2 = new AddressRules(false, "169.254.169.254", null);
			assertTrue(check(r2, "h", "169.254.169.254").allowed());
		}
	}

	@Test
	void blockListBeatsAllowList() throws Exception
	{
		AddressRules r = new AddressRules(true, "198.51.100.0/24, ads.example", "198.51.100.8, ads.example");
		assertEquals("block-list", check(r, "h", "198.51.100.8").rule());
		assertTrue(check(r, "h", "198.51.100.9").allowed());
		assertEquals("block-list", check(r, "ads.example", "192.0.2.1").rule());
	}

	@Test
	void chromeServiceHostsAreBlockedByDefault() throws Exception
	{
		// the six hosts full Chrome contacts on its own, from a proxy trace
		for(String h: new String[] { "clients2.google.com", "accounts.google.com", "www.google.com", "update.googleapis.com",
				"edgedl.me.gvt1.com", "optimizationguide-pa.googleapis.com" })
		{
			AddressRules.Decision d = check(AddressRules.defaults(), h, "203.0.113.80");
			assertFalse(d.allowed(), h);
			assertEquals("chrome-service", d.rule(), h);
		}
		assertEquals("chrome-service", check(AddressRules.defaults(), "WWW.Google.COM", "203.0.113.80").rule(), "host match ignores case");
		// other Google hosts a page may use are not affected
		assertTrue(check(AddressRules.defaults(), "fonts.googleapis.com", "203.0.113.80").allowed());
		assertTrue(check(AddressRules.defaults(), "maps.google.com", "203.0.113.80").allowed());
	}

	@Test
	void chromeServiceBlockCanBeOverriddenOrTurnedOff() throws Exception
	{
		assertTrue(check(new AddressRules(false, "www.google.com", null), "www.google.com", "203.0.113.80").allowed(), "explicit allow entry");
		assertTrue(check(new AddressRules(false, null, null, false), "www.google.com", "203.0.113.80").allowed(), "UrlOverlayBlockChromeServices=false");
		assertEquals("block-list", check(new AddressRules(false, "www.google.com", "www.google.com"), "www.google.com", "203.0.113.80").rule(),
				"block list still wins");
	}

	@Test
	void unresolvedHostIsBlocked() throws Exception
	{
		assertEquals("unresolved", AddressRules.defaults().check("h", new InetAddress[0]).rule());
	}

}
