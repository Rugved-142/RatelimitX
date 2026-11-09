# ============================================================
# STAGE 1: BUILD
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first so the dependency layer is cached while only sources change.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

# Tests run in CI against real containers; the image build only packages.
RUN mvn package -DskipTests


# ============================================================
# STAGE 2: RUN
# ============================================================
FROM eclipse-temurin:21-jre

# curl is needed by HEALTHCHECK below; the JRE image does not ship it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user: a process that never needs to write to the image should not be
# able to. The JAR is owned by root and only readable by the app user.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

USER app:app

# 8080 serves the API. 9091 serves actuator and must not be published.
EXPOSE 8080
EXPOSE 9091

# Probes the management port, which is reachable inside the container only.
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:9091/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
