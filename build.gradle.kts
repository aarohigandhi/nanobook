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

// The library itself has no runtime dependencies. Benchmarking tools live in
// their own source set so JMH and HdrHistogram never reach the shipped jar.
sourceSets {
    create("benchmarks") {
        java.srcDir("src/benchmarks/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val benchmarksImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val benchmarksAnnotationProcessor: Configuration by configurations.getting

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The benchmark harness is on the test classpath so its workload generators
    // can be tested. A generator that emits a different message count than the
    // benchmark declares silently rescales every ns/op figure it produces, and
    // nothing else in the build would catch that.
    testImplementation(sourceSets["benchmarks"].output)

    benchmarksImplementation("org.openjdk.jmh:jmh-core:1.37")
    benchmarksImplementation("org.hdrhistogram:HdrHistogram:2.2.2")
    benchmarksAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
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

// Replay an ITCH file:
//   ./gradlew replay --args="data/sample.itch"
//   ./gradlew replay --args="data/sample.itch --book AAPL"
tasks.register<JavaExec>("replay") {
    group = "nanobook"
    description = "Replay an ITCH 5.0 file through the parser and, optionally, a book."
    mainClass = "io.nanobook.Main"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Xmx2g")
}

// Prove zero-allocation: Epsilon GC never reclaims, so any steady-state garbage
// exhausts the heap and kills the run.
//   ./gradlew replayEpsilon --args="data/sample.itch --book AAPL"
tasks.register<JavaExec>("replayEpsilon") {
    group = "nanobook"
    description = "Replay under Epsilon GC. If it survives, the hot path allocates nothing."
    mainClass = "io.nanobook.Main"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseEpsilonGC",
        "-Xmx1g",
        "-Xlog:gc"
    )
}

// Differential fuzz the matching engine against the reference implementation:
//   ./gradlew fuzz --args="200000 400"
tasks.register<JavaExec>("fuzz") {
    group = "nanobook"
    description = "Fuzz MatchingEngine against ReferenceMatchingEngine."
    mainClass = "io.nanobook.tools.EngineFuzzer"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = listOf("-Xmx2g")
}

// Open-loop latency measurement:
//   ./gradlew latency --args="2000000 250000"
tasks.register<JavaExec>("latency") {
    group = "nanobook"
    description = "Measure order-to-report latency open-loop, reported as percentiles."
    mainClass = "io.nanobook.bench.LatencyHarness"
    classpath = sourceSets["benchmarks"].runtimeClasspath
    jvmArgs = listOf("-Xmx2g")
}

// The unfakeable zero-allocation proof: Epsilon never reclaims, so any
// steady-state garbage exhausts the heap and kills the run.
//   ./gradlew latencyEpsilon
tasks.register<JavaExec>("latencyEpsilon") {
    group = "nanobook"
    description = "Run the latency harness under Epsilon GC. Surviving proves zero steady-state allocation."
    mainClass = "io.nanobook.bench.LatencyHarness"
    classpath = sourceSets["benchmarks"].runtimeClasspath
    jvmArgs = listOf(
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseEpsilonGC",
        "-Xmx512m"
    )
}

// JMH microbenchmarks:
//   ./gradlew jmh
//   ./gradlew jmh --args="ArrayOrderBook -f 1 -wi 3 -i 5"
tasks.register<JavaExec>("jmh") {
    group = "nanobook"
    description = "Run the JMH microbenchmarks."
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets["benchmarks"].runtimeClasspath
    jvmArgs = listOf("-Xmx2g")
}
