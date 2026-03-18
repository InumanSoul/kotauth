val ktorVersion = "2.3.12"
val exposedVersion = "0.50.1"
val logbackVersion = "1.4.14"
val flywayVersion = "9.22.3"
val logstashEncoderVersion = "7.4"

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "com.kauth"
version = "1.0.0"

application {
    mainClass.set("com.kauth.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.postgresql:postgresql:42.7.3")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("at.favre.lib:bcrypt:0.10.2")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    // Phase 3b — SMTP email delivery (JavaMail, no wrapper libraries)
    implementation("com.sun.mail:javax.mail:1.6.2")

    // ---- Test dependencies ----
    // Ktor in-memory test engine — full routing + serialisation, no real server or DB
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    // Kotlin test assertions + JUnit 5 runner
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // MockK — Kotlin-native mocking (HTTP integration tests only)
    testImplementation("io.mockk:mockk:1.13.10")
    // JUnit 5 engine
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set("1.3.1")
    android.set(false)
    outputToConsole.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
        // kotlinx.html DSL files — deeply nested lambdas create non-converging
        // formatter loops between the indent and wrapping rules. These files are
        // presentation-only; all business logic is linted normally.
        exclude("**/*View.kt")
    }
}
