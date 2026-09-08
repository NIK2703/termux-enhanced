# Аудит системы дополнительных кнопок (ExtraKeys) — оптимизация

Дата: 2026-09-08
Объём анализа: `termux-shared/.../extrakeys/*`, `termux-shared/.../terminal/io/*`,
`app/.../terminal/io/TermuxTerminalExtraKeys.java`, `TermuxActivity.java`,
`TermuxTerminalSessionActivityClient.java`, `fragments/settings/termux/ExtraKeysEditorFragment.java`.

Метод: чтение исходников + сверка с `app/build/outputs/mapping/release/usage.txt`
(список кода, реально удалённого R8 как неиспользуемый — объективное подтверждение «мёртвого» кода).

---

## 0. Картина целиком

| Файл | Строк | Роль |
|---|---|---|
| `ExtraKeysView.java` | 2194 | God-объект: сборка сетки, тач/свайпы, автоповтор, гаптик, стейт-машина CTRL/ALT/SHIFT/FN, редакторские жесты, отрисовка на Canvas, подбор шрифта |
| `ExtraKeysTouchHandler.java` | 424 | **Дубликат** тач-логики `ExtraKeysView` — ни одного использования |
| `ExtraKeysContextWatcher.java` | 244 | Поллинг `/proc/*/stat` — ни одного использования |
| `ExtraKeysEditorFragment.java` | 1207 | Визуальный редактор раскладок |
| `TermuxTerminalExtraKeys.java` | 503 | Контекстные/сессионные раскладки, приоритеты |
| `ExtraKeysInfo.java` / `ExtraKeyButton.java` | 270 / 265 | Парсинг JSON → матрица кнопок |

---

## A. Мёртвый код (дешёвые победы, −670 строк)

### A1. `ExtraKeysContextWatcher` никогда не создаётся — фича не работает
`termux-shared/.../extrakeys/ExtraKeysContextWatcher.java` — 244 строки, ноль `new ExtraKeysContextWatcher(...)`.
В `usage.txt` (строки 23188–23189) класс и его интерфейс помечены удалёнными.

Следствия:
* Свойство `extra-keys-context` (**переключение раскладки по foreground-процессу: vim/python/htop**) парсится
  (`TermuxTerminalExtraKeys.parseContextMap()`), но **никогда не применяется** — `onForegroundProcessChanged()`
  (стр. 274) не вызывается ниоткуда.
* Свойство `extra-keys-context-poll-interval` вообще инертно.
* Половина стейт-машины в `TermuxTerminalExtraKeys` (строки 274–468: `mCurrentContext`,
  `resolveContextForProcess`, `applyContextLayout`, `applyDefaultLayout`) — недостижимый код,
  на который тратится время при каждом чтении.
* Два design-документа (`extra-keys-context-watcher-design.md`,
  `extra-keys-context-termuxactivity-integration.md`) описывают несуществующую интеграцию.

**Решение:** либо дописать ~20 строк интеграции (создать watcher в `TermuxActivity`,
`start()/stop()` в `onResume/onPause`, `setTerminalSession()` при смене таба, `destroy()` в `onDestroy`),
либо удалить класс, `KEY_EXTRA_KEYS_CONTEXT(_POLL_INTERVAL)` и контекстную ветку целиком.
Третьего (молчаливый мёртвый код) не стоит.

### A2. `ExtraKeysTouchHandler` — точная копия тач-логики
424 строки, ноль ссылок (`usage.txt` 23192–23195). Дублирует `handleTouchEvent`,
`startScheduledExecutors`, `endSpecialButtonHold`, `detectDirection`, `performHapticFeedback`
из `ExtraKeysView` (899–1042, 1142–1224). Плюс создаёт **свой**
`Executors.newSingleThreadScheduledExecutor()` на инстанс.

**Решение:** удалить. Если цель была «вынести тач из God-объекта» — выносить надо сам
`ExtraKeysView`, а не копировать его (см. D4).

### A3. `showPopup()` / `dismissPopup()` / `mPopupWindow`
`ExtraKeysView.java:1228–1258`, `usage.txt:23216–23218` — удалены R8. Popup-кнопка
осталась от старой схемы «свайп вверх = показать popup», сейчас свайп сразу отправляет ключ.
Поле `mPopupWindow` + обработка в `onDetachedFromWindow` (1205–1208) тоже мертвы.

### A4. Половина gesture-API не вызывается
`IExtraKeysView.onExtraKeyButtonGesturePress` (стр. 176) **не имеет ни одного вызова** —
`ExtraKeysView` зовёт только `onExtraKeyButtonGestureRelease` (968, 992, 1025) и
`onAnyExtraKeyButtonClick`. Переопределение в `TerminalExtraKeys:160` удалено R8
(`usage.txt:23651`).

Это не только мёртвый код, но и **функциональная дыра**: по задумке (javadoc 164–178)
свайп должен «нажать» модификатор на `ACTION_MOVE` и отпустить на `ACTION_UP` — т.е.
`CTRL`+стрелка свайпом. Сейчас press-фазы нет, поэтому свайп на модификатор
работает как обычный тап-тоггл.

**Решение:** либо вызвать `onExtraKeyButtonGesturePress` рядом с
`onAnyExtraKeyButtonClick(...)` в `ACTION_MOVE` (946) и в ветке «свайп на отпускании» (1023),
либо убрать метод и поправить javadoc — но тогда поведение нужно признать багом.

### A5. Прочие удалённые R8 члены
`ExtraKeysView.getExtraKeyButtonAt()` (1304), `cancelEditorGesture()` (1411),
`MacroRunner.destroy()/isRunning()` (87/93), `TerminalExtraKeys.setTerminalView()` (43),
3-аргументный `ExtraKeysInfo` ctor (128).

---

## B. Баги корректности (важнее микрооптимизаций)

### B1. **[критично] Автоповтор отправляет ввод в терминал из фонового потока**
`ExtraKeysView.java:1152–1155` (долгое удержание) и `951–954` (удержание после свайпа):

```java
mRepetitiveFuture = mScheduledExecutor.scheduleWithFixedDelay(() -> {
    mLongPressCount++;
    onExtraKeyButtonClick(view, buttonInfo, button);   // ← поток пула, не main
}, mLongPressTimeout, mLongPressRepeatDelay, MS);
```

`onExtraKeyButtonClick` → `TerminalExtraKeys.onTerminalExtraKeyButtonClick` →
`terminalView.onKeyDown(...)` / `terminalView.inputCodePoint(...)` / `session.write(...)`.
`TerminalView.inputCodePoint` (terminal-view:1736) работает с `mEmulator`, включает
мигание курсора и вызывает `invalidate()` — то есть **терминальный эмулятор и View
мутируются не из UI-потока**. Затрагивает `UP/DOWN/LEFT/RIGHT/BKSP/DEL/PGUP/PGDN`
(`PRIMARY_REPETITIVE_KEYS`), т.е. ровно те клавиши, которые держат дольше всего.

Плюс `mLongPressCount++` — не `volatile`/`AtomicInteger`, пишется из пула,
читается в `ACTION_UP` на main (стр. 1034 `if (mLongPressCount == 0) view.performClick();`).
Гонка → «клик иногда не срабатывает» или «лишний клик».

**Решение:** повторы гонять через `Handler(Looper.getMainLooper()).postDelayed()`
(рекурсивный Runnable) — отдельный поток не нужен вообще; либо
`mHandler.post(...)` внутри задачи пула. `mLongPressCount` → `AtomicInteger`
или считать только на main.

### B2. `setLongPressRepeatDelay` проверяет поле вместо параметра
`ExtraKeysView.java:752–757`:
```java
public void setLongPressRepeatDelay(int longPressRepeatDelay) {
    if (mLongPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY   // ← поле, а не параметр
        && mLongPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
        mLongPressRepeatDelay = longPressRepeatDelay;
    } else {
        mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY;
    }
}
```
Поле инициализировано в `0` → **любой первый вызов игнорирует переданное значение**
и ставит `DEFAULT (80)`. Пользовательская настройка задержки автоповтора молча не работает.
Фикс: заменить оба вхождения на `longPressRepeatDelay`.

### B3. Утечка потоков: `mScheduledExecutor` никогда не завершается
`ExtraKeysView.java:435` — `Executors.newSingleThreadScheduledExecutor()` на **каждый инстанс**,
без daemon-фабрики и без `shutdown()`. `onDetachedFromWindow()` (1197) отменяет только задачи.
`TermuxActivity` пересоздаётся на каждое изменение в редакторе (см. C7) →
каждый `recreate()` порождает новый `ExtraKeysView` и новый **non-daemon** поток,
который живёт до конца процесса.

**Решение:** daemon-фабрика + `shutdownNow()` в `onDetachedFromWindow()`,
или (лучше) полностью перейти на main-thread `Handler` — см. B1.

### B4. Состояние жеста общее на всю сетку — мультитач ломается
`mTouchDownX/Y`, `mRuntimeSwipeDirection`, `mGestureActive{Button,MaterialButton,View}`
(417–433) — поля view, а не жеста. Второй палец на другой кнопке перезаписывает
координаты первого; `mGestureActiveView` — единственный, поэтому два одновременных
свайпа ⇒ потерянный `GestureRelease` и «залипший» модификатор.
**Решение:** держать состояние жеста в `SparseArray<GestureState>` по `pointerId`.

### B5. `reload()` может оставить недостроенную сетку
`ExtraKeysView.java:830–832`: `if (button == null) return;` — `return` из середины
двойного цикла, панель остаётся частично собранной (уже вызван `removeAllViews()`).
**Решение:** `continue`/break с флагом, или `createSpecialButton` не должен возвращать null
(он возвращает null только при отсутствии ключа в `mSpecialButtons` — а проверить можно заранее).

### B6. Пул кнопок сбрасывается не полностью
`ExtraKeysView.java:833–843` сбрасывает text/listener/tag/pressed, но не
`setEllipsize`, `setMaxLines`, `setSingleLine`, `setTypeface`, `setCornerRadius`.
Бывшая многострочная кнопка, переиспользованная под один символ, сохраняет
`Ellipsize.END`/`maxLines > 1`. Визуально проявляется как «иногда текст обрезается».

### B7. Редактор: индекс ребёнка ≠ индекс кнопки
`ExtraKeysEditorFragment.java:844–857` — `row = i / mCols; col = i % mCols;` считается
по `i` из цикла, который пропускает не-`MaterialButton` (847). Любой лишний ребёнок
сдвигает все координаты → `setTag(new int[]{row,col,flags})` и, как следствие,
`onCellMove`/`onKeyTap`/`onKeySwipe` **редактируют не ту ячейку**.
Там же `mGrid[visibleRowStart()+row][visibleColStart()+col]` без проверки границ,
хотя `ExtraKeysView.reload()` считает столбцы как `maximumLength(matrix)`, а не `mCols`.
Аналогично `openSignalPicker():1088` и `mEditorMoveListener:746–748`
(индексация до валидации, мёртвая проверка `src == null`).

---

## C. Лишняя работа

### C1. Исключения как штатный путь парсинга
`ExtraKeyButton.java:196–202`:
```java
public String getStringFromJson(JSONObject config, String key) {
    try { return config.getString(key); } catch (JSONException e) { return null; }
}
```
`JSONObject.getString` бросает `JSONException` при отсутствии ключа → на каждый
`{key: "ESC"}` это **2 исключения с `fillInStackTrace()`** (macro + display), на каждую
вложенную swipe-кнопку ещё столько же. Типовая раскладка 2×10 + popup-ы ≈
**150–300 исключений на каждый `new ExtraKeysInfo(...)`**, а он создаётся:
при старте, при каждом `reloadActivityStyling`, при каждом переключении таба
с сессионным профилем, при каждом `save()` в редакторе.

**Решение:** `config.optString(key, null)` — ноль исключений, −1 аллокация стек-трейса.
Ожидаемый эффект: парсинг раскладки в **2–4 раза** быстрее.

### C2. Три копии одной матрицы
`ExtraKeysInfo.java:138–171`: `JSONArray` → `Object[][]` (139–146) → `ExtraKeyButton[][]` (149–168).
Промежуточный `Object[][]` — чистые потери: можно сразу `arr.getJSONArray(i)` в цикле 149.

### C3. `isSameLayout` считается **после** полного парсинга
`TermuxTerminalExtraKeys.java:397–401` и `429–432`: сначала
`new ExtraKeysInfo(contextLayoutJson, ...)` (весь JSON + все исключения из C1),
потом `if (!isSameLayout(mExtraKeysInfo, newInfo))`. Гард экономит только `reload()` view,
но не парсинг — а именно он и есть дорогая часть.

**Решение:** сначала сравнить **строки** JSON (`mCurrentJson.equals(newJson)`), парсить
только при расхождении. Кэш «строка → ExtraKeysInfo» (`LruCache`, 4–8 шт.) даст
повторное переключение табов практически бесплатным.
Побочно убирает `ExtraKeysInfo previousInfo = mExtraKeysInfo;` (396) — неиспользуемая переменная.

### C4. Двойной rebuild при каждом `reloadActivityStyling`
`TermuxActivity.java:3871–3885`: `reloadExtraKeys()` внутри уже может дёрнуть
`applySessionLayout() → extraKeysView.reload()` (TermuxTerminalExtraKeys:436),
после чего строка 3884 **безусловно** вызывает `reload()` второй раз,
а 3892 — `setTerminalToolbarHeight()`. Итого: 2 полные пересборки сетки
(≈2×N `MaterialButton` + 2×2N лямбды) и 2 пересчёта высоты на один reload стиля.

**Решение:** `reloadExtraKeys()` возвращает `boolean` («уже перезагрузил»),
и 3884 выполняется только если `false`.

### C5. Редактор: файловый ввод-вывод на каждый чих
`ExtraKeysEditorFragment.rebuildPreview():810–824`:
* `ColorSchemeUtils.ensureColorSchemeForTheme(...)` → `getColorSchemeFileForTheme` +
  `loadTerminalColorScheme` → чтение и парсинг `colors.properties` **с диска**
  и мутация глобального `TerminalColors.COLOR_SCHEME`;
* два `getResources().getStringArray(...)` + сборка `Properties`;
* `new TermuxColorSchemeManager().recompute(mPrefs)`.

Всё это — на каждый слайдер и на каждое назначение сигнала.
**Решение:** единожды кэшировать схему в поле, инвалидировать по смене темы.

### C6. Редактор: двойная сборка JSON + повторный парс ради валидации
`handleSignalPickerResult():1171–1172` (и 1138–1139, 772–773) делают
`rebuildPreview()` — который уже вызывает `buildJsonMatrix()` (785) и
`new ExtraKeysInfo(json)` (793) — а затем `save()`, который снова вызывает
`buildJsonMatrix()` (1178) **и** `new JSONArray(json)` (1184) только чтобы проверить
то, что только что собрал сам.
**Решение:** `rebuildPreview()` возвращает JSON (или `save()` принимает его аргументом);
валидацию строкой убрать (`buildJsonMatrix` строит валидный `JSONArray`).

### C7. Редактор: полное пересоздание Activity на каждое изменение
`save():1204` → `TermuxActivity.updateTermuxActivityStyling(ctx, true)` →
`ACTION_RELOAD_STYLE` с `EXTRA_RECREATE_ACTIVITY=true` → `recreate()` всей Activity.
Одно назначение сигнала = пересоздание Activity + новая сетка кнопок + новый поток (B3).
**Решение:** `false` — для предпросмотра достаточно `reloadActivityStyling(false)`;
`recreate()` нужен только если меняется тема/ночной режим.

### C8. Гаптик-фидбек делает IPC-вызовы в `Settings` на каждое нажатие
`ExtraKeysView.performExtraKeyButtonHapticFeedback():1090–1118` вызывается из
`OnClickListener` (895) на **каждый тап и каждый свайп** и выполняет:
`Settings.System.getInt(resolver, HAPTIC_FEEDBACK_ENABLED, 0)` (1107) —
Binder-вызов в `SettingsProvider`; на API < 28 дополнительно
`Settings.Global.getInt(resolver, "zen_mode", 0)` (1114). Плюс
`TermuxAppSharedProperties.getProperties()` (1093) и `props.getExtraKeysHaptic()`.
Это десятки микросекунд–миллисекунд на каждое нажатие, в UI-потоке, ради значения,
которое меняется раз в год.
**Решение:** кэшировать `hapticMode` и системный флаг в поля, обновлять через
`ContentObserver` + при `reloadActivityStyling`.

### C9. Отрисовка индикаторов свайпа
`drawRuntimeEdgeIndicators()` (1693–1812) включён **по умолчанию**
(`mRuntimeEdgeIndicatorsEnabled = true`, 258) и выполняется в каждом `dispatchDraw`,
для каждого ребёнка делая до 4 вызовов `edgeColor()`, каждый из которых может
вызвать `isSpecialButton()` + `SpecialButton.valueOf()` (valueOf — поиск по массиву
enum с генерацией `IllegalArgumentException` при промахе; здесь безопасен только
благодаря предварительной проверке ключа).
**Решение:** кэшировать `RuntimeEdgeInfo` с уже разрезолвленными цветами,
пересчитывать при смене активных модификаторов, а не на каждый кадр;
`SpecialButton.valueOf` → статичная `HashMap<String, SpecialButton>`.

### C10. Прочие микро-аллокации
| Место | Что |
|---|---|
| `ExtraKeysInfo.java:226` | `getCharDisplayMapForStyle("none")` аллоцирует новый map на каждый вызов (в редакторе — дважды за rebuild) |
| `ExtraKeysView.java:530` | `keySet().stream().map(...).collect(toSet())` при каждой `setSpecialButtons` |
| `ExtraKeysView.java:522` | `getSpecialButtonsKeys()` копирует `HashSet` на каждый вызов |
| `ExtraKeysView.java:894/899` | 2 лямбды + captures **на каждую кнопку** на каждый `reload()` (≈80 объектов на rebuild) |
| `ExtraKeysView.java:556` | `applyColorsToExistingButtons` красит дважды (дети + `mSpecialButtons`) |
| `ExtraKeyButton.java:184–186` | `stream().map().collect(joining(" "))` на каждую кнопку при парсинге — заменить на `StringBuilder` |
| `ExtraKeysConstants.java:57–63` | `CleverMap.get(k, def)` = `containsKey` + `get` = 2 хэш-поиска |
| `ExtraKeysConstants.java:21–49` | `PRIMARY_KEY_CODES_FOR_STRINGS`: `containsKey` + `get` на каждое нажатие; `HashMap(24)` → `ArrayMap` / один `get` с проверкой null |
| `ExtraKeysConstants.java:75–210` | 10 статических карт через double-brace init → 10 анонимных классов + инициализация при загрузке класса |
| `TermuxTerminalExtraKeys.java:344–351, 371–377` | `.toLowerCase()` внутри цикла по ключам при каждом переключении таба |

---

## D. Архитектура

### D1. `ExtraKeysView` — God-объект (2194 строки)
Смешаны: сборка сетки, тач/свайп, тайминги автоповтора, гаптик, стейт-машина
модификаторов, **редакторские** жесты, Canvas-отрисовка, подбор размера шрифта,
обрезание макро-текста. ~15 % полей (`mEditor*`, `mEditorEdgePaint`, `mRuntimeEdgePaint`,
`mLpRunnable`, `mActiveChild`…) нужны только редактору, но живут в production-вью
и тянут за собой `dispatchDraw` с двумя ветками рисования.

**Предложение:** вынести в отдельные классы — `ExtraKeysGridBuilder` (reload),
`ExtraKeysGestureController` (тач/свайп/повтор, per-pointer состояние),
`ExtraKeysRenderer` (Canvas), `ExtraKeysTextFitter` (шрифт/обрезание);
редакторские жесты — в подкласс `ExtraKeysEditorView` либо в `ExtraKeysGestureController`
с флагом. Это же автоматически убирает дубль A2.

### D2. Редактор ходит кругом через JSON
`KeyCell[][] → buildJsonMatrix() → String → ExtraKeysInfo → ExtraKeyButton → view`
(785 → 793 → 836), и `save()` — ещё раз. Плюс `computeDisplay()` (1073–1085)
**дублирует** логику `ExtraKeyButton` (184–186), но с другим разделителем
(`"+"` вместо `" "`) — именно из-за этого расхождения существует эвристика
« legacy-auto » (924–949).
**Решение:** один общий композер display-текста в `ExtraKeyButton`; редактор
собирает `ExtraKeysInfo` напрямую из `KeyCell[][]` без строкового round-trip.

### D3. Три параллельных состояния «какая раскладка активна»
`TermuxTerminalExtraKeys`: `mCurrentContext` + `mCurrentSessionContext` + `mLastSessionName`,
приоритеты размазаны по 6 методам (274–468). При этом контекстная половина мертва (A1),
а приоритеты пересчитываются в `reloadExtraKeys()` (88–105), `onForegroundProcessChanged()`
(274) и `onSessionNameChanged()` (306) тремя разными способами.
**Решение:** единственный метод `resolveActiveLayout()` + одно поле
`ActiveLayout {source: DEFAULT|SESSION|PROCESS, key: String}`.

### D4. Что сделано **хорошо** (не трогать)
* Пул `MaterialButton` в `reload()` (799–843) — переиспользование вместо пересоздания. ✔
* Асимметричные margin-ы (717–725): только trailing-сторона, чтобы зазор не удваивался. ✔
* Бинарный поиск размера шрифта (1419–1445) + кэш `mCachedFittedFontSp`. ✔
* Условный `post()` только при пустом кэше (1076–1078). ✔
* `isSameLayout` как гард от лишних `reload()`. ✔ (но см. C3 — срабатывает слишком поздно)
* Токенайзер `BindingTokenizer` с поддержкой кавычек и DELAY — корректный и покрывает крайние случаи. ✔

---

## E. План по приоритету

**P0 — баги и потоки (мало кода, большой эффект)**
1. B1: автоповтор на main-thread `Handler`; `mLongPressCount` → atomic/main-only.
2. B2: фикс `setLongPressRepeatDelay` (одна строка).
3. B3: daemon-фабрика + `shutdownNow()` в `onDetachedFromWindow()`.
4. B7: индексы и границы в редакторе (координаты тегов, `openSignalPicker`, move-listener).
5. A4: решить — вызывать `onExtraKeyButtonGesturePress` или удалить API.

**P1 — убрать мёртвое (≈ −670 строк, −2 класса)**
6. A1: либо интегрировать `ExtraKeysContextWatcher` (~20 строк), либо удалить его
   вместе с `extra-keys-context*` и контекстной ветвью.
7. A2: удалить `ExtraKeysTouchHandler`.
8. A3: удалить `showPopup`/`dismissPopup`/`mPopupWindow`.

**P2 — стоимость парсинга и пересборки**
9. C1: `optString` вместо исключений (ожидаемо ×2–4 на парсинге).
10. C3: сравнение JSON-строк до парсинга + `LruCache` «строка → ExtraKeysInfo».
11. C4: убрать двойной `reload()` в `reloadActivityStyling`.
12. C8: кэш настроек гаптика (убрать IPC с каждого нажатия).

**P3 — редактор**
13. C5: кэш цветовой схемы; C6: убрать двойную сборку JSON; C7: `recreate=false`.
14. C10: микро-аллокации по таблице.

**P4 — структура**
15. D1: декомпозиция `ExtraKeysView`.
16. D2/D3: общий композер display + единое состояние активной раскладки.

---

## F. Ожидаемый суммарный эффект

| Направление | Эффект |
|---|---|
| `optString` + отказ от `Object[][]` + кэш по строке JSON | парсинг раскладки ×2–4 быстрее, −200…300 исключений на загрузку |
| Устранение двойного `reload()` | −1 полная пересборка сетки на каждый reload стиля |
| Кэш гаптика | −1…2 Binder-вызова на каждое нажатие кнопки |
| main-thread повторы | снятие гонки с эмулятором, −1 поток на инстанс |
| Кэш схемы в редакторе | −1 чтение/парсинг файла на каждое изменение |
| Удаление мёртвого кода | −668 строк, −2 класса, −1 поток на инстанс (A2), −244 строки неработающей фичи |
