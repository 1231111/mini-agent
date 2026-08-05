# Security notes (landing checklist)

## Rotate secrets

API keys and DB passwords previously lived in committed `application.yml`. They are now env-driven.
**Rotate all exposed keys** (OpenAI/Mimo, ChatAnywhere, SiliconFlow, Tavily, Bing, MySQL) even if you only use local profile.

## Required env

See `.env.example`. Minimum for prod:

- `AGENT_AUTH_COOKIE_SECRET` (≥32 random chars)
- `SPRING_DATASOURCE_PASSWORD`
- `LANGCHAIN4J_OPENAI_API_KEY` (and other provider keys you use)

## Defaults hardened

- `agent.tools.exec-enabled=false` — shell tool not registered
- `agent.tools.allow-absolute-write=false` — writes confined to `workspace/`
- `agent.tools.block-private-network=true` — SSRF guard on `http_get` / browser
- Signed session cookies (`uid` + `uexp` + `usig`); plain `uid` forgery rejected
- Passwords stored with BCrypt (legacy SHA-256 upgraded on login)
- Per-user API ownership checks; rate limit + concurrency caps

## Run

```bash
cp .env.example .env   # fill values
docker compose up -d --build
```

Local without Docker: set env vars or copy `application-local.yml.example` → `application-local.yml`, then:

```bash
mvn -pl mini-agent-app -am spring-boot:run -Dspring-boot.run.profiles=local
```
