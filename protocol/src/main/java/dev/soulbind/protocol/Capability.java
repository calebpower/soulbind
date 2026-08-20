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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a credential is permitted to do.
 *
 * <p>Capabilities are the only categories the dispatcher knows. There is no
 * notion of a "chat connector" or a "forum connector" anywhere in the system —
 * those are descriptions humans use, and they fall out of which capabilities a
 * connector claims. That is what lets a new integration arrive without a
 * dispatcher change, which is the property the whole architecture is arranged
 * to keep.
 *
 * <p>The wire form is the {@linkplain #wireName() hyphenated lowercase name}.
 * The enum constant is an implementation detail; the wire form is the contract,
 * so it is stated explicitly rather than derived from {@link #name()} — a
 * rename of the constant must not silently become a protocol change.
 */
public enum Capability {

    /** Claim that a platform account completed a challenge. */
    IDENTITY_PROVIDER("identity-provider"),

    /** Request a code for an account this credential vouches for. */
    CODE_DISPLAY("code-display"),

    /** Submit a code typed by an account this credential vouches for. */
    CODE_ENTRY("code-entry"),

    /** Ask allow/deny for an (identity, gate) pair. */
    ENFORCEMENT_POINT("enforcement-point"),

    /** Consume events and apply side effects. Acknowledges with an idempotency key. */
    EFFECTOR("effector"),

    /** Append connector-side events to the audit stream. */
    AUDIT_SOURCE("audit-source"),

    /**
     * Read link state for an identity, and nothing else.
     *
     * <p>The only capability in this enum that grants no mutation of any kind.
     * Every other one either changes the identity graph, changes policy, or
     * causes a side effect somewhere; this one answers "what is this account
     * linked to" and stops.
     *
     * <p>It exists because a dashboard needed it. Before it, reading link state
     * meant holding either {@code config-management} — which also permits
     * rewriting every rule and unlinking any identity — or {@code code-display},
     * which also permits minting a link code. Neither is a reasonable grant for
     * a component whose entire job is to display a value, and the analytics
     * connector is the most-installed and least-audited surface in the system.
     *
     * <p>Reading is not nothing, and the grant is not "harmless": link state
     * says which platforms a person is on and when they joined them, which is
     * exactly the correlation a dashboard should not hand out casually. The
     * analytics connector keeps the subject id off the page unless an operator
     * opts in, for that reason.
     */
    LINK_STATE_READER("link-state-reader"),

    /** Read and mutate rules, overrides, runtime config; inspect subjects; unlink. */
    CONFIG_MANAGEMENT("config-management");

    private final String wireName;

    Capability(String wireName) {
        this.wireName = wireName;
    }

    /** The form that appears on the wire and in the documentation. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a wire name.
     *
     * <p>Returns empty rather than throwing, and does not fall back to a
     * default. An unrecognised capability is a refusal with a reason; silently
     * treating it as "no capability" would turn a protocol mismatch into a
     * confusing authorization failure much later.
     */
    public static Optional<Capability> fromWireName(String s) {
        if (s == null) {
            return Optional.empty();
        }
        // Locale.ROOT deliberately: the Turkish dotless-i turns "I" into "ı"
        // under a Turkish default locale, so a locale-sensitive lowercase here
        // would make capability parsing depend on the server's locale.
        String needle = s.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.wireName.equals(needle)).findFirst();
    }
}
