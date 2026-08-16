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

import mineflayer from 'mineflayer';

export { extractCode, looksLikeOutage, ALPHABET } from './codes.js';

/**
 * A player, driven.
 *
 * Every wait is bounded. A harness that hangs gets killed by a timeout nobody
 * reads, and the reason it was waiting is lost with it -- so each wait says what
 * it wanted and what it saw instead.
 */
export class Player {
  #bot = null;
  #chat = [];
  #kickReason = null;
  #ended = false;

  constructor({ host, port, username, timeoutMs = 30000, version = undefined }) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.timeoutMs = timeoutMs;
    // Explicit rather than autodetected.
    //
    // The proxy advertises its own protocol string, and mineflayer's table is
    // keyed on a slightly different one -- 3.5.1 says '26.1.2' where the client
    // knows '26.1', and autodetection then refuses the connection outright. A
    // version the caller states is a version somebody chose, and the stack
    // script keeps it beside the jar pins it has to agree with.
    this.version = version;
  }

  /** Every chat line seen so far, oldest first. */
  get chat() {
    return [...this.#chat];
  }

  /** Why the proxy disconnected us, if it did. */
  get kickReason() {
    return this.#kickReason;
  }

  get ended() {
    return this.#ended;
  }

  /**
   * Connects, resolving when spawned.
   *
   * Rejects if the proxy kicks us -- WITH the kick reason, because "connection
   * failed" is useless and "You need to link your account first" is the whole
   * point of the test that expects it.
   */
  async connect() {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(
          `did not spawn within ${this.timeoutMs}ms; last chat: ` +
          JSON.stringify(this.#chat.slice(-5))));
      }, this.timeoutMs);

      this.#bot = mineflayer.createBot({
        host: this.host,
        port: this.port,
        username: this.username,
        ...(this.version ? { version: this.version } : {}),
        // Offline mode. A scripted test cannot authenticate against a
        // commercial account service and should not try; the README says what
        // that costs.
        auth: 'offline',
      });

      this.#bot.on('messagestr', (message) => {
        this.#chat.push(message);
      });

      this.#bot.on('kicked', (reason) => {
        this.#kickReason = typeof reason === 'string' ? reason : JSON.stringify(reason);
        this.#ended = true;
        clearTimeout(timer);
        reject(new Error(`kicked: ${this.#kickReason}`));
      });

      this.#bot.on('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });

      this.#bot.on('end', () => {
        this.#ended = true;
      });

      this.#bot.once('spawn', () => {
        clearTimeout(timer);
        resolve(this);
      });
    });
  }

  /**
   * Connects, expecting to be REFUSED.
   *
   * Separate from connect() rather than a flag on it, because a test asserting
   * the gate closed must fail loudly if the player gets in -- and a boolean
   * argument would let that read as success.
   */
  async connectExpectingRefusal(expectedText) {
    if (!expectedText) {
      // Required, and this is why: the first version accepted ANY kick as proof
      // the gate refused -- and passed while the plugin was not loaded at all,
      // satisfied by "Unable to connect to lobby". A test that cannot tell the
      // gate from an unrelated failure is a test that reports the gate working
      // when nothing is enforcing anything.
      throw new Error(
        'connectExpectingRefusal needs the text the refusal must contain; ' +
        'any-kick-is-a-pass hid a plugin that never loaded');
    }
    try {
      await this.connect();
    } catch (err) {
      if (this.#kickReason === null) {
        throw err;
      }
      if (!this.#kickReason.includes(expectedText)) {
        throw new Error(
          `kicked, but not by the gate. Expected the reason to contain ` +
          `${JSON.stringify(expectedText)}, got: ${this.#kickReason}`);
      }
      return this.#kickReason;
    }
    throw new Error(
      'the join gate let this player in; it was expected to refuse. ' +
      'A gate that logs a denial without enforcing it looks exactly like this.');
  }

  send(message) {
    this.#bot.chat(message);
  }

  /**
   * Waits for a chat line matching a pattern.
   *
   * Returns the match, so a caller can pull a code out of it rather than
   * re-scanning. On timeout it reports what it DID see, because "timed out
   * waiting for /link/" tells nobody whether the reply was wrong or absent.
   */
  async waitForChat(pattern, { timeoutMs = this.timeoutMs } = {}) {
    const deadline = Date.now() + timeoutMs;
    const seenBefore = this.#chat.length;

    while (Date.now() < deadline) {
      for (const line of this.#chat.slice(seenBefore)) {
        const match = line.match(pattern);
        if (match) {
          return { line, match };
        }
      }
      await new Promise((r) => setTimeout(r, 100));
    }

    throw new Error(
      `no chat matched ${pattern} within ${timeoutMs}ms. Saw: ` +
      JSON.stringify(this.#chat.slice(seenBefore)));
  }

  async disconnect() {
    if (this.#bot && !this.#ended) {
      this.#bot.quit();
      this.#ended = true;
    }
  }
}
