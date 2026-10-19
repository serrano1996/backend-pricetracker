# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Separate layer so dependencies are cached across source-only changes
RUN ./gradlew dependencies --no-daemon > /dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# Heap sized from the container limit; Railway bills by RAM actually used
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
