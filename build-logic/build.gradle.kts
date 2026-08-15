plugins {
    `kotlin-dsl`
}

// build-logic itself compiles with the JDK Gradle runs on; it produces no
// distributed artifact and therefore carries no --release contract.
