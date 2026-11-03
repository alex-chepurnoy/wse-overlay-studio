// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Adding layers (plan 4.1, PR 7b): the Add bar, the Add menus under the list, in the empty inspector and on the
// overlay row; a click places a layer at its usual size, a drag draws it, Enter centers it, Escape cancels; new ids;
// the id is edited in the panel header and a saved layer's rename goes with the save.
'use strict';
const { test, expect } = require('./harness');

// the Add layer menu under the layer list, the one way to add (plan 4.1, as changed 2026-11-03)
async function addLayer(page, kind) {
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: kind, exact: true }).click();
}

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-rowfor="add:list"]')).toBeVisible();
}
const frame = (page) => page.locator('[data-frame]');
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });

/** Source pixels of the 1920 x 1080 frame to a page point. */
async function at(page, x, y) {
  const f = await frame(page).boundingBox();
  return { x: f.x + x * f.width / 1920, y: f.y + y * f.height / 1080 };
}

async function saved(page, wse, name) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/' + name)).pop().body;
}

test('a click places text at the click point at its usual size', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Scoreboard');
  await addLayer(page, 'Text');
  await expect(page.locator('[data-placehint]')).toContainText('Click to place the text');
  await expect(frame(page)).toBeFocused();
  const p = await at(page, 400, 300);
  await page.mouse.click(p.x, p.y);
  await expect(page.locator('[data-placehint]')).toHaveCount(0);
  await expect(page.locator('.pg[data-id="text-1"] text')).toHaveText('Text');
  await expect(page.locator('[data-layers] .prow[aria-selected=true] .lname')).toHaveText('Text');
  const o = (await saved(page, wse, 'Scoreboard')).objects.find((x) => x.id === 'text-1');
  expect(o).toMatchObject({ type: 'string', text: 'Text', font: 'Fira Sans', fontSize: 48 });
  expect(Math.abs(o.x - 400)).toBeLessThanOrEqual(2);
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(page.locator('[data-insp] [data-f=y]'), 'its top is at the click').toHaveValue(/^(29[89]|30[0-2])$/);
  expect(o.y - 300, 'saved as the first baseline, an ascent below the click').toBeGreaterThan(30);
  expect(o.y - 300).toBeLessThan(48);
  expect(o.zOrder, 'on top of the page').toBe(2);
});

test('on a smaller stream a new layer starts at a size scaled to it, where it was clicked', async ({ page, studio, wse }) => {
  // layers are in the stream's own pixels; the usual sizes are for 1080p, so a 512 x 288 stream gets them at 288/1080
  const orig = wse.streamStatus;
  wse.streamStatus = function (s) { const r = orig.call(this, s); if (r.transcoder.state === 'running') Object.assign(r.transcoder, { decoderWidth: 512, decoderHeight: 288 }); return r; };
  await openOverlay(page, studio, 'Scoreboard');
  const f = await frame(page).boundingBox(), pt = (x, y) => ({ x: f.x + x * f.width / 512, y: f.y + y * f.height / 288 });
  await addLayer(page, 'Text');
  let p = pt(100, 60); await page.mouse.click(p.x, p.y);
  await addLayer(page, 'Image');
  await expect(page.locator('[data-placehint]')).toBeVisible();
  p = pt(400, 100); await page.mouse.click(p.x, p.y);
  const objs = (await saved(page, wse, 'Scoreboard')).objects, text = objs.find((x) => x.id === 'text-1'), img = objs.find((x) => x.id === 'image-1');
  expect(text.fontSize).toBe(13);
  expect(text.y - 60, 'its top at the click, its baseline an ascent below').toBeGreaterThan(8);
  expect(text.y - 60).toBeLessThan(14);
  expect(Math.abs(img.w - 85)).toBeLessThanOrEqual(2);
  expect(Math.abs(img.h - 48)).toBeLessThanOrEqual(2);
  expect(Math.abs(img.x - 400), 'the click point, not pushed left to fit a 1080p size').toBeLessThanOrEqual(2);
});

test('a drag draws a shape at the dragged size; Escape cancels placing', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Scoreboard');
  const shape = page.locator('[data-rowfor="add:list"]');
  await shape.click();
  await page.getByRole('menuitem', { name: 'Rectangle', exact: true }).click();
  const a = await at(page, 200, 500), b = await at(page, 700, 700);
  await page.mouse.move(a.x, a.y); await page.mouse.down();
  await page.mouse.move(b.x, b.y, { steps: 6 }); await page.mouse.up();
  await expect(page.locator('.pg[data-id="shape-1"] rect')).toHaveCount(2);

  await shape.click();
  await page.getByRole('menuitem', { name: 'Ellipse' }).click();
  await expect(page.locator('[data-placehint]')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-placehint]')).toHaveCount(0);
  await expect(page.locator('.pg[data-id="shape-2"]'), 'nothing placed').toHaveCount(0);

  const o = (await saved(page, wse, 'Scoreboard')).objects.find((x) => x.id === 'shape-1');
  expect(o.type).toBe('rectangle');
  for (const [k, v] of Object.entries({ x: 200, y: 500, w: 500, h: 200 })) expect(Math.abs(o[k] - v), k).toBeLessThanOrEqual(3);
});

test('Enter places in the center; Date and time holds a clock token', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Scoreboard');
  await addLayer(page, 'Date and time');
  await page.keyboard.press('Enter');
  await expect(page.locator('.pg[data-id="clock-1"] text')).toHaveText(/^HH:mm:ss #\d+$/);
  const o = (await saved(page, wse, 'Scoreboard')).objects.find((x) => x.id === 'clock-1');
  expect(o).toMatchObject({ type: 'string', font: 'Fira Mono' });
  expect(Math.abs(o.x - (1920 - 300) / 2)).toBeLessThanOrEqual(2);
});

test('the list menu adds lines, arrows and triangles by their points', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Scoreboard');
  for (const [kind, x] of [['Arrow', 200], ['Triangle', 900]]) {
    await page.locator('[data-rowfor="add:list"]').click();
    await page.getByRole('menuitem', { name: kind }).click();
    const p = await at(page, x, 600);
    await page.mouse.click(p.x, p.y);
  }
  const objs = (await saved(page, wse, 'Scoreboard')).objects;
  const arrow = objs.find((o) => o.id === 'shape-1'), tri = objs.find((o) => o.id === 'shape-2');
  expect(arrow).toMatchObject({ type: 'line', arrowHead: 'end' });
  expect(arrow.x2 - arrow.x).toBe(300);
  expect(arrow.w, 'a line has no box').toBeUndefined();
  expect(tri.type).toBe('triangle');
  expect([tri.y, tri.y3]).toEqual([tri.y2 + 170, tri.y2 + 170]);
});

test('Web page opens its address form; the empty inspector has the same menu', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Scoreboard');
  await addLayer(page, 'Web page');
  await expect(page.locator('#ovs-purl')).toBeFocused();
  await page.locator('[data-addcancel]').click();
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  // the first Escape closes the inspector drawer the form opened, the second deselects
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-insp]')).toContainText('No layer selected');
  await page.locator('[data-insp] [data-rowfor="add:insp"]').click();
  await page.getByRole('menuitem', { name: 'Image' }).click();
  await expect(page.locator('[data-placehint]')).toContainText('Click to place the image');
});

test('the overlay row menu adds any kind, opening the overlay first', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Scoreboard');
  const row = page.locator('[data-list] [data-rowfor="overlay:LowerThird"]');
  await row.click();
  await expect(page.getByRole('menuitem')).toHaveText(['Add web page', 'Add text', 'Add date and time', 'Add shape...', 'Add image', 'Add from a preset...', 'Duplicate...', 'Delete overlay...']);
  await page.getByRole('menuitem', { name: 'Add text' }).click();
  await expect(page.locator('h2[data-title]:visible')).toContainText('LowerThird');
  await expect(page.locator('[data-placehint]')).toContainText('Click to place the text');

  await page.keyboard.press('Escape');
  await page.locator('[data-list] [data-rowfor="overlay:LowerThird"]').click();
  await page.getByRole('menuitem', { name: 'Add shape...' }).click();
  await expect(page.getByRole('menuitem', { name: 'Rounded rectangle' }), 'the Shape menu opens').toBeVisible();
});

test('the id is edited in the panel header, checked, and a saved layer is renamed on save', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await page.locator('[data-layers] .prow').filter({ hasText: 'HOME 2 - 1' }).click();
  const id = page.getByRole('textbox', { name: 'Layer id' });
  await expect(id).toHaveValue('score');
  for (const bad of ['a..b', 'has space', 'clock']) {
    await id.fill(bad); await id.press('Enter');
    await expect(page.locator('[data-liderr]')).not.toBeEmpty();
    await expect(page.locator('.pg[data-id="score"]'), bad + ' is refused').toHaveCount(1);
  }
  await id.fill('home'); await id.press('Enter');
  await expect(page.locator('.pg[data-id="home"]')).toHaveCount(1);
  await expect(page.getByRole('textbox', { name: 'Layer id' })).toHaveValue('home');
  const body = await saved(page, wse, 'Mixed');
  expect(body.renamed).toEqual({ score: 'home' });
  expect(body.objects.map((o) => o.id)).toContain('home');
});

test('a layer added and renamed before its first save carries no rename', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Scoreboard');
  await addLayer(page, 'Text');
  await page.keyboard.press('Enter');
  const id = page.getByRole('textbox', { name: 'Layer id' });
  await id.fill('title'); await id.press('Enter');
  const body = await saved(page, wse, 'Scoreboard');
  expect(body.renamed).toBeUndefined();
  expect(body.objects.map((o) => o.id)).toEqual(['score', 'title']);
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('no Add bar or Add menu is offered', async ({ page, studio }) => {
    await studio.open();
    await page.locator('[data-tab=overlays]').click();
    await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
    await expect(page.locator('.pg[data-id="score"]')).toBeVisible();
    // write controls are hidden for a read-only user, as every other one is ([data-write])
    await expect(page.locator('[data-rowfor="add:list"]')).toBeHidden();
    await expect(page.getByRole('textbox', { name: 'Layer id' })).toHaveCount(0);
  });
});

test('the readout under the canvas is not a live region; the placing hint is said once', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Scoreboard');
  // a ticking clock rewrites the readout every second; a screen reader should not hear it each time
  await expect(page.locator('[data-coords]')).toHaveAttribute('aria-live', 'off');
  await addLayer(page, 'Text');
  await expect(page.locator('[data-say]')).toHaveText(/Click to place the text/);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-say]')).toHaveText('');
});
