# syntax=docker/dockerfile:1
# NOT built in the authoring environment (no Docker daemon). Base image tags are not pinned by digest: pin them in CI.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/dpi-agentic-rag-log-analysis-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080 8081
# UTC is required: PostgreSQL rejects some legacy zone ids. Container-aware heap sizing; die on OOM so the pod restarts cleanly.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
