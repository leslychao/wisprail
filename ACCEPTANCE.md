# Проверка Wisprail

Дата: 30 сентября 2026. `fullAcceptance: NOT_ASSERTED`, `ownerAcceptance: NOT_GRANTED`.
Исходные дизайн, HTML и PNG не переписаны под реализацию. Каноническое поведение задаёт
`sing-box_javafx_vpn_solution.md`; демонстрационные сетевые успехи HTML в приложение не перенесены.

## Исправления и границы доказательств

- Перед изменениями проверены working tree, staged и untracked. Индекс пуст, исходники untracked;
  текущие файлы сохранены. Сборка берёт их фактическое содержимое, а не `git archive HEAD`.
- `DesktopView` владеет навигацией/черновиком. `ConnectionService` сохраняет единоличное владение
  подключением; UI отображает selected/applied/target отдельно. Отмена active-save теперь до записи.
- Все 41 сценарий имеют отдельную JavaFX-фикстуру и снимок. Это PASS отрисовки и перечисленных
  assertions, **не** PASS полного пользовательского/сетевого маршрута и **не** пиксельная приёмка.
- Визуальное сравнение: клиент 1070×700 при 100%, HTML Chrome и реальные JavaFX Scene/Popup/Dialog.
  41 наложение 50/50 и 41 diff ×3: [отчёт](frontend/target/visual/comparison/index.html).
  `metrics.json` содержит сырые отличия пикселей, без выдуманного порога автоматического PASS.
  Данные в фикстурах местами отличаются (события, readiness, версии); это указано по строкам.
- Нативная рамка исключена из сравнения клиентской области. JavaFX/Chrome растеризуют шрифты
  различно; Segoe UI Semibold проверен по фактическим именам JavaFX. Это не оправдывает
  оставшиеся отличия TabPane, некоторых диалогов, журнала и компактной панели — visual acceptance PARTIAL.
- Минимальная область 860×640 и длинные русские подписи отрисованы. Ранее пройдены реальные
  Tab/Shift+Tab/Enter/Esc при toolkit scale 1.0/1.25/1.5 (`native-scale-*.txt`, `native-*.png`).
  Повтор после последних правок не завершился: окно теряло фокус/Tab не достигал ожидаемого
  поля. Финальная проверка native foreground PID установила владельца **LockApp**: экран Windows заблокирован. `keyboard-final-*.log`: INCONCLUSIVE, не засчитывается PASS текущей версии.
  Проверка владельца фокуса оставлена и усилена проверкой PID foreground window.
  Переключение системного DPI 100/150%, отдельный монитор и полный клавиатурный обход — NOT_RUN.

## Запуск Windows: установленная причина и повтор

Исходный пакет `deploy/target/windows-20260930-135714/images/Wisprail` блокировался Windows Code
Integrity: 3033/3077, `VerifiedAndReputableDesktop`, status `0xc0e90002`, файл
`runtime/bin/fontmanager.dll`. Открытое окно и exit 0 старого прогона не доказывали исправный запуск.
Старое заключение «запуск PASS» этим расследованием уточнено.

`jlink` использовал неподписанный payload Microsoft JDK JMOD. Его SHA-256 совпал со старой DLL:
`2bedfe4e51bc63bf0e9debfc94dddefbe5c38bd0183da6a0f9036c8683336ef3`.
Оригинал **того же** JDK 21.0.11 имеет Valid подпись Microsoft и другой хеш:
`8c4de2f25742ecc915f12f58166c80338e1a1e21d7add623bda3f609a7d2ddee`.
JavaFX и JNA не были причиной воспроизведённой блокировки.

Исправление в `package-windows.ps1`: после jlink все DLL/EXE runtime заменяются неизменёнными
оригиналами того же JDK; обязательны x64 PE, Valid vendor signatures и равенство SHA после копии.
Нет отключения Code Integrity, подписи, TLS или политики выполнения; UI не получает admin token.

Повтор нового распакованного ZIP: `deploy/verify-windows-zip.ps1`, доказательства
[latest-windows.json](deploy/target/acceptance/latest-windows.json) и соответствующий
`deploy/target/acceptance/windows-<timestamp>/` (окно, loaded-modules, Code Integrity, SHA архива).
Первый подтверждённый повтор: `windows-20260930-152827` — обычный пользователь, 77 Valid runtime
DLL/EXE, загружены fontmanager/JavaFX/JNA, новых 3033/3077 нет, штатный exit 0, системная Java
удалена из окружения дочернего процесса. Финальный повтор дополнительно использует новые
user.home/JNA temp, чтобы проверить извлечение из JAR, а не старый native-кеш. При недоступном
foreground отдельный screenshot=NOT_RUN; такой запуск не выдаётся за визуальную проверку окна.
`Wisprail.exe` и весь ZIP не объявляются подписанным продуктом: сохранены подписи поставщика runtime.

## Сборка и платформы

Одна команда и окружения описаны в [README](README.md#zip-дистрибутивы-одним-запуском).
[build-results.json](deploy/target/dist/build-results.json) содержит фактический buildId, версию,
SHA-256 снимка и результаты платформ. При отсутствующем Mac Windows ZIP сохраняется, общий exit 1.

| Проверка | Результат / граница |
|---|---|
| Maven Wrapper clean verify, Java 21, `-Xlint:all -Werror` | 67 пройденных проверок (19 unit + 4 real-engine IT + 44 UI), 1 opt-in keyboard test пропущен. Лог финального запуска `deploy/target/build-distributions-final.log`; surefire/failsafe внутри каталога снимка |
| CIDR/полный охват IPv4, suffix boundaries, импорт, секреты, ревизии/атомарность | Backend unit tests; реальные сетевые пути этим не подтверждаются |
| Ошибка кандидата, switch token/revision, отмена, duplicate click, поздний ответ, cleanup failure | `ConnectionServiceTest`; fake runtime на границе владельца, не успешный VPN в приложении |
| Реальный sing-box check/OpenVPN/VLESS TLS/Reality, версия/API | `SingboxConfigIT`, engine 1.14.2 |
| Реальное VLESS TLS HTTP и OpenVPN endpoint handshake | `LoopbackVpnIT`, loopback без TUN и системного DNS |
| JavaFX 41 состояния + поиск + два основных теста | `DesignScenariosTest` / `DesktopViewTest`; 44 проверки, без service IPC |
| Обычный Windows ZIP + новый runtime | BUILT/PASS по metadata и `latest-windows.json` |
| Windows PowerShell 5.1 команда из Run XML | BLOCKED до входа в скрипт: existing effective `Restricted`; защита не менялась |
| Тот же build-distributions.ps1 из уже настроенного PowerShell 7 | Выполнен непосредственно, без IDE; отсутствие Mac возвращает exit 1 |
| Текущие untracked исходники и содержимое ZIP | PASS: 97 файлов сверены с manifest, свежий JAR совпадает с ZIP, тестовый файл из старого target отсутствует, версии/хеши совпадают; `deploy/target/acceptance/distribution-content.json` |
| Ошибка обязательной сборки и старый ZIP | PASS: намеренно отсутствующий JDK → Windows FAILED, общий exit 1, прежний ZIP удалён; `deploy/target/build-distributions-negative.json` |
| macOS arm64 / x64 сборка и распаковка | NOT_RUN: Mac/разрешённые Mac SSH aliases отсутствуют; интеграция подготовлена |
| Mac executable permissions/symlinks, Keychain, UDS, Service Management | NOT_RUN: нужен Mac; используется штатный ditto на стороне Mac |
| Установка/обновление/удаление службы Windows, UAC отказ/разрешение | NOT_RUN: служба не устанавливалась; нужны контролируемая административная сессия и допустимый политикой установщик |
| Подписанный release MSI/PKG и notarization | NOT_RUN: нет signing identity/notary access, WiX/SDK и Mac; обычный ZIP их не требует |
| Реальные TUN, NRPT/SystemConfiguration, соседний VPN, .local, сон/смена сети | NOT_RUN: нет контролируемого привилегированного VPN-стенда и целевых ресурсов |
| Запуск именно из IDEA | NOT_RUN для итоговой конфигурации: пользователь явно заменил эту проверку прямым запуском скрипта |

## Все 41 сценарий

В колонке «проверено» указаны выполненные render/переходы; действия с сетью, секретами,
установщиками или файлами не считаются выполненными по одному открытию диалога.
Ссылка каждой строки содержит **HTML, JavaFX, наложение и diff**. `UI_RENDER_PASS` не равен
визуальной или платформенной приёмке. Оставшиеся отличия перечислены отдельно, без переписывания дизайна.

| № / ID | Требование | Расхождение и исправление | Проверено | Снимки | Результат / ограничение |
|---|---|---|---|---|---|
| 1. `connected` | Карточка активного VPN и применённые сети | Восстановлены оболочка 66 px, sidebar 246 px, значки, строки настроек и быстрые ссылки | CONNECTED → карточка; сохранённый снимок не подменяет applied | [сравнение](frontend/target/visual/comparison/index.html#connected) | UI_RENDER_PASS; Геометрия проверена наложением; реальные TUN/DNS NOT_RUN |
| 2. `disconnected` | Карточка отключённого профиля | Состояние и действие относятся к выбранному профилю; статус в шапке | DISCONNECTED → Подключить | [сравнение](frontend/target/visual/comparison/index.html#disconnected) | UI_RENDER_PASS; Запуск службы и реальный VPN NOT_RUN |
| 3. `connecting` | Ожидание и отмена подключения | Индикатор, этап и отмена привязаны к target/operationId | CONNECTING → ожидание; ConnectionServiceTest проверяет cancel/late callback | [сравнение](frontend/target/visual/comparison/index.html#connecting) | UI_RENDER_PASS; Кнопка отмены показана; сетевой переход UI→служба NOT_RUN |
| 4. `switch` | Выбран другой профиль | Выбор отделён от applied, показано предупреждение переключения | Выбор RGF Test сохраняет applied RGF Moscow (assert) | [сравнение](frontend/target/visual/comparison/index.html#switch) | UI_RENDER_PASS; Разрыв действующего VPN и запуск другого NOT_RUN |
| 5. `disconnecting` | Отключение | Вместо преждевременной кнопки подключения отображается ожидание очистки | DISCONNECTING → карточка ожидания | [сравнение](frontend/target/visual/comparison/index.html#disconnecting) | UI_RENDER_PASS; Реальная очистка NOT_RUN |
| 6. `error` | Ошибка подключения | Ошибка target-профиля и переход в журнал, повторный запуск доступен только когда разрешён | ERROR/TIMEOUT → ошибка | [сравнение](frontend/target/visual/comparison/index.html#error) | UI_RENDER_PASS; Неправильные credentials на внешнем сервере NOT_RUN |
| 7. `empty` | Пустой экран | Убрана пустая боковая панель; центральный знак и добавление; загрузка отделена от пустого списка | loading → ready/empty; поиск без совпадений не выдаёт пустое хранилище | [сравнение](frontend/target/visual/comparison/index.html#empty) | UI_RENDER_PASS; Визуальная сверка выполнена |
| 8. `add` | Три способа добавления | Карточки файла, ссылки, ручного ввода вместо разрозненных действий; закрытие без записи | Открытие и отмена окна добавления | [сравнение](frontend/target/visual/comparison/index.html#add) | UI_RENDER_PASS; Выбор каждого способа вручную end-to-end NOT_RUN |
| 9. `import` | Предпросмотр распознанного импорта | Таблица название/тип/сервер/сети; замечания и недостающие поля; секреты скрыты | Preview → диалог → отмена; backend проверяет строгий импорт | [сравнение](frontend/target/visual/comparison/index.html#import) | UI_RENDER_PASS; Переход к черновику реализован; полный файловый маршрут NOT_RUN |
| 10. `editor` | Редактор: Основное | Редактор перенесён в рабочую область, четыре вкладки, fixed footer и прокрутка | Открытие применённого профиля → Основное | [сравнение](frontend/target/visual/comparison/index.html#editor) | UI_RENDER_PASS; Тип существующего профиля неизменяем; формат TabPane отличается от HTML |
| 11. `access` | Редактор: Доступ OpenVPN | Поля входа, защищённое хранение, строки файлов и свёрнутые дополнительные параметры | Основное → Доступ | [сравнение](frontend/target/visual/comparison/index.html#access) | UI_RENDER_PASS; Нет переключателя показа пароля; файловый chooser ОС NOT_RUN |
| 12. `networks` | Сети профиля | Текстовое поле заменено списком с изменением/удалением и формой добавления | Редактор → Сети; backend проверяет CIDR и полный IPv4-охват | [сравнение](frontend/target/visual/comparison/index.html#networks) | UI_RENDER_PASS; Изменение/удаление через реальные клики всех строк NOT_RUN |
| 13. `dns` | DNS профиля | Отдельный список суффиксов, пояснение DNS и маршрутов | Редактор → DNS; backend проверяет границы суффиксов | [сравнение](frontend/target/visual/comparison/index.html#dns) | UI_RENDER_PASS; Системный split DNS NOT_RUN |
| 14. `dns-warning` | DNS вне выбранных сетей | Предупреждение и явное добавление /32 без скрытого изменения профиля | Ввод 10.80.0.53 → предложение маршрута (внизу прокручиваемой формы) | [сравнение](frontend/target/visual/comparison/index.html#dns-warning) | UI_RENDER_PASS; Нажатие добавления /32 в нативном окне NOT_RUN |
| 15. `active-save` | Изменение активного профиля | Выбор Отмена/Сохранить на потом/Переподключить выполняется ДО записи; подписи не обрезаются | Отмена сохраняет черновик и оба исходных снимка (assert); deferred render проверен отдельно | [сравнение](frontend/target/visual/comparison/index.html#active-save) | UI_RENDER_PASS; Переподключение через установленную службу NOT_RUN |
| 16. `auth` | Запрос пароля VPN | Пароль скрыт, показаны учётная запись и пояснение о системном пароле | Открытие credential dialog → отмена | [сравнение](frontend/target/visual/comparison/index.html#auth) | UI_RENDER_PASS; Фикстура не имитирует сетевую авторизацию; username меняется в редакторе |
| 17. `permission` | Запрос системного разрешения | Настоящий installer/UAC, ожидание exit code; запуск процесса не выдаётся за готовую службу | Открытие пояснения → отмена до установщика | [сравнение](frontend/target/visual/comparison/index.html#permission) | UI_RENDER_PASS; UAC allow/deny и установка NOT_RUN; фон сравнения — настройки, а не демо-карточка |
| 18. `unsupported` | Недоступный компонент | Предупреждение внутри карточки с переходом в настройки; connect недоступен | online=false → предупреждение; профили остаются | [сравнение](frontend/target/visual/comparison/index.html#unsupported) | UI_RENDER_PASS; Фактический offline при запуске ZIP подтверждён |
| 19. `journal` | Журнал | Заголовок, вкладки, фильтры, таблица, экспорт и ограниченный источник событий | Навигация → события; реальные backend события отображаются теми же контролами | [сравнение](frontend/target/visual/comparison/index.html#journal) | UI_RENDER_PASS; В фикстуре 2 события вместо 6 HTML; стили уровней пока обычный текст |
| 20. `diagnostics` | Проверка подключения | Проверка вынесена в вкладку, ограничена ширина, таблица результатов и разбор адреса | Навигация → Проверка с Не проверено | [сравнение](frontend/target/visual/comparison/index.html#diagnostics) | UI_RENDER_PASS; Произвольный выбранный профиль не подключается; реальная системная диагностика NOT_RUN |
| 21. `settings` | Общие настройки | Центрированная область 744 px, секции, переключатели и выбор закрытия | Навигация → Настройки → Общие | [сравнение](frontend/target/visual/comparison/index.html#settings) | UI_RENDER_PASS; Автозапуск не менялся; закрепление его в ОС NOT_RUN |
| 22. `components` | Компоненты и данные | Структурированные строки версии, поддержки, проверки; установщик и доступ к данным | Общие → Компоненты | [сравнение](frontend/target/visual/comparison/index.html#components) | UI_RENDER_PASS; Доступность — IPC, а не результат запуска installer; кнопки установки NOT_RUN |
| 23. `delete` | Удаление активного профиля | Подтверждение; удаление возможно только после подтверждённой остановки | Открытие удаления → отмена; backend хранение/очистка проверены | [сравнение](frontend/target/visual/comparison/index.html#delete) | UI_RENDER_PASS; Реальная остановка и удаление активного профиля NOT_RUN |
| 24. `export` | Экспорт профиля | Предупреждение о внутренних адресах, секреты исключены владельцем ProfileImport | Открытие экспорта → отмена; ProfileTest/ProfileStorageTest подтверждают redaction | [сравнение](frontend/target/visual/comparison/index.html#export) | UI_RENDER_PASS; Нативный Save chooser NOT_RUN |
| 25. `cleanup` | Ошибка очистки | Новый запуск заблокирован; footer не обещает восстановление сети | CLEANUP_FAILED → Проверить, Повторить отсутствует (assert); сервисный регрессионный тест | [сравнение](frontend/target/visual/comparison/index.html#cleanup) | UI_RENDER_PASS; Физический отказ удаления маршрутов NOT_RUN |
| 26. `tray` | Компактный трей | JavaFX-панель использует общий snapshot, актуальное имя и действия существующего owner | Отрисовка настоящего Popup поверх рабочего окна | [сравнение](frontend/target/visual/comparison/index.html#tray) | UI_RENDER_PASS; Положение системного трея зависит от ОС; открытие кликом значка NOT_RUN |
| 27. `exit` | Выход с активным VPN | Три различимых действия; увеличена ширина, убрано сокращение подписей | Открытие → отмена; нормальное закрытие offline ZIP подтверждено | [сравнение](frontend/target/visual/comparison/index.html#exit) | UI_RENDER_PASS; Выход при работающем VPN NOT_RUN |
| 28. `vless-main` | VLESS: Основное | Общий редактор; сервер/порт, дополнительные параметры проверки | Открытие Personal → Основное | [сравнение](frontend/target/visual/comparison/index.html#vless-main) | UI_RENDER_PASS; Требование реальной readiness-проверки шире HTML-демо |
| 29. `vless-access` | VLESS: Доступ | UUID скрыт; обычный TLS и Reality с явными параметрами | Открытие Personal → Доступ | [сравнение](frontend/target/visual/comparison/index.html#vless-access) | UI_RENDER_PASS; Дополнительные реальные поля/прокрутка обоснованы спецификацией; Reality сервер NOT_RUN |
| 30. `add-file` | Импорт из файла | Выбор файла → настоящий FileChooser; обработка вне FX thread, отмена отбрасывает поздний результат | Открытие выбора → отмена | [сравнение](frontend/target/visual/comparison/index.html#add-file) | UI_RENDER_PASS; Демонстрационная ссылка примера не добавлена в рабочий режим; native chooser NOT_RUN |
| 31. `add-link` | Импорт ссылки | Форма VLESS, подсказка о секрете; строгий parser | Открытие ввода → отмена; backend отклоняет неизвестные параметры | [сравнение](frontend/target/visual/comparison/index.html#add-link) | UI_RENDER_PASS; Буфер обмена пользователя не читался; успешный импорт UI end-to-end NOT_RUN |
| 32. `import-error` | Ошибка импорта | Ошибка сохраняет профили и соединение; повторный выбор файла | Открытие ошибки → отмена; parser отрицательные проверки PASS | [сравнение](frontend/target/visual/comparison/index.html#import-error) | UI_RENDER_PASS; Макет/тексты ошибки ещё отличаются от HTML |
| 33. `network-form` | Добавление сети | Типизированная форма, /32, явное подтверждение нормализации | Сети → диалог → отмена; backend CIDR tests | [сравнение](frontend/target/visual/comparison/index.html#network-form) | UI_RENDER_PASS; Нормализация в диалоге полным нативным проходом NOT_RUN |
| 34. `domain-form` | Добавление домена | Суффиксы с поддоменами, формат без схемы/пути; изменение/удаление | DNS → диалог → отмена; backend domain tests | [сравнение](frontend/target/visual/comparison/index.html#domain-form) | UI_RENDER_PASS; Нативный ввод/нормализация end-to-end NOT_RUN |
| 35. `validation` | Проверка полей | Реальный ProfileValidator; успех не обещает выполненный sing-box check; ошибки подсвечивают вкладки/поля | Валидные поля при offline → информационный диалог; invalid draft проверен DesktopViewTest | [сравнение](frontend/target/visual/comparison/index.html#validation) | UI_RENDER_PASS; HTML выдаёт демо-успех; production явно разделяет поля/движок/доступность |
| 36. `unsaved` | Несохранённые изменения | Черновик принадлежит оболочке; отмена перехода возвращает выбранный раздел | Изменение имени → Журнал → отмена; editor остаётся (assert) | [сравнение](frontend/target/visual/comparison/index.html#unsaved) | UI_RENDER_PASS; Динамическое имя заголовка и текст отмены отличаются от HTML |
| 37. `log-detail` | Подробности события | Панель появляется при выборе строки, копирование доступно | События → выбор строки → подробности | [сравнение](frontend/target/visual/comparison/index.html#log-detail) | UI_RENDER_PASS; Тестовое событие отличается от HTML; оформление подробностей ещё не идентично |
| 38. `diagnostics-done` | Результат проверки | PASS/ERROR/NOT_RUN из службы; тестовая фикстура только в test | displayChecks → строки результатов | [сравнение](frontend/target/visual/comparison/index.html#diagnostics-done) | UI_RENDER_PASS; В отличие от демо, сеть и DNS оставлены NOT_RUN; это не сетевое доказательство |
| 39. `profile-menu` | Меню профиля | Изменение, копия, экспорт, удаление; popup привязан к правому краю кнопки | Открытие MenuButton → снимок popup | [сравнение](frontend/target/visual/comparison/index.html#profile-menu) | UI_RENDER_PASS; Нативная клавиатурная навигация по меню NOT_RUN |
| 40. `report` | Отчёт | Предпросмотр текста, внутренние адреса включаются отдельной отметкой | Открытие отчёта → отмена; backend redaction PASS | [сравнение](frontend/target/visual/comparison/index.html#report) | UI_RENDER_PASS; Предпросмотр подробнее HTML и шире; запись через native chooser NOT_RUN |
| 41. `about` | О программе | Фактическая версия из Maven и ограничения вместо текста о концепте | Открытие из настроек → закрытие | [сравнение](frontend/target/visual/comparison/index.html#about) | UI_RENDER_PASS; Версии/текст и фон отличаются от демонстрации намеренно |

## Обязательная системная приёмка — отдельно на каждой ОС

1. Установить пакет допустимого для среды типа (обычный либо подписанный release), разрешить компонент штатным диалогом ОС. Проверить отказ
   в разрешении, доступ разрешённого пользователя и отказ другому пользователю.
2. На контролируемых OpenVPN и VLESS TLS/Reality серверах проверить успешное подключение,
   неправильные credentials, неверный сертификат, недоступный DNS/HTTPS и отсутствие ложного CONNECTED.
3. Обычным приложением, без диагностического proxy-обхода, выполнить запрос к профильному IP,
   непрофильному IP и корпоративному имени. На сервере/интерфейсах подтвердить фактический путь
   пакетов и обращения к корпоративному DNS. Сравнить непрофильный путь до/после подключения.
4. Повторить с другим VPN, пересекающимися маршрутами и DNS, управляемой NRPT/MDM-политикой,
   `.local`, положительным и отрицательным DNS-кешем. Чужие настройки должны остаться неизменными.
5. Проверить переключение: отказ кандидата сохраняет активное соединение; после остановки старого
   отказ нового не приводит к откату. Повторный клик, отмена и позднее событие не запускают второй VPN.
6. Завершить UI, службу и движок по отдельности; проверить аварийную очистку только собственных
   объектов. Искусственно запретить очистку: состояние ERROR, повторный запуск блокируется.
7. Сменить сеть, выполнить сон/пробуждение; потеря готовности отражается в UI и трее без скрытого
   переподключения. При необходимости повторить подключение явно.
8. Проверить отсутствие UUID, паролей, ключей, сертификатного материала в JSON профиля,
   журнале, экспорте, скопированном профиле и отчёте. Приватные временные конфигурации должны
   быть недоступны обычному постороннему пользователю и удаляться после завершения/восстановления.
9. Проверить обновление заполненного хранилища и удаление пакета; собственные сетевые настройки
   отсутствуют, пользовательские профили и секреты сохранены. Проверить подпись и notarization.

Для каждого прогона сохранять ОС/версию, хеш пакета и движка, сценарий, наблюдаемые маршруты,
DNS и результат без секретов. UI-снимок, открытый TCP-порт или `sing-box check` не заменяет шаг 3.

