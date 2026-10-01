FROM eclipse-temurin:25-jdk-noble AS build

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B package

FROM node:22-bookworm-slim AS node

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates ffmpeg python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/yt-dlp \
    && /opt/yt-dlp/bin/pip install --no-cache-dir 'yt-dlp[default]'
COPY --from=node /usr/local/bin/node /usr/local/bin/node
ENV PATH="/opt/yt-dlp/bin:${PATH}" \
    QUARKUS_HTTP_HOST=0.0.0.0
WORKDIR /app
COPY --from=build /workspace/target/quarkus-app/ /app/
RUN useradd --system --uid 10001 --home-dir /app appuser \
    && chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
