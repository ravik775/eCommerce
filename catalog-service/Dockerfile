# syntax=docker/dockerfile:1
# Multi-stage build for catalog-service (Phase 5, ADR-0026).
# Build context must be the repo root, not this directory — the Maven
# reactor needs every module's pom.xml present to resolve the dependency
# graph even when -am filters the actual build down to this module +
# common-lib. See docker-compose.yml: `context: .`, `dockerfile:
# catalog-service/Dockerfile`.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY common-lib common-lib
COPY api-gateway api-gateway
COPY catalog-service catalog-service
COPY config-server config-server
COPY inventory-service inventory-service
COPY notification-service notification-service
COPY order-service order-service
COPY payment-service payment-service
COPY service-discovery service-discovery
COPY user-service user-service
COPY otel-agent-extension otel-agent-extension
# Cache mount, not a layer: persists ~/.m2 across builds even when an
# upstream COPY layer invalidates (any module's source changing busts
# every other service's Dockerfile cache too, since all 9 modules are
# copied into every build context) — without this, a one-line change in
# any module forced a full from-scratch dependency-resolution+compile of
# the whole reactor for every service, observed taking ~5min each.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -q -pl catalog-service -am package -DskipTests -Dassembly.skipAssembly=true
RUN mkdir -p /workspace/extracted && \
    java -Djarmode=layertools -jar /workspace/catalog-service/target/catalog-service-*.jar extract --destination /workspace/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER spring:spring
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8083
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
