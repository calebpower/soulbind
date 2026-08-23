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

/**
 * The control database for {@code migrate-selftest.sh}.
 *
 * <p>{@code Storage.open} migrates, so opening once is the whole setup — and it
 * is deliberately the same call the server makes rather than a hand-written
 * schema, because a second definition of "apply the migrations" could agree
 * with itself while disagreeing with the server.
 *
 * <p>A row is written afterwards so the control is a database that has been
 * <b>used</b>, not merely created. That is the state a deployment leaves, and
 * it is the state the check exists to run against.
 *
 * <p>Usage: {@code Seed <jdbcUrl>}
 */
public final class Seed {

    private Seed() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Seed <jdbcUrl>");
            System.exit(2);
        }
        try (Storage storage = Storage.open(Backend.SQLITE, args[0], null, null)) {
            storage.policy().gateSeen(
                    "selftest.gate", "conn-selftest", "a gate the control declares");
        }
    }
}
