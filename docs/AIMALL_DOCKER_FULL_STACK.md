# AIMall full Docker stack

This stack runs the complete AIMall environment in an isolated Compose project. It does not replace `start.bat`, reuse the local development database, or reuse historical Docker volumes.

## Included services

The default stack starts 15 services:

- MySQL, Redis, etcd, MinIO, Milvus, and MailHog
- AIMall Java server, AI service, storefront, and admin frontend
- Prometheus, Alertmanager, Loki, Promtail, and Grafana

Cloudflare Named Tunnel is the optional sixteenth service under the `tunnel` profile.

## Prerequisites

- Docker Desktop with Compose v2
- At least 8 GB of memory available to Docker
- Free disk space for images, build cache, databases, vectors, and monitoring data
- Alipay key files already mounted/configured when sandbox payment is enabled
- A Cloudflare Named Tunnel token only when public callback ingress is required

## First-time setup

Create the local environment file and replace every placeholder secret:

```powershell
Copy-Item .env.docker.example .env.docker.local
notepad .env.docker.local
```

`.env.docker.local` and `.docker-secrets/` are ignored by Git. Never put real passwords, API keys, tunnel tokens, or private keys in `.env.docker.example`.

For networks that cannot reliably pull Docker Hub images, set a registry proxy prefix. The trailing slash is required:

```dotenv
DOCKER_HUB_MIRROR_PREFIX=docker.m.daocloud.io/
```

## Start

Start or update the default 15-service stack:

```powershell
.\docker-full-start.bat
```

The command is idempotent: it rebuilds changed application images and preserves named volumes.

Enable the Cloudflare Named Tunnel after setting `CLOUDFLARE_TUNNEL_TOKEN` and a stable `ALIPAY_NOTIFY_BASE_URL`:

```powershell
.\docker-full-start.bat -Tunnel
```

Do not enable the tunnel profile with an empty token.

## URLs

| Service | URL |
| --- | --- |
| Storefront | http://localhost:15173 |
| Admin | http://localhost:15174 |
| Backend health | http://localhost:18080/api/health |
| AI health | http://localhost:18000/health |
| MailHog | http://localhost:18025 |
| MinIO console | http://localhost:19001 |
| Prometheus | http://localhost:19090 |
| Alertmanager | http://localhost:19093 |
| Grafana | http://localhost:13000 |

MySQL, Redis, Milvus, MinIO, Java, AI, and monitoring APIs bind to `127.0.0.1` where direct host access is needed. The storefront and admin use dedicated ports `15173` and `15174`, so the local Vite ports `5173` and `5174` remain available.

## Verification

Check container state and resolved Compose configuration:

```powershell
docker compose --env-file .env.docker.local -f docker-compose.full.yml ps
docker compose --env-file .env.docker.local -f docker-compose.full.yml config --quiet
```

Prometheus should show both `aimall-server` and `aimall-ai-service` as `UP` at http://localhost:19090/targets. Grafana provisions Prometheus as the default datasource and Loki as the log datasource.

Follow all logs or one service:

```powershell
docker compose --env-file .env.docker.local -f docker-compose.full.yml logs -f
docker compose --env-file .env.docker.local -f docker-compose.full.yml logs -f aimall-server
```

## Stop and restart

Stop containers while preserving all data:

```powershell
.\docker-full-stop.bat
```

Restart without rebuilding:

```powershell
docker compose --env-file .env.docker.local -f docker-compose.full.yml restart
```

Never add `-v` to `down` unless all Docker-only database, vector, cache, upload, email, and monitoring data should be permanently deleted.

## Data isolation

Compose uses project name `aimall-docker`, dedicated host ports, a private Compose network, and project-scoped named volumes. Existing local services and historical Docker volumes are not modified. The Java and AI containers share only the stack's `shared_storage` volume for knowledge files.

## Current limitation

The default 15-service stack is complete without Cloudflare. Public Alipay asynchronous callbacks require the optional tunnel (or another stable public HTTPS ingress). That capability remains unavailable until a valid Named Tunnel token and public callback URL are supplied.
