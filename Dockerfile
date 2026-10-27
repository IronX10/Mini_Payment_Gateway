# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven wrapper -------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer, so a source-only change doesn't re-download the world.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI (unit + Testcontainers); the image build only packages.
RUN ./mvnw -B -q package -DskipTests \
    && cp target/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Runtime stage: slim JRE, non-root, layered jar ----------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system mpg && useradd --system --gid mpg mpg

# Least-volatile layers first: dependencies rarely change, application classes change every build.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER mpg
EXPOSE 8080
# Respect the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
