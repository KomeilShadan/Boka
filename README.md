# boka

A concurrency-safe appointment reservation API learning project using Java, Spring Boot, and PostgreSQL. This first session implements only the toolchain and Docker skeleton; reservation concurrency is a future deliverable.

## Quick start

Prerequisites: Docker Engine with daemon access, Docker Compose v2, GNU Make, and internet access for first downloads. No host JDK is required for the container stack.

```sh
make init
make up
curl --fail http://localhost:8080/api/v1/status
# {"application":"boka","providerCount":0}
docker compose -p boka exec -T app curl -fsS http://localhost:8081/actuator/health/readiness
make down
```

API and database ports bind to localhost; management port 8081 stays inside the Docker network. Change `APP_PORT` / `POSTGRES_PORT` in ignored `.env` for conflicts. `make init` preserves existing values. `make down` preserves data. Only `make reset CONFIRM=yes` deletes this project's volumes. Do not reuse a Compose project named `boka` for an unrelated stack.

## Host development and tests

```sh
make bootstrap   # isolated checksum-verified JDK 25; Linux x86_64
make doctor
make infra-up
make run         # local profile; first stop container app if already running
make test        # fast tests, no Docker
make integration-test  # fresh disposable PostgreSQL; never development DB
make verify      # formatter + unit tests + integration tests
make build       # executable JAR and unit tests
```

`make help` lists current commands. Monitoring, authenticated smoke, and load-test targets arrive with their milestones. No application security or reservation endpoints exist yet; localhost demo use only.

Open `pom.xml` in IntelliJ. Set Project SDK and Maven importer/runner JDK to Temurin 25 (the isolated JDK lives in `.tools/jdk-25.0.5+8`). Use Maven Wrapper. Run/debug `BokaApplication` with profile `local` and environment variables from `.env` (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, optional `POSTGRES_PORT`/`APP_PORT`). IntelliJ does not automatically load shell `.env` files; enter them in the run configuration. Set a breakpoint in `StatusController.status`, then call the endpoint. Stop the Docker app with `docker compose -p boka stop app` first. No machine-specific SDK configuration is checked in.
