/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Finds where an element sits in an XML file's text, so the installer can insert or remove exact text and leave every
 * other byte as it is. Handles comments, processing instructions, CDATA and attributes; WSE config files use no DTDs.
 */
public final class XmlScan
{
	private XmlScan()
	{
	}

	/** An element's position: start tag, end of start tag, start of closing tag, end of closing tag. */
	public record Span(int open, int contentStart, int close, int end)
	{
	}

	/** Every element at this path, e.g. "/Root/Server/Properties", in document order. */
	public static List<Span> find(String text, String path)
	{
		List<Span> out = new ArrayList<>();
		Deque<String> stack = new ArrayDeque<>();
		Deque<int[]> starts = new ArrayDeque<>();
		int i = 0, n = text.length();
		while(i < n)
		{
			int lt = text.indexOf('<', i);
			if(lt < 0)
			{
				break;
			}
			if(text.startsWith("<!--", lt))
			{
				i = skip(text, lt, "-->");
				continue;
			}
			if(text.startsWith("<![CDATA[", lt))
			{
				i = skip(text, lt, "]]>");
				continue;
			}
			if(text.startsWith("<?", lt) || text.startsWith("<!", lt))
			{
				i = skip(text, lt, ">");
				continue;
			}
			int gt = tagEnd(text, lt);
			if(gt < 0)
			{
				throw new IllegalArgumentException("Unclosed tag at offset " + lt);
			}
			if(text.charAt(lt + 1) == '/')
			{
				String name = text.substring(lt + 2, gt).trim();
				if(stack.isEmpty() || !stack.peek().equals(name))
				{
					throw new IllegalArgumentException("Closing tag </" + name + "> does not match at offset " + lt);
				}
				String current = currentPath(stack);
				stack.pop();
				int[] st = starts.pop();
				if(current.equals(path))
				{
					out.add(new Span(st[0], st[1], lt, gt + 1));
				}
			}
			else
			{
				String inner = text.substring(lt + 1, gt);
				boolean selfClosing = inner.endsWith("/");
				String name = inner.replaceAll("/$", "").trim().split("[\\s/]", 2)[0];
				if(selfClosing)
				{
					stack.push(name);
					if(currentPath(stack).equals(path))
					{
						out.add(new Span(lt, gt + 1, gt + 1, gt + 1));
					}
					stack.pop();
				}
				else
				{
					stack.push(name);
					starts.push(new int[] { lt, gt + 1 });
				}
			}
			i = gt + 1;
		}
		return out;
	}

	public static Span first(String text, String path)
	{
		List<Span> all = find(text, path);
		return all.isEmpty() ? null : all.get(0);
	}

	private static int skip(String text, int from, String endMark)
	{
		int e = text.indexOf(endMark, from);
		if(e < 0)
		{
			throw new IllegalArgumentException("Unterminated markup at offset " + from);
		}
		return e + endMark.length();
	}

	// the '>' that ends a tag, skipping '>' inside quoted attribute values
	private static int tagEnd(String text, int lt)
	{
		char quote = 0;
		for(int k = lt + 1; k < text.length(); k++)
		{
			char c = text.charAt(k);
			if(quote != 0)
			{
				if(c == quote)
				{
					quote = 0;
				}
			}
			else if(c == '"' || c == '\'')
			{
				quote = c;
			}
			else if(c == '>')
			{
				return k;
			}
		}
		return -1;
	}

	private static String currentPath(Deque<String> stack)
	{
		StringBuilder b = new StringBuilder();
		java.util.Iterator<String> it = stack.descendingIterator();
		while(it.hasNext())
		{
			b.append('/').append(it.next());
		}
		return b.toString();
	}

	/** The whitespace at the start of the line holding offset. */
	public static String indentAt(String text, int offset)
	{
		int ls = text.lastIndexOf('\n', offset - 1) + 1;
		int k = ls;
		while(k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t'))
		{
			k++;
		}
		return text.substring(ls, k);
	}

	/** Start of the line holding offset. */
	public static int lineStart(String text, int offset)
	{
		return text.lastIndexOf('\n', offset - 1) + 1;
	}
}
