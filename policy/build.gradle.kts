plugins {
    id("soulbind.java-21")
    id("soulbind.licence-inventory")
}

// NO DEPENDENCIES, and that is the design.
//
// The evaluator is a pure function of (identity graph slice, rules, overrides,
// clock). It has no storage, no transport, no JSON and no logging -- so there
// is nothing for a dependency to be FOR. That emptiness is what makes the Tier
// 4 decision matrix possible: every row calls the function directly, with no
// HTTP and no database, so the matrix can be exhaustive rather than
// representative.
//
// A dependency appearing here is the signal that I/O has crept in. There is no
// guard for that beyond this comment and the empty block below, because the
// dependency-graph guard would need a rule per module and this module's rule is
// simply "none".
//
// Release 21, not 25: connector-sdk caches decisions and needs these types, and
// it loads inside a server operator's JVM.
