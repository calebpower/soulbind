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
 * Submit a code typed by an account the caller can authenticate locally.
 *
 * <p>The code arrives exactly as the person typed it. Normalisation happens
 * once, in core, so both implementations of this protocol cannot disagree about
 * what a typed code means — a connector normalising first and core normalising
 * again would be two chances to differ.
 */
public record CodeRedeemRequest(
        String code, String platformKind, String platformId, String display) {}
