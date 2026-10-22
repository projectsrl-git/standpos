# syntax=docker/dockerfile:1
# StandPOS backend. Build context: repository root.
#   docker buildx build -f backend/Dockerfile --platform linux/amd64,linux/arm64 .

# Bytecode is platform-independent: build once on the native platform, no emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY print-protocol print-protocol
COPY print-agent/pom.xml print-agent/pom.xml
COPY backend backend
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -q -DskipTests -pl backend -am package \
    && java -Djarmode=tools -jar backend/target/standpos-backend.jar extract --destination /app

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app standpos
WORKDIR /app
COPY --from=build /app /app
USER standpos
EXPOSE 8080
# Extra arguments are passed to the application, e.g. `--bootstrap`.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/standpos-backend.jar"]
