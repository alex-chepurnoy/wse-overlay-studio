/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.install;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the install manifest: objects, arrays, strings, numbers, booleans and null. JDK only. */
final class Json
{
	private final String s;
	private int i;

	private Json(String s)
	{
		this.s = s;
	}

	static Object parse(String text)
	{
		Json j = new Json(text);
		Object v = j.value();
		j.ws();
		if(j.i != text.length())
		{
			throw new IllegalArgumentException("Trailing data in JSON at " + j.i);
		}
		return v;
	}

	private void ws()
	{
		while(i < s.length() && Character.isWhitespace(s.charAt(i)))
		{
			i++;
		}
	}

	private Object value()
	{
		ws();
		char c = s.charAt(i);
		if(c == '{')
		{
			Map<String, Object> m = new LinkedHashMap<>();
			i++;
			ws();
			if(s.charAt(i) == '}')
			{
				i++;
				return m;
			}
			while(true)
			{
				ws();
				String k = string();
				ws();
				expect(':');
				m.put(k, value());
				ws();
				if(s.charAt(i) == ',')
				{
					i++;
					continue;
				}
				expect('}');
				return m;
			}
		}
		if(c == '[')
		{
			List<Object> l = new ArrayList<>();
			i++;
			ws();
			if(s.charAt(i) == ']')
			{
				i++;
				return l;
			}
			while(true)
			{
				l.add(value());
				ws();
				if(s.charAt(i) == ',')
				{
					i++;
					continue;
				}
				expect(']');
				return l;
			}
		}
		if(c == '"')
		{
			return string();
		}
		if(s.startsWith("true", i))
		{
			i += 4;
			return Boolean.TRUE;
		}
		if(s.startsWith("false", i))
		{
			i += 5;
			return Boolean.FALSE;
		}
		if(s.startsWith("null", i))
		{
			i += 4;
			return null;
		}
		int st = i;
		while(i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0)
		{
			i++;
		}
		if(st == i)
		{
			throw new IllegalArgumentException("Unexpected '" + c + "' in JSON at " + i);
		}
		String num = s.substring(st, i);
		return num.matches("-?\\d+") ? (Object)Long.parseLong(num) : (Object)Double.parseDouble(num);
	}

	private void expect(char c)
	{
		if(s.charAt(i) != c)
		{
			throw new IllegalArgumentException("Expected '" + c + "' in JSON at " + i);
		}
		i++;
	}

	private String string()
	{
		expect('"');
		StringBuilder b = new StringBuilder();
		while(true)
		{
			char c = s.charAt(i++);
			if(c == '"')
			{
				return b.toString();
			}
			if(c != '\\')
			{
				b.append(c);
				continue;
			}
			char e = s.charAt(i++);
			switch(e)
			{
				case 'n' -> b.append('\n');
				case 'r' -> b.append('\r');
				case 't' -> b.append('\t');
				case 'b' -> b.append('\b');
				case 'f' -> b.append('\f');
				case 'u' -> {
					b.append((char)Integer.parseInt(s.substring(i, i + 4), 16));
					i += 4;
				}
				default -> b.append(e);
			}
		}
	}

	static String write(Object v)
	{
		StringBuilder b = new StringBuilder();
		write(b, v, "");
		return b.append('\n').toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder b, Object v, String ind)
	{
		if(v == null)
		{
			b.append("null");
		}
		else if(v instanceof Map)
		{
			Map<String, Object> m = (Map<String, Object>)v;
			if(m.isEmpty())
			{
				b.append("{}");
				return;
			}
			b.append("{\n");
			int k = 0;
			for(Map.Entry<String, Object> e: m.entrySet())
			{
				b.append(ind).append("  ");
				quote(b, e.getKey());
				b.append(": ");
				write(b, e.getValue(), ind + "  ");
				b.append(++k < m.size() ? ",\n" : "\n");
			}
			b.append(ind).append('}');
		}
		else if(v instanceof List)
		{
			List<Object> l = (List<Object>)v;
			if(l.isEmpty())
			{
				b.append("[]");
				return;
			}
			b.append("[\n");
			for(int k = 0; k < l.size(); k++)
			{
				b.append(ind).append("  ");
				write(b, l.get(k), ind + "  ");
				b.append(k + 1 < l.size() ? ",\n" : "\n");
			}
			b.append(ind).append(']');
		}
		else if(v instanceof Number || v instanceof Boolean)
		{
			b.append(v);
		}
		else
		{
			quote(b, v.toString());
		}
	}

	private static void quote(StringBuilder b, String s)
	{
		b.append('"');
		for(char c: s.toCharArray())
		{
			switch(c)
			{
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if(c < 0x20)
					{
						b.append(String.format("\\u%04x", (int)c));
					}
					else
					{
						b.append(c);
					}
				}
			}
		}
		b.append('"');
	}
}
