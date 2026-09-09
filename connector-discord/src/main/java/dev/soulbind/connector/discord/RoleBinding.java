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

package dev.soulbind.connector.discord;

import java.util.Locale;
import java.util.Objects;

/**
 * One gate, one role, and which direction the events move it.
 *
 * <p>Replaces the single {@code effector.gate} / {@code effector.role} pair. A
 * deployment wants more than one role — "linked to the game", "linked to the
 * forum" — and each is a different gate.
 *
 * <p><b>{@link Mode} is what makes hysteresis expressible without core learning
 * about it.</b> A role that should be granted at one threshold and taken away at
 * a lower one is two gates, each an ordinary requirement, bound to the same
 * role: the higher gate binds {@code GRANT} and the lower binds {@code REVOKE}.
 * The two directions that are ignored — losing the grant gate, meeting the keep
 * gate — <em>are</em> the hysteresis band. Nothing here holds state, and each
 * gate on its own is still a plain question core answers the same way for
 * everybody.
 */
public record RoleBinding(String gate, String role, Mode mode) {

    public RoleBinding {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(mode, "mode");
    }

    /** Which half of the event stream this binding acts on. */
    public enum Mode {
        /** Grant on {@code requirements-met}; never take the role away. */
        GRANT,
        /** Take the role away on {@code requirements-lost}; never grant it. */
        REVOKE,
        /** Both, which is what a single-threshold role wants. */
        BOTH;

        public boolean grants() {
            return this != REVOKE;
        }

        public boolean revokes() {
            return this != GRANT;
        }

        /**
         * Parses a configured mode, or returns null so the caller can report it
         * with the rest of the configuration's problems at once.
         */
        public static Mode fromConfigName(String name) {
            if (name == null || name.isBlank()) {
                return BOTH;
            }
            return switch (name.strip().toLowerCase(Locale.ROOT)) {
                case "grant" -> GRANT;
                case "revoke" -> REVOKE;
                case "both" -> BOTH;
                default -> null;
            };
        }
    }
}
