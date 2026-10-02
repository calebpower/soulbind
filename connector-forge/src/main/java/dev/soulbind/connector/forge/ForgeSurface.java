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
package dev.soulbind.connector.forge;

/**
 * The forge, as this connector needs it.
 *
 * <p><b>The seam.</b> Two implementations exist: the host's own administrative
 * interface, and a scripted one the battery drives. The connector's logic —
 * normalising a code, calling core, deciding whether an account may exist,
 * creating it — runs identically against both, so all of it is exercised
 * without the forge in the room.
 *
 * <p>Protocol-faithful fakery of the host's API is explicitly out of scope.
 * Faking it means maintaining a second implementation of somebody else's
 * product, which rots the moment they change it and tests nothing this
 * connector owns. The seam is here, at the operations the connector actually
 * performs.
 *
 * <p>Deliberately small. Every method is something the connector does; nothing
 * here exists because the host's API has it.
 */
public interface ForgeSurface {

    /** An account to be created. */
    record Account(String username, String email, String password) {

        public Account {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("an account needs a username");
            }
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("an account needs an email address");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("an account needs a password");
            }
        }

        /**
         * Never the password.
         *
         * <p>A record's generated {@code toString} prints every component, and
         * this one reaches a log the first time somebody wraps a call to the
         * host in a debug line.
         */
        @Override
        public String toString() {
            return "Account[username=" + username + ", email=" + email + ", password=<redacted>]";
        }
    }

    /**
     * Whether an account is already there.
     *
     * <p><b>Three answers, not two.</b> A boolean would collapse "the forge says
     * no such account" into "the forge did not answer", and those must diverge:
     * the first means the name is free, the second means nothing is known. Told
     * apart here rather than in the caller, because a caller handed {@code
     * false} has already lost the distinction and will create an account over
     * the top of somebody else's.
     */
    enum Presence {
        PRESENT,
        ABSENT,
        /** The forge could not be reached, or answered something unreadable. */
        UNKNOWN
    }

    Presence presence(String username);

    /** What happened when an account was asked for. */
    enum Creation {
        CREATED,
        /**
         * The name was taken.
         *
         * <p>Deliberately NOT a success, which is where this contract parts
         * company with {@code grantRole} in the chat connector. A role is a
         * desired state and an account that already has it is fine. An account
         * is an act with an owner: reporting "already there" as success would
         * let somebody who passed the gate be handed a forge account that was
         * never theirs.
         */
        ALREADY_EXISTS,
        /** The forge refused, or could not be reached. */
        FAILED
    }

    Creation create(Account account);

    /**
     * Whether an account administers the forge.
     *
     * <p>Three answers again, and for a sharper reason than {@link Presence}'s.
     * This one decides whether an account may be deactivated, so "I could not
     * find out" must not collapse into "ordinary" — that is the reading that
     * locks the operator out of the forge they would use to repair it.
     */
    enum Role {
        ADMINISTRATOR,
        ORDINARY,
        /** The forge could not be reached, or answered something unreadable. */
        UNKNOWN
    }

    Role role(String username);

    /**
     * Activates or deactivates an account.
     *
     * <p><b>Returns whether the account is in that state afterwards</b>, not
     * whether this call changed anything. An account already active is a
     * success when activation was asked for: the desired state holds. Only a
     * genuine failure — the account is gone, the token lost its rights, the
     * forge is unreachable — is false.
     *
     * <p>This is the shape an effector needs. Events are at-least-once, so the
     * same transition arrives twice and the second application must be a
     * no-op that still reports success.
     */
    boolean setActive(String username, boolean active);
}
