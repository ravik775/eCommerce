# syntax=docker/dockerfile:1
# Multi-stage build for service-discovery (Phase 5, ADR-0026).
# Build context must be the repo root, not this directory — the Maven
# reactor needs every module's pom.xml present to resolve the dependency
# graph even when -am filters the actual build down to this module +
# common-lib. See docker-compose.yml: `context: .`, `dockerfile:
# service-discovery/Dockerfile`.
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
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -q -pl service-discovery -am package -DskipTests -Dassembly.skipAssembly=true
RUN mkdir -p /workspace/extracted && \
    java -Djarmode=layertools -jar /workspace/service-discovery/target/service-discovery-*.jar extract --destination /workspace/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER spring:spring
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8086
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
