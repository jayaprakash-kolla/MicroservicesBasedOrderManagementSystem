# Order Management System — Event-Driven Microservices

Five Spring Boot services implementing a distributed order flow with the **saga pattern**. Each service owns its own database, they communicate only through Kafka events, and a failed payment triggers a compensating transaction that returns the reserved stock.

The interesting part is not the happy path — it is what happens when step 3 fails after step 2 already committed.

---

## Contents

1. [Why a saga](#why-a-saga)
2. [Architecture](#architecture)
3. [The saga, step by step](#the-saga-step-by-step)
4. [Tech stack](#tech-stack)
5. [Project structure](#project-structure)
6. [Prerequisites](#prerequisites)
7. [Running it locally](#running-it-locally)
8. [Trying it out](#trying-it-out)
9. [Running the tests](#running-the-tests)
10. [Configuration reference](#configuration-reference)
11. [Design decisions](#design-decisions)
12. [Troubleshooting](#troubleshooting)

---

## Why a saga

In a monolith, "create order, reserve stock, take payment" is one database transaction. Roll it back and everything undoes itself.

Here, those three steps live in three services with three separate databases. There is no shared transaction to roll back. So instead each step publishes an event, the next step reacts to it, and any step that fails triggers **compensating actions** that explicitly undo the earlier ones.

That is a saga. This project implements the **choreography** flavour: no central coordinator, each service just knows which events it cares about and what to do about them.

---

## Architecture

```
                             Client
                               |
                               v
                    +---------------------+
                    |     API Gateway     |  :8080
                    +---------------------+
                               |
              +----------------+----------------+
              |                |                |
              v                v                v
     +---------------+ +----------------+ +---------------+
     | order-service | | inventory-svc  | | payment-svc   |
     |     :8081     | |     :8082      | |    :8083      |
     +---------------+ +----------------+ +---------------+
        | order_db |      | inventory_db |    | payment_db |
              \             |                /
               \            |               /
                +-----------+--------------+
                            |
                      +-----------+
                      |   Kafka   |  :9092
                      +-----------+

     All three also register with Eureka (discovery-server :8761)
```

Each service has its own H2 database that no other service can reach. That constraint is what forces the event-driven design — there is no way to "just join the tables".

---

## The saga, step by step

### Happy path

| # | Service | Consumes | Does | Publishes |
|---|---------|----------|------|-----------|
| 1 | order | *(HTTP POST)* | Saves order as `PENDING` | `OrderCreatedEvent` → `order-created` |
| 2 | inventory | `order-created` | Reserves stock for every line | `InventoryReservedEvent` → `inventory-reserved` |
| 2a | order | `inventory-reserved` | Status → `INVENTORY_RESERVED` | — |
| 3 | payment | `inventory-reserved` | Charges the customer | `PaymentCompletedEvent` → `payment-completed` |
| 4 | order | `payment-completed` | Status → `CONFIRMED` | — |

Note that steps 2a and 3 both consume the same topic in **different consumer groups**, so each gets its own copy.

### Failure path A — not enough stock

| # | Service | Consumes | Does | Publishes |
|---|---------|----------|------|-----------|
| 2 | inventory | `order-created` | Refuses, changes nothing | `InventoryFailedEvent` → `inventory-failed` |
| 3 | order | `inventory-failed` | Status → `CANCELLED` | — |

No compensation needed: nothing was reserved, nothing was charged.

### Failure path B — payment declined (the interesting one)

| # | Service | Consumes | Does | Publishes |
|---|---------|----------|------|-----------|
| 3 | payment | `inventory-reserved` | Gateway declines | `PaymentFailedEvent` → `payment-failed` |
| 4 | order | `payment-failed` | Status → `CANCELLED` **and compensates** | `OrderCancelledEvent` → `order-cancelled` |
| 5 | inventory | `order-cancelled` | Releases the held stock | — |

Step 5 is the compensating transaction. Without it, every declined payment would permanently leak inventory.

---

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Framework | Spring Boot 3.2, Java 17 | Records make the event contracts short and immutable |
| Messaging | Apache Kafka (`spring-kafka`) | Durable log, consumer groups, natural dead-letter story |
| Service discovery | Netflix Eureka | Gateway routes to `lb://ORDER-SERVICE`, not host:port |
| Edge routing | Spring Cloud Gateway | One public port; auth and rate limiting would live here |
| Persistence | Spring Data JPA + H2 (one DB per service) | Zero-setup local runs; swap to Postgres by changing four properties |
| Testing | JUnit 5, Mockito, AssertJ, embedded Kafka, Awaitility | Full suite runs with no Docker and no external services |

The card processor is a `SimulatedPaymentGateway` that declines any charge above a configured amount — so the failure path is reproducible on demand rather than random.

---

## Project structure

```
order-management-system/
├── pom.xml                     parent reactor
├── docker-compose.yml          Kafka + Kafka UI
│
├── common-events/              shared library, not an application
│   └── com/jayaprakash/oms/common/
│       ├── events/             the 6 event records + Topics constants
│       └── config/             KafkaMessagingConfig (producer + retry/DLT)
│
├── discovery-server/           Eureka registry            :8761
├── api-gateway/                Spring Cloud Gateway       :8080
│
├── order-service/              :8081  saga initiator, owns order state
│   └── com/jayaprakash/oms/order/
│       ├── api/                controller + DTOs + error handling
│       ├── domain/             Order, OrderLineItem, OrderStatus
│       ├── messaging/          publisher + OrderSagaListener (4 listeners)
│       ├── repository/
│       └── service/            OrderService — the state machine
│
├── inventory-service/          :8082  reserves and releases stock
│   └── com/jayaprakash/oms/inventory/
│       ├── domain/             InventoryItem, Reservation
│       ├── messaging/          publisher + InventorySagaListener (2 listeners)
│       └── service/            InventoryService — all-or-nothing reservation
│
└── payment-service/            :8083  charges the customer
    └── com/jayaprakash/oms/payment/
        ├── domain/             Payment, PaymentStatus
        ├── messaging/          publisher + PaymentSagaListener (1 listener)
        └── service/            PaymentService + PaymentGateway
```

**Reading order if you are new to the code:** `Topics.java` → `OrderSagaListener` → `OrderService` → `InventoryService.releaseForOrder`. Those four files contain the entire saga.

---

## Prerequisites

- **JDK 17 or newer** — check with `java -version`
- **Maven 3.8+** — check with `mvn -version` (or use your IDE's bundled Maven)
- **Docker Desktop** — needed to *run* the system, not to test it

---

## Running it locally

### Step 1 — Build everything once

```bash
cd order-management-system
mvn clean install -DskipTests
```

This must be run from the root. It builds `common-events` first and installs it into your local repository so the three services can depend on it.

### Step 2 — Start Kafka

```bash
docker compose up -d
docker compose ps        # wait until oms-kafka is healthy
```

Kafka is on `localhost:9092`. A web UI for browsing topics and messages is at **http://localhost:8090**.

### Step 3 — Start the services, in this order

Open five terminals. **Start the discovery server first** and give it about 20 seconds; the others will keep retrying registration but the gateway cannot route until at least one service has registered.

```bash
# Terminal 1
cd discovery-server && mvn spring-boot:run

# Terminal 2
cd order-service && mvn spring-boot:run

# Terminal 3
cd inventory-service && mvn spring-boot:run

# Terminal 4
cd payment-service && mvn spring-boot:run

# Terminal 5
cd api-gateway && mvn spring-boot:run
```

From an IDE: import the root `pom.xml` as a Maven project and run each `*Application` main class in the same order.

### Step 4 — Confirm everything registered

Open **http://localhost:8761** and check that `API-GATEWAY`, `ORDER-SERVICE`, `INVENTORY-SERVICE`, and `PAYMENT-SERVICE` all appear as registered instances.

Inventory-service seeds three SKUs at startup:

| SKU | Stock | Purpose |
|---|---|---|
| `LAPTOP-001` | 100 | Normal orders |
| `PHONE-002` | 50 | Normal orders |
| `RARE-003` | 1 | Order 2 or more to trigger an inventory failure |

---

## Trying it out

All requests go through the gateway on port 8080.

### 1. The happy path

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
        "customerId": "cust-1",
        "items": [ { "skuCode": "LAPTOP-001", "quantity": 2, "price": 500.00 } ]
      }'
```

You get back `202 Accepted` with the order in `PENDING`. Watch the four terminals — within a second you should see the whole saga run. Then check the result:

```bash
curl http://localhost:8080/api/v1/orders/1
```

Status should now be `CONFIRMED`. Stock has dropped:

```bash
curl http://localhost:8080/api/v1/inventory
```

### 2. Failure path A — inventory refuses

`RARE-003` has exactly one unit, so ask for five:

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
        "customerId": "cust-1",
        "items": [ { "skuCode": "RARE-003", "quantity": 5, "price": 100.00 } ]
      }'
```

The order goes to `CANCELLED` with a `failureReason` naming the SKU, and stock is untouched — no partial reservation.

### 3. Failure path B — payment declines and stock comes back

The simulated gateway declines anything above `payment.decline-above-amount` (default 100000). Order enough to cross it:

```bash
# Check stock BEFORE
curl http://localhost:8080/api/v1/inventory

curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
        "customerId": "cust-1",
        "items": [ { "skuCode": "LAPTOP-001", "quantity": 3, "price": 50000.00 } ]
      }'

# Wait a second, then check stock AFTER
curl http://localhost:8080/api/v1/inventory
```

This is the part worth watching in the logs. In order:

```
order-service      Publishing OrderCreatedEvent: orderId=3
inventory-service  Reserved 1 line(s) for order 3          <- 3 units held
payment-service    CHARGE DECLINED customer=cust-1 amount=150000.00
order-service      Order 3 CANCELLED (payment) and compensation published
inventory-service  Released 1 hold(s) for cancelled order 3 <- 3 units returned
```

Available stock ends up exactly where it started. `reservedQuantity` returns to 0. Check the declined payment too:

```bash
curl http://localhost:8080/api/v1/payments
```

### 4. Watch it in Kafka

Open **http://localhost:8090**, go to Topics, and read the messages on `order-created`, `inventory-reserved`, `payment-failed`, and `order-cancelled`. The saga is visible as a literal sequence of messages.

### API summary

| Method | Path | Service | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/orders` | order | Place an order (starts the saga) |
| `GET` | `/api/v1/orders/{id}` | order | Current status of one order |
| `GET` | `/api/v1/orders?customerId=` | order | List orders |
| `GET` | `/api/v1/inventory` | inventory | Stock levels, available and reserved |
| `POST` | `/api/v1/inventory` | inventory | Add stock for a SKU |
| `GET` | `/api/v1/payments` | payment | All charge attempts |
| `GET` | `/api/v1/payments/order/{orderId}` | payment | Payment for one order |

Each service also exposes an H2 console at `http://localhost:{port}/h2-console` (JDBC URL `jdbc:h2:mem:orderdb`, `inventorydb`, or `paymentdb`; user `sa`, empty password).

---

## Running the tests

**No Docker, no Kafka, no Eureka needed.** The integration tests boot an embedded Kafka broker in-process and the `test` profile disables the Eureka client.

```bash
# everything, from the root
mvn test

# one module
cd order-service && mvn test

# one class
mvn test -Dtest=OrderServiceTest
```

In IntelliJ or VS Code, right-click any test class and run it directly.

### What is covered

| Test class | Module | Type | What it proves |
|---|---|---|---|
| `OrderServiceTest` | order | unit | State machine: totals, transitions, idempotency guards, and that a payment failure publishes compensation while an inventory failure does not |
| `OrderControllerTest` | order | web slice | 202 on accept, per-field validation errors, 404 shape |
| `OrderSagaIntegrationTest` | order | embedded Kafka | Real round trip: happy path to `CONFIRMED`, both failure paths, duplicate events ignored |
| `InventoryServiceTest` | inventory | unit | All-or-nothing reservation, unknown SKU, duplicate suppression, release |
| `InventorySagaIntegrationTest` | inventory | embedded Kafka | Stock moves on `order-created`, comes back on `order-cancelled`, reserves once on redelivery |
| `SimulatedPaymentGatewayTest` | payment | unit | Threshold behaviour including the boundary |
| `PaymentServiceTest` | payment | unit | Every path publishes exactly one event; a duplicate never reaches the gateway |
| `PaymentSagaIntegrationTest` | payment | embedded Kafka | Accept and decline branches, one payment row per order |

Each service's integration test runs that service for real and **plays the part of the others** by publishing the events they would have published. That keeps each test fast and independent while still exercising real serialisation, real listeners, and real transactions.

Asynchronous assertions use Awaitility rather than `Thread.sleep`, so they finish as soon as the pipeline settles and stay stable on a loaded machine.

---

## Configuration reference

| Property | Where | Default | Meaning |
|---|---|---|---|
| `payment.decline-above-amount` | payment-service | `100000` | Charges strictly above this are declined |
| `spring.kafka.bootstrap-servers` | all | `localhost:9092` | Override with `KAFKA_BOOTSTRAP_SERVERS` |
| `eureka.client.service-url.defaultZone` | all | `http://localhost:8761/eureka` | Override with `EUREKA_URL` |
| `server.port` | each service | 8080–8083, 8761 | HTTP port |

**Lower `payment.decline-above-amount` to 100** in `payment-service/src/main/resources/application.yml` if you want every order to fail and want to watch compensation repeatedly.

### Switching from H2 to PostgreSQL

Add the driver to the service's `pom.xml` and replace its datasource block. Give each service its **own database** — sharing one would defeat the entire design:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb   # inventorydb, paymentdb for the others
    driver-class-name: org.postgresql.Driver
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: validate
```

No application code changes.

---

## Design decisions

**Choreography, not orchestration.** No central saga coordinator: each service reacts to events and knows how to undo its own work. That keeps services independent, at the cost of the flow being spread across four listener classes rather than one. For a saga with three steps that is the right trade; past six or seven steps, an orchestrator becomes easier to reason about.

**Payment listens for `inventory-reserved`, not `order-created`.** Charging first and discovering afterwards that the goods were unavailable would mean issuing refunds instead of simply declining. Step ordering in a saga is a design decision, not an accident.

**One topic per event type.** More topics than strictly necessary, but each `@KafkaListener` method then handles exactly one type with no manual type mapping, which makes the flow much easier to trace. Every message is keyed by order id, so all events for one order stay ordered within their topic.

**Reservation is all-or-nothing.** Inventory checks every line before mutating anything. A partially reserved order would hold stock that could never be sold or released.

**Idempotency everywhere, enforced differently in each service.** Order-service guards on order status; inventory-service checks for existing reservations and filters on `RESERVED` when releasing; payment-service has a unique constraint on `order_id` so the database itself refuses a double charge. Kafka delivers at least once, so every consumer needs an answer to "what if I see this twice?"

**Optimistic locking on stock rows.** Two orders racing for the last unit will conflict; `@Version` makes the loser fail loudly (and get retried) instead of both succeeding and overselling.

**Deterministic payment failures.** A random gateway would produce a test suite that fails one run in ten. A threshold makes the compensating path reproducible on demand.

**Retry then dead-letter.** A failing listener retries twice a second apart, then the message goes to `<topic>.DLT` rather than blocking the partition forever. `IllegalArgumentException` is marked non-retryable, since a malformed event will still be malformed on the third attempt.

**Save before publish.** Order-service commits the row and then publishes. If publication failed, the order would sit in `PENDING` for a reconciliation job to find — recoverable. Publishing first would be worse: downstream services could act on an order that does not exist.

### What a production build would add

- **Transactional outbox** — right now a crash between commit and publish strands an order in `PENDING`. The outbox pattern writes the event to a table in the same transaction and a relay publishes it, closing that window.
- **A reconciliation job** — scan for orders stuck in `PENDING` or `INVENTORY_RESERVED` past a timeout and cancel them.
- **Distributed tracing** (Micrometer Tracing + Zipkin) — following one order across four services currently means reading four terminals.
- **Resilience4j circuit breakers** on the gateway's synchronous hops.
- **Reservation expiry** — stock held for an order whose saga never completed should time out and release itself.

---

## Troubleshooting

**`Could not resolve dependencies for common-events`**
You ran `mvn` inside a service directory before building the root. Run `mvn clean install -DskipTests` from the project root first.

**`Connection to node -1 could not be established`**
Kafka is not up. Run `docker compose ps` and confirm `oms-kafka` is healthy — first startup takes 20–30 seconds.

**Gateway returns 503 Service Unavailable**
The target service has not registered with Eureka yet. Check http://localhost:8761. Registration can take up to 30 seconds after a service starts.

**An order stays `PENDING` forever**
Inventory-service is not running or is not consuming. Check its terminal for `Received OrderCreatedEvent`, and check consumer lag in the Kafka UI at http://localhost:8090.

**An order stays `INVENTORY_RESERVED` forever**
Payment-service is down. This is exactly the failure mode a reconciliation job would handle; for now, start payment-service and it will consume the backlog from where it left off.

**Payments never decline**
Your order total is below `payment.decline-above-amount`. Either order more expensive items or lower the threshold in `payment-service/src/main/resources/application.yml`.

**Port already in use**
`mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9091`

**Messages ended up on a `.DLT` topic**
A listener threw three times. Open the dead-letter topic in the Kafka UI — the original message and the exception are both preserved in the record headers.

**Tests are slow on the first run**
Maven is downloading dependencies and each embedded Kafka broker takes a few seconds to start. Later runs are much faster.
