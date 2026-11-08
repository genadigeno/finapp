# The application image, finapp-app (P15-TSK-003; PHASE_15_PLAN.md section 5; owner decision C4; ADR-0120).
#
# Build it with infra/scripts/image-build.sh, not with `docker build` alone: the script builds the boot jar, passes
# the build arguments below, builds the image twice and refuses it unless the two builds are the same bytes, it runs
# as UID 65532, it holds nothing but the jar's entries and the pinned base, and it is within its size budget.
#
# THE TWO FROM LINES ARE THE BASE IMAGES' ONLY PINS. Each names the tag it claims and the digest that is pulled: a
# tag can be moved by whoever controls the source repository, a digest cannot. They live here rather than in a
# shell-sourced pins file because Dependabot's docker ecosystem reads FROM lines (it proposes the next tag and its
# digest weekly) and does not resolve ARG; infra/scripts/check-pinned-images.sh reads them too, refuses one that is
# not pinned by digest, and reports a moved tag or a newer release on its weekly schedule. No other file repeats a
# digest. ApplicationImageTest holds every property below on every build.
#
# No HEALTHCHECK, deliberately (C4): Kubernetes ignores it and probes the actuator instead, and the runtime base has
# no shell to run one.

# The builder: a JDK only to run the Spring Boot jar's own extractor. The jar is built by Gradle outside the image,
# so no build tool and no source ever enter a stage.
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS builder
ARG JAR
ARG SOURCE_DATE_EPOCH
WORKDIR /workspace
COPY ${JAR} application.jar
# Extract the layered jar (dependencies, spring-boot-loader, snapshot-dependencies, application) with its launcher,
# each layer under app/ so that one COPY per layer lands it at /app. Then every file and directory takes the
# commit's time: extraction writes them at the build's instant, which made two builds of one jar two different
# images (measured 2026-11-08: three of the four layers differed). SOURCE_DATE_EPOCH is required - `touch` refuses
# an empty date, so a build without it fails rather than quietly stamping the clock.
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted \
 && for layer in dependencies spring-boot-loader snapshot-dependencies application; do \
      mkdir -p "layers/$layer" && mv "extracted/$layer" "layers/$layer/app"; \
    done \
 && find layers -exec touch --no-dereference --date="@${SOURCE_DATE_EPOCH}" {} +

# The runtime: distroless Java 21, the nonroot variant - no shell, no package manager, a JRE and its certificates.
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:b22857e939bd95abd69d9d747f0421a274f9f70855ecfc335f7d97f6eeef578d AS runtime
ARG REVISION
ARG SOURCE
ARG VERSION
LABEL org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.version="${VERSION}"
# Least-changing first, so a code change rebuilds and ships one layer: the libraries, the loader, the snapshot
# libraries (none today: the platform's own modules are in the application layer), then the application. The files
# stay root's and read-only to the process: it cannot rewrite the code it runs.
COPY --from=builder /workspace/layers/dependencies/ /
COPY --from=builder /workspace/layers/spring-boot-loader/ /
COPY --from=builder /workspace/layers/snapshot-dependencies/ /
COPY --from=builder /workspace/layers/application/ /
WORKDIR /app
# Explicit and numeric although the base already runs as 65532: an explicit user survives a change of base, and a
# numeric one is what Kubernetes' runAsNonRoot can verify without reading the image's /etc/passwd.
USER 65532:65532
# MaxRAMPercentage sizes the heap from the container's memory limit (calibrated by P15-TSK-009 and Phase 16);
# ExitOnOutOfMemoryError ends a JVM that ran out of heap so it is restarted, rather than left half alive holding
# leases. Further JVM flags go in JDK_JAVA_OPTIONS.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
