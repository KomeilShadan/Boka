# boka

Boka is a learning project for an appointment reservation API built with Java, Spring Boot, and PostgreSQL. It focuses on correctness under concurrent requests.

The current API supports demo authentication, administrator-created providers, non-overlapping slots, and authenticated slot browsing. Milestone 3 is in progress. The implementation includes reservation persistence, domain rules, and transactional hold and confirmation services. Reservation-aware availability and reservation HTTP endpoints remain pending.

## Quick start

You need Docker Engine with daemon access, Docker Compose v2, and GNU Make. The first downloads require internet access. The container stack does not require a host JDK.

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

API and database ports bind to localhost. Management port 8081 stays inside the Docker network. For port conflicts, change `APP_PORT` or `POSTGRES_PORT` in the ignored `.env` file.

`make init` preserves existing values and adds missing settings with generated passwords. `make down` preserves data. Only `make reset CONFIRM=yes` deletes this project's volumes. Do not reuse a Compose project named `boka` for an unrelated stack.

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

`make help` lists current commands. Monitoring, authenticated smoke, and load-test targets arrive with their milestones. HTTP Basic serves non-browser local API clients. No reservation endpoints exist yet.

To use IntelliJ:

1. Open `pom.xml`.
2. Set Project SDK and Maven importer/runner JDK to Temurin 25. The isolated JDK lives in `.tools/jdk-25.0.5+8`.
3. Select Maven Wrapper.
4. Stop the Docker app with `docker compose -p boka stop app` before a host run.
5. Run or debug `BokaApplication` with profile `local` and the environment variables from `.env`.
6. Set a breakpoint in `StatusController.status`.
7. Call the endpoint.

The required variables are `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DEMO_ADMIN_PASSWORD`, `DEMO_CUSTOMER1_PASSWORD`, and `DEMO_CUSTOMER2_PASSWORD`. The optional variables are `POSTGRES_PORT` and `APP_PORT`. IntelliJ does not automatically load shell `.env` files. Enter these values in the run configuration. Keep machine-specific SDK settings outside Git.

## API slice: identities, providers and slots

`make up` explicitly enables the `demo` profile. `make run` uses `local`, which includes `demo`. On first startup, these profiles seed `admin`, `customer1`, and `customer2` with BCrypt password hashes.

The initializer preserves existing accounts on restart. A password change in `.env` does not rotate a seeded account's password. Other profiles seed no users. User registration and credential rotation are outside this milestone.

Use the environment loaded above. All business routes require HTTP Basic. Only administrators can create providers and slots. Both customers can browse. These examples use fictional data and future dates. Adjust the dates when needed.

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

Browse windows select slot starts in `[from,to)` and exclude slots that already started. Windows must be positive and at most 31 days. Pages start at 0, with a maximum of 10000. Page size defaults to 20 and must be between 1 and 100. Results sort by start, then UUID.

At this stage, the response marks all returned slots as available. Reservation-aware availability arrives with the booking milestone. Inactive or missing providers return 404.

Slots use UTC instants and PostgreSQL `timestamptz` at microsecond precision. Slot creation locks the provider row to serialize competing application requests. A GiST exclusion constraint also prevents overlap, including writes that bypass the application. Adjacent slots are valid. The migration installs PostgreSQL's `btree_gist` extension. The migration role needs permission to install it. Flyway owns schema changes, and Hibernate validates the schema.

Errors use `application/problem+json`:

| Outcome | Status |
| --- | --- |
| Invalid input | 400 |
| Absent or bad credentials | 401 |
| Forbidden role or route | 403 |
| Missing or inactive provider | 404 |
| Overlapping slots | 409 |

For negative examples:

- Omit credentials from the status request to receive 401.
- Create a provider as `customer1` to receive 403.
- Repeat an identical slot creation to receive 409.
- Submit a timestamp without its offset to receive 400.

Unknown routes default to deny. API error responses expose neither SQL nor stack traces.

Authentication is stateless and creates no login session. This non-browser API exercise disables CSRF protection. HTTP Basic can still be vulnerable to CSRF in browsers. Reassess this decision before adding browser clients. Basic credentials require HTTPS outside localhost. Management health stays inside the Docker network and exposes no component details.

## Reservation foundation (Milestone 3, slice 3.1)

V4 adds the reservations table and a partial unique index. The index allows at most one `HELD` or `CONFIRMED` reservation per slot. The table retains historical `EXPIRED` and `CANCELLED` rows. A past deadline alone does not release an active row. Confirmed reservations remain occupied after their original hold deadline.

The domain model validates microsecond precision, a positive hold duration, and a future slot start. Confirmation must occur strictly before both the deadline and slot start. Repeated confirmation preserves the original confirmed state and timestamps.

Tests demonstrate JPA dirty checking, rollback after flush, migration from an existing V3 schema, and competing writes on independent PostgreSQL connections.

Run `make verify` to exercise this slice. Run `make up` to apply the additive V4 migration through normal startup. Slice 3.2 implements slot-first locking, fresh database time, configured hold duration, customer-bound service authorization, and expiry-on-acquire.

Reservation-aware availability and the required idempotent HTTP contract remain pending. Slice 3.3 implements internal confirmation orchestration. Existing security rules continue to deny reservation routes.

## Transactional hold acquisition (Milestone 3, slice 3.2)

`ReservationHoldService.acquireHold(slotId, customerUsername)` is an internal Spring application service. Its method-security guard requires an authenticated customer whose name matches the supplied username. It also requires an enabled account with the persisted customer role.

The service acquires a JPA pessimistic write lock on the slot. It reloads state and samples PostgreSQL `clock_timestamp()`. It then rejects occupancy or atomically expires an overdue hold and creates a replacement.

The service flushes the expiry before inserting the replacement. This releases the active-slot unique index entry before the insert. Replacement failure rolls the whole operation back.

The default hold duration is five minutes. To change it, set optional `BOKA_RESERVATION_HOLD_DURATION=PT45S` in your local environment or `.env`. Supported values range from 1 second to 24 hours at microsecond precision. Restart the app after a change.

Compose forwards the setting with a five-minute fallback. The local run script exports `.env`. This slice adds no migration.

Use `make verify` for the complete suite. Reservation HTTP routes remain denied until their remaining orchestration and idempotency contract are ready. The internal service creates a new hold or raises a business conflict. It does not promise retry replay. Confirmation uses the same slot-first lock order. Future cancellation and expiry writers must follow it too.


## Transactional confirmation (Milestone 3, slice 3.3)

`ReservationConfirmationService.confirm(reservationId, customerUsername)` confirms an owned hold through a secured Spring service proxy. The caller must be an authenticated customer with a matching username and an enabled persisted customer account.

The service first resolves the owner's slot ID without loading a reservation entity. It locks that slot, reloads the reservation and refreshes managed state. It then samples database time and applies the existing domain transition. Missing and other customers' reservations share the same not-found result.

A live hold confirms only before its deadline and slot start. Repeating confirmation returns the same state and timestamps, including after the original deadline or slot start. Terminal reservations cannot become active again. Rejected overdue confirmation does not commit expiry cleanup. Hold acquisition already performs that cleanup when rebooking.

The service flushes inside its transaction and returns a response DTO. An enclosing transaction failure still rolls back the update. The algorithm assumes the existing PostgreSQL READ COMMITTED isolation and uses the same slot lock as hold acquisition. Its transaction timeout is ten seconds.

Run `make verify` for ownership, state, rollback and independent-connection race tests. HTTP routes remain denied while the reservation API contract is incomplete. The MVP includes no payment module.
