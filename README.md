# Wisprail

JavaFX-клиент для выборочного IPv4 VPN: OpenVPN и VLESS TCP TLS / Reality, один активный
профиль, корпоративный split DNS. JavaFX работает от пользователя, Java-служба выполняет
привилегированные операции, комплектный sing-box 1.14.2 обслуживает протоколы и TUN.

Требования: [спецификация](sing-box_javafx_vpn_solution.md) и
[дизайн](vpn_javafx_design_spec.md). Подтверждённые проверки и оставшиеся ограничения:
[ACCEPTANCE.md](ACCEPTANCE.md). Это разработческая версия; приёмка реального системного
VPN и установщиков ещё не завершена.

## Сборка и запуск интерфейса

Нужны JDK 21 (для упаковки 21.0.11) и Python 3 для получения движка. Maven 3.9.11 загружает wrapper. JavaFX 21.0.11
и остальные зависимости закреплены в POM. При первом запуске нужны GitHub и Maven Central.

Windows x64, PowerShell из корня:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
python deploy/fetch-engine.py --platform windows-amd64
.\mvnw.cmd -B -ntp verify
& "$env:JAVA_HOME\bin\java.exe" --module-path frontend/target/javafx --add-modules javafx.controls -cp 'frontend/target/wisprail-desktop-0.1.0-SNAPSHOT.jar;frontend/target/lib/*' app.wisprail.ui.DesktopLauncher
```

macOS 13+, JDK той же архитектуры, что и приложение:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
python3 deploy/fetch-engine.py --platform darwin-arm64  # darwin-amd64 для Intel
sh ./mvnw -B -ntp -Dwisprail.engine="$PWD/deploy/target/engine/sing-box" verify
"$JAVA_HOME/bin/java" --module-path frontend/target/javafx --add-modules javafx.controls \
  -cp 'frontend/target/wisprail-desktop-0.1.0-SNAPSHOT.jar:frontend/target/lib/*' \
  app.wisprail.ui.DesktopLauncher
```

Без установленной службы можно редактировать и импортировать профили. Подключение и
проверка конфигурации через службу становятся доступны после её установки. Запуск JavaFX
от администратора не заменяет установку службы. Интеграционные тесты используют локальные
серверы с временными сертификатами и не изменяют TUN, маршруты и системный DNS.
При отсутствии движка эти тесты отмечаются как пропущенные; такой результат не является
полной проверкой. UI-тестам нужна графическая сессия.

## ZIP-дистрибутивы одним запуском

Общая конфигурация `.run/Build Distributions.run.xml`: **Build Distributions**, Shell Script plugin.
Она запускает из корня проекта одну команду:

```powershell
powershell.exe -NoProfile -File deploy/build-distributions.ps1
```

Можно выполнить тот же файл непосредственно в уже настроенном PowerShell:

```powershell
& ./deploy/build-distributions.ps1
```

Нужны Git, Python 3, Maven Wrapper, JDK **21.0.11 x64** в `JAVA_HOME`, интернет для зависимостей
и движка. Проверено с Microsoft JDK: у оригиналов DLL/EXE должны быть действительные подписи
поставщика. Сборка после `jlink` восстанавливает эти неизменённые файлы из **того же JDK**,
проверяя PE-архитектуру, подписи и SHA-256. Личный сертификат для обычного ZIP не требуется.

Windows PowerShell 5.1 в этой среде имеет effective policy `Restricted` и отклоняет `-File`
до выполнения скрипта. Политика не изменялась. Сам скрипт проверен в уже настроенном
PowerShell 7 с `RemoteSigned`; конфигурация IDEA сохраняет согласованную команду `powershell.exe`.
Для её выполнения нужен разрешённый организацией запуск локальных PowerShell-скриптов.
После указания пользователя IDEA больше не запускалась: проверялся непосредственно скрипт.

По умолчанию оба Mac ZIP собираются **локально на Windows через Docker Desktop с Linux engine**.
Mac, macOS VM, Xcode на Windows, Apple ID и личный сертификат для этой сборки не требуются.
Контейнер получает закреплённый SDK 14.5 непосредственно с Apple CDN, Microsoft JDK 21.0.11,
OSXCross и `rcodesign`; URL и SHA-256 находятся в `deploy/macos/cross/inputs.json`.
Первый запуск скачивает инструменты и создаёт локальный образ; далее Docker повторно использует
слои и Maven volume `wisprail-macos-maven`. Нужны интернет и свободное место для образа,
двух runtime и промежуточных пакетов (ориентир — 10 ГБ).

В Linux выполняется `verify` с Linux JavaFX и локальным sing-box. Затем Maven собирает
каждый целевой пакет с Mac JavaFX; исполнение Mac-тестов на Linux невозможно и не заявляется.
Linux `jlink` читает Mac JMOD того же JDK, launcher берётся из Mac `jdk.jpackage.jmod`,
`services.m` компилируется OSXCross для arm64/x86_64 с minimum macOS 13. Создаются привычные
`.app` и PKG с существующими preinstall/postinstall, без изменения протокола установки службы.
Все Mach-O проверяются по архитектуре, хешам страниц CodeDirectory и запечатанным ресурсам;
это проверка целостности ad-hoc подписи, **не** проверка доверия Apple/Gatekeeper.
`rcodesign 0.29 verify` ошибочно требует CMS у ad-hoc; отдельный проверяющий скрипт
проверяется на испорченных бинарниках, Info.plist, ресурсах и ссылках. PKG распаковывается для сверки
содержимого, прав и владельца, ZIP — для проверки файлов, executable bits и symlinks.
Это статические проверки пакета: запуск UI, Installer, Service Management и VPN на настоящей
macOS остаются отдельной приёмкой. Контейнер не получает Docker socket, домашний каталог,
ключи или исходное рабочее дерево; ему передаётся только снимок исходников и каталог результата.
Удалённый Docker endpoint отклоняется. Образ с SDK используется только локально и не публикуется.

Существующий путь сборки на Mac сохранён с явным выбором режима и разрешёнными SSH aliases:

```powershell
$env:WISPRAIL_MAC_ARM64_HOST = 'authorized-mac-arm64-alias'
$env:WISPRAIL_MAC_X64_HOST = 'authorized-mac-x64-alias'
& ./deploy/build-distributions.ps1 -MacBuildMode ssh
```

На каждом Mac нужны macOS 13+, JDK 21.0.11 нужной архитектуры, Python 3 и Xcode Command Line
Tools (`clang`, `lipo`, `codesign`, `pkgbuild`, `ditto`). SSH работает в BatchMode с проверкой
известного ключа. Пароли, сертификаты и пользовательские абсолютные пути не записываются в XML.
Обычный Mac ZIP использует ad-hoc signing без личного сертификата; это **не** Developer ID
и **не** notarization. Gatekeeper и корпоративные ограничения не отключаются.

Скрипт фиксирует текущие сборочные файлы, включая untracked и незакоммиченные изменения,
проверяет их хеши до/после копирования и собирает в чистом каталоге снимка через существующие
платформенные скрипты. Git/IDE-данные, `.env`, результаты `target` не включаются.
Все платформы получают один source manifest, SHA-256 снимка и версию корневого POM.
Снимок и промежуточные результаты сохраняются в `deploy/target/builds/<buildId>`.

Результаты: **`deploy/target/dist`**:

- `wisprail-<version>-windows-x64.zip`
- `wisprail-<version>-macos-arm64.zip`
- `wisprail-<version>-macos-x64.zip`
- соответствующие `.zip.sha256` и `build-results.json` со статусом каждой платформы.

Внутри ZIP — приложение, собственный Java runtime, JavaFX/JNA, sing-box 1.14.2, средства
установки системного компонента, лицензии и `build-info.json`. Mac ZIP сохраняет Unix-права
и символические ссылки: через `zip -y` в Linux либо `ditto` на Mac. Системная Java не нужна.
При любой обязательной недоступной/ошибочной платформе общий exit code **1**. `PARTIAL`
и Windows `BUILT` не означают готовность Mac. Старые архивы текущей версии удаляются перед
платформенными сборками; учитывайте только результаты текущего `build-results.json`.
Сборка не устанавливает службу, не подключает VPN и не меняет системную сеть.
Логи контейнера и результаты Linux-тестов: `deploy/target/builds/<buildId>/macos-<arch>`.
`BUILT` означает, что ZIP создан и прошёл проверки упаковки; это не подтверждение запуска
на macOS. `build-info.json` явно содержит `macosLaunch: NOT_RUN` и `macosInstallation: NOT_RUN`
для кросс-сборки. Подпись `ad-hoc` не является Developer ID или notarization.

Изолированные проверки `deploy/macos/cross/test_package.py` выполняются автоматически
перед Maven в каждом контейнере сборки. Они компилируют небольшие реальные ARM/Intel Mach-O,
проверяют порчу подписи и содержимого, а также обратную распаковку PKG/ZIP.

## Пакеты и системный компонент

Платформенные команды остаются доступны отдельно:

```powershell
& ./deploy/package-windows.ps1
# Дополнительный режим подписанного MSI; нужны WiX 3 и Windows SDK signtool:
& ./deploy/package-windows.ps1 -Type msi -Release -SigningThumbprint '<thumbprint>'
```

Для службы Windows образ должен находиться в `C:\Program Files\Wisprail` либо быть установлен
через MSI. В приложении: «Настройки → Компоненты и данные → Установить / разрешить системный
компонент». UAC относится только к установщику, UI остаётся обычным пользовательским процессом.
Установщик защищает файлы, закрепляет SID владельца и создаёт Windows Service `Wisprail`.
UI ждёт exit code установщика, затем отдельно подтверждает доступность службы через IPC.
Неподписанные служебные скрипты могут не соответствовать корпоративной политике; проверка
подписей и политика выполнения не обходятся. ZIP не заменяет штатную установку службы.

macOS, обычный локальный ZIP и PKG:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21.0.11 -a arm64)
sh deploy/package-macos.sh --architecture arm64  # x64 для Intel/JDK x86_64
```

Подписанный выпуск — отдельный, явно включаемый режим:

```sh
export WISPRAIL_APP_SIGN_IDENTITY='Developer ID Application: ...'
export WISPRAIL_INSTALLER_SIGN_IDENTITY='Developer ID Installer: ...'
export WISPRAIL_NOTARY_PROFILE='wisprail-notary'
sh deploy/package-macos.sh --architecture arm64 --release
```

В режиме release подписываются приложение и native-библиотеки, PKG отправляется на
notarization и stapling. Метаданные различают подпись приложения и notarization установщика.
JavaFX и JNA извлекаются и подписываются отдельно, library validation не отключается.
Устанавливать PKG следует в активной сессии будущего пользователя. Разрешение фонового
компонента выполняется штатно в приложении/ОС. Автозапуск UI не подключает VPN автоматически.

Перед удалением отключите VPN, автозапуск и системный компонент через настройки.
MSI проверяет очистку при удалении. Профили и секреты сохраняются; конкретный профиль вместе
с секретом удаляется из приложения. Чужие NRPT/DNS-правила вручную не удаляются.

## Повторение проверки

```powershell
& ./deploy/verify-windows-zip.ps1 -Archive ./deploy/target/dist/wisprail-0.1.0-SNAPSHOT-windows-x64.zip
```

Этот отдельный тест запускается **обычным пользователем** в интерактивной Windows-сессии.
Он распаковывает ZIP в новый каталог, убирает системную Java из окружения дочернего процесса,
использует отдельные пользовательские данные и native-кеш, проверяет загруженные библиотеки,
подписи runtime, окно, штатный выход и события Code Integrity. Служба не устанавливается.
Доказательства: `deploy/target/acceptance/windows-<timestamp>` и `latest-windows.json`.

`DesignScenariosTest` создаёт настоящие JavaFX-снимки 41 состояния в `frontend/target/visual/javafx`.
Для эталона и сравнения из корня (нужны Node.js + Playwright + Chrome, Python + Pillow):

```sh
node frontend/src/test/visual/capture-reference.cjs
python frontend/src/test/visual/compare.py
```

HTML-отчёт: `frontend/target/visual/comparison/index.html`; есть 41 наложение и 41 diff.
Тестовые состояния не доступны в рабочем режиме. Нативная клавиатура проверяется отдельно
`DesktopKeyboardTest` с `-Dwisprail.nativeUi=true` и `-Dglass.win.uiScale=1.0`, `1.25`, `1.5`.
Он отказывается отправлять клавиши чужому окну. Полный список результатов и NOT_RUN:
[ACCEPTANCE.md](ACCEPTANCE.md). Ни render-тест, ни сборка не заменяют системную/сетевую приёмку.

## Профили, маршруты и DNS

Сети задаются в каноническом IPv4 CIDR. `/0` и совокупный полный охват IPv4 запрещены.
При необходимости редактор предлагает нормализацию с подтверждением. DNS вне сетей требует
явного добавления `/32`. До разрыва активного VPN служба проверяет кандидат настоящим
`sing-box check`; подтверждение переключения связано с кандидатом и ревизией состояния.

OpenVPN использует внутренний стек и `route_no_pull`. Импорт `.ovpn` поддерживает один
`remote`, TCP/UDP, `tun`, сертификаты/ключи inline или из каталога импорта, `auth-user-pass`
без файла пароля, `cipher`, `auth`, `verify-x509-name ... name`, `tls-auth` / `tls-crypt` /
`tls-crypt-v2`, IPv4 `route`, DNS и доменные `dhcp-option`. Неизвестные директивы, скрипты,
многосерверные конфигурации и неподдержанные варианты останавливают импорт. `redirect-gateway`
не применяется и явно указан в предпросмотре. Неполный импорт открывается как черновик.

Импорт VLESS принимает `security`, `type=tcp`, `sni`, `pbk`, `sid`, `fp`, `flow`,
`encryption=none`. UUID хранится как секрет. Экспорт собственного JSON и копирование
исключают секреты, ссылки на секреты и OpenVPN username; для подключения копии данные
доступа вводятся заново. Экспорт не меняет права выбранного каталога.

OpenVPN подтверждается событиями готовности endpoint. VLESS — ответом корпоративного DNS
или заданного HTTPS-ресурса внутри сетей; если указан HTTPS, проверяется он. TLS-сертификаты
не игнорируются. В HTTPS-проверке используется доверенное хранилище комплектного Java runtime.
Периодическая проверка выполняется раз в 15 секунд. Потеря готовности останавливает профиль
с ошибкой; автоматического переподключения и возврата к другому профилю нет.

DNS-правила ОС направляют только заданные домены к входу DNS в TUN; корпоративный DNS доступен
через VPN. Публичного DNS fallback и глобального перехвата нет. IPv6 и собственный DoH
приложений находятся вне гарантии. `.local`, DNS-кеш, политики организации, соседний VPN,
смена сети и сон требуют проверки в целевом окружении. Kill switch не реализуется.

## Данные и владельцы кода

| Данные | Windows | macOS |
|---|---|---|
| Профили и настройки UI | `%LOCALAPPDATA%\Wisprail` | `~/Library/Application Support/Wisprail` |
| Секреты | DPAPI текущего пользователя, подкаталог `secrets` | Keychain, service `app.wisprail.profiles` |
| Состояние службы | `%ProgramData%\Wisprail\service` | `/Library/Application Support/Wisprail/service` |
| Журнал службы | `journal/events.jsonl`, одна ротация | то же относительно каталога службы |
| Конфигурации движка | защищённый `runtime/<session UUID>` | то же; удаляются после остановки/восстановления |

`backend/.../connection/ConnectionService` владеет переходами; `engine/SingboxRuntime` —
движком и восстановлением; `platform` — DNS и системными проверками; `agent` — проверенным
локальным IPC; `profile` — моделями, валидацией, импортом и атомарной записью;
`storage` — DPAPI/Keychain. `frontend` содержит страницы, общий редактор и CSS.

Служба принимает только типизированные команды, самостоятельно собирает конфигурацию и
не принимает пользовательские команды, исполняемые пути или готовый sing-box JSON.
Журнал содержит только сообщения приложения, без сырого вывода движка. Буфер — 2000 событий,
файл — около 2 МБ плюс одна ротация. Отчёт по умолчанию не содержит адресов и доменов;
их включение требует отдельной отметки в предпросмотре. Если удаление старого секрета
не удалось, сохранение/удаление профиля не выдаётся за откат: UI сообщает частичный результат.

Лицензии и состав поставки: [THIRD_PARTY_NOTICES.md](deploy/THIRD_PARTY_NOTICES.md).
