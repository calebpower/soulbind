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

/**
 * Something that must be true of core, checked against the shadow model.
 *
 * <p><b>Complaints are returned, never thrown.</b> §11 asks the self-test's
 * invariants to complain "stackless, in about a second", and that is a property
 * of this interface rather than of the tests: a stack trace tells you where the
 * checker noticed, which is never interesting, and buries the one sentence that
 * is. It also stops at the first violation, when a run that has diverged has
 * usually diverged in several places at once and the shape of the set is the
 * evidence.
 *
 * <p>An invariant that returns an empty list is making a claim. One that cannot
 * be made to return a non-empty list is making none, which is what
 * {@code OracleSelfTest} exists to catch.
 */
public interface Invariant {

    /** A short stable name, used in reports and in the self-test's own output. */
    String name();

    /** One sentence on what must hold, for a reader who has just seen it fail. */
    String describes();

    /**
     * Checks the invariant.
     *
     * @return one complaint per violation; empty when it holds
     */
    List<String> check(ShadowModel model, CoreView core);
}
