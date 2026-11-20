# infra: local stack

`docker-compose.yml` runs the whole bank on your machine. Use it through the Makefile at the repo root (`make up`, `make down`, `make reset`, `make logs`, `make psql`).

| Service | Image | Port (localhost only) | Notes |
|---|---|---|---|
| `postgres` | `postgres:17-alpine` | 5432 | Volume `pgdata` |
| `chain` | `ghcr.io/foundry-rs/foundry:v1.8.7` (Anvil, pinned) | 8545 | Chain ID 31337, 2 s blocks; state saved to volume `chaindata` every 10 s and on shutdown |
| `api` | built from `services/api` | 8080 | Healthy when `/q/health/ready` is UP |
| `web` | built from the repo root (`apps/web/Dockerfile`) | 3000 | Healthy when `/login` responds |

All ports bind to `127.0.0.1`. Every key and password in this file is **local-only and public**: Anvil's well-known dev keys, a demo TOTP key, the demo password. The full list of variables and their production rules is in [docs/runbook.md](../docs/runbook.md#3-configuration).

`make reset` deletes both volumes together. The chain and the database must always be reset as a pair, or the API's chain health check reports the mismatch.
