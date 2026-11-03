// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// A template carries up to four overlays (plan 3.9, 4.5, PR 10a), each drawn on the input (Decode) or on chosen
// outputs (Encode). The builder saves slot 1 through the outputs marked overlay, as the module reads it, and slots 2
// to 4 in overlays; a template switched on in place saves its list through POST /templates/{t}/overlay.
'use strict';
const { test, expect } = require('./harness');

const screen = (page) => page.locator('[data-screen=template]');
const decode = (page) => screen(page).locator('.tb-box').first();
const outputsSec = (page) => screen(page).locator('.b-ovl');
const save = (page) => page.locator('[data-a=save]');

async function openTemplate(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=templates]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(screen(page)).toBeVisible();
}
// scoreboard-cpu with a second overlay on its 720p output only
function twoOverlays(wse) {
  Object.assign(wse.templates['scoreboard-cpu'], { overlays: [{ name: 'Scoreboard', encodes: [], slot: 1 }, { name: 'LowerThird', encodes: ['720p'], slot: 2 }] });
}

test('a master shows every overlay it carries, each where it draws', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await openTemplate(page, studio, 'scoreboard-cpu');
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(1);
  await expect(decode(page).locator('[data-ovname]')).toHaveValue('Scoreboard');
  await expect(outputsSec(page).locator('[data-ovname]')).toHaveValue('LowerThird');
  await expect(outputsSec(page).locator('[data-ovout][value="720p"]')).toBeChecked();
  await expect(outputsSec(page).locator('[data-ovout][value="360p"]')).not.toBeChecked();
  const sum = screen(page).locator('.b-sum');
  await expect(sum).toContainText('2 with Scoreboard');
  await expect(sum).toContainText('1 with LowerThird');
  await expect(screen(page).locator('.oc[data-i="0"] .oc-ov')).toContainText('LowerThird');
  await expect(screen(page).locator('.oc[data-i="1"] .oc-ov')).not.toContainText('LowerThird');
  await expect(save(page), 'reading a template back is not an edit').toHaveCount(0);
});

test('a second overlay on chosen outputs is saved in slot 2 with its outputs', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await outputsSec(page).locator('[data-addov]').click();
  const card = outputsSec(page).locator('[data-ov]');
  await expect(card.locator('[data-ovname]'), 'the first overlay the template does not carry yet').toHaveValue('LowerThird');
  await expect(card.locator('option[value="Scoreboard"]'), 'one overlay is carried once').toBeDisabled();
  await expect(card.locator('[data-ovout][value="720p"]')).toBeChecked();
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  const w = wse.templateWrites[0];
  expect(w.path).toBe('/vhosts/_defaultVHost_/templates/scoreboard-cpu/spec');
  expect(w.body.overlay).toBe('Scoreboard');
  expect(w.body.outputs.filter((o) => o.overlay).map((o) => o.name)).toEqual(['720p', '360p']);
  expect(w.body.overlays).toEqual([{ name: 'LowerThird', encodes: ['720p'], slot: 2 }]);
});

test('an overlay on every output is saved on the input, and the stacking order is the slot order', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await decode(page).locator('[data-addov]').click();
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(2);
  await expect(decode(page).locator('[data-ov]').nth(1)).toContainText('drawn on top here');
  // up: LowerThird now draws under Scoreboard, so it takes slot 1
  await decode(page).locator('[data-ovup]').click();
  await expect(decode(page).locator('[data-ovname]').first()).toHaveValue('LowerThird');
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  const b = wse.templateWrites[0].body;
  expect(b.overlay).toBe('LowerThird');
  expect(b.overlays).toEqual([{ name: 'Scoreboard', encodes: [], slot: 2 }]);
});

test('a template carries at most four overlays', async ({ page, studio, wse }) => {
  for (const n of ['Fourth', 'Fifth']) wse.overlays[n] = JSON.parse(JSON.stringify(wse.overlays.Scoreboard));
  await openTemplate(page, studio, 'scoreboard-cpu');
  const add = decode(page).locator('[data-addov]');
  for (let i = 0; i < 3; i++) await add.click();
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(4);
  await expect(add).toBeDisabled();
  await expect(add).toHaveAttribute('title', /At most 4 overlays/);
  await expect(outputsSec(page).locator('[data-addov]')).toBeDisabled();
});

test('moving an overlay to outputs starts it on the first output, and an empty pick is a problem', async ({ page, studio }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await decode(page).locator('[data-ovmove]').click();
  const card = outputsSec(page).locator('[data-ov]');
  await expect(card.locator('[data-ovname]')).toHaveValue('Scoreboard');
  await expect(card.locator('[data-ovout][value="720p"]')).toBeChecked();
  await card.locator('[data-ovout][value="720p"]').uncheck({ force: true });
  await expect(page.locator('.err').filter({ hasText: 'Overlay Scoreboard is on chosen outputs, and none is chosen.' })).toBeVisible();
  await expect(save(page)).toBeDisabled();
});

test('renaming or removing an output carries over to the overlays on it', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await openTemplate(page, studio, 'scoreboard-cpu');
  await screen(page).locator('.oc[data-i="0"] input[data-o=name]').fill('hd');
  await screen(page).locator('.oc[data-i="0"] input[data-o=name]').press('Tab');
  await expect(outputsSec(page).locator('[data-ovout][value="hd"]')).toBeChecked();
  await screen(page).locator('.oc[data-i="0"] [data-rm]').click();
  await expect(page.locator('.err').filter({ hasText: 'Overlay LowerThird is on chosen outputs, and none is chosen.' })).toBeVisible();
});

test('a new template starts with no overlay, and the overlay copy preset asks for one', async ({ page, studio, wse }) => {
  await studio.open();
  await page.locator('[data-tab=templates]').click();
  await page.evaluate(() => window.OverlayStudio.go('template:'));
  await expect(screen(page).locator('[data-ovname]')).toHaveCount(0);
  await screen(page).locator('#ovs-b-name').fill('fresh');
  await screen(page).getByRole('button', { name: 'Original + overlay copy' }).click();
  await expect(screen(page).locator('[data-presethint]')).toHaveText('Add an overlay for the overlay output.');
  await expect(page.locator('.err').filter({ hasText: 'Add an overlay for the overlay output' })).toBeVisible();
  await decode(page).locator('[data-addov]').click();
  await expect(save(page)).toBeEnabled();
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  expect(wse.templateWrites[0].path).toBe('/vhosts/_defaultVHost_/templates');
  expect(wse.templateWrites[0].body.overlay, 'the first overlay of the application').toBe('LowerThird');
  expect(wse.templateWrites[0].body.overlays).toEqual([]);
});

test('a template switched on in place saves its overlay list', async ({ page, studio, wse }) => {
  wse.templates.ladder = Object.assign({}, wse.templates['overlaystudio-cpu'], { managed: false,
    description: 'Ours, with overlays switched on in place.', overlay: 'Scoreboard', overlays: [{ name: 'Scoreboard', encodes: ['360p'], slot: 1 }] });
  await openTemplate(page, studio, 'ladder');
  const rows = screen(page).locator('[data-io]');
  await expect(rows).toHaveCount(1);
  await expect(rows.first().locator('[data-io-enc][value="360p"]')).toBeChecked();
  await expect(rows.first().locator('[data-io-enc][value="720p"]')).not.toBeChecked();
  await screen(page).locator('[data-io-add]').click();
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(1).locator('[data-io-ov]')).toHaveValue('LowerThird');
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  expect(wse.templateWrites[0].path).toBe('/vhosts/_defaultVHost_/templates/ladder/overlay');
  expect(wse.templateWrites[0].body).toEqual({ overlays: [{ name: 'Scoreboard', encodes: ['360p'], slot: 1 }, { name: 'LowerThird', encodes: [], slot: 2 }] });
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('the outputs table lists every overlay each output carries', async ({ page, studio, wse }) => {
    twoOverlays(wse);
    await openTemplate(page, studio, 'scoreboard-cpu');
    await expect(screen(page).locator('[data-carries="720p"]')).toContainText('Scoreboard, LowerThird');
    await expect(screen(page).locator('[data-carries="360p"]')).toContainText('Scoreboard');
    await expect(screen(page).locator('[data-carries="360p"]')).not.toContainText('LowerThird');
    await expect(screen(page).locator('[data-addov]')).toHaveCount(0);
  });
});

test('the save bar names an overlay moved to other outputs and a new stacking order', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await openTemplate(page, studio, 'scoreboard-cpu');
  await outputsSec(page).locator('[data-ovmove]').click();
  await expect(page.getByText('where LowerThird draws')).toBeVisible();
  await expect(page.getByText('overlay order')).toHaveCount(0);
  await decode(page).locator('[data-ovup]').click();
  await expect(page.getByText('where LowerThird draws, overlay order')).toBeVisible();
});
