# ============================================================
# STAGE 1: BUILD
# ============================================================
# Start with Maven + Java 21 image (has everything to compile)
FROM maven:3.9-eclipse-temurin-21 AS build

# Set working directory inside container
WORKDIR /app

# Copy pom.xml first (for dependency caching)
COPY pom.xml .

# Download all dependencies
# This layer is cached - won't re-download if pom.xml unchanged
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application (create JAR file)
# Skip tests for faster builds
RUN mvn package -DskipTests


# ============================================================
# STAGE 2: RUN
# ============================================================
# Start fresh with smaller image (only Java runtime, no compiler)
FROM eclipse-temurin:21-jre

# Set working directory
WORKDIR /app

# Copy ONLY the JAR from build stage (not source code, not Maven)
COPY --from=build /app/target/*.jar app.jar

# Document that app uses port 8080
EXPOSE 8080

# Command to run when container starts
ENTRYPOINT ["java", "-jar", "app.jar"]