# syntax=docker/dockerfile:1
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer: only re-resolves when the pom changes.
COPY pom.xml .
# The cache mount matters here: this machine's connection drops mid-resolve
# often enough that without it a failed build re-downloads the whole tree.
RUN --mount=type=cache,target=/root/.m2/repository     mvn -B -q dependency:go-offline

COPY src ./src

# Tests run as part of the image build, because this is the only place with a
# working route to Maven Central in this environment. Pass --build-arg
# SKIP_TESTS=true for a fast iteration build.
ARG SKIP_TESTS=false
RUN --mount=type=cache,target=/root/.m2/repository     mvn -B clean package -DskipTests=${SKIP_TESTS}

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# wget backs the compose healthcheck
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

# The media volume is shared with the render service. Both images pin the same
# GID and the directory is group-writable + setgid, so whichever container
# initialises the named volume, the other can still write into it.
RUN groupadd --gid 10100 pvmedia \
 && useradd --system --create-home --uid 10001 --gid 10100 paperviz
COPY --from=build /build/target/paperviz-backend-*.jar /app/app.jar

RUN mkdir -p /data/media \
 && chown -R paperviz:pvmedia /data/media /app \
 && chmod 2775 /data/media
USER paperviz

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
