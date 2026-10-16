# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this is

A **REST API** for a product catalog built with **Spring Boot 3.3 / Spring MVC** on embedded Tomcat. CRUD, search
and pagination, with every response available as **JSON, XML, HTML or plain text** from the same URL. Data lives in
PostgreSQL (Spring Data JPA + Flyway); locally it runs on in-memory H2 in PostgreSQL mode with zero setup. Writes are
protected by OAuth2 JWT bearer tokens. It ships as a Docker image deployed to Kubernetes (k3s/k3d) by Jenkins.

Third of three twins for the Spring REST course: `product-catalog-ws` (SOAP, JAX-WS), `product-catalog-rs`
(JAX-RS, Jersey) and this one. Same API contract as the JAX-RS project, plus security and JPA optimistic locking.

## Commands

| Task | Command |
|---|---|
| Run locally (H2, demo data) | `mvn spring-boot:run` · `make run` |
| Run against PostgreSQL | `make compose-db` then `make run-pg` |
| Build jar (no tests) | `mvn -B -ntp clean package -DskipTests` |
| All tests + coverage gate | `mvn -B -ntp clean verify` (`PostgresIT` needs Docker; skipped without it) |
| Unit + slice tests only | `mvn -B -ntp test` |
| One test | `mvn -Dtest=ProductControllerTest test` · `mvn -Dit.test=ProductApiIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false verify` |
| Write token (local) | `TOKEN=$(scripts/dev-token.sh)` · `make token` |
| Try it | `curl -s localhost:8080/api/v1/products?size=3 -H 'Accept: text/plain'` |
| Smoke test | `scripts/smoke-test.sh http://localhost:8080` |
| API collection | `cd api-collections/bruno/product-catalog-mvc && bru run --env local` |
| Update OpenAPI baseline | `scripts/update-contract.sh` (only for intended, compatible changes) |
| Render k8s | `kubectl kustomize k8s/overlays/k3s` |

`make help` lists all shortcuts. Full setup lives in `HOW_TO_RUN.md`.

## Architecture

```
com.infy.catalog
  CatalogApplication          @SpringBootApplication, nothing else
  config/
    CatalogProperties         typed @ConfigurationProperties("catalog"), validated at startup
    WebConfig                 HTML + text message converters, CORS source (/api/**)
    SecurityConfig            stateless resource server, per-endpoint rules, CSP, HS256 decoder (dev only)
    OpenApiConfig             springdoc group "v1", bearer scheme
    FlywaySeedConfig          adds classpath:db/seed when catalog.seed-data=true
  product/
    domain/                   Product (JPA entity, @Version), ProductData/Patch/Filter, domain exceptions
    service/                  ProductService (transactions, rules), repository, JPA Specifications
    web/                      ProductController, RootController, Etags, SortRules, ProductMapper
      dto/                    records: ProductRequest, ProductPatchRequest, ProductResponse, ProductPage, ProductSearchParams
      render/                 RepresentationConverters (Html via Thymeleaf, Text), TextRenderer, HtmlNavigation, ProblemView
  common/
    web/                      CorrelationIdFilter, PaginationLinks, QueryStrings
    error/                    ErrorCode, GlobalExceptionHandler (RFC 9457), ProblemAuthenticationHandlers
resources/
  application.yml             defaults = H2; application-postgres.yml; application-prod.yml
  db/migration/V1__…          schema (portable PostgreSQL/H2); db/seed/V100__… demo data
  templates/                  Thymeleaf: products/list, products/detail, problem, fragments
```

Request flow: `CorrelationIdFilter` (MDC `traceId`) → Spring Security (CORS, JWT, authorization) →
`DispatcherServlet` → content negotiation (Accept or `?format=`) → `ProductController` → `ProductService` →
JPA → DTO record → Jackson JSON / Jackson XML / `Html` / `Text` converter → response.

## Rules to follow

**Layering**
- Controllers stay thin: bind → service → map → `ResponseEntity`. Logic goes in the service, or in a pure helper in
  `web/` with a unit test (`Etags`, `SortRules`, `PaginationLinks`, `HtmlNavigation`).
- Constructor injection only. Entities never leave the service/web boundary: map to DTO records in `ProductMapper`.
- `domain/` has no Spring web imports. JPA annotations on `Product` are the only framework coupling there.
- Configuration is read through `CatalogProperties`, never `@Value` scattered in classes. Secrets come from env.

**HTTP semantics**
- Paths: `/api/v1/<plural-noun>`. POST → 201 + `Location` + `ETag`. PUT replaces (404 if missing, no upsert).
  PATCH = merge patch (absent/null = unchanged, `""` clears an optional string). DELETE → 204.
- ETag = `"<id>-<version>"` from JPA `@Version`. GET honours `If-None-Match` → 304. PUT/PATCH/DELETE honour
  `If-Match` → 412 (also when the DB row changed concurrently: `ObjectOptimisticLockingFailureException` → 412).
- Collections are always paginated: Spring Data `Pageable` (`page`, `size` default 20, max 100 — capped, not
  rejected), `X-Total-Count`, `Link` (first/prev/next/last, keeps filters). Never serialize `Page`/`PageImpl`:
  return `ProductPage`.
- Sorting only via the `SortRules` whitelist; `id` is appended as a tie-breaker for stable paging.

**Representations**
- Endpoints `produces` JSON, XML, HTML and text. JSON is first, so `*/*` gets JSON. `?format=json|xml|html|text`
  overrides Accept (`spring.mvc.contentnegotiation.favor-parameter`).
- HTML and text are **message converters**, not views, so errors (`ProblemDetail`) render in all four formats too.
- DTOs are records. XML names come from `@JacksonXmlRootElement`/`@JacksonXmlProperty`. Templates call record
  accessors as methods (`${p.name()}`).
- Money is `BigDecimal` with scale 2, written plain. Dates are `Instant` (ISO-8601 UTC). Nulls omitted. Unknown
  JSON properties ignored (tolerant reader).
- HTML is escaped by Thymeleaf (`th:text` only, never `th:utext`) and served with a script-free CSP.

**Errors**
- Every 4xx/5xx is an RFC 9457 `ProblemDetail` from `GlobalExceptionHandler` (one `@RestControllerAdvice`, extends
  `ResponseEntityExceptionHandler`) with `errorCode`, `traceId`, `instance` and optional `errors[]`.
- 401/403 from Spring Security are routed into the same handler (`ProblemAuthenticationHandlers`), so they are
  problems too.
- Business failures: throw domain exceptions (`ProductNotFoundException` 404, `DuplicateSkuException` 409,
  `VersionMismatchException` 412, `InvalidRequestException` 400). Never build error responses in controllers.
- Never leak stack traces, SQL, class names or Jackson messages. Binding errors name the field only.

**Validation & persistence**
- `@Valid` on every body and on `ProductSearchParams`. Every string is bounded; limits = column sizes in
  `V1__create_product_table.sql`. All violations are reported at once, sorted.
- `spring.jpa.hibernate.ddl-auto=validate`: Flyway owns the schema. Migrations are immutable: add `V2__…`.
  The migration must run on both PostgreSQL and H2 (PostgreSQL mode). Demo data lives only in `db/seed`.
- Duplicate SKU is checked in the service and backed by the unique constraint (`saveAndFlush` maps the race).

**API contract**
- `OpenApiContractIT` diffs `/v3/api-docs/v1` (keys sorted, servers removed) with
  `src/test/resources/contract/openapi-v1.json`. Additive changes only. Breaking change → `/api/v2` + a new
  springdoc group.

**Security**
- GET/HEAD/OPTIONS public; POST/PUT/PATCH/DELETE need `SCOPE_catalog.write`. Add new endpoints to `SecurityConfig`
  explicitly; anything unmatched requires authentication.
- Dev/k3s: HS256 with `JWT_HMAC_SECRET` (≥ 32 chars). Prod: `JWT_ISSUER_URI`, and the HMAC decoder bean is not
  created. Never commit a real secret; the k3s one is labelled dev-only.
- CORS once, in `WebConfig`: explicit origins (`*` rejected at startup), no credentials.
- Correlation ids are validated (`[A-Za-z0-9._-]{1,64}`) before reaching logs. Never log bodies, tokens or secrets.
- Swagger UI and api-docs are disabled by the `prod` profile.

**Kubernetes**: `k8s/base/app` is environment-neutral. Namespace `catalog-mvc`, resources prefixed
`product-catalog-mvc`, so the SOAP and JAX-RS projects can share the same k3d cluster (`catalog`). Config via
`configMapGenerator` (hash → rollout); prod secrets are never committed. Probes are Actuator liveness/readiness.
Pods run as non-root with a read-only root filesystem (writable `/tmp` emptyDir for Tomcat).

## Testing expectations

- Pure logic → plain unit tests (`WebHelpersTest`, `ProductServiceTest` with Mockito).
- HTTP contract of the controller (status codes, headers, negotiation, validation, problems, 401/403) →
  `ProductControllerTest` (`@WebMvcTest` with `SecurityConfig` imported and `jwt()` tokens).
- Whole app over real HTTP → `ProductApiIT` (H2). PostgreSQL specifics → `PostgresIT` (Testcontainers).
- Black-box regression → Bruno collection and `scripts/smoke-test.sh` (also run by Jenkins after deploy).
- JaCoCo line coverage gate: `coverage.minimum` in `pom.xml` (0.70).

## Gotchas

- Content negotiation picks the **first** producible type for `*/*`; keep JSON first in `produces`.
- `ResponseEntityExceptionHandler` handles Spring MVC exceptions; override its methods instead of adding
  `@ExceptionHandler`s for the same types (Spring fails at startup on duplicates).
- Spring Security runs before MVC: 401/403 never reach controllers, hence `ProblemAuthenticationHandlers`.
- A disallowed `Origin` gets **403 "Invalid CORS request"** from Spring's CORS processor (not a problem body).
- `If-None-Match` handling uses `WebRequest.checkNotModified`; the controller must then return `null`.
- H2 runs in PostgreSQL mode with `DATABASE_TO_LOWER=TRUE`; avoid PostgreSQL-only SQL in `db/migration`.
- `@Version` starts at 0, so a new product's ETag is `"<id>-0"`.

## Known gaps / roadmap

- No rate limiting, no metrics endpoint yet (Micrometer + Prometheus via Actuator is a small step).
- Keyword search uses `LIKE '%…%'`. Add `pg_trgm` or full-text search for large catalogs.
- HTML is read-only; a write UI would need a login flow (authorization code + PKCE), not bearer tokens in forms.
- Next in the course: HATEOAS (Spring HATEOAS), API versioning strategies, and Spring WebFlux.
