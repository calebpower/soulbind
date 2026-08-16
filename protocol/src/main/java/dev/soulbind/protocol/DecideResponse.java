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

import java.util.List;

/**
 * The answer, and how long it may be trusted.
 *
 * @param effect {@code allow} or {@code deny}
 * @param reason a stable machine-readable code. A connector matching on
 *     {@code detail} breaks the first time the prose is improved.
 * @param detail human-readable, for logs and for showing a person
 * @param ttlSeconds how long a connector may cache this. Carried in the
 *     response rather than configured per connector, so cache behaviour is
 *     core-tunable without redeploying anything.
 * @param missingKinds what the subject would need. Present on a denial so a
 *     connector can tell somebody what to do rather than only that they cannot.
 */
public record DecideResponse(
        String effect, String reason, String detail, int ttlSeconds, List<String> missingKinds) {

    public DecideResponse {
        missingKinds = missingKinds == null ? List.of() : List.copyOf(missingKinds);
    }
}
