# Kombiniertes Image: Frontend (React) + Backend (Spring Boot) in einem Container.
# Spring Boot liefert die gebaute React-App als statische Ressourcen aus,
# API und UI teilen sich damit Port 8080. Build-Kontext ist das Repo-Root.
#
# Zwei Varianten:
#   docker build -f backend/Dockerfile .                 volles Image (Standard)
#   docker build -f backend/Dockerfile --target slim .   ohne Chromium/Playwright
# Das schlanke Image ist fuer Betrieb OHNE BigBlueButton-Bots (BBB_ON aus):
# Upload, Bildschirmaufnahme, Transkription, Auswertung. Es ist ein Bruchteil
# so gross und startet entsprechend schneller (z.B. bei Scale-to-zero).

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

# --- Runtime schlank: nur Java + ffmpeg, keine Bots ---
FROM eclipse-temurin:21-jre-noble AS slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app recorder \
    && mkdir -p /data/recordings \
    && chown -R recorder /data

WORKDIR /app
COPY --from=build /build/target/bbb-bot-backend-*.jar app.jar

ENV STORAGE_DIR=/data/recordings \
    IMAGE_VARIANT=slim
VOLUME /data/recordings
EXPOSE 8080
USER recorder

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# --- Runtime voll: Playwright-Java-Image bringt Chromium + alle Browser-Abhaengigkeiten mit ---
FROM mcr.microsoft.com/playwright/java:v1.52.0-noble AS full
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/bbb-bot-backend-*.jar app.jar

ENV STORAGE_DIR=/data/recordings
VOLUME /data/recordings
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
