# Deployment preparation

## Proposed topology

One Linux DigitalOcean Droplet can run the Java process and Nginx continuously. Nginx serves the compiled React client and upgrades the price connection. Managed PostgreSQL can be reached by private hostname from the same VPC. Configure a trusted database source and certificate verification. Outbound firewall rules can permit DNS and TCP 443 to both market-data providers. These are documented platform capabilities. Exchange access from the selected region and IP needs a live check after provisioning. [Droplet networking](https://docs.digitalocean.com/products/networking/firewalls/how-to/configure-rules/) · [Managed PostgreSQL](https://docs.digitalocean.com/products/databases/postgresql/how-to/connect/) · [WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)

## Production configuration

1. From `backend`, build with Java 21 using `.\mvnw.cmd verify` on Windows or `./mvnw verify` on Linux. From `frontend`, run `npm ci` and `npm run build`. Copy the executable JAR and the contents of `frontend/dist` to the host. The proxy example uses `/srv/simulator/dist` as its document root. Only static build output belongs there.
2. Run Java under a dedicated unprivileged service user with a service manager that restarts failed processes. Use `java -jar simulator-0.5.0.jar`. Supply the variables below through a host secret store or a root-readable service environment file outside the document root and repository. Keep separate credentials for local tests. Restrict access to application logs, which can contain account identifiers or database constraint details.
3. Install a certificate and configure automatic renewal. Replace every `simulator.example` in [the Nginx site](../deploy/simulator.nginx.conf) with the public DNS name and adjust certificate paths. Include it in Nginx's `http` context. Validate with `nginx -t` before reloading. The configuration assumes Nginx receives the client connection directly, on standard ports 80 and 443. Review client IP handling and rate limits if adding another proxy.
4. Allow public access to 80/443, restrict administrative access and keep Java on loopback. Restrict PostgreSQL to the application host and authorized administrators. Configure backups and prove restoration into a separate database before accepting public accounts. The application needs schema migration privileges on its dedicated database.

| Variable | Production value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | Standard Edition example: `jdbc:postgresql://PRIVATE_HOST:PORT/simulator?sslmode=verify-full&sslrootcert=/etc/simulator/db-ca.crt`. Use the actual certificate hostname, port and CA supplied with the database. |
| `DB_USER`, `DB_PASSWORD` | Dedicated database login and a generated private password |
| `JWT_SECRET` | Independent random secret, at least 32 bytes before Base64 encoding. Generate privately, for example with `openssl rand -base64 32`. Rotation invalidates outstanding sessions. |
| `MARKET_MODE`, `MARKET_PROVIDER` | `LIVE` and `AUTO` for real public data. A live outage can block trades. Set `MOCK_FIXED` explicitly for offline demonstrations. |

## Security decisions and residual risks

- BCrypt cost 12 protects stored passwords. Bearer tokens use HS256, an issuer and a short lifetime. The browser holds tokens in memory. The server derives wallet ownership from the token. Registration returns a conflict for a registered email, so account existence is discoverable. There is no email verification, password recovery or per-token revocation.
- The production deployment serves the API, static assets and price socket under one HTTPS origin. It supplies no cross-origin permissions. Spring's WebSocket origin check is retained. The public socket contains prices only. State-changing API requests require a bearer token except registration and login.
- The edge rate-limits the authentication routes per IP and across the host, caps general API traffic per IP and limits simultaneous price sockets per IP. Shared campus networks can hit these limits. Local Vite and direct backend access bypass this edge, which is why the Java port stays private. HTTP 429 is safe to retry after waiting. For an interrupted trade, reuse the original order key.
- Validation errors explain quantities, UTF-8 password limits or risk breaches. Unexpected faults omit stack traces from HTTP responses. Never log Authorization headers, request bodies, signing secrets or connection passwords.
- The project uses Spring Boot 3.5.16, the last open-source 3.5 release. Before a public launch, migrate to a supported version or arrange supported maintenance, review then-current dependency advisories and rerun both database and browser suites. A major framework upgrade requires a separate compatibility review. [Spring release notice](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

## Acceptance checks on an approved host

1. HTTP redirects to the canonical HTTPS name. Check the certificate chain, expiry, renewal and HSTS. Anonymous `/api/me` must return 401. Production API docs should be unavailable. External clients should be unable to reach Java or PostgreSQL directly.
2. Register a disposable account. Login should open a $10,000 portfolio. Inspect the network panel for a successful WSS upgrade, subsequent snapshots and source timestamps. Leave the tab open beyond 90 seconds, then interrupt and restore connectivity to check recovery.
3. In LIVE mode verify both fresh quotes and completed daily history, then execute a small paper trade. Check its source, quote time and receipt. If the provider is inaccessible from that region, try the alternative public provider and recheck. A missing feed must block new orders.
4. Run the README's deterministic cap, retry, concurrency and rounding checks on a separate test database. Confirm the expected prior receipt on replay and a single history entry. Compare cash and positions before and after a rejected trade.
5. Check service restart and database restore with a disposable account. Confirm settled holdings and the original receipt survive. Inspect logs for unexpected disclosure. Test the site's rate limits and how its shared-IP policy affects the intended demo audience.
