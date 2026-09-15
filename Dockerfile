# ---- Build stage: compile the fat JAR with Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies separately from source changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- Runtime stage: minimal JRE 21, non-root ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /app/target/*.jar app.jar

USER app

# Cloud Run injects PORT; Spring reads it via server.port=${SERVER_PORT:${PORT:8090}}
EXPOSE 8090

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
