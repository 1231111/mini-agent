# Host service limits

The repository no longer ships Docker or Compose deployment files. Production
server deployments run the three Spring Boot JARs under a host service manager.
The unit files in this directory are a baseline for systemd and deliberately
keep the services private behind the TLS reverse proxy:

```sh
sudo install -o root -g root -m 0644 scripts/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mini-agent-account.service mini-agent-memory-service.service mini-agent-app.service
```

Create the referenced environment files under `/etc/miniagent/` with mode
`0600`, and replace `/opt/miniagent` or the JAR paths if the release is staged
elsewhere. Set `MINI_AGENT_JAVA_OPTS` only after sizing the host; the unit files
always provide bounded memory and CPU defaults.

The limits are intentional release gates: `MemoryMax`, `CPUQuota`, `TasksMax`,
`LimitNOFILE`, `Restart=on-failure`, `ExitOnOutOfMemoryError`, and a graceful
stop window cover runaway processes, OOM recovery, file-descriptor exhaustion,
and rolling restarts. Keep the app stop window at least 35 minutes because an
agent run can hold an SSE request for up to 30 minutes.
