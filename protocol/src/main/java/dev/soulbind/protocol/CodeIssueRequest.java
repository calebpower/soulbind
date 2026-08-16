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
 * Ask for a code on behalf of an account the caller can authenticate locally.
 *
 * <p>Core does not verify the account and could not: it has no way to
 * authenticate a platform account itself, which is precisely why connectors
 * exist. The connector knows who ran the command; core trusts the connector's
 * credential and records which one vouched.
 */
public record CodeIssueRequest(String platformKind, String platformId, String display) {}
