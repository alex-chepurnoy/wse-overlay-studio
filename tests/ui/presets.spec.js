// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Presets (plan 4.3, PR 8c): the editor's "From a preset..." and New overlay's "A preset" start, each a single layer
// landing at its set place on the frame (the background stream's size in the editor, 1920 x 1080 from New overlay);
// date and time presets with samples; text styles on the Text tab and nowhere in the preset lists; and the prompt
// before another start replaces an unsaved New overlay.
'use strict';
const { test, expect } = require('./harness');

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-frame] .pg').first()).toBeVisible();
}
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
async function saved(page, wse, name) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/' + name)).pop().body.objects;
}
async function fromPreset(page, name) {
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'From a preset...' }).click();
  await page.locator('[data-insp] [data-preset]').filter({ hasText: name }).first().click();
}
// the stream's decoded size, as the module reports it
function streamSize(wse, w, h) {
  const orig = wse.streamStatus;
  wse.streamStatus = function (s) { const r = orig.call(this, s); if (r.transcoder.state === 'running') Object.assign(r.transcoder, { decoderWidth: w, decoderHeight: h }); return r; };
}

test('From a preset lists every preset by group, with samples for the dates, and no text styles', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Scoreboard');
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'From a preset...' }).click();
  const insp = page.locator('[data-insp]');
  await expect(insp.locator('.pre-g .sec-h')).toHaveText(['Shapes', 'Badges and bars', 'Corner bug', 'Date and time']);
  await expect(insp.locator('[data-preset]')).toHaveCount(8 + 5 + 1 + 12);
  await expect(insp.locator('[data-preset="date:0"] [data-presample]'), 'the server\'s sample').toHaveText(/^HH:mm:ss #\d+$/);
  const names = await insp.locator('.pre-n').evaluateAll((xs) => xs.map((x) => x.firstChild.textContent.trim()));
  for (const style of ['Title', 'Lower third name', 'Lower third role', 'Caption', 'Ticker']) expect(names, 'text styles are not presets').not.toContain(style);
  await insp.getByRole('button', { name: 'Cancel' }).click();
  await expect(insp.locator('[data-preset]')).toHaveCount(0);
});

test('a preset lands at its place on the background stream\'s frame, as one layer, selected', async ({ page, studio, wse }) => {
  streamSize(wse, 1280, 720);
  await openOverlay(page, studio, 'Scoreboard');
  await fromPreset(page, 'Lower third');
  await expect(page.locator('[data-layers] .prow[aria-selected=true]')).toContainText('lower-third-1');
  await fromPreset(page, 'Countdown d\'d\' HH:mm:ss');
  await expect(page.locator('[data-itab=format]'), 'a date or time opens on its Format tab').toHaveAttribute('aria-selected', 'true');
  const objs = await saved(page, wse, 'Scoreboard');
  // (0.05, 0.75, 0.50, 0.14) of 1280 x 720
  expect(objs.find((o) => o.id === 'lower-third-1')).toMatchObject({ type: 'string', box: true, text: 'Name\nRole', x: 64, y: 540, w: 640, h: 101 });
  // a right-aligned box at (0.65, 0.05, 0.30, 0.07), in the font clocks use
  expect(objs.find((o) => o.id === 'clock-1')).toMatchObject({ text: '{countdown:d\'d\' HH:mm:ss}', box: true, align: 'right', font: 'Fira Mono', x: 832, y: 36, w: 384, h: 50 });
});

test('New overlay starts from a preset on a 1920 x 1080 frame, with a name suggested', async ({ page, studio, wse }) => {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByRole('button', { name: 'New' }).click();
  await page.locator('[data-start="preset"]').click();
  await page.getByRole('button', { name: 'Create overlay' }).click();
  await expect(page.locator('[data-perr]')).toHaveText('Choose a preset, or start Empty.');
  await page.locator('[data-preset="bug"]').click();
  await expect(page.locator('#ovs-nn')).toHaveValue('Corner-bug');
  await page.locator('[data-preset="live-blink"]').click();
  await expect(page.locator('#ovs-nn'), 'a suggested name follows the preset').toHaveValue('Live-badge-blinking');
  await page.getByRole('button', { name: 'Create overlay' }).click();
  await expect(page.locator('h2[data-title]:visible')).toContainText('Live-badge-blinking');
  const body = wse.posts.filter((p) => p.path.endsWith('/overlays/Live-badge-blinking')).pop().body;
  expect(body.objects).toHaveLength(1);
  // (0.04, 0.05, 0.10, 0.06) of 1920 x 1080
  expect(body.objects[0]).toMatchObject({ id: 'live-1', type: 'string', text: 'LIVE', box: true, x: 77, y: 54, w: 192, h: 65, background: 'rgb(200,0,0)', blinkSeconds: 1 });
});

test('another start asks before replacing a New overlay that was started', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByRole('button', { name: 'New' }).click();
  await page.locator('#ovs-nn').fill('Halfway');
  await page.locator('[data-rowfor="overlay:Scoreboard"]').click();
  await page.getByRole('menuitem', { name: 'Duplicate...' }).click();
  const dlg = page.getByRole('alertdialog', { name: 'Replace the new overlay you started?' });
  await expect(dlg).toContainText('Halfway');
  await dlg.getByRole('button', { name: 'Cancel' }).click();
  await expect(page.locator('#ovs-nn')).toHaveValue('Halfway');
  await page.locator('[data-rowfor="overlay:Scoreboard"]').click();
  await page.getByRole('menuitem', { name: 'Duplicate...' }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Replace it' }).click();
  await expect(page.locator('#ovs-nn')).toHaveValue('Scoreboard-copy');
});

test('a text style sets the font, size, weight, color and box of the selected text, keeping its top', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await page.locator('[data-layers] .prow[data-id="score"]').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  const top = await page.locator('#ovs-f-y').inputValue();
  await page.getByRole('tab', { name: 'Text' }).click();
  await page.getByRole('group', { name: 'Text styles' }).getByRole('button', { name: 'Lower third role' }).click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(page.locator('#ovs-f-y'), 'a free text keeps its top').toHaveValue(top);
  const o = (await saved(page, wse, 'Mixed')).find((x) => x.id === 'score');
  expect(o).toMatchObject({ font: 'Fira Sans Condensed', fontStyle: 'plain', fontSize: 32, color: 'rgb(220,220,220)', background: 'rgb(0,0,0,180)', padding: 10 });
});

test('a date or time preset suggests what it is as the name, and its toast names the preset', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByRole('button', { name: 'New' }).click();
  await page.locator('[data-start="preset"]').click();
  const name = page.locator('#ovs-nn');
  for (const [id, want] of [['date:0', 'Clock'], ['date:3', 'Date'], ['date:9', 'Countdown'], ['date:11', 'Stream-time']]) {
    await page.locator('[data-preset="' + id + '"]').click();
    await expect(name).toHaveValue(want);
  }
  await page.getByRole('button', { name: 'Cancel' }).click();
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'From a preset...' }).click();
  await page.locator('[data-insp] [data-preset="date:1"]').click();
  await expect(page.locator('.toast').last()).toContainText('Added the date and time h:mm:ss a preset as clock-1.');
});

test('the presets sit in columns in New overlay and in one column in the panel', async ({ page, studio }) => {
  await page.setViewportSize({ width: 1400, height: 900 });
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByRole('button', { name: 'New' }).click();
  await page.locator('[data-start="preset"]').click();
  const cols = (sel) => page.locator(sel).first().evaluate((g) => getComputedStyle(g).gridTemplateColumns.split(' ').length);
  expect(await cols('[data-screen="new-overlay"] .pre-items')).toBeGreaterThan(1);
  await page.getByRole('button', { name: 'Cancel' }).click();
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'From a preset...' }).click();
  expect(await cols('[data-insp] .pre-items')).toBe(1);
});

test('stream time in New overlay\'s samples counts from opening it', async ({ page, studio }) => {
  await page.clock.install({ time: Date.parse('2027-03-14T10:00:00Z') });
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await page.clock.fastForward(600000);
  await page.locator('[data-list]').getByRole('button', { name: 'New' }).click();
  await page.locator('[data-start="preset"]').click();
  await expect(page.locator('[data-preset="date:11"] [data-presample]')).toHaveText(/^H:mm:ss #[0-2]$/);
});
