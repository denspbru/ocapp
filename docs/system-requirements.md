# Системные требования и техническая спецификация

## Платформа

Java 21+, Maven 3.9+, Spring Boot 3.5.6/Undertow, Selenium 4.49, Apache POI 5.4.1. Chrome/Chromium и совместимый ChromeDriver предоставляет deployment; для offline/production оба executable path задаются явно.

## HTTP contract

Канонический MHTML endpoint — `POST /MakeMHTML`; `POST /MakeSnapshot` — backward-compatible alias. Также доступны `/MakePDF` и `/MakePPTX`. Spring MVC сохраняет protocol statuses/headers (`404`, `405`/`Allow`, `415`). Только unexpected exceptions дают sanitized `500`.

## Архитектура и lifecycle

`ConversionController` отвечает за HTTP. `ConversionService` выполняет URL validation и HEAD preflight внутри operation timeout, затем вызывает cancellation-aware `PageRenderer`. Один atomic ownership state (`QUEUED`, `RUNNING`, `CANCELLING`, `FINISHED`) исключает browser start после queued cancellation. Running timeout вызывает hook `RenderContext.cancel()`, который принудительно закрывает зарегистрированный `BrowserSession`; cleanup bounded настройкой `cleanup-timeout`. Permit освобождается queued cancel owner либо worker `finally`, но не request thread до разрешения ownership.

`BrowserSession.close()` и `forceClose()` идемпотентны. Startup имеет отдельный atomic lifecycle `NEW/STARTING/ACTIVE/CANCELLED`: cancel до startup claim не вызывает driver starter, а late-attached resources сразу закрываются. Cleanup bounded ждёт `driver.quit()` и `ChromeDriverService.stop()`, затем внешний supervisor находит same-user child process tree по уникальному profile path/driver port, выполняет graceful/forced termination, повторно проверяет process/descendants после обоих deadlines и только после подтверждённого exit удаляет profile. Survivor даёт стабильный `BROWSER_CLEANUP_FAILED`. `BrowserFactory` применяет этот путь после любой partial-startup ошибки и валидирует оба явно заданных executable.

## Production egress contract

Default `converter.security.egress-mode=PROXY` fail-closed: без корректного absolute HTTP `proxy-url` context не запускается. Proxy применяется к Chromium и Java HEAD preflight. Chrome получает:

- `--proxy-server` и отключённый implicit loopback bypass;
- resolver rule `MAP * ~NOTFOUND` с исключением только proxy host, поэтому destination DNS выполняет proxy;
- `--disable-quic` и `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`;
- запрет operator arguments и inverse aliases, переопределяющих profile/proxy/resolver/QUIC/WebRTC/sandbox policy (`--no-proxy-server`, auto-detect, QUIC enable, sandbox disable, extensions и т. п.).

[`deploy/docker-compose.yml`](../deploy/docker-compose.yml) подключает app только к internal network. Squid находится также в egress network; [`squid.conf`](../deploy/squid.conf) после DNS запрещает loopback/private/link-local/CGNAT/multicast/metadata ranges для HTTP и CONNECT. WebSocket покрыт HTTP Upgrade либо CONNECT tunnel теми же destination ACL.

Это пример validating-proxy boundary, не доказательство host firewall isolation. Оператор поддерживает ACL, proxy image/config и cloud-specific special ranges. Application `UrlSecurityPolicy`/DevTools interception остаются defense in depth.

`UNSAFE` доступен только когда одновременно заданы:

```properties
converter.security.egress-mode=UNSAFE
converter.security.unsafe-acknowledge-risk=true
```

Он предназначен для trusted local E2E. `allow-private-addresses=true` разрешает localhost в application policy и не добавляет противоречащий localhost resolver rule; production proxy ACL всё равно должен запрещать private destinations.

## URL/navigation security

Host request и patterns проходят одну canonicalization: lowercase IDNA ASCII и удаление одного или нескольких terminal dots. Exact/wildcard deny/allow используют canonical value. Все DNS answers проверяются; deny имеет приоритет. Browser performance events независимо от HEAD preflight определяют top-level GET status и redirect count. Trace инвалидирует старый status при новой main-frame navigation и повторно проверяется после readiness, непосредственно до capture и после capture; redirect overflow или final `4xx/5xx` дают controlled `422`.

## Readiness

Bounded readiness включает:

1. `document.readyState=complete`, fonts, images и non-zero Canvas;
2. configurable DOM mutation quiet period;
3. `readiness-selector` либо trusted operator `readiness-script` marker;
4. optional settle delay.

Scripts никогда не принимаются через API. Delayed Canvas/ECharts может создать marker после завершения render.

## M6 authoring hints contract v1

OCApp распознаёт только следующий versioned allowlist: `data-pptx-slide="break-before"`, non-empty bounded `data-pptx-title`, boolean `data-pptx-ignore`, boolean `data-pptx-keep-together`, non-empty bounded `data-pptx-notes`, `data-pptx-layout="blank|title|title-only|content|section|closing"`, `data-pptx-render="image|native"`. Boolean form — attribute без value/empty или `true`; `false` отключает hint. Presence recognized attribute сохраняется независимо от normalized effect: false-only `ignore`/`keep-together` записывает `ocapp.authoring-hints.contract=1`, но не меняет planning/rendering. Enum и boolean values trim/case-normalize; title/notes сохраняют bounded source value.

Parser работает внутри уже bounded extraction pass. Invalid enum/boolean/blank text не попадает в normalized `AuthoringHints`, создаёт content-free `PPTX_HINT_INVALID` в общем `page-model.max-warnings` budget и далее эквивалентен отсутствию hint. Независимый Java validator разрешает только normalized values и иначе возвращает stable content-free `PAGEMODEL_INVALID`. Text metadata по-прежнему ограничены `max-field-length`, `max-metadata-characters` и общими model limits.

Порядок решений:

1. URL/navigation policy, browser isolation, readiness и все resource/output limits не могут быть ослаблены hints.
2. `ignore=true` исключает subtree до model/planning; `false` сохраняет automatic behavior.
3. `slide=break-before` имеет приоритет над whitespace/block heuristics и конфликтующим `keep-together`; minimum slice, max-slides и valid geometry остаются обязательны.
4. `keep-together=true` защищает помещающийся block только от heuristic split; oversized content получает прежний deterministic forced split.
5. `render=image` создаёт один bounded localized crop корня, подавляет descendants и тем самым не дублирует region; ancestor image выше descendant native.
6. `render=native` — preference, а не bypass: unsafe/unrepresentable transform, clipping, overlap, type/style/asset всё равно использует localized screenshot fallback.
7. Для каждого итогового slide первый non-empty title/notes/layout по `(domOrder,id)` побеждает; другой value даёт максимум один `PPTX_HINT_CONFLICT` на attribute/slide без values в warning. Максимум conflict warnings равен трём на slide и дополнительно ограничен `pptx.max-slides`.

Title создаётся как POI title placeholder, notes — как speaker-notes body. `layout` выбирает allowlisted semantic alias и записывается в slide name; при M7 template создаётся slide из layout того же package с precedence hint → global → content → blank. PPTX custom property `ocapp.authoring-hints.contract=1` фиксирует contract version. Pages без recognized hints сохраняют M2–M5 automatic planning/rendering и не получают contract property.

## M7 templates and branding (0.5.0)

`converter.pptx.template.path` принимает только absolute operator-local `.pptx`. Локальный preflight выполняется под capacity permit и operation timeout, но до URL/DNS/navigation и browser acquisition: bounded immutable bytes/manifest, duplicate/traversal/absolute ZIP-name checks, entry/count/expanded-size/ratio limits, запрет external relationships, macros/VBA, ActiveX, OLE/embedded packages и linked media, затем structural POI validation. JVM `ZipSecureFile` globals не меняются. Fresh `XMLSlideShow` создаётся на каждую conversion; existing content slides default `REJECT`, explicit `REMOVE`.

Template theme/master/layout authoritative. Alias targets должны быть unique; one content box используется planner, visual crops и editable mapper. Optional branding: bounded local PNG/JPEG logo, no-template colors/fonts, footer, source `NONE|ORIGIN|ORIGIN_PATH`, slide numbers. Branding входит в shape/asset/output budgets. Final bytes проходят POI reopen validator. Stable errors content-free и не содержат filesystem/layout/relationship/page content.

## M3 border/image fidelity hardening

PageModel хранит четыре computed border sides. Native text/container border допустим только при uniform visible `solid` sides, finite bounded nonnegative width и parseable opaque color; width переводится из CSS px в PPT points единым coordinate mapping. Unsupported, non-uniform, dashed/dotted/complex или translucent border переводит bounded region в localized screenshot fallback, сохраняя background и не создавая duplicate border/content shapes. Safe bounded embedded data images остаются native; external/non-embedded references renderer повторно не загружает и сохраняет через screenshot fallback.

Автоматизированный release gate `0.5.0`: exact/finite Apache POI reopen assertions и fail-closed Chromium + LibreOffice visual checks. Microsoft PowerPoint не запускался в этом release environment; manual PowerPoint validation выполняется при доступности и обязательна перед `1.0`/GA.

## M8 layout validation contract (0.5.1)

M8 is additive to the `0.5.0` wire contract: `/MakePPTX` keeps the existing request and binary success response. Both visual and editable paths classify generated shapes by stable POI shape ID, serialize the final PPTX, run bounded OPC validation, reopen it with POI and execute deterministic layout validation before return. The validator is mandatory; there is no bypass switch.

Objective non-finite/negative/out-of-slide anchors and broken picture references are **ERROR** findings and retain the existing sanitized `422 PPTX_TEMPLATE_OUTPUT_INVALID`. Text overflow, unintended overlap, tiny font, empty slide, low raster density and unavailable renderer/font measurement are **WARN** throughout `0.5.x`. Validation interruption preserves the cancellation path instead of becoming an output error. Scan budgets reserve a bounded truncation diagnostic.

Stable codes are `PPTX_LAYOUT_OUT_OF_SLIDE`, `PPTX_LAYOUT_TEXT_OVERFLOW`, `PPTX_LAYOUT_UNINTENDED_OVERLAP`, `PPTX_LAYOUT_TINY_FONT`, `PPTX_LAYOUT_EMPTY_SLIDE`, `PPTX_LAYOUT_LOW_RES_IMAGE`, `PPTX_LAYOUT_BROKEN_REFERENCE`, `PPTX_LAYOUT_MEASUREMENT_UNAVAILABLE` and `PPTX_LAYOUT_DIAGNOSTICS_TRUNCATED`. A diagnostic contains only enum code/severity, slide/shape ordinals, allowlisted role/provenance and finite numeric actual/threshold fields. Fixed metrics contain mode, shape-role counts, fallback-area ratio, forced breaks, pagination warnings, diagnostic counters and truncation totals. Neither diagnostics, metrics nor aggregate logs may contain document/notes text, URLs, filesystem/relationship paths, arbitrary names or image bytes.

Expected visual-slice/branding, background, template-placeholder and explicitly registered fallback relationships are excluded from unintended-overlap findings. Speaker notes are package-validated but excluded from visible-layout, empty-slide, overlap, font and visual metrics.

### Visual regression profile

`pptx-visual-regression` is separate from Surefire and `real-browser`. It requires an explicitly configured local LibreOffice whose exact version matches the approved manifest, checksum-protected synthetic baselines and fixed 144-DPI PDFBox rasterization. It performs no downloads. Missing executable/baseline, checksum/version mismatch, timeout, conversion failure, page-count mismatch or comparison failure is a test failure.

LibreOffice runs with a unique temporary profile, no listener, invalid network proxies, bounded captured output and process-tree termination on timeout/failure. Approved baselines are read-only test inputs. Candidate, diff and content-free comparison metadata stay below `target/pptx-visual-regression`. Existing fail-closed real-browser fixtures continue to cover text/table/SVG/Canvas/mixed/template pipelines. The automated `0.5.1` release gate combines Java 21 `verify`, real-browser/LibreOffice integration and checksum-protected visual regression; manual Microsoft PowerPoint validation remains required before `1.0`/GA.

## Resource bounds

До expensive allocation проверяются CDP layout width/height/pixels. Base64 decoded upper bound проверяется до decode, а PNG signature/IHDR dimensions — до `ImageIO`. PDF использует CDP `ReturnAsStream` и bounded chunks. PPTX ограничен screenshot bytes/pixels, slides, per-crop PNG и final ZIP bounded streams. Controlled overflow возвращает `413 OUTPUT_TOO_LARGE`.

Ограничение: `Page.captureSnapshot` возвращает MHTML как готовый `String`; allocation внутри Chrome/Selenium предшествует application byte limit. POI/ImageIO и Selenium также имеют внутренние allocations, поэтому container/JVM RAM limit обязателен. Compose example задаёт app 1 GiB, `/tmp` 256 MiB; production sizing определяется нагрузочным тестом и `max-concurrent`.

## Health

Readiness health group включает `readinessState,browser`. Conversion и health probe разделяют один fair `BrowserProcessLimiter`, поэтому `max-concurrent` ограничивает их суммарное число browser processes. Burst cache misses остаются single-flight, cancellation не теряет permit.  Browser indicator с configurable timeout действительно выполняет start/close; cache refresh single-flight, TTL отсчитывается после probe. Timeout возвращает `DOWN` в пределах health deadline и запускает cancellable supervised cleanup без ожидания отдельного cleanup budget; `fail-startup-if-unavailable=true` делает failed probe startup error. Details скрыты `show-details=never`.

## Ключевые настройки

- browser: `binary`, `driver-path`, `page-load-timeout`, `script-timeout`, `readiness-timeout`, `dom-quiet-period`, `readiness-selector`, `readiness-script`, `cleanup-timeout`, `health-probe-timeout`, `health-cache-ttl`;
- security: `egress-mode`, `proxy-url`, `unsafe-acknowledge-risk`, `allowed-hosts`, `denied-hosts`, `allow-private-addresses`, `max-redirects`, `preflight-timeout`;
- limits: `max-concurrent`, `acquire-timeout`, `operation-timeout`, `max-output-bytes`, `max-capture-width`, `max-capture-height`, `max-capture-pixels`, `max-screenshot-bytes`;
- PDF/PPTX page geometry и `pptx.max-slides`.
- M8 layout validation: overflow point/ratio, body/footer font, raster PPI, overlap area/ratio thresholds and `max-diagnostics`, `max-shapes`, `max-text-shapes`, `max-images`, `max-overlap-comparisons` budgets.

Полные defaults: [`application.properties`](../src/main/resources/application.properties).

## Тестирование

Default Surefire suite не использует внешнюю сеть/browser. Он проверяет protocol errors, routes, host canonicalization/IDN/dots, egress config/flags, dimensions/base64/PNG limits, navigation traces/status/redirects, deterministic cancellation races, startup cleanup, health cache/time bounds и artifact signatures.

`mvn -Preal-browser verify` включает Failsafe `*E2EIT`: local `HttpServer`, random-port Spring app, MHTML/PDF/PPTX endpoints, serialized delayed marker и PPTX pixel proof, HEAD-200/GET redirect chain, real GET 404/500 и late navigation, blocked private subresource, signatures/headers и temporary-profile cleanup. Profile ничего не скачивает и проверяет совпадение major browser/driver. Локальный opt-in явно skips при отсутствии пары; CI задаёт `-Docapp.e2e.failClosed=true` или `OCAPP_E2E_FAIL_CLOSED=true`, чтобы отсутствие/несовместимость завершали Failsafe ошибкой.

JaCoCo bundle gates: instruction/line ≥90%, branch ≥70%, method/class =100%.

Process supervisor управляет только дочерними процессами того же OS user; container PID/cgroup policy остаётся рекомендуемым deployment boundary на случай запрета host-ом `ProcessHandle.destroy/destroyForcibly`.


## M5 graphics export configuration

Inline SVG is accepted only through a fail-closed element/attribute allowlist; active elements, event handlers, CSS/style and URL/external references are rejected to localized screenshots. Canvas is exported locally as PNG, and ECharts as configured `png`/`svg`; no page data is sent to an external conversion service. Defaults: scale `2.0` (range `1.0..4.0`), background `transparent` or hex color, format `png`, `16,000,000` pixels and `8,388,608` decoded bytes per graphic. Pixel checks precede temporary Canvas/ECharts allocations; encoded length is checked before Java decode and POI embedding.
