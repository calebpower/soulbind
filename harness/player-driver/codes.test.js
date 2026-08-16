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

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractCode, looksLikeOutage, ALPHABET, offlineUuid } from './codes.js';

test('reads a code out of the connector\'s actual reply', () => {
  // The literal string the connector sends, so a change to the wording that
  // broke extraction would fail here rather than in a full-stack run.
  assert.equal(
    extractCode('Your link code is BCDFGHJK. Enter it on the other platform to finish linking. It expires in 10 minutes.'),
    'BCDFGHJK');
});

test('reads a numeric code', () => {
  assert.equal(extractCode('Your link code is 23456789.'), '23456789');
});

test('finds nothing in a reply that has no code', () => {
  assert.equal(extractCode('We could not get you a code right now.'), null);
  assert.equal(extractCode(''), null);
  assert.equal(extractCode(null), null);
  assert.equal(extractCode(undefined), null);
});

test('does not mistake lowercase for a code', () => {
  // Codes are shown uppercase. Matching lowercase would let an ordinary English
  // word be read as a code -- and then redeemed, and then reported as an
  // unknown-code failure that has nothing to do with the flow under test.
  assert.equal(extractCode('please link your account'), null);
});

test('does not match the excluded look-alikes', () => {
  // 0, O, 1, I, L and the vowels are not in the alphabet. A word made only of
  // them must not read as a code.
  assert.equal(extractCode('LOOOOOL'), null);
  assert.equal(extractCode('AEIOUAEIOU'), null);
});

test('is not pinned to one code length', () => {
  // A length-pinned pattern would match nothing if the connector changed the
  // length, and report "no code in the reply" for a reply that had one.
  assert.equal(extractCode('code: BCDFGH'), 'BCDFGH');
  assert.equal(extractCode('code: BCDFGHJKMNPQRSTV'), 'BCDFGHJKMNPQRSTV');
});

test('the alphabet excludes every character humans confuse', () => {
  // Mirrored from protocol/ deliberately rather than imported: importing would
  // make this harness agree with the implementation by construction, so a code
  // rendered wrongly would still extract correctly.
  for (const excluded of ['0', 'O', '1', 'I', 'L', 'A', 'E', 'U']) {
    assert.ok(!ALPHABET.includes(excluded), `${excluded} should not be in the alphabet`);
  }
  assert.equal(ALPHABET.length, 28);
});

test('an outage message is distinguishable from a refusal', () => {
  // The harness must fail differently for each: an outage during a run is an
  // infrastructure problem, a refusal is the thing under test.
  assert.ok(looksLikeOutage(
    'This check is temporarily unavailable, so access is on hold. This is a problem on our side, not yours -- please try again shortly.'));
  assert.ok(!looksLikeOutage('That code has expired. Ask for a new one.'));
  assert.ok(!looksLikeOutage(null));
});

test('the offline UUID matches what an offline-mode server computes', () => {
  // Version 3, MD5 of "OfflinePlayer:<name>". Pinned against the documented
  // value for Notch, which is a published vector rather than something this
  // harness produced -- if it were self-derived it would agree with itself and
  // disagree with the server, and the override would name nobody.
  const notch = offlineUuid('Notch');
  assert.equal(notch, 'b50ad385-829d-3141-a216-7e7d7539ba7f');

  // Version nibble is 3, variant is IETF.
  assert.equal(notch[14], '3');
  assert.ok(['8', '9', 'a', 'b'].includes(notch[19]));
});

test('the offline UUID is stable and name-sensitive', () => {
  assert.equal(offlineUuid('Linker'), offlineUuid('Linker'));
  assert.notEqual(offlineUuid('Linker'), offlineUuid('linker'));
});
