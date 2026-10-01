# Roadmap интеллектуальной PPTX-конвертации

Статус документа: источник истины для проектирования и поэтапной реализации PPTX-конвертации. Дата актуализации: 2026-09-30.

## 1. Цель и границы

### Цель

Развить текущую конвертацию веб-страницы в набор полноэкранных изображений до предсказуемого гибридного renderer-а:

- текст, изображения, списки, таблицы и другие поддержанные элементы создаются как редактируемые native PPTX objects;
- неподдерживаемые, неоднозначные или визуально сложные области сохраняются как локализованный screenshot fallback;
- разбиение на слайды учитывает структуру DOM, геометрию блоков и визуальные разрывы;
- каждый результат проходит структурную, а в тестовом профиле — визуальную проверку.

Ключевой критерий качества — не максимальная доля редактируемых объектов любой ценой, а отсутствие потери содержимого и контролируемая визуальная близость при детерминированном поведении.

### Границы

В scope входят:

- извлечение нормализованной модели уже загруженной страницы из Chromium;
- анализ структуры, выбор границ слайдов и стратегии представления каждого блока;
- генерация смешанных слайдов из native PPTX objects и локальных растровых fallback-областей;
- обработка SVG, Canvas и ECharts;
- шаблоны, branding, авторские hints и проверка layout-а;
- отдельный семантический режим, не меняющий исходный визуальный контракт по умолчанию.

В scope **не** входят:

- реализация полного CSS layout/rendering engine вне браузера;
- точное воспроизведение всех CSS-свойств, WebGL, видео, анимаций, интерактивности и browser compositing;
- обход CSP, CORS, CAPTCHA, DRM, authentication и anti-bot механизмов;
- обязательная передача содержимого страницы внешним AI-сервисам;
- обещание pixel-perfect редактируемого результата для произвольной страницы.

Chromium остаётся источником истины для вычисленного layout-а и растеризации. При сомнении renderer выбирает сохранность визуального результата через локальный screenshot fallback, а не приблизительную native-реконструкцию.

## 2. Текущее состояние реализации

Ниже описан код после M0 hardening на дату этого документа. Будущие компоненты из последующих разделов ещё не являются текущим контрактом.

### HTTP и orchestration

- [`ConversionController.pptx(...)`](../src/main/java/com/nicodim/ocapp/api/ConversionController.java#L31-L32) обслуживает `POST /MakePPTX` и передаёт запрос общему conversion layer. Поля режима PPTX в request сейчас отсутствуют.
- [`ConversionService.convert(...)`](../src/main/java/com/nicodim/ocapp/conversion/ConversionService.java) ограничивает concurrency, запускает preflight и render в executor, применяет общий operation timeout и формирует результат. Atomic ownership state исключает browser start после queued cancellation; running timeout вызывает `RenderContext.cancel()` и ждёт bounded cleanup.
- [`PageRenderer.render(...)`](../src/main/java/com/nicodim/ocapp/conversion/PageRenderer.java) — текущая cancellation-aware граница browser/rendering adapter. Она принимает `URI`, `OutputFormat` и опциональный `RenderContext`, возвращает готовый `byte[]`; промежуточной модели страницы пока нет.

### Browser extraction и готовность страницы

- [`BrowserFactory.open()`](../src/main/java/com/nicodim/ocapp/browser/BrowserFactory.java) создаёт ChromeDriver/service/profile, применяет fail-closed proxy/DNS/QUIC/WebRTC policy и очищает каждый partial startup failure.
- [`ChromePageRenderer.render(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java) проверяет фактический browser GET status и redirect chain, readiness и layout bounds до capture.
- [`ChromePageRenderer.installRequestGuard(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java) проверяет HTTP(S)-запросы как defense in depth; production network boundary реализуется validating proxy deployment-ом.
- [`ChromePageRenderer.waitUntilReady(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java) ждёт base readiness, DOM quiet и configurable selector/operator JS marker; delayed ECharts/Canvas может явно сигнализировать завершение.

### Текущий PPTX renderer

- [`ChromePageRenderer.capturePptx(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java#L138-L144) получает один full-page PNG через CDP.
- [`ChromePageRenderer.createPresentation(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java#L146-L179) декодирует screenshot, вычисляет фиксированную высоту crop по aspect ratio слайда и помещает каждый crop как одно изображение на слайд Apache POI.
- Текущий результат визуальный, но не редактируемый. Разрывы не учитывают DOM, заголовки, строки таблиц и целостность блоков. Локализованного fallback-а нет: screenshot покрывает весь слайд.
- [`ConverterProperties.Pptx`](../src/main/java/com/nicodim/ocapp/config/ConverterProperties.java#L87-L94) задаёт только ширину, высоту и максимальное число слайдов.
- [`ChromePageRenderer.validateArtifact(...)`](../src/main/java/com/nicodim/ocapp/browser/ChromePageRenderer.java#L181-L194) для PPTX сейчас проверяет ZIP-сигнатуру, но не целостность OOXML, geometry, overflow и визуальные дефекты.

### Безопасность, ресурсы и тесты

- [`UrlSecurityPolicy.validate(...)`](../src/main/java/com/nicodim/ocapp/security/UrlSecurityPolicy.java#L27-L51) ограничивает URL, host и resolved addresses; [`SecureRedirectResolver.resolve(...)`](../src/main/java/com/nicodim/ocapp/security/SecureRedirectResolver.java#L31-L54) выполняет HEAD preflight с ограничением redirect-ов.
- [`BrowserSession`](../src/main/java/com/nicodim/ocapp/browser/BrowserSession.java) линеаризует startup/cancel, bounded выполняет quit/service stop, затем graceful/forced termination same-user process tree и profile cleanup; `RenderContext` регистрирует forced-close hook.
- Layout/pixel/base64/PNG IHDR/output/slide limits применяются до соответствующих дорогих application allocations; PDF читается bounded CDP stream. MHTML CDP всё ещё возвращает готовый `String`, что документировано как ограничение.
- Unit tests проверяют image pagination, limits, races и Selenium flow; Failsafe [`RealBrowserE2EIT`](../src/test/java/com/nicodim/ocapp/RealBrowserE2EIT.java) запускает local-only Chromium MHTML/PDF/PPTX, доказывает delayed marker/canvas в artifacts, browser redirects, real/late GET errors и blocked subresource.

## 3. Целевая архитектура

```text
HTTP/API
  -> conversion orchestration and limits
  -> Browser extraction
       Chromium navigation/readiness/security
       DOM + computed styles + assets + screenshots
  -> immutable PageModel
  -> analysis and pagination
       normalization -> block classification -> fallback planning -> slide plan
  -> PPTX renderer
       native objects + localized screenshot fallback + template/master
  -> layout validation
       OOXML/geometry checks -> metrics/warnings -> optional visual regression
  -> PPTX bytes
```

### 3.1 Browser extraction

Browser adapter загружает страницу, фиксирует viewport, locale, timezone, device scale и readiness policy. После стабилизации он одним согласованным extraction pass получает DOM hierarchy, bounding boxes, computed styles, text runs, asset references, authoring hints и сведения о stacking/overlap. Canvas, SVG и ECharts извлекаются специализированными bounded adapters; для любого блока можно запросить clip screenshot.

Extraction не создаёт Apache POI objects и не принимает решений о структуре презентации. Browser-specific DTO преобразуются в валидированный `PageModel` до закрытия browser session. Любая диагностика ограничивается безопасными metadata; текст страницы и URL с query не пишутся в обычные логи.

### 3.2 PageModel

`PageModel` — immutable, renderer-independent boundary между браузером и анализом. Минимальная модель должна включать:

- metadata документа и воспроизводимого capture environment;
- нормализованную геометрию страницы и viewport;
- дерево/плоский порядок `PageBlock` с устойчивыми IDs и типами (`TEXT`, `IMAGE`, `LIST`, `TABLE`, `SVG`, `CANVAS`, `CHART`, `CONTAINER`, `FALLBACK`);
- `Bounds`, clipping, z-order, transform summary и overlap metadata;
- поддержанный subset `ComputedStyle`, достаточный для решения native/fallback;
- text runs, link targets, list/table semantics и asset references;
- readiness observations, `data-pptx-*` hints и bounded warnings.

Модель не должна быть дампом всего DOM или всех computed styles. Вводятся лимиты на число blocks, depth, text length, table cells, asset count/bytes, coordinate range и warning count. Невалидная или unbounded geometry отклоняется до pagination.

### 3.3 Analysis и pagination

Analysis pipeline выполняет чистые детерминированные преобразования:

1. нормализует порядок, координаты и наследуемые параметры;
2. классифицирует каждый block как native, localized fallback или ignored;
3. строит группы keep-together и кандидаты разрывов по DOM boundaries/whitespace;
4. рассчитывает `SlidePlan` с content box, fragments, source mapping и fallback clips;
5. обрабатывает oversized blocks по явным правилам;
6. выдаёт стабильные warning codes для принудительных split/fallback/scale.

Алгоритм не обращается к сети и не зависит от wall-clock, случайности или порядка hash collections. При одинаковом `PageModel`, options и template он формирует одинаковый `SlidePlan`.

### 3.4 PPTX renderer

Renderer принимает только `SlidePlan`, validated assets, template и render options. Для поддержанных блоков он создаёт XSLF text boxes, pictures, lists, tables и shapes. Для неподдержанных фрагментов он помещает clip screenshots в исходные координаты, исключая двойную отрисовку native children.

Единый coordinate mapper преобразует CSS px в PowerPoint points и учитывает content box, scale и template layout. Asset registry дедуплицирует изображения и применяет лимиты. Renderer не исполняет JavaScript и не повторяет semantic/layout analysis.

### 3.5 Layout validation

Проверка выполняется после построения презентации и до возврата ответа:

- обязательная fast structural validation: OOXML открывается Apache POI, shapes находятся в допустимых bounds, нет пустых случайных слайдов, не превышены лимиты;
- обязательная geometry/text validation: overflow, unintended overlap, tiny fonts, low-resolution raster и broken references дают error или bounded warning по политике;
- визуальная regression validation запускается в отдельном test profile, рендерит PPTX эталонным инструментом и создаёт diff artifacts;
- метрики включают число native/fallback blocks, fallback area ratio, forced breaks и warning codes, без содержимого страницы в labels/logs.

## 4. Архитектурные принципы

### Deterministic core

Browser capture допускает внешнюю вариативность, но `PageModel -> SlidePlan -> PPTX` должен быть чистым и воспроизводимым. Все thresholds и fallback-правила версионируются; locale, timezone, fonts, viewport и browser version фиксируются в тестовом окружении. AI не участвует в visual/editable pipeline.

### Bounded resources

Лимиты применяются до дорогих allocations: document dimensions/pixels, base64 payload, DOM nodes, text, table cells, assets, screenshots, slides, POI objects, output bytes и warning count. Большой блок не отменяет лимиты: он разбивается, rasterize-ится с bounded scale либо приводит к контролируемой ошибке.

### Security-first

Chromium запускается в изолированной среде с deny-by-default egress или validating proxy; application-level URL checks сохраняются как второй рубеж. Extraction scripts считаются частью trusted application code, не загружают внешние библиотеки и не передают page data наружу. URI assets повторно проверяются, SVG sanitization запрещает active/external content, а temporary artifacts очищаются при success, failure и cancellation.

### No external AI without explicit opt-in

Visual и editable modes полностью локальны и детерминированы. Summary mode сначала имеет локальную heuristic implementation. Любой внешний AI adapter выключен по умолчанию и требует явной конфигурации, согласия на передачу содержимого, лимитов, redaction policy и audit-safe metadata.

### Backward compatibility

До введения нового API существующий `POST /MakePPTX` сохраняет image-based visual behavior. Новые request fields и modes не считаются контрактом до отдельного API decision. При rollout гибридного renderer-а должна существовать конфигурация/режим совместимости, позволяющий получить прежний full-page screenshot pipeline. Ошибки и MIME/filename semantics остаются стабильными, если release notes не объявляют версионированную миграцию.

## 5. Этапы реализации

Статусы в этом документе: **backlog** — работа учтена, но не является активным контрактом; **planned** — scope определён, реализация не начата; **in progress** и **done** назначаются только по фактическому состоянию кода и acceptance criteria.

### M0 — hardening foundation

- **Status:** done in `openclaw/m0-hardening` (issue closure/release остаются отдельным parent action)
- **Target version:** `0.1.x`
- **Issues:** [#3 — bounded rendering memory](https://github.com/denspbru/ocapp/issues/3), [#4 — deny-by-default Chromium egress](https://github.com/denspbru/ocapp/issues/4), [#5 — race-safe cancellation/cleanup](https://github.com/denspbru/ocapp/issues/5), [#8 — Chrome startup cleanup/readiness](https://github.com/denspbru/ocapp/issues/8), [#9 — readiness/redirect limits](https://github.com/denspbru/ocapp/issues/9), [#10 — real Chromium E2E](https://github.com/denspbru/ocapp/issues/10)
- **Dependencies:** текущий `0.1` baseline; для egress — выбранная deployment boundary.
- **Deliverables:** pre-allocation limits; external browser supervisor/cancellation state model; реальная readiness health probe; configurable page readiness; browser redirect accounting; isolated real-Chromium smoke profile; документированный deny-by-default deployment.
- **Acceptance criteria:**
  - oversized capture завершается контролируемой ошибкой без исчерпания JVM memory;
  - private/loopback/link-local/metadata destinations и non-HTTP browser channels недоступны Chromium по умолчанию;
  - timeout/cancel не освобождает permit до разрешения ownership и cleanup, а stuck browser уничтожается bounded supervisor-ом;
  - startup failure после создания driver всегда закрывает process и profile, readiness отражает реальную способность начать conversion;
  - delayed Canvas/ECharts и browser GET redirect chain проходят bounded readiness/redirect tests;
  - отдельный Maven profile выполняет MHTML/PDF/PPTX через реальный Chromium и локальные fixtures без внешней сети.

Смежный M0 scope также реализует [#2 — canonical `/MakeMHTML` + alias](https://github.com/denspbru/ocapp/issues/2), [#6 — IDN/terminal-dot canonicalization и private-address behavior](https://github.com/denspbru/ocapp/issues/6), [#7 — preserved protocol errors и browser GET status](https://github.com/denspbru/ocapp/issues/7). GitHub issues намеренно не закрываются этой рабочей веткой до parent review.

### M1 — PageModel и DOM extraction

- **Status:** planned
- **Target version:** `0.2`
- **Issue:** [#11 — PageModel and DOM block extraction](https://github.com/denspbru/ocapp/issues/11)
- **Dependencies:** M0 issues [#3](https://github.com/denspbru/ocapp/issues/3), [#5](https://github.com/denspbru/ocapp/issues/5), [#8](https://github.com/denspbru/ocapp/issues/8), [#9](https://github.com/denspbru/ocapp/issues/9), [#10](https://github.com/denspbru/ocapp/issues/10).
- **Deliverables:** immutable model and validators; browser extraction script/adapter; supported style subset; asset/hint references; safe diagnostic serialization; fixture corpus for extraction.
- **Acceptance criteria:**
  - одинаковый local fixture в фиксированном environment даёт эквивалентный `PageModel`;
  - text, image, list, table, SVG, Canvas/chart и fallback block types представлены без Apache POI dependency;
  - hidden/ignored elements исключаются по документированным правилам;
  - invalid/unbounded geometry, depth, block count и asset references отвергаются до pagination;
  - nested flex/grid, scrolling, transforms и absolute positioning покрыты fixture tests;
  - текущий screenshot conversion остаётся доступен независимо от extraction failure.

### M2 — smart screenshot pagination

- **Status:** planned
- **Target version:** `0.2`
- **Issue:** [#12 — DOM-aware smart screenshot pagination](https://github.com/denspbru/ocapp/issues/12)
- **Dependencies:** M1.
- **Deliverables:** deterministic `SlidePlan`; break candidate scoring; keep-together/orphan rules; margins/footer/numbering policy; oversized-block strategy; warning codes.
- **Acceptance criteria:**
  - heading не остаётся внизу слайда без связанного content block;
  - table/image/chart не разрезается, если полностью помещается в content box;
  - стандартные fixtures не создают пустых или почти пустых слайдов;
  - oversized block делится предсказуемо и выдаёт стабильный warning;
  - unit tests проверяют tie-breaking и boundary cases, visual fixtures — итоговые cuts;
  - fallback к прежнему fixed slicing доступен при invalid/unsupported model.

### M3 — редактируемые text и images

- **Status:** planned
- **Target version:** `0.3`
- **Issue:** [#13 — editable text and images](https://github.com/denspbru/ocapp/issues/13)
- **Dependencies:** M1, M2.
- **Deliverables:** coordinate mapper; text-run/style mapper; link support; image asset pipeline; overlap/unsupported-style classifier; localized fallback compositor.
- **Acceptance criteria:**
  - headings/paragraphs/simple spans редактируются в PowerPoint;
  - поддержанные font, size, weight, style, color, alignment и spacing сохраняются в заданных tolerances;
  - ссылки кликабельны, изображения сохраняют aspect ratio и ожидаемое положение;
  - неоднозначные CSS/overlap области переходят в локальный screenshot fallback без пропажи или дублирования content;
  - mixed native/fallback fixtures проходят structural и visual regression checks.

### M4 — редактируемые lists и tables

- **Status:** planned
- **Target version:** `0.3`
- **Issue:** [#14 — editable lists and paginated tables](https://github.com/denspbru/ocapp/issues/14)
- **Dependencies:** M3; pagination contract M2.
- **Deliverables:** nested list mapping; native table renderer; row pagination and repeated headers; merged-cell handling; documented wide-table policy.
- **Acceptance criteria:**
  - ordered/unordered markers и поддержанная nesting depth сохраняются;
  - table cells остаются редактируемыми, widths/heights/fills/borders/alignment и supported merges воспроизводятся;
  - row никогда не режется между слайдами, header rows повторяются;
  - long table paginates deterministically;
  - wide/complex table применяет документированный scale threshold либо localized screenshot fallback.

### M5 — SVG, Canvas и ECharts

- **Status:** planned
- **Target version:** `0.3.x`
- **Issue:** [#15 — SVG, Canvas and ECharts export](https://github.com/denspbru/ocapp/issues/15)
- **Dependencies:** M1; readiness strategy из [#9](https://github.com/denspbru/ocapp/issues/9); localized fallback из M3.
- **Deliverables:** sanitized SVG path; Canvas export adapter; ECharts detector/exporter; bounded scale/background options; block screenshot fallback.
- **Acceptance criteria:**
  - compatible SVG сохраняет aspect ratio/transparency и не содержит active/external content;
  - delayed Canvas/ECharts fixtures экспортируются без clipping после readiness;
  - export scale ограничен конфигурацией и resource budgets;
  - tainted/cross-origin Canvas и failed ECharts detection безопасно переходят в block screenshot;
  - page data не отправляется сторонним conversion services.

### M6 — authoring hints `data-pptx-*`

- **Status:** planned
- **Target version:** `0.3.x`
- **Issue:** [#16 — data-pptx authoring hints](https://github.com/denspbru/ocapp/issues/16)
- **Dependencies:** M1, M2; для `render="native"` — M3/M4/M5 по типу блока.
- **Deliverables:** versioned attribute contract для `data-pptx-slide`, `data-pptx-title`, `data-pptx-ignore`, `data-pptx-keep-together`, `data-pptx-notes`, `data-pptx-layout`, `data-pptx-render="image|native"`; parser/validator; precedence rules.
- **Acceptance criteria:**
  - explicit valid hints имеют документированный приоритет над heuristics;
  - страницы без hints сохраняют автоматическое поведение;
  - invalid/conflicting hints не обходят security/limits и дают bounded warnings либо stable validation error;
  - forced native не отключает safety fallback, если representation невозможно;
  - каждый атрибут и конфликт приоритетов покрыты fixture tests.

### M7 — templates и branding

- **Status:** planned
- **Target version:** `0.3.x`
- **Issue:** [#17 — PPTX templates, master layouts and branding](https://github.com/denspbru/ocapp/issues/17)
- **Dependencies:** M3; M6 для per-block/per-slide layout hints.
- **Deliverables:** validated `.pptx` template loader; master/layout selection; built-in default theme; logo/theme/fonts/footer/source URL/numbering options; template compatibility tests.
- **Acceptance criteria:**
  - valid configured template выбирается без изменения default behavior при отсутствии template;
  - missing/invalid/untrusted template даёт stable diagnostic error до browser work, где возможно;
  - global layout и `data-pptx-layout` выбирают только allowlisted compatible layouts;
  - итог сохраняет theme/master relationships и открывается в PowerPoint и LibreOffice;
  - template assets также подчиняются output/resource limits.

### M8 — layout validation и visual regression

- **Status:** planned
- **Target version:** `0.3.x`
- **Issue:** [#18 — layout validation and visual regression](https://github.com/denspbru/ocapp/issues/18)
- **Dependencies:** M2, M3; расширение coverage по мере M4/M5/M7.
- **Deliverables:** structural/layout validator; stable warning/error taxonomy; presentation quality metrics; isolated visual-regression profile; baseline approval/update procedure; diff artifacts.
- **Acceptance criteria:**
  - fast structural validation выполняется для каждого сгенерированного PPTX;
  - out-of-slide shapes, text overflow, unintended overlap, tiny fonts, empty slides и low-resolution images определяются по заданным policies;
  - warning metadata bounded и не раскрывает page content;
  - golden-image failure создаёт пригодный для анализа diff, не меняя baseline автоматически;
  - representative text/table/SVG/Canvas/mixed/template fixtures покрыты regression suite.

### M9 — semantic mode и optional AI

- **Status:** planned
- **Target version:** `0.4`
- **Issue:** [#19 — semantic presentation mode with optional AI](https://github.com/denspbru/ocapp/issues/19)
- **Dependencies:** stable M1 PageModel, M3/M4 native renderer, M7 templates, M8 validation.
- **Deliverables:** deterministic section detection/ranking; `summary` slide planner; source attribution; optional provider interface; explicit consent/configuration/redaction/limits; audit-safe metadata.
- **Acceptance criteria:**
  - existing visual behavior остаётся backward compatible;
  - summary работает без AI provider на deterministic heuristics;
  - external provider не вызывается без явного opt-in для конкретной deployment policy/request contract;
  - provider-disabled/offline tests доказывают отсутствие egress;
  - input/output/provider limits соблюдаются, summary content маркируется как автоматически преобразованный и сохраняет source links.

## 6. Release map

| Release | Scope | Exit condition |
|---|---|---|
| `0.1.x` | M0 hardening и общий backlog [#2](https://github.com/denspbru/ocapp/issues/2), [#6](https://github.com/denspbru/ocapp/issues/6), [#7](https://github.com/denspbru/ocapp/issues/7) | Безопасная bounded browser foundation и реальный Chromium smoke |
| `0.2` | M1 PageModel + M2 smart screenshot pagination | DOM-aware visual PPTX при сохранённом legacy fallback |
| `0.3` | M3 editable text/images + M4 lists/tables | Основной документный контент редактируем, сложные области локально rasterized |
| `0.3.x` | M5 charts/media + M6 hints + M7 templates + M8 validation | Управляемый branded hybrid renderer с quality gates |
| `0.4` | M9 semantic mode | Deterministic summary и только opt-in external AI adapter |
| `1.0` | Production-ready consolidation | Стабильный контракт, эксплуатационные SLO/limits, compatibility и полная release validation |

## 7. Fixture и test matrix

Все browser fixtures обслуживаются локальным HTTP server без внешней сети. Для каждой строки фиксируются viewport, DPR, locale, timezone, browser version и набор fonts. Минимальные проверки: extraction snapshot, pagination assertions, PPTX structural validation и, где применимо, approved visual baseline.

| Fixture | Что проверяем | Ожидаемая стратегия | Основные assertions |
|---|---|---|---|
| Article | headings, paragraphs, links, inline styles, images | native text/images, fallback только для unsupported blocks | нет orphan headings; links работают; порядок чтения стабилен |
| Dashboard / ECharts | cards, delayed charts, legends, mixed SVG/Canvas | native simple text + chart export или block screenshot | readiness дожидается charts; нет clipping; bounded scale |
| Wide table | много columns, long values, merged cells | scale до threshold, затем localized fallback | policy детерминирована; ничего не выходит за slide bounds |
| Long table | header + много rows | native table split by rows | rows не режутся; header повторяется; порядок сохраняется |
| SVG | inline SVG, gradients, transparency, external refs | sanitized vector при совместимости, иначе raster fallback | aspect ratio/transparency; active/external refs не исполняются |
| Delayed Canvas | draw после XHR/timer, tainted variant | Canvas export либо clip screenshot | readiness bounded; tainted Canvas не ломает conversion |
| Flex/Grid | nested layouts, gaps, wrapping | native простые blocks, fallback сложных overlap regions | geometry соответствует PageModel; нет duplication |
| Absolute positioning | z-index, overlap, clipped children | native только при однозначности, иначе grouped fallback | stacking визуально сохранён; fallback locality ограничена |
| Custom fonts | available/missing web fonts, weight mapping | native text с documented substitution policy | fonts readiness; warning при substitution; стабильные metrics |
| Oversized block | image/table/preformatted block выше slide | deterministic split/scale/fallback | нет infinite loop/empty slides; forced-break warning |
| Authoring hints | каждый `data-pptx-*` и conflicts | hint precedence с safety override | versioned contract; invalid hint bounded warning/error |
| Blocked subresources | private URL, redirect, failed image/font/chart | placeholder/fallback/error по policy | SSRF блокируется; page content не утекает; стабильная диагностика |

### Уровни тестирования

1. **Pure unit:** model validation, classification, coordinate conversion, break scoring, table pagination, warning taxonomy.
2. **Extraction integration:** real Chromium + local fixtures; сравнение canonicalized `PageModel`, без внешнего network.
3. **Renderer integration:** synthetic `PageModel`/`SlidePlan` -> PPTX; повторное открытие Apache POI и semantic assertions по shapes.
4. **Endpoint E2E:** HTTP -> real Chromium -> artifact, headers, limits, cancellation и cleanup.
5. **Visual regression:** отдельный profile/container с фиксированным PPTX renderer; approved baselines и diff artifacts.
6. **Security/resource tests:** malicious SVG/hints/geometry, redirect/subresource cases, oversized inputs, decompression/OOXML limits и stuck browser.

Flaky visual comparison не маскируется широким tolerance: сначала устраняется environmental nondeterminism, затем задаются локальные tolerances для anti-aliasing. Baselines меняются только отдельным reviewable действием.

## 8. Proposed API modes — future design, не текущий контракт

Сейчас `POST /MakePPTX` принимает только `{ "url": "..." }`; перечисленные ниже modes **не реализованы и не являются публичным контрактом**. Их схема, defaults и versioning требуют отдельного API decision.

### `visual`

Цель — максимальная визуальная верность. Варианты реализации:

- legacy full-page screenshot slicing для полной backward compatibility;
- DOM-aware screenshot pagination после M2.

Native objects не обязательны. Этот mode должен оставаться аварийным fallback для всего документа.

### `editable`

Цель — гибридная презентация: поддержанные элементы native, сложные области — localized screenshot fallback. Quality policy может ограничивать минимальную native coverage или, предпочтительно, только сообщать metrics, не ухудшая сохранность результата.

### `summary`

Цель — новая семантическая структура слайдов, а не копия исходного layout-а. По умолчанию используются deterministic heuristics. Внешний AI — отдельный выключенный adapter с explicit opt-in; `summary` не означает автоматического разрешения внешнего provider-а.

### Требования к будущему контракту

- отсутствие поля `mode` должно сохранять выбранное backward-compatible default behavior;
- unsupported mode/options возвращают стабильную validation error;
- security и resource limits нельзя ослабить request options;
- response должен уметь сообщить versioned warnings/metrics вне бинарного body без раскрытия содержимого;
- template, hints и AI options должны иметь deployment-level allowlists;
- до утверждения versioned request schema не добавлять ad hoc query parameters.

## 9. Risks и trade-offs

| Risk / trade-off | Последствие | Mitigation / правило решения |
|---|---|---|
| Visual fidelity vs editability | Native shape может отличаться от browser rendering | Fallback при неоднозначности; измерять fallback area, а не запрещать её |
| Browser nondeterminism | Разные cuts/baselines | Фиксировать capture environment; deterministic core после PageModel |
| Font substitution | Text reflow/overflow | Readiness, allowlisted fonts, metrics и fallback для критичных blocks |
| DOM order vs visual order | Неверная semantic/group ordering | Хранить DOM и visual order отдельно; summary не выводить только из coordinates |
| Overlap/transforms/clipping | Duplication или потеря pixels | Conservative classifier и grouped localized fallback |
| Huge pages/assets/tables | OOM, долгий render, слишком много slides | Pre-allocation budgets, bounded raster scale, controlled 413/error |
| Screenshot seams | Повтор/пропуск pixels на границе | Единая coordinate system, integer rounding policy и seam fixtures |
| SVG/Canvas/ECharts security | Active content, tainted data, external fetch | Sanitization, no external converters, bounded local fallback |
| Template incompatibility | Broken relationships/layout | Validate before use, allowlist layouts, open result in POI/office fixtures |
| Visual tests portability | Flaky CI | Isolated pinned toolchain и отдельный profile |
| AI privacy/cost/nondeterminism | Data disclosure и нестабильный output | AI off by default, explicit consent, limits, audit metadata, heuristic baseline |
| Backward compatibility | Existing clients получают иной layout | Legacy visual path, explicit rollout/default decision, release notes and contract tests |

## 10. Definition of done по releases

### `0.1.x`

- Все acceptance criteria M0 и issues [#2](https://github.com/denspbru/ocapp/issues/2), [#6](https://github.com/denspbru/ocapp/issues/6), [#7](https://github.com/denspbru/ocapp/issues/7) закрыты тестами и документацией.
- Реальный Chromium smoke зелёный в заявленном release environment.
- Browser lifecycle, egress boundary, limits и operational configuration воспроизводимы и документированы.
- Нет регрессии текущего HTTP contract и artifact generation.

### `0.2`

- Versioned PageModel schema и invariants задокументированы; extraction и pagination имеют deterministic tests.
- Smart visual pagination проходит обязательную fixture matrix subset: article, long table, dashboard, oversized block.
- Legacy screenshot path остаётся доступен и проверен.
- Все новые allocations и model dimensions bounded.

### `0.3`

- Text/images/lists/tables native-rendering criteria M3/M4 выполнены.
- Localized fallback не теряет и не дублирует content в mixed fixtures.
- PPTX открывается Apache POI, PowerPoint и LibreOffice на release fixtures.
- Backward-compatible default mode утверждён contract test-ами и документацией.

### `0.3.x`

- M5–M8 completed в заявленном составе release; hints/template contracts versioned.
- Structural validation выполняется для каждого output, visual regression — в release test profile.
- Security fixtures для SVG/Canvas/templates/blocked resources и resource limits зелёные.
- Quality metrics и warning codes стабильны, bounded и документированы.

### `0.4`

- Summary mode работает offline без provider-а и не меняет visual/editable outputs.
- AI adapter остаётся disabled-by-default; explicit opt-in, redaction/limits и no-provider-egress tests доказаны.
- Source attribution и маркировка автоматически преобразованного content присутствуют.
- Semantic fixtures дают deterministic output при отключённом AI.

### `1.0`

- Public API и compatibility policy версионированы; migration path документирован.
- Все обязательные fixture/security/resource/E2E/visual release gates зелёные в pinned environment.
- Production deployment имеет deny-by-default egress, observability без sensitive labels, documented capacity limits и cleanup guarantees.
- Поддерживаемый CSS/element subset, fallback semantics, warnings, templates и known limitations опубликованы.
- Release artifact проходит reproducible build/signature/container checks проекта и открывается поддерживаемыми office applications.

## 11. Decision log

| Дата | Решение | Обоснование | Последствие |
|---|---|---|---|
| 2026-09-30 | Использовать hybrid renderer | Полная native-реконструкция browser layout непрактична, а единый screenshot не редактируем | Native PPTX objects применяются только для уверенно поддержанных blocks |
| 2026-09-30 | Сохранять localized screenshot fallback как обязательный механизм | Сложный CSS, overlap, Canvas и ошибки extraction не должны приводить к потере content | Fallback — нормальный quality path, а не исключительный failure |
| 2026-09-30 | Ввести PageModel как boundary | Browser extraction, analysis/pagination и POI rendering должны развиваться и тестироваться независимо | PageModel не зависит от Selenium/CDP DTO и Apache POI |
| 2026-09-30 | Разрешать AI только как optional explicit opt-in | Конвертация должна работать локально, предсказуемо и без скрытой передачи данных | Visual/editable modes не используют AI; summary имеет deterministic baseline |

## 12. Open questions

1. Какой exact default должен получить `POST /MakePPTX` после M2/M3: legacy `visual`, smart `visual` или `editable`, и нужен ли versioned endpoint для изменения default?
2. Должен ли API возвращать warnings/metrics через headers, sidecar JSON endpoint, multipart response или отдельный asynchronous job contract?
3. Как версионировать `PageModel` и `data-pptx-*`: внутренний schema version, публичный contract version или оба?
4. Какие thresholds считаются достаточными для native rendering: supported CSS allowlist, overlap tolerance, fallback area ratio и font substitution policy?
5. Какой toolchain является эталонным для PPTX-to-image visual regression и какие версии PowerPoint/LibreOffice официально поддерживаются?
6. Где проходит граница template trust: только operator-installed files, signed templates или upload per request?
7. Нужны ли speaker notes/source metadata в visual/editable modes и как исключить sensitive URL query/fragment?
8. Как обрабатывать fixed/sticky elements на длинной странице: повторять, показывать один раз или следовать authoring hint?
9. Как нормализовать responsive pages: один фиксированный viewport, selectable profiles или template-driven content width?
10. Какие deterministic rules summary mode использует для ranking, deduplication и максимального объёма slide content?
11. Может ли request-level AI opt-in быть достаточным, или обязательно одновременное deployment-level разрешение и allowlisted provider?
12. Нужен ли persistent diagnostic artifact (`PageModel`/`SlidePlan`) для troubleshooting, учитывая privacy и storage policy?
