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

package dev.soulbind.core;

import dev.soulbind.protocol.SchemaVersion;

/**
 * What this build is.
 *
 * <p>Reported at start-up, in {@code hello}, and by {@code soulbind version} —
 * so that "which build is running" is answerable from the outside, which is the
 * first question asked of a deployment behaving unexpectedly.
 */
public final class CoreVersion {

    /**
     * The build version.
     *
     * <p>Read from the jar's manifest when there is one, falling back to a
     * development marker. Deliberately not a constant edited by hand: a version
     * string somebody has to remember to bump is a version string that lies for
     * one release out of three.
     */
    public static final String VERSION = resolveVersion();

    /** The protocol version this build speaks. Distinct from the build version. */
    public static final int SCHEMA = SchemaVersion.CURRENT;

    private CoreVersion() {
        throw new AssertionError("no instances");
    }

    private static String resolveVersion() {
        Package pkg = CoreVersion.class.getPackage();
        String declared = pkg == null ? null : pkg.getImplementationVersion();
        // "(development)" rather than a plausible-looking number: a build run
        // from a classes directory should not be mistakable for a release in a
        // bug report.
        return declared == null || declared.isBlank() ? "(development)" : declared;
    }
}
