# Risk-Aware Crypto Trading Simulator

An educational paper-trading project for practicing with virtual USD. Phase 1 provides a persistent account, a $10,000 opening balance, and authenticated account lookup. A React client supports registration, sign-in and account overview. Swagger UI provides a second way to exercise the API. All balances are virtual. This project is for learning and does not provide financial advice.

## Run locally on Windows

Use PowerShell with a Java 21 JDK on PATH and Docker Desktop running with Linux containers and Compose v2. The Maven wrapper downloads Maven on first use. Alternatively, use an existing PostgreSQL installation as described below.

```powershell
Set-Location "$HOME\Desktop\crypto"
java -version
docker compose version
. .\scripts\local-env.ps1
docker compose up -d --wait db
Set-Location backend
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html). Register, log in, copy the returned access token, choose **Authorize**, paste the token, then execute **GET /api/me**. The machine-readable contract is at [OpenAPI](http://localhost:8080/v3/api-docs). The API and Compose database bind to loopback by default. Stop the API with Ctrl+C. Run `docker compose stop db` from the project root to stop PostgreSQL and keep the data.

The initialization script creates a git-ignored `.env` with two independent random secrets and loads them into the current shell. Dot-source it again in new shells. Keep this file private and retain it when restarting with the same database volume. Rotating the signing key invalidates existing tokens. Changing a database password in the file also requires updating the corresponding database role.

### Start the React client

With the API running on port 8080, open a second PowerShell window with Node.js 22.12 or newer installed.

```powershell
Set-Location "$HOME\Desktop\crypto\frontend"
npm.cmd ci
npm.cmd run build
npm.cmd run dev
```

Open [the simulator](http://127.0.0.1:5173). Create an account, sign in and check the opening balance. Vite forwards same-origin `/api` requests to Spring Boot during development. React holds the profile in memory, uses the login token to fetch it once and clears the profile on sign-out or expiry. Refreshing the browser opens the account access screen again. The production bundle in `frontend/dist` needs a host that routes `/api` to the backend. The frontend uses TypeScript for the account response shapes and Tailwind CSS for layout utilities.

### Try the account flow in another PowerShell window

```powershell
$api = 'http://localhost:8080/api'
$body = @{ email = "student-$([guid]::NewGuid())@example.test"; password = 'local-practice-passphrase' } | ConvertTo-Json
$account = Invoke-RestMethod -Method Post -Uri "$api/auth/register" -ContentType 'application/json' -Body $body
$account
$session = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$api/me" -Headers @{ Authorization = "Bearer $($session.accessToken)" }
```

Both account responses should show the same UUID and a virtual cash balance numerically equal to 10000. Posting the same registration again should return HTTP 409. Calling `/api/me` without a token should return HTTP 401.

### Use PostgreSQL without Docker

Create a dedicated database and login role with a password using your local PostgreSQL administrator. Give the login role ownership of the database and permission to create tables in the default schema. From the root, load the local secrets and override the connection in the current shell before starting Spring Boot.

```powershell
. .\scripts\local-env.ps1
$env:DB_URL = 'jdbc:postgresql://localhost:5432/simulator'
$env:DB_USER = 'simulator'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'PostgreSQL role password' -AsSecureString)).Password
Set-Location backend
.\mvnw.cmd spring-boot:run
```

## Follow one request through the code

Sources live under `backend/src/main/java/dev/riskaware/simulator/`.

| Location | Responsibility |
| --- | --- |
| `account/AccountController.java`, `AccountApi.java` | Map JSON to validated request records and return small response records. Password hashes stay inside the persistence layer and service. |
| `account/AccountService.java` | Register an account and wallet atomically, check passwords, and load the signed-in user's profile. |
| `account/AppUser.java`, `Wallet.java` | Map the account and its one-to-one virtual wallet to relational tables. |
| `account/UserRepository.java`, `WalletRepository.java` | Spring Data JPA supplies standard database operations and an email lookup. |
| `security/SecurityConfig.java`, `TokenService.java` | Configure BCrypt and Spring Security's bearer-token filter, sign short-lived JWTs, and verify their signatures, issuer and time claims. |
| `account/ApiErrors.java` | Format validation and service errors as HTTP problem details without echoing submitted passwords. |
| `backend/src/main/resources/db/migration/` | Flyway applies versioned schema changes at startup. Hibernate validates the mappings. |
| `backend/src/test/java/` | Exercise HTTP requests, security filters, services, migrations, and repositories together. |

Registration normalizes the email, hashes the password with BCrypt cost 12, flushes the user insert, and saves the opening wallet. The unique constraint arbitrates simultaneous registrations. The service transaction commits both writes together. Login compares the submitted password against the stored salted hash. For an unknown email it performs a dummy hash comparison and returns the same invalid-credentials response.

After login the client sends the signed JWT in the Authorization header. Spring Security verifies it before the controller receives the request. The subject is the account UUID. The profile service looks up both rows by that UUID. Client input cannot select another wallet.

### Phase 1 schema

| Table | Columns and invariants |
| --- | --- |
| `app_users` | UUID primary key, normalized unique email (254 characters), BCrypt hash, UTC creation instant. |
| `wallets` | User ID is both primary key and foreign key. Cash uses NUMERIC(19,2) with a nonnegative check. Registration sets 10000.00 once. |

Java uses `BigDecimal` to represent virtual dollars exactly. The opening balance is a grant. Fees, historical prices and profit/loss calculations belong to the trading phases.

### HTTP contract

| Method and route | Input | Success | Expected failures |
| --- | --- | --- | --- |
| POST `/api/auth/register` | Email and password | 201 with id, email, virtualCashUsd | 400 invalid input, 409 duplicate email |
| POST `/api/auth/login` | Email and password | 200 with accessToken, tokenType and expiresInSeconds | 400 invalid input, 401 invalid credentials |
| GET `/api/me` | Authorization bearer header | 200 with own id, email and virtualCashUsd | 401 missing, invalid or expired token |

Passwords must contain at least 12 characters and fit BCrypt's 72-byte UTF-8 input limit. Emails are trimmed and lowercased consistently. Tokens are signed with HS256 and issued for 15 minutes. Spring Security allows a small clock skew during validation. Tokens contain the user ID, issuer and timestamps. Treat them as credentials.

## Verification

From `backend`, run `.\mvnw.cmd clean verify`. The default suite starts Spring Boot with MockMvc and an isolated H2 database in PostgreSQL compatibility mode. It runs the Flyway migration and exercises registration, password hashing, normalized duplicates, validation, login, owner isolation, missing/tampered/expired tokens, API documentation, and rollback. Mockito injects a wallet-save failure after the user insert so the test can prove that the transaction discarded the user.

For PostgreSQL-specific verification with the running Compose service, use a **dedicated test database**. The tests create accounts and leave rows there. Run the database creation command once, then repeat the Maven command as needed.

```powershell
Set-Location "$HOME\Desktop\crypto"
. .\scripts\local-env.ps1
docker compose exec -T db createdb -U simulator simulator_test
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5432/simulator_test'
$env:TEST_DB_USER = 'simulator'
$env:TEST_DB_PASSWORD = $env:DB_PASSWORD
Set-Location backend
.\mvnw.cmd clean verify
```

## Interview notes

| Question | Concise answer |
| --- | --- |
| Why a modular monolith? | One deployment and one database keep local development manageable. Feature packages still give account, market data, and trading distinct responsibilities. |
| Why PostgreSQL and Flyway? | Foreign keys, uniqueness, decimal types and transactions enforce invariants across requests. Flyway makes the schema reproducible across machines. |
| Why put the transaction on the service? | Registration spans two repository calls. The service describes the whole operation, so both writes share one commit or rollback. |
| Why hash passwords if login uses JWT? | BCrypt protects the stored password verifier. The JWT authenticates subsequent requests after the user proves knowledge of the password. |
| Why use Spring Security's JWT support? | It centralizes bearer parsing and signature and timestamp checks. Controllers receive an authenticated principal. |
| Why a database constraint for duplicate email? | Two requests can pass an application-level lookup concurrently. The unique index allows only one row to commit. |
| How would you revoke a stolen token? | This phase relies on short expiry. Immediate per-user revocation would require additional server-side state. |
| What would you change before public deployment? | Add HTTPS, login throttling, password recovery, email verification, managed secrets and a dependency support review. Local Swagger testing uses HTTP on loopback. |

## Development boundary

Phase 2 will introduce orders, holdings, immutable trades, idempotency keys and spending concurrency tests. Phase 3 will supply timestamped quotes, WebSocket updates and historical data. Phase 4 will add the React dashboard and trading interface. Phase 5 will add historical risk and pre-trade limit checks. Phase 6 will verify the combined workflows. Phase 1 can be demonstrated independently using the account flow above.
