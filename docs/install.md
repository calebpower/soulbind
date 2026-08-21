# Installing soulbind on Ubuntu 26.04

Copyright (c) 2026 Caleb L. Power. Apache-2.0; see [LICENSE](../LICENSE).

This document is the one an operator follows, and the Phase 10 gate is a clean
install on a fresh machine **following only this file**. If a step here does not
work as written, that is a defect in this document, not an exercise for the
reader.

Everything below assumes a fresh Ubuntu 26.04 server and `sudo`.

---

## What you are installing

| Piece | What it is | Where it runs |
|---|---|---|
| **core** | The hub. Holds the identity graph, decides gates, keeps the audit log | One host, as a systemd service |
| **connector-discord** | Chat connector: shows and accepts link codes, applies a role | Anywhere that can reach core |
| **connector-velocity** | Proxy plugin: the join gate and `/link` | Your Velocity proxy |
| **connector-plan** | Plan extension: renders link state | Your Plan install |
| **flarum-connector** | Forum extension | Your Flarum install |

You do not need all of them. Core plus **one** connector is a working
installation; core alone is a working installation with nothing to talk to.

---

## 1. Java

Core and the Discord connector need a **Java 25** runtime. The connectors that
load into someone else's JVM need only Java 21, and they use whatever their host
provides.

```sh
sudo apt update
sudo apt install -y openjdk-25-jre-headless
java -version
```

Expect `openjdk version "25"` or later. If Ubuntu's archive does not carry
`openjdk-25-jre-headless` on your release, install Temurin 25 from Adoptium
instead; nothing here depends on which build it is.

## 2. A user and some directories

Core holds every connector credential hash and the whole identity graph. It gets
its own unprivileged user, and it does not need a login shell or a home
directory.

```sh
sudo useradd --system --no-create-home --shell /usr/sbin/nologin soulbind
sudo mkdir -p /opt/soulbind /etc/soulbind /var/lib/soulbind
sudo chown soulbind:soulbind /var/lib/soulbind
sudo chown root:soulbind /etc/soulbind
sudo chmod 750 /etc/soulbind
```

`/etc/soulbind` is group-owned by the service's group deliberately: `750` with
`root:root` would lock the `soulbind` user out of its own configuration, and
the failure would arrive later, as `doctor` unable to read a file that looks
perfectly in order.

## 3. Unpack core

Take the `core` distribution archive and unpack it.

```sh
sudo tar -xzf core-*.tar.gz -C /opt/soulbind
sudo mv /opt/soulbind/core-* /opt/soulbind/core
```

You now have:

```
/opt/soulbind/core/
├── bin/core              the command
├── lib/*.jar             every dependency, one file each
├── LICENSE
├── NOTICE
├── THIRD-PARTY.txt       the generated inventory of what is in lib/
└── packaging/            the systemd unit and sample configuration
```

`lib/` holding one jar per dependency is deliberate — see
[Replacing a bundled library](#replacing-a-bundled-library).

## 4. Configure

```sh
sudo cp /opt/soulbind/core/packaging/soulbind.toml.sample /etc/soulbind/soulbind.toml
sudo cp /opt/soulbind/core/packaging/core.env.sample /etc/soulbind/core.env
sudo chown root:soulbind /etc/soulbind/soulbind.toml /etc/soulbind/core.env
sudo chmod 640 /etc/soulbind/soulbind.toml
sudo chmod 600 /etc/soulbind/core.env
sudo editor /etc/soulbind/soulbind.toml
```

The sample is commented and every key in it is real — an unknown key is a load
error rather than something silently ignored, because a typo'd setting that
appears present and does nothing is the worst of both.

**Storage.** SQLite is the default and is the right answer for a single core on
one host: state is a file, backup is a file copy, and there is no second service
to keep running. Choose MariaDB when something else already needs the data, or
when you have a database team and no appetite for a new kind of backup. See
[Using MariaDB](#using-mariadb).

**Never put a password in `soulbind.toml`.** It is read from
`SOULBIND_STORAGE_PASSWORD` in `/etc/soulbind/core.env`, which is mode 0600.
A password in the config file is a password in every copy of that file,
including the one pasted into a bug report.

## 5. Check before you start

```sh
sudo -u soulbind /opt/soulbind/core/bin/core doctor --config /etc/soulbind/soulbind.toml
```

`doctor` judges the installation and prints **every** problem it finds, not the
first — fixing one per restart is how people stop reading and start guessing.
Exit 0 is healthy (warnings allowed), 1 unhealthy, 2 cannot run.

`serve` runs the same checks before it binds anything, so a configuration that
would fail `doctor` does not silently come up.

## 6. Install the service

```sh
sudo cp /opt/soulbind/core/packaging/soulbind-core.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now soulbind-core
systemctl status soulbind-core
```

The unit is hardened deliberately and every restriction in it is one core does
not need. Read it before editing. Two lines in particular:

- `MemoryDenyWriteExecute=no` is **not** an oversight. The JVM writes executable
  pages for JIT-compiled code; setting it to `yes` makes core fail to start
  rather than making it safer.
- `CapabilityBoundingSet=` is empty. If you must bind core below port 1024, add
  `CAP_NET_BIND_SERVICE` to **both** that and `AmbientCapabilities=`. Putting it
  behind a reverse proxy is the better answer — core is an HTTP service and
  terminating TLS is not its job.

## 7. Register a connector

Every connector authenticates to core with its own credential, holding **only**
the capabilities it needs.

```sh
sudo -u soulbind /opt/soulbind/core/bin/core register \
    --name discord \
    --capabilities code-display,code-entry,effector,link-state-reader,enforcement-point \
    --config /etc/soulbind/soulbind.toml
```

`link-state-reader` is what `/whoami` needs — it calls `identity.describe`.
Leaving it out gives a connector that links accounts perfectly well and then
answers "this credential does not hold the capability this operation requires"
the first time somebody asks what they are linked to. This example was missing
it until a live smoke found it.

`enforcement-point` is what lets the connector re-check a role after the rule
beneath it changes. Core emits `rule.changed`; the connector then asks `decide`
about each account still holding the role and takes it off whoever no longer
qualifies. Without the capability it keeps every role it has ever granted, and
says so in its log rather than pretending to reconcile.

The connector's `/soulbind connectors` subcommand additionally needs
`config-management`. **Most deployments should not grant that** — it is
administrative, it would let a chat bot read the whole audit log, and without
it that one subcommand simply refuses while everything else works.

This prints the credential **once**. Core keeps only a hash and cannot show it
to you again. Put it straight into the connector's `.env` file.

If you lose it, or it leaks, rotate rather than re-register:

```sh
tools/rpc.sh http://127.0.0.1:7180 "$ADMIN_CREDENTIAL" connector.rotate '{"name":"discord"}'
```

Rotation replaces the credential **immediately, with no overlap window** — the
old one stops working on the next request. That is the point: the case rotation
exists for is somebody else holding the credential, and a grace period is
exactly what you do not want then. Reconfigure the connector and restart it.

### Which capabilities

| Capability | Lets a connector |
|---|---|
| `identity-provider` | Assert who an account is |
| `code-display` | Show a link code |
| `code-entry` | Accept one |
| `link-state-reader` | Read what a subject is linked to |
| `enforcement-point` | Ask whether an identity may pass a gate |
| `effector` | Apply the consequence — a role, a group |
| `audit-source` | Write audit events |
| `config-management` | Administer core |

Grant the fewest that make the connector work. `harness/principals.txt` records
the working grant for each connector this project ships, and a guard keeps it in
step with what those connectors actually call.

## 8. Install a connector

### Discord

```sh
sudo tar -xzf connector-discord-*.tar.gz -C /opt/soulbind
sudo mv /opt/soulbind/connector-discord-* /opt/soulbind/connector-discord
sudo cp /opt/soulbind/connector-discord/packaging/discord.toml.sample /etc/soulbind/discord.toml
sudo cp /opt/soulbind/connector-discord/packaging/discord.env.sample /etc/soulbind/discord.env
sudo chown root:soulbind /etc/soulbind/discord.toml /etc/soulbind/discord.env
sudo chmod 640 /etc/soulbind/discord.toml
sudo chmod 600 /etc/soulbind/discord.env
sudo editor /etc/soulbind/discord.env    # bot token and core credential
sudo editor /etc/soulbind/discord.toml   # guild, role, gate
sudo cp /opt/soulbind/connector-discord/packaging/soulbind-discord.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now soulbind-discord
```

The bot token and the core credential go in the `.env`, not the `.toml`, for the
same reason as the storage password.

The unit is `After=soulbind-core.service` but deliberately **not** `Requires=`.
The connector is built to survive core being down and reconnect; `Requires=`
would take it down with core and turn a brief restart into a chat integration
that stopped responding.

### Velocity and Plan

Both are single jars. Drop them in and restart the host.

```sh
sudo cp connector-velocity-*.jar /path/to/velocity/plugins/
sudo cp connector-plan-*.jar     /path/to/plan/extensions/
```

Their third-party dependencies are relocated inside the jar, so they cannot
collide with the copies your proxy already has.

### Flarum

```sh
cd /path/to/flarum
composer require soulbind/flarum-connector
php flarum cache:clear
```

Enable it in the admin panel and set the core URL and credential there.

---

## Verify

The only verification that means anything is a real link.

1. In Discord, run the connector's link command. It gives you a code.
2. On the other platform, enter that code.
3. Ask core whether it agrees:

```sh
tools/rpc.sh http://127.0.0.1:7180 "$ADMIN_CREDENTIAL" subject.inspect \
    '{"platformKind":"discord","platformId":"<the account id>"}'
```

It answers with the subject and every identity linked to it. You name an
account, not a subject: a connector asking usually knows only the account in
front of it, and requiring a lookup first would make every caller do two round
trips to answer one question.

Reading it back from core is the check. A connector saying "linked!" is a
connector's opinion.

---

## Using MariaDB

Create the database **before** first start. Core creates its own tables; it does
not create its own database.

```sql
CREATE DATABASE soulbind CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'soulbind'@'localhost' IDENTIFIED BY '...';
GRANT ALL ON soulbind.* TO 'soulbind'@'localhost';
```

Then in `soulbind.toml`:

```toml
[storage]
backend = "mariadb"
url = "jdbc:mariadb://127.0.0.1:3306/soulbind"
user = "soulbind"
```

and the password in `/etc/soulbind/core.env`.

**Core states utf8mb4 for itself and refuses to boot if the schema is not
four-byte capable.** That is not fussiness. A server defaulting to latin1
silently mangles names it cannot represent, and it does so for *some* accounts
and not others — which surfaces months later as one person unable to link and no
pattern to it. Better to fail at start, where the message says what is wrong.

Uncomment the `After=mariadb.service` line in the unit if the database is on the
same host.

---

## Replacing a bundled library

Some of what core and the Discord connector depend on is licensed LGPL or EPL:
the MariaDB driver, logback, and (in the Discord connector) trove4j. Those are
**never** shaded into a combined jar. They ship as their own files in `lib/`
precisely so you can replace them — which is what satisfies the relink
requirement in practice.

`THIRD-PARTY.txt` in each distribution lists what is in that `lib/` and which of
them ship unbundled for this reason.

To replace one: drop the new jar into `lib/`, **remove the old one**, and
restart. Nothing needs rebuilding.

> The start script enumerates each jar by name rather than using a wildcard, so
> a replacement with a different version in its filename will not be on the
> classpath until the script is updated. Keep the filename, or edit
> `bin/core`'s `CLASSPATH=` line.

---

## Backing up

**SQLite.** Stop core, copy `/var/lib/soulbind/soulbind.db`, start core. Copying
a live SQLite file gets you a file that may not open.

**MariaDB.** `mysqldump` as usual.

**The audit log** is worth archiving separately, because it is the record of who
was linked to what and when, and it is what an incident review reads:

```sh
tools/audit-export.sh http://127.0.0.1:7180 "$ADMIN_CREDENTIAL" > audit-$(date +%F).jsonl
```

It prints the sequence it stopped at. Pass that back as a third argument next
time and you get only what has happened since, which makes it a nightly archive
rather than a full dump every night.

---

## Upgrading

1. Read the release notes for schema changes.
2. Back up (above). Core migrates the schema on start and does not migrate back.
3. Stop the services.
4. Unpack the new distribution alongside, move the old one aside, swap.
5. `doctor`, then start.

Core applies pending migrations at start. If one fails it refuses to serve
rather than running against a half-migrated schema.

---

## When something is wrong

```sh
systemctl status soulbind-core
journalctl -u soulbind-core -n 100 --no-pager
sudo -u soulbind /opt/soulbind/core/bin/core doctor --config /etc/soulbind/soulbind.toml
```

| Symptom | Usually |
|---|---|
| `missing-capability` in a connector's log | That credential was registered without the capability the operation needs. Re-register or rotate with the right set |
| `unknown-credential` | The credential was rotated, or the connector has the wrong one |
| Requests refused with a signature error | Clock skew between the connector's host and core's beyond the signature window. Fix NTP rather than widening the window |
| Core refuses to start, complaining about the charset | The MariaDB schema is not utf8mb4. See [Using MariaDB](#using-mariadb) |
| Core will not start under systemd but runs by hand | Almost always the unit's hardening against something the config asks for — a path outside `ReadWritePaths=`, or a port under 1024 |
