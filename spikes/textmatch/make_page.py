"""Spike S4: builds s4.html from samples.tsv. Each sample is one SVG <text>, white on black, baseline at
y = row * 140 + 100, x = 20, the way the 2.0 page will draw a text layer. After the fonts load, the page writes each
sample's measured width into <pre id="out"> for --dump-dom."""
import html
import json
import os

here = os.path.dirname(os.path.abspath(__file__))
rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(here, 'samples.tsv'), encoding='utf-8') if l.strip()]
faces = []
seen = set()
for f, fam, w, st, _, _ in rows:
	if f not in seen:
		seen.add(f)
		faces.append("@font-face{font-family:'%s';src:url('fonts/%s') format('truetype');font-weight:%s;font-style:%s}" % (fam, f, w, st))
texts = []
for i, (f, fam, w, st, size, text) in enumerate(rows):
	texts.append('<text x="20" y="%d" fill="#fff" style="font-family:\'%s\';font-weight:%s;font-style:%s;font-size:%spx">%s</text>'
		% (i * 140 + 100, fam, w, st, size, html.escape(text)))
page = '''<!doctype html><html><head><meta charset="utf-8"><style>%s
html,body{margin:0;background:#000}svg{display:block}text{white-space:pre}</style></head><body>
<svg id="s" width="1100" height="%d" xmlns="http://www.w3.org/2000/svg"><rect width="1100" height="%d" fill="#000"/>%s</svg>
<pre id="out" style="color:#000">pending</pre>
<script>
document.fonts.ready.then(function(){
  var t=document.querySelectorAll('#s text'), w=[];
  for(var i=0;i<t.length;i++) w.push(t[i].getComputedTextLength());
  document.getElementById('out').textContent=JSON.stringify({fontsLoaded:document.fonts.size, widths:w});
});
</script></body></html>''' % ('\n'.join(faces), len(rows) * 140, len(rows) * 140, '\n'.join(texts))
open(os.path.join(here, 's4.html'), 'w', encoding='utf-8', newline='\n').write(page)
print('rows', len(rows), 'faces', len(faces))
