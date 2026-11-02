// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
'use strict';
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: '.',
  testMatch: '*.spec.js',
  timeout: 30000,
  fullyParallel: true,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure'
  }
});
