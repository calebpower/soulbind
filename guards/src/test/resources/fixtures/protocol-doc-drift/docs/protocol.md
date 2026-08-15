# Deliberately broken fixture

This file is NOT documentation. It exists so `ProtocolDocSyncGuardTest` can be
observed rejecting a drifted document, rather than only observed passing on a
correct one. It carries three drifts on purpose:

1. `code.redeem` is attributed to the wrong capability.
2. `subject.teleport` is invented -- no such operation exists.
3. `audit.query` is missing entirely.

## Operations

| Operation | Required capability |
|---|---|
| `hello` | *(any registered)* |
| `heartbeat` | *(any registered)* |
| `event.subscribe` | *(any registered)* |
| `attest` | `identity-provider` |
| `code.issue` | `code-display` |
| `code.redeem` | `code-display` |
| `decide` | `enforcement-point` |
| `audit.push` | `audit-source` |
| `rule.get` | `config-management` |
| `rule.set` | `config-management` |
| `override.get` | `config-management` |
| `override.set` | `config-management` |
| `config.get` | `config-management` |
| `config.set` | `config-management` |
| `connector.list` | `config-management` |
| `subject.inspect` | `config-management` |
| `subject.teleport` | `config-management` |
| `identity.unlink` | `config-management` |
