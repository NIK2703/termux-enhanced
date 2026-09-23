# Bubble-окно для termux-enhanced — разбор реализуемости

Дата: 2026-09-22. Форк: `0.118.0+enhanced.19`. Целевая платформа: Android 11+ (LineageOS `marble`).

---

## 1. Вердикт

**Реализуемо, но это не «включить флаг», а новая схема хостинга activity.**

- Платформенная часть (нотификация + `BubbleMetadata`) — это ~150 строк и один новый канал нотификаций.
- Вся реальная стоимость — в двух архитектурных конфликтах самого Termux, которые сегодня
  замаскированы тем, что в приложении ровно одна «терминальная» activity (см. §5).
- **MVP** (один bubble = одна активная сессия, ввод/вывод, компактная панель) — задача на несколько
  дней работы. **Полный паритет с `TermuxActivity`** (вкладки, доп. панель, диалоги, настройки) внутри
  bubble — не стоит того и не нужен: окно bubble слишком мало.
- Рекомендуемая последовательность: сначала **этап 0 — эксперимент на устройстве** (§7), и только
  если bubble реально появляется на прошивке — архитектурная работа.

Отдельно: **`targetSdkVersion=28` здесь играет НА НАС**, а не против (§4). Поднимать targetSdk ради
bubbles не нужно и вредно.

---

## 2. Что такое bubble технически

Bubble — это **не окно приложения**. Это окно, которым владеет SystemUI; приложение лишь *просит*
его показать.

1. Приложение постит нотификацию с `Notification.BubbleMetadata` внутри.
2. `BubbleMetadata` несёт `PendingIntent` на activity и `Icon` для свёрнутого состояния.
3. SystemUI (`BubbleController`) создаёт окно и **встраивает в него activity приложения через
   `ActivityView`** — то есть внутри bubble живёт самая настоящая `Activity` с нормальным жизненным
   циклом, фокусом и IME.
4. Свёрнутый bubble = иконка. Развёрнутый = окно с activity.

Ключевые следствия:

- **IME в bubble работает** — в отличие от PiP (см. §9). Это главный аргумент за bubble для терминала.
- Свёрнутый bubble показывает только иконку: произвольный «collapsed layout» публичного API не имеет.
- «Когда bubble свёрнут или закрыт — activity уничтожается». Для Termux это **нормально**: сессии
  живут в `TermuxService`, а не в activity. Схема «сервис владеет состоянием, activity — только
  представление» у нас уже есть.
- Один bubble = один shortcut id. Отсюда естественная модель: **N сессий → N bubble**, но MVP — один
  bubble на активную сессию.

Историческая справка: API появился в **Android 10 (API 29)**, «bubbles для всех приложений» — Android 11.
В **Android 17** bubbles формализованы как полноценный windowing mode наравне со split screen, и
тогда *любое* приложение можно положить в bubble UI вообще без кода. Для Android 14/15 это
неактуально, но стоит держать в голове как «бесплатный» путь в будущем.

---

## 3. Чек-лист требований платформы и аудит репозитория

| # | Требование | Где живёт сейчас | Статус |
|---|---|---|---|
| 1 | `compileSdkVersion >= 29` | `gradle.properties:21` → 34 | ✅ |
| 2 | `minSdkVersion` — API 29 закрывается рантайм-гвардом | `gradle.properties:18` → 21 | ✅ (нужен `SDK_INT >= 29`) |
| 3 | Activity bubble-хоста: `android:resizeableActivity="true"` | `TermuxActivity` — есть | ✅ |
| 4 | Activity bubble-хоста: **`android:allowEmbedded="true"`** | **нет нигде в проекте** | ❌ блокер |
| 5 | Activity не `singleTask` (иначе не получится вторая копия) | `TermuxActivity: launchMode="singleTask"` | ❌ |
| 6 | `documentLaunchMode="always"` (для нескольких bubble на API ≤ 29) | — | ❌ |
| 7 | Канал нотификаций с `setAllowBubbles(true)` | `TermuxService.setupNotificationChannel()` → `IMPORTANCE_LOW` | ❌ |
| 8 | `BubbleMetadata` на нотификации | — | ❌ |
| 9 | Long-lived shortcut + `setShortcutId` | только статические `res/xml/shortcuts.xml` | ❌ |
| 10 | `Person` на нотификации/shortcut | — | ❌ |
| 11 | `POST_NOTIFICATIONS` (API 33+) | объявлен в манифесте, рантайм-запрос только в `BootstrapSelectorActivity:155` | ⚠️ дотянуть до основного флоу |
| 12 | Отдельный notification id | `TERMUX_APP_NOTIFICATION_ID = 1337` занят FGS | ⚠️ нужен свой |
| 13 | `SYSTEM_ALERT_WINDOW` (нужен только для альтернативы C) | объявлен в манифесте | ✅ |

Обратите внимание на пункт 4: документация формулирует его жёстко — *«The activity must be resizeable
and embedded. If it lacks either of these requirements, the system displays it as a notification
instead.»* То есть activity без `allowEmbedded` молча превратится в обычную нотификацию, без ошибки.
Именно поэтому этап 0 из §7 обязателен: без него можно неделю писать код, который «не работает».

---

## 4. Развилка targetSdk: почему 28 — это преимущество

Правило платформы (цитата из официального гайда):

> If an app targets Android 11 (API level 30) or higher, a notification doesn't appear as a bubble
> unless it meets the **conversation requirements**.

И далее, про сами conversation requirements:

> **(Only if the app targets Android 11 or higher)** The notification is associated with a valid
> long-lived dynamic or cached sharing shortcut… **If the app targets Android 10 or lower, the
> notification doesn't have to be associated with a shortcut.**

У нас `targetSdkVersion=28`. Значит действует **legacy-путь**, и bubble разрешён при выполнении
**любого одного** из условий:

- нотификация использует `MessagingStyle` и имеет `Person`; **или**
- это `startForeground` с `category = CATEGORY_CALL` и `Person`; **или**
- **приложение находится в foreground в момент отправки нотификации.**

Третье условие — это подарок. Оно выполняется *по определению*, когда пользователь сам нажал
«Открыть в bubble» в меню `TermuxActivity`. То есть:

- **не нужно** натягивать на терминал фиктивный `MessagingStyle` с фальшивым собеседником;
- **не нужно** публиковать conversation shortcut;
- **не нужно** трогать targetSdk.

⚠️ Но есть нюанс, который надо проверить на устройстве: SystemUI на Android 11+ использует shortcut id
в том числе для идентичности bubble (дедупликация «одна беседа — один bubble»). Для `targetSdk ≤ 29`
это не обязательно по документации, но поведение конкретной прошивки может отличаться. Поэтому в §7
shortcut публикуется всё равно — это дёшево и снимает целый класс рисков.

**Важно:** `setSuppressNotification(true)` (показать bubble, не показывая нотификацию) требует, чтобы
приложение было в foreground, и документация отдельно уточняет: *«note that a foreground service does
not qualify»*. Значит отправлять нотификацию нужно **из activity**, а не из `TermuxService`. Это
влияет на дизайн: менеджер bubble должен жить на стороне activity, а не сервиса.

---

## 5. Два архитектурных конфликта проекта (это главное)

### 5.1 Одна сессия = один view. Размер pty — свойство сессии, а не view

Цепочка (проверено по коду):

```
TerminalView.onSizeChanged()            terminal-view/.../TerminalView.java:2676
  → updateSize()                                                  :2681
  → mTermSession.updateSize(cols, rows, fontWidth, fontLineSpacing)  :2738
  → JNI.setPtyWindowSize (ioctl TIOCSWINSZ)
```

Последний записавший выигрывает. Сегодня это не всплывает ровно потому, что `ViewPager2` держит **по
одному view на каждую РАЗНУЮ сессию** — конфликта нет по построению.

Как только bubble-activity прицепится к сессии, которая уже показана в `TermuxActivity`, мы получим
**два view на одну сессию** и, как следствие, пинг-понг размеров: pty будет дёргаться между двумя
геометриями, ломая перенос строк, `less`/`vim`/`htop` и любую полноэкранную программу.

Хорошая новость: `TerminalSession` не хранит обратной ссылки на view (только `mClient` —
`TerminalSession.java:55`), то есть технически два view возможны. Плохая: размер — глобальный для
сессии.

**Вывод:** bubble-хост не может «просто прицепиться». Нужно одно из:

- **(a) Эксклюзивность.** Пока bubble владеет сессией, главная activity обязана её отпустить
  (`TerminalView.detachSession` / вывести страницу из пейджера). Дорого и хрупко.
- **(b) Отдельная сессия для bubble.** Bubble всегда заводит свою сессию, а главная activity её в
  пейджере не показывает (или показывает, но с гарантией, что view не создан, пока bubble активен).
  Это самая дешёвая и предсказуемая схема для MVP.
- **(c) Один владелец в момент времени.** Модель «или главный экран, или bubble»: при открытии bubble
  главная activity сворачивается и освобождает view; при разворачивании обратно — наоборот.
  Согласуется с тем, что bubble и так занимает место поверх других приложений.

### 5.2 Один `TerminalSessionClient` на весь сервис

```
TermuxService.java:84    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;
TermuxService.java:781   setTermuxTerminalSessionClient(...)   // переписывает клиента ВСЕМ сессиям
TermuxService.java:792   unsetTermuxTerminalSessionClient()
```

Слот **единственный**. Если bubble-activity зарегистрирует своего клиента, он отберёт его у
`TermuxActivity`, и главный экран перестанет получать `onTitleChanged` / `onSessionFinished` /
`onBell` / `onColorsChanged` — при живом главном экране это тихий регресс.

Дополнительно:

- `TermuxServiceConnectionManager` жёстко типизирован: `private final TermuxActivity mActivity;`
  (`TermuxServiceConnectionManager.java:43`) и дёргает методы activity напрямую. Переиспользовать
  как есть нельзя — нужна host-абстракция.
- `TermuxTerminalSessionActivityClient` — 1947 строк, `TermuxTerminalViewClient` — 1300,
  `TermuxActivity` — 4733, `SessionPagerManager` — 1739. Большая часть этих строк — список сессий,
  вкладки, диалоги, настройки: в bubble они не нужны, но компилятор об этом не знает.

**Вывод:** нужен либо мультиплексирующий клиент (композит, раздающий колбэки нескольким
подписчикам), либо правило «bubble отбирает клиента только пока жив и только когда главный экран
остановлен». Второе проще, но требует аккуратной работы с `onStart/onStop` в обеих activity.

---

## 6. Варианты реализации

| | A. Platform Bubbles | B. PiP | C. Overlay (Termux:Float) | D. Android 17+ bubble-mode |
|---|---|---|---|---|
| API | 29+ | 26+ | любая (+разрешение) | 17+ |
| IME / ввод | ✅ работает | ❌ не показывается | ✅ работает | ✅ |
| Нужен код приложения | да (~600–900 строк + рефактор) | ~50 строк | ~1500+ строк (своё окно) | **нет** |
| Место на экране | фиксировано SystemUI (высота clamp) | фиксировано | произвольное, перетаскивание, resize | как bubble |
| Поверх других приложений | ✅ | ✅ | ✅ | ✅ |
| Зависимость от прошивки | высокая (OEM-политики) | низкая | средняя (overlay-политики) | — |
| Разрешения | нотификации | нет | `SYSTEM_ALERT_WINDOW` | нет |
| Что уже есть в проекте | `resizeableActivity` | `resizeableActivity` | `SYSTEM_ALERT_WINDOW` + константы канала | — |

**A — то, что просили.** Нативно, без overlay-разрешения, с работающим IME.

**B — отвергаем для интерактива, но стоит помнить.** PiP для терминала бесполезен как ввод, но
отлично годится как *монитор*: смотреть прогресс сборки, не имея возможности печатать. Реализация
почти бесплатна (`resizeableActivity` уже есть) и не конфликтует ни с чем. Разумная «вторая
опция», а не замена.

**C — уже существует в природе.** Upstream-плагин `termux-float` делает ровно это: отдельное
приложение с `sharedUserId`, foreground-сервис, `WindowManager`-оверлей с `TerminalView`. Более
того, в этом репозитории **уже лежат его константы**:
`TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_ID = "termux_float_notification_channel"` и
`TERMUX_FLOAT_APP_NOTIFICATION_ID = 1339` (`TermuxConstants.java:881–885`) — то есть форк
унаследовал shared-константы плагина. Если нужен *настоящий* плавающий терминал с произвольным
размером и перетаскиванием — это путь C, а не bubble.

**D — «бесплатно, но потом».** На Android 17 bubbles стали системным windowing mode: пользователь
сможет положить в bubble UI любое приложение без кода со стороны приложения. Termux для этого уже
готов (`resizeableActivity="true"`). Тогда наша работа сведётся только к нормальному компактному
layout внутри маленького окна.

---

## 7. План работ по пути A

### Этап 0 — эксперимент на устройстве (обязателен, ~1 вечер)

Цель — ответить на вопрос «а работает ли это на `marble` вообще», не написав ни одной строки
продакшн-кода.

1. Временная activity: `android:resizeableActivity="true"`, `android:allowEmbedded="true"`,
   `excludeFromRecents="true"`, отдельный `taskAffinity`, содержимое — любой `TextView`.
2. Временный debug-хук (у нас уже есть `TermuxDebugCommandReceiver`), который из activity постит
   нотификацию с `BubbleMetadata` и `setAutoExpandBubble(true)`.
3. Проверить: появился ли bubble, развернулся ли, работает ли IME внутри окна.
4. Проверить отдельно вариант **без** shortcut id и **с** ним — есть ли разница.
5. Проверить поведение приложения без `allowEmbedded` — убедиться, что деградация именно «в
   нотификацию» (это подтверждает наше чтение документации).

Если этап 0 не проходит — путь A закрывается, идём в C.

### Этап 1 — `TermuxBubbleActivity`

Отдельный лёгкий хост: `TerminalView` + минимальный layout (терминал + узкая строка доп. клавиш).
Манифест: `resizeableActivity="true"`, `allowEmbedded="true"`, `documentLaunchMode="always"`,
`excludeFromRecents="true"`, отдельный `taskAffinity`, `configChanges` — как у `TermuxActivity`
(чтобы resize не пересоздавал activity).

### Этап 2 — host-абстракция вместо `TermuxActivity`

Обобщить `TermuxServiceConnectionManager` до интерфейса (по образцу контроллеров Phase 6:
`TermuxActivityPopupController` / `ViewHelper` / `BroadcastManager` / `TermuxDialogs` /
`TermuxPreferenceManager` / `TextInputPanelController`).

### Этап 3 — `TermuxBubbleManager`

- новый канал нотификаций (`termux_bubble_notification_channel`) с `setAllowBubbles(true)` —
  **отдельный** от FGS-канала `termux_notification_channel`, чтобы не менять семантику
  существующей нотификации 1337;
- новый notification id;
- `BubbleMetadata.Builder` + `setDesiredHeight()` + `setAutoExpandBubble(true)`
  + `setSuppressNotification(true)`; intent и иконка ставятся **по версии**: на API 30+
  `Builder(PendingIntent, Icon)`, на API 29 — `Builder()` + `setIntent()` + `setIcon()` (§11.5);
- публикация long-lived shortcut (`ShortcutManagerCompat.pushDynamicShortcut`) и `setShortcutId()`
  **на `Notification.Builder`** — у `BubbleMetadata.Builder` такого метода нет, см. §11.1;
- pre-flight проверки: `NotificationManager.areBubblesAllowed()` (API 29) /
  `areBubblesEnabled()` (API 31), `NotificationChannel.canBubble()`, `POST_NOTIFICATIONS`;
- `setDeleteIntent` (API 29) — узнать, что bubble закрыт, и почистить состояние.

### Этап 4 — эксклюзивность сессии (§5.1)

Решить и реализовать один из трёх вариантов. Для MVP — «bubble заводит свою сессию».

### Этап 5 — точка входа и i18n

Пункт меню / кнопка «Открыть в bubble» в `TermuxActivity`, отключённый (с пояснением) когда bubble
недоступны. Строки — во все локали: `values/` + `ar de es fr hi in ja ko pt ru tr zh` + `b+zh+Hant`
(источник истины — RU).

### Этап 6 — IME и геометрия

Наши инварианты сохраняются: фокус-листенер `TerminalView` **не** планирует показ, все показы —
явные через `SoftKeyboardRestore.showWithRetry`; никаких новых «показать позже». Но в маленьком окне
bubble надо отдельно проверить `adjustResize` / `fitsSystemWindows` и то, как SystemUI двигает окно
под клавиатуру.

---

## 8. Ловушки и риски

1. **`allowEmbedded` забыть — и всё молча деградирует в нотификацию.** Без ошибки в логе.
2. **OEM-различия.** Samsung / MIUI / HyperOS имеют собственные реализации bubble; на части прошивок
   они выключены по умолчанию или заменены своим UI. Проверять надо на конкретном устройстве.
3. **Свёрнутый bubble уничтожает activity.** Сессия выживет (сервис), но view пересоздаётся. В
   `onUnbind` сервиса есть комментарий, что `unsetTermuxTerminalSessionClient()` может не
   выполниться — этот путь надо проверить именно в сценарии «свернул/развернул bubble».
4. **FGS-нотификация — не кандидат в bubble.** Она `setOngoing(true)` (уведомление нельзя скрыть), а
   открытие bubble штатно скрывает свою нотификацию. Делать отдельную, не-ongoing.
5. **`setSuppressNotification` нельзя звать из сервиса** — foreground service не считается foreground.
   Только из activity.
6. **`POST_NOTIFICATIONS` (API 33+).** Сейчас запрашивается только в `BootstrapSelectorActivity`.
   Без него bubble не появится вообще, а пользователь не поймёт почему.
7. **Геометрия окна.** `setDesiredHeight` задаётся в dp и clamp-ится SystemUI; окно может оказаться
   заметно меньше, чем хочется терминалу.
8. **Жесты.** Горизонтальный свайп внутри развёрнутого bubble может конфликтовать с drag-to-collapse.
   `ViewPager2` внутрь bubble лучше не тащить — одна сессия, без пейджера.
9. **Инсеты.** Наш разбор от 22.09 (диалоги, `contentInsets`) здесь не повторяется — окно плавающее
   и статус-бар не перекрывает, но `getWindowVisibleDisplayFrame()` в таком окне отдаёт размер окна,
   а не декора: не смешивать пространства.
10. **Процесс.** Пока bubble развёрнут, приложение — foreground-процесс; свёрнутый bubble сам по себе
    процесс не держит (кроме сервиса).
11. **`sharedUserId`** в манифесте bubbles не мешает.
12. **Не поднимать targetSdk ради этой задачи.** Это сломает legacy storage (`requestLegacyExternalStorage`,
    `MANAGE_EXTERNAL_STORAGE`) и не даст ничего взамен (§4).

---

## 9. Как проверять (adb-оракулы)

Сценарии adb — только по явной просьбе; напоминание про wireless adb: `adb mdns services` +
`adb connect <ip>:5555` в начале каждого вызова.

- Появился ли bubble: `dumpsys notification --noredact | grep -i bubble`,
  `dumpsys activity activities | grep -i bubble`.
- Логи системы: `logcat -s BubbleController NotificationService`.
- Окно: `dumpsys window windows | grep -A5 -i bubble`.
- IME внутри окна: наш `uistate` (`kb=`).
- Конфликт размеров сессии: `stty size` внутри терминала при двух view на одну сессию.
- Факт установки сборки: `dumpsys package com.termux | grep lastUpdateTime` (не mtime APK).
- Debug ≠ release по путям: темы Termux:Style в debug не работают — проверять на релизе.

---

## 10. Что решить до начала работ

1. **Bubble или «настоящее плавающее окно»?** Bubble даёт IME и ноль overlay-разрешений, но окно
   принадлежит SystemUI: ни произвольного размера, ни перетаскивания. Overlay (путь C) даёт полный
   контроль, но это своё окно и своё разрешение. Для терминала это принципиальный выбор.
2. **Одна сессия или все?** MVP — одна (активная). N bubble = N shortcut id и N activity.
3. **Что делать с главным экраном при открытом bubble** — вариант (a), (b) или (c) из §5.1.
4. **Показывать ли bubble при старте сессии автоматически** (по аналогии с чатами) или только по
   явной команде пользователя. Второе безопаснее и меньше раздражает.

---

## Источники

- https://developer.android.com/develop/ui/views/notifications/bubbles — требования к activity,
  `BubbleMetadata`, поведение по targetSdk, условия появления bubble.
- https://developer.android.com/develop/ui/views/notifications/conversations — conversation
  requirements и оговорка про targetSdk ≤ 29 (shortcut не обязателен).
- https://developer.android.com/reference/android/app/Notification.BubbleMetadata(.Builder) —
  API-поверхность. **Проверено по факту** (`javap` по `platforms/android-34/android.jar`): у
  `Builder` есть ровно три конструктора (`()`, `(String shortcutId)`, `(PendingIntent, Icon)`) и
  методы `setIntent`, `setIcon`, `setDesiredHeight`, `setDesiredHeightResId`, `setAutoExpandBubble`,
  `setSuppressNotification`, `setDeleteIntent`, `build`. Метода `setShortcutId` **не существует**.
- Код проекта: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/termux/app/TermuxService.java`,
  `app/src/main/java/com/termux/app/terminal/TermuxServiceConnectionManager.java`,
  `terminal-view/src/main/java/com/termux/view/TerminalView.java`,
  `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`,
  `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`,
  `gradle.properties`.

---

## 11. Пост-фактум: что реализовано и что уточнилось

Реализация выполнена (ветка рабочего дерева, 22.09). Гейты пройдены: `:app:processReleaseResources`,
`:app:compileReleaseJavaWithJavac`, `:app:lintVitalRelease`, `:app:assembleDebug` — все
`BUILD SUCCESSFUL`.

### 11.1 `BubbleMetadata.Builder.setShortcutId()` не существует

Первая версия кода вызывала `bubbleMetadata.setShortcutId(shortcutId)` — компилятор её отверг.
Правда об API (сверено по исходникам AOSP: `core/java/android/app/Notification.java` в ветках
`android-10.0.0_r47` и `android-11.0.0_r48`):

| Конструктор | API | Замечание |
|---|---|---|
| `Builder()` | 29 | **на API 29 это единственный конструктор**; deprecated с 30, но не удалён |
| `Builder(PendingIntent, Icon)` | **30** | **выбранный путь на API 30+**; на 29 отсутствует ⇒ `NoSuchMethodError` |
| `Builder(String shortcutId)` | 30 | deprecated в 31; SystemUI **резолвит launch-intent из самого shortcut** и игнорирует `setIntent()` |

> Первая редакция этой таблицы была **перепутана** (у неё `Builder()` стоял как API 30, а
> `Builder(PendingIntent, Icon)` — как 29). Из-за этого безусловный вызов
> `Builder(PendingIntent, Icon)` выглядел безопасным, хотя на Android 10 он гарантированно падает:
> в android-10 у класса объявлены только `Builder()` и сеттеры `setIntent()` / `setIcon()`.
> Нашлось это только когда гейт доступности стали разбирать по версиям (§11.7) — до того на
> Android 10 никто не проверял.

`setDeleteIntent` — **API 29** (в android-10 уже есть), поэтому guard `SDK_INT >= R` в
`showBubbleInternal` избыточен: он лишь лишает Android 10 колбэка «пузырёк закрыт». Оставлен
сознательно — это изменение поведения в потоке, который здесь нечем проверить.
Ассоциация с long-lived sharing shortcut делается через `Notification.Builder.setShortcutId()`.
Второй конструктор сознательно **не** используется: его семантика (intent берётся из shortcut)
здесь не нужна, но чтобы она не выстрелила на ROM'ах, которые всё же смотрят в shortcut, shortcut
публикуется с **настоящим** intent'ом на `TermuxBubbleActivity`, а не с плейсхолдером
`ACTION_DEFAULT`.

### 11.2 Одна сессия — один клиент: понадобился мультиплексор

`TerminalSession` держит ровно один `TerminalSessionClient`, и это единственный канал, по которому
сессия сообщает «экран изменился» (`notifyScreenUpdate()` → `onTextChanged`). `TerminalView` сам
эмулятор не слушает — ему велят перерисоваться. Поэтому второй поверхности (bubble) нельзя просто
«отдать» клиент: main-activity замёрзнет. Сделано: `TermuxTerminalSessionClientMux` — один
primary-делегат + N secondary. Каждый fan-out обёрнут в guard: падение одного делегата не роняет
остальные.

**Уточнение после перехода на «настоящее окно в пузыре».** Изначально `TermuxService` держал
**одно** поле `mTermuxTerminalSessionActivityClient`, а primary в мультиплексоре переключался при
bind/unbind через `setPrimary()`. С двумя настоящими окнами это не работает: окно, привязавшееся
вторым, отбирало клиента у первого, и первый экран замирал. Теперь primary — **всегда**
`TermuxTerminalSessionServiceClient` (он не держит ссылок на окна, а `setTerminalShellPid` у него и у
activity-клиента реализованы одинаково, так что замена безопасна), а окна регистрируются как
secondary:

- поле — `CopyOnWriteArrayList<TermuxTerminalSessionActivityClient> mTermuxTerminalSessionActivityClients`;
- `setTermuxTerminalSessionClient(client)` — аддитивный (`addIfAbsent` + `addSecondary`);
- `unsetTermuxTerminalSessionClient(client)` — снимает **только своё** окно
  (`TermuxServiceConnectionManager.unbindService()` передаёт клиента своего окна);
- `unsetAllTermuxTerminalSessionClients()` — из `onUnbind` (окон не осталось);
- `setCurrentSessionInAllWindows(session)` — смена сессии сервисом (`SWITCH_TO_NEW_SESSION_*`)
  доезжает до всех окон;
- `termuxSessionListNotifyUpdated()` — рассылается по всем зарегистрированным окнам.

### 11.3 Пузырь содержит настоящее окно приложения, а не его «пародию»

Первая версия рисовала в пузыре собственный урезанный UI (`activity_termux_bubble.xml` +
`TermuxBubbleViewClient` + `TermuxBubbleSessionClient`: один терминал, своя панель клавиш, без вкладок
и диалогов). Это оказалось неверной моделью: пользователю нужно **то же самое окно**, а не его
подмножество. Итоговая схема:

- `TermuxBubbleActivity` — **пустой подкласс** `TermuxActivity`; всё видимое (тулбар, вкладки,
  панель доп. клавиш, диалоги, IME) наследуется целиком;
- ради этого `TermuxActivity` перестал быть `final` (единственное статическое состояние —
  `sInstance`/`getInstance()`, который **никто не вызывает**, так что два экземпляра безопасны);
- в пузыре живёт **второй экземпляр** того же класса activity, а не «та же» activity: одну activity
  нельзя показать в двух окнах одновременно. Сессии общие — они принадлежат `TermuxService`;
- у пузыря **своя** запись в манифесте, потому что `allowEmbedded` несовместим с `singleTask`, а
  `singleTask` у `TermuxActivity` менять нельзя: это изменило бы поведение запуска из лаунчера, а
  `excludeFromRecents` — атрибут уровня activity, и полноэкранный экземпляр исчез бы из recents;
- из-за этого у пузыря **свой** пейджер/вкладки и своя панель клавиш; сессии, созданные в одном окне,
  появляются в другом через `termuxSessionListNotifyUpdated()`;
- удалены как мёртвый код: layout, оба прежних клиента, drawable, стили/цвета `TermuxBubbleKey*`,
  строки `bubble_no_session_message` / `bubble_action_close` / `bubble_key_*` (по всем 14 локалям).

### 11.4 PendingIntent пузыря обязан быть `FLAG_MUTABLE`

Первый рабочий пост падал молча. В logcat:

```
java.lang.IllegalArgumentException: 0|com.termux.debug|1342|null|10477 Not posted.
    PendingIntents attached to bubbles must be mutable
```

Бросает `NotificationManagerService.checkDisqualifyingFeatures()`, а `notify()` отдаёт это как
`RemoteException` ⇒ без перехвата и логирования выглядит как «ничего не произошло». SystemUI
дописывает в этот intent параметры запуска (какой пузырь, где стоит, раскрыт ли), поэтому здесь —
**задокументированное исключение** из правила «всегда `FLAG_IMMUTABLE`». `FLAG_MUTABLE` есть только с
API 31, ниже intent'ы мутабельны по умолчанию.

Заодно разделены два сообщения об отказе: `bubble_unavailable_message` (не выполнена
предпроверка — пузыри выключены) и `bubble_open_failed_message` (предпроверка прошла, но система
отвергла пост). Раньше оба случая показывали первое, что и отправило пользователя искать
несуществующую проблему в разрешениях.

### 11.5 Платформа не даёт приложению включить пузыри — это делает только пользователь

Самое неочевидное. `NotificationChannel.setAllowBubbles(true)` **ничего не даёт** для обычного
(target) приложения. В `PreferencesHelper.createNotificationChannel()` ветка создания канала
заканчивается так:

```java
channel.setAllowBubbles(existing != null ? existing.getAllowBubbles()
                                         : NotificationChannel.DEFAULT_ALLOW_BUBBLE);
```

то есть значение, переданное приложением, **затирается** на `DEFAULT_ALLOW_BUBBLE` (-1). Ветка
обновления существующего канала копирует только name/description/group/blockable/importance —
`allowBubbles` там нет вообще, так что и повторным вызовом его не поднять. А
`NotificationChannel.canBubble()` — это `mAllowBubbles == ALLOW_BUBBLE_ON`, т.е. канал с -1 **не
пузырится**.

Подтверждено на устройстве: канал висел с `mAllowBubbles=-1`, нотификация публиковалась успешно
(`id=1342`, shortcut валиден), но запись получала `mAllowBubble=false` и `isBubble=false` —
SystemUI не считала её пузырём, `setSuppressNotification(true)` не срабатывал, и в шторке оставалась
обычная нотификация. После

```
adb shell cmd notification set_bubbles com.termux.debug 1
adb shell cmd notification set_bubbles_channel com.termux.debug termux_bubble_notification_channel true
```

канал получил `mAllowBubbles=1`, запись — `mAllowBubble=true`, `isBubble=true`, и пузырь появился.

Выводы для кода:

- `setAllowBubbles(true)` оставлен, но помечен в javadoc как **просьба, а не гарантия**;
- реальный переключатель — пользовательская настройка пузырей для приложения, её и читает
  `areBubblesAvailable()` (`areBubblesEnabled()` на API 31+, `areBubblesAllowed()` на 29–30);
- **`areBubblesEnabled()` — API 31, а не 30** (§11.7). Версия проверяется не по `SDK_INT`, а по
  наличию метода в рантайм-framework'е;
- именно поэтому важно, чтобы нотификация была «разговором»: `MessagingStyle` + `Person` +
  long-lived shortcut — это то, что делает канал видимым в списке пузырей в настройках, где
  пользователь и включает флаг;
- `targetSdk=28` здесь скорее помощь: conversation-требования для приложений с targetSdk ≥ 30 к нам
  не применяются, поднимать targetSdk ради пузырей не нужно.

### 11.6 Прочее, что подтвердилось на практике

- `TermuxActivity` — `singleTask` ⇒ `allowEmbedded` на ней невозможен, нужна отдельная activity.
- `setSuppressNotification(true)` требует **foreground activity** (foreground *service* не считается)
  ⇒ нотификация публикуется из `TermuxActivity`, не из `TermuxService`.
- Размер pty принадлежит **сессии**, а не view (`updateSize()` → `JNI.setPtyWindowSize`, побеждает
  последний записавший). С тех пор как пузырь стал настоящим окном, эта политика пересмотрена: две
  поверхности одной сессии дают ограниченный рефлоу при переносе фокуса, что принято как осознанная
  цена. Handle сессии в intent пузыря не передаётся — окно само решает, какая у него текущая вкладка,
  иначе появился бы второй источник истины, конфликтующий с его же обработкой вкладок.
- В `showBubble()` добавлен `Intent.ACTION_VIEW` на bubble-интенте: `ShortcutInfoCompat.Builder`
  требует intent с action.
- Гейт `:app:processReleaseResources` показал `added=={} / removed=={}` по bubble-строкам — все 14
  локалей согласованы.
- Апостроф в `bubble_unavailable_message` (`values/`) пришлось экранировать (`app\'s`) — aapt2 иначе
  падает; в репозитории для этого есть `scripts/check_apostrophes.py`.
- Контекстное меню терминала открывается **клавишей MENU** (`adb shell input keyevent 82`), а не
  long-press: long-press уходит в системное выделение текста.

### 11.7 Гейт доступности: версия Android, API в framework'е, Android Go

Краш с устройства `realme RMX3581`, Android 11 (API 30):

```
java.lang.NoSuchMethodError: No virtual method areBubblesEnabled()Z in class Landroid/app/NotificationManager;
    at com.termux.app.bubble.TermuxBubbleManager.areBubblesAllowed(SourceFile:133)
    at com.termux.app.bubble.TermuxBubbleManager.areBubblesAvailable(SourceFile:125)
    at com.termux.app.TermuxService.buildNotification(SourceFile:973)
    at com.termux.app.TermuxService.runStartForeground(SourceFile:257)
    at com.termux.app.TermuxService.onCreate(SourceFile:161)
```

Причина — неверная версия в таблице: `areBubblesEnabled()` появился в **API 31**, а гейт стоял
`SDK_INT >= R` (30), поэтому на Android 11 вызывался несуществующий метод. Падало из `onCreate`
сервиса, то есть приложение не поднималось вообще. Проверка версий — по
`$ANDROID_HOME/platforms/android-34/data/api-versions.xml` (атрибут `since`; опущен, когда совпадает
с `since` класса):

| метод | since | deprecated |
|---|---|---|
| `NotificationManager.areBubblesAllowed()` | 29 | 31 |
| `NotificationManager.areBubblesEnabled()` | 31 | — |
| `NotificationManager.getBubblePreference()` | 31 | — |
| `Notification.BubbleMetadata.Builder.setSuppressableBubble()` | 31 | — |

**Android Go.** Платформа сама отказывается от пузырей на low-RAM устройствах —
`BubbleExtractor.process()` (AOSP):

```java
boolean notifCanPresentAsBubble = canPresentAsBubble(record)
        && !mActivityManager.isLowRamDevice()
        && record.isConversation()
        && record.getShortcutInfo() != null
        && (record.getNotification().flags & FLAG_FOREGROUND_SERVICE) == 0;
...
if (!userEnabledBubbles || appPreference == BUBBLE_PREFERENCE_NONE || !notifCanPresentAsBubble) {
    record.setAllowBubble(false);
    if (!notifCanPresentAsBubble) record.getNotification().setBubbleMetadata(null);
}
```

То есть на Android Go (`ro.config.low_ram=true` ⇒ `ActivityManager.isLowRamDevice()` = true) метадата
пузыря вырезается до ранжирования: опубликовать пузырёк там невозможно в принципе, и кнопка «в
пузырёк» — ровно тот случай «кнопки, которая ничего не может», от которого гейт и существует.

**Реализация.** Единый гейт `TermuxBubbleManager.isSupported(context)` = три условия:

1. версия ≥ Android 10 (API 29);
2. в рантайм-framework'е есть метод опроса — резолвится reflection'ом один раз на процесс
   (`areBubblesEnabled`, иначе `areBubblesAllowed`); версия **не** сравнивается с `SDK_INT`, чтобы
   таблица версий не могла снова разойтись с реальностью;
3. устройство не low-RAM (`ActivityManager.isLowRamDevice()`) — вопрос ровно тот, что задаёт
   платформа.

От гейта зависят оба пользовательских входа: кнопка «в пузырёк» в нотификации сервиса
(`TermuxService.buildNotification`, условие `areBubblesAvailable`) и переключатель
`bubble-on-background` в «Оформлении» (`DisplayPreferencesFragment.configureBubbleOnBackgroundSupport`
гасит его через `setEnabled(false)`). Значение самой настройки при этом **не** переписывается, а
строка не прячется — иначе пользователь остался бы с пузырьком, который не появляется, и без
объяснения почему.

**Вторая находка того же класса — конструктор `BubbleMetadata.Builder`.** Разбор гейта по версиям
вывел на ещё одну ошибку таблицы версий: `Builder(PendingIntent, Icon)` появился только в API 30
(в android-10 у класса есть лишь `Builder()` + `setIntent()` / `setIcon()`), а вызывался он
безусловно — то есть на Android 10 пост пузырька падал бы с `NoSuchMethodError` ровно так же, как на
Android 11 падал `areBubblesEnabled()`. Теперь форма выбирается по версии, см. §11.5.

`setDeleteIntent` при этом — API 29 (в android-10 уже есть), и guard `SDK_INT >= R` вокруг него
избыточен: он лишь лишает Android 10 колбэка «пузырёк закрыт». Оставлен как есть — это изменение
поведения в потоке, который здесь нечем проверить.
