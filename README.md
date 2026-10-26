# OCApp

OCApp — Java 21 REST-сервис, который загружает веб-страницу в отдельной сессии Chrome/Chromium и возвращает MHTML, PDF или гибридный editable/fallback PPTX. Результаты на сервере не сохраняются.

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

Он использует `OCAPP_E2E_CHROME` и `OCAPP_E2E_CHROMEDRIVER` либо известные local/cache paths. Локальный opt-in без fail-closed gate явно пропускается, если совместимая пара отсутствует. CI обязан задавать `-Docapp.e2e.failClosed=true` либо `OCAPP_E2E_FAIL_CLOSED=true`: отсутствие executable, невозможность прочитать версию или несовпадение major тогда завершает Failsafe ошибкой. Ничего не скачивается. Profile проверяет MHTML/PDF/PPTX signatures и headers, safe/unsafe SVG, delayed Canvas, реально tainted cross-origin Canvas, delayed/missing ECharts, DOM-aware pagination, M6 hint extraction/precedence/OOXML metadata, HEAD-200/GET redirect chain, blocked private subresource, deterministic DOM extraction и cleanup временных профилей.

Release gate `0.5.1` состоит из Apache POI structural/layout validation, fail-closed real-Chromium + LibreOffice smoke и checksum-protected visual regression. Microsoft PowerPoint в автоматизированной среде не запускался и не заявляется как evidence; ручная проверка в PowerPoint выполняется, когда он доступен, и обязательна перед `1.0`/GA.

Опциональный external-office gate использует явно заданный локальный LibreOffice, отдельные bounded profiles и timeout; platform path не зашит в build. Он генерирует mixed M3, graphics M5, hints M6 и встроенный пятистраничный `test_report` реальным Chromium pipeline, экспортирует decks через Impress в PDF и рендерит PDF через PDFBox. M3 проверяет один slide/page, отсутствие потери/дублирования marker text, clickable URI, native image и localized Canvas fallback; M5 проверяет POI SVG+PNG picture types и видимые sanitized SVG, delayed Canvas и ECharts цвета; M6 проверяет две страницы, единственность title и видимый forced localized image; `test_report` проверяет пять hybrid slides, единственность native h1/h2, editable text/table, две SVG chart pictures, отсутствие slide-sized raster shape и сохраняет PNG preview каждой страницы. При заданном artifacts directory сохраняются исходные PPTX, PDF, PNG и LibreOffice logs:

```bash
mvn -Preal-browser -Docapp.e2e.failClosed=true \
  -Docapp.e2e.libreoffice=/Applications/LibreOffice.app/Contents/MacOS/soffice \
  -Docapp.e2e.artifactsDir=/tmp/ocapp-office-evidence verify
```

Вместо system properties доступны `OCAPP_E2E_LIBREOFFICE` и `OCAPP_E2E_ARTIFACTS_DIR`. Без LibreOffice property external-office test явно skipped; при заданном executable conversion failure/timeout является test failure.

Строгие JaCoCo gates: instruction/line ≥90%, branch ≥70%, method/class =100%.

## Безопасный запуск

Встроенный default — `converter.security.egress-mode=PROXY` с пустым `proxy-url`, поэтому приложение **намеренно не запускается**, пока validating proxy не настроен:

```bash
java -jar target/ocapp-0.5.1.jar \
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
| `converter.pptx.smart-pagination-enabled` | `true` | DOM-aware pagination по PageModel |
| `converter.pptx.editable-enabled` | `true` | Hybrid M3: native text/images/simple backgrounds плюс localized screenshot fallback; `false` сохраняет M2 full-width visual slices |
| `converter.pptx.legacy-fallback-enabled` | `true` | При ошибке extraction/planner использовать прежние fixed-height slices; max-slides не fallback-ится |
| `converter.pptx.max-items-per-slide` | `1000` | Pre-POI shape budget; при превышении slide безопасно сворачивается в один screenshot crop |
| `converter.pptx.max-native-table-cells` | `500` | Максимум source cells для native editable table; larger tables use localized screenshot fallback |
| `converter.pptx.max-native-table-columns` | `12` | Максимум columns для native editable table |
| `converter.pptx.min-native-table-column-points` | `18` | Минимальная средняя ширина native column после CSS→points mapping; более широкая/плотная table rasterizes locally |
| `converter.pptx.min-slice-height-pixels` | `120` | Минимальная CSS-высота эвристического/explicit slice |
| `converter.pptx.graphics-export-scale` | `2.0` (`1.0..4.0`) | Bounded scale для локального Canvas/ECharts export |
| `converter.pptx.graphics-export-background` | `transparent` | `transparent` либо `#RRGGBB`/`#RRGGBBAA` для Canvas/ECharts |
| `converter.pptx.graphics-export-format` | `png` | `png` или `svg` для ECharts `getDataURL`; Canvas остаётся PNG |
| `converter.pptx.max-graphics-export-pixels` | `16000000` | Pixel budget до temporary Canvas/ECharts allocation |
| `converter.pptx.max-graphics-export-bytes` | `8388608` | Per-graphic decoded-byte budget до Java decode/POI embedding |
| `converter.pptx.template.path` | empty | Absolute operator-local `.pptx`; empty keeps the exact built-in path |
| `converter.pptx.template.trusted-root` / `converter.pptx.brand.trusted-root` | empty | Optional absolute real-path containment root. Empty safely defaults to the configured file’s real parent for backward compatibility; every path component is checked without following symlinks. |
| `converter.pptx.template.original-slide-policy` | `REJECT` | Existing template content slides are rejected; explicit `REMOVE` deletes them from each fresh session |
| `converter.pptx.template.max-*` | see `application.properties` | Compressed bytes, ZIP entries, entry bytes, expanded bytes and ratio gates before POI |
| `converter.pptx.layouts.default-alias` | `content` | Global semantic layout fallback; per-page hint wins first |
| `converter.pptx.layout-validation.text-overflow-tolerance-points` / `-ratio` | `1.0` / `0.02` | Warning when both absolute and relative measured overflow exceed thresholds |
| `converter.pptx.layout-validation.minimum-body-font-points` / `minimum-footer-font-points` | `8` / `6` | Warning-only effective font thresholds |
| `converter.pptx.layout-validation.minimum-image-ppi` | `96` | Warning-only displayed raster density threshold; SVG is exempt |
| `converter.pptx.layout-validation.overlap-area-points` / `overlap-area-ratio` | `4` / `0.05` | Both thresholds must be exceeded for unintended-overlap warning |
| `converter.pptx.layout-validation.max-diagnostics` / `max-shapes` / `max-text-shapes` / `max-images` / `max-overlap-comparisons` | `200` / `10000` / `5000` / `2000` / `100000` | Deterministic validation budgets; objective truncation is reported |
| `converter.pptx.brand.*` | disabled/empty | Bounded local PNG/JPEG logo, colors/fonts/footer, redacted source URL and slide numbers |
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

## Editable text and images (M3)

При `converter.pptx.editable-enabled=true` deterministic planner классифицирует уже paginated PageModel в CSS pixels. Простые text runs становятся XSLF text boxes с font family/size/weight/style/color, alignment, line spacing и clickable links; Uniform visible `solid` border с bounded неотрицательной CSS-шириной и parseable opaque color сохраняется как native line на text/container shape; non-uniform, dashed/dotted/complex/translucent border переводит только соответствующую область в localized screenshot fallback. CSS px переводятся в PPT points тем же mapper-ом, без отдельного border/content duplicate shape. ordered/unordered LI становятся native bullets/auto-numbering до nesting level 8; supported TABLE становится editable XSLFTable с proportional widths/heights, fills, borders, alignment и merges. Long tables select whole source rows deterministically and repeat contiguous leading TH rows. Tables over configured cell/column/minimum-width bounds, unsupported styles, or row-spans crossing pagination use a localized screenshot fallback. Безопасные embedded raster data assets становятся native pictures с сохранением aspect ratio и CSS position; External/non-embedded image references не загружаются renderer-ом повторно и консервативно переходят в localized screenshot fallback. простые backgrounds становятся shapes. Совместимый inline SVG проходит fail-closed allowlist sanitization и сохраняется в OOXML как SVG picture; Canvas экспортируется локально в PNG после dimension/scale/pixel/byte checks; ECharts instance экспортируется локально через bounded `getDataURL` в PNG/SVG. Active/external/malformed/unsupported SVG, tainted Canvas, missing/failed ECharts, clipping, transforms, overlap groups и unsupported assets получают localized crops из того же bounded full-page screenshot. Crop подавляет native content под теми же pixels, поэтому mixed slide не теряет и не дублирует область.

Один uniform CSS-px-to-point mapper используется для всех native и fallback shapes. Asset count/decoded bytes/dimensions, shapes per slide, total localized crop pixels, screenshot bytes, slides и final ZIP проверяются до соответствующих дорогих allocations. `converter.pptx.editable-enabled=false` сохраняет M2 DOM-aware full-width screenshot output; extraction/planner failure по-прежнему использует fixed-height legacy path, если он разрешён.

## Authoring hints contract v1 (M6)

Релиз `0.5.0` поддерживает opt-in HTML contract v1; его версия записывается в PPTX custom property `ocapp.authoring-hints.contract=1`. Наличие любого распознанного атрибута, включая `data-pptx-ignore="false"` и `data-pptx-keep-together="false"`, записывает contract metadata независимо от нормализованного эффекта. Наличие hints не меняет URL validation, browser isolation/readiness, PageModel budgets, screenshot/asset/shape/pixel limits или output bounds. Страница без этих атрибутов проходит прежний automatic pagination и hybrid rendering без изменений.

| Атрибут | Допустимое значение | Семантика v1 |
|---|---|---|
| `data-pptx-slide` | `break-before` | Кандидат явной границы перед block; имеет приоритет над whitespace/block heuristics и `keep-together`, но minimum slice/max-slides/geometry limits сохраняются. |
| `data-pptx-title` | непустой bounded text | Первый по DOM order value внутри итогового slice становится отдельным title placeholder. |
| `data-pptx-ignore` | attribute/empty, `true`, `false` | Empty/`true` исключает весь subtree до PageModel и rendering; `false` явно не исключает. |
| `data-pptx-keep-together` | attribute/empty, `true`, `false` | Empty/`true` запрещает heuristic split, если block помещается; explicit slide break имеет приоритет, oversized block остаётся bounded forced split. |
| `data-pptx-notes` | непустой bounded text | Первый по DOM order value внутри slice записывается в speaker notes. |
| `data-pptx-layout` | `blank`, `title`, `title-only`, `content`, `section`, `closing` | Выбирает allowlisted semantic alias; с template создаёт slide из соответствующего layout того же package. Precedence: hint → global default → `content` → `blank`. |
| `data-pptx-render` | `image`, `native` | `image` принудительно создаёт один localized screenshot crop block-а и подавляет descendants/native shapes под ним; `native` запрашивает editable representation, но transforms, clipping, overlap, unsupported types/styles/assets и все safety/resource checks всё равно переводят region в localized fallback. |

## PPTX templates and branding (M7, 0.5.0)

Optional `converter.pptx.template.path` принимает только absolute operator-local `.pptx`. Under the conversion permit and operation timeout—but before URL validation, DNS, navigation, network, or browser acquisition—OCApp reads it into bounded immutable cached bytes. Every path component is checked without following symlinks, an optional real `trusted-root` is enforced, and pre/post file identity/size/mtime must match. The package gate validates ZIP names/duplicates/traversal/absolute paths/count/sizes/ratio, normalized internal relationship targets and target existence, strict part/content types, and rejects external relationships, VBA/macros, ActiveX, OLE/packages, comments/customXml/sample parts, notes in templates, disguised media, and malformed/extreme presentation structures; it then opens a fresh `XMLSlideShow` for every conversion. It never changes JVM `ZipSecureFile` globals. Existing content slides are rejected by default; `REMOVE` is an explicit deployment policy. Template theme/master/layout relationships remain authoritative.

Aliases are fixed to `blank|title|title-only|content|section|closing`; configured layout names must resolve uniquely. One validated layout content box drives pagination, visual crops and editable mapping. Branding supports a bounded local PNG/JPEG logo whose dimensions/image count are read from ImageReader metadata before any pixel decode, optional colors/fonts when no template is configured, footer, source origin+path with user-info/query/fragment removed, and slide numbers. One immutable preflighted branding snapshot is shared by visual and editable paths. Branding shapes/assets participate in existing shape/asset/output budgets. Every deck is reopened by POI and checked for slide count, page geometry, template relationships and in-slide finite anchors. No-template defaults remain unchanged.

Precedence v1: security/resource validation всегда выше hints; `ignore=true` удаляет subtree; explicit slide break выше pagination heuristics/`keep-together`; ancestor `render=image` выше descendant `render=native`; safety/representability fallback выше `render=native`. Для title/notes/layout побеждает первый непустой value по `(domOrder,id)`; отличающиеся последующие values дают один content-free `PPTX_HINT_CONFLICT` на атрибут и slide. Invalid enum/boolean/blank text даёт bounded content-free `PPTX_HINT_INVALID` и трактуется как отсутствие hint; Java boundary дополнительно отклоняет ненормализованные значения стабильным `PAGEMODEL_INVALID`. Значения title/notes и metadata подчиняются `max-field-length` и `max-metadata-characters`; parser warnings — `max-warnings`, а conflicts — не более трёх на slide при bounded `max-slides`. Warning custom properties содержат только code, slide index и имя атрибута, но не page content.

## PPTX layout validation и visual regression (M8, 0.5.1)

M8 не меняет HTTP request/response, MIME, filename или successful binary output release `0.5.0`. Каждый сгенерированный PPTX после serialization проходит bounded package validation, POI reopen и deterministic layout scan. Non-finite/negative/out-of-slide geometry и broken picture references блокируют output через прежний sanitized `422 PPTX_TEMPLATE_OUTPUT_INVALID`. Renderer/font-dependent quality findings — `TEXT_OVERFLOW`, `UNINTENDED_OVERLAP`, `TINY_FONT`, `EMPTY_SLIDE`, `LOW_RES_IMAGE` и `MEASUREMENT_UNAVAILABLE` — warning-only в `0.5.x`, чтобы сохранить compatibility. Interruption не превращается в diagnostic и сохраняет cancellation path.

Stable content-free codes: `PPTX_LAYOUT_OUT_OF_SLIDE`, `PPTX_LAYOUT_TEXT_OVERFLOW`, `PPTX_LAYOUT_UNINTENDED_OVERLAP`, `PPTX_LAYOUT_TINY_FONT`, `PPTX_LAYOUT_EMPTY_SLIDE`, `PPTX_LAYOUT_LOW_RES_IMAGE`, `PPTX_LAYOUT_BROKEN_REFERENCE`, `PPTX_LAYOUT_MEASUREMENT_UNAVAILABLE`, `PPTX_LAYOUT_DIAGNOSTICS_TRUNCATED`. Diagnostics содержат только enum code/severity, slide/shape ordinals, allowlisted role/provenance и finite numeric actual/threshold values. Page/notes text, URL, filesystem/relationship path, shape names и image bytes не сохраняются. Fixed-field metrics включают visual/editable mode, role counts, fallback-area ratio, forced breaks, pagination warnings, quality counts и bounded truncation totals; логи используют только эти aggregate values.

Visual regression изолирован от обычных Surefire и `real-browser` profile. `pptx-visual-regression` требует явно заданный local LibreOffice exact version, checksum-protected synthetic baselines и fixed 144-DPI PDFBox rendering. LibreOffice получает unique temporary profile, bounded output, timeout, invalid network proxies и process-tree cleanup. Missing/mismatched executable, baseline, checksum, page count или comparison — test failure. Tests никогда не обновляют baselines; actual/diff/metadata остаются только под `target/pptx-visual-regression`.

```bash
mvn -Ppptx-visual-regression \
  -Docapp.visual.libreoffice=/absolute/path/to/soffice verify
```

M3–M7 real-browser fixtures продолжают покрывать text/table/SVG/Canvas/mixed/template pipelines, а isolated two-page synthetic baseline проверяет детерминированный Office/PDFBox image output.

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

- M5 graphics path намеренно консервативен: Apache POI сохраняет прошедший allowlist SVG как vector picture, но Canvas всегда имеет честную raster boundary в Chromium PNG export; unsupported SVG features (включая style/animation/foreignObject/external or URL refs), tainted Canvas и failed ECharts остаются localized raster fallback.
- Microsoft PowerPoint validation в этой ветке не заявлена. Обязательный structural reopen выполняет Apache POI; fail-closed Chromium + LibreOffice M7 gate сохраняет PPTX/PDF/log и PNG каждой страницы.
- Application URL checks и DevTools interception — defense in depth, не network boundary.
- Process-tree supervisor рассчитан на дочерние процессы того же OS user и требует разрешения среды на `ProcessHandle.destroy/destroyForcibly`; deployment-level PID/cgroup supervision остаётся дополнительным рубежом.
- MHTML CDP API возвращает Java `String`, поэтому его исходное Chrome/Selenium representation нельзя ограничить до получения; последующее UTF-8 копирование и HTTP output ограничены.
- Custom readiness JavaScript доверенный operator config и исполняется в странице.
- Качество зависит от Chromium, fonts, CSP, locale/timezone и поведения страницы; CAPTCHA/DRM/authenticated pages не поддерживаются.

См. также [`docs/business-requirements.md`](docs/business-requirements.md), [`docs/system-requirements.md`](docs/system-requirements.md) и [`docs/pptx-roadmap.md`](docs/pptx-roadmap.md).
