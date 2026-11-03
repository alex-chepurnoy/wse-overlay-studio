// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The server check (plan 4.4, PR 9): 600 ms after the last edit the server draws the draft at one frozen moment, and
// where each layer draws there is compared with the canvas, text by its widest line and box, shapes and images by 2
// px. The fake measures no text, so a text is compared only where a test gives its box.
'use strict';
const { test, expect } = require('./harness');

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-frame] .pg').first()).toBeVisible();
}
const line = (page) => page.locator('[data-check]');
const rowOf = (page, id) => page.locator('[data-layers] .prow[data-id="' + id + '"]');

test('the draft is checked against the server\'s drawing after an edit, at one moment for both', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await expect(line(page)).toHaveText('Matches the server\'s drawing.');
  const r = wse.renders[wse.renders.length - 1];
  expect(r.width).toBe(640);
  expect(r.now, 'a frozen moment').toBeGreaterThan(0);
  expect(r.streamStart, 'stream time from when the overlay was opened, as the canvas counts it').toBeLessThanOrEqual(r.now);
  expect(r.overlay.objects.map((o) => o.id)).toContain('score');
  // a shape the server draws 10 px to the right
  wse.renderShift.bar = { x: 10 };
  await rowOf(page, 'bar').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await page.locator('#ovs-f-y').fill('161');
  await expect(line(page)).toHaveText('Differs from the server\'s drawing: bar starts 10 px further right on the server.');
  await expect(line(page)).toHaveClass(/warn/);
  await expect(page.locator('[data-say]'), 'said once through the polite region').toHaveText(/Differs from the server's drawing/);
});

test('a text is compared by its widest line and its box', async ({ page, studio, wse }) => {
  // the server measures the score's line far wider than the canvas does
  wse.textBoxes.score = { x: 100, y: 150, w: 900, h: 60, advance: 900 };
  await openOverlay(page, studio, 'Mixed');
  await expect(line(page)).toContainText(/score is \d+ px wider/);
  await expect(line(page)).toContainText('on the server');
});

test('a run of edits makes one check, after the last', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await expect(line(page)).toHaveText('Matches the server\'s drawing.');
  const before = wse.renders.length;
  await rowOf(page, 'bar').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  for (const y of ['161', '162', '163', '164']) await page.locator('#ovs-f-y').fill(y);
  await expect(line(page)).toHaveText('Matches the server\'s drawing.');
  await page.waitForTimeout(800);
  expect(wse.renders.length - before).toBe(1);
});

test('a refused check says the server check is unavailable, and runs again on the next edit', async ({ page, studio, wse }) => {
  wse.renderFail = 1;
  await openOverlay(page, studio, 'Mixed');
  await expect(line(page)).toHaveText('Server check unavailable: too many checks a second. It runs again on the next edit.');
  await expect(page.locator('[data-checkshow]')).toBeHidden();
  await rowOf(page, 'bar').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await page.locator('#ovs-f-y').fill('161');
  await expect(line(page)).toHaveText('Matches the server\'s drawing.');
});

test('the server\'s drawing can be laid over the canvas', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  await expect(line(page)).toHaveText('Matches the server\'s drawing.');
  const show = page.locator('[data-checkshow]');
  await show.click();
  await expect(show).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-frame] img[data-serverimg]')).toHaveAttribute('src', /^data:image\/png;base64,/);
  await show.click();
  await expect(page.locator('[data-frame] img[data-serverimg]')).toHaveCount(0);
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('a read-only user gets no check, and is told why', async ({ page, studio, wse }) => {
    await openOverlay(page, studio, 'Mixed');
    await expect(line(page)).toHaveText('Server check needs write access.');
    expect(wse.renders).toHaveLength(0);
  });
});

test('text is measured and drawn without kerning, as the server draws it', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  const w = await page.evaluate(async () => {
    await document.fonts.load('48px "Fira Sans"');
    const U = window.OverlayStudio, c = document.createElement('canvas').getContext('2d');
    c.font = '48px "Fira Sans"';
    c.fontKerning = 'normal'; const kerned = c.measureText('AVATAR Today').width;
    c.fontKerning = 'none'; const plain = c.measureText('AVATAR Today').width;
    const l = U.draw.text({ type: 'string', x: 0, y: 100, font: 'Fira Sans', fontStyle: 'plain', fontSize: 48 }, 'AVATAR Today');
    return { kerned, plain, page: l.maxWidth };
  });
  expect(w.plain - w.kerned, 'this text has kerning pairs').toBeGreaterThan(3);
  expect(w.page).toBeCloseTo(w.plain, 1);
  await expect(page.locator('.pg[data-id="score"] text').first()).toHaveAttribute('style', /font-kerning:\s*none/);
});
