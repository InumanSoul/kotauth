# ── Stage 1: CSS + JS compilation ─────────────────────────────────────────
# Uses Node.js for LightningCSS (CSS) and esbuild (JS).
# Node is not present in the runtime image.
# Output: four CSS bundles + four JS bundles + SRI hashes written to /build/
FROM node:20-slim AS frontend-build

WORKDIR /build

# Install dependencies first — this layer is cached until package-lock.json changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Copy CSS + JS source and build scripts
COPY frontend/css ./css
COPY frontend/js ./js
COPY frontend/scripts ./scripts

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

# JS bundles (esbuild concatenation + minification)
# Create the output directories that the build scripts expect
RUN mkdir -p src/main/resources/static/js src/main/resources
RUN node scripts/build-js.js
RUN node scripts/generate-sri.js


# ── Stage 2: Kotlin / Gradle build ────────────────────────────────────────
FROM gradle:8-jdk17 AS kotlin-build

WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .

# Inject the compiled CSS bundles so Gradle embeds them in the JAR.
# All CSS tasks are skipped because the output files are already present from Stage 1.
# CSS bundles from frontend build stage
COPY --from=frontend-build /build/kotauth-admin.css          src/main/resources/static/kotauth-admin.css
COPY --from=frontend-build /build/kotauth-auth.css           src/main/resources/static/kotauth-auth.css
COPY --from=frontend-build /build/kotauth-portal-sidenav.css src/main/resources/static/kotauth-portal-sidenav.css
COPY --from=frontend-build /build/kotauth-portal-tabnav.css  src/main/resources/static/kotauth-portal-tabnav.css

# JS bundles + SRI hashes from frontend build stage
COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-admin.js   src/main/resources/static/js/kotauth-admin.js
COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-auth.js    src/main/resources/static/js/kotauth-auth.js
COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-portal.js  src/main/resources/static/js/kotauth-portal.js
COPY --from=frontend-build /build/src/main/resources/static/js/branding.min.js    src/main/resources/static/js/branding.min.js
COPY --from=frontend-build /build/src/main/resources/js-integrity.properties      src/main/resources/js-integrity.properties

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
