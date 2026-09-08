# Анализ оптимальности системы переключения вкладок

Область анализа: `TermuxSessionTabsController`, `SessionPagerManager`, `TerminalPagerAdapter`,
`TermuxTerminalSessionActivityClient`, `TermuxActivity.termuxSessionListNotifyUpdated`,
`TermuxSessionSnapshotManager`, `TerminalSession` / `TerminalEmulator`.

Все выводы проверены по исходникам. Ни одно предложение не меняет наблюдаемое поведение —
только убирает работу, результат которой заведомо идентичен.

---

## 0. Узлы конвейера (кто кого вызывает)

**A. Переключение вкладки (тап / свайп / хоткей)** — горячий, но низкочастотный путь:

```
SessionPagerManager.onPageSelected(pos)
  → managePlaceholderForPosition(pos)          // SP-чтение + isAtMaxSessions()
  → onTerminalPageSelected(pos)
      ├─ saveTextInputForCurrentSession()
      ├─ mActivity.setTerminalView(pageView)
      ├─ tabs.setCurrentSession(pos)           // applyTabSelectionState() → setTabBackground() ×N
      └─ client.onSessionPageSelected(session)
            ├─ checkForFontAndColors()         ★ САМАЯ ДОРОГАЯ ОПЕРАЦИЯ (см. P0-1)
            ├─ updateBackgroundColor()         // повторный applySystemBarColors()
            ├─ applyTextInputVisibilityForSession()
            ├─ mActivity.getCurrentSessionCwd()// readlink /proc/<pid>/cwd на UI-потоке
            ├─ recordCurrentDirectory / onHistoryDirectoryChanged
            └─ applySessionExtraKeys()         // reloadSessionMap() — чтение с диска
```

**B. OSC-заголовок (спиннер/прогресс в CLI)** — 10–30 Гц, должен быть почти бесплатным:

```
TerminalSession.titleChanged()  [output-поток]
  → client.onTitleChanged()     [runIfVisible — НЕ постит в UI-поток!]
      → scheduleTitleRefresh()  → Handler.post
          → tabs.refreshTabAppearance() → populateTabView() ×N  (diff через TabRenderState)
          → applySessionExtraKeys(session, false)
```

**C. Структурное изменение списка сессий** (add/remove/rename):

```
TermuxActivity.termuxSessionListNotifyUpdated(preferredIndex)
  → tabs.updateTabs()                     // create/remove + populate ×N
  → SessionPagerManager.termuxSessionListNotifyUpdated()
      → adapter.syncWithServiceList()     // инкрементальные notify* (правильно)
      → setCurrentItem(restoreIndex,false) → onTerminalPageSelected() (путь A)
  → saveSessionSnapshot()                 ★ N × readlink + JSON + SP.apply()
```

---

## P0. Критично: полный пересчёт темы на каждое переключение вкладки

**Файл:** `TermuxTerminalSessionActivityClient.java:516-525` → `:1021` → `applyTerminalColorScheme():1066`

`onSessionPageSelected()` безусловно вызывает `checkForFontAndColors()`. Один вызов выполняет:

| Что | Стоимость |
|---|---|
| `getColorSchemeFileForTheme()` | `File.isFile()` → stat() |
| `loadTerminalColorScheme()` | `FileInputStream` + `Properties.load()` — чтение с диска |
| `recomputeUIColors()` | чтение prefs + пересчёт палитры |
| `applyPanelColors()` | ~8 `findViewById`, 6+ аллокаций `GradientDrawable`/`StateListDrawable`, `tintSelectionHandles`, `setScrollbarColors`, `setTextSelection*`, `applyStatusBarTheme` |
| `tabs.applySchemeColorsToTabs()` | **→ `invalidateRenderStateCache()`** (см. P0-2) |
| `mActivity.applySchemeColors()` | decorView bg + window flags + `setStatusBarColor`/`setNavigationBarColor` (IPC в WM) |
| `emulator.mColors.reset()` | сброс динамических OSC-цветов |
| `updateBackgroundColor()` | **второй** `applySystemBarColors()` за тот же вызов |
| `terminalView.invalidate()` + `onScreenUpdated()` | полная перерисовка терминала |
| `fontFile.exists()` + `.length()` | 2 stat() |
| `Typeface.createFromFile(fontFile)` | парсинг TTF с диска (при кастомном шрифте) |

Ни один из этих шагов не зависит от того, *какая* вкладка выбрана. Это работа уровня
«смена темы», а она выполняется на каждый тап по вкладке.

### Предложение P0-1a. Гейт по ключу схемы

Кэшировать ключ последнего применённого состояния и пропускать весь блок при совпадении:

```java
// TermuxTerminalSessionActivityClient
private String mAppliedSchemeKey = null;

private String buildSchemeKey(boolean isNight) {
    File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
    File fontFile  = TermuxConstants.TERMUX_FONT_FILE;
    return isNight
        + "|" + (colorsFile == null ? "-" : colorsFile.lastModified() + ":" + colorsFile.length())
        + "|" + (fontFile.exists() ? fontFile.lastModified() + ":" + fontFile.length() : "-");
}

/** Тяжёлый путь — только при реальной смене темы/схемы/шрифта. */
public void checkForFontAndColors() {
    final boolean isNight = TermuxActivity.isNightModeActive();
    final String key = buildSchemeKey(isNight);
    if (key.equals(mAppliedSchemeKey)) return;
    mAppliedSchemeKey = key;
    applyTerminalColorScheme(isNight);
}
```

Сброс `mAppliedSchemeKey = null` оставить в `onReloadActivityStyling()` и в обработчике
смены day/night — либо просто положиться на то, что ключ изменится сам (файл схемы
перезаписывается стилем, `isNight` меняется). Поведение идентично: пока схема, тема и
шрифт не менялись, пересчитывать нечего.

### Предложение P0-1b. На переключении вкладки вызывать только лёгкую часть

`onSessionPageSelected()` нужны ровно две вещи из этого конвейера: перепривязка шрифта к
новой (возможно только что созданной) странице и отражение живого фона в system bars.
Полный `checkForFontAndColors()` там нужен только из-за recreate (комментарий в коде это
подтверждает). Разделить:

```java
// onSessionPageSelected()
if (mActivity.isActivityRecreated()) checkForFontAndColors();   // редкий путь
else updateBackgroundColor();                                   // дешёвый путь
```

`checkForFontAndColors()` продолжит вызываться из `onCreate()`, `onStart()` (при recreate),
`onReloadActivityStyling()` и `onServiceConnected()` — т.е. во всех точках, где схема
действительно могла измениться.

### Предложение P0-1c. Убрать дублирующий `applySystemBarColors()`

`applyTerminalColorScheme()` в конце вызывает `updateBackgroundColor()`, а та снова делает
`applySystemBarColors(...)` — тот же вызов уже был выполнен внутри `applyPanelColors()` →
`applyStatusBarTheme()`. Один из двух удалить.

---

## P0-2. `applySchemeColorsToTabs()` обнуляет diff-кэш вкладок

**Файл:** `TermuxSessionTabsController.java:995-1025`

`TabRenderState` — хорошая оптимизация:Diff не переприменяет атрибуты, которые не изменились.
Но `applySchemeColorsToTabs()` в конце вызывает `invalidateRenderStateCache()`, и этот метод
вызывается **на каждом переключении вкладки** (через P0-1). Итог: кэш живёт ровно до первого
свайпа, после чего следующий `refreshTabAppearance()` делает для всех вкладок полный
`populateTabView()` — `setMaxLines`, `setPadding`, `setText`, `setMaxWidth`, `setLayoutParams`,
`setTextColor`, `setColorFilter`, `setPaintFlags`, `setSelected`, `setBackground`
(с аллокацией `GradientDrawable`) — со всеми `requestLayout()`.

### Предложение

Не инвалидировать, а **обновить** кэш — благо `applySchemeColorsToTabs` уже сам красит все
вкладки. Добавить в `TabRenderState` поля `textColorApplied` и `bgValid`, и синхронизировать их:

```java
public void applySchemeColorsToTabs(int textColor, int bg, int bgActive) {
    mSchemeTextColor = textColor; mSchemeBg = bg; mSchemeBgActive = bgActive;
    mSchemeApplied = true;
    if (mTabsContainer == null) return;

    for (int i = 0; i < getTabCount(); i++) {
        View tabView = getTabAt(i);
        // ... существующая покраска (без изменений) ...
        int desiredBg = tabView.isSelected() ? bgActive : bg;
        setTabBackground(tabView, desiredBg);

        TabRenderState state = (TabRenderState) tabView.getTag(R.id.session_tab_render_state_tag);
        if (state != null) {
            state.bgColor = desiredBg;
            state.bgValid = true;
            state.textColorApplied = textColor;   // новое поле
        }
    }
    applyAddButtonSchemeBackground();
    // invalidateRenderStateCache();  ← убрать
}
```

и в `populateTabView` завести diff по `textColorApplied` вместо `freshState`-условия для
цветов. Поведение то же (цвета применены здесь и сейчас), кэш остаётся валидным.

---

## P0-3. `TerminalPagerAdapter.onBindViewHolder()` → `checkForFontAndColorsForView()`

**Файл:** `TerminalPagerAdapter.java:317` → `TermuxTerminalSessionActivityClient.java:1030-1063`

На каждый бинд страницы (создание ViewHolder **и** каждый rebind при возврате страницы в
offscreen-окно во время свайпа) выполняется: 2 stat() (шрифт) → возможный
`Typeface.createFromFile()` → `Properties.load()` схемы с диска → `emulator.mColors.reset()`
→ `setTypeface()`.

### Предложение

* Кэшировать `Typeface` в статическом поле по ключу `(path, lastModified, length)` —
  шрифт не меняется между биндами.
* Кэшировать загруженную схему так же, как в P0-1a (общий ключ).
* `emulator.mColors.reset()` вызывать только если схема реально перезагружена.
* Дополнительно: `holder.itemView.findViewById(...)` вызывается в каждом бинде
  (`terminal_placeholder_hint_container`, `terminal_placeholder_hint_content`, и ещё 3 внутри
  placeholder-ветки) — вынести в `TerminalPageViewHolder`, заполняемые один раз в
  `onCreateViewHolder`.

---

## P1. `setTabBackground()` — аллокация drawable на каждый кадр свайпа

**Файл:** `TermuxSessionTabsController.java:1210-1216`, вызывается из `onPageScrolled():1143-1144`

```java
private void setTabBackground(View view, int color) {
    GradientDrawable d = new GradientDrawable();     // ← новая аллокация
    d.setShape(GradientDrawable.RECTANGLE);
    d.setCornerRadius(mActivity.getResources().getDimension(R.dimen.terminal_tab_corner_radius));
    d.setColor(color);
    view.setBackground(d);                            // ← новая привязка + invalidate
}
```

`onPageScrolled` вызывает её **дважды за кадр** — итого 2 аллокации + 2 `setBackground`
(с полным invalidate drawable state) + 2 `getDimension()` на каждый кадр свайпа (60–120 Гц).
Плюс по одному разу на вкладку в `applyTabSelectionState()`.

### Предложение

Держать по одному `GradientDrawable` на вкладку в `TabRenderState` и менять только цвет:

```java
private static final class TabRenderState {
    // ...
    GradientDrawable bgDrawable;   // переиспользуется
}

private void setTabBackground(View view, int color) {
    TabRenderState st = (TabRenderState) view.getTag(R.id.session_tab_render_state_tag);
    GradientDrawable d = null;
    if (st != null) {
        d = st.bgDrawable;
        if (d != null && st.bgColor == color && view.getBackground() == d) return; // уже ок
    }
    if (d == null) {
        d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(mTabCornerRadiusPx);   // закешированное значение
        if (st != null) st.bgDrawable = d;
    }
    d.setColor(color);                            // invalidate внутри drawable
    if (view.getBackground() != d) view.setBackground(d);
}
```

`d.setColor()` сам вызывает `invalidateSelf()`, визуально identично. Corner radius —
`float mTabCornerRadiusPx`, посчитанный один раз в конструкторе (значение из ресурсов
не зависит от конфигурации во время работы).

---

## P1. `isSingleLineMode()` — два чтения SharedPreferences на вкладку на каждый refresh

**Файл:** `TermuxSessionTabsController.java:138-141`, вызывается из `populateTabView():417` и `:427`

```java
private boolean isSingleLineMode() {
    return !"double".equals(mActivity.getSharedPreferences("termux_prefs", MODE_PRIVATE)
            .getString("tab_height_mode", "single"));
}
```

`populateTabView` вызывает его дважды. При 8 вкладках и 30 Гц OSC-трафике это
~500 синхронизированных чтений SP в секунду. Плюс на каждый вызов:
`getResources().getDimension()` ×4 (`pad_start`, `close_gap`, `title_max_width`,
и `corner_radius` в `setTabBackground`).

### Предложение

Закешировать в полях контроллера, инвалидировать там же, где меняется:

```java
private boolean mSingleLineMode = true;
private int mPadStartPx, mCloseGapPx, mTitleMaxWidthPx, mTabHeightPx, mCornerRadiusPx;
private boolean mDimensCached = false;

private void ensureDimens() {
    if (mDimensCached) return;
    mDimensCached = true;
    mSingleLineMode  = !"double".equals(prefs().getString("tab_height_mode", "single"));
    Resources r = mActivity.getResources();
    mPadStartPx      = Math.round(r.getDimension(mSingleLineMode
                        ? R.dimen.terminal_tab_padding_start_single
                        : R.dimen.terminal_tab_padding_start_double));
    mTabHeightPx     = Math.round(r.getDimension(mSingleLineMode
                        ? R.dimen.terminal_tab_height_single
                        : R.dimen.terminal_tab_height_double));
    mCloseGapPx      = Math.round(r.getDimension(R.dimen.terminal_tab_close_gap));
    mTitleMaxWidthPx = Math.round(r.getDimension(R.dimen.terminal_tab_title_max_width));
    mCornerRadiusPx  = r.getDimensionPixelSize(R.dimen.terminal_tab_corner_radius);
}
```

`mDimensCached = false` выставлять в `applyTabHeightMode()` и (на всякий случай) при
`onReloadActivityStyling()`. Сейчас `applyTabHeightMode()` всё равно уже читает эти же
значения и уже вызывает `invalidateRenderStateCache()` — место инвалидации готово.

Аналогично `SessionPagerManager.isPlaceholderEnabled():351` — читает SP на каждом
`onPageSelected`; кэшировать boolean с инвалидацией по `OnSharedPreferenceChangeListener`.

---

## P1. `isTitleTruncated()` — `StaticLayout` на каждое изменение заголовка

**Файл:** `TermuxSessionTabsController.java:540-554`

Вызывается на каждый `titleChanged` (то есть 10–30 раз/с для анимированного заголовка).
`new StaticLayout(...)` — это построение полного текстового layout (аллокация массивов
строк, измерение глифов). Для коротких заголовков (`bash`, `vim`, `~`) это впустую.

### Предложение — быстрый reject

```java
private boolean isTitleTruncated(TextView tv, String text, int maxWidthPx) {
    if (text == null || text.isEmpty()) return false;
    if (maxWidthPx <= 0) return false;
    // Быстрый путь: одна строка гарантированно влезает → эллипсиса нет.
    // StaticLayout нужен только когда текст реально длиннее доступной ширины.
    if (tv.getPaint().measureText(text) <= maxWidthPx) return false;
    // ... существующий StaticLayout-код ...
}
```

`measureText` дешевле StaticLayout на порядок и correctness не меняет: если текст уже
у́же доступной ширины, он не может быть обрезан ни по строкам, ни по эллипсису.

---

## P1. `findViewById` в горячих циклах

| Место | Частота |
|---|---|
| `populateTabView():372-373` (`session_tab_title`, `session_tab_close`) | 2 × N вкладок × до 30 Гц |
| `onPageScrolled():1147-1148` | 2 × каждый кадр свайпа |
| `applySchemeColorsToTabs():1006-1007` | 2 × N вкладок на каждое переключение |
| `applyTabSelectionState():1060` | 1 × N вкладок на каждое переключение |
| `TerminalPagerAdapter.onBindViewHolder():242,262,251,253` | 2–5 на каждый бинд |

### Предложение

Сохранять ссылки один раз:
* вкладки — в `TabRenderState` (поля `titleView`, `closeButton`), заполнять в `createTabView()`;
* страницы пейджера — в `TerminalPageViewHolder`, заполнять в конструкторе.

Это чистый выигрыш: `findViewById` — обход дерева, а ссылка уже есть.

---

## P1. `runIfVisible()` не переводит выполнение в UI-поток (корректность + гонки)

**Файл:** `TermuxTerminalSessionActivityClient.java:175-177, 190-215`

```java
private void runIfVisible(java.lang.Runnable action) {
    if (mActivity.isVisible()) action.run();     // ← выполняется на вызывающем потоке
}
```

`onTitleChanged()` приходит с output-потока терминала
(`TerminalSession.titleChanged()` → `mClient.onTitleChanged(this)`, без пост-обработки).
Внутри:

* `tabs.scrollStripToEnd()` — обращение к `HorizontalScrollView`/`LinearLayout` из фонового потока;
* `termuxSessionListNotifyUpdated()` → `updateTabs()` → `addView()`/`removeViews()`/`setText()`
  → `requestLayout()` → `ViewRootImpl.checkThread()` → **`CalledFromWrongThreadException`**.

Дополнительно `TerminalEmulator.mTitle` (строка 133) — обычное, не `volatile`, поле:
UI-поток читает его через `getTitle()` без синхронизации.

### Предложение

```java
private void runIfVisible(java.lang.Runnable action) {
    if (!mActivity.isVisible()) return;
    if (Looper.myLooper() == Looper.getMainLooper()) action.run();
    else mMainHandler.post(action);          // mMainHandler уже есть в классе
}
```

Это не «оптимизация ради оптимизации»: помимо снятия риска краша, исчезает гонка
`mPendingEndScrollSession` (сейчас флаг пишется из UI-потока, а читается/сбрасывается из
output-потока) и устраняется смысловая нагрузка с `mTitleRefreshPending`, который сейчас
выставляется из фонового потока без какой-либо синхронизации.

---

## P2. Мусор и аллокации

### P2-1. Мёртвая ветка в payload-оверлоаде

`TerminalPagerAdapter.java:331-338`:

```java
public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position,
                             @NonNull List<Object> payloads) {
    if (!payloads.isEmpty()) {
        onBindViewHolder(holder, position);
    } else {
        onBindViewHolder(holder, position);   // ← то же самое
    }
}
```

Обе ветки идентичны. Заменить на один вызов `onBindViewHolder(holder, position);`
(комментарий сверху оставляем — он объясняет, *почему* переопределение нужно).

### P2-2. `HashMap<Integer, TerminalView>` + линейный поиск

`TerminalPagerAdapter`: `mAttachedViews` — `HashMap` с боксингом `int → Integer`
на каждый `put/get` (а `put` идёт в каждом бинде), а `onViewRecycled()` ищет позицию
линейным проходом по `entrySet()`.

```java
private final SparseArray<TerminalView> mAttachedViews = new SparseArray<>();
```

Снять боксинг; для обратного поиска в `onViewRecycled` хранить позицию в самом
`TerminalPageViewHolder` (поле `int boundPosition = -1;`) — тогда удаление O(1) без
аллокации итератора.

### P2-3. Лямбда-аллокации в `onPageScrolled`

`SessionPagerManager.java:220`:

```java
withTabsController(tabs -> tabs.onPageScrolled(position, positionOffset));
```

Захватывающая лямбда → новый объект `Consumer` на **каждый кадр** свайпа (60–120/с),
плюс boxing `positionOffset` в `Float`. Вариант без изменения архитектуры — закопить
последние значения в поля и использовать незахватывающую лямбду (она инстанцируется один
раз и JIT/ART её кеширует):

```java
private int   mLastScrollPos;
private float mLastScrollOffset;

// в onPageScrolled:
mLastScrollPos = position; mLastScrollOffset = positionOffset;
withTabsController(tabs -> tabs.onPageScrolled(mLastScrollPos, mLastScrollOffset));
```

(поле + незахватывающая ссылка на `this` — аллокация один раз на класс).

### P2-4. `applyPanelColors()` аллоцирует 6+ drawable за вызов

Уже покрывается P0-1: после гейта метод перестанет вызываться на каждое переключение.
Если гейт по какой-то причине не применять — минимум: создавать `StateListDrawable`
один раз и переиспользовать, меняя цвета через `mutate()`.

---

## P3. Диск и IPC

### P3-1. `saveSessionSnapshot()` на каждое структурное обновление

**Файл:** `TermuxActivity.java:2951` → `TermuxSessionSnapshotManager.java:64-113`

На каждый add/remove/rename сессии: `N × terminal.getCwd()`
(`File("/proc/<pid>/cwd/").getCanonicalPath()` — readlink) + сборка `JSONArray`/`JSONObject`
+ `SharedPreferences.apply()` (асинхронная, но полная перезапись XML с fsync).
Здесь же, внутри цикла, ещё и `getTermuxSession()`/`getExecutionCommand()`.

**Предложение:** debounce. Снапшот нужен для холодного старта и всё равно пишется в
`onStop()`; задержка в 300–500 мс ни на что не влияет:

```java
private final Runnable mSaveSnapshotRunnable = () -> mSessionSnapshotManager.saveSessionSnapshot();

public void saveSessionSnapshot() {
    mHandler.removeCallbacks(mSaveSnapshotRunnable);
    mHandler.postDelayed(mSaveSnapshotRunnable, 400);
}
public void saveSessionSnapshotNow() {           // для onStop()
    mHandler.removeCallbacks(mSaveSnapshotRunnable);
    mSessionSnapshotManager.saveSessionSnapshot();
}
```

`onStop()`/`onPause()` перевести на `saveSessionSnapshotNow()`. При серийном закрытии
вкладок (Exit из уведомления) получится один запрос вместо N.

### P3-2. `getCwd()` на UI-потоке при переключении вкладки

**Файл:** `TermuxTerminalSessionActivityClient.java:558`

```java
String cwd = mActivity.getCurrentSessionCwd();   // readlink /proc/<pid>/cwd
mActivity.recordCurrentDirectory(cwd);
mActivity.onHistoryDirectoryChanged(cwd);
```

Комментарий в коде это acknowledges («resolved here once and passed down»), но обе
потребителя — история каталогов для попапа «новая вкладка» и история сообщений —
допускают асинхронность. Вынести в `Executors.newSingleThreadExecutor()` с публикацией
результата через `runOnUiThread`, либо кэшировать cwd с TTL ~1 с (cwd шелла меняется
только по `cd`, и история всё равно приблизительная).

### P3-3. `applySessionExtraKeys(session)` → `reloadSessionMap()` с диска

На каждое переключение вкладки (`onSessionPageSelected():563`) перечитывается
property-файл `extra-keys-session`. Переключение вкладки — не тот момент, когда этот
файл мог измениться. Оставить `reloadMap=true` только для `renameSession()` и
`onStart()`, а в `onSessionPageSelected()` звать
`applySessionExtraKeys(session, /*reloadMap=*/false)` (дешёвая in-memory ветка с
guard по `mLastExtraKeysSessionName` уже реализована).

---

## P4. Мелочи

* **`updatePagerUserInputEnabled()` вызывается дважды подряд** в
  `termuxSessionListNotifyUpdated()` (строки 777 и 799) — второй вызов нужен (после
  пере-arm'а placeholder'а), первый можно убрать, оставив вызов до
  `managePlaceholderForPosition()` только там, где он реально влияет
  (`setUserInputEnabled(false)` как safety-net перед синхронизацией — да, оставить).
  Это не баг, а один лишний `setUserInputEnabled` за обновление.
* **`item_session_tab.xml`**: `android:focusable="true"` у `ImageButton` — кнопка закрытия
  участвует в фокус-обходе внутри горизонтальной ленты. Если это не нужно для TV/клавиатуры,
  `focusable="false"` убирает лишний узел фокуса (проверить на Android TV!).
* **`mPageScrollSuppressed`** растёт как счётчик, но нигде не читается — мёртвое поле,
  комментарий обещает «log once». Удалить или реализовать лог.
* **`ensureActiveTabVisible()`** — публичный метод, но ниоткуда не вызывается (grep по
  `main/` даёт только объявление). Мёртвый код ~40 строк с собственным `ValueAnimator`.
* **`getTabCount()` вычисляется как `getChildCount() - 1`** и вызывается как условие цикла
  в `refreshTabAppearance`/`applySchemeColorsToTabs`/`applyTabSelectionState` →
  `getChildCount()` на каждой итерации. Закешировать в локальную переменную.

---

## Сводка по приоритету

| # | Где | Суть | Ожидаемый эффект |
|---|---|---|---|
| P0-1 | `onSessionPageSelected → checkForFontAndColors` | гейт по ключу схемы / разделение тяжёлого и лёгкого пути | убирает ~15 тяжёлых операций (диск, TTF, IPC, рестайл, перерисовку) с каждого переключения вкладки |
| P0-2 | `applySchemeColorsToTabs → invalidateRenderStateCache` | обновлять кэш вместо сброса | diff-кэш `TabRenderState` начинает реально работать; убирает полный relayout всех вкладок после каждого свайпа |
| P0-3 | `onBindViewHolder → checkForFontAndColorsForView` | кэш Typeface/схемы, ViewHolder-ссылки | дешёвый бинд страниц, нет парсинга TTF при свайпе |
| P1 | `setTabBackground` | переиспользовать `GradientDrawable` | −2 аллокации и −2 `setBackground` на кадр свайпа |
| P1 | `isSingleLineMode` / dimens | кэш в полях | −~500 SP-чтений и −~1000 `getDimension` в секунду при OSC-трафике |
| P1 | `isTitleTruncated` | быстрый reject через `measureText` | −10…30 `StaticLayout` в секунду на анимированной вкладке |
| P1 | `findViewById` в циклах | ViewHolder-ссылки | убирает обход дерева из горячих циклов |
| P1 | `runIfVisible` | `runOnUiThread` | снимает `CalledFromWrongThreadException` и гонку `mPendingEndScrollSession` |
| P2 | payload-оверлоад, `HashMap`, лямбды | чистка | меньше аллокаций в кадре |
| P3 | `saveSessionSnapshot`, `getCwd`, `reloadSessionMap` | debounce/async/кэш | убирает дисковые операции и readlink с UI-потока |

Ни один пункт не меняет порядок применения, не убирает существующие guard'ы
(`mEndScrollActive`, `mScrollSeq`, `mTerminalPageSwitchInProgress`, `done[]`) и не трогает
контракты `markPendingEndScrollSession` / `scrollStripToEnd` / `commitPlaceholder`.
