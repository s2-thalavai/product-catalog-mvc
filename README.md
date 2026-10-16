# Product Catalog – REST API (Spring MVC)

![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![Formats](https://img.shields.io/badge/formats-JSON%20%7C%20XML%20%7C%20HTML%20%7C%20text-6f42c1)
![Security](https://img.shields.io/badge/auth-OAuth2%20JWT-black)
![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791)
![Kubernetes](https://img.shields.io/badge/Kubernetes-k3s%20%7C%20k3d-326CE5)
![Jenkins](https://img.shields.io/badge/CI-Jenkins-D24939)

A production-style **REST API** for a product catalog: CRUD, keyword search, filtering, sorting and pagination,
built with **Spring Boot 3 / Spring MVC**, Spring Data JPA and Spring Security.

- **One URL, four formats:** JSON, XML, HTML (browsable) or plain text, chosen by the `Accept` header or `?format=`.
- **HTTP done properly:** status codes, `Location`, ETags from JPA `@Version` with 304/412, `Link` pagination headers, RFC 9457 errors.
- **Secure by default:** public reads, JWT bearer tokens with scope `catalog.write` for writes, explicit CORS, CSP.
- **Ready to ship:** Flyway, Actuator probes, graceful shutdown, Docker, Kubernetes (k3s/k3d), and a Jenkins pipeline with deploy, smoke test and rollback.

It is the Spring twin of `product-catalog-rs` (JAX-RS) and `product-catalog-ws` (SOAP): same catalog, same API
contract, so the three styles can be compared side by side.

---

## Contents

1. [Try it in 60 seconds](#try-it-in-60-seconds)
2. [Architecture](#architecture)
3. [API reference](#api-reference)
4. [Security](#security)
5. [Content negotiation](#content-negotiation)
6. [Errors](#errors)
7. [Testing](#testing)
8. [Deployment](#deployment)
9. [Configuration](#configuration)
10. [SOAP vs JAX-RS vs Spring MVC](#soap-vs-jax-rs-vs-spring-mvc)
11. [Design decisions](#design-decisions)
12. [Project layout](#project-layout)
13. [Tech stack and roadmap](#tech-stack)

---

## Try it in 60 seconds

**Prerequisites:** JDK 21 and Maven 3.9+. Nothing else: the default profile uses an in-memory H2 database.

```bash
mvn spring-boot:run              # or: make run
```

| Try | What you'll see |
|---|---|
| Open http://localhost:8080/ | HTML catalog with search form, sorting and paging |
| `curl -s 'localhost:8080/api/v1/products?size=5' -H 'Accept: text/plain'` | Aligned text table |
| `curl -s localhost:8080/api/v1/products/1` | JSON |
| `curl -s localhost:8080/api/v1/products/1 -H 'Accept: application/xml'` | XML |
| Open http://localhost:8080/swagger-ui/index.html | Swagger UI (Authorize with a token for writes) |
| `TOKEN=$(./scripts/dev-token.sh)` | A local write token (scope `catalog.write`) |
| `./scripts/smoke-test.sh http://localhost:8080` | 20 end-to-end checks, including 401/403 |

<details>
<summary>Windows PowerShell</summary>

```powershell
mvn spring-boot:run
Invoke-RestMethod "http://localhost:8080/api/v1/products?size=5"
```
Run the `.sh` scripts (token, smoke test) from Git Bash or WSL2.
</details>

---

## Architecture

```
                         ┌──────────────── Kubernetes namespace "catalog-mvc" ────────────────┐
  Browser ── HTML ──┐    │                                                                    │
                    ├───►│  Ingress ──► Service ──► Deployment: 2–5 pods (HPA, PDB)           │
  curl / apps ──────┘    │  /api, /v3/api-docs,       │  probes: /actuator/health/*           │
  JSON · XML · text      │  /swagger-ui (dev)         │ JPA / HikariCP                        │
  Bearer JWT for writes  │                            ▼                                       │
                         │               PostgreSQL 16 StatefulSet  (k3s / dev)               │
                         │               Managed PostgreSQL         (prod)                    │
                         └────────────────────────────────────────────────────────────────────┘
          prod: tokens issued by your identity provider (JWT_ISSUER_URI) · dev/k3s: HS256 dev secret
```

**Inside a pod: one request**

```
 HTTP request
     │
     ▼
 CorrelationIdFilter ─ traceId in MDC, echoed as X-Correlation-Id
     │
     ▼
 Spring Security ─ CORS ─ JWT (resource server) ─ GET public / writes need SCOPE_catalog.write
     │                                   │ 401 / 403
     ▼                                   ▼
 DispatcherServlet                  ProblemAuthenticationHandlers ──┐
     │ content negotiation (Accept or ?format=)                     │
     ▼                                                              │
 ProductController ──► ProductService ──► Spring Data JPA ──► DB    │
     │ @Valid DTO records       │ @Transactional, @Version          │
     │                    domain exception                          │
     │                          ▼                                   ▼
     │                    GlobalExceptionHandler ──► RFC 9457 ProblemDetail
     ▼                          │
 HttpMessageConverter ◄─────────┘  Jackson JSON · Jackson XML · HTML (Thymeleaf) · text
     │
     ▼
 HTTP response (+ ETag, Link, X-Total-Count, CSP, Cache-Control)
```

| Layer | Package | Knows about HTTP? | Spring pieces |
|---|---|---|---|
| Web | `product.web`, `common` | yes | `@RestController`, converters, `@RestControllerAdvice` |
| Business rules | `product.service` | no | `@Service`, `@Transactional`, Specifications |
| Model | `product.domain` | no | JPA entity, records, exceptions |
| Wiring | `config` | – | Security, CORS, springdoc, Flyway, `@ConfigurationProperties` |

---

## API reference

Base path `/api/v1/products` · OpenAPI at `/v3/api-docs/v1` · Swagger UI at `/swagger-ui/index.html` (not in prod).

| Method | Path | Auth | Body | Success | Errors |
|---|---|---|---|---|---|
| GET | `/api/v1/products` | – | – | **200** + `Link`, `X-Total-Count` | 400 |
| POST | `/api/v1/products` | `catalog.write` | product (JSON or XML) | **201** + `Location`, `ETag` | 400, 401, 403, 409, 415 |
| GET | `/api/v1/products/{id}` | – | – | **200** + `ETag` · **304** if `If-None-Match` matches | 404 |
| PUT | `/api/v1/products/{id}` | `catalog.write` | full product | **200** | 400, 401, 403, 404, 409, 412 |
| PATCH | `/api/v1/products/{id}` | `catalog.write` | changed fields only (`application/merge-patch+json`) | **200** | 400, 401, 403, 404, 409, 412 |
| DELETE | `/api/v1/products/{id}` | `catalog.write` | – | **204** | 401, 403, 404, 412 |

### Search and pagination parameters

| Parameter | Example | Meaning |
|---|---|---|
| `q` | `q=mouse` | Keyword in name, SKU or description (case-insensitive) |
| `category` | `category=electronics` | Exact category (case-insensitive) |
| `minPrice` / `maxPrice` | `minPrice=1000&maxPrice=9000` | Inclusive price range (`minPrice` ≤ `maxPrice`) |
| `inStock` | `inStock=true` | Only products with stock > 0 |
| `sort` | `sort=price,desc` | `id`, `name`, `price`, `stock` or `createdAt`, optional `,asc`/`,desc`; repeatable |
| `page` / `size` | `page=1&size=10` | 0-based page; size defaults to 20, **capped at 100** |

```http
GET /api/v1/products?category=electronics&inStock=true&sort=price,desc&size=3

HTTP/1.1 200 OK
Content-Type: application/json
X-Total-Count: 6
Link: <…&size=3&page=0>; rel="first", <…&size=3&page=1>; rel="next", <…&size=3&page=1>; rel="last"

{"items":[{"id":4,"sku":"ELEC-HEAD-01","name":"Noise Cancelling Headphones","price":8999.00,…}, …],
 "page":0,"size":3,"totalElements":6,"totalPages":2,"hasNext":true,"hasPrevious":false}
```

### Create, then update safely with ETags

```bash
TOKEN=$(./scripts/dev-token.sh)
curl -si -X POST localhost:8080/api/v1/products -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"FIT-KBEL-01","name":"Kettlebell 8 kg","category":"Fitness","price":1899.00,"stock":25}'
# 201 Created · Location: http://localhost:8080/api/v1/products/26 · ETag: "26-0"

curl -si -X PATCH localhost:8080/api/v1/products/26 -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/merge-patch+json' -H 'If-Match: "26-0"' -d '{"stock":20}'
# 200 OK with ETag "26-1", or 412 Precondition Failed if someone changed it in between
```
The ETag is `"<id>-<version>"`, where `version` is the JPA `@Version` column. The check is atomic: the UPDATE
includes `WHERE version = ?`, so two concurrent writers can't both win.

More ready-to-run requests: `http-samples/catalog.http` (IntelliJ / VS Code) and the Bruno collection in `api-collections/`.

---

## Security

| Request | Needs | Without it |
|---|---|---|
| `GET`, `HEAD`, `OPTIONS` | nothing | – |
| `POST`, `PUT`, `PATCH`, `DELETE` | `Authorization: Bearer <JWT>` with scope `catalog.write` | **401** (missing/invalid/expired) · **403** (no scope) |

| Environment | Token validation | Where tokens come from |
|---|---|---|
| Local, Compose | HS256, `JWT_HMAC_SECRET` (dev default) | `scripts/dev-token.sh`, Bruno collection script |
| k3s / k3d | HS256, Secret `product-catalog-mvc-jwt` | same, with the k3s secret |
| Production (`prod` profile) | signature + issuer from `JWT_ISSUER_URI` (JWKS) | your identity provider (Entra ID, Keycloak, Auth0…) |

Also: stateless (no session, no CSRF tokens needed), CORS with explicit origins and no credentials,
a script-free Content-Security-Policy on every response except Swagger UI, and 401/403 returned as RFC 9457
problems with a `WWW-Authenticate: Bearer` header.

---

## Content negotiation

The same resource, four representations:

| Ask with | You get | Good for |
|---|---|---|
| `Accept: application/json` (or nothing, or `*/*`) | JSON | apps, scripts |
| `Accept: application/xml` | XML (`<product>`, `<productPage>`) | enterprise integrations |
| `Accept: text/html` (any browser) | HTML page with search, sort and paging, no JavaScript | humans |
| `Accept: text/plain` | aligned text table | terminals, logs |
| `?format=json\|xml\|html\|text` | overrides `Accept` | quick links, browser testing |

Request bodies can be **JSON or XML**. Anything else gives **415**, and an unsupported `Accept` gives **406**.

How it works in Spring: JSON and XML come from Jackson (`jackson-dataformat-xml` on the classpath is enough).
HTML and text are two small custom `HttpMessageConverter`s registered in `WebConfig`, so the controller returns the
same DTO for every format, and errors (`ProblemDetail`) are rendered in all four formats too. `?format=` is plain
Spring configuration (`spring.mvc.contentnegotiation.favor-parameter`).

```text
$ curl -s 'localhost:8080/api/v1/products?category=books' -H 'Accept: text/plain'
ID  SKU           NAME                           CATEGORY    PRICE  STOCK
--  ------------  -----------------------------  --------  -------  -----
18  BOOK-JAVA-01  Java Design Patterns Handbook  Books     1499.00     30
19  BOOK-K8S-01   Kubernetes in Practice         Books     2499.00     22
20  BOOK-SQL-01   SQL for Developers             Books     1199.00     64

3 product(s), page 1 of 1, page size 20
```

---

## Errors

Every 4xx/5xx is an **RFC 9457 problem** (Spring's `ProblemDetail`), in the format the client asked for
(`application/problem+json`, `application/problem+xml`, HTML or text).

```json
{
  "type": "https://catalog.infy.com/problems/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request has 2 invalid field(s); see errors.",
  "instance": "/api/v1/products",
  "errorCode": "VALIDATION_FAILED",
  "traceId": "my-request-42",
  "errors": ["name is required", "price must be >= 0"]
}
```

| HTTP | `errorCode` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Invalid fields or query parameters (all reported at once) |
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON/XML or has a wrong value type |
| 401 | `UNAUTHORIZED` | No token, or it is invalid or expired |
| 403 | `FORBIDDEN` | Token lacks scope `catalog.write` |
| 404 | `PRODUCT_NOT_FOUND` / `NOT_FOUND` | Unknown id / unknown path |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` | Wrong method, `Accept` or `Content-Type` |
| 409 | `DUPLICATE_SKU` | SKU already used by another product |
| 412 | `PRECONDITION_FAILED` | `If-Match` ETag is stale, or a concurrent update won |
| 500 | `INTERNAL_ERROR` | Unexpected; details only in the server log, under the same `traceId` |

Send `X-Correlation-Id: <id>` and it comes back as the response header, as the problem's `traceId`, and in every
log line. Errors never contain stack traces, SQL or Java class names.

---

## Testing

```bash
mvn -B -ntp clean verify
```

| Layer | Where | What it proves | Needs |
|---|---|---|---|
| Unit | `ProductServiceTest`, `WebHelpersTest` | business rules, normalization, If-Match versions, ETags, sort whitelist, Link relations, config validation | – |
| Web slice | `ProductControllerTest` (`@WebMvcTest`) | status codes, headers, 4 formats, validation messages, problems, 401/403 with the real security config | – |
| Full app | `ProductApiIT` (`@SpringBootTest`, H2) | real HTTP: CRUD, 304/412, paging, CORS, Actuator, OpenAPI | – |
| Database | `PostgresIT` | Flyway + JPA + search on PostgreSQL 16 (Testcontainers `@ServiceConnection`) | Docker |
| API contract | `OpenApiContractIT` | `/v3/api-docs/v1` unchanged vs committed baseline | – |
| Black-box | Bruno (38 requests, 166 tests), `scripts/smoke-test.sh` | any running instance; Jenkins runs the smoke test after each deploy | running API |

The first build skips the contract test until a baseline exists: run `./scripts/update-contract.sh` once and commit it.
Coverage report: `target/site/jacoco/index.html` (gate: 70 % lines).

---

## Deployment

| Target | Command | Result |
|---|---|---|
| Docker Compose | `docker compose up -d --build` | API + PostgreSQL on `localhost:8080` |
| k3d (k3s in Docker, any OS) | `make k3d-up && make k3d-deploy` | `http://localhost:8081` with `Host: catalog-mvc.localhost` |
| Native k3s (Linux/WSL2) | `./infra/k3s/install-k3s.sh && ./infra/k3s/deploy-local.sh` | Traefik on port 80 |
| Jenkins (local lab) | `make jenkins-up` | http://localhost:8092, job `product-catalog-mvc` |
| Production | Jenkins with `DEPLOY_ENV=prod` | manual approval, read-only smoke test |

**Pipeline**

```
 Checkout ─► mvn verify ─► Docker build ─► Trivy scan ─► Publish ─► [prod: approval] ─► Deploy ─► Smoke test
             tests ·                                     k3d import                     kustomize       │
             contract ·                                  or registry                                    │ fail
             coverage                                                                                   ▼
                                                                                              kubectl rollout undo
```

It can share the k3d cluster with the other two projects: its own namespace (`catalog-mvc`), host
(`catalog-mvc.localhost`) and Jenkins port (8092). Step-by-step instructions: **[HOW_TO_RUN.md](HOW_TO_RUN.md)**.

---

## Configuration

Spring profiles pick the environment; environment variables (a ConfigMap in Kubernetes) fill in the values.

| Profile | Database | Security | Docs |
|---|---|---|---|
| *(default)* | in-memory H2 (PostgreSQL mode) + demo data | HS256 dev secret | Swagger UI on |
| `postgres` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | HS256 (`JWT_HMAC_SECRET`) | Swagger UI on |
| `postgres,prod` | same | issuer (`JWT_ISSUER_URI`), no shared secret | Swagger UI and api-docs off |

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | – | see above |
| `SEED_DATA` | `true` (H2) · `false` (postgres) | load 25 demo products |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | explicit origins only; `*` is rejected |
| `JWT_HMAC_SECRET` / `JWT_ISSUER_URI` | dev value / – | token validation (dev / prod) |
| `APP_PORT` | `8080` | HTTP port |

Full list: [HOW_TO_RUN.md → Configuration reference](HOW_TO_RUN.md#configuration-reference).

---

## SOAP vs JAX-RS vs Spring MVC

Same catalog, three styles:

| | `product-catalog-ws` (SOAP) | `product-catalog-rs` (JAX-RS) | `product-catalog-mvc` (Spring MVC) |
|---|---|---|---|
| Java API | JAX-WS (Metro) | JAX-RS standard (Jersey) | Spring MVC (`@RestController`) |
| Server | embedded | Grizzly, no servlet container | embedded Tomcat (Spring Boot) |
| Addressing | one endpoint, operation in the body | one URI per resource | one URI per resource |
| Actions | operation names, always `POST` | HTTP methods | HTTP methods |
| Formats | XML in a SOAP envelope | JSON, XML, HTML, text (`MessageBodyWriter`) | JSON, XML, HTML, text (`HttpMessageConverter`) |
| Contract | WSDL + XSD | OpenAPI (swagger-core) | OpenAPI (springdoc) + Swagger UI |
| Errors | SOAP Fault, HTTP 500 | RFC 9457 via `ExceptionMapper`s | RFC 9457 via `@RestControllerAdvice` + `ProblemDetail` |
| Validation | hand-written validator | hand-written validator | Bean Validation (`@Valid`) on DTO records |
| Data access | JDBC (hand-written SQL) | JDBC (hand-written SQL) | Spring Data JPA + Specifications |
| Paging | in the body | body + `Link` + `X-Total-Count` | same, from Spring Data `Pageable` |
| Concurrency control | – | `If-Match` (check-then-write) | `If-Match` + JPA `@Version` (atomic) |
| Security | – | – | OAuth2 resource server (JWT, scopes) |
| Health | custom | custom `/health/*` | Actuator probes |
| Wiring | manual | manual (`ResourceConfig`) | auto-configuration + `@ConfigurationProperties` |
| Try it with | SoapUI | browser, curl, Bruno | browser, curl, Bruno, Swagger UI |

What Spring adds is mostly **less code for the same behaviour**: validation, paging, persistence, health,
security and docs come from starters, so the project's own code is the controller, the service and the rules.

---

## Design decisions

- **DTO records, never entities, on the wire:** the JSON/XML shape can't change by accident when the table does, and lazy-loading surprises can't leak into responses.
- **HTML and text as message converters, not MVC views:** one controller method serves all four formats, and errors render in all four too.
- **`ProductPage` instead of serializing Spring Data's `Page`:** a stable, documented shape (Spring itself warns that `PageImpl` JSON is not stable).
- **ETag from `@Version`:** cheap conditional GETs, and `If-Match` becomes real optimistic locking at the database.
- **Sort whitelist + `id` tie-breaker:** clients can't sort by internal properties, and paging is stable when values tie.
- **One portable migration (PostgreSQL + H2), seed data in a separate Flyway location:** zero-setup local runs on the same schema as production, demo data never reaches prod.
- **Security problems through the MVC handler:** 401/403 look like every other error (format, `errorCode`, `traceId`).
- **HS256 only outside production:** convenient local tokens; the `prod` profile has no shared secret at all.

---

## Project layout

```
src/main/java/com/infy/catalog/
├── config/             CatalogProperties, WebConfig (converters, CORS), SecurityConfig, OpenApiConfig, FlywaySeedConfig
├── product/
│   ├── domain/         Product (JPA, @Version), ProductData/Patch/Filter, domain exceptions
│   ├── service/        ProductService, ProductRepository, ProductSpecifications
│   └── web/            ProductController, RootController, Etags, SortRules, ProductMapper
│       ├── dto/        request/response records, ProductSearchParams
│       └── render/     HTML (Thymeleaf) + text converters
├── common/
│   ├── web/            CorrelationIdFilter, PaginationLinks, QueryStrings
│   └── error/          ErrorCode, GlobalExceptionHandler, ProblemAuthenticationHandlers
└── CatalogApplication.java
src/main/resources/     application*.yml, db/migration, db/seed, templates/
k8s/                    Kustomize: base/{app,postgres}, overlays/{k3s,prod}
infra/                  k3d, k3s and Jenkins (JCasC) setup
api-collections/        Bruno collection (166 tests, mints its own tokens)
http-samples/           catalog.http for IntelliJ / VS Code
scripts/                dev-token, smoke test, OpenAPI baseline
Dockerfile · docker-compose.yml · Jenkinsfile · Makefile
```

Contributing or using Claude Code? Read **[CLAUDE.md](CLAUDE.md)**: layering rules, HTTP conventions and how to evolve the API safely.

---

## Tech stack

| Area | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3.3, embedded Tomcat |
| Web | Spring MVC, Jackson (JSON + XML), Thymeleaf (HTML), Bean Validation, springdoc-openapi 2 |
| Data | Spring Data JPA (Hibernate 6), Flyway, PostgreSQL 16, H2 (local) |
| Security | Spring Security OAuth2 resource server (JWT) |
| Ops | Actuator (liveness/readiness), graceful shutdown, correlation ids in logs |
| Testing | JUnit 5, Mockito, AssertJ, `@WebMvcTest`, `spring-security-test`, Testcontainers, JaCoCo, Bruno |
| Delivery | Docker (multi-stage, Temurin JRE Alpine), Kustomize, k3s/k3d, Traefik, Jenkins (JCasC), Trivy |

## Roadmap

- [ ] Micrometer + Prometheus metrics, rate limiting
- [ ] Full-text search (`pg_trgm`) for large catalogs
- [ ] HATEOAS links (Spring HATEOAS) and an `/api/v2` with its own springdoc group
- [ ] Spring WebFlux version of the same API
# product-catalog-mvc
