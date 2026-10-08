# Wisprail

JavaFX-клиент для выборочного IPv4 VPN: OpenVPN и VLESS TCP TLS / Reality, один активный
профиль, корпоративный split DNS. JavaFX работает от пользователя, Java-служба выполняет
привилегированные операции, комплектный sing-box обслуживает протоколы и TUN.

Платформы: Windows 10/11 x64, macOS 13+ Intel и Apple Silicon.
Требования: [спецификация](docs/specification.md), [дизайн](docs/design/specification.md),
[HTML-макет](docs/design/prototype.html). Структура и рабочие правила — в [AGENTS.md](AGENTS.md).

## Разработка

Нужны JDK 21, Python 3 и графическая пользовательская сессия для JavaFX-тестов.
Версии библиотек заданы в Maven POM; Maven 3.9.11 получает Wrapper.
В PowerShell из корня репозитория (путь JDK замените своим):

```powershell
$env:JAVA_HOME = 'C:\Users\vitalii\.jdks\ms-21.0.11'
python packaging/fetch-engine.py --platform windows-amd64
.\mvnw.cmd -B -ntp clean verify
& "$env:JAVA_HOME\bin\java.exe" -cp 'desktop/target/wisprail-desktop-0.1.0.jar;desktop/target/lib/*' app.wisprail.ui.Launcher
```

Если `python` — неработающий псевдоним Windows, используйте полный путь к Python.
Java 17 в системе заменять не нужно: `JAVA_HOME` выше действует только в текущем процессе.
Без комплектного движка интеграционные тесты пропускаются, что не является полной проверкой.
Исходный UI запускается без повышения прав; сетевые действия требуют установленного компонента.
Результаты тестов находятся в `core/target/` и `desktop/target/`, снимки UI —
в `desktop/target/visual/`.

## Дистрибутивы

Единая сборка всех трёх целей запускается на Windows с JDK **21.0.11 AMD64**, Python 3
и работающим Docker. Сборщик получает закреплённый WiX 3.14.1, создаёт снимки исходников,
проверяет движок и собирает Windows через jpackage, затем обе архитектуры macOS через OSXCross:

```powershell
.\packaging\build-distributions.ps1 -JavaHome $env:JAVA_HOME -Python python
```

Ключ `-WindowsOnly` ограничивает сборку Windows и не требует Docker.
Ключ `-ZipOnly` пропускает только Windows EXE. EXE, ZIP, снимки исходников, перечни файлов,
версии компонентов и `SHA256SUMS` находятся в `packaging/target/dist/`.
Собственные Windows EXE не подписываются личным сертификатом; подписи поставщиков
сохраняются. Системные ограничения запуска продолжают действовать. Установщик размещает
службу в Program Files и запускает её через SCM под LocalSystem; UI работает от пользователя.
Обновление сохраняет владельца службы, профили и секреты. Удаление прекращается, если
служба не подтвердила очистку собственных сетевых объектов.

Для macOS используется `packaging/macos/cross/Dockerfile`: OSXCross с официальным Apple SDK,
Linux JDK 21.0.11, macOS JDK 21.0.11 целевой архитектуры и rcodesign 0.29.0.
Проверяемые по SHA-256 инструменты получает отдельная команда; установка SDK и JDK в ОС
не выполняется. При работающем Docker из PowerShell:

```powershell
python packaging/fetch-tools.py --target macos
docker build -t wisprail-macos-cross:21.0.11 -f packaging/macos/cross/Dockerfile packaging/target/tools
docker run --rm -v "${PWD}:/workspace" wisprail-macos-cross:21.0.11 arm64 /opt/jdk-mac-arm64/Contents/Home
docker run --rm -v "${PWD}:/workspace" wisprail-macos-cross:21.0.11 amd64 /opt/jdk-mac-amd64/Contents/Home
```

Либо в уже подготовленном Linux-окружении из корня:

```sh
export JAVA_HOME=/opt/jdk-linux-21.0.11
export OSXCROSS_ROOT=/opt/osxcross
bash packaging/macos/cross/build.sh arm64 /opt/jdk-macos-arm64/Contents/Home
bash packaging/macos/cross/build.sh amd64 /opt/jdk-macos-amd64/Contents/Home
```

Обычная macOS-сборка использует ad-hoc подпись. Developer ID и notarization не выполняются.
На Linux проверяется побайтное совпадение пакета с копией, в которой заново вычислены
ad-hoc подписи и подписи ресурсов с тем же временем. Проверка средствами Apple `codesign`
относится к пока пропущенной системной приёмке macOS.
Регистрацией службы и автозапуском управляет SMAppService; требуемое разрешение выдаётся
в настройках macOS. Перед обновлением закройте приложение; установщик останавливает
службу, проверяет очистку и восстанавливает прежние настройки регистрации после замены.
Для удаления установленной в `/Applications` версии выполните:

```sh
sudo /Applications/Wisprail.app/Contents/Resources/uninstall.command
```

Команда удаляет приложение только после подтверждённой очистки службы и собственных
сетевых объектов. Профили и секреты в пользовательском хранилище сохраняются.

## Данные и проверка готовности

Профили и DPAPI-секреты Windows находятся в `%LOCALAPPDATA%\Wisprail`, журнал службы —
в `%PROGRAMDATA%\Wisprail\agent`. На macOS профили находятся
в `~/Library/Application Support/Wisprail`, секреты — в Keychain.
Импорт ограничен 1 МиБ; экспорт исключает секреты и сертификаты.

По согласованному решению системная приёмка Windows и macOS пока имеет статус **NOT_RUN**:
реальные TUN/NRPT/SystemConfiguration, установка/обновление/удаление на ОС, соседний VPN,
смена сети, сон и пробуждение. Сборка, тесты, изображения UI и проверка пакета подтверждают
только соответствующие свойства; они не означают «100% проверено» и не заменяют эту приёмку.
Нативные клавиатурные проверки требуют фактического фокуса тестового окна Windows.
Если окружение не доставляет ввод, результат отмечается `NOT_RUN` с причиной в
`desktop/target/visual/native-input.txt`; проверка обработчика события не заменяет нативный ввод.
