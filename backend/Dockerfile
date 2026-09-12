# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer: only re-resolves when the pom changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# wget backs the compose healthcheck
RUN apt-get update \
 && apt-get install -y --no-install-recommends wget \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 paperviz
COPY --from=build /build/target/paperviz-backend-*.jar /app/app.jar

RUN mkdir -p /data/media && chown -R paperviz:paperviz /data/media /app
USER paperviz

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
