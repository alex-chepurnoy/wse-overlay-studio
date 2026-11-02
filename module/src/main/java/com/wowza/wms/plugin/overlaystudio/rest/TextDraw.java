/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.overlaystudio.rest;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import com.wowza.wms.plugin.overlaystudio.TextTokens;

/**
 * Draws a text layer (plan 3.2): its tokens for this moment, laid out free or in a box (wrapped at w, clipped at h,
 * aligned, shrunk to fit), over its background, with its outline and shadow. Every length is in source pixels times
 * the output's scale.
 */
final class TextDraw
{
	static final double DEFAULT_LINE_HEIGHT = 1.2;
	// render quality for measuring, the same as drawing
	static final FontRenderContext FRC = new FontRenderContext(null, true, true);

	private TextDraw()
	{
	}

	/** How the text sits: the font, the lines and their widths, the line step and the first baseline. */
	record Layout(Font font, List<String> lines, float[] widths, float lineStep, float ascent, float descent)
	{
		float maxWidth()
		{
			float m = 0;
			for(float w: widths)
			{
				m = Math.max(m, w);
			}
			return m;
		}

		float height()
		{
			return lines.isEmpty() ? 0 : (lines.size() - 1) * lineStep + ascent + descent;
		}
	}

	/** The text as drawn at now: tokens filled in; the raw text if it does not parse (save refuses that). */
	static String resolved(ObjectConfig o, long now, long streamStart)
	{
		if(o.text == null)
		{
			return "";
		}
		try
		{
			return TextTokens.render(o.text, o.tokenContext(now, streamStart));
		}
		catch(IllegalArgumentException e)
		{
			return o.text;
		}
	}

	static boolean isBox(ObjectConfig o)
	{
		return Boolean.TRUE.equals(o.box) && o.w > 0 && o.h > 0;
	}

	static Layout layout(String text, Font font, float wrapWidth, double lineHeight)
	{
		List<String> lines = new ArrayList<>();
		for(String para: text.split("\n", -1))
		{
			if(wrapWidth <= 0)
			{
				lines.add(para);
				continue;
			}
			wrap(para, font, wrapWidth, lines);
		}
		float[] widths = new float[lines.size()];
		for(int i = 0; i < lines.size(); i++)
		{
			widths[i] = width(lines.get(i), font);
		}
		java.awt.font.LineMetrics lm = font.getLineMetrics("Hgjy", FRC);
		return new Layout(font, lines, widths, (float)(font.getSize2D() * lineHeight), lm.getAscent(), lm.getDescent());
	}

	static float width(String s, Font font)
	{
		return s.isEmpty() ? 0 : (float)font.getStringBounds(s, FRC).getWidth();
	}

	/** Greedy word wrap at width; a word wider than the line breaks between characters. */
	static void wrap(String para, Font font, float width, List<String> out)
	{
		if(para.isEmpty())
		{
			out.add("");
			return;
		}
		StringBuilder line = new StringBuilder();
		for(String word: para.split(" ", -1))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if(width(candidate, font) <= width)
			{
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if(line.length() > 0)
			{
				out.add(line.toString());
				line.setLength(0);
			}
			// the word alone, broken where it does not fit
			for(int i = 0; i < word.length(); )
			{
				int cp = word.codePointAt(i);
				String next = line.toString() + new String(Character.toChars(cp));
				if(line.length() > 0 && width(next, font) > width)
				{
					out.add(line.toString());
					line.setLength(0);
					continue;
				}
				line.appendCodePoint(cp);
				i += Character.charCount(cp);
			}
		}
		out.add(line.toString());
	}

	/** The layout at this scale: in a box, shrunk by autoFit until it fits, down to half the font size. */
	static Layout layoutFor(ObjectConfig o, String text, double scaled)
	{
		Font font = o.awt_font(scaled);
		if(font == null)
		{
			return null;
		}
		double lh = o.lineHeight == null ? DEFAULT_LINE_HEIGHT : o.lineHeight;
		if(!isBox(o))
		{
			return layout(text, font, 0, lh);
		}
		float pad = (float)(ObjectConfig.num(o.padding) * scaled);
		float innerW = (float)(o.w * scaled) - 2 * pad, innerH = (float)(o.h * scaled) - 2 * pad;
		Layout l = layout(text, font, innerW, lh);
		if(Boolean.TRUE.equals(o.autoFit))
		{
			float full = font.getSize2D();
			for(float size = full; (l.height() > innerH || l.maxWidth() > innerW) && size > full * 0.5f; )
			{
				size = Math.max(full * 0.5f, size * 0.95f);
				Font f = ObjectConfig.getFont(o.font, o.fontStyle, size);
				if(f == null)
				{
					break;
				}
				l = layout(text, f, innerW, lh);
			}
		}
		return l;
	}

	static void draw(ObjectConfig o, Graphics2D g, double scaled, long now, long streamStart)
	{
		String text = resolved(o, now, streamStart);
		Layout l = layoutFor(o, text, scaled);
		if(l == null)
		{
			// no fonts on this server (Fonts.unavailable): the text is left out, the other layers draw
			return;
		}
		boolean box = isBox(o);
		float pad = (float)(ObjectConfig.num(o.padding) * scaled);
		float radius = (float)(ObjectConfig.num(o.radius) * scaled);
		float x = o.getX(scaled), y = o.getY(scaled);
		float areaX, areaY, areaW, areaH, firstBaseline, innerW;
		if(box)
		{
			areaX = x;
			areaY = y;
			areaW = o.getW(scaled);
			areaH = o.getH(scaled);
			innerW = areaW - 2 * pad;
			firstBaseline = y + pad + l.ascent();
		}
		else
		{
			innerW = l.maxWidth();
			areaX = x - pad;
			areaY = y - l.ascent() - pad;
			areaW = innerW + 2 * pad;
			areaH = l.height() + 2 * pad;
			firstBaseline = y;
		}
		Shape oldClip = g.getClip();
		if(box)
		{
			g.clip(new Rectangle(Math.round(areaX), Math.round(areaY), Math.round(areaW), Math.round(areaH)));
		}
		if(o.background != null)
		{
			g.setColor(o.awt_color(o.background));
			g.fill(new RoundRectangle2D.Float(areaX, areaY, areaW, areaH, radius * 2, radius * 2));
		}
		g.setFont(l.font());
		float left = box ? x + pad : x;
		float outline = o.outlineWidth == null ? 0 : (float)(o.outlineWidth * scaled);
		for(int i = 0; i < l.lines().size(); i++)
		{
			String line = l.lines().get(i);
			if(line.isEmpty())
			{
				continue;
			}
			float lx = switch(o.align == null ? "left" : o.align)
			{
				case "center" -> left + (innerW - l.widths()[i]) / 2;
				case "right" -> left + innerW - l.widths()[i];
				default -> left;
			};
			float base = firstBaseline + i * l.lineStep();
			if(o.shadowColor != null)
			{
				g.setColor(o.awt_shadowColor());
				g.drawString(line, lx + o.getShadowOffset(scaled), base + o.getShadowOffset(scaled));
			}
			if(outline > 0 && o.outlineColor != null)
			{
				// the stroke is centered on the letters' edge; twice the width shows the width outside it
				Shape glyphs = new TextLayout(line, l.font(), g.getFontRenderContext()).getOutline(AffineTransform.getTranslateInstance(lx, base));
				g.setColor(o.awt_color(o.outlineColor));
				g.setStroke(new BasicStroke(outline * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(glyphs);
			}
			if(o.gradientColor != null)
			{
				g.setPaint(o.getGradientPaint(o.awt_color(), o.awt_gradientColor(), o.gradientDirection, Math.round(lx), Math.round(base - l.ascent()),
						Math.round(lx + l.widths()[i]), Math.round(base + l.descent())));
			}
			else
			{
				g.setColor(o.awt_color());
			}
			g.drawString(line, lx, base);
		}
		g.setClip(oldClip);
	}

	/**
	 * The canvas area the text can paint at this scale, before the layer's padding for antialiasing: the box itself,
	 * or without a box the full width over its lines and background (a token's width changes between draws, so the
	 * width is never measured). Null when no font can draw.
	 */
	static Rectangle bounds(ObjectConfig o, double scaled)
	{
		Font font = o.awt_font(scaled);
		if(font == null)
		{
			return null;
		}
		int extra = (int)Math.ceil((o.outlineWidth == null ? 0 : o.outlineWidth * scaled) + (o.shadowColor != null ? Math.abs(o.getShadowOffset(scaled)) : 0));
		if(isBox(o))
		{
			Rectangle r = new Rectangle(o.getX(scaled), o.getY(scaled), o.getW(scaled), o.getH(scaled));
			r.grow(extra, extra);
			return r;
		}
		int lines = o.text == null ? 1 : o.text.split("\n", -1).length;
		double lh = o.lineHeight == null ? DEFAULT_LINE_HEIGHT : o.lineHeight;
		java.awt.font.LineMetrics lm = font.getLineMetrics("Hgjy", FRC);
		float pad = (float)(ObjectConfig.num(o.padding) * scaled);
		int top = (int)Math.floor(o.getY(scaled) - lm.getAscent() - lm.getLeading() - pad - extra);
		int bottom = (int)Math.ceil(o.getY(scaled) + (lines - 1) * font.getSize2D() * lh + lm.getDescent() + pad + extra);
		return new Rectangle(0, top, ObjectConfig.ANY_WIDTH, bottom - top);
	}
}
