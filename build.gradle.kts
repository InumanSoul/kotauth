val ktorVersion = "3.4.2"
val exposedVersion = "0.61.0"
val logbackVersion = "1.5.32"
val flywayVersion = "12.4.0"
val logstashEncoderVersion = "8.1"
val lettuceVersion = "6.5.5.RELEASE"
val testcontainersVersion = "1.21.0"

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("io.ktor.plugin") version "3.4.2"
    id("com.gradleup.shadow") version "9.1.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

group = "com.kauth"
version = "1.11.0"

application {
    mainClass.set("com.kauth.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.postgresql:postgresql:42.7.10")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("com.auth0:java-jwt:4.5.1")
    implementation("at.favre.lib:bcrypt:0.10.2")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")
    implementation("com.sun.mail:javax.mail:1.6.2")

    implementation("io.lettuce:lettuce-core:$lettuceVersion")

    // ---- Test dependencies ----
    // Ktor in-memory test engine
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    // Kotlin test assertions + JUnit 5 runner
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // MockK — Kotlin-native mocking (HTTP integration tests only)
    testImplementation("io.mockk:mockk:1.13.16")
    // JUnit 5 engine
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.5")
    // Testcontainers — Redis integration tests (tagged @Tag("redis"); excluded from `make test`)
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("redis")
    }
}

tasks.register<Test>("redisTest") {
    description = "Runs Redis-backed integration tests (Testcontainers, Docker required)"
    group = "verification"
    useJUnitPlatform {
        includeTags("redis")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Forward Docker-related env vars (DOCKER_HOST for OrbStack/Colima/etc.,
    // DOCKER_API_VERSION to override docker-java's stale default).
    listOf("DOCKER_HOST", "DOCKER_API_VERSION", "TESTCONTAINERS_RYUK_DISABLED").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // docker-java reads `api.version` as a JVM system property (env var alone is not honored).
    System.getenv("DOCKER_API_VERSION")?.let { systemProperty("api.version", it) }
}

// ── E2E smoke tests (Playwright) ─────────────────────────────────────────
sourceSets {
    create("e2eTest") {
        kotlin.srcDir("src/e2eTest/kotlin")
        resources.srcDir("src/e2eTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

val e2eTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val e2eTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    e2eTestImplementation("com.microsoft.playwright:playwright:1.44.0")
}

tasks.register<Test>("e2eTest") {
    description = "Runs E2E browser smoke tests (Playwright)"
    group = "verification"
    testClassesDirs = sourceSets["e2eTest"].output.classesDirs
    classpath = sourceSets["e2eTest"].runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("playwright.headless", System.getProperty("playwright.headless", "true"))
}

// ── CSS compilation ───────────────────────────────────────────────────────
val lightningCssBin = "frontend/node_modules/.bin/lightningcss"

// Step 1: install lightningcss-cli from frontend/package-lock.json (once, then cached)
val installCssDeps =
    tasks.register<Exec>("installCssDeps") {
        description = "Installs LightningCSS CLI into frontend/node_modules via npm ci"
        group = "build"

        commandLine("npm", "ci", "--prefix", "frontend")

        inputs.files("frontend/package.json", "frontend/package-lock.json")
        outputs.dir("frontend/node_modules")
    }

// Step 2a: compile admin bundle
val compileCssAdmin =
    tasks.register<Exec>("compileCssAdmin") {
        description = "Compiles the admin console CSS bundle (frontend/css/index-admin.css → kotauth-admin.css)"
        group = "build"
        dependsOn(installCssDeps)

        commandLine(
            lightningCssBin,
            "--bundle",
            "--minify",
            "--targets",
            ">= 0.5%",
            "frontend/css/index-admin.css",
            "-o",
            "src/main/resources/static/kotauth-admin.css",
        )

        inputs.dir("frontend/css")
        outputs.file("src/main/resources/static/kotauth-admin.css")
    }

// Step 2b: compile auth bundle
val compileCssAuth =
    tasks.register<Exec>("compileCssAuth") {
        description = "Compiles the auth pages CSS bundle (frontend/css/index-auth.css → kotauth-auth.css)"
        group = "build"
        dependsOn(installCssDeps)

        commandLine(
            lightningCssBin,
            "--bundle",
            "--minify",
            "--targets",
            ">= 0.5%",
            "frontend/css/index-auth.css",
            "-o",
            "src/main/resources/static/kotauth-auth.css",
        )

        inputs.dir("frontend/css")
        outputs.file("src/main/resources/static/kotauth-auth.css")
    }

// Step 2c: compile portal-sidenav bundle
val compileCssPortalSidenav =
    tasks.register<Exec>("compileCssPortalSidenav") {
        description =
            "Compiles the portal sidebar CSS bundle (frontend/css/index-portal-sidenav.css → kotauth-portal-sidenav.css)"
        group = "build"
        dependsOn(installCssDeps)

        commandLine(
            lightningCssBin,
            "--bundle",
            "--minify",
            "--targets",
            ">= 0.5%",
            "frontend/css/index-portal-sidenav.css",
            "-o",
            "src/main/resources/static/kotauth-portal-sidenav.css",
        )

        inputs.dir("frontend/css")
        outputs.file("src/main/resources/static/kotauth-portal-sidenav.css")
    }

// Step 2d: compile portal-tabnav bundle
val compileCssPortalTabnav =
    tasks.register<Exec>("compileCssPortalTabnav") {
        description =
            "Compiles the portal centered-tabs CSS bundle (frontend/css/index-portal-tabnav.css → kotauth-portal-tabnav.css)"
        group = "build"
        dependsOn(installCssDeps)

        commandLine(
            lightningCssBin,
            "--bundle",
            "--minify",
            "--targets",
            ">= 0.5%",
            "frontend/css/index-portal-tabnav.css",
            "-o",
            "src/main/resources/static/kotauth-portal-tabnav.css",
        )

        inputs.dir("frontend/css")
        outputs.file("src/main/resources/static/kotauth-portal-tabnav.css")
    }

// ── JS compilation ────────────────────────────────────────────────────────
// Uses the same installCssDeps task (npm ci) — esbuild is in the same package.json.

val compileJs =
    tasks.register<Exec>("compileJs") {
        description = "Compiles and minifies JS bundles via esbuild"
        group = "build"
        dependsOn(installCssDeps)

        commandLine("node", "frontend/scripts/build-js.js")

        inputs.dir("frontend/js")
        outputs.files(
            "src/main/resources/static/js/kotauth-admin.min.js",
            "src/main/resources/static/js/kotauth-auth.min.js",
            "src/main/resources/static/js/kotauth-portal.min.js",
            "src/main/resources/static/js/branding.min.js",
        )
    }

val generateJsSri =
    tasks.register<Exec>("generateJsSri") {
        description = "Generates js-integrity.properties with SHA-256 SRI hashes for each JS bundle"
        group = "build"
        dependsOn(compileJs)

        commandLine("node", "frontend/scripts/generate-sri.js")

        inputs.files(
            "src/main/resources/static/js/kotauth-admin.min.js",
            "src/main/resources/static/js/kotauth-auth.min.js",
            "src/main/resources/static/js/kotauth-portal.min.js",
            "src/main/resources/static/js/branding.min.js",
        )
        outputs.file("src/main/resources/js-integrity.properties")
    }

// ── Version properties ────────────────────────────────────────────────────
// Generates src/main/resources/version.properties so the running application
// can report its own version without parsing build files at runtime.
// The file is listed in .gitignore — it is produced fresh on every build.
abstract class GenerateVersionPropertiesTask : DefaultTask() {
    @get:Input abstract val appVersion: Property<String>

    @get:Input abstract val ktVersion: Property<String>

    @get:Input abstract val ktorVer: Property<String>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            app.version=${appVersion.get()}
            kotlin.version=${ktVersion.get()}
            ktor.version=${ktorVer.get()}
            """.trimIndent(),
        )
    }
}

val generateVersionProperties =
    tasks.register<GenerateVersionPropertiesTask>("generateVersionProperties") {
        description = "Generates version.properties with app and runtime version metadata"
        group = "build"
        appVersion.set(project.version.toString())
        ktVersion.set("2.3.20")
        ktorVer.set(ktorVersion)
        outputFile.set(layout.projectDirectory.file("src/main/resources/version.properties"))
    }

// All CSS bundles and version properties must be ready before resources are packaged into the JAR
tasks.named("processResources") {
    dependsOn(
        compileCssAdmin,
        compileCssAuth,
        compileCssPortalSidenav,
        compileCssPortalTabnav,
        generateVersionProperties,
        generateJsSri,
    )
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
    }
}
