FROM gradle:8.10-jdk21 AS build
WORKDIR /app

# Cache dependencies: chỉ copy file build trước
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon -q > /dev/null 2>&1 || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]