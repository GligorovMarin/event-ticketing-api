# Dockerfile
#
# Multi-stage build, referenced by docker-compose.yml (`target: dev`).
#   - dev  : Maven + JDK, used locally and in CI to run the app and the tests
#   - build: compiles the code into one runnable jar
#   - prod : small runtime image (JRE only, non-root user) containing just that jar

# ---------- Stage 1: development ----------
FROM maven:3.9-eclipse-temurin-21 AS dev
WORKDIR /app

# Copy ONLY pom.xml first and download the dependencies. Docker caches each step, so this slow
# download is repeated only when pom.xml changes, not on every code edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
EXPOSE 8080
CMD ["mvn", "-B", "spring-boot:run"]

# ---------- Stage 2: build ----------
FROM dev AS build
# Tests run in CI (`mvn verify`); here we only need the jar.
RUN mvn -B -q -DskipTests package

# ---------- Stage 3: production ----------
FROM eclipse-temurin:21-jre AS prod
WORKDIR /app

# Run as an unprivileged user, never as root (see the same note in the clinic project's Dockerfile).
RUN useradd --system --uid 10001 --no-create-home appuser

COPY --from=build /app/target/ticketing-*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
