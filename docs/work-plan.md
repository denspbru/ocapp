# План работ

## Выполненный implementation pass

- [x] Изучить исходное описание, git state/history и сохранить пользовательское удаление корневого `BUSINESS_REQUIREMENTS.md`.
- [x] Зафиксировать бизнес-требования, системные требования и план в `docs/`.
- [x] Создать Maven/Java 21/Spring Boot проект, исключить Tomcat, подключить Undertow.
- [x] Реализовать `POST /MakeSnapshot`, `/MakePDF`, `/MakePPTX` и единый JSON request contract.
- [x] Реализовать MHTML/PDF через Chrome DevTools и PPTX через полноразмерный screenshot + Apache POI.
- [x] Реализовать встроенную и внешнюю properties-конфигурацию (`--config`).
- [x] Разделить HTTP, orchestration, security, browser и document generation на тестируемые порты/адаптеры.
- [x] Реализовать URL/DNS/redirect policy, correlation ID, безопасные ошибки/логи/filename.
- [x] Реализовать concurrency gate, operation timeout, size limit, browser/profile cleanup и health endpoints.
- [x] Добавить unit/integration tests без внешней сети.
- [x] Выполнить `mvn test` и исправить ошибки до зелёного результата.
- [x] Выполнить `mvn package` и проверить executable JAR/Undertow на свободном порту.
- [x] Выполнить API smoke для health, validation, correlation и SSRF rejection.
- [x] Выполнить локальный browser smoke MHTML/PDF/PPTX через Chrome и loopback fixture; сигнатуры и динамический DOM подтверждены.
- [x] Провести итоговый review git diff и перечислить созданные/изменённые файлы.

## Независимый review/hardening pass

- [x] Исправить `--config` для абсолютного escaped file URI и проверить путь с пробелом/CLI priority на собранном JAR.
- [x] Перенести preflight+render под полный operation timeout и удерживать concurrency permit до фактического завершения worker/cleanup.
- [x] Добавить DevTools interception и URL/DNS validation фактической browser navigation, redirects и HTTP(S)-subresources; отклонять Chrome args, обходящие isolation/network policy.
- [x] Исправить MDC propagation и исключить чувствительные exception messages/stack traces из обычных error logs.
- [x] Добавить строгую request validation, unknown-field rejection и защиту `Content-Disposition` filename.
- [x] Усилить recursive browser profile cleanup, duration validation и проверку MHTML/PDF/PPTX signatures/containers.
- [x] Обновить Selenium до 4.49 для установленного Chrome/CDP 154.
- [x] Расширить offline test suite и включить JaCoCo gate 90/70/90/100/100 для instruction/branch/line/method/class; покрыть bootstrap `main` реальным тестом вызова со статически подменённым Spring launcher.
- [x] Повторно проверить Undertow-only dependencies, `mvn test`, `mvn package`, `mvn verify`, startup/API/browser smoke всех трёх форматов.

## Production hardening после базовой поставки

1. Развернуть сервис в отдельном network namespace/container с egress deny-by-default либо обязательным validating proxy, чтобы подресурсы Chromium не могли обращаться к внутренним сетям.
2. Установить и зафиксировать совместимые версии Chromium/ChromeDriver в образе вместе с набором шрифтов/locale/timezone: браузер и driver не упаковываются внутрь JAR.
3. Провести нагрузочное тестирование и подобрать concurrency/timeouts/output limits под RAM/CPU.
4. Подключить аутентификацию/API gateway, TLS termination и rate limiting среды.
5. Добавить метрики Micrometer для форматов, длительности, ошибок и занятости без URL labels.
6. Проверить MHTML/PDF/PPTX на целевых эталонных программах и корпоративных dynamic/ECharts fixtures.
