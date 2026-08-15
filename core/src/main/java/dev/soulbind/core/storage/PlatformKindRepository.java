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

package dev.soulbind.core.storage;

import java.util.List;

/**
 * Platform kinds, learned at runtime.
 *
 * <p>A kind is created the first time a connector registers one. Nothing here
 * enumerates the possibilities, and nothing may: the dispatcher's ignorance of
 * which platforms exist is what lets a new one arrive without a dispatcher
 * change. The vocabulary guard enforces that ignorance mechanically.
 */
public interface PlatformKindRepository {

    /** Records a kind if it is not already known. Idempotent. */
    void seen(String kind, String registeredByConnectorId);

    List<String> list();

    boolean isKnown(String kind);
}
