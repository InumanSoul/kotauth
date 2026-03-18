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

// ── CSS compilation ───────────────────────────────────────────────────────
// LightningCSS bundles + minifies the source CSS in frontend/css/ and writes
// the compiled output to src/main/resources/static/ so it is embedded in the
// fat JAR by processResources.
//
// Two bundles are compiled separately:
//   index-admin.css → kotauth-admin.css  (admin console, fixed dark theme)
//   index-auth.css  → kotauth-auth.css   (auth pages, tenant-themeable)
//
// LightningCSS is installed via npm into frontend/node_modules/ (no global
// install required). The installCssDeps task handles this automatically —
// it only re-runs when frontend/package.json or package-lock.json change.
//
// In Docker (multi-stage), the CSS is compiled in a dedicated css-build stage
// (node:20-slim) and copied into place before Gradle runs, so all three CSS
// tasks are skipped with -x flags in the kotlin-build stage. See Dockerfile.

// Local lightningcss binary installed by npm into frontend/node_modules
val lightningCssBin = "frontend/node_modules/.bin/lightningcss"

// Step 1: install lightningcss-cli from frontend/package-lock.json (once, then cached)
val installCssDeps = tasks.register<Exec>("installCssDeps") {
    description = "Installs LightningCSS CLI into frontend/node_modules via npm ci"
    group = "build"

    commandLine("npm", "ci", "--prefix", "frontend")

    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
}

// Step 2a: compile admin bundle
val compileCssAdmin = tasks.register<Exec>("compileCssAdmin") {
    description = "Compiles the admin console CSS bundle (frontend/css/index-admin.css → kotauth-admin.css)"
    group = "build"
    dependsOn(installCssDeps)

    commandLine(
        lightningCssBin,
        "--bundle",
        "--minify",
        "--targets", ">= 0.5%",
        "frontend/css/index-admin.css",
        "-o", "src/main/resources/static/kotauth-admin.css",
    )

    inputs.dir("frontend/css")
    outputs.file("src/main/resources/static/kotauth-admin.css")
}

// Step 2b: compile auth bundle
val compileCssAuth = tasks.register<Exec>("compileCssAuth") {
    description = "Compiles the auth pages CSS bundle (frontend/css/index-auth.css → kotauth-auth.css)"
    group = "build"
    dependsOn(installCssDeps)

    commandLine(
        lightningCssBin,
        "--bundle",
        "--minify",
        "--targets", ">= 0.5%",
        "frontend/css/index-auth.css",
        "-o", "src/main/resources/static/kotauth-auth.css",
    )

    inputs.dir("frontend/css")
    outputs.file("src/main/resources/static/kotauth-auth.css")
}

// Both CSS bundles must be ready before resources are packaged into the JAR
tasks.named("processResources") {
    dependsOn(compileCssAdmin, compileCssAuth)
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
