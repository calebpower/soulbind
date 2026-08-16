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
package dev.soulbind.sdk.transport;

/**
 * The request could not be completed.
 *
 * <p><b>Not a refusal.</b> A refusal is a well-formed response that says no, and
 * a connector must treat the two differently: a refusal means tell the person,
 * an unreachable core means fall back to the cache and then to the fail mode.
 * Collapsing them is how "you may not" becomes "try again later", and how a
 * genuine denial gets retried until it accidentally succeeds.
 */
public class TransportException extends Exception {

    private static final long serialVersionUID = 1L;

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
