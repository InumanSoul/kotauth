# ── Stage 1: CSS compilation ──────────────────────────────────────────────
# Uses Node.js only to install lightningcss-cli (an npm package that ships
# a native Rust binary). Node is not present in the runtime image.
# Output: four CSS bundles written to /build/
FROM node:20-slim AS css-build

WORKDIR /build

# Install dependencies first — this layer is cached until package-lock.json changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Copy CSS source and compile all bundles
COPY frontend/css ./css

# Admin console bundle (fixed dark theme — tokens.css provides all defaults)
RUN ./node_modules/.bin/lightningcss \
      --bundle \
      --minify \
      --targets '>= 0.5%' \
      css/index-admin.css \
      -o /build/kotauth-admin.css

# Auth pages bundle (tenant-themeable — no :root defaults, TenantTheme injects them at runtime)
RUN ./node_modules/.bin/lightningcss \
      --bundle \
      --minify \
      --targets '>= 0.5%' \
      css/index-auth.css \
      -o /build/kotauth-auth.css

# Portal sidebar layout bundle
RUN ./node_modules/.bin/lightningcss \
      --bundle \
      --minify \
      --targets '>= 0.5%' \
      css/index-portal-sidenav.css \
      -o /build/kotauth-portal-sidenav.css

# Portal centered/tab layout bundle
RUN ./node_modules/.bin/lightningcss \
      --bundle \
      --minify \
      --targets '>= 0.5%' \
      css/index-portal-tabnav.css \
      -o /build/kotauth-portal-tabnav.css


# ── Stage 2: Kotlin / Gradle build ────────────────────────────────────────
FROM gradle:8-jdk17 AS kotlin-build

WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .

# Inject the compiled CSS bundles so Gradle embeds them in the JAR.
# All CSS tasks are skipped because the output files are already present from Stage 1.
COPY --from=css-build /build/kotauth-admin.css          src/main/resources/static/kotauth-admin.css
COPY --from=css-build /build/kotauth-auth.css           src/main/resources/static/kotauth-auth.css
COPY --from=css-build /build/kotauth-portal-sidenav.css src/main/resources/static/kotauth-portal-sidenav.css
COPY --from=css-build /build/kotauth-portal-tabnav.css  src/main/resources/static/kotauth-portal-tabnav.css

RUN gradle buildFatJar \
      -x installCssDeps \
      -x compileCssAdmin \
      -x compileCssAuth \
      -x compileCssPortalSidenav \
      -x compileCssPortalTabnav \
      --no-daemon


# ── Stage 3: Runtime ───────────────────────────────────────────────────────
# Eclipse Temurin JRE — no build tools, no Node.js, no LightningCSS.
# Final image: ~65 MB base + ~15-20 MB JAR ≈ 85 MB total.
FROM eclipse-temurin:17-jre

# Install curl for Docker HEALTHCHECK — not present in the base JRE image.
# The health probe hits /health/ready which verifies DB connectivity and config.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

COPY --from=kotlin-build /home/gradle/src/build/libs/*.jar /app/kauth.jar

ENTRYPOINT ["java", "-jar", "/app/kauth.jar"]
