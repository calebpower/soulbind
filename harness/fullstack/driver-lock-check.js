// Compares the installed player-driver tree against its committed lock.
//
// Exit 0 when every package the lock names is installed at the locked version;
// exit 1 otherwise, printing what differs. One implementation, used both to
// decide whether an install is needed and to explain a failure -- a second copy
// for the explanation would be a second definition of "matches", and the two
// would disagree the first time one changed.
//
// Node rather than shell: the lock is JSON, and this repository has been bitten
// enough times by hand-parsing structured formats.
//
// NARROWINGS, stated rather than implied. This checks lock ⊆ installed, by
// VERSION:
//
//   * it does not check installed ⊆ lock, so a package left over from an older
//     resolution passes unnoticed. It is a pre-install probe, not an audit;
//   * it compares `version` only, never `resolved` or `integrity`, so the right
//     version from a different registry passes.
//
// Both are acceptable for deciding "is an install needed", and neither would be
// acceptable as a supply-chain check. `npm ci` does the stricter job when this
// says an install is needed.
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2];
if (!root) {
  console.error("usage: driver-lock-check.js <player-driver-dir>");
  process.exit(2);
}

const lock = JSON.parse(fs.readFileSync(path.join(root, "package-lock.json"), "utf8"));
const problems = [];
let checked = 0;

for (const [where, meta] of Object.entries(lock.packages || {})) {
  if (!where.startsWith("node_modules/") || !meta.version) continue;
  checked += 1;
  let installed = null;
  try {
    installed = JSON.parse(
      fs.readFileSync(path.join(root, where, "package.json"), "utf8")
    ).version;
  } catch {
    problems.push(`  missing: ${where} (lock wants ${meta.version})`);
    continue;
  }
  if (installed !== meta.version) {
    problems.push(`  ${where}: installed ${installed}, lock wants ${meta.version}`);
  }
}

// A lock naming nothing would otherwise pass vacuously.
if (checked === 0) {
  console.error("  the lock names no packages at all");
  process.exit(1);
}
if (problems.length > 0) {
  problems.forEach((p) => console.error(p));
  process.exit(1);
}
process.exit(0);
