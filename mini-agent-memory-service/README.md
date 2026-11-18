# mini-agent-memory-service

Cloud memory API and evolution worker, backed by its own MySQL database.

## Production startup

Create the database, then set `MEMORY_SERVICE_DB_URL` (or `DB_URL` when running the jar directly), `DB_USERNAME`, and `DB_PASSWORD`. The URL must use `sslMode=VERIFY_IDENTITY`, and the JVM trust store must contain the database CA. The password must be at least 32 bytes. Configure `ACCOUNT_BASE_URL`, `ACCOUNT_INTERNAL_KEY`, and `ACCOUNT_MEMORY_TOKEN_KEY`; both service keys must match the values configured in Account. Generate the service keys with `scripts/gen-memory-service-keys.sh` and store them in the deployment secret manager.

The production profile listens on loopback by default. For a multi-host deployment, bind to a private interface with `MEMORY_SERVICE_ADDRESS` and restrict ingress to trusted service hosts. Local development should explicitly select `SPRING_PROFILES_ACTIVE=dev`; the launcher defaults to `prod`.

Build and start the service with the production profile:

```bash
./mvnw -pl mini-agent-memory-service -am -DskipTests package

MEMORY_SERVICE_JAR=mini-agent-memory-service/target/mini-agent-memory-service-0.0.1-SNAPSHOT.jar \
  MEMORY_SERVICE_ROLE=all ./scripts/start-memory-service.sh --foreground
```

`start-memory-service.sh` activates `prod` by default. The validator fails startup when a required credential is missing, shorter than its minimum, or matches a previously shipped development credential. Set `SPRING_PROFILES_ACTIVE` only when intentionally selecting another profile.

## Local development

The `dev` profile is the default for direct local runs and reads credentials from the gitignored `.env` file or IDE environment. The repository contains no usable default credentials. The service launch script loads `.env` automatically. Keep the services bound to localhost and never use this profile for a network-accessible deployment.

```bash
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw -pl mini-agent-memory-service -am spring-boot:run
```
