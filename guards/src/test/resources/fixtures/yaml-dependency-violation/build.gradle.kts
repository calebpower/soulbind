// MUST-FAIL FIXTURE. Not part of the build; read as text by DependencyGraphGuardTest.
//
// A YAML parser entering the graph is how a project ends up with two config
// formats: one deliberate, one that arrived because a library happened to bring
// a parser and the next config file took the path of least resistance.

plugins { id("soulbind.java-25") }

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
}
