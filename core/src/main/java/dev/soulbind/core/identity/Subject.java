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

package dev.soulbind.core.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * A person, as far as this system is concerned.
 *
 * <p><b>A subject has no name, no email and no password.</b> soulbind does not
 * have accounts of its own; adding one would make it a thing to be logged into,
 * breached, and reset — and it would immediately become the account people
 * actually care about, which is the opposite of the point. A subject is the
 * join between identities and nothing else.
 */
public record Subject(String id, Instant createdAt, Status status) {

    public enum Status {
        ACTIVE,
        /** Retained rather than deleted, so audit can still name them. */
        SUSPENDED
    }

    public Subject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
