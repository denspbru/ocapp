# OCApp

OCApp — Java 21 REST-сервис, который загружает веб-страницу в отдельной сессии Chrome/Chromium и возвращает MHTML, PDF или image-based PPTX. Результаты на сервере не сохраняются.

## API

| API | Результат | MIME type |
|---|---|---|
| `POST /MakeMHTML` | MHTML (`.mhtml`) | `application/x-mimearchive` |
| `POST /MakeSnapshot` | MHTML (`.mhtml`), совместимый alias | `application/x-mimearchive` |
| `POST /MakePDF` | PDF (`.pdf`) | `application/pdf` |
| `POST /MakePPTX` | PPTX (`.pptx`) | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| `GET /test_report` | Встроенный self-contained HTML-отчёт для export examples/manual E2E | `text/html` |

`/MakeMHTML` — канонический MHTML route; `/MakeSnapshot` сохранён для обратной совместимости. Все export-операции принимают ровно такой JSON; встроенный `/test_report` используется как source в примерах по умолчанию:

```json
{"url":"http://localhost:8088/test_report"}
```

Пример:

```bash
curl --fail-with-body -X POST http://localhost:8088/MakeMHTML \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: example-1' \
  -d '{"url":"http://localhost:8088/test_report"}' -o test-report.mhtml
```

Поле `url` по-прежнему обязательно: API не подставляет source неявно. Loopback source не получает исключения из SSRF policy; для локального self-export запустите явно доверенный local E2E mode из раздела «Безопасный запуск». Production/private-host policy остаётся deny-by-default.

Успех: `200`, точный `Content-Length`, MIME type, `Content-Disposition: attachment` и `X-Correlation-ID`. Spring MVC protocol errors сохраняют исходные статусы и headers (`404`, `405` с `Allow`, `415`); только неожиданные ошибки становятся `500`. Фактический top-level browser GET со статусом `4xx/5xx` отклоняется как `422 TARGET_HTTP_ERROR`.

## Требования и сборка

- JDK/JRE 21, Maven 3.9+;
- совместимые Chrome/Chromium и ChromeDriver;
- для offline/production явно настроенные executable paths;
- для production — validating HTTP proxy, который после DNS запрещает private/loopback/link-local/metadata destinations.

```bash
mvn test
mvn verify
```

Обычный Surefire suite не запускает браузер и не использует внешнюю сеть. Отдельный Failsafe profile запускает локальный HTTP fixture и реальный Chromium:

```bash
# local opt-in: missing/incompatible pair is an explicit assumption skip
mvn -Preal-browser verify

# CI: fail closed if Chrome/ChromeDriver are missing or major versions differ
mvn -Preal-browser -Docapp.e2e.failClosed=true verify
# equivalent environment gate: OCAPP_E2E_FAIL_CLOSED=true
```

Он использует `OCAPP_E2E_CHROME` и `OCAPP_E2E_CHROMEDRIVER` либо известные local/cache paths. Локальный opt-in без fail-closed gate явно пропускается, если совместимая пара отсутствует. CI обязан задавать `-Docapp.e2e.failClosed=true` либо `OCAPP_E2E_FAIL_CLOSED=true`: отсутствие executable, невозможность прочитать версию или несовпадение major тогда завершает Failsafe ошибкой. Ничего не скачивается. Profile проверяет MHTML/PDF/PPTX signatures и headers, delayed Canvas marker, DOM-aware pagination трёхцветной tall-page fixture, HEAD-200/GET redirect chain, blocked private subresource, deterministic DOM extraction на локальном complex-layout fixture и cleanup временных профилей.

Строгие JaCoCo gates: instruction/line ≥90%, branch ≥70%, method/class =100%.

## Безопасный запуск

Встроенный default — `converter.security.egress-mode=PROXY` с пустым `proxy-url`, поэтому приложение **намеренно не запускается**, пока validating proxy не настроен:

```bash
java -jar target/ocapp-0.2.1.jar \
  --converter.security.proxy-url=http://proxy.internal:3128 \
  --converter.browser.binary=/usr/bin/chromium \
  --converter.browser.driver-path=/usr/bin/chromedriver
```

Полный исполняемый пример находится в [`deploy/docker-compose.yml`](deploy/docker-compose.yml): контейнер приложения подключён только к internal network, Squid подключён также к public egress, а [`deploy/squid.conf`](deploy/squid.conf) применяет destination ACL после DNS к HTTP, CONNECT и WebSocket handshake/tunnel. Перед запуском:

```bash
mvn package
docker compose -f deploy/docker-compose.yml up --build
```

Ограничения примера: Squid ACL не является универсальным cloud firewall; оператор должен поддерживать список специальных сетей, защищать/аудировать proxy config и не подключать контейнер приложения к другой egress-сети. Приложение не утверждает и не проверяет host firewall isolation. QUIC и non-proxied WebRTC отключены; browser DNS destination resolution блокируется, кроме имени proxy. Java HEAD preflight также использует proxy.

Только для доверенного локального E2E доступен отдельный аварийный режим с обязательным вторым gate:

```properties
converter.security.egress-mode=UNSAFE
converter.security.unsafe-acknowledge-risk=true
converter.security.allow-private-addresses=true
```

`UNSAFE` нельзя считать production isolation.

## Ключевая конфигурация

Все defaults находятся в [`application.properties`](src/main/resources/application.properties).

| Key | Default | Назначение |
|---|---:|---|
| `converter.security.egress-mode` | `PROXY` | `PROXY` или явно подтверждённый `UNSAFE` |
| `converter.security.proxy-url` | пусто | Обязательный absolute `http://` URL validating proxy |
| `converter.security.unsafe-acknowledge-risk` | `false` | Отдельное подтверждение для `UNSAFE` |
| `converter.security.allow-private-addresses` | `false` | Defense-in-depth URL/DNS policy; в proxy production boundary private destinations всё равно запрещены |
| `converter.security.allowed-hosts` / `denied-hosts` | пусто | Canonical IDN/exact/wildcard host policy; terminal dots удаляются |
| `converter.security.max-redirects` | `5` | Отдельные limits для HEAD preflight и реальной browser GET chain |
| `converter.browser.binary` / `driver-path` | пусто | Browser/driver executable; оба явно заданных пути валидируются |
| `converter.browser.readiness-timeout` | `15s` | Общий bounded readiness wait |
| `converter.browser.dom-quiet-period` | `500ms` | Период без DOM mutations |
| `converter.browser.readiness-selector` | `body` | Обязательный DOM marker, если custom script пуст |
| `converter.browser.readiness-script` | пусто | Operator-configured JS expression; никогда не берётся из request |
| `converter.browser.cleanup-timeout` | `5s` | Bounded quit/service-stop cleanup |
| `converter.browser.health-probe-timeout` / `health-cache-ttl` | `10s` / `30s` | Bounded cached start-and-close probe |
| `converter.limits.max-concurrent` | `2` | Одновременные conversions |
| `converter.limits.operation-timeout` | `90s` | Полный timeout, включая preflight и render |
| `converter.limits.max-output-bytes` | `52428800` | Bounded output stream limit |
| `converter.limits.max-capture-width` / `max-capture-height` | `10000` / `50000` | Pre-capture layout limits |
| `converter.limits.max-capture-pixels` | `100000000` | Pre-allocation pixel limit |
| `converter.limits.max-screenshot-bytes` | `52428800` | Base64 decoded screenshot limit |
| `converter.pptx.max-slides` | `100` | Верхняя граница плана; превышение даёт `PPTX_MAX_SLIDES_EXCEEDED` до screenshot/POI |
| `converter.pptx.smart-pagination-enabled` | `true` | DOM-aware screenshot pagination по PageModel |
| `converter.pptx.legacy-fallback-enabled` | `true` | При ошибке extraction/planner использовать прежние fixed-height slices; max-slides не fallback-ится |
| `converter.pptx.min-slice-height-pixels` | `120` | Минимальная CSS-высота эвристического/explicit slice |
| `converter.page-model.max-blocks` / `max-depth` | `5000` / `64` | DOM model graph bounds до materialization |
| `converter.page-model.max-text-length` / `max-table-cells` | `1000000` / `20000` | Общий text и table-cell budgets |
| `converter.page-model.max-assets` / `max-asset-bytes` | `2000` / `52428800` | Asset references и оценка embedded bytes |
| `converter.page-model.max-coordinate` / `max-warnings` | `1000000` / `500` | Geometry и bounded diagnostics |
| `converter.page-model.max-overlap-checks` | `100000` | Верхняя граница overlap comparisons |
| `converter.page-model.max-field-length` / `max-metadata-characters` | `4096` / `250000` | Per-field и aggregate budgets для metadata, hints, URI, style и transform strings до materialization |

Readiness всегда включает `document.readyState`, fonts, images, non-zero Canvas, DOM quiet и selector либо operator JS marker. PDF читается через bounded CDP stream. Base64 size проверяется до decode, PNG IHDR — до `ImageIO`, layout dimensions/pixels — до capture; PPTX PNG crops и final ZIP пишутся через bounded streams.

## Внутренний PageModel (M1)

После readiness trusted extraction script одним pass строит immutable renderer-independent `PageModel`: metadata/capture geometry, stable DOM-path IDs, parent/children, отдельные DOM и visual orders, normalized style subset, clipping/transform/overlap summary, text runs, links, list/table semantics, asset references, `data-pptx-*` hints и bounded warnings. Hidden, zero-area и `data-pptx-ignore` subtrees исключаются. Java validator повторно проверяет graph, finite geometry и все budgets до будущей pagination. Модель и extractor не импортируют Selenium DTO/CDP DTO или Apache POI; только browser adapter принимает `JavascriptExecutor`.

`PageModelDiagnostics.safeCanonicalJson(...)` — явный opt-in для deterministic tests/диагностики: он удаляет page text, link targets и URL path/query/fragment. Отдельный явно sensitive `canonicalJsonIncludingSensitiveContent(...)` предназначен только для контролируемых тестов. Обычные production logs не сериализуют модель и не содержат page text/query URL.

## DOM-aware screenshot pagination (M2)

`POST /MakePPTX` по умолчанию использует валидированный PageModel и deterministic `SmartPaginationPlanner`. Границы выбираются в порядке explicit `data-pptx-slide`, whitespace, block boundary и bounded fallback; planner не режет помещающиеся text/image/table/chart, `keepTogether`, transformed или overlapping blocks и защищает heading с последующим content. Oversized blocks делятся предсказуемо с content-free warning. План обязан давать положительные, непрерывные, монотонные slices без blank first/last slide.

Chromium по-прежнему создаёт один full-page PNG в уже существующих width/height/pixel/byte limits. CSS-координаты плана переводятся в пиксели screenshot с единой rounding policy и exact coverage; каждый crop помещается full-width без изменения aspect ratio. Это bounded single-capture design, но bitmap целой страницы всё ещё декодируется в памяти.

Если extraction или planner завершается ошибкой и `converter.pptx.legacy-fallback-enabled=true`, renderer использует прежнее fixed-height slicing. `PPTX_MAX_SLIDES_EXCEEDED` никогда не fallback-ится и возвращается до screenshot/POI. Smart pagination можно полностью отключить через `converter.pptx.smart-pagination-enabled=false`.

M2 остаётся image-only: native/editable objects, локализованный fallback, margins/footer/numbering, API `mode` и публичная выдача PageModel **не реализованы** и относятся к следующим milestones.

## Lifecycle и health

`ConversionService` и readiness probe используют общий fair `BrowserProcessLimiter`; `converter.limits.max-concurrent` является верхним пределом суммарных browser processes, включая health probe. `ConversionService` использует единое atomic state `QUEUED/RUNNING/CANCELLING/FINISHED`. Cancelled-before-start task не входит в browser work. Browser startup отдельно линеаризован (`NEW/STARTING/ACTIVE/CANCELLED`), поэтому отмена до claim не запускает driver, а поздно созданные ресурсы сразу передаются cleanup. Timeout вызывает зарегистрированный `BrowserSession.forceClose()`: Selenium quit/service-stop bounded, затем same-user process tree находится по уникальному profile/driver port, завершается graceful/forced, после каждого deadline повторно проверяется, и профиль удаляется только при подтверждённом завершении дерева. Surviving process/descendant даёт стабильный `BROWSER_CLEANUP_FAILED`, а не success. Permit освобождает только владелец task/finally после разрешения cleanup ownership.

```bash
curl http://localhost:8088/actuator/health/liveness
curl http://localhost:8088/actuator/health/readiness
```

Readiness group включает `browser`: bounded cached single-flight probe действительно открывает и закрывает браузер; timeout запускает supervised cleanup асинхронно и не растягивает health deadline. `fail-startup-if-unavailable=true` превращает failed probe в startup failure.

## Ошибки и лимиты

Problem JSON содержит `status`, стабильный `code`, безопасный `detail`, correlation ID и timestamp. Основные статусы: `400` invalid request/URL, `403` SSRF policy, `413` pre-allocation/output/slide limit, `422` target/navigation/artifact failure, `429` capacity (`Retry-After: 1`), `503` browser/executor, `504` timeout.

Размер JVM/container нужно задавать вместе с `max-concurrent`, pixel/screenshot/output limits. Compose example ограничивает app до 1 GiB и `/tmp` до 256 MiB; это отправная точка, а не универсальный sizing. Selenium/CDP всё ещё создают некоторые промежуточные объекты внутри browser/client libraries, поэтому heap и container limits остаются обязательными.

## Известные ограничения

- PPTX состоит из DOM-aware full-width raster slices и не содержит редактируемых DOM objects; при model/planner failure доступен legacy fixed-height fallback.
- Asset references содержат metadata/URI и bounded estimate для embedded data URI; M1 не загружает и не декодирует assets и не экспортирует Canvas/SVG payload.
- Application URL checks и DevTools interception — defense in depth, не network boundary.
- Process-tree supervisor рассчитан на дочерние процессы того же OS user и требует разрешения среды на `ProcessHandle.destroy/destroyForcibly`; deployment-level PID/cgroup supervision остаётся дополнительным рубежом.
- MHTML CDP API возвращает Java `String`, поэтому его исходное Chrome/Selenium representation нельзя ограничить до получения; последующее UTF-8 копирование и HTTP output ограничены.
- Custom readiness JavaScript доверенный operator config и исполняется в странице.
- Качество зависит от Chromium, fonts, CSP, locale/timezone и поведения страницы; CAPTCHA/DRM/authenticated pages не поддерживаются.

См. также [`docs/business-requirements.md`](docs/business-requirements.md), [`docs/system-requirements.md`](docs/system-requirements.md) и [`docs/pptx-roadmap.md`](docs/pptx-roadmap.md).
