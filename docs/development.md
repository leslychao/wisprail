# Разработка и проверка

Все команды выполняются из корня репозитория.

## Сборка и запуск интерфейса

Нужны JDK 21 (для упаковки 21.0.11) и Python 3 для получения движка. Maven 3.9.11 загружает wrapper. JavaFX 21.0.11
и остальные зависимости закреплены в POM. При первом запуске нужны GitHub и Maven Central.

Windows x64, PowerShell из корня:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
python packaging/fetch-engine.py --platform windows-amd64
.\mvnw.cmd -B -ntp verify
& "$env:JAVA_HOME\bin\java.exe" --module-path desktop/target/javafx --add-modules javafx.controls -cp 'desktop/target/wisprail-desktop-0.1.0-SNAPSHOT.jar;desktop/target/lib/*' app.wisprail.ui.DesktopLauncher
```

macOS 13+, JDK той же архитектуры, что и приложение:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
python3 packaging/fetch-engine.py --platform darwin-arm64  # darwin-amd64 для Intel
sh ./mvnw -B -ntp -Dwisprail.engine="$PWD/packaging/target/engine/sing-box" verify
"$JAVA_HOME/bin/java" --module-path desktop/target/javafx --add-modules javafx.controls \
  -cp 'desktop/target/wisprail-desktop-0.1.0-SNAPSHOT.jar:desktop/target/lib/*' \
  app.wisprail.ui.DesktopLauncher
```

Без установленной службы можно редактировать и импортировать профили. Подключение и
проверка конфигурации через службу становятся доступны после её установки. Запуск JavaFX
от администратора не заменяет установку службы. Интеграционные тесты используют локальные
серверы с временными сертификатами и не изменяют TUN, маршруты и системный DNS.
При отсутствии движка эти тесты отмечаются как пропущенные; такой результат не является
полной проверкой. UI-тестам нужна графическая сессия.

## Повторение проверки

```powershell
& ./packaging/verify-windows-zip.ps1 -Archive ./packaging/target/dist/wisprail-0.1.0-SNAPSHOT-windows-x64.zip
```

Этот отдельный тест запускается **обычным пользователем** в интерактивной Windows-сессии.
Он распаковывает ZIP в новый каталог, убирает системную Java из окружения дочернего процесса,
использует отдельные пользовательские данные и native-кеш, проверяет загруженные библиотеки,
подписи runtime, окно, штатный выход и события Code Integrity. Служба не устанавливается.
Доказательства: `packaging/target/acceptance/windows-<timestamp>` и `latest-windows.json`.

`DesignScenariosTest` создаёт настоящие JavaFX-снимки 41 состояния в `desktop/target/visual/javafx`.
Для эталона и сравнения из корня (нужны Node.js + Playwright + Chrome, Python + Pillow):

```sh
node desktop/src/test/visual/capture-reference.cjs
python desktop/src/test/visual/compare.py
```

HTML-отчёт: `desktop/target/visual/comparison/index.html`; есть 41 наложение и 41 diff.
Тестовые состояния не доступны в рабочем режиме. Нативная клавиатура проверяется отдельно
`DesktopKeyboardTest` с `-Dwisprail.nativeUi=true` и `-Dglass.win.uiScale=1.0`, `1.25`, `1.5`.
Он отказывается отправлять клавиши чужому окну. Полный список результатов и NOT_RUN:
[acceptance.md](acceptance.md). Ни render-тест, ни сборка не заменяют системную/сетевую приёмку.

