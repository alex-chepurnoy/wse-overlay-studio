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
// the Add menu of a stage (input, outputs, or form for a template switched on in place), an item, then an overlay
async function addFrom(page, stage, item, name) {
  await page.locator('[data-rowfor="tpladd:' + stage + '"]').click();
  await page.getByRole('menuitem', { name: item, exact: true }).click();
  await page.locator('[data-ovpick] [data-ovpname="' + name + '"]').click();
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
  await addFrom(page, 'outputs', 'All overlays', 'LowerThird');
  const card = outputsSec(page).locator('[data-ov]');
  await expect(card.locator('[data-ovname]')).toHaveValue('LowerThird');
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
  await addFrom(page, 'input', 'All overlays', 'LowerThird');
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
  for (const n of ['LowerThird', 'Fourth', 'Fifth']) await addFrom(page, 'input', 'All overlays', n);
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(4);
  await expect(decode(page)).toContainText('A template carries at most 4 overlays.');
  await page.locator('[data-rowfor="tpladd:outputs"]').click();
  for (const item of ['Web page', 'Text', 'All overlays']) await expect(page.getByRole('menuitem', { name: item, exact: true })).toBeDisabled();
  await expect(page.getByRole('menuitem', { name: 'Image (fixed)' }), 'fixed images are counted apart').toBeEnabled();
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
  await addFrom(page, 'input', 'Web page', 'LowerThird');
  await expect(save(page)).toBeEnabled();
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  expect(wse.templateWrites[0].path).toBe('/vhosts/_defaultVHost_/templates');
  expect(wse.templateWrites[0].body.overlay).toBe('LowerThird');
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
  await addFrom(page, 'form', 'All overlays', 'LowerThird');
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

test('the Add menu opens a picker of the overlays holding that kind, with thumbnails, one at a time', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await expect(decode(page).locator('[data-ov] .ly-thumb img'), 'the card shows its overlay').toHaveAttribute('src', /^data:image\/png;base64,/);
  wse.thumbFail = 1;
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Text', exact: true }).click();
  const pick = page.locator('[data-ovpick]');
  await expect(pick.locator('[data-ovpname]'), 'only Mixed holds text').toHaveCount(1);
  await expect(pick.locator('[data-ovpname="Mixed"]')).toContainText('text, date and time, shape, image (7 layers)');
  await expect(pick.locator('[data-ovpname="Mixed"] img'), 'a 429 waits and tries again').toHaveAttribute('src', /^data:image\/png;base64,/);
  expect(wse.thumbs.filter((n) => n === 'Mixed').length).toBe(2);
  // the presets of that kind, then All, where an overlay the template carries cannot be picked again
  await expect(pick.getByRole('button', { name: /Lower third/ })).toBeVisible();
  await pick.locator('[data-ovkind=""]').click();
  await expect(pick.locator('[data-ovpname]')).toHaveCount(3);
  await expect(pick.locator('[data-ovpname="Scoreboard"]')).toBeDisabled();
  await expect(pick.locator('[data-ovpname="Scoreboard"]')).toContainText('Already in this template');
  await pick.locator('[data-ovkind="webPage"]').click();
  await expect(pick.getByRole('button', { name: /New web page overlay/ })).toBeVisible();
  await pick.locator('[data-ovpname="LowerThird"]').click();
  await expect(pick).toHaveCount(0);
  await expect(decode(page).locator('[data-ovname]').nth(1)).toHaveValue('LowerThird');
  await expect(decode(page).locator('[data-ov]').nth(1).locator('.ly-thumb img')).toHaveAttribute('src', /^data:image\/png;base64,/);
});

test('a preset opens New overlay, and the editor brings the new overlay back into the template', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await page.locator('[data-rowfor="tpladd:outputs"]').click();
  await page.getByRole('menuitem', { name: 'Text', exact: true }).click();
  await page.locator('[data-ovpick]').getByRole('button', { name: /Lower third/ }).click();
  const fresh = page.locator('[data-screen="new-overlay"]');
  await expect(fresh.locator('#ovs-nn'), 'a name suggested from the preset').toHaveValue('Lower-third');
  await expect(fresh.locator('[data-addingnote]')).toContainText('For template scoreboard-cpu');
  await fresh.getByRole('button', { name: 'Create overlay' }).click();
  const banner = page.locator('[data-banner="adding"]');
  await expect(banner).toContainText('Adding to template scoreboard-cpu.');
  await banner.getByRole('button', { name: 'Add to template and go back' }).click();
  await expect(screen(page)).toBeVisible();
  const card = outputsSec(page).locator('[data-ov]');
  await expect(card.locator('[data-ovname]')).toHaveValue('Lower-third');
  await expect(card.locator('[data-ovout][value="720p"]')).toBeChecked();
  await expect(save(page), 'the template is left unsaved, for the user to Save').toBeEnabled();
  expect(wse.templateWrites).toHaveLength(0);
  await expect(banner).toHaveCount(0);
});

test('leaving the editor another way adds nothing, and Cancel on New overlay goes back to the template', async ({ page, studio }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Shape', exact: true }).click();
  await page.locator('[data-ovpick]').getByRole('button', { name: 'Circle' }).click();
  await page.locator('[data-screen="new-overlay"]').getByRole('button', { name: 'Cancel' }).click();
  await expect(screen(page)).toBeVisible();
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(1);
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Shape', exact: true }).click();
  await page.locator('[data-ovpick]').getByRole('button', { name: 'Circle' }).click();
  await page.locator('[data-screen="new-overlay"]').getByRole('button', { name: 'Create overlay' }).click();
  await expect(page.locator('[data-banner="adding"]')).toBeVisible();
  await page.locator('[data-tab=templates]').click();
  await page.locator('[data-list]').getByText('scoreboard-cpu', { exact: true }).first().click();
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(1);
  await expect(page.locator('[data-banner="adding"]')).toHaveCount(0);
});

test('Image (fixed) in the Add menu opens the image library picker', async ({ page, studio }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Image (fixed)' }).click();
  await expect(screen(page).locator('.ly-picker input[name=ovs-img]')).toHaveCount(2);
});
