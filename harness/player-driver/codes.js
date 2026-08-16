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

import { createHash } from 'node:crypto';

/**
 * Reading a link code out of chat.
 *
 * Deliberately in its own file, importing nothing. The bot driver needs a
 * running proxy and a game client to exercise at all; this needs neither, so
 * separating them means the parsing is unit-tested and only the driving is
 * left to the full-stack run.
 */

/**
 * The link-code alphabet, mirrored from `protocol/`.
 *
 * A second copy, and the duplication is deliberate rather than careless: this
 * harness is meant to read what a PERSON sees. Importing the alphabet from the
 * Java side would make the harness agree with the implementation by
 * construction, so a code rendered wrongly would still be extracted correctly
 * and the test would pass.
 *
 * If the alphabet changes, this fails and somebody updates it on purpose.
 */
export const ALPHABET = '23456789BCDFGHJKMNPQRSTVWXYZ';

/**
 * Pulls a link code out of a chat line.
 *
 * Matches on the ALPHABET and a minimum length rather than an exact length: the
 * connector may change how many characters a code has, and a length-pinned
 * pattern would then match nothing and report "no code in the reply" for a
 * reply that plainly contained one.
 *
 * @returns the code, or null
 */
export function extractCode(line) {
  if (typeof line !== 'string') {
    return null;
  }
  const match = line.match(new RegExp(`\\b[${ALPHABET}]{6,}\\b`));
  return match ? match[0] : null;
}

/**
 * Whether a message reads as a refusal a person should act on.
 *
 * Used to tell "the system is down" apart from "you did something wrong" in
 * assertions, because the harness should fail differently for each -- an
 * outage during a test run is an infrastructure problem, and a refusal is the
 * thing under test.
 */
export function looksLikeOutage(message) {
  return typeof message === 'string' && message.includes('our side, not yours');
}

/**
 * The UUID an offline-mode server gives a name.
 *
 * Version 3, MD5 of "OfflinePlayer:<name>". The harness needs it to write an
 * override for a player who has not linked yet -- and it has to be computed the
 * same way the server does, or the override names somebody who does not exist
 * and the gate refuses anyway, for a reason nothing reports.
 */
export function offlineUuid(name) {
  const hash = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();

  hash[6] = (hash[6] & 0x0f) | 0x30; // version 3
  hash[8] = (hash[8] & 0x3f) | 0x80; // IETF variant

  const hex = hash.toString('hex');
  return [
    hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16),
    hex.slice(16, 20), hex.slice(20, 32),
  ].join('-');
}
