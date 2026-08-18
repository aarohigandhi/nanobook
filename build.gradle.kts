plugins {
    java
    application
}

group = "io.nanobook"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.nanobook.Main"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Replay an ITCH file:  ./gradlew replay --args="data/sample.itch"
tasks.register<JavaExec>("replay") {
    group = "nanobook"
    description = "Replay an ITCH 5.0 file through the parser."
    mainClass = "io.nanobook.Main"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Xmx2g")
}

// Prove zero-allocation: Epsilon GC never reclaims, so any garbage kills the run.
// ./gradlew replayEpsilon --args="data/sample.itch"
tasks.register<JavaExec>("replayEpsilon") {
    group = "nanobook"
    description = "Replay under Epsilon GC. If it survives, the hot path allocates nothing."
    mainClass = "io.nanobook.Main"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseEpsilonGC",
        "-Xmx512m",
        "-Xlog:gc"
    )
}
