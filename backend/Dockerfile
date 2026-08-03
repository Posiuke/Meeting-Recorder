# Kombiniertes Image: Frontend (React) + Backend (Spring Boot) in einem Container.
# Spring Boot liefert die gebaute React-App als statische Ressourcen aus,
# API und UI teilen sich damit Port 8080. Build-Kontext ist das Repo-Root.

# --- Frontend-Build ---
FROM node:22-alpine AS frontend
WORKDIR /fe
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# --- Backend-Build (bindet das Frontend als statische Ressourcen ein) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -q dependency:go-offline
COPY backend/src ./src
# Gebautes Frontend nach src/main/resources/static -> landet im JAR
COPY --from=frontend /fe/dist ./src/main/resources/static
RUN mvn -q -DskipTests package

# --- Runtime: Playwright-Java-Image bringt Chromium + alle Browser-Abhaengigkeiten mit ---
FROM mcr.microsoft.com/playwright/java:v1.52.0-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/bbb-bot-backend-*.jar app.jar

ENV STORAGE_DIR=/data/recordings
VOLUME /data/recordings
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
