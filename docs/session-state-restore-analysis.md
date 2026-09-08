# Аудит системы запоминания состояния сессий (клавиатура / панель ввода / фокус)

Сворачивание ↔ разворачивание. Анализ на оптимальность + точки оптимизации без изменения функциональности.

---

## 1. Карта системы

| Уровень | Хранилище | Ключ | Что живёт | Где пишется/читается |
|---|---|---|---|---|
| **L1** (RAM) | `SessionUiStateStore` | `TerminalSession.mHandle` | текст панели, каретка, видимость панели, фокус (панель/терминал), `scrollTopRow`+`transcriptRows`, per-session KB-intent | `app/.../terminal/io/SessionUiStateStore.java` |
| **L2** (recreate) | `Bundle` (7 вложенных бандлов) | тот же handle | текст/каретка/видимость/фокус/скролл/KB-intent + global KB-intent + active index | `saveToBundle()` / `restoreFromBundle()` |
| **L3** (смерть процесса) | `termux_prefs` → `ui_state_json` | **индекс** в списке сессий | только `text`, `caret`, `scrollRow`, `scrollRows`, `activeIndex`. Панель/фокус/KB-intent намеренно не сохраняются | `exportToJson()` / `importFromJson()` |
| **Слепок табов** | `termux_prefs` → `session_snapshot` | — | cwd, имя, failsafe, активный индекс | `TermuxSessionSnapshotManager` |
| **Активная сессия** | `termux_prefs` → `current_session` | — | handle текущей сессии | `setCurrentStoredSession()` |

Ключевые участники: `TermuxActivity` (onPause/onStop/onResume/onStart), `TermuxTerminalViewClient.setSoftKeyboardState()` + per-page focus listener, `SessionPagerManager.onTerminalPageSelected()`, `TermuxTerminalSessionActivityClient.onStart/onStop`, `SoftKeyboardRestore`, `KeyboardUtils`, `TermuxActivityRootView` (margin-контроллер), `ImeVisibilityDetector` (frame-метод).

---

## 2. Что реально происходит при СВОРАЧИВАНИИ

`onPause()` → `onSaveInstanceState()` (pre-P) или `onStop()` → `onSaveInstanceState()` (P+)

| # | Вызов | Стоимость |
|---|---|---|
| 1 | `onPause` → `captureCurrentSessionUiState()` | `EditText.getText().toString()` (аллокация, до 32 K chars = 64 KB), `setCaret`, `setFocusOnInput`, `setScrollState`, `computeImeVisibility()` |
| 2 | `onSaveInstanceState` → `saveTerminalToolbarTextInput()` | **второй** `getText().toString()` + `putString(...)` в бандл |
| 3 | `onSaveInstanceState` → `mTextInputState.saveToBundle()` | 7 аллокаций `Bundle`, итерация по всем сессиям |
| 4 | `onStop` → `setFocusOnInputForCurrentSession()` + `saveTextInputForCurrentSession()` | **третий** `getText().toString()` — дубль п.1 |
| 5 | `onStop` → `mMessageHistoryCtrl.flushPersist()` | `apply()` (уже условный — только если есть pending). ✅ |
| 6 | `onStop` → `setCurrentStoredSession()` | `apply()` #1 |
| 7 | `onStop` → `saveSessionSnapshotNow()` | **N × `File.getCanonicalPath("/proc/<pid>/cwd")` в UI-потоке** + JSON + `apply()` #2 |
| 8 | `onStop` → `persistUiState()` | JSON по всем сессиям + `apply()` #3 |
| 9 | `ActivityThread.handleStopActivity` → `QueuedWork.waitToFinish()` | **блокирует UI-поток**, пока все `apply()` из п.6–8 не допишут `termux_prefs` на диск |

**Итого за сворачивание:** 3–4 полные перезаписи XML `termux_prefs` (все пишут в один файл), 2N readlink'ов `/proc` в UI-потоке, 3 копирования текста поля ввода.

> `apply()` не проверяет, изменилось ли значение: `SharedPreferencesImpl.apply()` всегда ставит в очередь `enqueueDiskWrite` → полная сериализация файла. Писать то же самое значение = та же цена.

---

## 3. Что происходит при РАЗВОРАЧИВАНИИ

| # | Вызов | Стоимость |
|---|---|---|
| 1 | `onStart` → сброс лачей IME, `mImeDetector.refresh()` | 1 × `getWindowVisibleDisplayFrame()` (IPC) |
| 2 | `onStart` → `new Handler(...).postDelayed(mJustResumed=false, 400)` | аллокация Handler, нет `removeCallbacks` (гонка при быстром stop/start) |
| 3 | `onStart` → `rootView.forceRelayout()` + регистрация `OnGlobalLayoutListener` | IPC + IPC на каждом кадре |
| 4 | `onStart` → sessionClient: `setCurrentSession(stored)` → индекс тот же → **`onSessionPageSelected()`** | `checkForFontAndColors`, `updateBackgroundColor`, `getCurrentSessionCwdAsync` (readlink в пуле), **`applyTextInputVisibilityForSession(session, applyFocus=true)` → полный IME-reconcile** — а дальше `onResume` сделает это ЕЩЁ РАЗ |
| 5 | `onStart` → `termuxSessionListNotifyUpdated()` → `updateTabs()` | `populateTabView()` для **всех** табов (setText → requestLayout) + дебаунс `saveSessionSnapshot()` (через 400 мс снова N readlink + `apply()`) |
| 6 | `onResume` → `setSoftInputMode(ADJUST_RESIZE|ALWAYS_HIDDEN)` | dispatch атрибутов окна (IPC) #1 |
| 7 | `onResume` → viewClient `setSoftKeyboardState()` | `clearDisableSoftKeyboardFlags` + `setSoftInputMode` (IPC) #2–3, `requestFocus()`, возможный `postDelayed(show, 300)` |
| 8 | `onResume` → sessionClient `loadBellSoundPool()` | **`new SoundPool.Builder().build()` + `load()` из raw-ресурса в UI-потоке** (создаётся и освобождается каждый цикл) |
| 9 | `onResume` → `applyTextInputVisibilityForSession(..., false, kbIntent)` → `setTextInputSlotVisible` + `restoreTextInputForSession` + `updateToggleTextInputButtonIcon` | умеренно |
| 10 | `onResume` → `reassertPanelLayout()` | **рекурсивный `requestLayout()+invalidate()` по всему поддереву тулбара** (ExtraKeysView = десятки кнопок) + `OneShotPreDrawListener` |
| 11 | `onResume` → `postDelayed(forceRelayout, 300)` (еще один `new Handler`) | IPC |
| 12 | `onWindowFocusChanged(true)` → `runKeyboardRestore()` | `reassertPanelLayout()` **второй раз**, `whenViewLaidOut(..., 6)` (до 6 pre-draw + post), `showWithRetry` (до 4 × 120 мс), `postDelayed(latch=false, 800)` |
| 13 | `SoftKeyboardRestore` | до 4 × `imm.showSoftInput` + 3 отложенных Runnable |

**Итого за разворачивание:** 3–5 dispatch'ов атрибутов окна, 2 прохода `kickLayoutTree`, до 4 попыток показа IME, ~5 отложенных Runnable, 800 мс «глухого» окна `mRestoringKeyboard`, пере-создание SoundPool, 2 комплекта N readlink'ов, полный IME-reconcile дважды (onStart + onResume).

---

## 4. Находки, ранжированные

### P0-1 · Три-четыре независимых записи в один и тот же `termux_prefs` в `onStop` 🔴

`TermuxActivity.onStop()`: `setCurrentStoredSession()` (#1) → `flushPersist()` (#2, условно) → `saveSessionSnapshotNow()` (#3) → `persistUiState()` (#4). Все — в `termux_prefs`. Каждый `apply()` = полная перезапись файла, а `waitToFinish()` ждёт их все на UI-потоке.

**Правка (безопасно, без смены семантики):**

```java
// TermuxSessionSnapshotManager — не писать, если содержимое не изменилось
private String mLastSnapshotJson;
...
if (json.equals(mLastSnapshotJson)) return;
mLastSnapshotJson = json;
prefs.edit().putString(PREF_SESSION_SNAPSHOT, json).apply();
```

Аналогично в `TermuxActivity.persistUiState()` — кэшировать последнюю строку JSON и выходить, если совпала. В типовищем сценарии (пользователь свернул, ничего не меняя) это снимает **2 из 3 записей**.

**Правка глубже (следующий шаг):** `TermuxPreferenceManager` уже заявлен как «single entry point for termux_prefs» — расширить его батчевым редактором (`beginBatch()/endBatch()`), чтобы весь `onStop` писал файл один раз. Функционально идентично: `SharedPreferences.Editor` — один и тот же файл и тот же порядок ключей.

**Эффект: −2…3 полные перезаписи XML и минус соответствующее ожидание в `QueuedWork.waitToFinish()` (ориентировочно 3–10 мс UI-потока на цикл).**

---

### P0-2 · `saveSessionSnapshotNow()` делает N × readlink `/proc/<pid>/cwd` в UI-потоке, и это делается дважды за цикл 🔴

`TermuxSessionSnapshotManager.saveSessionSnapshot()` → `terminal.getCwd()` → `new File("/proc/<pid>/cwd/").getCanonicalPath()` для **каждой** сессии, на главном потоке, в `onStop` (то есть внутри окна, которое ждёт `waitToFinish`).

Плюс второй комплект: `onStart` → `termuxSessionListNotifyUpdated()` → `saveSessionSnapshot()` (дебаунс 400 мс) → ещё N readlink + ещё `apply()` — хотя список табов не менялся.

**Правка:** флаг «грязности». Слепок уже пишется дебаунсом после каждого структурного изменения; `onStop` должен перечитывать cwd, только если с последней записи было изменение:

```java
// TermuxActivity
private boolean mSnapshotDirty;            // выставляется в termuxSessionListNotifyUpdated(),
                                           // onSessionPageSelected, create/remove session
public void saveSessionSnapshot() {
    mSnapshotDirty = true;
    mSnapshotHandler.removeCallbacks(mSaveSnapshotRunnable);
    mSnapshotHandler.postDelayed(mSaveSnapshotRunnable, 400);
}
public void saveSessionSnapshotNow() {
    mSnapshotHandler.removeCallbacks(mSaveSnapshotRunnable);
    if (!mSnapshotDirty) return;           // на диске уже актуален
    mSessionSnapshotManager.saveSessionSnapshot();
    mSnapshotDirty = false;
}
```

**Эффект: −N readlink'ов из `onStop` и −1 запись XML в обычном цикле.**

---

### P0-3 · Тройное копирование текста поля ввода за цикл 🟠

`onPause` (capture) → `onSaveInstanceState` (`saveTerminalToolbarTextInput`) → `onStop` (`saveTextInputForCurrentSession`). При буфере 32 K символов это 3 × 64 KB мусора за сворачивание.

Дополнительно: `ARG_TERMINAL_TOOLBAR_TEXT_INPUT` **пишется, но нигде не читается** (проверено grep по всему проекту) — мёртвая запись. Четыре константы `ARG_TEXT_INPUT_*_PER_SESSION` в `TermuxActivity` (строки 431–434) тоже дублируют константы `SessionUiStateStore` и не используются.

**Правка:** удалить `saveTerminalToolbarTextInput()` и неиспользуемые константы; в `onStop` сохранять только если состояние могло измениться (multi-window: после `onPause` активность ещё видима, поэтому полный отказ от `onStop`-сейва некорректен — но достаточно сравнить через `TextUtils.equals(...)` без аллокации и перезаписывать только при различии).

---

### P1-1 · `reassertPanelLayout()` вызывается дважды за разворачивание и рекурсивно инвалидирует весь тулбар 🟠

`TermuxActivity:819` (onResume) и `:1082` (runKeyboardRestore). Внутри — `kickLayoutTree(toolbar)`: `requestLayout()` **и `invalidate()`** на каждом узле поддерева, включая все кнопки ExtraKeysView.

`invalidate()` по детям избыточен: один `invalidate()` на корне поддерева помечает всю область «грязной», дети перерисовываются в проходе draw. А `requestLayout()` на корне уже планирует measure/layout поддерева.

**Правка:**
1. Убрать `invalidate()` из рекурсии, оставить один `toolbar.invalidate()`.
2. Добавить fast-path — в норме (высота не деградировала) вообще ничего не делать:

```java
if (toolbar.getHeight() > 0 && toolbar.getMeasuredHeight() > 0
        && (slot == null || slot.getHeight() > 0)) return;
```

3. Не вызывать второй раз в рамках одного resume (`mPanelRelayoutDone`, сбрасывать в `onResume`).

---

### P1-2 · `mRestoringKeyboard` снимается «тупым» таймером 800 мс 🟠

`runKeyboardRestore()`: `target.postDelayed(() -> mRestoringKeyboard = false, 800)` при том, что `showWithRetry` сам останавливается, как только probe увидел IME (обычно 120–240 мс).

Побочный эффект важнее экономии: пока висит latch, `onImeVisibilityChanged()` считает состояние «в переходе» и **не записывает keyboard-intent**. Если пользователь в течение 800 мс после разворота сам скрыл клавиатуру — его намерение не сохранится, и при следующем развороте клавиатура неожиданно вернётся.

**Правка:** передавать в `SoftKeyboardRestore.showWithRetry(target, probe, onSettled)` колбэк завершения (срабатывает при `probe.isImeVisible()` сразу после шоу либо после последней попытки) и снимать latch в нём, оставив `postDelayed(..., 800)` только как страховку, удаляемую при settle.

---

### P1-3 · 3–5 dispatch'ов атрибутов окна за разворачивание с одинаковым значением 🟠

`KeyboardUtils.setSoftInputModeAdjustResize()` / `setSoftKeyboardAlwaysHiddenFlags()` вызывают `getWindow().setSoftInputMode(...)` без проверки текущего значения; `setSoftKeyboardState()` на каждом resume вызывает `clearDisableSoftKeyboardFlags()` без проверки (при том, что рядом уже есть `areDisableSoftKeyboardFlagsSet()`). Каждый вызов — `dispatchWindowAttributesChanged` → relayout окна.

**Правка (2 строки, риск нулевой):**

```java
public static void setSoftInputModeAdjustResize(Activity activity) {
    if (activity == null || activity.getWindow() == null) return;
    if (activity.getWindow().getAttributes().softInputMode
            == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) return;
    activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
}
```
```java
// TermuxTerminalViewClient.setSoftKeyboardState()
if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity))
    KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
```

---

### P1-4 · SoundPool создаётся и освобождается на каждом цикле 🟠

`onResume → loadBellSoundPool()` (`new SoundPool.Builder().build()` + `load()` из `R.raw.bell` — чтение и декодирование ресурса в UI-потоке), `onStop → releaseBellSoundPool()`.

При этом `onBell` (`:372`) всё равно вызывает `loadBellSoundPool()` лениво, т.е. прогрев в `onResume` не является необходимостью.

**Правка:** перенести прогрев из критического пути — `mMainHandler.post(loadBellSoundPool)` (или `postDelayed(..., 300)`), а `release()` делать на фоновом потоке в `onStop`. Поведение не меняется: звук доступен в любой момент, ленивая загрузка в `onBell` остаётся страховкой.

---

### P1-5 · IME-reconcile выполняется дважды за разворачивание 🟠

`onStart` → `setCurrentSession(stored)` → индекс совпадает → `onSessionPageSelected()` → `applyTextInputVisibilityForSession(session, applyFocus=true)` → focus + reconcile клавиатуры (включая `hideSoftKeyboard`/`showWithRetry`). Затем `onResume` → `runKeyboardRestore()` делает то же самое ещё раз, уже как «единственная власть».

**Правка:** в `onStart` помечать, что restore выполнит `onResume`, и передавать `applyFocus=false` (слот и текст всё равно применятся), либо выставлять `mRestoringKeyboard = true` уже в `onStart` для возврата из фона. Это убирает лишний `hideSoftKeyboard`+`showSoftInput` и лишний комплект вызовов `setSoftInputMode`.

---

### P2-1 · `onStart` → `updateTabs()` пере-заполняет все табы и планирует запись слепка 🟡

`termuxSessionListNotifyUpdated()` вызывается на каждом возврате в foreground; `updateTabs()` сам по себе инкрементальный (без `removeAllViews`), но всё равно прогоняет `populateTabView()` по каждому табу (setText → requestLayout), а затем планирует `saveSessionSnapshot()` (через 400 мс — N readlink + `apply()`), хотя список не менялся.

**Правка:** в `populateTabView()` выходить, если заголовок/выделение/цвет не изменились (сравнение со старыми значениями в tag'е); снимок планировать только при структурном изменении (счётчик/состав сессий), а не при каждом `termuxSessionListNotifyUpdated()`.

---

### P2-2 · Аллокации в `onGlobalLayout` на каждом кадре 🟡

`TermuxActivityRootView.onGlobalLayout()` → `ViewUtils.getWindowAndViewRects()` аллоцирует `Rect` ×2–3 и `Point`, вызывает `getDisplayOrientation(context)` и `getWindowVisibleDisplayFrame()` (binder-вызов к WMS). Это происходит на каждом кадре анимации клавиатуры.

`ImeVisibilityDetector` (второй слушатель) делает ещё один `getWindowVisibleDisplayFrame()` за кадр **и остаётся прикреплённым во время фона** — в отличие от root-view слушателя, у него нет проверки `isVisible()` и он не снимается в `onStop`.

**Правка:** переиспользовать `Rect`/`Point` как поля; кэшировать ориентацию (меняется только на config change); в `ImeVisibilityDetector` добавить guard активности либо снимать/вешать его в `onStop`/`onStart`. На API 30+ (где авторитетны insets) frame-детектор можно оставить только как fallback, включаемый при `softInputMode != ADJUST_RESIZE`.

---

### P2-3 · `saveToBundle()` 🟡

* 7 объектов `Bundle` аллоцируются всегда (даже когда 6 из них останутся пустыми);
* `focusBundle.putBoolean(key, false)` пишется для **каждой** сессии, хотя `false` — значение по умолчанию (основной случай).

**Правка:** аллоцировать бандлы лениво; писать только `true`.

---

### P2-4 · Мелкие утечки/аллокации в hot path 🟢

* `new Handler(Looper.getMainLooper())` в `onStart` и `onResume` — завести одно поле `mMainHandler` (есть прецедент: `mSnapshotHandler`) и именованные `Runnable` с `removeCallbacks()` перед `postDelayed` (снимает гонку таймеров `mJustResumed` при быстром stop/start).
* `whenViewLaidOut(..., 6)` — 6 попыток pre-draw избыточны; достаточно 2–3.
* `updateTextInputToggleButtonAnchor()` читает `SharedPreferences` и вызывает `setLayoutParams` на каждом переключении панели — значение `tab_panel_position` можно кэшировать слушателем (как уже сделано в `SessionPagerManager` для `swipe_rightmost_new_tab`).

---

## 5. Что трогать НЕЛЬЗЯ (инварианты)

1. **Capture состояния — в `onPause`, не в `onStop`.** На pre-P `onSaveInstanceState` вызывается до `onStop`, поэтому перенос захвата в `onStop` сломает восстановление через Bundle.
2. **`mRestoringKeyboard` поднимается ДО `mTermuxTerminalViewClient.onResume()`** (до `setSoftKeyboardState()`). Менять порядок нельзя — иначе `requestFocus()` из `setSoftKeyboardState` запланирует `+500 мс` show, который переживёт hide (баг «клавиатура возвращается при развороте»).
3. **Keyboard-intent пишется только вне `inTransition`** (`mIsPaused / mJustResumed / mRestoringKeyboard / mPendingKeyboardRestore / pageSwitch / systemDrop`). Любое расширение этого множества напрямую меняет поведение.
4. **L3 JSON намеренно не хранит `panelVisible` / `focusOnInput` / KB-intent** —Fresh-старт всегда с закрытой панелью. Не «улучшать».
5. **L3 ключуется индексом**, потому что handle не выживает смерть процесса; `importFromJson` требует тот же порядок сессий, что и `restoreSessionSnapshot()`.
6. `mTerminalPageSwitchInProgress` снимается через `post()` — фокус доставляется в looper'е после возврата из `onTerminalPageSelected()`.

---

## 6. План внедрения

| Шаг | Правка | Риск | Выигрыш |
|---|---|---|---|
| 1 | Skip-if-unchanged для `session_snapshot` и `ui_state_json` | мин. | −2 записи XML/цикл |
| 2 | `mSnapshotDirty` — не перечитывать `/proc/*/cwd` в `onStop` без изменений | низкий | −N readlink в UI-потоке |
| 3 | Удалить мёртвый `ARG_TERMINAL_TOOLBAR_TEXT_INPUT` и 4 дубликата констант | нулевой | −1 toString/цикл |
| 4 | Идемпотентность в `KeyboardUtils` + guard `areDisableSoftKeyboardFlagsSet` | низкий | −2–3 IPC/цикл |
| 5 | Fast-path + один `invalidate()` в `reassertPanelLayout()`; вызов 1 раз за resume | средний | −1 полный проход layout по тулбару |
| 6 | `onSettled`-колбэк вместо `postDelayed(800)` | средний | −600 мс латча + устранение потери user intent |
| 7 | Прогрев SoundPool вне критического пути | низкий | −десятки мс в `onResume` |
| 8 | `applyFocus=false` в `onStart` при возврате из фона | средний | −1 лишний IME-reconcile |
| 9 | Кэш Rect/Point + guard активности в детекторах layout | средний | −аллокации/IPC на кадре анимации |
| 10 | Батчевый `SharedPreferences.Editor` на весь `onStop` | средний | −2 записи XML → 1 |

**Как мерить:** `adb shell am trace-ipc` / Perfetto на `onPause`→`onStop` и `onStart`→`onResume`+800 мс; `StrictMode.detectDiskWrites()` + `StrictMode.noteSlowCall` в `onStop`; замер `QueuedWork.waitToFinish()` через systrace-секцию «QueuedWork»; `KBTrace` (уже встроен, `KBTrace.ENABLED`) для подсчёта лишних `showSoftInput`/`setSoftInputMode` за цикл.

---

## 7. Резюме «смета» на один цикл сворачивание→разворачивание

| Ресурс | Сейчас | После P0–P1 |
|---|---|---|
| Полные перезаписи `termux_prefs` | 3–4 | 1 |
| `readlink /proc/<pid>/cwd` в UI-потоке | 2N | 0…N (только при изменениях) |
| `getText().toString()` поля ввода | 3 | 1 |
| dispatch атрибутов окна | 3–5 | 1–2 |
| Проходы `requestLayout` по поддереву тулбара | 2 | 0–1 |
| `showSoftInput` попыток | 1–4 | 1–2 |
| Окно подавления записи user-intent | 800 мс | ≤ ~240 мс |
| Создание/освобождение SoundPool | 1/цикл | 0/цикл |
