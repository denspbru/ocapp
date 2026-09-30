# OCApp

OCApp — REST-сервис для сохранения динамических веб-страниц в MHTML, PDF или PPTX. Сервис загружает страницу в Chrome/Chromium, дожидается её готовности, формирует файл и возвращает его непосредственно в HTTP-ответе. Результаты на сервере не сохраняются.

Приложение предназначено для headless-запуска на серверной инфраструктуре и поставляется как исполняемый JAR.

## Возможности и форматы

| API | Результат | MIME type |
|---|---|---|
| `POST /MakeSnapshot` | MHTML (`.mhtml`) | `application/x-mimearchive` |
| `POST /MakePDF` | PDF (`.pdf`) | `application/pdf` |
| `POST /MakePPTX` | PPTX (`.pptx`) | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |

- Страница исполняется в Chrome/Chromium с JavaScript, Canvas/SVG и загружаемыми ресурсами.
- MHTML и PDF формируются через Chrome DevTools Protocol.
- PPTX собирается Apache POI из полноразмерного снимка страницы; длинная страница разбивается на несколько слайдов.
- Для каждого запроса создаются отдельные процесс Chromium и временный профиль.
- Поддерживаются ограничения конкурентности, времени выполнения, числа слайдов и размера результата.
- URL, перенаправления и HTTP(S)-подресурсы проходят проверки, снижающие риск SSRF.
- Каждый ответ получает correlation ID для сопоставления с консольными журналами.

## Стек и архитектура

- Java 21, Spring Boot 3.5.6 и встроенный Undertow;
- Selenium 4.49.0 и Chrome DevTools Protocol;
- Apache POI 5.4.1 для PPTX;
- Jakarta Validation и Spring Boot Actuator;
- Maven и JaCoCo.

HTTP-контракт находится в слое `api`. `ConversionService` управляет проверкой URL, предварительным обходом перенаправлений, лимитами конкурентности и полным тайм-аутом операции. Пакет `security` реализует URL/DNS/redirect policy. Интерфейс `PageRenderer` отделяет orchestration от адаптера `ChromePageRenderer`; браузерная сессия отвечает за WebDriver и очистку временного профиля. Ошибки приводятся к единому Problem JSON, а correlation ID переносится в worker конвертации.

## Системные требования

- JDK/JRE 21 или новее;
- Maven 3.9+ для сборки из исходного кода;
- установленный Google Chrome или Chromium;
- совместимый ChromeDriver либо возможность Selenium Manager обнаружить браузер и разрешить подходящий driver.

Chrome/Chromium и ChromeDriver **не встроены физически в JAR**. При пустых `converter.browser.binary` и `converter.browser.driver-path` Selenium Manager ищет установленный браузер и управляет driver; его сетевые и cache-требования зависят от окружения Selenium. Для воспроизводимого или offline production-развёртывания установите совместимые версии браузера и ChromeDriver заранее и явно задайте пути к ним.

Среда исполнения должна разрешать запуск дочернего процесса браузера и создание временного профиля. Для корректного отображения контента также нужны используемые страницей шрифты, locale и timezone.

## Сборка и тестирование

```bash
mvn test
mvn package
```

Исполняемый файл создаётся как `target/ocapp-1.0.0-SNAPSHOT.jar`. Для запуска JaCoCo-проверок покрытия, заданных в `pom.xml`, используйте:

```bash
mvn verify
```

Основной набор тестов не требует внешней сети или браузера: внешние компоненты заменяются тестовыми реализациями и mocks.

## Запуск

С конфигурацией из `src/main/resources/application.properties` и портом `8088`:

```bash
java -jar target/ocapp-1.0.0-SNAPSHOT.jar
```

На другом порту:

```bash
java -jar target/ocapp-1.0.0-SNAPSHOT.jar --server.port=8090
```

С дополнительным внешним properties-файлом:

```bash
java -jar target/ocapp-1.0.0-SNAPSHOT.jar --config=/etc/ocapp/ocapp.properties
```

`--config` преобразуется приложением в `spring.config.additional-location`: встроенные значения сохраняются, внешний файл их переопределяет, а параметры командной строки имеют более высокий приоритет. Путь может содержать пробелы и преобразуется в абсолютный экранированный `file:` URI. Пустое или синтаксически неверное значение `--config` завершает запуск ошибкой.

## Конфигурация

Длительности задаются в синтаксисе Spring Boot, например `100ms`, `15s` или `2m`. Значения по умолчанию находятся в [`src/main/resources/application.properties`](src/main/resources/application.properties).

| Настройка | По умолчанию | Назначение |
|---|---:|---|
| `server.port` | `8088` | HTTP-порт сервиса |
| `converter.browser.binary` | пусто | Путь к Chrome/Chromium; пустое значение включает обнаружение Selenium Manager |
| `converter.browser.driver-path` | пусто | Путь к ChromeDriver; пустое значение делегирует выбор Selenium Manager |
| `converter.browser.headless` | `true` | Headless-режим браузера |
| `converter.browser.extra-arguments` | пусто | Дополнительные аргументы Chrome; аргументы, способные переопределить профиль, proxy или resolver policy, запрещены |
| `converter.browser.page-load-timeout` | `30s` | Тайм-аут загрузки страницы |
| `converter.browser.script-timeout` | `15s` | Тайм-аут выполнения скриптов |
| `converter.browser.readiness-timeout` | `15s` | Ожидание готовности страницы |
| `converter.browser.settle-delay` | `500ms` | Дополнительная задержка стабилизации |
| `converter.browser.viewport-width` / `viewport-height` | `1440` / `900` | Размер viewport |
| `converter.browser.device-scale-factor` | `1.0` | Масштаб устройства |
| `converter.browser.fail-startup-if-unavailable` | `false` | Завершать startup, если явно настроенный browser binary недоступен |
| `converter.security.allowed-hosts` | пусто | Разрешённые hosts; пустой список не ограничивает публичные hosts |
| `converter.security.denied-hosts` | пусто | Запрещённые hosts; deny имеет приоритет |
| `converter.security.allow-private-addresses` | `false` | Разрешить private/loopback и другие локальные адреса |
| `converter.security.max-redirects` | `5` | Максимум HTTP-перенаправлений |
| `converter.security.preflight-timeout` | `10s` | Тайм-аут предварительной проверки цели |
| `converter.limits.max-concurrent` | `2` | Максимум одновременных конвертаций |
| `converter.limits.acquire-timeout` | `100ms` | Ожидание свободной ёмкости |
| `converter.limits.operation-timeout` | `90s` | Полный тайм-аут конвертации, включая preflight |
| `converter.limits.max-output-bytes` | `52428800` | Максимальный размер результата |
| `converter.pdf.landscape` | `false` | Альбомная ориентация PDF |
| `converter.pdf.paper-width-inches` / `paper-height-inches` | `8.27` / `11.69` | Размер страницы PDF |
| `converter.pdf.margin-inches` | `0.4` | Поля PDF |
| `converter.pptx.slide-width-inches` / `slide-height-inches` | `13.333` / `7.5` | Размер слайда PPTX |
| `converter.pptx.max-slides` | `100` | Максимальное число слайдов |

Host patterns задаются точным именем или wildcard-суффиксом, например `*.example.org`. Списочные properties Spring Boot можно передавать как разделённые запятыми значения.

## Использование API

Все три операции принимают `Content-Type: application/json` и объект ровно с одним полем:

```json
{
  "url": "https://example.org/report"
}
```

`url` обязателен, не может быть пустым и ограничен 4096 символами. Поддерживаются только абсолютные HTTP/HTTPS URL без user-info. Неизвестные JSON-поля отклоняются.

### MHTML

```bash
curl --fail-with-body \
  -X POST http://localhost:8088/MakeSnapshot \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: snapshot-example-1' \
  -d '{"url":"https://example.org/"}' \
  -o page.mhtml
```

### PDF

```bash
curl --fail-with-body \
  -X POST http://localhost:8088/MakePDF \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/"}' \
  -o page.pdf
```

### PPTX

```bash
curl --fail-with-body \
  -X POST http://localhost:8088/MakePPTX \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/"}' \
  -o page.pptx
```

Успешный ответ имеет статус `200`, бинарное тело, точный `Content-Length`, `X-Correlation-ID` и `Content-Disposition: attachment; filename="<host>-<UTC timestamp>.<extension>"`. `Content-Type` соответствует таблице форматов выше.

Входной `X-Correlation-ID` используется, только если содержит от 1 до 64 символов из набора `A-Z`, `a-z`, `0-9`, `.`, `_`, `-`; иначе сервис создаёт UUID.

## Ошибки

Ошибки возвращаются с `Content-Type: application/problem+json` и полями:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "code": "INVALID_REQUEST",
  "detail": "Request body must contain a non-empty URL string",
  "correlationId": "e6f2d8ef-1a17-4f28-9d41-8a5bde3a4185",
  "timestamp": "2026-09-30T06:00:00Z"
}
```

Основные статусы:

| HTTP | Условие |
|---:|---|
| `400` | Неверный JSON, запрос, URL или схема |
| `403` | URL либо redirect заблокирован SSRF-политикой |
| `413` | Результат или число слайдов превышает настроенный предел |
| `422` | Цель недоступна, redirect некорректен или страницу/файл нельзя обработать |
| `429` | Исчерпана конкурентная ёмкость; возвращается `Retry-After: 1` |
| `500` | Непредвиденная внутренняя ошибка |
| `503` | Браузер или executor недоступен |
| `504` | Тайм-аут или прерывание конвертации |

Поле `code` содержит стабильный машинный код конкретной причины, а `correlationId` совпадает с заголовком `X-Correlation-ID` ответа и записью в журнале. Ответы не включают stack trace, локальные пути, содержимое документов или секреты.

## Health endpoint

```bash
curl http://localhost:8088/actuator/health
curl http://localhost:8088/actuator/health/liveness
curl http://localhost:8088/actuator/health/readiness
```

Подробности health скрыты (`show-details=never`). Browser health проверяет существование явно заданного executable, но не запускает браузер. При пустом `converter.browser.binary` health сообщает режим Selenium Manager как доступный; фактический запуск браузера впервые проверяется при конвертации.

## Ограничения и безопасность

- Результат зависит от версии Chromium, шрифтов, CSP, доступности ресурсов, анимаций и поведения страницы.
- Сервис не обходит CAPTCHA, DRM и антибот-защиту, не передаёт клиентские cookies/`Authorization` и не предназначен для аутентифицированных страниц.
- PPTX содержит изображения страницы, а не редактируемые DOM-элементы.
- По умолчанию запрещены URL с credentials, схемы кроме HTTP/HTTPS, loopback, private/site-local, link-local, multicast, unspecified, carrier-grade NAT и metadata-сети. Каждый DNS-ответ должен быть безопасен.
- Перенаправления проверяются без автоматического follow; browser navigation, redirects и HTTP(S)-подресурсы дополнительно перехватываются и проверяются через DevTools.
- Эти проверки не связывают криптографически Java DNS-ответ с IP, к которому в итоге подключится Chromium, поэтому полностью не устраняют DNS rebinding/TOCTOU и возможные обходы браузерного уровня.
- В production требуется второй рубеж: egress deny-by-default в отдельном container/network namespace/firewall либо обязательный validating proxy. Не полагайтесь на application-level SSRF policy как на единственную защиту.
- `converter.security.allow-private-addresses=true` следует использовать только в изолированной доверенной среде.
- Перед публикацией сервиса добавьте TLS termination, аутентификацию, rate limiting и ресурсные ограничения среды исполнения.

## Документация

- [`docs/business-requirements.md`](docs/business-requirements.md) — бизнес-требования и HTTP-контракт;
- [`docs/system-requirements.md`](docs/system-requirements.md) — техническая спецификация, архитектура и эксплуатация;
- [`docs/description.md`](docs/description.md) — исходное описание задачи;
- [`docs/work-plan.md`](docs/work-plan.md) — выполненные этапы и дальнейший production hardening.

## Лицензия

Проект распространяется по лицензии [Apache License 2.0](LICENSE). Полный текст лицензии находится в файле `LICENSE`.
