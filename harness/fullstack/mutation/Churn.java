/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import java.time.Instant;

/**
 * A live writer, standing in for the running deployment.
 *
 * <p>{@code migrate-check.sh} runs against a stack that is still up: core is
 * serving and connectors are draining the event outbox, which moves
 * {@code event_cursor} and {@code event_outbox} rows the whole time. This
 * writes to a small table on the same cadence so the check faces the condition
 * it actually meets in a session, without needing a session to produce it.
 *
 * <p>It writes through {@code Storage} rather than raw SQL on purpose: the
 * point is a second process holding the database the way the server does,
 * including the write serialisation SQLite needs.
 *
 * <p>Usage: {@code Churn <jdbcUrl> <millis>}
 */
public final class Churn {

    private Churn() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Churn <jdbcUrl> <millis>");
            System.exit(2);
        }
        long until = System.currentTimeMillis() + Long.parseLong(args[1]);
        try (Storage storage = Storage.open(Backend.SQLITE, args[0], null, null)) {
            int n = 0;
            while (System.currentTimeMillis() < until) {
                storage.runtimeConfig().set(
                        "selftest.churn", Integer.toString(n++), Instant.now(), "selftest");
                Thread.sleep(150L);
            }
        }
    }
}
