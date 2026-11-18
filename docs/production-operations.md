# Production operations

## Shutdown and task recovery

The server uses Spring graceful shutdown and allows 35 minutes per shutdown phase. Agent runs and their SSE requests have a 30-minute deadline, leaving five minutes to persist terminal state and flush the final event. Set the orchestrator's termination grace period to at least 36 minutes; a shorter platform timeout can still kill the JVM before Spring finishes draining. The desktop profile keeps its shorter 15-second shutdown window.

If the process is killed before completion, startup recovery marks orphaned task runs interrupted. External side effects already performed by tools cannot be rolled back; operators should review action-journal entries with an unknown outcome before retrying.

## Network and database transport

The `prod` profile binds App, Account, and Memory Service to loopback by default and requires MySQL `VERIFY_IDENTITY` TLS. The public App endpoint must sit behind a TLS-terminating reverse proxy. For a proxy on another host, explicitly bind each service to a private interface and allow ingress only from that proxy or trusted service network. Install the database CA and host certificate in the JVM trust store before deployment. Account session cookies are `Secure`, `HttpOnly`, and `SameSite=Lax` in production. There is no implicit development profile; select `dev` explicitly for local work and keep production launchers on `prod` so readiness checks cannot be bypassed by an omitted profile.

In production, the App's primary chat model and streaming chat model endpoints (`LLM_BASE_URL`) and the independent evaluator endpoint (`AGENT_JUDGE_BASE_URL`) must all use absolute HTTPS URLs. Their API keys (`LLM_API_KEY` or `MIMO_API_KEY`, and `AGENT_JUDGE_API_KEY`) are sent only over HTTPS; production readiness rejects missing or non-HTTPS endpoint configuration. Keep these keys in the deployment secret store and never place them in URL query parameters.

## Metrics access

The App exposes Prometheus metrics at `/actuator/prometheus`; `/actuator/metrics/**` and the scrape endpoint require an authenticated `SYSTEM_ADMIN` bearer token. Keep actuator traffic on a private monitoring path and store the scrape credential in the monitoring system's secret store. LLM duration, aggregate input/output tokens, compression savings, and quota decisions use bounded labels and do not put user, session, or prompt values into metric labels. Provider billing cost is not inferred from token counts because configured providers and model pricing differ; reconcile cost using provider billing data until explicit, versioned pricing is configured.

The repository uses host-run JARs rather than Docker/Compose. Install the
systemd units under `scripts/systemd/` (or reproduce their limits in the chosen
service manager) before exposing production traffic. They set bounded
`MemoryMax`/`CPUQuota`/`TasksMax`/`LimitNOFILE`, `Restart=on-failure`,
`ExitOnOutOfMemoryError`, and a graceful stop window. The App unit allows 35
minutes for an in-flight run to persist terminal state; Account and
Memory Service use shorter windows. Do not run the JARs from an unbounded shell
for a production deployment.

Production JDBC pools have explicit per-instance limits: App defaults to 20 connections, Account and Memory Service to 10 each. Set `APP_DB_POOL_MAX_SIZE`, `ACCOUNT_DB_POOL_MAX_SIZE`, and `MEMORY_DB_POOL_MAX_SIZE` to fit the database's `max_connections` after accounting for every replica, migration/maintenance clients, and failover headroom; minimum idle values are independently configurable.

## Upload and event retention limits

The App accepts files up to 100 MiB, with a 140 MiB request allowance for multipart metadata or base64 encoding. The legacy base64 storage entry point checks the encoded length before allocating decoded bytes, then checks the exact decoded length. Increase these limits only after sizing heap, temporary storage, reverse-proxy request limits, and request timeouts together.

Uploads are content-sniffed before persistence; a client MIME type alone cannot make unknown binary data acceptable. The `prod` profile requires a malware scanner. Set `FILE_UPLOAD_ANTIVIRUS_ENABLED=true` and provide the ClamAV host/port (the implementation uses the bounded `INSTREAM` protocol); if no scanner is configured, uploads fail closed. ClamAV timeouts and invalid responses also reject the upload. Keep the scanner on a private network and size its stream limit and timeout to the 100 MiB upload cap.

Processed memory events are retained for 90 days by default and purged in bounded batches. Completed memory-index outbox rows are retained for 14 days; failed rows remain for operator investigation. Override the event retention period with `MEMORY_EVENT_RETENTION_DAYS` only when a documented retention policy requires it.

The local shared `system` tenant receives a total budget of 1,000,000 tokens per day and a per-user budget of 100,000 tokens per day. Both limits apply: a user's requests stop at their own cap, and all users together stop at the tenant cap. Set `AGENT_SELF_HOSTED_DAILY_TOKEN_LIMIT` and `AGENT_SELF_HOSTED_DAILY_USER_TOKEN_LIMIT` before first tenant creation to choose positive defaults for newly created shared tenants. Migrations V32 and V34 backfill existing zero-limited `system` tenants to the migration baselines of 1,000,000 total and 100,000 per user. V34 creates a separate per-user counter and does not apportion historical tenant totals. Other tenants keep `dailyUserTokenLimit=0` (no individual cap) unless an administrator sets it through the tenant administration API. The tenant administration API can set either limit to `0` to disable that level's cap.

Production `llm_judge` acceptance requires a separately configured evaluator (`AGENT_JUDGE_MODEL_NAME`, `AGENT_JUDGE_BASE_URL`, and `AGENT_JUDGE_API_KEY`). If that evaluator is unavailable, semantic acceptance fails closed instead of falling back to the execution model.

## Database migrations and recovery

Flyway migrations are append-only and run at service startup. Before production upgrades, take a restorable database backup and rehearse its restore against the target MySQL version. The release gate (`scripts/preflight-migrations.sh`) requires `MIGRATION_BACKUP_VERIFIED=true` after that restore drill and an immutable `MIGRATION_BACKUP_ID`; it then checks the migration chain and production Flyway policy. Keep the backup identifier and restore evidence with the deployment record. Restoring the pre-upgrade backup is the rollback path; the repository does not supply destructive down migrations.

Desktop releases also verify model archives against the separately protected CI secret `MODELS_ARCHIVE_SHA256`; do not derive the expected digest from the same downloadable archive. Configure this secret before enabling that release workflow.

## Memory deletion and retention boundary

The memory-service `DELETE /internal/v2/memory/subjects/{userId}` endpoint now performs a transactionally complete purge of that service's user facts, USER-scoped procedures and history, outcome/evolution records, and legacy procedure tables. It returns `SubjectErasureResult` with `complete`, item/fact counts, and `completedAt`; retrying after a lost response returns the same stored receipt. A SHA-256 subject tombstone serializes writes against erasure and rejects late facts, USER procedure imports, and outcome replays. The endpoint is scoped to the authenticated user in the token. Project-shared SOP content remains, while the erased user's project memberships are removed and owned project grants are revoked.

App `POST /v1/memory/erase-subject` orchestrates local SQL/blob, Redis, vector, upload, conversation, trace/event and cache cleanup before calling memory-service. It reports `PENDING_REMOTE` when the remote receipt is unavailable or incomplete and reports `COMPLETE` only after both local and remote completion are recorded. A tombstone blocks late writes from reintroducing erased user data. Deleting an individual App memory entry (`DELETE /v1/memory/memories/{id}`) remains a soft delete with asynchronous vector-index removal; App `/v1/memory/forget` still applies retention policy only.
