# boka

A concurrency-safe appointment reservation API learning project using Java, Spring Boot, and PostgreSQL. The current version supports demo authentication, admin-created providers and non-overlapping slots, and authenticated slot browsing. Reservation holds and confirmation are the next milestone.

## Quick start

Prerequisites: Docker Engine with daemon access, Docker Compose v2, GNU Make, and internet access for first downloads. No host JDK is required for the container stack.

```sh
make init
make up
set -a
source .env
set +a
curl --fail --user "admin:$DEMO_ADMIN_PASSWORD" http://localhost:8080/api/v1/status
# {"application":"boka","providerCount":0} on an empty database
docker compose -p boka exec -T app curl -fsS http://localhost:8081/actuator/health/readiness
make down
```

API and database ports bind to localhost; management port 8081 stays inside the Docker network. Change `APP_PORT` / `POSTGRES_PORT` in ignored `.env` for conflicts. `make init` preserves existing values and adds missing settings with generated passwords. `make down` preserves data. Only `make reset CONFIRM=yes` deletes this project's volumes. Do not reuse a Compose project named `boka` for an unrelated stack.

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

`make help` lists current commands. Monitoring, authenticated smoke, and load-test targets arrive with their milestones. HTTP Basic is configured for non-browser local API clients; no reservation endpoints exist yet.

Open `pom.xml` in IntelliJ. Set Project SDK and Maven importer/runner JDK to Temurin 25 (the isolated JDK lives in `.tools/jdk-25.0.5+8`). Use Maven Wrapper. Run/debug `BokaApplication` with profile `local` and environment variables from `.env` (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DEMO_ADMIN_PASSWORD`, `DEMO_CUSTOMER1_PASSWORD`, `DEMO_CUSTOMER2_PASSWORD`, optional `POSTGRES_PORT`/`APP_PORT`). IntelliJ does not automatically load shell `.env` files; enter them in the run configuration. Set a breakpoint in `StatusController.status`, then call the endpoint. Stop the Docker app with `docker compose -p boka stop app` first. No machine-specific SDK configuration is checked in.

## API slice: identities, providers and slots

`make up` explicitly enables the `demo` profile. `make run` uses `local`, which includes `demo`. They seed `admin`, `customer1`, and `customer2` on first startup, storing BCrypt hashes. Existing accounts are preserved on restart; editing a password in `.env` does not rotate a previously seeded account. Without these profiles, no users are seeded. User registration and credential rotation are outside this milestone.

Use the environment loaded above. All business routes require HTTP Basic. Only administrators can create providers and slots; both customers can browse. These examples use fictional data and future dates (adjust them when needed):

```sh
curl --fail-with-body --user "admin:$DEMO_ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Fictional Demo Provider"}' \
  http://localhost:8080/api/v1/providers
# 201: {"id":"...","displayName":"Fictional Demo Provider","active":true}

# Set provider_id to the returned UUID.
curl --fail-with-body --user "admin:$DEMO_ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"startsAt":"2030-01-02T13:00:00+03:30","endsAt":"2030-01-02T13:30:00+03:30"}' \
  "http://localhost:8080/api/v1/providers/$provider_id/slots"
# 201; response timestamps are UTC, e.g. 2030-01-02T09:30:00Z.

curl --fail-with-body --user "customer1:$DEMO_CUSTOMER1_PASSWORD" --get \
  --data-urlencode 'from=2030-01-02T00:00:00Z' \
  --data-urlencode 'to=2030-01-03T00:00:00Z' \
  --data-urlencode 'page=0' --data-urlencode 'size=20' \
  "http://localhost:8080/api/v1/providers/$provider_id/slots"
# {"items":[...],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

Browse windows select slot starts in `[from,to)` and exclude slots that have already started. Windows must be positive and at most 31 days. Pages start at 0 (maximum 10000); size defaults to 20 and is bounded to 1–100. Results sort by start, then UUID. At this stage all returned slots are available; reservation-aware availability arrives with the booking milestone. Inactive/missing providers return 404.

Slots use UTC instants and PostgreSQL `timestamptz`, normalized to microsecond precision. A GiST exclusion constraint prevents overlap per provider, including concurrent inserts. Adjacent slots are valid. The migration installs PostgreSQL's `btree_gist` extension, so the migration role needs permission to install it. Flyway is the sole DDL owner; Hibernate validates the schema.

Errors use `application/problem+json`: invalid input 400, absent/bad credentials 401, forbidden roles/routes 403, missing/inactive provider 404, and overlapping slots 409. For negative examples: omit credentials from the status request (401), create a provider as `customer1` (403), repeat an identical slot creation (409), or submit a timestamp without its offset (400). Unknown routes default to deny. SQL and stack traces are not included in API error responses.

Authentication is stateless and creates no login session. CSRF protection is disabled for this non-browser API exercise; HTTP Basic can still be vulnerable to CSRF in browsers, so revisit this before adding browser clients. Basic credentials require HTTPS outside localhost. Management health remains internal to the Docker network and exposes no component details.
