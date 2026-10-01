# Бизнес-требования: сервис конвертации веб-страниц

## 1. Назначение и область

Сервис возвращает итоговое состояние динамической страницы как MHTML, PDF или image-based PPTX без server-side хранения результата. В scope: JavaScript/Canvas/SVG/ECharts, bounded readiness/resources, SSRF defense, enforced production egress proxy, correlation/health. Не входят UI, batch/scheduling, editable PPTX DOM objects, CAPTCHA/DRM/auth bypass, client cookies/Authorization.

## 2. API

Все операции — `POST`, `Content-Type: application/json`, body `{"url":"https://example.org/report"}`.

| Операция | Статус | Результат |
|---|---|---|
| `/MakeMHTML` | canonical | MHTML / `application/x-mimearchive` |
| `/MakeSnapshot` | compatibility alias | MHTML / `application/x-mimearchive` |
| `/MakePDF` | canonical | PDF / `application/pdf` |
| `/MakePPTX` | canonical | PPTX / OOXML presentation MIME |

Успех имеет `200`, binary body, точный `Content-Length`, safe attachment filename и correlation ID. Framework errors сохраняют `404`, `405`/`Allow`, `415`; unexpected exception становится sanitized `500`. Problem JSON не раскрывает stack/path/query/document/credentials.

## 3. Реализованные функциональные требования M0

- **FR-001:** обязательный absolute HTTP(S) URL до 4096 chars, без user-info.
- **FR-002:** request host и exact/wildcard patterns canonicalized одинаково: IDNA ASCII, lowercase, удаление всех terminal dots.
- **FR-003:** все DNS answers проверяются; private/loopback/link-local/multicast/unspecified/CGNAT/metadata запрещены, если application private mode не включён.
- **FR-004:** HEAD redirect preflight no-follow; browser GET status/redirect chain измеряются отдельно из Chromium performance events. Final 4xx/5xx и overflow redirect limit отклоняются до capture.
- **FR-005:** production startup fail-closed без validating HTTP proxy. Chromium proxy bypass/DNS/QUIC/non-proxied WebRTC закрыты; Java preflight использует тот же proxy. Application checks остаются defense in depth.
- **FR-006:** отдельный `UNSAFE` mode требует acknowledgement и предназначен только для trusted local E2E.
- **FR-007:** readiness bounded и включает base document readiness, fonts/images/Canvas, DOM quiet и selector либо trusted operator JS marker.
- **FR-008:** MHTML — CDP snapshot; PDF — bounded CDP stream; PPTX — bounded full-page PNG slices и Apache POI.
- **FR-009:** layout dimensions/pixels, base64 decoded size, PNG IHDR, screenshot/output bytes и slide count проверяются до соответствующих дорогих allocations, где API это позволяет; overflow даёт controlled `413`.
- **FR-010:** atomic queued/running/cancelling/finished ownership исключает start после queued cancel; отдельный atomic browser-start lifecycle закрывает race до/во время driver startup. Timeout выполняет bounded Selenium cleanup и graceful/forced termination same-user process tree; permits освобождаются владельцем после cleanup resolution.
- **FR-011:** startup failure закрывает driver/service/profile; оба configured executables валидируются.
- **FR-012:** readiness health bounded, cached single-flight, реально открывает/закрывает browser, cancellable на startup и входит в readiness group.
- **FR-013:** stable Problem codes, correlation ID и safe logs.

## 4. Нефункциональные требования

- Java 21, Spring Boot/Undertow, Selenium/Chromium CDP, Apache POI; executable JAR.
- Default port 8088; external config via `--config`.
- Headless browser process/profile per request; compatible browser/driver installed externally.
- Bounded concurrency, acquire/preflight/page/script/readiness/operation/cleanup/health timeouts.
- JVM/container memory and `/tmp` limits обязательны; starting deployment example: app 1 GiB, tmpfs 256 MiB, `max-concurrent=2`, output/screenshot 50 MiB, capture 100M pixels.
- Unit/integration suite no external network. Dedicated Failsafe browser profile uses only loopback fixture and skips clearly when local browser pair unavailable.
- JaCoCo instruction/line ≥90%, branch ≥70%, method/class 100%; tests assert behavior, not invocation alone.

## 5. HTTP status policy

| HTTP | Условие |
|---:|---|
| 200 | artifact сформирован |
| 400 | invalid JSON/request/URL |
| 403 | URL/request/redirect/subresource blocked |
| 404/405/415 | preserved Spring MVC protocol error |
| 413 | dimensions/pixels/decoded bytes/output/slides limit |
| 422 | unreachable target, browser GET 4xx/5xx, invalid navigation/artifact |
| 429 | capacity exhausted; `Retry-After: 1` |
| 500 | только unexpected internal error |
| 503 | browser/executor unavailable |
| 504 | operation/readiness/cancellation timeout |

## 6. Критерии приёмки M0

1. Canonical `/MakeMHTML` и alias `/MakeSnapshot` имеют одинаковый tested contract.
2. Default app не стартует без valid proxy config; deployment example блокирует private destinations после DNS для HTTP/CONNECT/WebSocket paths.
3. Oversized layout/base64/PNG/PPTX/output завершается controlled `413`, не неконтролируемой JVM allocation в application code.
4. Deterministic latch tests доказывают no-start-after-cancel, forced-close и permit ownership.
5. Dotted/IDN exact/wildcard rules и localhost private on/off покрыты.
6. 404/405/415 headers/status, top-level browser GET errors и independent browser redirect limit покрыты.
7. Missing/incompatible/partial browser startup и bounded cached readiness probe покрыты.
8. Local real-browser profile проверяет signatures/headers/cleanup, фактический delayed Canvas в MHTML/PPTX, GET redirects при HEAD 200, real/late GET 4xx/5xx и blocked private subresource.
9. `mvn clean verify`, browser profile при наличии Chromium и `git diff --check` успешны.

## 7. Ограничения

Validating proxy/deployment boundary остаётся operator-controlled security component; приложение не может доказать host firewall isolation. Process supervisor может завершать только дочерние процессы того же OS user, поэтому container PID/cgroup supervision остаётся дополнительным рубежом. Squid example требует сопровождения ACL и cloud-specific ranges. MHTML CDP возвращает готовый String до application byte limit; Selenium/ImageIO/POI имеют внутренние allocations, поэтому hard container memory limit обязателен. PPTX raster-only. Custom readiness script доверенный operator config. Качество зависит от browser/fonts/CSP/locale и поведения страницы.
