# `harness/player-driver/`

A real client driving the proxy connector.

## Why a real client

The alternative is calling the connector's own methods and asserting they
return what we told them to. That proves the code we wrote does what we wrote,
which is not in doubt.

What is in doubt is whether a **player** can link: whether the command is
registered under the name people will type, whether the reply reaches their
chat, whether the join gate actually stops a connection rather than logging that
it would have. Those are properties of the proxy, the plugin descriptor, the
event wiring and the messages — none of which a unit test touches.

## No backdoors

The player runs the link command. The code is read out of chat, exactly as a
person would read it. It is redeemed through the real surface. Nothing here
writes to a database, and nothing calls core directly to arrange a starting
state.

A harness that seeded state would defeat both the migration test and the claim
that the flow works — and it would keep passing after the flow broke.

## Offline mode

The driver connects to a proxy in offline mode, because a scripted test cannot
authenticate against a commercial account service and should not try. That is a
real difference from production and it is worth naming: it means these runs
exercise the UUID shape offline mode produces, not the shape online mode does.

Both shapes are covered by fixtures in `connector-velocity`, which is where that
claim belongs — a fixture can assert a UUID shape without a network, and this
harness cannot conjure an online-mode account.

## Running it

```sh
# npm ci, not npm install: package-lock.json is committed and the harness
# installs from it. See harness/fullstack/driver-lock-check.js.
npm ci
node smoke.js --host 127.0.0.1 --port 25577 --username Alex
```

Exits non-zero on failure, prints what it observed on success. Every wait is
bounded: a harness that hangs is a harness that gets killed by a timeout nobody
reads, and the reason is lost.
