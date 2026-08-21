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

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Every decision the role mechanics make, with no library type in sight.
 *
 * <p><b>Why this exists.</b> {@code JdaSurface} says of itself that it is
 * "deliberately mechanical" and that anything worth asserting belongs above the
 * seam. That was true of its translation and false of its role handling: four
 * distinct failure paths, each with its own operator-facing message, each
 * returning false — and reaching any of them meant faking a {@code RestAction}
 * chain, so no test did. Sixty-two mutants in that file were executed by
 * nothing at all.
 *
 * <p>That is the shape the Velocity group effector had when it shipped inert
 * for three separate reasons and no tier could see it. {@code GroupEffector}
 * was fixed by taking its platform work as lambdas so every behaviour except
 * the lookup is testable without the platform on the classpath. This is the
 * same fix, applied to the connector that has the harder platform to fake.
 * DECISIONS 10.29.
 *
 * <h2>One resolution per operation</h2>
 *
 * <p>{@link Platform#resolve} answers everything the decisions need in a single
 * call, deliberately. Asking "is there a server", "does the role exist", "is
 * there such a member" and "does he hold it" as four separate questions would
 * turn one member retrieval into four — a real cost against a rate-limited API,
 * paid on every event, in exchange for an interface that reads slightly better.
 */
final class GuildRoles {

    /**
     * What the platform knows about one account-and-role pair.
     *
     * <p>Strings and booleans. A handle here would put a library type back in
     * every file that touches one, which is the thing the seam exists to
     * prevent.
     *
     * @param serverConfigured whether there is a server to act on at all
     * @param roleExists whether a role of that name is on it
     * @param memberExists whether the account is a member of it
     * @param held whether the account already holds the role
     */
    record Pair(
            boolean serverConfigured,
            boolean roleExists,
            boolean memberExists,
            boolean held) {}

    /** The platform operations, each of which may fail the way platforms do. */
    interface Platform {
        Pair resolve(String platformId, String role) throws Exception;

        void addRole(String platformId, String role) throws Exception;

        void removeRole(String platformId, String role) throws Exception;

        List<String> holders(String role) throws Exception;
    }

    private final Platform platform;
    private final BiConsumer<String, Throwable> log;

    GuildRoles(Platform platform, BiConsumer<String, Throwable> log) {
        this.platform = platform;
        this.log = log;
    }

    /**
     * Grants a role, reporting whether the account holds it afterwards.
     *
     * <p>Not whether this call changed anything: an event stream is
     * at-least-once, so the same grant arrives twice, and reporting the second
     * as a failure would have the effector refuse to acknowledge an event it had
     * already applied — forever.
     */
    boolean grant(String platformId, String role) {
        return act(platformId, role, "apply", pair -> {
            if (pair.held()) {
                return true;
            }
            platform.addRole(platformId, role);
            return true;
        });
    }

    /** Removes a role, reporting whether the account is without it afterwards. */
    boolean revoke(String platformId, String role) {
        return act(platformId, role, "remove", pair -> {
            if (!pair.held()) {
                return true;
            }
            platform.removeRole(platformId, role);
            return true;
        });
    }

    /** Whether the account holds the role right now. */
    boolean has(String platformId, String role) {
        return act(platformId, role, "read", Pair::held);
    }

    /**
     * Everyone holding the role.
     *
     * <p>A role that does not exist and a role nobody holds are the same answer
     * for reconciliation, and distinguishing them here would make every caller
     * handle a case with no different action.
     *
     * <p><b>Empty on failure, never a partial list.</b> Reconciliation revokes
     * from the accounts it is given; handing it half the holders because a page
     * failed would revoke from nobody in the missing half and look like success.
     */
    List<String> holders(String role) {
        if (role == null || role.isBlank()) {
            return List.of();
        }
        try {
            Pair pair = platform.resolve(null, role);
            if (!pair.serverConfigured() || !pair.roleExists()) {
                return List.of();
            }
            return platform.holders(role);
        } catch (Exception e) {
            log.accept("could not list holders of role '" + role + "'", e);
            return List.of();
        }
    }

    /** What to do once the guards have passed. */
    @FunctionalInterface
    private interface Work {
        boolean apply(Pair pair) throws Exception;
    }

    /**
     * The four guards, in the order an operator would want to hear them.
     *
     * <p>Each says what is wrong and nothing else. "Could not apply the role" is
     * not something anybody can act on; "no role named 'linked' on this server"
     * is.
     *
     * @param verb what was being attempted, for the failure line
     */
    private boolean act(String platformId, String role, String verb, Work work) {
        if (role == null || role.isBlank()) {
            // Nothing configured. Not a failure -- a deployment may want the
            // gate without a role -- and deliberately silent, because logging
            // it would fire on every event for the life of the process.
            return false;
        }
        try {
            Pair pair = platform.resolve(platformId, role);
            if (!pair.serverConfigured()) {
                log.accept("no server configured, so roles cannot be applied", null);
                return false;
            }
            if (!pair.roleExists()) {
                log.accept("no role named '" + role + "' on this server", null);
                return false;
            }
            if (!pair.memberExists()) {
                log.accept("no member " + platformId + " on this server", null);
                return false;
            }
            return work.apply(pair);
        } catch (Exception e) {
            // The member left, the bot lost its permission, the gateway
            // hiccupped. Reported and false -- the effector then does not
            // acknowledge, and the event comes back.
            log.accept("could not " + verb + " role '" + role + "' for " + platformId, e);
            return false;
        }
    }
}
