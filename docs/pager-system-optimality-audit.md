# Аудит 3: система пейджера терминалов — цена, которая живёт не в кадре

**Дата:** 2026-09-13
**Предмет:** весь пейджер сессий (`ViewPager2` + `TerminalPagerAdapter` + `SessionPagerManager` +
`PagerOverscrollController` + пикер правого свайпа) — поиск работы, которую можно снять **без
изменения поведения**.
**Метод:** чтение кода + сверка с AOSP (`sources/android-36.1`: `View.java`, `ImageView.java`,
`TextView.java`, `ColorDrawable.java`) и с исходниками проекта (`TerminalView`, `TerminalRenderer`,
`TerminalEmulator`, `TerminalSession`, `ColorSchemeUtils`, `MonetOptions`, `PackageUtils`).
Замеров на устройстве в этом проходе **нет** — все выводы получены из кода.
**Предыдущие документы:** `docs/placeholder-picker-swipe-optimality-audit.md` (аудит 1),
`docs/placeholder-picker-swipe-optimality-audit-2.md` (аудит 2),
`docs/render-optimality-audit-{4,5}.md`, `docs/directory-picker-swipe-design.md`.

---

## 1. Короткий вывод

1. **Кадровый путь пейджера (свайп) уже разобран и почти пуст.** Аудиты 1–2 сняли с него всё
   дорогое: `measure+layout` иерархии, offscreen-буфер, пере-запись строк пикера, `getLocationOnScreen`
   на MOVE, `GONE`-переходы, запись `marginEnd`. На `onPageScrolled` осталось несколько десятков
   чтений полей, две `sin` в овердраге и пара записей свойств рендер-ноды, которые сами себя
   гасят по неизменившемуся значению (`View.setTranslationX`/`setAlpha` — early-out, проверено).
   **Новых рычагов там практически нет** (§6).
2. **Главная находка этого прохода — путь ПРИВЯЗКИ страницы (`onBindViewHolder`), а не кадр.**
   Один bind страницы сегодня стоит: **два лишних `TerminalRenderer`**, **один `createPackageContext`
   с созданием `Resources`**, **4–5 системных вызовов `stat` + сборка строки-ключа + `MonetOptions.load()`
   (Properties + объект + 7 чтений prefs)**, полный repaint страницы, ioctl на pty. Это в
   **десятки раз** больше работы, чем весь кадровый путь свайпа, — и происходит это ровно там,
   где пользователь её видит: привязка страницы-плейсхолдера приходит **через кадр после коммита,
   то есть внутри settle-анимации свайпа**, который только что открыл вкладку. См. §4, P1–P5.
3. **Найдена функциональная проблема, порождённая тем же путём.** `onEmulatorSet()` пишет
   `setAutoScrollDisabled()` в **активный** эмулятор, хотя вызывается из привязки **любой** страницы.
   Следствие: привязка фоновой страницы сбрасывает состояние «пользователь прокрутил вверх» у
   сессии, на которой он стоит, — и первый же вывод возвращает вьюпорт в низ. См. §5, C1.
4. **Пикер правого свайпа платит на каждом взводе плейсхолдера**, а не на жесте: `buildItems()` +
   `premeasure()` (до 10 шейпингов текста) выполняются в `bind()`, а `bind()` приходит в settle
   коммита. Кеш ширин уже есть — но он живёт **внутри View**, который при взводе берётся из пула
   заново. См. §4, P6.
5. **Структурный рычаг уровня дизайна:** плейсхолдер вставляется/удаляется на каждом оседании у
   последней вкладки (`notifyItemInserted`/`Removed` + полный bind + recycle). Постоянно взведённая
   страница убрала бы этот чурн целиком, но это поведенческое изменение — см. §7, S1 (наблюдение,
   не рекомендация).

---

## 2. Границы: что уже закрыто, а что нет

| Проход | Что разобрал | Итог |
|---|---|---|
| аудит 1 | путь «палец → пикер»: `measure+layout` иерархии, offscreen-буфер, пере-запись строк, шейпинг текста | внедрено (§4.1–4.6, 4.8) |
| аудит 2 | тот же путь после правок: `GONE`→`INVISIBLE`, layout-проход плавающей кнопки, коалесинг по кадру | N1/N2/N4 внедрено; N3 (коалесинг) и гейт §4.7 **откачены** |
| render 4–5 | путь отрисовки `TerminalRenderer` (скан строк → submit → GPU) | закрыто |
| **аудит 3 (этот)** | **путь ПРИВЯЗКИ страницы, путь КОММИТА, структурные свойства пейджера; кадровый путь — только остаточное** | см. §4–§7 |

Кадровый путь специально перепроверен ещё раз (§6) — не потому что там что-то найдено, а чтобы
зафиксировать: **дальнейшие усилия в нём не окупятся**, и следующий проход не должен начинать с него.

---

## 3. Карта: три пути исполнения пейджера

| Путь | Когда | Частота | Что там сегодня |
|---|---|---|---|
| **A. Кадр** (`onPageScrolled`) | драг + settle | 2–4 вызова на отображаемый кадр | ~30 чтений полей, 2 `sin`, 4–6 записей свойств рендер-ноды с early-out |
| **B. Привязка** (`onBindViewHolder`) | создание/переиспользование ViewHolder, re-arm плейсхолдера, коммит, add/remove вкладки | 1–3 на жест; **обязательно внутри settle коммита** | `TerminalRenderer` ×2, `createPackageContext` ×1, `stat` ×4–5, `MonetOptions.load()`, полный repaint, ioctl pty, `premeasure` пикера ×10 шейпингов |
| **C. Коммит** (`commitPlaceholderToSession`) | оседание на плейсхолдере | 1 на открытие вкладки | `fork()` новой сессии (неустранимо) + rebind + два `post()` |

**Вывод из карты:** путь B тяжелее пути A на порядок, и он же лежит внутри settle. Именно туда
стоит вкладываться.

---

## 4. Путь B — привязка страницы (главное)

### P1. Два лишних `TerminalRenderer` на каждый bind ⭐

`TerminalPagerAdapter.onBindViewHolder()` (`:492`) зовёт `terminalView.setTextSize(getFontSize())`,
а затем (`:509`) `checkForFontAndColorsForView(terminalView)` → `terminalView.setTypeface(...)`.
Оба сеттера устроены так:

```java
// TerminalView.java:1278
public void setTextSize(int textSize) {
    mRenderer = new TerminalRenderer(textSize, mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface);
    mRenderer.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);
    updateSize();
}
// TerminalView.java:1284
public void setTypeface(Typeface newTypeface) {
    mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface);
    ...
}
```

**Ни одного early-out.** При этом `TerminalRenderer` — не пустышка: конструктор (`:235`) создаёт
5 `Paint`, поле `SparseArray<Float> supplementaryMeasures`, `RenderKey`, три массива по 0x80
(`asciiMeasure`/`asciiWc`/`asciiMismatch` — 768 байт), делает 3 нативных вызова метрик
(`getFontSpacing`, `ascent`, `measureText("X")`) и прогоняет цикл на 128 итераций с `WcWidth.width()`
и `Math.abs`. Плюс дважды вызывается `updateSize()`.

Итого на **каждый** bind страницы: **~8 аллокаций, 6 нативных вызовов, 256 итераций цикла** —
и всё это выбрасывается, потому что ни размер шрифта, ни гарнитура не менялись.
На коммите это происходит внутри settle.

**Правка (безопасная):** тождественный early-out в обоих сеттерах.

```java
public void setTextSize(int textSize) {
    if (mRenderer != null && mRenderer.mTextSize == textSize) return;
    ...
}
public void setTypeface(Typeface newTypeface) {
    if (mRenderer != null && mRenderer.mTypeface == newTypeface) return;
    ...
}
```

**Почему это безопасно.** Оба сеттера — чистая функция от (size, typeface): рендерер не держит
ни сессии, ни состояния экрана. Существующий код уже сохраняет «вторую половину» пары
(`setTextSize` берёт typeface у текущего рендерера, `setTypeface` — размер), то есть композиция
`setTextSize(s)` + `setTypeface(t)` даёт ровно то же, что давала. `resolveTerminalTypeface()`
возвращает **кэшированный** экземпляр `Typeface`, поэтому сравнение по тождеству корректно.
`invalidate()`, который сейчас делает `setTypeface`, дублируется вызовом
`terminalView.invalidate()` строкой выше в `checkForFontAndColorsForView()`.
Реальный bind с новым размером/гарнитурой (смена настройки) по-прежнему пересоздаёт рендерер —
условие просто не выполняется.

### P2. `createPackageContext` на каждый bind ⭐

`TerminalView.updateSize()` в ветке «эмулятора нет или размер изменился» зовёт `mClient.onEmulatorSet()`
(`:755`). При bind `attachSession()` обнуляет `mEmulator`, поэтому ветка берётся **всегда**, и
`TermuxTerminalViewClient.onEmulatorSet()` (`:216`) делает:

```java
TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(mActivity, true);
```

`build(context, true)` → `TermuxUtils.getContextForPackageOrExitApp(...)` → `PackageUtils.getContextForPackage`
→ **`context.createPackageContext("com.termux", CONTEXT_RESTRICTED)`** (`PackageUtils.java:56`).
Это не «получить ссылку»: создаётся новый `ContextImpl`, выполняется IPC к PackageManager
(`getApplicationInfoAsUser`) и строится `Resources` (AssetManager + конфигурация). Порядок цены —
доли-единицы миллисекунды, и это на **каждый bind страницы**, то есть в том числе внутри settle.

**Правка:** читать уже кэшированные настройки активности —
`mActivity.getPreferences().isScrollOnNewOutputEnabled()`. `TermuxActivity` строит `mPreferences`
один раз в `onCreate()` (`TermuxActivity.java:657`), геттер — `:3916`. Fallback на `build(...)`
оставить только на случай `null`, что возможно лишь до `onCreate`.

### P3. `resolveSchemeKey()` — 4–5 `stat` и полный разбор Monet-опций на каждый bind

`checkForFontAndColorsForView()` (`TermuxTerminalSessionActivityClient:1280`) первой же строкой
зовёт `ensureColorSchemeLoaded(isNight, resolveSchemeKey(isNight))` — **аргумент вычисляется всегда**,
хотя гейт внутри сравнивает строку и в 99 % случаев ничего не делает. Что стоит эта строка
(`buildSchemeKey`, `:1167`):

| Что | Цена |
|---|---|
| `ColorSchemeUtils.getColorSchemeFileForTheme(isNight)` | `File.isFile()` — системный вызов |
| `colorsFile.lastModified()` + `.length()` | 2 системных вызова |
| `fontFile.lastModified()` + `.length()` | 2 системных вызова |
| `getSelectedSchemeName(mActivity, isNight)` | чтение prefs (кэш `sPreferences` — ок) |
| `MonetOptions.load()` | **`new Properties()` (Hashtable + таблица), 7 чтений prefs, парсинг, новый объект `MonetOptions`** |
| `ColorSchemeUtils.monetToken(isNight)` | volatile-чтение |
| конкатенация 7 частей | `StringBuilder` + `String` |

Итого **4–5 системных вызовов + ~4 аллокации + ~8 чтений prefs** на каждый bind — ради того, чтобы
обнаружить, что ничего не изменилось. `warmUpMonet()` при этом дёшев: он выходит сразу, если выбран
не Monet.

**Правка:** кэшировать собранный ключ и пересобирать его только по триггеру, а не по факту вызова.
Триггеры, которые уже есть в коде: `invalidateAppliedScheme()` (`:1205`, вызывается после recreate и
при явной перезагрузке стиля), смена night mode (config change) и listener на prefs. Плюс дешёвый
throttle (например, не чаще раза в 1 с) — он закрывает единственный оставшийся сценарий: файл
`colors.properties` отредактирован внешним приложением, пока Termux на переднем плане.

### P4. Сброс палитры и полный repaint страницы на каждый bind

Тот же `checkForFontAndColorsForView()` **безусловно** (то есть и когда ключ не изменился):

```java
emulator.mColors.reset();          // TerminalColors.reset(): System.arraycopy(defaults → current)
terminalView.invalidate();
terminalView.onScreenUpdated();    // → repaintAfterUpdate() → полный/частичный repaint
```

**Две вещи здесь.**

* **Цена.** `onScreenUpdated()` на привязываемой странице — это полный проход отрисовки. Для
  страницы-плейсхолдера (эмулятора нет) он выходит рано, для реальной — нет.
* **Поведение.** `TerminalColors.reset()` (`TerminalColors.java:28`) копирует в `mCurrentColors`
  **дефолты глобальной схемы**. Значит, любая динамическая палитра, выставленная программой через
  OSC 4/11/12, **стирается при каждой перепривязке страницы**. Это противоречит намерению, явно
  записанному в `TerminalPagerAdapter.applyPlaceholderColors()`: «`getCurrentTerminalColor()` — это
  то, что заставляет плейсхолдер совпадать с живым терминалом, **включая OSC 4/11 динамические
  цвета**». К моменту чтения палитра уже сброшена, если перед этим была хоть одна привязка.

**Правка:** выполнять сброс и repaint только когда ключ схемы **действительно** сменился (то есть
внутри ветки, где `ensureColorSchemeLoaded` реально загрузил палитру). Это и снимает цену с
bind-пути, и возвращает OSC-цветам жизнь. Требует проверки глазами (§9).

### P5. `JNI.setPtyWindowSize` на каждый bind

`TerminalView.updateSize()` зовёт `mTermSession.updateSize(cols, rows, fontWidth, lineSpacing)`
всегда, когда `mEmulator == null` (то есть на каждом bind), а `TerminalSession.updateSize()`
(`:104`) **не сравнивает** размер с текущим: делает `JNI.setPtyWindowSize(...)` (ioctl на pty) и
`mEmulator.resize(...)`. `TerminalEmulator.resize()` early-out по совпадению rows/columns имеет
(`:422`), так что reflow не происходит; ядро, по-видимому, тоже не шлёт SIGWINCH при неизменном
размере — но сам ioctl остаётся.

**Правка (низкий приоритет):** в `TerminalView.updateSize()` сравнивать с
`mTermSession.getEmulator()` (`mRows`/`mColumns`) и звать `mTermSession.updateSize(...)` только при
реальном изменении, а присваивание `mEmulator` + `mClient.onEmulatorSet()` оставить как есть.

### P6. `premeasure()` пикера на пути settle ⭐

`DirectoryPickerController.bind()` (`:118`) на **каждом** bind страницы-плейсхолдера делает:

```java
buildItems();              // clear + обход истории + до 10 add
view.premeasure(mItems);   // measureInto(): measureText() на каждый из до 10 путей
```

`measureText` — шейпинг текста, самая дорогая операция во всём пикере (именно её аудит 1 §4.5
выносил из кадра `show()`). Комментарий в `bind()` утверждает, что это происходит «пока страница
в покое», но **взвод плейсхолдера приходит через кадр после коммита**, то есть внутри settle
свайпа, которым только что открыли вкладку. Кеш ширин при этом не переиспользуется: он живёт в
`DirectoryPickerView`, а при взводе страница берётся из пула заново (после коммита слот занят
реальной сессией), поэтому `isMeasuredPrefix()` в `setItems()` уже не спасает.

**Правка:** поднять кеш ширин на уровень `DirectoryPickerController` (он живёт столько же, сколько
пейджер) и передавать его во `View`; либо, как минимум, пропускать `premeasure()`, если список
истории не изменился с прошлого bind (сигнатура: ссылка на список + размер + первая/последняя
запись). Второе — правка в 5 строк и полностью снимает шейпинг с settle-пути.

### P7. Мелочи пути B (проверено, не стоит отдельной правки)

| Что | Вердикт |
|---|---|
| `hint.setBackgroundColor(Color.TRANSPARENT)` в трёх ветках `onBindViewHolder` | **мёртвый код.** Первый вызов ставит `ColorDrawable`; дальше `View.setBackgroundColor` видит `mBackground instanceof ColorDrawable` и мутирует его, а `ColorDrawable.setColor` early-out по неизменившемуся цвету (`ColorDrawable.java:147`). Можно удалить вместе с комментариями-пояснениями — эффекта на экран нет, читаемость выше. |
| `applyPlaceholderHintColors()` → `setColorFilter(fg)` + `setTextColor(fg)` | `ImageView.setColorFilter(int)` **всегда** аллоцирует `PorterDuffColorFilter` (`ImageView.java:1533`), `TextView.setTextColor` — `ColorStateList.valueOf` (кэш) + `updateTextColors()`. Один раз на взвод плейсхолдера. Дешёвая правка: запоминать последний `fg` в холдере/адаптере и выходить при совпадении. |
| `getPagerRecyclerView()` — `(RecyclerView) mTerminalPager.getChildAt(0)` | 4 вызова, ни один не в кадре. Можно закешировать в поле в `setup()`, выигрыш нулевой. |
| `registerForContextMenu` / `registerTerminalViewFocusListener` / `setKeepScreenOn` на bind | сеттеры слушателей и `setFlags` с early-out — норма. |

---

## 5. Путь C — коммит и оседание

### C1. Привязка страницы сбрасывает «прокручено вверх» у АКТИВНОЙ сессии ⭐ (функциональная)

`TermuxTerminalViewClient.onEmulatorSet()` (`:216`) — общий клиент, он привязан ко **всем** страницам
(`onCreateViewHolder`/`onBindViewHolder`), и параметра «какая вьюха» у колбэка нет. Поэтому метод
работает с активной вьюхой:

```java
TerminalView terminalView = mActivity.getTerminalView();
if (terminalView != null && terminalView.mEmulator != null) {
    TermuxAppSharedPreferences prefs = ...;
    boolean disabled = !prefs.isScrollOnNewOutputEnabled();
    terminalView.mEmulator.setAutoScrollDisabled(disabled);   // ← всегда перезаписывает
}
```

То есть **bind фоновой страницы переписывает флаг `autoScrollDisabled` у эмулятора той сессии,
на которой стоит пользователь.** При дефолтной настройке `scroll-on-new-output = вкл` это значит
`setAutoScrollDisabled(false)` — флаг сбрасывается.

Дальше в `TerminalView.onScreenUpdated()` (`:925`) ветка «следовать за выводом» берётся ровно
тогда, когда флаг **снят**, и делает `mTopRow = 0`:

```java
} else if (isSelectingText() || mEmulator.isAutoScrollDisabled()) {
    ...                       // держим вьюпорт на месте
}
if (!skipScrolling && mTopRow != 0) { ... mTopRow = 0; }   // ← прыжок в низ
```

**Сценарий, где это видно:** пользователь прокручен в историю сессии A; происходит привязка
страницы (добавление/закрытие вкладки → `notifyItemRangeInserted/Removed`; возврат из фона →
`syncTerminalPagerToService`; взвод плейсхолдера при оседании на последней вкладке) → флаг A
сброшен → первый же вывод на A возвращает вьюпорт в низ.

**Правка:** у колбэка нет получателя, поэтому нужен один из двух путей —
(a) `TerminalView.updateSize()` передаёт себя в клиент (перегрузка `onEmulatorSet(TerminalView)`), и
клиент пишет флаг только в тот эмулятор, который действительно появился; либо
(b) адаптер на время bind выставляет на клиенте «вьюха, которую сейчас привязываем», и клиент
сверяет её с активной. Вариант (a) чище и не требует состояния.
Заодно это делает ветку «обновить маргин кнопки» ниже в том же методе осмысленной: она тоже
адресована активной вьюхе, но там это как раз и нужно (маргин считается для активной страницы).

**Обязательно проверить на устройстве** (правило проекта): прокрутить сессию вверх, открыть/закрыть
вкладку, дождаться вывода — вьюпорт не должен прыгать в низ.

### C2. `commitPlaceholderToSession()` — проверено, оптимизировать нечего

Разобраны все шаги (`:629`–`:719`): `createSessionForPlaceholder()` (fork — неустранимо),
`picker.beginRowFadeOut()`, `fadeOutPlaceholderOverlay()` **до** `commitPlaceholder()` (порядок
обязателен — см. память проекта), in-place rebind через payload, `post()` с re-arm плейсхолдера и
`onTerminalPageSelected`. Аллокации: `String` каталога, замыкание `post`, `Consumer` для полосы
вкладок — по одному на открытие вкладки. Трогать нечего.

### C3. Чурн плейсхолдера на каждом оседании у последней вкладки

`managePlaceholderForPosition()` (`:605`) взводит плейсхолдер при оседании на последней реальной
вкладке и снимает при уходе с неё. Каждое — `notifyItemInserted/Removed` → проход layout у
RecyclerView → bind/recycle ViewHolder'а. Стоимость bind'а плейсхолдера описана в P6 (плюс P1–P3
не применяются: эмулятора у него нет, `attachSession` не зовётся, значит `onEmulatorSet` не летит).
Это не ошибка — это цена выбранного дизайна; вариант её убрать — §7, S1.

---

## 6. Путь A — кадровый путь: остаточное и снятое с подозрения

### Q1. `revealFor()` считается дважды за один колбэк

`applyOverlayReveal()` (`:891`) и следом `shouldLatchAnchor()` (`:927`) независимо зовут
`revealFor(position, offset)`, а он внутри читает `getPlaceholderOverlayPage()`. Правка: посчитать
один раз в `onPageScrolled` и передать дальше. Экономия — единицы наносекунд на MOVE; ценности
почти нет, но это убирает дублирование источника истины.

### Q2. `setPlaceholderScrollOffset()` пере-выводит `getParent()`/`getWidth()` каждый кадр

`TerminalPagerAdapter:771-775`: `(View) hintContent.getParent()` и `parent.getWidth()` на каждый
колбэк. Ширина родителя не меняется за время жеста (страница `match_parent`, пейджер full-bleed),
поэтому её можно защёлкнуть на первом кадре жеста и сбрасывать на `IDLE` — тот же приём, что уже
применён к `mPagerLocation` для `getLocationOnScreen()` (`SessionPagerManager:199`, §4.6 аудита 1).
Выигрыш мал; ценность в симметрии с уже принятым решением.

### Q3. Квантование визуальных значений (узкий вариант аудита 2 §3.3) — всё ещё открыт

Аудит 2 оставил этот путь отката как «не внедрён». Он по-прежнему актуален: квантовать альфу
контейнера оверлея по шагу 1/255 и `translationX` подсказки по целому пикселю. `View.setAlpha` и
`View.setTranslationX` имеют early-out по неизменившемуся значению (проверено, `View.java:19799`,
`:20324`), поэтому квантование **не меняет порядок применения** (урок аудита 2: ломает именно
откладывание записи) и лишь убирает инвалидацию предков на кадрах, где визуально ничего не
изменилось. Ожидаемый эффект скромный (десятки процентов на кадрах медленного драга), риск — низкий.

### Q4. Снято с подозрения в этом проходе

| Что | Вердикт |
|---|---|
| `TermuxSessionTabsController.onPageScrolled` | 5 early-out, `setTabBackground` уже гасит неизменившийся цвет (`:1253`), `HorizontalScrollView.scrollTo` клампит и выходит по int. **Норма.** |
| `withTabsController(mScrollForwarder)` | `getTermuxSessionTabsController()` — чтение поля (`TermuxActivity:3843`), лямбда — поле, без боксинга. **Норма.** |
| `updateFloatingButtonMarginForScroll` | 2 × `getPagerPageView` (`SparseArray.get`), 2 × `computeSettledFloatingButtonMarginEnd` → `hasScrollbar` = чтение поля `mActiveTranscriptRows`; запись идёт `setTranslationX` с early-out. **Норма** (дублирование вызовов отмечено ещё аудитом 2, ценность кеша нулевая). |
| `PagerOverscrollController` | `apply()` читает ширину один раз на кадр; `setTranslationX` рантайм-узла с early-out; `bleedIntoScroll` выходит на `mTranslationPx == 0`; на обычном свайпе не исполняется вообще (дельта потребляется целиком). **Норма.** |
| `ensureSettled()` → `post(settleNow)` | один `post` на `SCROLL_STATE_IDLE`, то есть на жест. **Норма.** |
| `onPageScrollStateChanged(IDLE)` → 2 `post` | один раз на жест; `updateFloatingButtonMargin()` и сброс полосы вкладок обязательны. **Норма.** |
| `TerminalPagerAdapter.getItemId()` | вызывается RecyclerView на каждый layout для каждого видимого холдера; внутри — чтение поля + `String.hashCode()` (кэширован). **Норма.** |
| `mAttachedViews` (`SparseArray`) и его сдвиг в `syncWithServiceList` | линейно по числу привязанных страниц (≤3), и только на add/remove. **Норма.** |

---

## 7. Структурные наблюдения (не рекомендации)

### S1. Постоянно взведённый плейсхолдер убрал бы чурн целиком

Сейчас страница-плейсхолдер живёт только пока пользователь стоит на последней реальной вкладке.
Причина в комментарии — «чтобы правый свайп всегда имел настоящую соседнюю страницу». Но
**удалять** её для этого не требуется: плейсхолдер стоит в конце списка, поэтому правый свайп с
промежуточной вкладки и так попадает в следующую реальную, а не в него; коммит же и так гейтится
`position == getPlaceholderIndex()` в `onPageSelected`. Постоянно взведённая страница убрала бы
`notifyItemInserted/Removed`, bind и recycle на каждом оседании (C3 + P6).

**Почему это только наблюдение.** Проверить придётся как минимум: семантику
`TermuxSessionTabsController.setPlaceholderActive()` (она управляет блендингом последней вкладки в
кнопку «+»), поведение `updatePagerUserInputEnabled()` (счёт страниц), и всю ветку «программный
скролл на слот плейсхолдера» в `onPageSelected`, которая сегодня существует именно из-за совпадения
индекса плейсхолдера с индексом только что добавленной сессии. Это поведенческое изменение, а не
оптимизация, и делать его вслепую нельзя.

### S2. `setOffscreenPageLimit(1)` — оставить

Стоимость — второй живой `TerminalView` (свой `TerminalRenderer`, свои `Paint`) и его layout.
Выгода — «живой» сосед под пальцем, ради чего пейджер и делался. Компромисс осознанный.

### S3. `setHasFixedSize(true)` на RecyclerView пейджера — не рекомендую без отдельной проверки

Страницы `match_parent`, то есть размер пейджера от содержимого не зависит, и флаг формально
применим. Но `ViewPager2` его не ставит, а взаимодействие с `PagerSnapHelper` и с
`ScrollEventAdapter` при вставке/удалении плейсхолдера не проверено. Выигрыш — один пропущенный
`onMeasure` на структурное изменение, то есть мало. Соотношение риск/выгода плохое.

---

## 8. Реестр предлагаемых правок

| # | Файл | Правка | Риск | Что даёт |
|---|---|---|---|---|
| P1 | `TerminalView` | early-out в `setTextSize()` / `setTypeface()` | **низкий** — чистая функция от (size, typeface); реальное изменение по-прежнему пересоздаёт рендерер | −2 `TerminalRenderer`, −6 нативных вызовов, −256 итераций на каждый bind |
| P2 | `TermuxTerminalViewClient` | `mActivity.getPreferences()` вместо `TermuxAppSharedPreferences.build(...)` | **низкий** — тот же объект, построен в `onCreate` | −1 `createPackageContext` (+IPC +Resources) на каждый bind |
| P3 | `TermuxTerminalSessionActivityClient` | кэш `buildSchemeKey()` + триггеры (`invalidateAppliedScheme`, смена night mode, listener prefs) + throttle | **средний** — нужно не пропустить реальную смену схемы | −4–5 `stat`, −~4 аллокации, −8 чтений prefs на каждый bind |
| P4 | `TermuxTerminalSessionActivityClient` | сброс палитры и repaint только при реальной смене ключа | **средний** — поведенческий: OSC-цвета перестанут стираться | снимает полный repaint страницы с bind; заодно лечит расхождение с намерением из `applyPlaceholderColors()` |
| P5 | `TerminalView` | не звать `mTermSession.updateSize()` при совпадении размера | **низкий** | −1 ioctl на pty на каждый bind |
| P6 | `DirectoryPickerController` / `DirectoryPickerView` | поднять кеш ширин на уровень контроллера **или** пропускать `premeasure()` при неизменившейся истории | **низкий** | −до 10 шейпингов текста с settle-пути коммита |
| P7 | `TerminalPagerAdapter` | удалить `setBackgroundColor(TRANSPARENT)`; кеш последнего `fg` в `applyPlaceholderHintColors` | **низкий** | читаемость + 1 аллокация на взвод |
| C1 | `TerminalView` + `TermuxTerminalViewClient` | передавать в `onEmulatorSet` вьюху-источник и писать `setAutoScrollDisabled` только в её эмулятор | **средний** — функциональная правка, нужна проверка на устройстве | чинит сброс «прокручено вверх» при bind фоновой страницы |
| Q1/Q2 | `SessionPagerManager` / `TerminalPagerAdapter` | посчитать `reveal` один раз; защёлкнуть ширину родителя оверлея на жест | **низкий** | гигиена, выигрыш наносекундный |
| Q3 | `SessionPagerManager` | квантовать альфу оверлея (1/255) и `translationX` подсказки (1 px) | **низкий** — не трогает порядок применения | меньше инвалидаций предков на медленном драге |

**Пути отката** — все правки независимы и локализованы; ни одна не меняет структуру пейджера,
`SessionPagerManager` и `PagerOverscrollController` не трогаются вообще (кроме Q1/Q2/Q3).

**Что НЕ делать** (записано, чтобы не наступить снова): откладывать **запись** визуального
состояния оверлея на кадр (`postOnAnimation`). Аудит 2 §3.3: settle-анимация `ViewPager2` питается
теми же `onPageScrolled`-колбэками, поэтому «позже» = «не синхронно со страницей», и это видно на
отпускании пальца.

---

## 9. Что проверять глазами (release, по правилу проекта)

1. **P1** — смена размера шрифта в настройках по-прежнему меняет шрифт во всех страницах (включая
   фоновую), смена гарнитуры через Termux:Style — тоже. Отдельно: страница, взятая из пула после
   другой с размером шрифта, перерисовывается правильно.
2. **P2/P3** — смена схемы (Default ↔ Monet ↔ Monet-вариант), смена темы день/ночь, смена
   `colors.properties` внешним редактором: всё должно применяться с первого кадра, без «применилось
   только после переключения вкладки».
3. **P4** — программа, выставляющая OSC 4/11 (например, скрипт с `printf '\033]4;1;#ff0000\007'`),
   затем переключение вкладки туда-обратно: цвет должен **остаться** (сегодня — стирается).
   И наоборот: смена схемы должна по-прежнему перекрашивать всё.
4. **P6** — свайп на последней вкладке, открывающий новую: подёргивания/задержки на коммите быть не
   должно; список каталогов появляется на первом кадре раскрытия.
5. **C1** — прокрутить сессию в историю, открыть и закрыть вкладку, дождаться вывода: вьюпорт **не
   должен** прыгнуть в низ. Затем то же с оседанием на последней вкладке (взвод плейсхолдера).
6. **Q3** — медленный драг на плейсхолдер и обратно: оверлей не должен отставать или дёргаться
   (это ровно тот симптом, из-за которого откатили коалесинг).
7. **Общее.** Для чисел — `dumpsys gfxinfo <pkg> framestats`, интересны `Prepare`/`Draw`; базовая
   линия — свайп между двумя реальными вкладками. Отдельно полезно снять `framestats` вокруг
   **открытия вкладки** (это путь B, а не кадровый) — именно там ожидается основной эффект.

---

## 10. Порядок работ

| Приоритет | Пункт | Ожидаемый эффект | Риск |
|---|---|---|---|
| 1 | **P1** — early-out рендерера | самый дешёвый и самый крупный выигрыш на bind | низкий |
| 2 | **P2** — кэшированные prefs в `onEmulatorSet` | −`createPackageContext` на bind | низкий |
| 3 | **P6** — не шейпить подписи пикера на settle | −10 `measureText` с коммита | низкий |
| 4 | **C1** — адресовать `setAutoScrollDisabled` источнику | функциональная правка | средний, нужна проверка |
| 5 | **P3** — кэш ключа схемы | −4 `stat` + аллокации на bind | средний |
| 6 | **P4** — сброс палитры только при смене | −полный repaint на bind + OSC-цвета | средний |
| 7 | **P5, P7, Q1, Q2** | гигиена | низкий |
| 8 | **Q3** — квантование | меньше инвалидаций на медленном драге | низкий |
| — | **S1** — постоянный плейсхолдер | убирает чурн целиком | **поведенческое, требует отдельного дизайна** |

**Ключевой вывод для следующих проходов:** кадровый путь пейджера исчерпан — там остались
наносекунды. Всё дорогое, что в пейджере ещё есть, живёт на **привязке страницы** и приходит
**внутри settle-анимации жеста**. Следующий замер имеет смысл делать не на свайпе, а вокруг
открытия вкладки.

---

## 11. Что реализовано (проход внедрения)

Все пункты §8 внедрены, кроме **Q2** — см. ниже. Итог: 10 файлов, 4 модуля, `assembleRelease`
собирается.

| # | Где | Что сделано |
|---|---|---|
| P1 | `TerminalView` | identity early-out в `setTextSize()` (`mRenderer.mTextSize == textSize`) и `setTypeface()` (сравнение по ссылке — типография кэширована вызывающим). Пара вызовов по-прежнему пересоздаёт рендерер, если меняется любая половина: первый сохраняет текущий typeface, второй — текущий размер |
| P2 | `TermuxTerminalViewClient` | `mActivity.getPreferences()` с откатом на `build(...)` только при `null`. Runnable для маргина кнопки вынесен в поле и связывается в конструкторе |
| P3 | `TermuxTerminalSessionActivityClient` | `resolveSchemeKey()` мемоизирован (`mCachedSchemeKey` + `mCachedSchemeKeyIsNight` + `SCHEME_KEY_MAX_AGE_MS = 1000`). `invalidateAppliedScheme()` сбрасывает и ключ, и `sCachedTypefaceCheckedAtMs`. Проверка шрифта троттлится через `TYPEFACE_KEY_MAX_AGE_MS = 1000` |
| P4 | `TermuxTerminalSessionActivityClient` | `ensureColorSchemeLoaded(...)` возвращает `boolean` (реально ли перезагружена палитра); `mColors.reset()` + `invalidate()` + `onScreenUpdated()` выполняются **только** при `true`. `setTypeface()` остаётся безусловным — он теперь дёшев благодаря P1 |
| P5 | `TerminalEmulator` + `TerminalView` | новый `TerminalEmulator.hasSize(cols, rows, cellW, cellH)`; `updateSize()` зовёт `mTermSession.updateSize(...)` только если эмулятор сессии `null` или размер не совпал |
| P6 | `DirectoryPickerView` | `setLabelTextSize()` выходит по неизменившемуся размеру и **очищает** кеш ширин вместо `measureInto(mItems)`; `premeasure()` выходит через `isMeasuredPrefixOf(items)`; `isMeasuredPrefix()` делегирует новой `isMeasuredPrefixOf(List)` |
| P7 | `TerminalPagerAdapter` | три `setBackgroundColor(TRANSPARENT)` удалены (ветка `mPlaceholderFadingOut` стала пустой); `applyPlaceholderHintColors` под guard'ом |
| C1 | `TerminalView` + `TerminalViewClient` + `TermuxTerminalViewClientBase` + `TermuxTerminalViewClient` | сигнатура `onEmulatorSet(TerminalView)`; `TerminalView.updateSize()` передаёт `this`; `setAutoScrollDisabled` пишется в эмулятор **источника** |
| Q1 | `SessionPagerManager` | `reveal` считается один раз в `onPageScrolled` и передаётся в `applyOverlayReveal(float)` / `shouldLatchAnchor(float, float)` |
| Q3 | `TerminalPagerAdapter` | `setTranslationX(Math.round(...))`; `parent.setAlpha(quantizeAlpha(...))` (`Math.round(a*255f)/255f`); удалён вечный no-op `setAlpha(1f)` |
| — | `TerminalView` | javadoc-ссылки `onEmulatorSet()` → `onEmulatorSet(TerminalView)` в `TerminalView`, `TermuxActivity` |

### Q2 — внедрено, затем откатано (обоснование)

Защёлкивание ширины родителя оверлея (по образцу `mPagerLocation` в `SessionPagerManager:199`)
было написано — ключ по `hintContent`, чтобы перечитывать ширину и для затухающего оверлея коммита,
и для перевзведённого плейсхолдера — и **снято** при проверке. Причина конкретная и решающая:

`TermuxActivity` объявляет
`android:configChanges="orientation|screenSize|smallestScreenSize|density|screenLayout|…"`,
то есть поворот и ресайз в split-screen **не пересоздают** активити. Адаптер, `hintContent` и
защёлка переживают это, а ширина пейджера меняется — защёлка держала бы устаревшую ширину и
кривила положение оверлея до конца следующего жеста. Довод §6 о «симметрии с уже принятым
решением» не работает: `getLocationOnScreen()` обходит цепочку родителей, а `getWidth()` — это два
чтения поля. Два чтения поля не стоят риска устаревшего состояния, поэтому ширина читается вживую.

Остальная часть Q3 (округление `translationX` и квантование альфы) сохранена: там устаревания нет,
а выигрыш реальный — квантование снимает перезапись display list предков на кадрах, где визуально
ничего не изменилось.

### Урок, зафиксированный этим проходом

Guard «не применять то же значение» обязан жить **на том же объекте, что и само значение**, если
объект переиспользуется. Кеш последнего `fg` для подсказки плейсхолдера был сначала полем адаптера
— и это была бы ошибка: свежесозданный `ViewHolder` несёт тинт **разметки**
(`?attr/colorOnSurface`, не палитру терминала), а холдер-плейсхолдер меняется на каждом коммите
(закоммиченный слот становится реальной страницей, хвостовой плейсхолдер взводится на другом
холдере). Кеш адаптера увидел бы «уже применено» и оставил бы новый плейсхолдер в цвете темы.
Кеш перенесён в `TerminalPageViewHolder` (`mHintFg` / `mHintFgValid`).
