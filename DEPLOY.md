# Deploying GateKeeper on Vercel

Vercel runs each service from its `Dockerfile` (Container Runtime, beta, free on the Hobby plan).
The databases live on free external services. Kafka isn't needed in the cloud: the gateway sends
traffic events to Analytics over HTTP and picks up route changes by reloading them.

You will create **3 Vercel projects** from the same GitHub repo, one per folder:

| Vercel project | Root Directory | Needs |
|---|---|---|
| `gatekeeper-analytics` | `analytics-service` | MongoDB Atlas |
| `gatekeeper-management` | `api-management-service` | MySQL (Aiven) |
| `gatekeeper-gateway` | `smart-gateway-service` | Redis (Upstash) |

Keep a notes file open: you'll copy a few values from one step into later ones.

---

## Step 1: Two secrets

In Terminal, run this **twice** and save both outputs in your notes as **JWT secret** and **Ingest token**:

```bash
openssl rand -hex 32
```

Also pick two private passwords (12+ characters) and save them as **Admin password** and **Gateway password**.
They replace the public demo passwords from the README.

## Step 2: MySQL on Aiven (free)

1. Sign up at **aiven.io** → **Create service** → **MySQL** → **Free plan** → pick a region near you → **Create**.
2. When it's running, open the service **Overview** and copy **Host**, **Port**, **User** and **Password** into your notes.
3. Your **DB URL** is `jdbc:mysql://` + Host + `:` + Port + `/defaultdb?sslMode=REQUIRED`
   (for example `jdbc:mysql://mysql-abc.aivencloud.com:12345/defaultdb?sslMode=REQUIRED`).

## Step 3: MongoDB Atlas (free)

1. Sign up at **mongodb.com/atlas** → create a **free M0** cluster.
2. **Database Access** → add a user with a password. **Network Access** → **Allow access from anywhere** (`0.0.0.0/0`),
   because Vercel doesn't have fixed IP addresses.
3. **Connect** → **Drivers** → copy the connection string, put your password into it, and add the database name
   `gatekeeper_analytics` right after `.mongodb.net/`. Save it as **MongoDB URI**.

## Step 4: Redis on Upstash (free)

1. Sign up at **upstash.com** → **Create Database** (Redis) → free plan.
2. On the database page, copy the URL that starts with `rediss://` and save it as **Redis URL**.

## Step 5: Analytics on Vercel

1. Sign up at **vercel.com** with your GitHub account → **Add New… → Project** → import **Gatekeeper**.
2. **Root Directory** → **Edit** → choose `analytics-service`. Framework preset: **Other** (Vercel detects the `Dockerfile`).
3. **Environment Variables**, add:

| Name | Value |
|---|---|
| `MONGODB_URI` | your **MongoDB URI** |
| `JWT_SECRET` | your **JWT secret** |
| `INGEST_TOKEN` | your **Ingest token** |
| `KAFKA_ENABLED` | `false` |

4. **Deploy**. When it's done, copy the domain (like `gatekeeper-analytics.vercel.app`) and save it as
   **Analytics URL**, with `https://` in front.

## Step 6: Management + dashboard on Vercel

Same as step 5, but Root Directory `api-management-service` and these variables:

| Name | Value |
|---|---|
| `DB_URL` | your **DB URL** |
| `DB_USERNAME` | your Aiven **User** |
| `DB_PASSWORD` | your Aiven **Password** |
| `JWT_SECRET` | your **JWT secret** (same as step 5) |
| `PLATFORM_ADMIN_PASSWORD` | your **Admin password** |
| `GATEKEEPER_GATEWAY_PASSWORD` | your **Gateway password** |
| `ANALYTICS_URL` | your **Analytics URL** |
| `KAFKA_ENABLED` | `false` |

Deploy, then save its domain (with `https://`) as **Management URL**. Open it: that's your dashboard.
Sign in as `platform-admin` with your **Admin password**.

## Step 7: Gateway on Vercel

Root Directory `smart-gateway-service`, and these variables:

| Name | Value |
|---|---|
| `GATEKEEPER_MANAGEMENT_URL` | your **Management URL** |
| `GATEKEEPER_GATEWAY_PASSWORD` | your **Gateway password** (same as step 6) |
| `REDIS_URL` | your **Redis URL** |
| `ANALYTICS_URL` | your **Analytics URL** |
| `INGEST_TOKEN` | your **Ingest token** (same as step 5) |
| `KAFKA_ENABLED` | `false` |

Deploy, then save its domain (with `https://`) as **Gateway URL**.

## Step 8: Let the dashboard read analytics

In the **analytics** Vercel project → **Settings → Environment Variables**, add `DASHBOARD_ORIGIN` = your
**Management URL**, then **Deployments → ⋯ → Redeploy**.

## Step 9: Try it

1. In the dashboard: create organization `FoodFast` / slug `foodfast`, set it **ACTIVE**, add its admin.
2. Sign in as the FoodFast admin → **Routes → + New route**:
   path `/foodfast/orders/**`, target `https://httpbin.org`, prefix `/anything`, methods `GET, POST`, tick **Active**.
3. Within about 30 seconds the gateway serves it: open your **Gateway URL** + `/foodfast/orders/42` in a browser.
4. The dashboard's **Traffic** page shows the requests.

## Good to know

- **Cold starts:** a service that had no traffic for 5 minutes sleeps; the next request takes ~10 s while Java starts.
- **Route changes** reach the gateway when it starts and every 30 s while it's awake (no Kafka in the cloud).
- **Free tiers:** Aiven, Atlas and Upstash free plans can pause after inactivity; log in to wake them if the app errors.
