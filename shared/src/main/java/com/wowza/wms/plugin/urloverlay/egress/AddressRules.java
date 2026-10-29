/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.egress;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a captured page may connect to a host and port. Order: the port (80 and 443, plus
 * UrlOverlayAllowPorts), UrlOverlayBlockHosts, cloud metadata, this host's own addresses (loopback, link-local,
 * unspecified and every interface address), UrlOverlayAllowHosts, private and special ranges, otherwise allow.
 * Metadata and this host's own addresses open only for an exact address entry in UrlOverlayAllowHosts, never for a
 * hostname entry, a range or UrlOverlayAllowPrivate. Every resolved address must pass; one blocked address blocks the
 * host. Names are resolved before the decision so a DNS answer cannot change between check and connect; the lookup
 * itself still reaches the resolver.
 */
public final class AddressRules
{
	public record Decision(boolean allowed, String rule, String address)
	{
		static Decision allow(String address)
		{
			return new Decision(true, "allowed", address);
		}

		static Decision block(String rule, String address)
		{
			return new Decision(false, rule, address);
		}
	}

	/** One UrlOverlayAllowHosts or UrlOverlayBlockHosts entry: a hostname, an address, or a CIDR range. */
	record Entry(String host, byte[] network, int prefix)
	{
		boolean matchesHost(String h)
		{
			return host != null && host.equalsIgnoreCase(h);
		}

		boolean matchesAddress(InetAddress a)
		{
			if(network == null)
			{
				return false;
			}
			byte[] b = normalize(a).getAddress();
			if(b.length != network.length)
			{
				return false;
			}
			return inPrefix(b, network, prefix);
		}
	}

	/**
	 * Hosts full Chrome contacts on its own (network time, sign-in, updater, component downloads, optimization
	 * guide), as seen in a proxy trace of Chrome. Refused by default (UrlOverlayBlockChromeServices);
	 * an explicit UrlOverlayAllowHosts entry for one of them lets a page that needs it through.
	 */
	public static final java.util.Set<String> CHROME_SERVICE_HOSTS = java.util.Set.of(
			"clients2.google.com",
			"accounts.google.com",
			"www.google.com",
			"update.googleapis.com",
			"edgedl.me.gvt1.com",
			"optimizationguide-pa.googleapis.com");

	public static final String RULE_CHROME_SERVICE = "chrome-service";

	private static final byte[] METADATA_V4 = { (byte)169, (byte)254, (byte)169, (byte)254 };
	private static final byte[] METADATA_V6 = parseLiteral("fd00:ec2::254");

	/** The ports a page may reach without UrlOverlayAllowPorts. */
	public static final java.util.Set<Integer> DEFAULT_PORTS = java.util.Set.of(80, 443);
	public static final String RULE_PORT = "port";
	public static final String RULE_THIS_HOST = "this-host";

	private final boolean allowPrivate;
	private final boolean blockChromeServices;
	private final List<Entry> allow;
	private final List<Entry> block;
	private final java.util.SortedSet<Integer> ports;
	private java.util.function.Supplier<java.util.Set<InetAddress>> local = LocalAddresses.SYSTEM;

	public AddressRules(boolean allowPrivate, String allowHosts, String blockHosts)
	{
		this(allowPrivate, allowHosts, blockHosts, true);
	}

	public AddressRules(boolean allowPrivate, String allowHosts, String blockHosts, boolean blockChromeServices)
	{
		this(allowPrivate, allowHosts, blockHosts, blockChromeServices, null);
	}

	/** @param allowPorts ports besides 80 and 443, comma separated (UrlOverlayAllowPorts) */
	public AddressRules(boolean allowPrivate, String allowHosts, String blockHosts, boolean blockChromeServices, String allowPorts)
	{
		this.allowPrivate = allowPrivate;
		this.blockChromeServices = blockChromeServices;
		this.allow = parseList(allowHosts);
		this.block = parseList(blockHosts);
		this.ports = parsePorts(allowPorts);
	}

	/** Uses these as this host's own addresses instead of the interfaces'; for tests. */
	AddressRules localAddresses(java.util.function.Supplier<java.util.Set<InetAddress>> local)
	{
		this.local = local;
		return this;
	}

	public List<Integer> allowedPorts()
	{
		return new ArrayList<>(ports);
	}

	/** Whether a page may connect to this port at all; checked before any name is resolved. */
	public Decision checkPort(int port)
	{
		return ports.contains(port) ? Decision.allow(String.valueOf(port)) : Decision.block(RULE_PORT, String.valueOf(port));
	}

	static java.util.SortedSet<Integer> parsePorts(String csv)
	{
		java.util.SortedSet<Integer> out = new java.util.TreeSet<>(DEFAULT_PORTS);
		for(String raw: csv == null ? new String[0] : csv.split(","))
		{
			try
			{
				int p = Integer.parseInt(raw.trim());
				if(p > 0 && p < 65536)
				{
					out.add(p);
				}
			}
			catch(NumberFormatException e)
			{
				// not a port; ignored
			}
		}
		return java.util.Collections.unmodifiableSortedSet(out);
	}

	public static AddressRules defaults()
	{
		return new AddressRules(false, null, null);
	}

	public boolean allowPrivate()
	{
		return allowPrivate;
	}

	public boolean blockChromeServices()
	{
		return blockChromeServices;
	}

	public List<String> allowEntries()
	{
		return describe(allow);
	}

	public List<String> blockEntries()
	{
		return describe(block);
	}

	/** Resolves the host and checks every address. A name that does not resolve is blocked. */
	public Decision check(String host)
	{
		InetAddress[] addresses;
		try
		{
			addresses = InetAddress.getAllByName(host);
		}
		catch(UnknownHostException e)
		{
			return Decision.block("unresolved", host);
		}
		return check(host, addresses);
	}

	public Decision check(String host, InetAddress[] addresses)
	{
		if(addresses == null || addresses.length == 0)
		{
			return Decision.block("unresolved", host);
		}
		for(Entry e: block)
		{
			if(e.matchesHost(host))
			{
				return Decision.block("block-list", host);
			}
		}
		boolean hostAllowed = false;
		for(Entry e: allow)
		{
			if(e.matchesHost(host))
			{
				hostAllowed = true;
			}
		}
		if(blockChromeServices && !hostAllowed && host != null && CHROME_SERVICE_HOSTS.contains(host.toLowerCase(Locale.ROOT)))
		{
			return Decision.block(RULE_CHROME_SERVICE, host);
		}
		Decision last = null;
		for(InetAddress raw: addresses)
		{
			InetAddress a = normalize(raw);
			String text = a.getHostAddress();
			Decision d = checkAddress(a, text, hostAllowed);
			if(!d.allowed())
			{
				return d;
			}
			last = d;
		}
		return last;
	}

	private Decision checkAddress(InetAddress a, String text, boolean hostAllowed)
	{
		for(Entry e: block)
		{
			if(e.matchesAddress(a))
			{
				return Decision.block("block-list", text);
			}
		}
		boolean explicitlyAllowed = false;
		boolean exactlyAllowed = false;
		for(Entry e: allow)
		{
			if(e.matchesAddress(a))
			{
				explicitlyAllowed = true;
				exactlyAllowed |= e.prefix() == e.network().length * 8;
			}
		}
		if(isMetadata(a))
		{
			// only an explicit address or range entry unblocks metadata, never a hostname or allowPrivate
			return explicitlyAllowed ? Decision.allow(text) : Decision.block("cloud-metadata", text);
		}
		String own = thisHost(a);
		if(own != null)
		{
			// WSE, the Manager, REST and chromedriver listen here: only an exact address entry opens it
			return exactlyAllowed ? Decision.allow(text) : Decision.block(own, text);
		}
		if(explicitlyAllowed || hostAllowed)
		{
			return Decision.allow(text);
		}
		String special = specialRange(a);
		if(special != null && !allowPrivate)
		{
			return Decision.block(special, text);
		}
		return Decision.allow(text);
	}

	/** The rule that keeps a page off this host itself, or null when the address is another machine's. */
	String thisHost(InetAddress a)
	{
		if(a.isAnyLocalAddress())
		{
			return "unspecified";
		}
		if(a.isLoopbackAddress())
		{
			return "loopback";
		}
		if(a.isLinkLocalAddress())
		{
			return "link-local";
		}
		if(a.getAddress().length == 4 && (a.getAddress()[0] & 0xff) == 0)
		{
			return "unspecified";
		}
		java.util.Set<InetAddress> mine = local == null ? null : local.get();
		return mine != null && mine.contains(withoutScope(a)) ? RULE_THIS_HOST : null;
	}

	/** The address without an IPv6 scope, so interface addresses compare by their bytes alone. */
	static InetAddress withoutScope(InetAddress a)
	{
		try
		{
			return InetAddress.getByAddress(a.getAddress());
		}
		catch(UnknownHostException e)
		{
			return a;
		}
	}

	/** @return the name of the special range the address is in, or null for a public address */
	static String specialRange(InetAddress a)
	{
		if(a.isAnyLocalAddress())
		{
			return "unspecified";
		}
		if(a.isLoopbackAddress())
		{
			return "loopback";
		}
		if(a.isLinkLocalAddress())
		{
			return "link-local";
		}
		if(a.isMulticastAddress())
		{
			return "multicast";
		}
		byte[] b = a.getAddress();
		if(a instanceof Inet4Address)
		{
			int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
			if(b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168))
			{
				return "private";
			}
			if(b0 == 100 && b1 >= 64 && b1 <= 127)
			{
				return "cgnat";
			}
			if(b0 == 0)
			{
				return "unspecified";
			}
			if(b0 >= 240)
			{
				return "reserved";
			}
			return null;
		}
		if(a instanceof Inet6Address)
		{
			if((b[0] & 0xfe) == 0xfc)
			{
				return "unique-local";
			}
			if(a.isSiteLocalAddress())
			{
				return "site-local";
			}
		}
		return null;
	}

	static boolean isMetadata(InetAddress a)
	{
		byte[] b = a.getAddress();
		return java.util.Arrays.equals(b, METADATA_V4) || java.util.Arrays.equals(b, METADATA_V6);
	}

	/** IPv4-mapped IPv6 addresses (::ffff:a.b.c.d) are judged as the IPv4 address they carry. */
	static InetAddress normalize(InetAddress a)
	{
		if(a instanceof Inet6Address)
		{
			byte[] b = a.getAddress();
			boolean mapped = true;
			for(int i = 0; i < 10; i++)
			{
				mapped &= b[i] == 0;
			}
			mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
			if(mapped)
			{
				try
				{
					return InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] });
				}
				catch(UnknownHostException e)
				{
					return a;
				}
			}
		}
		return a;
	}

	static List<Entry> parseList(String csv)
	{
		List<Entry> out = new ArrayList<>();
		if(csv == null)
		{
			return out;
		}
		for(String raw: csv.split(","))
		{
			String s = raw.trim();
			if(s.isEmpty())
			{
				continue;
			}
			int slash = s.indexOf('/');
			String addressPart = slash >= 0 ? s.substring(0, slash) : s;
			byte[] net = parseLiteral(addressPart);
			if(net == null)
			{
				out.add(new Entry(s.toLowerCase(Locale.ROOT), null, 0));
				continue;
			}
			int prefix = net.length * 8;
			if(slash >= 0)
			{
				try
				{
					prefix = Math.max(0, Math.min(net.length * 8, Integer.parseInt(s.substring(slash + 1).trim())));
				}
				catch(NumberFormatException e)
				{
					continue;
				}
			}
			out.add(new Entry(null, net, prefix));
		}
		return out;
	}

	/** Parses an IP literal without any DNS lookup; null if the text is not a literal address. */
	static byte[] parseLiteral(String s)
	{
		if(s == null || s.isEmpty())
		{
			return null;
		}
		boolean v4 = s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
		boolean v6 = s.indexOf(':') >= 0 && s.matches("[0-9a-fA-F:.]+");
		if(!v4 && !v6)
		{
			return null;
		}
		if(v4)
		{
			for(String part: s.split("\\."))
			{
				if(Integer.parseInt(part) > 255)
				{
					return null;
				}
			}
		}
		try
		{
			return normalize(InetAddress.getByName(s)).getAddress();
		}
		catch(UnknownHostException e)
		{
			return null;
		}
	}

	static boolean inPrefix(byte[] address, byte[] network, int prefix)
	{
		int full = prefix / 8, rest = prefix % 8;
		for(int i = 0; i < full; i++)
		{
			if(address[i] != network[i])
			{
				return false;
			}
		}
		if(rest == 0)
		{
			return true;
		}
		int mask = (0xff << (8 - rest)) & 0xff;
		return (address[full] & mask) == (network[full] & mask);
	}

	private static List<String> describe(List<Entry> entries)
	{
		List<String> out = new ArrayList<>();
		for(Entry e: entries)
		{
			if(e.host() != null)
			{
				out.add(e.host());
			}
			else
			{
				try
				{
					out.add(InetAddress.getByAddress(e.network()).getHostAddress() + "/" + e.prefix());
				}
				catch(UnknownHostException ex)
				{
					// unreachable for a parsed literal
				}
			}
		}
		return out;
	}
}
