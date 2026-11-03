// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Editing on the canvas (plan 4.1, PR 7c): the canvas as one Tab stop with the selected layer its active descendant,
// Page Up and Page Down, Enter and F2, text edited in place, Alt+click through layers, a hit margin for thin lines,
// the turn handle, line and triangle point handles, guides and Ctrl, the list menu's Duplicate, Bring to front,
// Send to back and Hide on canvas, a shape's kind within its geometry, undo, read-only and Help.
'use strict';
const { test, expect } = require('./harness');

async function openMixed(page, studio) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText('Mixed', { exact: true }).first().click();
  await expect(page.locator('.pg[data-id="score"] .lyr-svg')).toBeVisible();
}
const frame = (page) => page.locator('[data-frame]');
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
const row = (page, text) => page.locator('[data-layers] .prow').filter({ hasText: text });
const selectedRow = (page) => page.locator('[data-layers] .prow[aria-selected=true] .lname');

/** Source pixels of the 1920 x 1080 frame to a page point. */
async function at(page, x, y) {
  const f = await frame(page).boundingBox();
  return { x: f.x + x * f.width / 1920, y: f.y + y * f.height / 1080 };
}
async function center(locator) { const b = await locator.boundingBox(); return { x: b.x + b.width / 2, y: b.y + b.height / 2 }; }
async function drag(page, from, to, opts) {
  await page.mouse.move(from.x, from.y); await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 8 });
  if (opts && opts.before) await opts.before();
  await page.mouse.up();
}
async function saved(page, wse) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/Mixed')).pop().body.objects;
}
const obj = (objs, id) => objs.find((o) => o.id === id);

test('Tab into the canvas selects the front layer; Page Up and Page Down walk the layers', async ({ page, studio }) => {
  await openMixed(page, studio);
  await page.getByRole('toolbar', { name: 'Add a layer' }).getByRole('button', { name: 'Image' }).focus();
  await page.keyboard.press('Tab');
  const f = frame(page);
  await expect(f).toBeFocused();
  await expect(f).toHaveAttribute('role', 'application');
  await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-logo');
  await expect(selectedRow(page)).toHaveText('logo.png');
  await page.keyboard.press('PageDown');
  await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-score');
  await page.keyboard.press('PageDown');
  await expect(selectedRow(page)).toHaveText(/^HH:mm:ss #\d+$/);
  await page.keyboard.press('PageUp');
  await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-score');
  await expect(f).toBeFocused();
  // Escape leaves no active layer; the next arrow chooses the front one without moving it
  await page.keyboard.press('Escape');
  await expect(f).not.toHaveAttribute('aria-activedescendant', /./);
  await page.keyboard.press('ArrowRight');
  await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-logo');
  await expect(bar(page)).toBeHidden();
});

test('Enter on a shape goes to its first field; Enter or F2 on text edits it in place', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  await row(page, 'Rounded rectangle').click();
  await frame(page).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('textbox', { name: 'Layer id' })).toBeFocused();

  await row(page, 'HOME 2 - 1').click();
  await frame(page).focus();
  await page.keyboard.press('Enter');
  const ed = page.locator('[data-tedit]');
  await expect(ed).toBeFocused();
  await expect(ed).toHaveValue('HOME 2 - 1');
  await page.keyboard.type('AWAY 3 - 0');
  await expect(page.locator('.pg[data-id="score"] text').last(), 'the canvas draws as you type').toHaveText('AWAY 3 - 0');
  await page.keyboard.press('Enter');
  await expect(ed).toHaveCount(0);
  await expect(frame(page)).toBeFocused();

  await row(page, /HH:mm:ss #\d+/).click();
  await frame(page).focus();
  await page.keyboard.press('F2');
  await expect(ed).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(ed).toHaveCount(0);
  await expect(selectedRow(page), 'Escape ends the edit and keeps the layer').toHaveText(/^HH:mm:ss #\d+$/);
  expect(obj(await saved(page, wse), 'score').text).toBe('AWAY 3 - 0');
});

test('a double-click edits text in place; Shift+Enter adds a line', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  await page.locator('.pg[data-id="score"]').dblclick();
  const ed = page.locator('[data-tedit]');
  await expect(ed).toBeFocused();
  await page.keyboard.press('Control+End');
  await page.keyboard.press('Shift+Enter');
  await page.keyboard.type('LIVE');
  await page.keyboard.press('Enter');
  await expect(ed).toHaveCount(0);
  expect(obj(await saved(page, wse), 'score').text).toBe('HOME 2 - 1\nLIVE');
});

test('Alt+click selects the next layer under the pointer; a thin line is hit near it', async ({ page, studio }) => {
  await openMixed(page, studio);
  // a rectangle placed over the bar
  await page.getByRole('toolbar', { name: 'Add a layer' }).getByRole('button', { name: 'Add a shape' }).click();
  await page.getByRole('menuitem', { name: 'Rectangle', exact: true }).click();
  let p = await at(page, 650, 170);
  await page.mouse.click(p.x, p.y);
  await expect(selectedRow(page)).toHaveText('Rectangle');
  // mouse.click takes no modifiers: Alt is held on the keyboard
  p = await at(page, 700, 200);
  await page.keyboard.down('Alt');
  await page.mouse.click(p.x, p.y);
  await expect(selectedRow(page)).toHaveText('Rounded rectangle');
  await page.mouse.click(p.x, p.y);
  await expect(selectedRow(page), 'round again to the front').toHaveText('Rectangle');
  await page.keyboard.up('Alt');

  // a line: a press a few pixels off it still takes it, one well away from everything deselects
  await page.getByRole('toolbar', { name: 'Add a layer' }).getByRole('button', { name: 'Add a shape' }).click();
  await page.getByRole('menuitem', { name: 'Line', exact: true }).click();
  await drag(page, await at(page, 300, 950), await at(page, 900, 950));
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-layers] .prow[aria-selected=true]')).toHaveCount(0);
  const f = await frame(page).boundingBox(), off = 4 * 1920 / f.width;
  p = await at(page, 600, 950 + 2 + off);
  await page.mouse.click(p.x, p.y);
  await expect(selectedRow(page), 'within the 6 px margin').toHaveText('Line');
  p = await at(page, 600, 1040);
  await page.mouse.click(p.x, p.y);
  await expect(page.locator('[data-layers] .prow[aria-selected=true]'), 'a press on nothing deselects').toHaveCount(0);
});

test('the turn handle turns a layer, Shift in 15 degree steps, and undo takes it back', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  await row(page, 'Rounded rectangle').click();
  const handle = page.locator('.pg[data-id="bar"] .h.rot');
  await expect(handle).toBeVisible();
  // the bar's center is 750, 200: straight right of it is a quarter turn
  await drag(page, await center(handle), await at(page, 950, 200));
  await expect(page.locator('.pg[data-id="bar"]')).toHaveAttribute('aria-label', /turned 90 degrees/);
  await page.keyboard.down('Shift');
  await drag(page, await center(handle), await at(page, 950, 260));
  await page.keyboard.up('Shift');
  await expect(page.locator('.pg[data-id="bar"]')).toHaveAttribute('aria-label', /turned 105 degrees/);
  await page.keyboard.press('Control+z');
  await expect(page.locator('.pg[data-id="bar"]')).toHaveAttribute('aria-label', /turned 90 degrees/);
  expect(obj(await saved(page, wse), 'bar').rotation).toBe(90);
});

test('a line moves by its ends and a triangle by its corners', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  // Ctrl: the points go exactly where they are dragged, not to a guide
  await page.keyboard.down('Control');
  await row(page, 'Arrow').click();
  await expect(page.locator('.pg[data-id="arrow"] .h.pt')).toHaveCount(2);
  await expect(page.locator('.pg[data-id="arrow"] .h:not(.rot):not(.pt)'), 'no corner grips on a line').toHaveCount(0);
  await drag(page, await center(page.locator('.pg[data-id="arrow"] [data-g="p2"]')), await at(page, 1500, 250));
  await row(page, 'Triangle').click();
  await expect(page.locator('.pg[data-id="tri"] .h.pt')).toHaveCount(3);
  await drag(page, await center(page.locator('.pg[data-id="tri"] [data-g="p2"]')), await at(page, 1150, 470));
  await page.keyboard.up('Control');
  const objs = await saved(page, wse), arrow = obj(objs, 'arrow'), tri = obj(objs, 'tri');
  expect([arrow.x, arrow.y], 'the other end stays').toEqual([1000, 500]);
  expect(Math.abs(arrow.x2 - 1500)).toBeLessThanOrEqual(3);
  expect(Math.abs(arrow.y2 - 250)).toBeLessThanOrEqual(3);
  expect([tri.x, tri.y, tri.x3, tri.y3]).toEqual([1000, 700, 1200, 700]);
  expect(Math.abs(tri.x2 - 1150)).toBeLessThanOrEqual(3);
  expect(Math.abs(tri.y2 - 470)).toBeLessThanOrEqual(3);
});

test('a drag snaps to the frame edge with a guide; Ctrl places it freely', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  const box = page.locator('.pg[data-id="bar"]');
  await row(page, 'Rounded rectangle').click();
  // 595 px left puts the bar's left edge 5 px from the frame's
  const a = await at(page, 750, 200), b = await at(page, 155, 200);
  await drag(page, a, b, { before: () => expect(page.locator('[data-guide="x0"]')).toHaveCount(1) });
  await expect(page.locator('[data-guide]'), 'guides go with the drag').toHaveCount(0);
  expect(obj(await saved(page, wse), 'bar').x).toBe(0);

  await page.keyboard.down('Control');
  await drag(page, await center(box), await at(page, 160, 200), { before: () => expect(page.locator('[data-guide]')).toHaveCount(0) });
  await page.keyboard.up('Control');
  const x = obj(await saved(page, wse), 'bar').x;
  expect(x, 'not snapped: 590 px left of 600').toBeGreaterThan(6);
  expect(x).toBeLessThan(14);
});

test('the list menu duplicates, brings to front, sends to back and hides on the canvas only', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  const menu = async (id, item) => { await page.locator('[data-rowfor="page:' + id + '"]').click(); await page.getByRole('menuitem', { name: item }).click(); };
  await menu('ring', 'Duplicate');
  await expect(selectedRow(page)).toHaveText('Ellipse');
  await expect(page.locator('.pg[data-id="shape-1"]')).toHaveCount(1);
  await menu('tri', 'Bring to front');
  await menu('logo', 'Send to back');
  await menu('score', 'Hide on canvas');
  await expect(page.locator('.pg[data-id="score"]')).toHaveCount(0);
  await expect(row(page, 'HOME 2 - 1').locator('[data-hiddenmark]')).toBeVisible();
  const objs = await saved(page, wse);
  const copy = obj(objs, 'shape-1'), z = objs.map((o) => o.zOrder);
  expect(copy).toMatchObject({ type: 'ellipse', x: 620, y: 320, w: 200, h: 120, dash: 'dotted' });
  expect(obj(objs, 'tri').zOrder).toBe(Math.max(...z));
  expect(obj(objs, 'logo').zOrder).toBe(Math.min(...z));
  expect(obj(objs, 'score'), 'hidden here, still saved').toBeTruthy();
  expect(JSON.stringify(objs)).not.toContain('hidden');
  await menu('score', 'Show on canvas');
  await expect(page.locator('.pg[data-id="score"]')).toHaveCount(1);
});

test('a shape changes kind within its geometry', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  const kind = page.getByLabel('Shape', { exact: true });
  await row(page, 'Ellipse').click();
  await expect(kind.locator('option')).toHaveText(['Rectangle', 'Rounded rectangle', 'Square', 'Ellipse', 'Circle']);
  await kind.selectOption('circle');
  await row(page, 'Rounded rectangle').click();
  await kind.selectOption('ellipse');
  await row(page, 'Arrow').click();
  await expect(kind.locator('option')).toHaveText(['Line', 'Arrow']);
  await kind.selectOption('line');
  await row(page, 'Triangle').click();
  await expect(kind, 'a triangle has no other kind').toHaveCount(0);
  const objs = await saved(page, wse);
  expect(obj(objs, 'ring')).toMatchObject({ type: 'ellipse', w: 120, h: 120 });
  expect(obj(objs, 'bar').type).toBe('ellipse');
  expect(obj(objs, 'bar').arcWidth).toBeUndefined();
  expect(obj(objs, 'arrow').arrowHead).toBeUndefined();
  expect([obj(objs, 'arrow').x2, obj(objs, 'arrow').y2], 'the ends stay').toEqual([1300, 420]);
});

test('Help lists the canvas shortcuts', async ({ page, studio }) => {
  await openMixed(page, studio);
  await page.keyboard.press('?');
  const help = page.getByRole('alertdialog', { name: 'How Overlay Studio works' });
  for (const k of ['Page Up', 'F2', 'Shift+Enter', 'Alt', 'Double-click', 'Ctrl']) await expect(help, k).toContainText(k);
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('the canvas can be heard but not changed', async ({ page, studio }) => {
    await openMixed(page, studio);
    const f = frame(page);
    await f.focus();
    await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-logo');
    await page.keyboard.press('PageDown');
    await expect(f).toHaveAttribute('aria-activedescendant', 'ovs-lyr-score');
    await expect(page.locator('.pg .h'), 'no handles').toHaveCount(0);
    const before = await page.locator('.pg[data-id="score"]').getAttribute('style');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await expect(page.locator('[data-tedit]')).toHaveCount(0);
    expect(await page.locator('.pg[data-id="score"]').getAttribute('style'), 'arrows move nothing').toBe(before);
    await page.locator('.pg[data-id="score"]').dblclick();
    await expect(page.locator('[data-tedit]')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: 'Layer id' })).toHaveCount(0);
  });
});
