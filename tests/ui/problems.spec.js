// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Problems the page shows (plan 4.2a, PR 8d): a font not on this server and characters a font cannot draw (from
// GET /fonts/check), an image file that is gone, a save's warnings, text cut at its box's edge, layers outside the
// stream's frame with Fit to this stream, and the 64-layer limit.
'use strict';
const { test, expect } = require('./harness');

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-frame] .pg').first()).toBeVisible();
}
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
const rowOf = (page, id) => page.locator('[data-layers] .prow[data-id="' + id + '"]');
async function saved(page, wse, name) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/' + name)).pop().body.objects;
}
function streamSize(wse, w, h) {
  const orig = wse.streamStatus;
  wse.streamStatus = function (s) { const r = orig.call(this, s); if (r.transcoder.state === 'running') Object.assign(r.transcoder, { decoderWidth: w, decoderHeight: h }); return r; };
}
const layer = (wse, overlay, id) => wse.overlays[overlay].objects.find((o) => o.id === id);

test('a font not on this server shows on the row and the font field, and the save warns about it', async ({ page, studio, wse }) => {
  layer(wse, 'Mixed', 'score').font = 'Helvetica Neue';
  await openOverlay(page, studio, 'Mixed');
  await expect(rowOf(page, 'score').locator('[data-rowprob]')).toHaveText('Font');
  await rowOf(page, 'score').click();
  await expect(page.locator('[data-prob=font]')).toHaveText('Drawn as Dialog on this server: Helvetica Neue is not installed here.');
  // a save of the overlay carries the module's warning, shown on the row until the next save
  await page.locator('#ovs-f-text').fill('HOME 3 - 1');
  await saved(page, wse, 'Mixed');
  await expect(rowOf(page, 'score').locator('[data-rowprob]')).toHaveText(['Font', 'Warning']);
  await expect(page.locator('[data-prob=font]').last()).toContainText("Font 'Helvetica Neue' is not installed on this server");
});

test('characters the font cannot draw are named under the text', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await rowOf(page, 'score').click();
  await page.locator('#ovs-f-text').fill('東京 2 - 1');
  await expect(page.locator('[data-prob=text]')).toHaveText('These characters will not draw in Fira Sans: 京 東');
  await expect(rowOf(page, 'score').locator('[data-rowprob]')).toHaveText('Characters');
  // PT Serif draws Cyrillic
  await page.locator('#ovs-f-text').fill('Москва 2 - 1');
  await page.locator('#ovs-f-font').selectOption('PT Serif');
  await expect(page.locator('[data-prob=text]')).toHaveCount(0);
  expect(wse.requests.some((r) => r.path.startsWith('/rest/v1/server/plugin/overlaystudio/fonts/check?font=PT%20Serif'))).toBe(true);
});

test('an image file that is gone says File missing on its row and in the Image tab', async ({ page, studio, wse }) => {
  layer(wse, 'Mixed', 'logo').imageFile = 'overlaystudio/gone.png';
  wse.goneImages = ['overlaystudio/gone.png'];
  await openOverlay(page, studio, 'Mixed');
  await expect(rowOf(page, 'logo').locator('[data-rowprob]')).toHaveText('File missing');
  await rowOf(page, 'logo').click();
  await expect(page.locator('[data-prob=imageFile]')).toContainText('File missing: overlaystudio/gone.png. The layer draws nothing');
});

test('text that does not fit its box says it is cut off', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  await rowOf(page, 'score').click();
  await page.locator('details[data-boxadv] summary').click();
  await page.locator('#ovs-f-box').check();
  await page.getByRole('tab', { name: 'Place' }).click();
  await page.locator('#ovs-f-w').fill('60');
  await page.getByRole('tab', { name: 'Text' }).click();
  await expect(page.locator('[data-prob=text]')).toContainText('Text is cut off at the box\'s edge');
  await page.locator('#ovs-f-autoFit').check();
  await expect(page.locator('[data-prob=text]')).toContainText('even at half its size');
});

test('layers outside a smaller stream\'s frame say so, and Fit to this stream scales every layer to it', async ({ page, studio, wse }) => {
  streamSize(wse, 1280, 720);
  const barW = layer(wse, 'Mixed', 'bar').w;
  await openOverlay(page, studio, 'Mixed');
  const out = page.locator('[data-layers] [data-rowprob]').filter({ hasText: 'Outside the frame' });
  await expect(out.first()).toBeVisible();
  const before = await out.count();
  expect(before).toBeGreaterThan(0);
  await page.locator('[data-layers] [data-fitstream]').first().click();
  await expect(out, 'every layer fits once scaled').toHaveCount(0);
  const objs = await saved(page, wse, 'Mixed');
  const score = objs.find((o) => o.id === 'score'), bar1 = objs.find((o) => o.id === 'bar');
  // 1280 / 1920 across, 720 / 1080 down; sizes by the height
  expect(score.x).toBe(Math.round(100 * 1280 / 1920));
  expect(score.y).toBe(Math.round(200 * 720 / 1080));
  expect(bar1.w).toBe(Math.round(barW * 1280 / 1920));
  expect(objs.find((o) => o.id === 'score').fontSize).toBe(Math.round(48 * 720 / 1080));
});

test('an overlay at 64 layers adds no more, and says why', async ({ page, studio, wse }) => {
  const objs = wse.overlays.Mixed.objects;
  for (let i = objs.length; i < 64; i++) objs.push({ id: 'pad-' + i, type: 'rectangle', x: 10, y: 10, w: 20, h: 20, zOrder: 0 });
  await openOverlay(page, studio, 'Mixed');
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'Text', exact: true }).click();
  await expect(page.locator('.toast').last()).toContainText('An overlay holds at most 64 layers');
  await expect(page.locator('[data-placehint]')).toHaveCount(0);
});
