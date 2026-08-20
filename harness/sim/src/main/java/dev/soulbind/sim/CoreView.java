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

package dev.soulbind.sim;

import java.util.List;
import java.util.Optional;

/**
 * What core says, reduced to what the invariants need to ask it.
 *
 * <p><b>This interface is why the oracle self-test is possible.</b> §14 requires
 * the self-test to be built <em>before</em> the harness: the invariants are fed
 * the responses a broken core would send, and each must complain. That is only
 * achievable if "what core said" is a value the test can fabricate, rather than
 * something reachable exclusively by standing a server up.
 *
 * <p>So the checker never touches a socket. One implementation talks to a real
 * core through {@code connector-sdk}; the self-test supplies implementations
 * that lie in specific, chosen ways. An invariant that cannot be made to
 * complain against a liar is an invariant that would not have complained about
 * the real thing either.
 *
 * <p>Deliberately narrow. Every method here is a question some invariant asks;
 * nothing is exposed because it might be useful later. A wide seam is a seam
 * the self-test cannot cover.
 */
public interface CoreView {

    /** One identity as core reports it. */
    record Identity(String platformKind, String platformId, boolean verified) {
        /** The form audit rows and log lines use. */
        public String ref() {
            return platformKind + ":" + platformId;
        }
    }

    /** A subject and everything core says is on it. */
    record Subject(String subjectId, List<Identity> identities) {}

    /** One audit row, reduced to the fields any invariant compares. */
    record AuditRow(long sequence, String actor, String action, String subjectId) {}

    /**
     * The subject owning an identity, or empty when core does not know it.
     *
     * <p>Empty is a real answer — "no such identity" — and is distinct from a
     * failure to ask, which is {@link #reachable()}.
     */
    Optional<Subject> describe(String platformKind, String platformId);

    /** Audit rows with a sequence strictly greater than {@code after}, oldest first. */
    List<AuditRow> auditSince(long after);

    /** Whether a link code is still redeemable, as core sees it. */
    boolean codeRedeemable(String code);

    /**
     * Whether every response so far was a well-formed envelope with no 5xx.
     *
     * <p>The cheap oracle, and it is separate from the others on purpose: §11
     * requires that "every response also passes the cheap no-5xx/envelope
     * wrapper", independently of whatever the invariants conclude. A run whose
     * model and server agree perfectly, having exchanged a 500 on the way, has
     * still found something.
     */
    boolean reachable();

    /** What the transport saw go wrong, when {@link #reachable()} is false. */
    List<String> transportComplaints();
}
