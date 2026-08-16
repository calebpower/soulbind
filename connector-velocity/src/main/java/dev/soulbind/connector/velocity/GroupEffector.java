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
package dev.soulbind.connector.velocity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Granting and revoking a permission group.
 *
 * <p>The permissions plugin is a <b>soft dependency</b>, looked up reflectively.
 * Its absence is a logged, non-fatal condition — a proxy without it should still
 * run {@code /link}, still enforce the join gate, and still be usable. Refusing
 * to start over a missing optional integration turns one operator's choice into
 * an outage.
 *
 * <p>Reflection rather than a compile-time dependency for the same reason: a
 * hard dependency would make the plugin unloadable without it, which is the
 * opposite of optional.
 *
 * <h2>Why the API surface here is two lambdas</h2>
 *
 * <p>The reflective lookup is separated from the granting, so every behaviour
 * that matters — what happens when the plugin is absent, when a grant throws,
 * when the same grant arrives twice — is testable without the permissions
 * plugin on the classpath. The lookup itself is the only part that needs it, and
 * it is the part with no logic in it.
 */
public final class GroupEffector {

    /** What an effector does, once something has resolved how to do it. */
    @FunctionalInterface
    public interface GroupAction {
        void apply(UUID playerId, String group) throws Exception;
    }

    private final GroupAction grant;
    private final GroupAction revoke;
    private final BiConsumer<String, Throwable> log;
    private final boolean available;

    private GroupEffector(
            GroupAction grant,
            GroupAction revoke,
            BiConsumer<String, Throwable> log,
            boolean available) {
        this.grant = grant;
        this.revoke = revoke;
        this.log = log;
        this.available = available;
    }

    /** An effector backed by something that can actually change permissions. */
    public static GroupEffector of(
            GroupAction grant, GroupAction revoke, BiConsumer<String, Throwable> log) {
        return new GroupEffector(grant, revoke, log, true);
    }

    /**
     * An effector for a proxy with no permissions plugin.
     *
     * <p>Every call is a no-op that logs once at construction. Deliberately not
     * an exception and not a null: a connector holding a null effector would
     * need a null check at every call site, and one of them would be missed.
     */
    public static GroupEffector absent(BiConsumer<String, Throwable> log) {
        log.accept(
                "no permissions plugin found; group grants are disabled. Linking and the join "
                        + "gate still work -- this only means no group is applied when a "
                        + "subject satisfies a gate.",
                null);
        return new GroupEffector((id, group) -> { }, (id, group) -> { }, log, false);
    }

    /**
     * Looks up a permissions plugin reflectively.
     *
     * @param resolver supplies the integration when the class is present. Passed
     *     in rather than hard-coded so the lookup is testable and so a
     *     deployment with a different permissions plugin needs a resolver, not a
     *     fork.
     */
    public static GroupEffector discover(
            String className,
            java.util.function.Supplier<Optional<GroupEffector>> resolver,
            BiConsumer<String, Throwable> log) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            return absent(log);
        } catch (LinkageError e) {
            // Present but unloadable -- a version mismatch, usually. Logged
            // with its cause rather than swallowed, because "no permissions
            // plugin found" would send an operator looking for a plugin that
            // is sitting right there.
            log.accept("a permissions plugin is present but could not be loaded", e);
            return absent(log);
        }
        return resolver.get().orElseGet(() -> absent(log));
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Grants a group, reporting rather than throwing.
     *
     * <p>An effector failure must not propagate into the event that triggered
     * it. A player who linked successfully should not see an error because a
     * permissions plugin was briefly unhappy — the link happened, and the group
     * is a consequence that can be retried.
     *
     * @return true if it was applied
     */
    public boolean grant(UUID playerId, String group) {
        return run(grant, playerId, group, "grant");
    }

    public boolean revoke(UUID playerId, String group) {
        return run(revoke, playerId, group, "revoke");
    }

    private boolean run(GroupAction action, UUID playerId, String group, String what) {
        if (!available) {
            // Nothing was applied, so say so. Reporting success because a no-op
            // did not throw would let a caller log "granted" for a group that
            // does not exist anywhere -- and an operator reading that log would
            // have no way to discover the permissions plugin was missing.
            return false;
        }
        if (group == null || group.isBlank()) {
            // Nothing configured. Not a failure: a deployment may want the gate
            // without a group.
            return false;
        }
        try {
            action.apply(playerId, group);
            return true;
        } catch (Exception e) {
            log.accept("could not " + what + " group '" + group + "' for " + playerId, e);
            return false;
        }
    }
}
