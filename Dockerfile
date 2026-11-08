FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS builder
ARG JAR
ARG SOURCE_DATE_EPOCH
WORKDIR /workspace
COPY ${JAR} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted \
 && for layer in dependencies spring-boot-loader snapshot-dependencies application; do \
      mkdir -p "layers/$layer" && mv "extracted/$layer" "layers/$layer/app"; \
    done \
 && find layers -exec touch --no-dereference --date="@${SOURCE_DATE_EPOCH}" {} +

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:b22857e939bd95abd69d9d747f0421a274f9f70855ecfc335f7d97f6eeef578d AS runtime
ARG REVISION
ARG SOURCE
ARG VERSION
LABEL org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=builder /workspace/layers/dependencies/ /
COPY --from=builder /workspace/layers/spring-boot-loader/ /
COPY --from=builder /workspace/layers/snapshot-dependencies/ /
COPY --from=builder /workspace/layers/application/ /
WORKDIR /app
USER 65532:65532
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
