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

package dev.soulbind.core.audit;

import java.util.List;

/**
 * One page of the audit log, and whether it is the last one.
 *
 * <p>This type exists because the bare list could not say the difference
 * between "that is the whole log" and "that is the first thousand rows of it".
 * Every audit read that crosses the wire returns a page, so a caller writing an
 * export cannot mistake a truncation for the end -- which is the failure that
 * makes an export worse than no export, since it looks complete.
 *
 * @param entries the rows, oldest first
 * @param more whether at least one further row matches beyond this page
 * @param lastSequence the highest sequence in this page, or the cursor that was
 *     passed in when the page is empty, so a caller can always continue from it
 */
public record AuditPage(List<AuditEntry> entries, boolean more, long lastSequence) {

    public AuditPage {
        entries = List.copyOf(entries);
    }
}
