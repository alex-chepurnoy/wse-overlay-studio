/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.urloverlay.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of the capture protocol's headers: objects, arrays, strings, numbers, booleans and null. Written here so the
 * capture service and the module share it without a JSON library on either side. Whole numbers read back as Long,
 * others as Double.
 */
public final class Json
{
	private Json()
	{
	}

	public static String write(Object v)
	{
		StringBuilder b = new StringBuilder();
		write(b, v);
		return b.toString();
	}

	private static void write(StringBuilder b, Object v)
	{
		if(v == null)
		{
			b.append("null");
		}
		else if(v instanceof String s)
		{
			string(b, s);
		}
		else if(v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)
		{
			b.append(v);
		}
		else if(v instanceof Number n)
		{
			double d = n.doubleValue();
			if(Double.isNaN(d) || Double.isInfinite(d))
			{
				b.append("null");
			}
			else if(d == Math.rint(d) && Math.abs(d) < 1e15)
			{
				b.append((long)d);
			}
			else
			{
				b.append(d);
			}
		}
		else if(v instanceof Map<?, ?> m)
		{
			b.append('{');
			boolean first = true;
			for(Map.Entry<?, ?> e: m.entrySet())
			{
				if(!first)
				{
					b.append(',');
				}
				first = false;
				string(b, String.valueOf(e.getKey()));
				b.append(':');
				write(b, e.getValue());
			}
			b.append('}');
		}
		else if(v instanceof Iterable<?> it)
		{
			b.append('[');
			boolean first = true;
			for(Object o: it)
			{
				if(!first)
				{
					b.append(',');
				}
				first = false;
				write(b, o);
			}
			b.append(']');
		}
		else
		{
			string(b, v.toString());
		}
	}

	private static void string(StringBuilder b, String s)
	{
		b.append('"');
		for(int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
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

	/** Parses one JSON value; IllegalArgumentException when the text is not JSON. */
	public static Object parse(String s)
	{
		Parser p = new Parser(s);
		p.space();
		Object v = p.value();
		p.space();
		if(p.i != s.length())
		{
			throw new IllegalArgumentException("Text after the JSON value at " + p.i);
		}
		return v;
	}

	/** Parses a JSON object; IllegalArgumentException when the text is not one. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> object(String s)
	{
		Object v = parse(s);
		if(!(v instanceof Map))
		{
			throw new IllegalArgumentException("Not a JSON object");
		}
		return (Map<String, Object>)v;
	}

	private static final class Parser
	{
		final String s;
		int i;

		Parser(String s)
		{
			this.s = s;
		}

		void space()
		{
			while(i < s.length() && Character.isWhitespace(s.charAt(i)))
			{
				i++;
			}
		}

		IllegalArgumentException bad(String what)
		{
			return new IllegalArgumentException("Bad JSON at " + i + ": " + what);
		}

		Object value()
		{
			if(i >= s.length())
			{
				throw bad("end of text");
			}
			char c = s.charAt(i);
			if(c == '{')
			{
				return object();
			}
			if(c == '[')
			{
				return array();
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
			return number();
		}

		Map<String, Object> object()
		{
			Map<String, Object> m = new LinkedHashMap<>();
			i++;
			space();
			if(i < s.length() && s.charAt(i) == '}')
			{
				i++;
				return m;
			}
			while(true)
			{
				space();
				if(i >= s.length() || s.charAt(i) != '"')
				{
					throw bad("expected a name");
				}
				String k = string();
				space();
				if(i >= s.length() || s.charAt(i) != ':')
				{
					throw bad("expected ':'");
				}
				i++;
				space();
				m.put(k, value());
				space();
				if(i < s.length() && s.charAt(i) == ',')
				{
					i++;
					continue;
				}
				if(i < s.length() && s.charAt(i) == '}')
				{
					i++;
					return m;
				}
				throw bad("expected ',' or '}'");
			}
		}

		List<Object> array()
		{
			List<Object> l = new ArrayList<>();
			i++;
			space();
			if(i < s.length() && s.charAt(i) == ']')
			{
				i++;
				return l;
			}
			while(true)
			{
				space();
				l.add(value());
				space();
				if(i < s.length() && s.charAt(i) == ',')
				{
					i++;
					continue;
				}
				if(i < s.length() && s.charAt(i) == ']')
				{
					i++;
					return l;
				}
				throw bad("expected ',' or ']'");
			}
		}

		String string()
		{
			StringBuilder b = new StringBuilder();
			i++;
			while(i < s.length())
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
				if(i >= s.length())
				{
					break;
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
						if(i + 4 > s.length())
						{
							throw bad("short \\u escape");
						}
						b.append((char)Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> b.append(e);
				}
			}
			throw bad("unterminated string");
		}

		Object number()
		{
			int start = i;
			while(i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0)
			{
				i++;
			}
			String t = s.substring(start, i);
			if(t.isEmpty())
			{
				throw bad("unexpected character");
			}
			try
			{
				return t.contains(".") || t.contains("e") || t.contains("E") ? (Object)Double.parseDouble(t) : (Object)Long.parseLong(t);
			}
			catch(NumberFormatException e)
			{
				throw bad("not a number: " + t);
			}
		}
	}
}
