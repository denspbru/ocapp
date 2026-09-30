# Системные требования и техническая спецификация

## Платформа

- JRE: Java 21+ (сборка компилируется с `release=21`).
- Сборка: Maven 3.9+; итоговый `target/ocapp-1.0.0-SNAPSHOT.jar`.
- Web stack: Spring Boot 3.5.6, Undertow (Tomcat исключён), Jakarta Validation, Actuator.
- Rendering: установленный в среде Google Chrome/Chromium, Selenium 4.49, Chrome DevTools Protocol.
- Presentation: Apache POI 5.4.1.
- Chrome/Chromium и ChromeDriver не включаются физически в executable JAR. Deployment image/host должен предоставить совместимый браузер, разрешить его дочерний запуск и создание защищённого временного профиля.

## Запуск

```bash
java -jar target/ocapp-1.0.0-SNAPSHOT.jar
java -jar target/ocapp-1.0.0-SNAPSHOT.jar --server.port=8090
java -jar target/ocapp-1.0.0-SNAPSHOT.jar --config=/etc/ocapp/ocapp.properties
```

`--config` преобразуется в Spring `spring.config.additional-location` с абсолютным экранированным `file:` URI: встроенные defaults сохраняются, внешний файл их переопределяет, а параметры командной строки имеют наивысший приоритет. Пробелы и специальные символы пути безопасно кодируются; пустой или синтаксически неверный `--config=` завершает запуск ошибкой.

## Архитектура

`ConversionController` отвечает только за HTTP-контракт. `ConversionService` оркестрирует URL policy, redirect preflight, semaphore/executor timeout и naming; полный timeout включает preflight, а permit удерживается до фактического выхода worker и browser cleanup. `UrlSecurityPolicy` отделяет синтаксис/host/DNS-политику. `SecureRedirectResolver` выполняет HEAD без автоматического follow и валидирует каждый навигационный `Location`. `PageRenderer` — заменяемый порт; `ChromePageRenderer` — production adapter и через Selenium DevTools перехватывает фактические browser requests (главный документ, redirects и subresources), проверяя URL/DNS перед продолжением. `BrowserSession` владеет WebDriver и временным профилем. `ApiExceptionHandler` формирует единый Problem JSON; `CorrelationIdFilter` создаёт MDC/request ID, который переносится в conversion worker.

## Конфигурация

Все duration используют Spring Boot duration syntax (`100ms`, `15s`, `2m`). Основные ключи:

- `server.port=8088`.
- `converter.browser.binary`, `driver-path`, `headless`, `extra-arguments`. Если оба пути пусты, Selenium Manager обнаруживает установленный Chrome/Chromium и разрешает подходящий driver; в production рекомендуется явно закрепить совместимые binary и driver в deployment image и указать их пути.
- `converter.browser.page-load-timeout`, `script-timeout`, `readiness-timeout`, `settle-delay`.
- `converter.browser.viewport-width`, `viewport-height`, `device-scale-factor`.
- `converter.security.allowed-hosts`, `denied-hosts`, `allow-private-addresses`, `max-redirects`, `preflight-timeout`.
- `converter.limits.max-concurrent`, `acquire-timeout`, `operation-timeout`, `max-output-bytes`.
- `converter.pdf.landscape`, `paper-width-inches`, `paper-height-inches`, `margin-inches`.
- `converter.pptx.slide-width-inches`, `slide-height-inches`, `max-slides`.

Host patterns are exact names or wildcard suffixes such as `*.example.org`. Deny has precedence. `allow-private-addresses=true` is intended only for isolated trusted environments.

## Безопасность

URL canonicalized through `URI`; HTTP(S)-only, no user-info. All DNS answers are rejected if any is unsafe. Redirects are no-follow preflighted and validated before the next preflight connection. Chrome uses a fresh profile, blocks literal localhost hostnames, intercepts every HTTP(S) browser request through DevTools, repeats URL/DNS policy for navigation/redirects/subresources, and revalidates the final top-level URL before and after capture. Reserved Chrome arguments that could replace the profile, proxy or resolver policy are rejected. No inbound Authorization/Cookie is forwarded deliberately.

Application-level DNS checks and DevTools interception cannot cryptographically bind Chromium's eventual socket to the exact IP that Java validated; DNS rebinding/TOCTOU and browser implementation bypasses remain possible. Therefore production egress deny-by-default (network namespace/container firewall) or a validating forward proxy is still a required second boundary. Application checks are defense-in-depth, not a substitute for network isolation.

## Эксплуатация

- `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
- Browser health verifies an explicitly configured binary without launching a process. Пустые пути не означают embedded browser: Selenium Manager обнаруживает установленный в ОС Chrome/Chromium и разрешает driver при первой конвертации; его сетевые/cache-требования зависят от окружения Selenium. Для offline production оба совместимых артефакта заранее устанавливаются и пути задаются явно.
- Console logs contain operation, safe scheme+host origin, duration, size, result/error and correlation ID; query/fragment and payload are omitted.
- Each conversion gets a separate Chromium profile and process. Size CPU/RAM accordingly; start with `max-concurrent=2`.
- Graceful shutdown is enabled. Stuck tasks are interrupted and browser session cleanup executes when Selenium returns.

## Тестирование

Unit/integration tests replace DNS, HTTP redirect client, renderer and conversion port; no external network is needed. Они также проверяют Selenium/DevTools call flow на mocks, permit release после timeout, MDC propagation, recursive cleanup и сигнатуры MHTML/PDF/PPTX. JaCoCo gate: instruction ≥90%, branch ≥70%, line ≥90%, method =100%, class =100%; bootstrap `main` покрывается тестом со статически подменённым `SpringApplication.run`, без фиктивного исключения из отчёта. Browser smoke may use a loopback fixture only when `allow-private-addresses=true` in a dedicated test process. Production defaults intentionally reject loopback.
