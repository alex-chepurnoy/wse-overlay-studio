// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The panel for each kind (plan 4.2, PR 8a): Text, Shape, Effects, Image and Timing tabs beside Place, every field
// through the registry, colors and times checked as the module checks them, the image library picker shared with
// Templates, and read-only values.
'use strict';
const { test, expect } = require('./harness');

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-frame] .pg').first()).toBeVisible();
}
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
const row = (page, text) => page.locator('[data-layers] .prow').filter({ hasText: text });
const tabs = (page) => page.locator('[data-insp] [role=tab]');
// by the field's registry name (its label also holds the help tip's button)
const field = (page, name) => page.locator(name === 'kind' ? '#ovs-kind' : '#ovs-f-' + name);
async function saved(page, wse, name) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/' + name)).pop().body.objects;
}
const obj = (objs, id) => objs.find((o) => o.id === id);

test('each kind has its own tabs', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  for (const [name, want] of [['HOME 2 - 1', ['Text', 'Place', 'Timing']], ['Rounded rectangle', ['Shape', 'Effects', 'Place', 'Timing']],
    ['Arrow', ['Shape', 'Effects', 'Place', 'Timing']], ['logo.png', ['Image', 'Place', 'Timing']]]) {
    await row(page, name).click();
    await expect(tabs(page), name).toHaveText(want);
    await expect(tabs(page).first(), name + ' opens on its own tab').toHaveAttribute('aria-selected', 'true');
  }
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await row(page, 'score').click();
  await expect(tabs(page)).toHaveText(['Page', 'Place', 'Key, crop', 'Timing']);
});

test('the Text tab sets content, font and style, and a bad color is refused', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'HOME 2 - 1').click();
  const text = field(page, 'text');
  await text.fill('HOME 3 - 1');
  await expect(page.locator('.pg[data-id="score"] text').last()).toHaveText('HOME 3 - 1');
  await expect(row(page, 'HOME 3 - 1'), 'the list names it by its text').toHaveCount(1);
  const font = field(page, 'font');
  await expect(font.locator('optgroup[label="Bundled with Overlay Studio"] option')).toHaveText(['Fira Sans', 'Fira Sans Condensed', 'Fira Mono', 'PT Serif']);
  await font.selectOption('Fira Sans Condensed');
  await field(page, 'fontSize').fill('60');
  await field(page, 'fontStyle').selectOption({ label: 'Italic' });
  const color = field(page, 'textColor');
  await color.fill('reddish');
  await expect(page.locator('[data-err-for=textColor]')).toContainText('rgb(r,g,b)');
  await color.fill('rgb(255,200,0)');
  await expect(page.locator('[data-err-for=textColor]')).toBeEmpty();
  const o = obj(await saved(page, wse, 'Mixed'), 'score');
  expect(o).toMatchObject({ text: 'HOME 3 - 1', font: 'Fira Sans Condensed', fontSize: 60, fontStyle: 'italic', color: 'rgb(255,200,0)' });
});

test('Box and outline is closed until opened; a box keeps the text where it was', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'HOME 2 - 1').click();
  const adv = page.locator('[data-insp] details[data-boxadv]');
  await expect(adv).not.toHaveAttribute('open', '');
  await adv.locator('summary').click();
  await field(page, 'outlineColor').fill('black');
  await field(page, 'outlineWidth').fill('3');
  const before = await page.locator('.pg[data-id="score"]').boundingBox();
  await field(page, 'box').check();
  await expect(page.locator('[data-insp] details[data-boxadv]'), 'stays open').toHaveAttribute('open', '');
  const after = await page.locator('.pg[data-id="score"]').boundingBox();
  expect(Math.abs(after.x - before.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(after.y - before.y)).toBeLessThanOrEqual(2);
  await field(page, 'align').selectOption({ label: 'Center' });
  const o = obj(await saved(page, wse, 'Mixed'), 'score');
  expect(o).toMatchObject({ box: true, align: 'center', outlineColor: 'black', outlineWidth: 3, padding: 10 });
  // the box starts at the text's extent: the padding before the old start, and the font's ascent above its baseline
  expect(o.x).toBe(90);
  expect(o.w).toBeGreaterThan(100);
  expect(o.y).toBeLessThan(200 - 40);
});

test('the Shape and Effects tabs style a shape; corner rounding and the arrow head follow the kind', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'Rounded rectangle').click();
  const kind = field(page, 'kind');
  await expect(kind).toHaveValue('rounded');
  await field(page, 'fillColor').fill('rgb(0,128,0)');
  await field(page, 'stroke').fill('6');
  await field(page, 'dash').selectOption({ label: 'Dashed' });
  await field(page, 'gradientColor').fill('white');
  await field(page, 'gradientDirection').selectOption({ label: 'Right' });
  await field(page, 'arcWidth').fill('0');
  await expect(kind, 'square corners make it a rectangle').toHaveValue('rectangle');
  await field(page, 'arcWidth').fill('40');
  await expect(kind).toHaveValue('rounded');
  await page.getByRole('tab', { name: 'Effects' }).click();
  await field(page, 'shadowColor').fill('rgb(0,0,0,128)');
  await field(page, 'shadowOffset').fill('6');
  await field(page, 'opacity').fill('80');
  await row(page, 'Arrow').click();
  await page.getByRole('tab', { name: 'Shape' }).click();
  await expect(field(page, 'fillColor'), 'a line has no fill').toHaveCount(0);
  await field(page, 'arrowHead').selectOption({ label: 'None' });
  await expect(field(page, 'kind')).toHaveValue('line');
  const objs = await saved(page, wse, 'Mixed'), b = obj(objs, 'bar');
  expect(b).toMatchObject({ fillColor: 'rgb(0,128,0)', stroke: 6, dash: 'dashed', gradientColor: 'white', gradientDirection: 'right',
    arcWidth: 40, arcHeight: 40, shadowColor: 'rgb(0,0,0,128)', shadowOffset: 6, opacity: 80 });
  expect(obj(objs, 'arrow').arrowHead).toBe('none');
});

test('every color has a picker; a picked color keeps the transparency the field had', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'Rounded rectangle').click();
  const fill = field(page, 'fillColor'), pick = page.locator('[data-cpick=fillColor]');
  await expect(pick).toHaveValue('#1e5ac8');
  await pick.fill('#00ff80');
  await expect(fill).toHaveValue('rgb(0,255,128)');
  // a color typed in moves the picker
  await fill.fill('rgb(255,0,0,128)');
  await expect(pick).toHaveValue('#ff0000');
  await pick.fill('#0000ff');
  await expect(fill, 'the alpha stays').toHaveValue('rgb(0,0,255,128)');
  await page.getByRole('tab', { name: 'Effects' }).click();
  await expect(page.locator('[data-cpick=shadowColor]'), 'a field with no color starts black').toHaveValue('#000000');
  await row(page, 'HOME 2 - 1').click();
  await expect(page.locator('[data-cpick=textColor]')).toHaveValue('#ffffff');
  expect(obj(await saved(page, wse, 'Mixed'), 'bar').fillColor).toBe('rgb(0,0,255,128)');
});

test('Place turns a layer and shows only the fields its kind has', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'HOME 2 - 1').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(field(page, 'w'), 'a free text sizes by its font').toHaveCount(0);
  await field(page, 'rotation').fill('-12.5');
  await expect(page.locator('.pg[data-id="score"]')).toHaveAttribute('aria-label', /turned -12.5 degrees/);
  await row(page, 'logo.png').click();
  await expect(field(page, 'w')).toBeVisible();
  const lock = field(page, 'lockAspect');
  await expect(lock).toBeChecked();
  await lock.uncheck();
  const objs = await saved(page, wse, 'Mixed');
  expect(obj(objs, 'score').rotation).toBe(-12.5);
  expect(obj(objs, 'logo').lockAspect).toBe(false);
});

test('the Image tab chooses from the library and will not delete a file in use', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'logo.png').click();
  await expect(page.locator('[data-imgname]')).toContainText('overlaystudio/logo.png');
  await expect(page.locator('[data-imgname]')).toContainText('200 x 100');
  await page.getByRole('button', { name: 'Choose another image' }).click();
  const pick = page.locator('[data-insp] .ly-picker');
  await expect(pick.locator('input[name=ovs-img]')).toHaveCount(2);
  await expect(pick.locator('input[value="overlaystudio/logo.png"]')).toBeChecked();
  await pick.getByRole('button', { name: 'Delete overlaystudio/logo.png from the server' }).click();
  await expect(page.locator('.toast, [role=status]').filter({ hasText: 'Layer logo in this overlay uses' }).first()).toBeVisible();
  expect(wse.posts.filter((p) => p.path.endsWith('/images')), 'nothing deleted').toHaveLength(0);
  await pick.locator('input[value="house.png"]').check();
  await pick.getByRole('button', { name: 'Use image' }).click();
  await expect(pick).toHaveCount(0);
  await expect(row(page, 'house.png')).toHaveCount(1);
  await page.keyboard.press('Control+z');
  await expect(row(page, 'logo.png'), 'undo takes the choice back').toHaveCount(1);
  await page.keyboard.press('Control+Shift+z');
  expect(obj(await saved(page, wse, 'Mixed'), 'logo').imageFile).toBe('house.png');
});

test('Timing takes a stream time or a date with its offset, and fades and blinks', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'Ellipse').click();
  await page.getByRole('tab', { name: 'Timing' }).click();
  await field(page, 'showFrom').fill('stream+30');
  const hide = field(page, 'hideAt');
  await hide.fill('tomorrow');
  await expect(page.locator('[data-err-for=hideAt]')).toContainText('stream+N');
  await hide.fill('2027-01-01T18:00:00-05:00');
  await expect(page.locator('[data-err-for=hideAt]')).toBeEmpty();
  await field(page, 'fadeIn').fill('1.5');
  await field(page, 'fadeOut').fill('3');
  await expect(page.locator('[data-err-for=fadeOut]')).toContainText('From 0 to 2');
  await field(page, 'blinkSeconds').fill('1');
  const o = obj(await saved(page, wse, 'Mixed'), 'ring');
  expect(o).toMatchObject({ showFrom: 'stream+30', hideAt: '2027-01-01T18:00:00-05:00', fadeIn: 1.5, blinkSeconds: 1 });
  expect(o.fadeOut).toBeUndefined();
});

// the stream's decoded size as the module reports it, under transcoder (OverlayStudioModule.streamStatus)
function streamSize(wse, w, h) {
  const orig = wse.streamStatus;
  wse.streamStatus = function (s) { const r = orig.call(this, s); if (r.transcoder.state === 'running') Object.assign(r.transcoder, { decoderWidth: w, decoderHeight: h }); return r; };
}

test('the frame checks use the layer\'s drawn area, not its first point', async ({ page, studio, wse }) => {
  // the triangle's first point (1000, 700) is on this frame's bottom edge; the triangle itself is inside
  streamSize(wse, 1200, 700);
  await openOverlay(page, studio, 'Mixed');
  await expect(page.locator('[data-coords]')).toContainText('1200 x 700');
  await row(page, 'Triangle').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(page.locator('[data-outside]')).toBeHidden();
  await expect(page.locator('[data-fit]')).toHaveText('Past title safe');
  await row(page, 'HOME 2 - 1').click();
  await expect(page.locator('[data-fit]')).toHaveText('Inside title safe');
  await expect(page.locator('[data-coords]'), 'a text\'s size in whole pixels').toHaveText(/score at 100, \d+ · \d+ x \d+$/);
  await field(page, 'x').fill('1250');
  await expect(page.locator('[data-fit]')).toHaveText('Outside the frame');
  await expect(page.locator('[data-outside]')).toContainText('score is outside cam1 (1200 x 700): nothing of it shows');
});

test('a free text\'s Y is the top of its first line, and it keeps its top when its font grows', async ({ page, studio, wse }) => {
  // score is saved with its first baseline at y 200; the module still takes the baseline, the page shows the top
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'HOME 2 - 1').click();
  await page.getByRole('tab', { name: 'Place' }).click();
  const y = field(page, 'y'), top = Number(await y.inputValue());
  expect(top, 'the top, an ascent above the baseline').toBeLessThan(200);
  expect(top).toBeGreaterThan(200 - 64);
  const asc = 200 - top;
  await expect(page.locator('.pg[data-id="score"]'), 'the canvas label says the same').toHaveAttribute('aria-label', new RegExp('Y ' + top + '(,|$)'));
  await y.fill('100');
  let o = obj(await saved(page, wse, 'Mixed'), 'score');
  expect(Math.abs(o.y - (100 + asc)), 'saved as its baseline').toBeLessThanOrEqual(2);
  await page.getByRole('tab', { name: 'Text' }).click();
  await field(page, 'fontSize').fill(String(2 * o.fontSize));
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(field(page, 'y')).toHaveValue(/^(9[89]|10[0-2])$/);
  o = obj(await saved(page, wse, 'Mixed'), 'score');
  expect(o.y - 100, 'the baseline moved down by the bigger ascent').toBeGreaterThan(1.5 * asc);
});

test('a layer never saved shows no saved values; the empty panel offers to add one', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  await page.getByRole('toolbar', { name: 'Add a layer' }).getByRole('button', { name: 'Text' }).click();
  await page.keyboard.press('Enter');
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(page.locator('[data-insp] .hint').filter({ hasText: 'Saved:' })).toHaveCount(0);
  await row(page, 'HOME 2 - 1').click();
  await field(page, 'x').fill('120');
  // the saved value shows from the panel's next drawing on, as for every field
  await row(page, 'HOME 2 - 1').click();
  await expect(page.locator('[data-insp] .hint').filter({ hasText: 'Saved: 100' }), 'a saved layer still shows its saved value').toHaveCount(1);
  await page.keyboard.press('Escape');
  await page.locator('[data-frame]').focus();
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-insp]')).toContainText('Select a layer in the list or on the canvas, or add one.');
  await expect(page.locator('[data-insp]')).not.toContainText('clock and score');
});

test('Templates choose a transcoder image from the same picker', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-tab=templates]').click();
  await page.locator('[data-list]').getByText('scoreboard-cpu', { exact: true }).first().click();
  await page.locator('[data-screen=template] [data-addimg]').first().click();
  const pick = page.locator('[data-screen=template] .ly-picker');
  await expect(pick.locator('input[name=ovs-img]')).toHaveCount(2);
  await pick.locator('input[value="house.png"]').check();
  await pick.getByRole('button', { name: 'Add image' }).click();
  await expect(pick).toHaveCount(0);
  await expect(page.locator('[data-screen=template]')).toContainText('house.png');
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('the panel shows the values and changes nothing', async ({ page, studio }) => {
    await openOverlay(page, studio, 'Mixed');
    await row(page, 'Rounded rectangle').click();
    const insp = page.locator('[data-insp]');
    await expect(insp).toContainText('Line width');
    await expect(insp.locator('input, select, textarea')).toHaveCount(0);
    await row(page, 'logo.png').click();
    await expect(insp.getByRole('button', { name: /Choose/ })).toHaveCount(0);
    await expect(insp).toContainText('overlaystudio/logo.png');
  });
});
