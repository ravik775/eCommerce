# ADR-0011: Artifact packaging — Spring Boot executable JAR bundled as a release ZIP (WAR available as fallback)

**Status**: Accepted
**Date**: 2026-08-12
**Deciders**: Solution/Security Architect

## Context

Instruction: "the final output will be zip or war." Spring Boot 3 defaults to producing an executable JAR (embedded Tomcat/Netty); WAR packaging is also supported for deployment into an external servlet container, but requires more moving parts (a `SpringBootServletInitializer` subclass, `provided` scope on the embedded container dependency, an external Tomcat/etc. to deploy into).

## Options Considered

| Option | Pros | Cons |
|---|---|---|
| WAR, deployed to an external servlet container | Traditional enterprise deployment model; matches some legacy ops expectations | Requires maintaining a separate application server per environment; Spring Boot's embedded-server model (already the repo's existing convention — see current `spring-boot-starter-web` usage) has to be reconfigured (`provided` scope, servlet initializer) to support it; more moving parts for no benefit at this project's scale, since nothing here requires a shared app server |
| Executable JAR, packaged into a release ZIP per service (jar + `application.yml` template + start/stop script) | Matches Spring Boot's default, already-working packaging model exactly — zero rework; the ZIP wrapper satisfies the "zip or war" instruction while staying with the simpler, already-proven artifact type; self-contained (embedded server), runs anywhere a JVM exists with `java -jar` | Not a traditional WAR — if an external servlet container is later mandated by an ops constraint not currently known, this would need revisiting (WAR profile kept available for exactly that case) |

## Decision

Each service is packaged as its existing Spring Boot executable JAR, then wrapped into a release **ZIP** (`<service>-<version>.zip`) containing: the executable JAR, an `application.yml` config template (no secrets), and a minimal start script (`java -jar <service>.jar`). A `war` Maven profile is added per service (inactive by default) for the fallback case, using `spring-boot-starter-tomcat` with `provided` scope and a `SpringBootServletInitializer`, so WAR packaging remains one flag away (`mvn package -Pwar`) without being the default path.

## Consequences

- Positive: no rework of the existing embedded-server model; simplest path that still literally satisfies "zip or war"; the WAR profile is documented and available, not silently dropped, if it's ever actually needed.
- Negative / accepted trade-off: the ZIP is a custom convention (jar + template + script), not a single standardized artifact type — acceptable since it's simple enough to generate in one Maven Assembly/Antrun step per service.
- Follow-up required: build the ZIP assembly descriptor as part of Phase 1's CI pipeline bootstrap; the WAR profile is deferred (not built) until an actual need for it is identified.

## Relationship to Docker/Kubernetes (Phases 5–6)

This is not a competing choice against containerization — a Kubernetes Pod can only run an OCI/Docker container, so JAR/WAR and "Docker image" answer different questions: JAR/WAR is the *application packaging format*; the Docker image is the *deployable unit* built later by copying that JAR onto a JRE base image (`FROM eclipse-temurin:21-jre`, `COPY app.jar`, `ENTRYPOINT java -jar app.jar`). The JAR decision made here is exactly what Phase 5's Dockerfiles will consume — it is not superseded by containerization, it's a prerequisite for it. WAR would additionally require a Tomcat base image inside the container, duplicating the embedded server Spring Boot already provides — reinforcing JAR as the right choice even once Docker is in the picture, not just for now.

## Related

- ADR-0010 (CI/CD pipeline — this is the packaging step within it)
- `doc/architecture/07-migration-planning.md`, Phase 5, for how this JAR becomes a Docker image
