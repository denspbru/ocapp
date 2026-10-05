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

Полные defaults: [`application.properties`](../src/main/resources/application.properties).

## Тестирование

Default Surefire suite не использует внешнюю сеть/browser. Он проверяет protocol errors, routes, host canonicalization/IDN/dots, egress config/flags, dimensions/base64/PNG limits, navigation traces/status/redirects, deterministic cancellation races, startup cleanup, health cache/time bounds и artifact signatures.

`mvn -Preal-browser verify` включает Failsafe `*E2EIT`: local `HttpServer`, random-port Spring app, MHTML/PDF/PPTX endpoints, serialized delayed marker и PPTX pixel proof, HEAD-200/GET redirect chain, real GET 404/500 и late navigation, blocked private subresource, signatures/headers и temporary-profile cleanup. Profile ничего не скачивает и проверяет совпадение major browser/driver. Локальный opt-in явно skips при отсутствии пары; CI задаёт `-Docapp.e2e.failClosed=true` или `OCAPP_E2E_FAIL_CLOSED=true`, чтобы отсутствие/несовместимость завершали Failsafe ошибкой.

JaCoCo bundle gates: instruction/line ≥90%, branch ≥70%, method/class =100%.

Process supervisor управляет только дочерними процессами того же OS user; container PID/cgroup policy остаётся рекомендуемым deployment boundary на случай запрета host-ом `ProcessHandle.destroy/destroyForcibly`.


## M5 graphics export configuration

Inline SVG is accepted only through a fail-closed element/attribute allowlist; active elements, event handlers, CSS/style and URL/external references are rejected to localized screenshots. Canvas is exported locally as PNG, and ECharts as configured `png`/`svg`; no page data is sent to an external conversion service. Defaults: scale `2.0` (range `1.0..4.0`), background `transparent` or hex color, format `png`, `16,000,000` pixels and `8,388,608` decoded bytes per graphic. Pixel checks precede temporary Canvas/ECharts allocations; encoded length is checked before Java decode and POI embedding.
