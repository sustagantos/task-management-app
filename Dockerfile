# syntax=docker/dockerfile:1

# 1. Build the SPA. Its output is copied into the jar's static resources, so
#    one artifact serves both API and UI from one origin - no CORS, no second
#    container, no separate deploy to keep in step.
FROM node:22-alpine AS frontend
WORKDIR /fe
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# 2. Build the jar.
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
# Dependencies resolve in their own layer so a source-only change does not
# re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ ./src/
COPY --from=frontend /fe/dist/ ./src/main/resources/static/
RUN mvn -B -DskipTests package

# 3. Run it.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=backend /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
