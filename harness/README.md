# harness

Test drivers that are not themselves tests.

> **Status: empty.** Each harness arrives with the tier it serves.

| Directory | Serves | Phase |
|---|---|---|
| `discord-scripted/` | A scripted chat surface, so the real connector logic, SDK, transport and core are exercised without the live platform | 6 |
| `player-driver/` | A real game client driving the proxy connector, so linking is exercised through the real flow rather than a backdoor | 5 |
| `fullstack/` | The staged containerised battery | 8 |
| `sim/` | Simulated users: generator, actors, shadow model, checker, nemesis, shrinker | 9 |

## The rule these all obey

**No backdoors.** State is built through the real flows. A player really runs
the link command; the code is really redeemed through the real surface. A
harness that seeded state directly would defeat both the migration test and the
claim that the flow works.

The scripted surfaces import the connector's **real** validation and
normalisation rather than re-implementing it — a fake that reimplements the
thing it stands in for tests the fake.
