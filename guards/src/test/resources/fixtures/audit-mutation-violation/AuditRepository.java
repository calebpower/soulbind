// Deliberately broken fixture. NOT compiled, NOT on any source set.
//
// A repository that grew a retention method. The capability to alter a recorded
// event is exactly what must not exist for a caller to acquire.
package dev.soulbind.core.storage;

import dev.soulbind.core.audit.AuditEntry;
import java.time.Instant;

public interface AuditRepository {
    AuditEntry append(AuditEntry entry);

    long deleteBefore(Instant cutoff);
}
