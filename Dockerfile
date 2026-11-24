# syntax=docker/dockerfile:1
#
# One parameterized multi-stage image for every service in this Maven multi-module repo.
#   docker build --build-arg MODULE=user-service -t ecom/user-service .
# The build context must be the repo root: every module inherits from the root pom.xml.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /workspace

# 1) POMs first. This layer (and the dependency download) is cached until a pom changes,
#    so editing Java code doesn't re-download the internet.
COPY pom.xml .
COPY discovery-client/pom.xml  discovery-client/
COPY api-gateway/pom.xml       api-gateway/
COPY user-service/pom.xml      user-service/
COPY product-service/pom.xml   product-service/
COPY inventory-service/pom.xml inventory-service/
COPY order-service/pom.xml     order-service/
COPY search-service/pom.xml    search-service/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am dependency:go-offline

# 2) Sources of the one module being built
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am package -DskipTests \
 && java -Djarmode=tools -jar ${MODULE}/target/${MODULE}-*.jar \
        extract --layers --launcher --destination /extracted

# ---------- Stage 2: runtime ----------
# JRE only (no JDK, no Maven, no sources) → smaller image, smaller attack surface
FROM eclipse-temurin:21-jre

# curl is used by the compose healthcheck; app runs as an unprivileged user
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system app \
 && useradd --system --gid app --no-create-home app

WORKDIR /app

# Spring Boot layered jar: least-frequently-changing layers first, so a code change
# only replaces the small "application" layer.
COPY --from=build --chown=app:app /extracted/dependencies/          ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/    ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/           ./

USER app

# Size the heap from the container memory limit, and die (so Docker restarts us) on OOM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
