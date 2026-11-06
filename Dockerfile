# One module per image. MODULE selects the jar:
#   mini-agent-app              client agent (desktop). Compose does not run it.
#   mini-agent-account          account
#   mini-agent-memory-service   cloud memory upgrade
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY . .
ARG MODULE=mini-agent-app
RUN mvn -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 appuser \
    && mkdir -p /app/workspace /app/generated-images /app/user-uploads /app/memory \
    && chown -R appuser:appuser /app

ARG MODULE=mini-agent-app
COPY --from=build /src/${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar /app/app.jar

USER appuser

EXPOSE 8080 8081 8091
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
