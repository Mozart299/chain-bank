COMPOSE := docker compose -f infra/docker-compose.yml
# The API needs JDK 21. Use ~/.jdks/jdk-21* or a registered JDK 21 if present; otherwise rely on JAVA_HOME (CI).
JAVA21_HOME ?= $(firstword $(wildcard $(HOME)/.jdks/jdk-21*/Contents/Home) $(shell /usr/libexec/java_home -v 21 2>/dev/null))
MVN := $(if $(JAVA21_HOME),JAVA_HOME=$(JAVA21_HOME) ,)./mvnw

.PHONY: up down reset logs smoke test test-api test-contracts test-web api-dev web-dev psql openapi

up: ## Build and start the full stack, wait for health
	$(COMPOSE) up -d --build --wait

down:
	$(COMPOSE) down

reset: ## Stop and delete all local data (database volume)
	$(COMPOSE) down -v

logs:
	$(COMPOSE) logs -f

smoke:
	./scripts/smoke.sh
	node scripts/e2e-auth.mjs

test: test-api test-contracts test-web

test-api: ## all API tests, or one suite: make test-api TEST=TransferConcurrencyTest
	cd services/api && $(MVN) -B test $(if $(TEST),-Dtest=$(TEST),)

test-contracts:
	pnpm --filter @chainbank/contracts test

test-web:
	pnpm --filter @chainbank/web lint && pnpm --filter @chainbank/web typecheck

api-dev: ## Quarkus dev mode (starts its own Postgres via Dev Services)
	cd services/api && $(MVN) quarkus:dev

web-dev:
	pnpm --filter @chainbank/web dev

psql: ## SQL shell on the running stack's database
	$(COMPOSE) exec postgres psql -U chainbank chainbank

openapi: ## refresh docs/api/openapi.yaml from the running API
	curl -fsS http://127.0.0.1:8080/q/openapi -o docs/api/openapi.yaml
