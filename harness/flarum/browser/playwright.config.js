// Copyright (c) 2026 Caleb L. Power
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',

  // No retries. A gate that works on the second attempt is a gate that does not
  // work: every assertion here is about a deterministic decision, and a retry
  // would turn an intermittent authorization bug into a green run.
  retries: 0,

  // Serial. The tests change forum-wide settings and core-wide rules, so running
  // them in parallel would have each one reading another's configuration -- and
  // the failures would look like flakiness rather than interference.
  workers: 1,
  fullyParallel: false,

  // Generous but finite. The stack is real, so a page load is a real page load;
  // an unbounded wait turns a hung forum into a test run that never ends.
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env.FORUM_URL || 'http://127.0.0.1:8480',
    // Kept on failure only. A trace per passing test is hundreds of megabytes
    // nobody reads.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  reporter: [['list'], ['json', { outputFile: 'results.json' }]],
});
