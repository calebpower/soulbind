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

package dev.soulbind.protocol;

/**
 * The protocol schema version carried on every message.
 *
 * <p>A peer that receives a major version it does not know <b>refuses with a
 * reason</b>. It never negotiates downward and never guesses: a silent
 * downgrade lets two peers disagree about what a message means while both
 * appear to be working, and the disagreement surfaces as corrupted state much
 * later, somewhere else.
 */
public final class SchemaVersion {

    /** The version this build speaks. */
    public static final int CURRENT = 1;

    private SchemaVersion() {
        throw new AssertionError("no instances");
    }

    /**
     * Whether a received version can be handled.
     *
     * <p>Exact equality, deliberately, for as long as there is one version.
     * "Accept anything less than or equal to CURRENT" is a compatibility policy,
     * and adopting one before there is a second version to be compatible with
     * means adopting it untested — the first real version bump would be the
     * first time the branch ever ran.
     */
    public static boolean isSupported(int schema) {
        return schema == CURRENT;
    }
}
