# syntax=docker/dockerfile:1
# Multi-stage build for api-gateway (Phase 5, ADR-0026).
# Build context must be the repo root, not this directory — the Maven
# reactor needs every module's pom.xml present to resolve the dependency
# graph even when -am filters the actual build down to this module +
# common-lib. See docker-compose.yml: `context: .`, `dockerfile:
# api-gateway/Dockerfile`.
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
    chmod +x mvnw && ./mvnw -q -pl api-gateway,otel-agent-extension -am package -DskipTests -Dassembly.skipAssembly=true
RUN mkdir -p /workspace/extracted && \
    java -Djarmode=layertools -jar /workspace/api-gateway/target/api-gateway-*.jar extract --destination /workspace/extracted
# ADR-0064: fetched via Maven (not curl'd from GitHub releases) so it
# goes through the same dependency-resolution/checksum path as every
# other jar this build already trusts, and reuses the .m2 cache mount
# instead of needing separate network egress rules for GitHub.
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /workspace/otel && \
    ./mvnw -q dependency:copy \
      -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.10.0:jar \
      -DoutputDirectory=/workspace/otel && \
    mv /workspace/otel/opentelemetry-javaagent-2.10.0.jar /workspace/otel/opentelemetry-javaagent.jar && \
    cp /workspace/otel-agent-extension/target/otel-agent-extension-*.jar /workspace/otel/otel-agent-extension.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
# ADR-0064: the OTel Java agent + its extension jar for this service
# only — bytecode-instruments reactor-netty/Spring Cloud Gateway
# directly, closing the proxied-route span gap library instrumentation
# (ADR-0055/ADR-0056/ADR-0063) couldn't reliably close. Read-only,
# owned by root, readable by the spring user — consistent with
# readOnlyRootFilesystem: true in k8s/base/api-gateway.yaml.
COPY --from=build /workspace/otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /workspace/otel/otel-agent-extension.jar /otel/otel-agent-extension.jar
USER spring:spring
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
