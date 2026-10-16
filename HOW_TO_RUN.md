# How to run

| # | What | You need |
|---|---|---|
| 1 | [Run locally (H2, zero setup)](#1-run-locally-h2-zero-setup) | JDK 21, Maven |
| 2 | [Run with PostgreSQL](#2-run-with-postgresql) | Docker |
| 3 | [Get a token for writes](#3-get-a-token-for-writes) | bash + openssl (Git Bash/WSL on Windows) |
| 4 | [Call the API](#4-call-the-api) | curl / browser / Bruno / IntelliJ / VS Code |
| 5 | [Run the tests](#5-run-the-tests) | JDK 21, Maven, Docker (for one IT) |
| 6 | [Kubernetes on k3d (recommended local cluster)](#6-kubernetes-on-k3d-k3s-in-docker) | Docker, k3d, kubectl |
| 7 | [Kubernetes on native k3s (Linux / WSL2 / VM)](#7-kubernetes-on-native-k3s) | Linux host, Docker |
| 8 | [Jenkins CI/CD](#8-jenkins-cicd-local-lab) | Docker, path 6 done |
| 9 | [Production deployment](#9-production-deployment) | cluster + registry + managed PostgreSQL + OAuth2 issuer |

Configuration reference and troubleshooting are at the end.

---

## 0. Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker | 24+ (Compose v2, BuildKit) | `docker version` |
| kubectl | 1.29+ | `kubectl version --client` |
| k3d | 5.7+ | `k3d version` (`brew install k3d` · `choco install k3d` · `curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh \| bash`) |
| Bruno CLI (optional) | latest | `npm i -g @usebruno/cli` |

**Windows:** run the `.sh` scripts from **Git Bash** or **WSL2**. If one fails with `bad interpreter: /bin/bash^M`,
run `git config core.autocrlf input` and re-clone.

---

## 1. Run locally (H2, zero setup)

```bash
mvn spring-boot:run          # or: make run
```
The default profile needs nothing installed: an **in-memory H2 database in PostgreSQL mode**, the same Flyway
migration as production, 25 demo products, Swagger UI, and a local-only JWT secret for write tokens.

Or run the jar:
```bash
mvn -B -ntp clean package -DskipTests
java -jar target/product-catalog-mvc.jar
```
PowerShell: `java -jar target\product-catalog-mvc.jar` (the defaults need no environment variables).

| URL | What |
|---|---|
| http://localhost:8080/ | Redirects to the product list |
| http://localhost:8080/api/v1/products | The API. A browser gets the **HTML** view with search and paging |
| http://localhost:8080/api/v1/products?format=json | Same data as JSON (`xml`, `text` work too) |
| http://localhost:8080/swagger-ui/index.html | Swagger UI (click **Authorize** and paste a token from section 3) |
| http://localhost:8080/v3/api-docs/v1 | OpenAPI 3 document, group `v1` |
| http://localhost:8080/actuator/health/liveness · /readiness | Probes |

Stop with Ctrl+C (graceful shutdown: in-flight requests finish first).

---

## 2. Run with PostgreSQL

**Everything in Docker Compose** (PostgreSQL 16 + the API, demo data seeded):
```bash
docker compose up -d --build        # or: make compose-up
docker compose logs -f app          # Flyway "Successfully applied 2 migrations", then "Started CatalogApplication"
docker compose down -v              # stop and delete the database volume
```
Port clash (for example the JAX-RS project is running)? `APP_PORT=9080 PG_PORT=15432 docker compose up -d`.

**The API from your IDE / Maven, PostgreSQL in Docker** (the usual dev loop):
```bash
docker compose up -d postgres       # or: make compose-db
SPRING_PROFILES_ACTIVE=postgres SEED_DATA=true DB_PASSWORD=catalog-dev-only mvn spring-boot:run
```
In IntelliJ: Run configuration → Active profiles `postgres`, environment `SEED_DATA=true;DB_PASSWORD=catalog-dev-only`.

---

## 3. Get a token for writes

Reads (`GET`, `HEAD`) are public. `POST`, `PUT`, `PATCH` and `DELETE` need a JWT bearer token with scope
`catalog.write`, otherwise **401** (no/invalid token) or **403** (token without the scope).

Locally and on k3s the API validates HS256 tokens signed with `JWT_HMAC_SECRET`; `scripts/dev-token.sh` mints them:
```bash
TOKEN=$(./scripts/dev-token.sh)                    # scope catalog.write, valid 1 h   (or: make token)
./scripts/dev-token.sh --scope "" --ttl 60         # no scope -> writes get 403
JWT_HMAC_SECRET=k3s-dev-only-jwt-secret-change-me-0123456789 ./scripts/dev-token.sh   # for the k3s cluster
```
In production there is no shared secret: the `prod` profile validates tokens from your identity provider
(`JWT_ISSUER_URI`, e.g. Entra ID / Keycloak / Auth0), which must issue the `catalog.write` scope.

---

## 4. Call the API

### Endpoints

| Method | Path | Auth | Success | Notes |
|---|---|---|---|---|
| GET | `/api/v1/products` | public | 200 | search + pagination, `X-Total-Count`, `Link` |
| GET | `/api/v1/products/{id}` | public | 200 / 304 | `ETag`; `If-None-Match` → 304 |
| POST | `/api/v1/products` | `catalog.write` | 201 | `Location` + `ETag` |
| PUT | `/api/v1/products/{id}` | `catalog.write` | 200 | full replace; optional `If-Match` → 412 if stale |
| PATCH | `/api/v1/products/{id}` | `catalog.write` | 200 | `application/merge-patch+json` (or JSON/XML); `If-Match` |
| DELETE | `/api/v1/products/{id}` | `catalog.write` | 204 | `If-Match` |

Search parameters: `q`, `category`, `minPrice`, `maxPrice`, `inStock`, `page` (0-based), `size` (default 20,
max 100), `sort=field[,asc|desc]` with field `id|name|price|stock|createdAt` (repeatable).

### curl

```bash
API=http://localhost:8080/api/v1/products
curl -s "$API?size=3" -H 'Accept: text/plain'                                   # table in the terminal
curl -s "$API?category=electronics&minPrice=1000&sort=price,desc&size=3" | jq   # JSON (the default)
curl -s "$API/1" -H 'Accept: application/xml'                                   # XML
curl -si "$API/1" | grep -i etag                                                # ETag: "1-0"
curl -si "$API/1" -H 'If-None-Match: "1-0"'                                     # 304 Not Modified

TOKEN=$(./scripts/dev-token.sh)
curl -si -X POST "$API" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"FIT-KBEL-01","name":"Kettlebell 8 kg","category":"Fitness","price":1899.00,"stock":25}'
#  -> 201, Location: http://localhost:8080/api/v1/products/26, ETag: "26-0"
curl -si -X PATCH "$API/26" -H "Authorization: Bearer $TOKEN" -H 'If-Match: "26-0"' \
  -H 'Content-Type: application/merge-patch+json' -d '{"stock":0}'               # 200, ETag "26-1"
curl -si -X DELETE "$API/26" -H "Authorization: Bearer $TOKEN"                  # 204
curl -si -X DELETE "$API/1"                                                     # 401 problem, no token
```

### Browser

Open http://localhost:8080/api/v1/products: an HTML page with a search form, paging links and
links to the JSON/XML/text views of the same query. HTML is read-only (writes need a token, so use Swagger UI).

### Collections and clients

| Client | Where | How |
|---|---|---|
| Bruno | `api-collections/bruno/product-catalog-mvc` | Open the folder in Bruno, pick env `local`, **Run collection** · CLI: `make bruno` |
| IntelliJ / VS Code REST Client | `http-samples/catalog.http` | Paste `make token` output into `@token`, click ▶ per request |
| Swagger UI | `/swagger-ui/index.html` | **Authorize** → paste the token |

The Bruno collection (38 requests, 166 assertions) mints its own tokens in the collection pre-request script from
the environment variable `jwtSecret`, so it needs no manual step. Env `k3d-port-forward` uses the k3s secret.

### Smoke test (one command)

```bash
./scripts/smoke-test.sh http://localhost:8080                 # 20 checks incl. 401/403, mints its own token
./scripts/smoke-test.sh https://catalog.example.com --read-only   # production: never writes
```

---

## 5. Run the tests

```bash
mvn -B -ntp clean verify      # or: make test
```

| Suite | Covers | Docker |
|---|---|---|
| `ProductServiceTest` | business rules: normalization, duplicate SKU, If-Match versions, merge patch | no |
| `WebHelpersTest` | ETag parsing, sort whitelist, Link relations, query strings, HTML navigation, config validation | no |
| `ProductControllerTest` (`@WebMvcTest`) | status codes, headers, JSON/XML/HTML/text, validation, problems, 401/403 with security on | no |
| `ProductApiIT` (`@SpringBootTest`, H2) | full app over real HTTP: CRUD, 304/412, paging, CORS, OpenAPI, Actuator | no |
| `PostgresIT` | Flyway + JPA + search on PostgreSQL 16 (Testcontainers, `@ServiceConnection`) | **yes** (skipped otherwise) |
| `OpenApiContractIT` | `/v3/api-docs/v1` vs committed baseline | no |

Reports: `target/surefire-reports`, `target/failsafe-reports`; coverage `target/site/jacoco/index.html` (gate 70 %).
Without Docker: `mvn verify -Dcoverage.minimum=0.0` if the skipped `PostgresIT` pulls coverage under the gate.

**OpenAPI baseline (do once):** the first build skips `OpenApiContractIT`. Create the baseline and commit it:
```bash
./scripts/update-contract.sh && git add src/test/resources/contract
```

---

## 6. Kubernetes on k3d (k3s in Docker)

Works on Windows, macOS and Linux with no registry. It can share the `catalog` cluster with the SOAP and JAX-RS
projects: this API lives in namespace `catalog-mvc` with host `catalog-mvc.localhost`.

```bash
./infra/k3d/create-cluster.sh     # cluster "catalog", ingress -> localhost:8081 (reused if it exists)
./infra/k3d/deploy-local.sh       # docker build -> k3d image import -> kubectl apply -k k8s/overlays/k3s
```
This deploys the API (2 replicas, HPA 2-5, PDB, probes on `/actuator/health/*`), a PostgreSQL StatefulSet (1 Gi)
with a NetworkPolicy, the dev JWT secret, and a Traefik ingress for `/api`, `/v3/api-docs` and `/swagger-ui` on
host `catalog-mvc.localhost`. CORS allows `http://localhost:3000` and `:5173`.

```bash
kubectl -n catalog-mvc get pods,svc,ingress,hpa
curl -s -H 'Host: catalog-mvc.localhost' 'http://localhost:8081/api/v1/products?size=3' -H 'Accept: text/plain'
JWT_HMAC_SECRET=k3s-dev-only-jwt-secret-change-me-0123456789 \
  ./scripts/smoke-test.sh http://localhost:8081 --host catalog-mvc.localhost

kubectl -n catalog-mvc port-forward svc/product-catalog-mvc 18080:80    # then Bruno env "k3d-port-forward"
kubectl -n catalog-mvc logs -l app.kubernetes.io/name=product-catalog-mvc -f --prefix
./infra/k3d/delete-cluster.sh                                            # removes the whole cluster
```
Swagger UI through the ingress: add `127.0.0.1 catalog-mvc.localhost` to your hosts file if your OS doesn't resolve
`*.localhost`, then open http://catalog-mvc.localhost:8081/swagger-ui/index.html.

---

## 7. Kubernetes on native k3s

```bash
./infra/k3s/install-k3s.sh                 # Linux / VM / WSL2 with systemd; writes ~/.kube/config-k3s
export KUBECONFIG=~/.kube/config-k3s
./infra/k3s/deploy-local.sh                # build, import into k3s containerd (sudo), deploy
JWT_HMAC_SECRET=k3s-dev-only-jwt-secret-change-me-0123456789 \
  ./scripts/smoke-test.sh http://localhost --host catalog-mvc.localhost
```

---

## 8. Jenkins CI/CD (local lab)

Pipeline (`Jenkinsfile`): **checkout → `mvn verify` (unit, `@WebMvcTest`, ITs, Testcontainers, OpenAPI contract,
coverage gate) → Docker image → Trivy scan → publish (k3d import or registry push) → manual approval (prod) →
deploy with kustomize → smoke test (token from the cluster's JWT secret) → automatic `rollout undo` on failure.**

1. Push this project to a Git repository Jenkins can read.
2. `./infra/k3d/create-cluster.sh` (writes `infra/jenkins/secrets/kubeconfig-k3s`).
3. ```bash
   cp infra/jenkins/.env.example infra/jenkins/.env     # admin password + GIT_REPO_URL
   ./infra/jenkins/start-jenkins.sh                      # http://localhost:8092
   ```
4. Job **product-catalog-mvc → Build with Parameters**: `DEPLOY_ENV=k3s`, `IMAGE_DELIVERY=k3d-import`.

This Jenkins uses port **8092** and container `catalog-mvc-jenkins`, so it can run next to the SOAP (8090) and
JAX-RS (8091) ones.

| Credential id | Type | Created by | Used for |
|---|---|---|---|
| `kubeconfig-k3s` | Secret file | JCasC (from `infra/jenkins/secrets/`) | deploy to k3d |
| `kubeconfig-prod` | Secret file | you | deploy to prod |
| `registry-creds` | Username/password | you | `IMAGE_DELIVERY=registry-push` |

Jenkins runs as root with the host Docker socket, which is fine **for a local lab only**. In a shared Jenkins use agents.

---

## 9. Production deployment

`k8s/overlays/prod`: API only (managed PostgreSQL), profiles `postgres,prod`, 3-10 replicas spread across nodes,
NGINX ingress with TLS for `/api` only. The `prod` profile disables Swagger UI and `/v3/api-docs`, and validates
JWTs from `JWT_ISSUER_URI` (no shared secret exists in production).

1. Replace every `CHANGE_ME` (DB host, issuer URI, registry, hostname, CORS origin).
2. Register the API with your identity provider so it issues tokens with scope `catalog.write` to writers.
3. The cluster needs ingress-nginx and metrics-server, and pull access to your registry (AKS: `az aks update --attach-acr <acr>`).
4. Create the secrets out of band (no JWT secret in prod):
   ```bash
   kubectl create namespace catalog-mvc
   kubectl -n catalog-mvc create secret generic product-catalog-mvc-db --from-literal=username='<user>' --from-literal=password='<password>'
   kubectl -n catalog-mvc create secret tls product-catalog-mvc-tls --cert=tls.crt --key=tls.key
   ```
5. Jenkins: `DEPLOY_ENV=prod`, `IMAGE_DELIVERY=registry-push`, `REGISTRY=<registry>`. The pipeline asks for
   approval, and the smoke test runs read-only (it still checks that an anonymous write gets 401).

---

## Configuration reference

Environment variables (mapped in `application*.yml`):

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | none (H2) | `postgres` for a real DB; add `prod` in production |
| `APP_PORT` | `8080` | HTTP port |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost / `catalog` / – | `postgres` profile (password from a Secret) |
| `DB_POOL_SIZE` | `10` | Hikari max pool size |
| `SEED_DATA` | `true` (H2) · `false` (postgres) | Load the 25 demo products (Flyway location `db/seed`) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` · required in prod | Comma-separated explicit origins; `*` is rejected at startup |
| `JWT_HMAC_SECRET` | local dev value | HS256 secret for dev/k3s tokens, ≥ 32 chars; ignored by `prod` |
| `JWT_ISSUER_URI` | – | OAuth2 issuer, required by `prod` |
| `HTTP_THREADS` | `200` (k8s: `100`) | Tomcat max threads |
| `SHUTDOWN_GRACE_SECONDS` | `20` | Time for in-flight requests on SIGTERM |
| `LOG_LEVEL` | `INFO` | Level for `com.infy.catalog` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM flags (image) |

Fixed in `application.yml`: page size default 20 / max 100, `?format=` parameter, Jackson (ISO-8601 UTC, plain
decimals, unknown properties ignored, nulls omitted).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Port 8080 was already in use` | `APP_PORT=9080 mvn spring-boot:run` |
| Browser shows HTML, you wanted JSON | Add `?format=json`, or send `Accept: application/json` |
| `401` on POST/PUT/PATCH/DELETE | Add `Authorization: Bearer $(./scripts/dev-token.sh)` |
| `401 invalid_token` with a fresh token | The token's secret ≠ server's `JWT_HMAC_SECRET` (k3s uses its own), or clock skew |
| `403 FORBIDDEN` | Token lacks scope `catalog.write` |
| `403 Invalid CORS request` | Request sent an `Origin` that isn't in `CORS_ALLOWED_ORIGINS` (exact scheme+host+port) |
| `406 Not Acceptable` | Accept header has no supported type; use JSON, XML, HTML or text |
| `415 Unsupported Media Type` | Set `Content-Type: application/json` (or XML; PATCH also takes `application/merge-patch+json`) |
| `412 Precondition Failed` | Your `If-Match` ETag is stale: GET again, retry with the new ETag |
| Startup: `Could not resolve placeholder 'JWT_ISSUER_URI'` | `prod` profile without its required variables |
| Startup: `Schema-validation: missing table` | Flyway didn't run: check `spring.flyway` and DB permissions |
| `bad interpreter: /bin/bash^M` | CRLF line endings, see Prerequisites |
| `PostgresIT` skipped | Docker unavailable to Maven; start Docker or use `-Dcoverage.minimum=0.0` |
| `OpenApiContractIT` skipped / failing | No baseline yet / API changed: see section 5 |
| Pod `CreateContainerConfigError` | Secret `product-catalog-mvc-db` missing in `catalog-mvc` |
| Pod `ImagePullBackOff` on k3d | `k3d image import product-catalog-mvc:local -c catalog` |
| Readiness 503 | DB unreachable: `kubectl -n catalog-mvc logs deploy/product-catalog-mvc` |
| Ingress 404 | Send `-H 'Host: catalog-mvc.localhost'` |
| Jenkins can't reach the cluster | Rerun `./infra/jenkins/start-jenkins.sh` (joins the k3d network) |
