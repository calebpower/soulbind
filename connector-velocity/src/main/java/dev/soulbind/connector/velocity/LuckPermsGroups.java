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
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

/**
 * The LuckPerms integration behind {@link GroupEffector}.
 *
 * <p>{@code GroupEffector.discover} has always taken a resolver so the lookup
 * could be tested and so a deployment with a different permissions plugin would
 * need a resolver rather than a fork. The plugin passed
 * {@code java.util.Optional::empty} — a placeholder that was never replaced, so
 * even with LuckPerms installed the effector fell through to {@code absent()}
 * and every group grant was a no-op. Nothing caught it because nothing called
 * grant or revoke either, and LuckPerms is not in the composed stack. See
 * DECISIONS 10.23.
 *
 * <p>Isolated in its own class so that {@code GroupEffector} stays testable
 * without LuckPerms on the classpath: nothing here is touched unless the
 * provider class is present, which is what {@code discover} establishes first.
 */
final class LuckPermsGroups {

    private LuckPermsGroups() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds an effector over the running LuckPerms, if there is one.
     *
     * <p>Returns empty rather than throwing when the provider is present but
     * not yet ready: LuckPerms registers its API during its own start-up, and a
     * proxy loading plugins in an unlucky order would otherwise take soulbind
     * down over a race that resolves itself.
     */
    static Optional<GroupEffector> resolve(BiConsumer<String, Throwable> log) {
        final LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException notReady) {
            log.accept(
                    "LuckPerms is installed but its API is not registered yet; group grants"
                            + " are disabled for this session. Restarting the proxy usually"
                            + " resolves it.",
                    notReady);
            return Optional.empty();
        }

        return Optional.of(GroupEffector.of(
                (playerId, group) -> modify(luckPerms, playerId, group, true),
                (playerId, group) -> modify(luckPerms, playerId, group, false),
                log));
    }

    /**
     * Adds or removes an inheritance node, and saves.
     *
     * <p>Blocking on the future deliberately. {@code GroupEffector} reports
     * whether the change was applied, and the effector's caller acknowledges an
     * event only after it was — so returning before LuckPerms had written would
     * let a connector acknowledge work it had not finished, and the event would
     * never come back.
     */
    private static void modify(LuckPerms luckPerms, UUID playerId, String group, boolean add)
            throws Exception {
        luckPerms.getUserManager().modifyUser(playerId, user -> {
            InheritanceNode node = InheritanceNode.builder(group).build();
            if (add) {
                user.data().add(node);
            } else {
                user.data().remove(node);
            }
        }).get();
    }

    /**
     * Whether a player is already in a group, for an idempotent grant.
     *
     * <p>Reads the cached user, which is populated for anybody online. An
     * offline player answers false and the grant is attempted anyway — which is
     * correct, because {@code add} on a node already present is a no-op in
     * LuckPerms, and the alternative is loading user storage to answer a
     * question whose wrong answer costs nothing.
     */
    static boolean hasGroup(LuckPerms luckPerms, UUID playerId, String group) {
        User user = luckPerms.getUserManager().getUser(playerId);
        if (user == null) {
            return false;
        }
        return user.getNodes(NodeType.INHERITANCE).stream()
                .anyMatch(node -> node.getGroupName().equalsIgnoreCase(group));
    }
}
