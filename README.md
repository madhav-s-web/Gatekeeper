# GateKeeper — Multi-tenant API Gateway

Three Spring Boot 4 / Java 21 microservices plus a web dashboard.

## 🌐 Live demo (deployed on Vercel)

| | Link |
|---|---|
| **Dashboard** (control plane) | https://gatekeeper-api-management-service.vercel.app |
| **Gateway** — a live route, proxied to httpbin.org | https://gatekeeper-smart-gateway-service.vercel.app/foodfast/orders/42 |
| Gateway health | https://gatekeeper-smart-gateway-service.vercel.app/health |
| Analytics health | https://gatekeeper-analytics-service.vercel.app/health |

Open the gateway link and you'll get httpbin's echo of the forwarded request: the gateway matched
`/foodfast/orders/**`, rewrote the path to `/anything/42`, added an `X-Gateway: gatekeeper` header and recorded
the call for the dashboard's **Traffic** page. Dashboard logins are private; demo access on request.
Services sleep when idle, so the first request can take ~15 s. How it's deployed: [DEPLOY.md](DEPLOY.md).

## Architecture

```
 Admin (browser) ──► API Management Service :8081 ──► MySQL
   dashboard            │ routes feed     │ Kafka: gateway-configuration-events
                        ▼                 ▼
 Client ──────────► Smart Gateway :8080 ──────► target services (orders, payments, …)
                        │  Redis: rate limits + idempotency keys
                        ▼
                   Kafka: gateway-access-events ──► Analytics Service :8082 ──► MongoDB
```

| Service | What it does |
|---|---|
| **api-management-service** | Platform admins create organizations (tenants) and one tenant admin each. Tenant admins configure their routes: path, target, allowed methods, rate limit, timeout, idempotency, header rules. Every change is versioned, audited and announced on Kafka. Serves the dashboard at `http://localhost:8081`. |
| **smart-gateway-service** | Matches each request to a route (most specific pattern wins), enforces method, rate limit (Redis) and idempotency (Redis), applies header rules, forwards to the target with the route's timeout, and publishes an access event. Keeps serving its last routes if API Management is down; refreshes on Kafka events and every 30 s. |
| **analytics-service** | Stores access events in MongoDB (auto-deleted after 30 days) and serves per-route traffic stats. Shown on the dashboard's **Traffic** page. |

## Run it

```bash
docker compose up -d
```

Then start each service in its own terminal tab:

```bash
cd api-management-service && ./mvnw spring-boot:run
```
```bash
cd smart-gateway-service && ./mvnw spring-boot:run
```
```bash
cd analytics-service && ./mvnw spring-boot:run
```

Open **http://localhost:8081** and sign in with the demo account `platform-admin` / `platform123`
(seeded by Flyway `V3`; change it outside local development).

1. **Organizations → + New organization**: name `FoodFast`, slug `foodfast`. Set its status to **ACTIVE**.
2. **Admin**: create `foodfast-admin` / `foodfast123`. Sign out, then sign in with the **FoodFast** shortcut.
3. **Routes → + New route**: path `/foodfast/orders/**`, target `http://localhost:8093`, prefix `/orders`, tick **Active**.
4. Call it through the gateway: `curl -i http://localhost:8080/foodfast/orders/42`
   (forwarded to `http://localhost:8093/orders/42`).
5. **Traffic** shows the requests.

## API

| | Endpoint | Role |
|---|---|---|
| Auth | `POST /api/auth/login` | public |
| Organizations | `GET/POST /api/platform/organizations`, `GET/PUT/DELETE /{id}`, `PATCH /{id}/status` | PLATFORM_ADMIN |
| Tenant admin | `GET/POST/PUT/DELETE /api/platform/organizations/{id}/tenant-admin` | PLATFORM_ADMIN |
| Routes | `GET/POST /api/tenant/routes`, `GET/PUT/DELETE /{id}`, `PATCH /{id}/status` | TENANT_ADMIN |
| History | `GET /api/tenant/route-versions/{routeId}`, `GET /api/tenant/audit-records` | TENANT_ADMIN |
| Own org | `GET/PUT /api/tenant/organization` | TENANT_ADMIN |
| Gateway feed | `GET /api/gateway/routes` | GATEWAY_SERVICE |
| Analytics | `GET /api/tenant/analytics/summary`, `/events`; `GET /api/platform/analytics/organizations/{id}/summary` | TENANT_ADMIN / PLATFORM_ADMIN |

## Gateway behaviour

| Situation | Response |
|---|---|
| No route matches | `404` |
| Method not allowed | `405` + `Allow` header |
| Over `requestsPerMinute` | `429` + `Retry-After` |
| POST/PATCH on an idempotent route without `Idempotency-Key` | `400` |
| Same key, same request | stored response replayed, `Idempotent-Replayed: true` (target called once) |
| Same key, different request | `422`; still running → `409` |
| Target too slow / unreachable | `504` / `502` |
| Redis down (rate limit or idempotency route) | `503` — fails closed so limits and "only once" are never silently skipped |

## Configuration

| Variable | Default | Used by |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:mysql://localhost:3306/gatekeeper`, `root`, `123456` | management |
| `JWT_SECRET` | demo value | management (signs), analytics (verifies) |
| `GATEKEEPER_KAFKA_SERVERS` | `localhost:9092` | all |
| `GATEKEEPER_MANAGEMENT_URL`, `GATEKEEPER_GATEWAY_USERNAME/PASSWORD` | `http://localhost:8081`, `gateway-service` / `gateway123` | gateway |
| `MONGODB_URI` | `mongodb://localhost:27017/gatekeeper_analytics` | analytics |

## Tests

Each service has tests that run without MySQL, Kafka, Redis or MongoDB (H2, fake servers, mocks):

```bash
cd api-management-service && ./mvnw test
```
