FROM node:26-slim AS frontend-build

WORKDIR /build

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/css ./css
COPY frontend/js ./frontend/js
COPY frontend/scripts ./frontend/scripts

RUN ./node_modules/.bin/lightningcss \
      --bundle --minify --targets '>= 0.5%' \
      css/index-admin.css -o /build/kotauth-admin.css

RUN ./node_modules/.bin/lightningcss \
      --bundle --minify --targets '>= 0.5%' \
      css/index-auth.css -o /build/kotauth-auth.css

RUN ./node_modules/.bin/lightningcss \
      --bundle --minify --targets '>= 0.5%' \
      css/index-portal-sidenav.css -o /build/kotauth-portal-sidenav.css

RUN ./node_modules/.bin/lightningcss \
      --bundle --minify --targets '>= 0.5%' \
      css/index-portal-tabnav.css -o /build/kotauth-portal-tabnav.css

RUN mkdir -p src/main/resources/static/js src/main/resources
RUN node frontend/scripts/build-js.js
RUN node frontend/scripts/generate-sri.js


FROM eclipse-temurin:24-jdk AS kotlin-build

WORKDIR /app
COPY . .

COPY --from=frontend-build /build/kotauth-admin.css          src/main/resources/static/kotauth-admin.css
COPY --from=frontend-build /build/kotauth-auth.css           src/main/resources/static/kotauth-auth.css
COPY --from=frontend-build /build/kotauth-portal-sidenav.css src/main/resources/static/kotauth-portal-sidenav.css
COPY --from=frontend-build /build/kotauth-portal-tabnav.css  src/main/resources/static/kotauth-portal-tabnav.css

COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-admin.min.js   src/main/resources/static/js/kotauth-admin.min.js
COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-auth.min.js    src/main/resources/static/js/kotauth-auth.min.js
COPY --from=frontend-build /build/src/main/resources/static/js/kotauth-portal.min.js  src/main/resources/static/js/kotauth-portal.min.js
COPY --from=frontend-build /build/src/main/resources/static/js/branding.min.js        src/main/resources/static/js/branding.min.js
COPY --from=frontend-build /build/src/main/resources/js-integrity.properties          src/main/resources/js-integrity.properties

RUN ./gradlew buildFatJar \
      -x installCssDeps \
      -x compileCssAdmin \
      -x compileCssAuth \
      -x compileCssPortalSidenav \
      -x compileCssPortalTabnav \
      -x compileJs \
      -x generateJsSri \
      --no-daemon


FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r -g 10001 kotauth \
    && useradd -r -u 10001 -g kotauth -d /app -s /sbin/nologin kotauth

EXPOSE 8080

COPY --from=kotlin-build --chown=kotauth:kotauth /app/build/libs/*.jar /app/kauth.jar

USER kotauth

ENTRYPOINT ["java", "-jar", "/app/kauth.jar"]
