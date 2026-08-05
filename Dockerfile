# Multi-stage build for mini-agent-app
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY mini-agent-memory/pom.xml mini-agent-memory/
COPY mini-agent-app/pom.xml mini-agent-app/
RUN mvn -q -pl mini-agent-app -am dependency:go-offline || true
COPY mini-agent-memory mini-agent-memory
COPY mini-agent-app mini-agent-app
RUN mvn -q -pl mini-agent-app -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 appuser \
    && mkdir -p /app/workspace /app/generated-images /app/user-uploads /app/memory \
    && chown -R appuser:appuser /app
COPY --from=build /src/mini-agent-app/target/mini-agent-app-*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
