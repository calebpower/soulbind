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

/**
 * Acknowledge every event up to and including a sequence.
 *
 * <p>Cumulative, not per-event. A connector that applied 1..50 says 50 once,
 * rather than fifty times — and a cumulative acknowledgement cannot leave a
 * hole, which a per-event scheme can.
 */
public record EventAckRequest(long through) {}
