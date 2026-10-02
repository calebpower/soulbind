plugins {
    id("soulbind.licence-inventory")
    id("soulbind.java-25")
}

// No `application` or `soulbind.service-dist` yet: both exist to produce a
// start script around a main class, and this module has no entry point until
// the daemon arrives. Declaring a distribution around a mainClass that does not
// exist would build a start script that fails at run time, and
// DistributionArchiveGuardTest would be asserting the shape of something
// nothing can execute.

dependencies {
    implementation(project(":connector-sdk"))
}
