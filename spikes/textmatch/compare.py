"""Spike S4: compares the browser's render of s4.html with the Java renders, row by row.
Usage: python compare.py <chrome.png> <chrome-widths.txt> <java-prefix> <java-widths.txt>"""
import json
import sys
from PIL import Image, ImageChops

chrome_png, chrome_w, java_prefix, java_w = sys.argv[1:5]
rows = [l.rstrip('\n').split('\t') for l in open('samples.tsv', encoding='utf-8') if l.strip()]
cw = json.loads(open(chrome_w, encoding='utf-8').read().strip())['widths']
jw = {}
for line in open(java_w, encoding='utf-8'):
	k, v = line.strip().split('=', 1)
	jw[k] = json.loads(v)
c = Image.open(chrome_png).convert('L')
print('%-3s %-42s %8s | %-29s | %-29s' % ('#', 'sample', 'browser', 'Java fractional off', 'Java fractional on'))
print('%-3s %-42s %8s | %7s %6s %8s %4s | %7s %6s %8s %4s' % ('', '', 'width', 'width', 'diff%', 'px>64%', 'edge', 'width', 'diff%', 'px>64%', 'edge'))
summary = {}
for mode in ('fm_off', 'fm_on'):
	summary[mode] = []
java_img = {m: Image.open('%s-%s.png' % (java_prefix, m.replace('_', '-'))).convert('L') for m in ('fm_off', 'fm_on')}
for i, r in enumerate(rows):
	box = (0, i * 140, 1100, i * 140 + 140)
	cr = c.crop(box)
	cells = []
	for mode in ('fm_off', 'fm_on'):
		jr = java_img[mode].crop(box)
		d = ImageChops.difference(cr, jr)
		data = d.tobytes()
		ink = sum(1 for p in cr.tobytes() if p > 64) or 1
		big = sum(1 for p in data if p > 64)
		wdiff = (jw[mode][i] - cw[i]) / cw[i] * 100
		cb = cr.point(lambda v: 255 if v > 64 else 0).getbbox()
		jb = jr.point(lambda v: 255 if v > 64 else 0).getbbox()
		edge = max(abs(x - y) for x, y in zip(cb, jb)) if cb and jb else -1
		summary[mode].append((abs(wdiff), big / ink * 100, edge))
		cells.append('%7.1f %+5.1f%% %7.1f%% %4d' % (jw[mode][i], wdiff, big / ink * 100, edge))
	label = ('%s %s %s' % (r[1], 'bold' if r[2] == '700' else ('italic' if r[3] == 'italic' else ''), r[4])).strip()
	print('%-3d %-42s %8.1f | %s | %s' % (i + 1, label[:42], cw[i], cells[0], cells[1]))
for mode in ('fm_off', 'fm_on'):
	w = sorted(x[0] for x in summary[mode])
	p = sorted(x[1] for x in summary[mode])
	e = sorted(x[2] for x in summary[mode])
	print('%s: width diff median %.1f%% max %.1f%%; differing ink pixels (>64 levels, as %% of the browser\'s ink) median %.1f%% max %.1f%%; ink box edges apart median %d px max %d px'
		% (mode, w[len(w) // 2], w[-1], p[len(p) // 2], p[-1], e[len(e) // 2], e[-1]))
