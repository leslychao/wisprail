# Сборка и установка дистрибутивов

Все команды выполняются из корня репозитория.

## ZIP-дистрибутивы одним запуском

Общая конфигурация `.run/Build Distributions.run.xml`: **Build Distributions**, Shell Script plugin.
Она запускает из корня проекта одну команду:

```powershell
powershell.exe -NoProfile -File packaging/build-distributions.ps1
```

Можно выполнить тот же файл непосредственно в уже настроенном PowerShell:

```powershell
& ./packaging/build-distributions.ps1
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
OSXCross и `rcodesign`; URL и SHA-256 находятся в `packaging/macos/cross/inputs.json`.
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
& ./packaging/build-distributions.ps1 -MacBuildMode ssh
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
Снимок и промежуточные результаты сохраняются в `packaging/target/builds/<buildId>`.

Результаты: **`packaging/target/dist`**:

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
Логи контейнера и результаты Linux-тестов: `packaging/target/builds/<buildId>/macos-<arch>`.
`BUILT` означает, что ZIP создан и прошёл проверки упаковки; это не подтверждение запуска
на macOS. `build-info.json` явно содержит `macosLaunch: NOT_RUN` и `macosInstallation: NOT_RUN`
для кросс-сборки. Подпись `ad-hoc` не является Developer ID или notarization.

Изолированные проверки `packaging/macos/cross/test_package.py` выполняются автоматически
перед Maven в каждом контейнере сборки. Они компилируют небольшие реальные ARM/Intel Mach-O,
проверяют порчу подписи и содержимого, а также обратную распаковку PKG/ZIP.

## Пакеты и системный компонент

Платформенные команды остаются доступны отдельно:

```powershell
& ./packaging/package-windows.ps1
# Дополнительный режим подписанного MSI; нужны WiX 3 и Windows SDK signtool:
& ./packaging/package-windows.ps1 -Type msi -Release -SigningThumbprint '<thumbprint>'
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
sh packaging/package-macos.sh --architecture arm64  # x64 для Intel/JDK x86_64
```

Подписанный выпуск — отдельный, явно включаемый режим:

```sh
export WISPRAIL_APP_SIGN_IDENTITY='Developer ID Application: ...'
export WISPRAIL_INSTALLER_SIGN_IDENTITY='Developer ID Installer: ...'
export WISPRAIL_NOTARY_PROFILE='wisprail-notary'
sh packaging/package-macos.sh --architecture arm64 --release
```

В режиме release подписываются приложение и native-библиотеки, PKG отправляется на
notarization и stapling. Метаданные различают подпись приложения и notarization установщика.
JavaFX и JNA извлекаются и подписываются отдельно, library validation не отключается.
Устанавливать PKG следует в активной сессии будущего пользователя. Разрешение фонового
компонента выполняется штатно в приложении/ОС. Автозапуск UI не подключает VPN автоматически.

Перед удалением отключите VPN, автозапуск и системный компонент через настройки.
MSI проверяет очистку при удалении. Профили и секреты сохраняются; конкретный профиль вместе
с секретом удаляется из приложения. Чужие NRPT/DNS-правила вручную не удаляются.

