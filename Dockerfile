# Stage 1: Build phase using Gradle
FROM gradle:8-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle buildFatJar --no-daemon

# Stage 2: Runtime phase using Eclipse Temurin (The replacement for OpenJDK)
FROM eclipse-temurin:17-jre
# Install curl for Docker HEALTHCHECK — not present in the base JRE image.
# The health probe hits /health/ready which verifies DB connectivity and config.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
EXPOSE 8080
# Copy the fat JAR produced in the build stage
COPY --from=build /home/gradle/src/build/libs/*.jar /app/kauth.jar
ENTRYPOINT ["java", "-jar", "/app/kauth.jar"]
