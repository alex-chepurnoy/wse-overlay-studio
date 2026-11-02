/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.egress;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The addresses of this host's own network interfaces, so a captured page cannot reach WSE, the Manager or chromedriver
 * through the server's public or private address. Read again once a minute, since addresses change (DHCP, VPN, Docker).
 */
public final class LocalAddresses implements Supplier<Set<InetAddress>>
{
	public static final LocalAddresses SYSTEM = new LocalAddresses(60_000);

	private final long maxAgeMs;
	private volatile Set<InetAddress> cached = Collections.emptySet();
	private volatile long readAt = 0;

	LocalAddresses(long maxAgeMs)
	{
		this.maxAgeMs = maxAgeMs;
	}

	@Override
	public Set<InetAddress> get()
	{
		long now = System.currentTimeMillis();
		if(readAt == 0 || now - readAt > maxAgeMs)
		{
			cached = read();
			readAt = now;
		}
		return cached;
	}

	static Set<InetAddress> read()
	{
		Set<InetAddress> out = new HashSet<>();
		try
		{
			for(NetworkInterface n: Collections.list(NetworkInterface.getNetworkInterfaces()))
			{
				for(InetAddress a: Collections.list(n.getInetAddresses()))
				{
					out.add(AddressRules.withoutScope(AddressRules.normalize(a)));
				}
			}
		}
		catch(Exception e)
		{
			// none listed: loopback and the special ranges are still refused by their own rules
		}
		return Collections.unmodifiableSet(out);
	}
}
