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
// Add, Overlay in a stage's menu (input or outputs), or + Overlay on a template switched on in place, then an overlay
async function openPicker(page, stage) {
  if (stage === 'form') { await page.locator('[data-ovpickopen="form"]').click(); return; }
  await page.locator('[data-rowfor="tpladd:' + stage + '"]').click();
  await page.getByRole('menuitem', { name: 'Overlay', exact: true }).click();
}
async function addFromMenu(page) { await openPicker(page, 'input'); }
async function addFrom(page, stage, name) {
  await openPicker(page, stage);
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
  await addFrom(page, 'outputs', 'LowerThird');
  const card = outputsSec(page).locator('[data-ov]');
  await expect(card.locator('[data-ovname]')).toHaveValue('LowerThird');
  await expect(card.locator('option[value="Scoreboard"]'), 'one overlay is carried once').toBeDisabled();
  await expect(card.locator('[data-ovout][value="720p"]')).toBeChecked();
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  const w = wse.templateWrites[0];
  expect(w.path).toBe('/vhosts/_defaultVHost_/templates/scoreboard-cpu/spec');
  expect(w.body).not.toHaveProperty('overlay');
  expect(w.body.outputs.some((o) => 'overlay' in o), 'outputs name no overlay; the list does').toBe(false);
  expect(w.body.overlays).toEqual([{ name: 'Scoreboard', encodes: [], slot: 1 }, { name: 'LowerThird', encodes: ['720p'], slot: 2 }]);
});

test('an overlay on every output is saved on the input, and the stacking order is the slot order', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await addFrom(page, 'input', 'LowerThird');
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(2);
  await expect(decode(page).locator('[data-ov]').nth(1)).toContainText('drawn on top here');
  // up: LowerThird now draws under Scoreboard, so it takes slot 1
  await decode(page).locator('[data-ovup]').click();
  await expect(decode(page).locator('[data-ovname]').first()).toHaveValue('LowerThird');
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  const b = wse.templateWrites[0].body;
  expect(b.overlays).toEqual([{ name: 'LowerThird', encodes: [], slot: 1 }, { name: 'Scoreboard', encodes: [], slot: 2 }]);
});

test('a template carries at most four overlays', async ({ page, studio, wse }) => {
  for (const n of ['Fourth', 'Fifth']) wse.overlays[n] = JSON.parse(JSON.stringify(wse.overlays.Scoreboard));
  await openTemplate(page, studio, 'scoreboard-cpu');
  for (const n of ['LowerThird', 'Fourth', 'Fifth']) await addFrom(page, 'input', n);
  await expect(decode(page).locator('[data-ovname]')).toHaveCount(4);
  await expect(decode(page)).toContainText('A template carries at most 4 overlays.');
  await page.locator('[data-rowfor="tpladd:outputs"]').click();
  await expect(page.getByRole('menuitem', { name: 'Overlay', exact: true })).toBeDisabled();
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
  await addFrom(page, 'input', 'LowerThird');
  await expect(save(page)).toBeEnabled();
  await save(page).click();
  await expect.poll(() => wse.templateWrites.length).toBe(1);
  expect(wse.templateWrites[0].path).toBe('/vhosts/_defaultVHost_/templates');
  expect(wse.templateWrites[0].body.overlays).toEqual([{ name: 'LowerThird', encodes: [], slot: 1 }]);
});

test('a template switched on in place saves its overlay list', async ({ page, studio, wse }) => {
  wse.templates.ladder = Object.assign({}, wse.templates['overlaystudio-cpu'], { managed: false,
    description: 'Ours, with overlays switched on in place.', overlays: [{ name: 'Scoreboard', encodes: ['360p'], slot: 1 }] });
  await openTemplate(page, studio, 'ladder');
  const rows = screen(page).locator('[data-io]');
  await expect(rows).toHaveCount(1);
  await expect(rows.first().locator('[data-io-enc][value="360p"]')).toBeChecked();
  await expect(rows.first().locator('[data-io-enc][value="720p"]')).not.toBeChecked();
  await addFrom(page, 'form', 'LowerThird');
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

test('a template picks from the saved overlays only, with thumbnails, one at a time', async ({ page, studio, wse }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await expect(decode(page).locator('[data-ov] .ly-thumb img'), 'the card shows its overlay').toHaveAttribute('src', /^data:image\/png;base64,/);
  wse.thumbFail = 1;
  await page.locator('[data-rowfor="tpladd:input"]').click();
  // the Add menu: an overlay or a fixed image, and nothing that makes an overlay
  await expect(page.getByRole('menuitem')).toHaveText(['Overlay', 'Image (fixed)']);
  await page.getByRole('menuitem', { name: 'Overlay', exact: true }).click();
  const pick = page.locator('[data-ovpick]');
  await expect(pick.locator('[data-ovpname]')).toHaveCount(3);
  await expect(pick.locator('[data-ovpname="Mixed"]')).toContainText('text, date and time, shape, image (7 layers)');
  await expect(pick.locator('[data-ovpname="LowerThird"] img'), 'a 429 waits and tries again: LowerThird is asked for first').toHaveAttribute('src', /^data:image\/png;base64,/);
  expect(wse.thumbs.filter((n) => n === 'LowerThird').length).toBe(2);
  await expect(pick.locator('[data-ovpname="Scoreboard"]')).toBeDisabled();
  await expect(pick.locator('[data-ovpname="Scoreboard"]')).toContainText('Already in this template');
  await expect(pick.locator('[data-ovpname="LowerThird"]')).toContainText('web page (1 layer)');
  await expect(pick.locator('.pre'), 'no presets').toHaveCount(0);
  await expect(pick.getByRole('button', { name: /New/ }), 'no way to make an overlay here').toHaveCount(0);
  await pick.locator('[data-ovpname="LowerThird"]').click();
  await expect(pick).toHaveCount(0);
  await expect(decode(page).locator('[data-ovname]').nth(1)).toHaveValue('LowerThird');
  await expect(decode(page).locator('[data-ov]').nth(1).locator('.ly-thumb img')).toHaveAttribute('src', /^data:image\/png;base64,/);
});

test('with no overlay in the application, the picker says where overlays are made', async ({ page, studio, wse }) => {
  for (const n of Object.keys(wse.overlays)) delete wse.overlays[n];
  await openTemplate(page, studio, 'overlaystudio-cpu');
  await addFromMenu(page);
  await expect(page.locator('[data-ovpick]')).toContainText('No overlay in live yet. Overlays are made in the Overlays tab.');
  await expect(page.locator('[data-screen=template] [data-go="new-overlay:"]')).toHaveCount(0);
});

test('Image (fixed) in the Add menu opens the image library picker', async ({ page, studio }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Image (fixed)' }).click();
  await expect(screen(page).locator('.ly-picker input[name=ovs-img]')).toHaveCount(2);
});

test('an overlay with no layers says so in the picker', async ({ page, studio, wse }) => {
  wse.overlays.Blank = { objects: [] };
  await openTemplate(page, studio, 'scoreboard-cpu');
  await openPicker(page, 'input');
  await expect(page.locator('[data-ovpick] [data-ovpname="Blank"]')).toContainText('No layers yet');
  await expect(page.locator('[data-ovpick] [data-ovpname="Blank"]')).not.toContainText('0 layers');
});

// live2 has the module too, and holds its own copies of overlays
function secondApp(wse) {
  wse.apps[1].installed = true;
  wse.otherOverlays.live2 = { Scoreboard: JSON.parse(JSON.stringify(wse.overlays.Scoreboard)), LowerThird: { objects: [] } };
  wse.otherShowing.live2 = { LowerThird: ['studio1'] };
  wse.apps.push({ name: 'live3', installed: true, streamType: 'live', liveStreamTranscoder: '', templates: '', savedStreams: [] });
}

test('Copy to other applications marks each one by the overlay it holds, and copies to the missing ones', async ({ page, studio, wse }) => {
  secondApp(wse);
  twoOverlays(wse);
  await openTemplate(page, studio, 'scoreboard-cpu');
  await decode(page).locator('[data-ovcopy="Scoreboard"]').click();
  const panel = decode(page).locator('[data-copyp]');
  await expect(panel.locator('[data-copyrow="live2"]')).toContainText('Has it');
  await expect(panel.locator('[data-copyrow="live3"]')).toContainText('Missing');
  await expect(panel.locator('[data-copyapp="live2"]'), 'the same overlay is not copied again').toBeDisabled();
  await panel.locator('[data-copyapp="live3"]').check();
  await panel.getByRole('button', { name: 'Copy to 1 application' }).click();
  await expect.poll(() => wse.otherOverlays.live3 && Object.keys(wse.otherOverlays.live3)).toEqual(['Scoreboard']);
  expect(wse.otherOverlays.live3.Scoreboard).toEqual(wse.overlays.Scoreboard);
  await expect(panel.locator('[data-copyrow="live3"]')).toContainText('Has it');
});

test('copying over a different version asks first, and says when it is on air there', async ({ page, studio, wse }) => {
  secondApp(wse);
  twoOverlays(wse);
  await openTemplate(page, studio, 'scoreboard-cpu');
  await outputsSec(page).locator('[data-ovcopy="LowerThird"]').click();
  const panel = outputsSec(page).locator('[data-copyp]');
  const row = panel.locator('[data-copyrow="live2"]');
  await expect(row).toContainText('Differs');
  await expect(row).toContainText('On air there on studio1');
  await panel.locator('[data-copyapp="live2"]').check();
  await panel.getByRole('button', { name: 'Copy to 1 application' }).click();
  const dlg = page.getByRole('alertdialog');
  await expect(dlg).toContainText('Replace LowerThird in live2?');
  await expect(dlg).toContainText('live2 shows it on studio1');
  await dlg.getByRole('button', { name: 'Cancel' }).click();
  expect(wse.otherOverlays.live2.LowerThird).toEqual({ objects: [] });
  await panel.getByRole('button', { name: 'Copy to 1 application' }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Replace' }).click();
  await expect(row).toContainText('Has it');
  expect(wse.otherOverlays.live2.LowerThird).toEqual(wse.overlays.LowerThird);
});

test('with no other application holding the module, there is no Copy', async ({ page, studio }) => {
  await openTemplate(page, studio, 'scoreboard-cpu');
  await expect(decode(page).locator('[data-ovcopy]')).toHaveCount(0);
});

test('an image drawn by an overlay in any application is not deleted', async ({ page, studio, wse }) => {
  secondApp(wse);
  wse.otherOverlays.live2.Logo = { objects: [{ id: 'logo', type: 'image', imageFile: 'overlaystudio/logo.png', x: 0, y: 0, w: 100, h: 50 }] };
  await openTemplate(page, studio, 'scoreboard-cpu');
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Image (fixed)' }).click();
  await screen(page).locator('[data-imgdel="overlaystudio/logo.png"]').click();
  await expect(page.locator('.toast').filter({ hasText: 'overlaystudio/logo.png is drawn by overlay live/Mixed, overlay live2/Logo; choose another image there first.' })).toBeVisible();
  expect(wse.posts.filter((x) => x.path.endsWith('/images'))).toHaveLength(0);
});
