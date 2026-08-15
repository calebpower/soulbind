// MUST-FAIL FIXTURE. Not part of the build; read as text by ReleaseLevelGuardTest.
//
// This is what connector-velocity's build file would look like if someone gave
// it the wrong convention plugin. The consequence is not a build failure -- it
// compiles perfectly. It fails at class-load time inside a proxy JVM with
// UnsupportedClassVersionError, long after the mistake was made.

plugins { id("soulbind.java-25") }

dependencies {
    implementation(project(":connector-sdk"))
}
