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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The role decisions, with no Discord anywhere.
 *
 * <p>Every one of these was previously unreachable without a live gateway, and
 * PIT reported sixty-two mutants in {@code JdaSurface} executed by nothing at
 * all. The failure paths are the point: four of them, each returning false, each
 * with a different message, and an operator whose role is not appearing needs
 * the right one.
 */
class GuildRolesTest {

    private static final String WHO = "acct-1";
    private static final String ROLE = "linked";

    /** A platform whose every answer is scripted, and which records what it was asked. */
    private static final class FakePlatform implements GuildRoles.Platform {
        private GuildRoles.Pair pair = new GuildRoles.Pair(true, true, true, false);
        private List<String> holders = List.of();
        private RuntimeException resolveFailure;
        private RuntimeException mutateFailure;
        private RuntimeException holdersFailure;
        private final List<String> calls = new ArrayList<>();

        @Override
        public GuildRoles.Pair resolve(String platformId, String role) {
            calls.add("resolve " + platformId + " " + role);
            if (resolveFailure != null) {
                throw resolveFailure;
            }
            return pair;
        }

        @Override
        public void addRole(String platformId, String role) {
            calls.add("add " + platformId + " " + role);
            if (mutateFailure != null) {
                throw mutateFailure;
            }
        }

        @Override
        public void removeRole(String platformId, String role) {
            calls.add("remove " + platformId + " " + role);
            if (mutateFailure != null) {
                throw mutateFailure;
            }
        }

        @Override
        public List<String> holders(String role) {
            calls.add("holders " + role);
            if (holdersFailure != null) {
                throw holdersFailure;
            }
            return holders;
        }

        FakePlatform answering(
                boolean server, boolean roleExists, boolean member, boolean held) {
            this.pair = new GuildRoles.Pair(server, roleExists, member, held);
            return this;
        }
    }

    private record Fixture(GuildRoles roles, FakePlatform platform, List<String> logged) {}

    private static Fixture fixture(FakePlatform platform) {
        List<String> logged = new ArrayList<>();
        return new Fixture(
                new GuildRoles(platform, (message, cause) -> logged.add(message)),
                platform,
                logged);
    }

    private static Fixture fixture() {
        return fixture(new FakePlatform());
    }

    // --- the four guards, each with its own message ---------------------------

    @Test
    @DisplayName("no server configured says so, rather than failing anonymously")
    void noServer() {
        Fixture f = fixture(new FakePlatform().answering(false, false, false, false));

        assertFalse(f.roles().grant(WHO, ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("no server configured")),
                f.logged()::toString);
    }

    @Test
    @DisplayName("a role that is not on the server names the role")
    void noSuchRole() {
        Fixture f = fixture(new FakePlatform().answering(true, false, false, false));

        assertFalse(f.roles().grant(WHO, ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("no role named '" + ROLE + "'")),
                "the message does not name the role, which is the one thing an operator would"
                        + " go and fix: " + f.logged());
    }

    @Test
    @DisplayName("an account that is not a member names the account")
    void noSuchMember() {
        Fixture f = fixture(new FakePlatform().answering(true, true, false, false));

        assertFalse(f.roles().grant(WHO, ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("no member " + WHO)),
                f.logged()::toString);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("an unconfigured role is a silent no-op, not a failure and not a log line")
    void blankRoleIsSilent(String role) {
        // A deployment may want the gate without a role. Logging it would fire
        // on every event for the life of the process, which is how an operator
        // learns to ignore this connector's output.
        Fixture f = fixture();

        assertFalse(f.roles().grant(WHO, role));
        assertTrue(f.logged().isEmpty(), f.logged()::toString);
        assertTrue(f.platform().calls.isEmpty(),
                "the platform was asked about a role that is not configured: "
                        + f.platform().calls);
    }

    // --- granting -------------------------------------------------------------

    @Test
    @DisplayName("granting a role the account lacks applies it")
    void grantApplies() {
        Fixture f = fixture(new FakePlatform().answering(true, true, true, false));

        assertTrue(f.roles().grant(WHO, ROLE));
        assertTrue(f.platform().calls.contains("add " + WHO + " " + ROLE),
                f.platform().calls::toString);
    }

    @Test
    @DisplayName("granting a role already held is true, and asks the platform for nothing")
    void grantIsIdempotent() {
        // TRUE, because the contract is whether the role is held afterwards.
        // Delivery is at-least-once: reporting the second arrival as a failure
        // would have the effector refuse to acknowledge an event it had already
        // applied, forever.
        Fixture f = fixture(new FakePlatform().answering(true, true, true, true));

        assertTrue(f.roles().grant(WHO, ROLE));
        assertFalse(f.platform().calls.stream().anyMatch(c -> c.startsWith("add ")),
                "the platform was asked to grant a role the account already held: "
                        + f.platform().calls);
    }

    @Test
    @DisplayName("a platform that refuses the grant is reported as not applied")
    void grantFailureIsHonest() {
        FakePlatform platform = new FakePlatform().answering(true, true, true, false);
        platform.mutateFailure = new IllegalStateException("missing permission");
        Fixture f = fixture(platform);

        assertFalse(f.roles().grant(WHO, ROLE),
                "a refused grant reported success; the effector would acknowledge an event it"
                        + " never applied and the role would never appear");
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("could not apply role")),
                f.logged()::toString);
    }

    // --- revoking -------------------------------------------------------------

    @Test
    @DisplayName("revoking a role the account holds removes it")
    void revokeRemoves() {
        Fixture f = fixture(new FakePlatform().answering(true, true, true, true));

        assertTrue(f.roles().revoke(WHO, ROLE));
        assertTrue(f.platform().calls.contains("remove " + WHO + " " + ROLE),
                f.platform().calls::toString);
    }

    @Test
    @DisplayName("revoking a role nobody holds is true, and asks the platform for nothing")
    void revokeIsIdempotent() {
        Fixture f = fixture(new FakePlatform().answering(true, true, true, false));

        assertTrue(f.roles().revoke(WHO, ROLE));
        assertFalse(f.platform().calls.stream().anyMatch(c -> c.startsWith("remove ")),
                f.platform().calls::toString);
    }

    @Test
    @DisplayName("a refused revoke says 'remove', not 'apply'")
    void revokeFailureNamesWhatItWasDoing() {
        // The verb is in the message because the two failures send an operator
        // to different places -- a grant that fails is usually a rank ordering,
        // a revoke that fails is usually a member who has left.
        FakePlatform platform = new FakePlatform().answering(true, true, true, true);
        platform.mutateFailure = new IllegalStateException("gone");
        Fixture f = fixture(platform);

        assertFalse(f.roles().revoke(WHO, ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("could not remove role")),
                f.logged()::toString);
    }

    // --- reading --------------------------------------------------------------

    @Test
    @DisplayName("has() answers the platform's view, and false when it cannot be asked")
    void has() {
        assertTrue(fixture(new FakePlatform().answering(true, true, true, true))
                .roles().has(WHO, ROLE));
        assertFalse(fixture(new FakePlatform().answering(true, true, true, false))
                .roles().has(WHO, ROLE));
        assertFalse(fixture(new FakePlatform().answering(false, false, false, false))
                .roles().has(WHO, ROLE),
                "an unaskable platform answered that the account holds the role");
    }

    @Test
    @DisplayName("a lookup that throws is reported and false, never propagated")
    void resolveFailureIsContained() {
        // The member left, the bot lost its permission, the gateway hiccupped.
        // False means the effector does not acknowledge, so the event comes
        // back -- which is the recovery. An exception out of here would kill the
        // drain instead.
        FakePlatform platform = new FakePlatform();
        platform.resolveFailure = new IllegalStateException("gateway said no");
        Fixture f = fixture(platform);

        assertFalse(f.roles().grant(WHO, ROLE));
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("could not apply role")),
                f.logged()::toString);
    }

    // --- holders --------------------------------------------------------------

    @Test
    @DisplayName("holders of a configured role are returned")
    void holders() {
        FakePlatform platform = new FakePlatform().answering(true, true, false, false);
        platform.holders = List.of("acct-1", "acct-2");

        assertEquals(List.of("acct-1", "acct-2"), fixture(platform).roles().holders(ROLE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("holders of an unconfigured role asks the platform nothing")
    void holdersOfNothing(String role) {
        Fixture f = fixture();

        assertTrue(f.roles().holders(role).isEmpty());
        assertTrue(f.platform().calls.isEmpty(), f.platform().calls::toString);
    }

    @Test
    @DisplayName("no server, or no such role, is an empty list rather than an error")
    void holdersWithoutAServerOrARole() {
        // A role that does not exist and a role nobody holds are the same answer
        // for reconciliation, and distinguishing them would make every caller
        // handle a case with no different action.
        assertTrue(fixture(new FakePlatform().answering(false, false, false, false))
                .roles().holders(ROLE).isEmpty());
        assertTrue(fixture(new FakePlatform().answering(true, false, false, false))
                .roles().holders(ROLE).isEmpty());
    }

    @Test
    @DisplayName("a listing that fails is empty and said, never a partial answer")
    void holdersFailureIsEmptyAndLogged() {
        // Reconciliation revokes from the accounts it is given. Handing it half
        // the holders because a page failed would revoke from nobody in the
        // missing half and look exactly like success.
        FakePlatform platform = new FakePlatform().answering(true, true, false, false);
        platform.holdersFailure = new IllegalStateException("rate limited");
        Fixture f = fixture(platform);

        assertTrue(f.roles().holders(ROLE).isEmpty());
        assertTrue(f.logged().stream().anyMatch(m -> m.contains("could not list holders")),
                f.logged()::toString);
    }

    @Test
    @DisplayName("the holders path asks about the role and about nobody in particular")
    void holdersResolvesWithoutAMember() {
        // A null account, so the platform skips the member retrieval rather than
        // retrieving nothing -- one round trip against a rate-limited API that
        // would otherwise be spent asking about a member the caller never named.
        FakePlatform platform = new FakePlatform().answering(true, true, false, false);
        fixture(platform).roles().holders(ROLE);

        assertTrue(platform.calls.contains("resolve null " + ROLE), platform.calls::toString);
    }
}
