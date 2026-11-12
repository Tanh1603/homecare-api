FROM gradle:9.7.1-jdk21 AS build
WORKDIR /app

ARG SERVICE
ENV GRADLE_USER_HOME=/app/.gradle-cache
COPY . .

RUN --mount=type=cache,id=homecare-gradle-cache,target=/app/.gradle-cache,sharing=locked \
    set -eu; \
    test -n "$SERVICE"; \
    gradle ":services:${SERVICE}:bootJar" \
        --no-daemon \
        --no-watch-fs \
        --max-workers=2 \
        -x test; \
    artifact="$(find "/app/build/services/${SERVICE}/libs" \
        -type f -name '*.jar' ! -name '*-plain.jar' \
        -print -quit)"; \
    test -n "$artifact"; \
    cp "$artifact" /app/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]