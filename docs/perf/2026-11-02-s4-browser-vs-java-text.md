# Browser text against Java text (2.0 plan, spike S4)

Question: how close is the page's drawing of a text layer (SVG text in the browser, with the bundled fonts loaded by
`@font-face`) to what the module draws with Java2D? The answer sets the tolerance of the "matches the server's
drawing" check (plan 4.4).

Method: 20 samples (`spikes/textmatch/samples.tsv`): Fira Sans, Fira Sans Condensed, Fira Mono and PT Serif, regular,
bold and italic, 18 to 96 px, Latin, accents, Cyrillic, Greek, digits and punctuation. Each drawn white on black at the
same baseline, by:

- the browser: `s4.html`, one SVG `<text>` per sample, rendered headless at device scale 1 by desktop Chrome on Windows
  and by the capture service's chrome-headless-shell 156.0.8078.80 on the AWS Ubuntu server; widths from
  `getComputedTextLength()`;
- Java: `JavaText.java` with WSE's own Java 21.0.3, antialiasing on, once with fractional metrics off (what the module
  does today) and once with them on.

Measured per sample: advance width difference, and how far apart the edges of the drawn text's box are (pixels above
level 64). Pixel-by-pixel difference is reported too, but it is not a usable measure: antialiasing differs on every
glyph edge even where the text looks the same, so it runs 20 to 120% of the ink.

## Results

| Pairing | Width difference, median / max | Box edges apart, median / max |
|---|---|---|
| Chrome Windows vs Java, fractional metrics on | 0.4% / 1.2% | 2 / 5 px |
| Chrome Windows vs Java, fractional metrics off | 0.9% / 2.8% | 4 / 7 px |
| Chrome Linux vs Java, fractional metrics on | 0.4% / 2.9% | 1 / 7 px |
| Chrome Linux vs Java, fractional metrics off | 0.5% / 1.0% | 2 / 3 px |
| Chrome Windows vs Chrome Linux (widths) | up to 2.8% | |

Java's drawing is pixel-identical on Windows and Linux (as in S3). The browser differs by system: headless Chrome on
Linux lays text out on whole pixels, Chrome on Windows does not. macOS was not tested.

One page bug found: SVG text collapses runs of spaces ("AWAY   45:12" drew as "AWAY 45:12", 7% narrower), so the page
must set `white-space: pre` on text layers. With it, that sample matches like the others.

Raw output: `spikes/textmatch/result-windows.txt`, `spikes/textmatch/result-linux-browser.txt`, images beside them.

## What it means for 2.0

- **The module draws text with fractional metrics on**: closest to Chrome on Windows (the usual browser for the
  Manager), and still within 3% and 7 px of Chrome on Linux.
- **The render check compares boxes, not pixels**: "Matches the server's drawing" when, for every text layer, the
  advance width is within 3% and each edge of the drawn box within 8 px at source scale; shapes and images, drawn the
  same way on both sides, within 2 px. A wrong font, a missing glyph or a wrong size moves text well past that.
- **The page sets `white-space: pre`** on every text element.
