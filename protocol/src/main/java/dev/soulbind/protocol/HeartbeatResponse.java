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
 * Core's answer to {@code heartbeat}.
 *
 * <p>Carries the server clock for the same reason {@link HelloResponse} does: a
 * connector whose clock has drifted will start having its signed requests
 * refused as stale, and the earliest place to notice is the response it is
 * already receiving.
 *
 * @param serverTimeSeconds seconds since the epoch, by core's clock
 * @param signatureWindowSeconds how far from that a signed timestamp may be
 */
public record HeartbeatResponse(long serverTimeSeconds, int signatureWindowSeconds) {}
