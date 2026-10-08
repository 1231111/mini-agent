# Multi-stage build for the Maven reactor.  Which module gets packaged is a build
# arg, so one file produces both images:
#
#   docker build .                                        -> agent   (mini-agent-app,     :8080)
#   docker build --build-arg MODULE=mini-agent-account .  -> account (mini-agent-account, :8081)
#
# Why one file instead of two: the expensive part (the layer-cached dependency
# download) is identical for both, and the module list has to stay in sync with
# the root pom either way.  Two files would mean two places to update whenever a
# module is added.
FROM maven:3.9.9-eclipse-temurin-21 AS build

# The default is what every pre-existing build invocation gets, so omitting
# --build-arg MODULE keeps the previous behaviour exactly.
ARG MODULE=mini-agent-app

WORKDIR /src
COPY pom.xml .
COPY mini-agent-common/pom.xml mini-agent-common/
COPY mini-agent-memory/pom.xml mini-agent-memory/
COPY mini-agent-tools/pom.xml mini-agent-tools/
COPY mini-agent-loop/pom.xml mini-agent-loop/
COPY mini-agent-planner/pom.xml mini-agent-planner/
COPY mini-agent-app/pom.xml mini-agent-app/
COPY mini-agent-account/pom.xml mini-agent-account/
RUN mvn -q -pl ${MODULE} -am dependency:go-offline || true
COPY mini-agent-common mini-agent-common
COPY mini-agent-memory mini-agent-memory
COPY mini-agent-tools mini-agent-tools
COPY mini-agent-loop mini-agent-loop
COPY mini-agent-planner mini-agent-planner
COPY mini-agent-app mini-agent-app
COPY mini-agent-account mini-agent-account
RUN mvn -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

# Re-declared because an ARG's scope is a single stage.  Passing the same
# --build-arg value fills in both declarations, so the two stay consistent.
ARG MODULE=mini-agent-app

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 appuser \
    && mkdir -p /app/workspace /app/generated-images /app/user-uploads /app/memory \
    && chown -R appuser:appuser /app

# Only this image's own jar.  Both jars are not copied unconditionally: building
# the agent image does not build account (it is not on the agent's dependency
# path), so a second COPY would fail there.
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar /app/app.jar

USER appuser

# Documentation only.  The port actually listened on comes from server.port in
# application*.yml (agent: 8080, account: ${ACCOUNT_SERVER_PORT:8081}), and the
# binding that matters is the port mapping in docker-compose.yml.
EXPOSE 8080 8081

ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
