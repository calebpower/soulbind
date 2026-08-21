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
import java.util.Optional;

/**
 * The chat platform, as this connector needs it.
 *
 * <p><b>The seam.</b> Two implementations exist: the real client library, and a
 * scripted one the battery drives. The connector's logic — validating input,
 * calling core, deciding what to say, granting a role — runs identically
 * against both, so all of it is exercised without the platform in the room.
 *
 * <p>Protocol-faithful fakery of the platform's gateway is explicitly out of
 * scope. Faking a wire protocol means maintaining a second implementation of
 * somebody else's product, which rots the moment they change it and tests
 * nothing this connector owns. The seam is here, at the operations the
 * connector actually performs.
 *
 * <p>Deliberately small. Every method is something the connector does; nothing
 * here exists because the platform's own API has it.
 */
public interface ChatSurface {

    /** Somebody who invoked a command. */
    record Invoker(String platformId, String displayName, boolean isAdministrator) {

        public Invoker {
            if (platformId == null || platformId.isBlank()) {
                throw new IllegalArgumentException("an invoker needs a platform id");
            }
        }
    }

    /** A command as the connector receives it. */
    record Invocation(String command, List<String> arguments, Invoker invoker) {

        public Invocation {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        public Optional<String> firstArgument() {
            return arguments.isEmpty() ? Optional.empty() : Optional.of(arguments.get(0));
        }
    }

    /**
     * Replies to an invocation.
     *
     * @param ephemeral whether only the invoker sees it. A link code shown in a
     *     public channel is a link code anybody can redeem, so the connector
     *     must be able to say "only they see this" — and a surface that could
     *     not express it would make that impossible to get right.
     */
    void reply(Invocation invocation, String message, boolean ephemeral);

    /**
     * Grants a role.
     *
     * <p><b>Returns whether the role is held afterwards</b>, not whether this
     * call changed anything. An account that already had it is a success: the
     * desired state holds. Only a genuine failure — the role was deleted, the
     * bot lost its permission, the account left — is false.
     *
     * <p>An earlier version of this contract said false for "already had it" as
     * well as failure, on the reasoning that the desired state holds either
     * way. That is self-contradictory: on failure it does not hold. It also
     * made the connector's own idempotence check unobservable, because the
     * scripted surface deduplicated underneath it and a mutation removing the
     * check passed.
     */
    boolean grantRole(String platformId, String role);

    /** Revokes a role. Returns whether the role is absent afterwards. */
    boolean revokeRole(String platformId, String role);

    /** Whether an account currently holds a role. */
    boolean hasRole(String platformId, String role);

    /** Registers the commands this connector answers. */
    /**
     * Every account on this platform currently holding a role.
     *
     * <p>Asked of the PLATFORM rather than remembered by the connector. The
     * platform is authoritative — an operator may have granted or removed the
     * role by hand, and a connector reconciling against its own memory would
     * fight them. It also survives a restart without persisting anything.
     *
     * <p>Empty when the role does not exist, which is the same answer as
     * "nobody has it" for reconciliation purposes and needs no special case.
     */
    List<String> membersWithRole(String role);

    void registerCommands(List<String> commands);
}
