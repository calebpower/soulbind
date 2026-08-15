plugins { id("soulbind.java-21") }

// protocol is deliberately dependency-light. It carries DTOs, the link-code
// alphabet and its normalisation, HMAC request signing and schema version
// constants — the surface the golden vectors pin and the PHP extension
// re-implements. Every dependency added here is a dependency the PHP side must
// somehow mirror, so additions need a reason.
