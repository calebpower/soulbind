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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A chat surface with no chat platform behind it.
 *
 * <p>Lives in the connector's <b>main</b> source set, not its tests, and that is
 * deliberate: the full-stack battery drives it over a control API from another
 * process, so it has to ship. It is a test double the way an in-memory database
 * is — a real implementation of the interface with a different backing store.
 *
 * <p><b>It imports the connector's real logic rather than re-implementing it.</b>
 * There is no second copy of validation or normalisation here; the connector
 * handles an invocation this produces exactly as it handles a real one. A
 * scripted surface that reimplemented any of that would drift, and the drift
 * would be invisible precisely where the tests were most confident.
 *
 * <p>What it does NOT do is fake the platform's gateway protocol. That means
 * maintaining a second implementation of somebody else's product, which rots
 * when they change it and tests nothing this connector owns.
 */
public final class ScriptedSurface implements ChatSurface {

    /** Something the connector said. */
    public record Sent(String toPlatformId, String message, boolean ephemeral) {}

    private final List<Sent> sent = new ArrayList<>();
    private final Map<String, Set<String>> roles = new LinkedHashMap<>();
    private final List<String> registeredCommands = new ArrayList<>();

    /**
     * Every grant and revoke actually attempted.
     *
     * <p>The connector's idempotence is about not CALLING the platform twice,
     * so counting calls is the only way to see it. Counting resulting state
     * cannot: the state is the same either way, which is the point.
     */
    private final List<String> grantCalls = new ArrayList<>();

    private final List<String> revokeCalls = new ArrayList<>();

    /** Roles this surface refuses to grant, so failure is expressible. */
    private final Set<String> unavailableRoles = new LinkedHashSet<>();

    /**
     * Roles whose grant THROWS, which is different from refusing.
     *
     * <p>A real client library raises on a network error or an expired token,
     * and a connector that has only ever met a polite `false` handles that
     * badly. Distinct from {@code unavailableRoles} because the two travel
     * different paths: a refusal is an answer, an exception is not.
     */
    private final Set<String> throwingRoles = new LinkedHashSet<>();

    @Override
    public void reply(Invocation invocation, String message, boolean ephemeral) {
        sent.add(new Sent(invocation.invoker().platformId(), message, ephemeral));
    }

    @Override
    public boolean grantRole(String platformId, String role) {
        grantCalls.add(platformId + " " + role);
        if (throwingRoles.contains(role)) {
            throw new IllegalStateException(
                    "the platform client raised while granting '" + role + "' (simulated)");
        }
        if (unavailableRoles.contains(role)) {
            // A platform can refuse: the role was deleted, the bot lost its
            // permission, the account left. Expressible, because a connector
            // that has never met a refusal handles one badly.
            return false;
        }
        roles.computeIfAbsent(platformId, id -> new LinkedHashSet<>()).add(role);
        // Whether the role is HELD, not whether this call changed it -- the
        // contract a real platform offers. Returning Set.add's result would
        // deduplicate underneath the connector and make its own idempotence
        // check unobservable, which is exactly what hid a mutation removing it.
        return true;
    }

    @Override
    public boolean revokeRole(String platformId, String role) {
        revokeCalls.add(platformId + " " + role);
        if (unavailableRoles.contains(role)) {
            return false;
        }
        Set<String> held = roles.get(platformId);
        if (held != null) {
            held.remove(role);
        }
        return true;
    }

    @Override
    public boolean hasRole(String platformId, String role) {
        return roles.getOrDefault(platformId, Set.of()).contains(role);
    }

    @Override
    public void registerCommands(List<String> commands) {
        registeredCommands.clear();
        registeredCommands.addAll(commands);
    }

    // --- the control surface, for a driver -----------------------------------

    /** Everything the connector said, oldest first. */
    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** The most recent message, which is what a person would have just read. */
    public Sent lastSent() {
        if (sent.isEmpty()) {
            throw new IllegalStateException(
                    "the connector said nothing. Every command must produce a reply -- silence "
                            + "leaves somebody staring at a prompt wondering whether it worked.");
        }
        return sent.get(sent.size() - 1);
    }

    public Set<String> rolesOf(String platformId) {
        return Set.copyOf(roles.getOrDefault(platformId, Set.of()));
    }

    /** Grants attempted, including ones the platform refused. */
    public List<String> grantCalls() {
        return List.copyOf(grantCalls);
    }

    public List<String> revokeCalls() {
        return List.copyOf(revokeCalls);
    }

    public List<String> registeredCommands() {
        return List.copyOf(registeredCommands);
    }

    /** Makes a role fail to apply, as a platform sometimes does. */
    public ScriptedSurface makeRoleUnavailable(String role) {
        unavailableRoles.add(role);
        return this;
    }

    public ScriptedSurface makeRoleAvailable(String role) {
        unavailableRoles.remove(role);
        return this;
    }

    /** Makes granting this role raise, as a client library does on a bad day. */
    public ScriptedSurface makeRoleThrow(String role) {
        throwingRoles.add(role);
        return this;
    }

    public ScriptedSurface stopThrowing(String role) {
        throwingRoles.remove(role);
        return this;
    }

    /** Gives an account a role without the connector doing it, to set a scene. */
    public ScriptedSurface preexistingRole(String platformId, String role) {
        roles.computeIfAbsent(platformId, id -> new LinkedHashSet<>()).add(role);
        return this;
    }

    public void clear() {
        sent.clear();
    }
}
