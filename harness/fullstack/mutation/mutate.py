"""Applies one named mutation to a recorded Plan response.

    mutate.py <source-json> <destination> <mutant-name>

Every mutation here changes a VALUE the check claims to assert on. None of them
changes the shape: a mutant that produced malformed JSON would be killed by the
parser rather than by the assertion, and would say nothing about whether the
assertion works.

Unknown names are an error rather than a no-op. A typo'd mutant that silently
copies the file through is a mutant that "survives" every time and reads as a
finding about the check.
"""
import json
import sys

source, destination, name = sys.argv[1], sys.argv[2], sys.argv[3]
with open(source, encoding="utf-8") as handle:
    doc = json.load(handle)


def provider(node, wanted):
    """Plan nests the provider name under `description`; `value` is its sibling."""
    if isinstance(node, dict):
        described = node.get("description")
        if isinstance(described, dict) and described.get("name") == wanted \
                and "value" in node:
            return node
        for value in node.values():
            found = provider(value, wanted)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = provider(value, wanted)
            if found is not None:
                return found
    return None


def require(wanted):
    found = provider(doc, wanted)
    if found is None:
        raise SystemExit(
            "mutant '%s' targets provider '%s', which is not in %s. The fixture and"
            " the catalogue have drifted; re-record or fix the name."
            % (name, wanted, source))
    return found


def strip_table_columns(node):
    hit = False
    if isinstance(node, dict):
        if node.get("tableName") == "unlinkedTable":
            node.setdefault("table", {})["columns"] = []
            hit = True
        for value in node.values():
            hit = strip_table_columns(value) or hit
    elif isinstance(node, list):
        for value in node:
            hit = strip_table_columns(value) or hit
    return hit


MUTANTS = {
    "linked-false": lambda: require("linked").__setitem__("value", False),
    "linked-null": lambda: require("linked").__setitem__("value", None),
    "status-not-linked": lambda: require("linkStatus").__setitem__("value", "not linked"),
    "status-unknown": lambda: require("linkStatus").__setitem__(
        "value", "unknown (core unreachable)"),
    "platforms-forum-only": lambda: require("platforms").__setitem__("value", "forum"),
    "platforms-placeholder": lambda: require("platforms").__setitem__("value", "-"),
    "proof-null": lambda: require("proof").__setitem__("value", None),
    "proof-placeholder": lambda: require("proof").__setitem__("value", "-"),
    "since-seconds": lambda: require("linkedSince").__setitem__("value", 1787201694),
    "since-zero": lambda: require("linkedSince").__setitem__("value", 0),
    "aggregate-zero": lambda: require("linked_aggregate").__setitem__("value", "0%"),
    "counters-non-numeric": lambda: require("linkedPlayers").__setitem__("value", "-"),
    "table-no-columns": lambda: (_ for _ in ()).throw(SystemExit(
        "table-no-columns found no unlinkedTable")) if not strip_table_columns(doc) else None,
}

if name not in MUTANTS:
    raise SystemExit("unknown mutant '%s'; known: %s"
                     % (name, ", ".join(sorted(MUTANTS))))
MUTANTS[name]()

with open(destination, "w", encoding="utf-8") as handle:
    json.dump(doc, handle)
