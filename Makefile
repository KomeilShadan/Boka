SHELL := /bin/bash
.DEFAULT_GOAL := help
.PHONY: help bootstrap doctor init up down infra-up run logs build test integration-test verify fmt fmt-check reset
help: ## List commands (Docker/Compose/Make for runtime; JDK 25 for host builds)
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "%-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
bootstrap: ## Install isolated checksum-verified Temurin 25 (Linux x86_64)
	@scripts/bootstrap.sh
doctor: ## Inspect tools, Docker access, config, and port conflicts
	@scripts/doctor.sh
init: ## Generate ignored local configuration, preserving existing values
	@scripts/init.sh
up: ## Build and start API + PostgreSQL; wait for readiness
	@scripts/compose-up.sh app
down: ## Stop this project's containers; preserve data
	docker compose -p boka down
infra-up: ## Start only PostgreSQL and wait for health
	@scripts/compose-up.sh db
run: ## Run locally with JDK 25 and local profile (stop container app first)
	@scripts/run.sh
logs: ## Follow application logs
	docker compose -p boka logs -f app
build: ## Package with fast tests
	@scripts/maven.sh -B package
test: ## Fast unit tests; no Docker required
	@scripts/maven.sh -B test
integration-test: ## PostgreSQL Testcontainers tests, including Failsafe verification
	@docker info >/dev/null || { echo 'Docker daemon unavailable; integration tests require Docker.' >&2; exit 1; }
	@scripts/maven.sh -B verify
verify: ## Formatting, unit tests, integration tests; Docker required
	@$(MAKE) integration-test
fmt: ## Apply pinned Java formatter
	@scripts/maven.sh -B spotless:apply
fmt-check: ## Check Java formatting
	@scripts/maven.sh -B spotless:check
reset: ## Remove this project's data ONLY with CONFIRM=yes
	@test "$(CONFIRM)" = yes || { echo 'Refusing: use make reset CONFIRM=yes'; exit 1; }
	docker compose -p boka down --volumes
