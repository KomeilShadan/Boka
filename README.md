# boka

Boka is a learning project for an appointment reservation API built with Java, Spring Boot, and PostgreSQL. It focuses on correctness under concurrent requests.

The API supports demo authentication, provider administration, slot browsing, retry-safe hold creation and owner-only reservation reads. A background worker expires overdue holds.

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
make verify      # setup script, formatter, and test suites
make build       # executable JAR and unit tests
```

`make help` lists current commands. The `local` profile binds the API and management ports to `127.0.0.1`. HTTP Basic serves local API clients.

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

Demo passwords must have a Java string length of at least 12 and fit within BCrypt's limit of 72 UTF-8 bytes. The initializer preserves existing accounts and passwords, including during concurrent startup. Other profiles seed no users.

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

Browse includes both available and occupied future slots. A live hold or CONFIRMED reservation returns available=false. An overdue hold or terminal history does not block logical availability. The query uses a sampled database time. Another request can claim a slot after browsing, so acquisition must recheck under its slot lock. Inactive or missing providers return 404.

Slots use UTC instants and PostgreSQL `timestamptz` at microsecond precision. Slot creation locks the provider row to serialize competing application requests. A GiST exclusion constraint also prevents overlap, including writes that bypass the application. Adjacent slots are valid. The migration installs PostgreSQL's `btree_gist` extension. The migration role needs permission to install it. Flyway owns schema changes, and Hibernate validates the schema.

Slot creation and browsing return 400 for timestamps outside the supported database range.

Errors use `application/problem+json`:

| Outcome | Status |
| --- | --- |
| Invalid input | 400 |
| Absent or bad credentials | 401 |
| Forbidden role or route | 403 |
| Missing or inactive provider | 404 |
| Overlapping slots | 409 |
| Authentication service unavailable | 503 |

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

Slice 3.3 adds internal confirmation. Slice 3.4 adds reservation-aware availability. Slice 4.1 adds internal creation replay, which slice 4.4 exposes to demo customers.

## Transactional hold acquisition (Milestone 3, slice 3.2)

`ReservationHoldService.acquireHold(slotId, customerUsername)` is an internal Spring application service. Its method-security guard requires an authenticated customer whose name matches the supplied username. It also requires an enabled account with the persisted customer role.

The service acquires a JPA pessimistic write lock on the slot. It reloads state and samples PostgreSQL `clock_timestamp()`. It then rejects occupancy or atomically expires an overdue hold and creates a replacement.

The service flushes the expiry before inserting the replacement. This releases the active-slot unique index entry before the insert. Replacement failure rolls the whole operation back.

The default hold duration is five minutes. To change it, set optional `BOKA_RESERVATION_HOLD_DURATION=PT45S` in your local environment or `.env`. Supported values range from 1 second to 24 hours at microsecond precision. Restart the app after a change.

Compose forwards the setting with a five-minute fallback. The local run script exports `.env`. This slice adds no migration.

Use `make verify` for the complete suite. The hold service creates a hold or raises a conflict. The creation service adds HTTP replay. Confirmation, cancellation and the expiry worker use the same slot-first lock order.


## Transactional confirmation (Milestone 3, slice 3.3)

`ReservationConfirmationService.confirm(reservationId, customerUsername)` confirms an owned hold through a secured Spring service proxy. The caller must be an authenticated customer with a matching username and an enabled persisted customer account.

The service first resolves the owner's slot ID without loading a reservation entity. It locks that slot, reloads the reservation and refreshes managed state. It then samples database time and applies the existing domain transition. Missing and other customers' reservations share the same not-found result.

A live hold confirms only before its deadline and slot start. Repeating confirmation returns the same state and timestamps, including after the original deadline or slot start. Terminal reservations cannot become active again. Rejected overdue confirmation does not commit expiry cleanup. Hold acquisition already performs that cleanup when rebooking.

The service flushes inside its transaction and returns a response DTO. An enclosing transaction failure still rolls back the update. The algorithm assumes the existing PostgreSQL READ COMMITTED isolation and uses the same slot lock as hold acquisition. Its transaction timeout is ten seconds.

Run `make verify` for ownership, state, rollback and independent-connection race tests. Confirmation remains internal.

Payments are not implemented. The revised roadmap includes full funding before confirmation, refunds, disputes, wallets, a double-entry general ledger, BNPL, Excel reports and recovery. The planned wallet supports customer top-ups, booking payments and withdrawals, plus provider balances and payouts. Both provider-managed BNPL and Boka-managed installments are planned. Installments use a separate credit-approval gate rather than claiming full cash payment. These features are not implemented. The current payment-free confirmation service remains internal. Production security and payment activation remain pending.


## Reservation-aware availability (Milestone 3, slice 3.4)

The existing authenticated slot browse route now calculates occupancy. SlotRepository projects directly into SlotResponse with a correlated NOT EXISTS check. It returns no reservation/customer identifiers. Historical rows do not duplicate slots or page counts.

SlotService samples PostgreSQL clock_timestamp() once. Content and count reuse that cutoff for future-start filtering, and content uses it for hold expiry. Deadline equality means expired. CONFIRMED remains occupied after its original hold deadline. Browse neither changes reservation state nor takes booking row locks.

Availability describes committed state at read time and can become stale immediately. Content/count statements and later pages are not one global snapshot under READ COMMITTED. Acquisition still rechecks under a slot lock and performs overdue cleanup before replacement.

Run `make verify` for deadline, state, paging, privacy, bounded-query and concurrent-writer checks. Run `make up` for Docker.


## Idempotent creation (Milestone 4, slice 4.1)

ReservationCreationService.create(slotId, key, customerUsername) is the internal retry-safe entry point. It reuses ReservationHoldService inside the same transaction. V5 adds durable request records with a unique customer/operation/key constraint. Existing migrations remain unchanged.

A new request creates the hold and stores its original 201 status and JSON body. Identical retries return those exact values, even after confirmation or expiry. The replay does not extend expiry or recreate a booking. Reusing a key for another slot raises a domain conflict. Keys are case-sensitive ASCII values of 1–128 letters, digits, dots, underscores, colons or hyphens.

PostgreSQL `ON CONFLICT` coordinates concurrent claims. The service checks the account before it replays a response. A business or storage failure rolls back the key claim and any hold changes. Successful records currently have no retention deadline. The controller writes the stored JSON directly.

Run `make verify` for key, replay, ownership, rollback, concurrency and migration tests. Slice 4.2 adds internal owned cancellation. Payments, wallets and accounting remain planned.


## Owned cancellation (Milestone 4, slice 4.2)

ReservationCancellationService cancels an owned live hold or confirmed reservation before the slot starts. An overdue hold follows the expiry path. A repeated cancellation returns the same status and timestamp, even after rebooking.

Cancellation and confirmation use the same owner lookup and PostgreSQL slot lock. The service checks current state and database time after locking. The CANCELLED transition releases the slot on commit. A rollback restores the previous state.

Run `make verify` for cancellation rules, ownership, rollback and lock races. Run `make up` for the local API. The cancellation route remains pending.

## Bounded hold expiry (Milestone 4, slice 4.3)

The scheduler starts after 30 seconds and waits 30 seconds after each batch. Each batch expires up to 100 overdue holds. PostgreSQL skips busy slots so other work can continue. The next runs can retry any skipped slots.

Run `make up` to apply V6 and start the worker. Set `BOKA_RESERVATION_EXPIRY_ENABLED=false` in `.env` to pause the worker. To change the delay, set `BOKA_RESERVATION_EXPIRY_DELAY` to a positive ISO-8601 duration, such as `PT15S`. Run `make up` again to apply changes in Docker. For host development, stop and start `make run`.

A hold deadline still applies when the worker is paused. Run `make verify` for batch, locking, rollback and startup recovery tests.

## Create a hold over HTTP (Milestone 4, slice 4.4)

Run `make up` and load `.env` as shown in Quick start. Set `slot_id` to a future, available slot UUID. Send the request as a customer in the local or demo profile.

Use hyphenated UUID text for IDs in both reservation requests. Uppercase and lowercase hex digits are valid.

```sh
curl -si --user "customer1:$DEMO_CUSTOMER1_PASSWORD" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: first-hold-001' \
  -d "{\"slotId\":\"$slot_id\"}" \
  "http://localhost:${APP_PORT:-8080}/api/v1/reservations"
```

The response is `201 Created` with the hold ID, slot ID, status and timestamps. Repeat the same key and slot to get the original status and JSON. The replay retains the original deadline, even after expiry. Use a new key for a new hold.

| Status | Condition |
| --- | --- |
| 400 | Missing or invalid key or slot ID |
| 401 | Missing or invalid credentials |
| 403 | Customer role or local/demo profile required |
| 404 | Slot missing or its provider inactive |
| 409 | Slot unavailable or key already used with another slot |
| 503 | Database connection failure at transaction start or a database operation unavailable after start |

Omit the key to try `400`. Reuse a successful key with another slot UUID to try `409`. If the creation transaction rolls back, retry with the same key. If a response is lost after commit, the request replays the saved result.

## Read a reservation (Milestone 4, slice 4.4)

Set `reservation_id` to the `id` from your hold creation response. Send this request as the same customer in the local or demo profile.

```sh
curl -si --user "customer1:$DEMO_CUSTOMER1_PASSWORD" \
  "http://localhost:${APP_PORT:-8080}/api/v1/reservations/$reservation_id"
```

`200 OK` returns the committed reservation status and timestamps. An overdue hold can still show `HELD` until cleanup commits `EXPIRED`. The deadline still governs confirmation.

Try the same ID as `customer2` to get `404`. A missing reservation also returns `404`. An invalid UUID returns `400`. Missing or invalid credentials return `401`. Administrators and customers outside the local and demo profiles receive `403`.
