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
 * Claim that a platform account completed a challenge.
 *
 * <p>Distinct from redeeming a code: {@code attest} is how an
 * {@code identity-provider} connector says "I proved this account belongs to
 * this person by some means of my own". The proof method is recorded so policy
 * can care about how something was proven rather than only that it was.
 */
public record AttestRequest(
        String platformKind, String platformId, String display, String proofMethod) {}
