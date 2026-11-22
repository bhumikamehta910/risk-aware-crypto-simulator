# Free demo on Render and Neon

The root Dockerfile builds React with Node 22, copies its compiled files into Spring's static resources, and packages one Java 21 application. Render terminates HTTPS and forwards HTTP and WebSocket upgrades to the same process. PostgreSQL on Neon owns the ledger. Only quotes, short-lived market caches and throttle counters live in the process. Local development still uses Vite, the local PostgreSQL database and the Maven wrapper.

## Before creating anything

Use a **Free Neon organization** and a **Render Hobby workspace with Free compute and no payment method**. Check Billing and usage for existing services in both accounts. With a payment method attached, Render can bill for excess bandwidth or build usage even on Free compute. If either console requires a payment method, a paid plan, a disk, or an upgrade to continue, stop at that screen to maintain the ₹0 budget. Use the included onrender.com address. [Render Free limits](https://render.com/docs/free) · [Neon plans](https://neon.com/docs/introduction/plans)

The backend uses Spring Boot 4.1.3 and Java 21. Boot manages Spring Security 7.1, Spring Data JPA, Hibernate, Jackson 3 and Flyway 12. The PostgreSQL Flyway module and the existing four versioned migrations serve both fresh and previously initialized databases. Local OpenAPI uses springdoc 3.2.1. The production profile disables its endpoints. The Docker build and runtime both use Java 21. [Boot release](https://spring.io/blog/2026/11/20/spring-boot-4-1-3-available-now/) · [Spring migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) · [4.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes) · [springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)

## 1. Set up Neon manually

1. In the Neon Console, choose **New Project** in your Free organization. Name it `risk-aware-simulator`, choose AWS **Europe (Frankfurt), eu-central-1**, and select PostgreSQL 17 if available to match local verification. Keep the default primary branch and scale-to-zero settings. Confirm the Free label before creating the project. [Neon regions](https://neon.com/docs/introduction/regions)
2. Open the project dashboard and choose **Connect**. Select the primary branch, the database (typically `neondb`) and its owner role. Disable **Connection pooling** for a direct, read-write endpoint. Flyway and Hikari share this endpoint. Hikari opens at most four database connections per app instance.
3. Copy the hostname, database, role name and password privately into Render's environment form below. Use the direct hostname exactly as Neon displays it. Keep username and password in separate variables. [Neon connection details](https://neon.com/docs/connect/connect-from-any-app)
4. A fresh Neon database starts empty. Flyway creates the schema once and records applied versions. To carry existing local demo accounts over, first back up the local database, restore a full PostgreSQL dump (including Flyway history) into an **empty** Neon database, then start the app. Use the same populated database and branch when redeploying. Do not run the test suite against it. Review ownership and row counts after any manual transfer.

## 2. Configure Render manually

After reviewing and publishing the intended revision to GitHub yourself, open Render → **New → Web Service**, connect GitHub, grant access to the simulator repository and select the branch containing the root Dockerfile. Set the following values. [Render Docker setup](https://render.com/docs/docker) · [Available regions](https://render.com/docs/regions)

| Render field | Value |
| --- | --- |
| Name and region | Choose an available name, e.g. `risk-aware-simulator-demo`. Select **Frankfurt, Germany**. |
| Language / runtime and instance | **Docker**, **Free** in the Hobby workspace |
| Root directory | Leave blank (repository root) |
| Dockerfile path and build context | `./Dockerfile` and `.` respectively |
| Docker command | Leave blank to use the image entry point |
| Health check path | `/index.html` (available once Spring serves the bundled UI) |
| Auto-deploy | **Off** for manual review before future releases |
| Persistent disk, custom domain, paid add-ons | Leave unconfigured |

Under **Environment**, enter the four private deployment values. The remaining settings are defaults or Render-provided values. Never put these secrets in the Dockerfile, GitHub, frontend variables or screenshots.

| Variable | Value / source |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://<direct-neon-host>:5432/<database>?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`. Use the Java trust store and verify the server hostname. |
| `DB_USER` | `<neon-owner-role>` with migration rights on this dedicated database |
| `DB_PASSWORD` | `<neon-role-password>`, as issued, without URL encoding |
| `JWT_SECRET` | `<base64-of-32-random-bytes>` generated privately below. Retain across restarts. Rotation expires existing sign-ins. |
| `SPRING_PROFILES_ACTIVE` | Image default `prod,render` activates both production security and Render networking. |
| `MARKET_MODE`, `MARKET_PROVIDER` | Image defaults `LIVE`, `AUTO`. Either exchange can supply real data. AUTO can reconnect through the alternative. New trades require fresh server-side prices. |
| `PORT`, `RENDER_EXTERNAL_HOSTNAME` | Supplied by Render. The app listens on all interfaces at that port and uses the platform hostname as its canonical HTTPS origin. |

Generate a new deployment signing secret in a private PowerShell window. This copies the value to the clipboard without putting it in the console output. Paste it into Render, then clear the clipboard. Keep an approved private backup.

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
Set-Clipboard -Value ([Convert]::ToBase64String($bytes))
# After pasting into Render:
Set-Clipboard -Value ' '
```

Check the compute and billing selections once more, then choose **Deploy Web Service** when ready. Wait for build completion, Flyway validation and a healthy service. Open the assigned HTTPS URL. The free service itself serves the landing page, so only Render can display a message while the instance is fully asleep. After HTML arrives the page displays a loading hint. If a request gets a temporary gateway error, the client offers retry guidance.

## 3. Verify the actual Frankfurt deployment

1. Register a disposable user, sign in and confirm $10,000 in virtual cash. Refresh a fragment URL such as `/#risk` and sign in again. The dashboard intentionally keeps JWTs in memory.
2. In browser DevTools → Network, look for a 101 upgrade on `wss://<your-host>/ws/prices`, increasing market timestamps, LIVE mode and fresh quotes for **both** assets. Disconnect and reconnect the browser network. Check that fresh frames resume. Under Risk select BTC/7 days and ETH/30 days and inspect the provider, dates and return counts. Region-specific exchange access is only verified when these checks pass from the deployed service. [Render WebSockets](https://render.com/docs/websocket)
3. Set a low maximum order value, review a purchase above it and check the limit explanation. Lower the quantity, confirm a small allowed order and record the receipt ID, cash, holdings and saved limits. A new order must fail when required quotes are stale. Never switch the deployed service to mock mode to mask a feed outage.
4. Restart the Render service, sign in to the same account and compare those records with your earlier values. Market valuation may move. Settled cash, quantity and the receipt must persist. The same committed order and retry key return the original receipt. Flyway should report the schema up to date.
5. Visit `/api/me` without a token (401) and `/v3/api-docs` (unavailable). The service accepts its canonical HTTPS browser origin for price upgrades. CORS remains closed. Spring uses the configured public name, port 443 and HTTPS scheme, without accepting forwarded headers as a source of identity. The auth limit allows ten login/registration POSTs per transport peer and 120 globally in each one-minute window. Visitors sharing a Render proxy can share that allowance. A 429 includes Retry-After. Counters reset on process restart.

## Limits and troubleshooting

**Clock synchronization.** New quotes with exchange timestamps later than the server's UTC clock are discarded. If history loads while live trades remain unavailable, compare the host's UTC time with the public exchanges. Correct the host clock and repeat the LIVE browser suite. Changing the browser's time zone or increasing the stale-price timeout cannot correct a slow server clock.

The single free instance enforces authentication throttling in Spring. The earlier standalone proxy's per-IP general API rate and per-IP socket limits are specific to that deployment. On Render, Tomcat limits aggregate connections and worker threads. Those ceilings do not guarantee fairness among clients. The public socket broadcasts prices only. Each wallet mutation still requires a valid token and a database transaction.

| Symptom | What to do |
| --- | --- |
| First request takes a minute or longer | Free Render services can spin down after 15 idle minutes. Let the instance boot and Neon wake up. Refresh once. Check Render logs and the health path if it stays unavailable. Avoid keep-awake pings. |
| Database connection or SSL error | Check the direct read-write endpoint, database name, role, raw password, JDBC prefix and Java SSL factory. Use the certificate's DNS name. Keep certificate verification enabled. Flyway retries initial connections. Hikari retires idle connections and obtains replacements. Retry an uncertain order with its original key. |
| UI loads but socket or historical data fails | Open the assigned HTTPS hostname directly. Confirm WSS uses the same hostname and port. A working browser socket can still report a disconnected upstream exchange. Check the feed status, freshness and AUTO fallback. Allow provider backoff after a 429. Insufficient daily history suppresses the risk metric. |
| Out of memory, slow start or overloaded service | Free compute has 512 MB. The image allows a 220 MB Java heap, serial GC and smaller thread stacks. Tomcat has 24 request threads and a 160-connection cap. Native memory and TLS also use RAM. Check Render's memory graph and restart logs under your expected demo traffic. These settings are a starting budget, not a verified cloud capacity. |
| Quota warning or suspension | Render currently includes 750 instance hours **per workspace per month**. Builds and outbound bandwidth have separate allowances. Unusually high outbound traffic to external services may cause suspension. Stop unnecessary usage or wait for allowance reset per the provider's policy. Check the billing screen before retrying a build. [Render Free](https://render.com/docs/free) |
| Neon usage or retention warning | Neon currently lists 100 CU-hours/project/month, 1 GB storage/project, 5 GB public transfer/project and a limited restore window. Sleeping compute preserves the branch's stored data. Check current retention, restore and suspension terms in the console and keep independent backups. Render's 30-day free database expiry concerns its own PostgreSQL product. This setup stores records in Neon. [Neon Free terms](https://neon.com/docs/introduction/plans) |

To rehearse the container on a Docker-equipped machine, run `docker build -t risk-simulator:local .` from the repository root. Supply the same runtime variables privately. For local execution use a test database reachable **from inside the container** and a TLS proxy matching the configured external hostname. The normal Vite development commands are in the README. [PostgreSQL JDBC TLS](https://jdbc.postgresql.org/documentation/ssl/) · [Render environment variables](https://render.com/docs/environment-variables)
