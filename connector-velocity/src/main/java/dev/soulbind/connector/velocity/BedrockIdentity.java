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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Recognising Bedrock players behind the proxy.
 *
 * <p>Bedrock clients reach a Java server through Geyser, and Floodgate gives
 * them a UUID in a reserved range and — usually — a name carrying a configured
 * prefix. Both are conventions of that stack, not of Minecraft, and both are
 * facts the <em>connector</em> knows. Core does not: it sees the same
 * {@code minecraft} platform kind with {@code flags.bedrock = true}, which is
 * why this class lives here and could not live there.
 *
 * <p><b>The UUID is the identity; the name is not.</b> A name prefix is
 * configurable, can be turned off entirely, and changes when an operator
 * decides it should. Treating it as the identifier is how a rename silently
 * reassigns an entitlement. The prefix is parsed only to produce a readable
 * display name.
 *
 * <p>This class has no Floodgate dependency and never will. The plugin detects
 * Floodgate reflectively as a soft dependency; this is the part that must work
 * whether or not it is present, and must be testable without a proxy.
 */
public final class BedrockIdentity {

    /**
     * Floodgate allocates Bedrock UUIDs with all-zero high bits.
     *
     * <p>A Java player's UUID is version 4 (online mode) or version 3 (offline
     * mode); either way the most significant 64 bits are never zero. Floodgate
     * uses that gap deliberately, packing the Xbox Live id into the low bits.
     *
     * <p>Checked structurally rather than by string prefix. The two are in fact
     * <b>equivalent</b> for any {@link UUID} object — {@code toString()} always
     * emits the same dashed layout, so {@code startsWith("00000000-0000-0000")}
     * agrees on every input. An earlier version of this comment claimed the
     * string form "varies with how it was rendered", which is not true and was
     * caught by a mutation test passing when it should not have.
     *
     * <p>The structural check is still the right one, for two reasons that are
     * true: it allocates no string on a path taken for every player join, and it
     * does not depend on {@code UUID.toString()}'s format, which is a JDK
     * detail this code has no business relying on.
     */
    public static boolean isBedrockUuid(UUID uuid) {
        return uuid != null && uuid.getMostSignificantBits() == 0L;
    }

    /** The Xbox Live id packed into a Floodgate UUID, as a decimal string. */
    public static Optional<String> xuidOf(UUID uuid) {
        if (!isBedrockUuid(uuid)) {
            return Optional.empty();
        }
        long low = uuid.getLeastSignificantBits();
        // Unsigned: an XUID can exceed Long.MAX_VALUE, and rendering it signed
        // produces a negative number that matches nothing on the Bedrock side.
        return Optional.of(Long.toUnsignedString(low));
    }

    private BedrockIdentity() {
        throw new AssertionError("no instances");
    }

    /**
     * Strips a configured name prefix, if the name carries it.
     *
     * <p>Returns the name unchanged when the prefix is absent, empty or null —
     * an operator who disabled the prefix has not created an error condition.
     *
     * <p>Only ONE leading occurrence is removed. A player legitimately named
     * {@code ..Alex} behind a {@code .} prefix becomes {@code .Alex}, not
     * {@code Alex}: stripping repeatedly would mangle a real name, and a
     * mangled display name is worse than a slightly odd one because it looks
     * correct.
     */
    public static String stripPrefix(String name, String prefix) {
        if (name == null || prefix == null || prefix.isEmpty()) {
            return name;
        }
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /**
     * The flags core should store for a player.
     *
     * <p>Deliberately a plain map rather than a typed object: core stores it and
     * returns it without understanding it, and giving it a type would invite
     * something up there to read the type and branch on it.
     */
    public static Map<String, Object> flagsFor(UUID uuid) {
        if (!isBedrockUuid(uuid)) {
            // Absent rather than `bedrock = false`. A Java player has no Bedrock
            // trait; writing one would put a Bedrock-shaped field on every
            // identity in the system and invite a reader to treat its absence as
            // unknown rather than as "no".
            return Map.of();
        }
        Map<String, Object> flags = new java.util.LinkedHashMap<>();
        flags.put("bedrock", true);
        xuidOf(uuid).ifPresent(x -> flags.put("xuid", x));
        return Map.copyOf(flags);
    }

    /**
     * A display name safe to show an operator.
     *
     * <p>Never used as an identifier. Lowercased comparison is deliberately NOT
     * done here: Minecraft names are case-preserving, and folding one for
     * display would show an operator something different from what the player
     * sees.
     */
    public static String displayFor(String rawName, String prefix, UUID uuid) {
        String stripped = stripPrefix(rawName, prefix);
        if (stripped == null || stripped.isBlank()) {
            // A Bedrock player whose name did not arrive still needs to be
            // nameable in an audit row. The UUID is the identity anyway.
            return uuid == null ? "(unknown)" : uuid.toString();
        }
        return stripped;
    }

    /** Locale-independent lowercase, for the rare comparison that needs one. */
    public static String fold(String s) {
        // Locale.ROOT: under a Turkish default locale 'I' becomes a dotless i,
        // so a locale-sensitive fold would make name comparison depend on where
        // the proxy happens to be running.
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
