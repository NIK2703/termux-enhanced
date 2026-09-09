# Прозрачный фон терминала с настоящими обоями устройства

**Статус:** проектное предложение (design doc), код не написан.
**Проект:** `E:\projects\termux-enhanced` (форк Termux)
**Target по API:** прозрачность — Android 5.0+ (`minSdk 21`); системный блюр — **только** Android 12+ (`API 31`), своей реализации для старых версий нет.
**Зависимость от Termux:Styling:** нет.
**Связанные документы:** `docs/monet-terminal-scheme-design.md` (там обои используются как *источник цветов*, здесь — как *подложка*; фичи независимы и дополняют друг друга).

---

## 1. Постановка задачи

| Требование | Формулировка |
|---|---|
| T1 | Фон терминала прозрачный, сквозь него видны **обои устройства** |
| T2 | Это должны быть **настоящие обои системы**, а не битмап-копия (`WallpaperManager.getDrawable()` **не** подходит — см. §2.1) |
| T3 | Опция **системного блюра** (Android 12+). Свою реализацию блюра для старых версий **не писать** |
| T4 | Прозрачность настраивается в диапазоне **0% … 50%** |
| T5 | При **0%** отображение обоев под терминалом **полностью отключено** |
| T6 | Блюр — на выбор: **в реальном времени** (`live`, системный) или **запечённый** (`baked`, считается один раз и кэшируется) |
| T7 | В режиме `live` для **статичных** обоев блюр не должен пересчитываться: пока терминал не выдаёт кадр, стоимость равна нулю (§10.7) |

Проектные решения, принятые сразу:

* **Диапазон 0–50%** — это «прозрачность» в пользовательских терминах. В коде она превращается в альфу фона: `alpha = 255 * (100 - percent) / 100`, т.е. 0% → `alpha=255` (непрозрачно), 50% → `alpha=128`.
* **0% — это не «чуть-чуть обоев», а выключенная фича**: подложка под терминалом не рисуется, рендерер работает ровно как сегодня (ни одного лишнего `if` на горячем пути). Это принципиально: платить за композитинг обоев должны только те, кто включил фичу.
* **Значение по умолчанию — 0%** (текущее поведение не меняется ни для одного существующего пользователя).
* **Два режима блюра (T6) — это два разных механизма, а не «качество» одного и того же:**

  | | `live` | `baked` |
  |---|---|---|
  | Что под терминалом | **настоящие** обои (wallpaper-surface системы) | **снапшот/производная** от обоев (битмап) |
  | Чем размывается | `FLAG_BLUR_BEHIND` в SurfaceFlinger | `android.graphics.RenderEffect` в нашем процессе |
  | Когда считается | каждый скомпонованный кадр | **один раз**, результат кэшируется |
  | Нужен `FLAG_SHOW_WALLPAPER` | да | **нет** |
  | Нужна полупрозрачная поверхность окна | да | **нет** |
  | Нужен `recreate()` при смене режима | да (тема статична) | **нет** |
  | Зависит от `isCrossWindowBlurEnabled()` | да | **нет** |
  | Стоимость в покое | 0 (если нет кадров, §10.7) | 0 (всегда) |
  | T2 (настоящие обои) | ✅ соблюдается | ❌ **нарушается сознательно** |

  `baked` — это **отдельный, явно выбранный пользователем режим**, а не фолбэк и не значение по умолчанию. Подробно — §10.
* **⚠️ `baked` по-разному доступен на разных версиях Android** — это надо заложить в дизайн сразу. Единственный способ получить битмап обоев — `WallpaperManager.getDrawable()`, а его поведение менялось трижды (`WallpaperManager.java:1046-1068`):

  | Условие | `getDrawable()` даёт |
  |---|---|
  | Есть `MANAGE_EXTERNAL_STORAGE` (Termux его объявляет и умеет запрашивать — `PermissionUtils.java:342`) | **настоящие обои, на всех версиях** |
  | API ≤ 32 + `READ_EXTERNAL_STORAGE` выдан | настоящие обои |
  | API 33 (Android 13), разрешения нет | **дефолтные обои вместо пользовательских** (молча — самое опасное поведение); на части сборок `SecurityException` |
  | API 34+ (Android 14), разрешения нет | **`SecurityException` всегда** |
  | В любом случае, если стоят **живые** обои | встроенную дефолтную картинку, а не кадр живого wallpaper (`WallpaperManager.java:1087-1089`) |

  Вывод: `baked` может быть точной копией только при выданном `MANAGE_EXTERNAL_STORAGE`, и **никогда** не воспроизведёт живые обои. Поэтому у режима два источника: снапшот (когда доступен) и **всегда рабочая производная из `WallpaperColors`** (§10.5). Без этого `baked` на Android 13/14 у большинства пользователей молча показывал бы не их обои.
* **Гарантия T7 для `live` достигается не кэшем, а отсутствием кадров.** Пересчёт блюра привязан не к «изменились ли обои», а к «компонуется ли кадр вообще» (§10.6). Это проверяемое свойство; §10.7 сводит его к чек-листу, §10.8 — к способу измерения.

---

## 2. Как Android вообще показывает обои под окном приложения

Это ключевой раздел: вся фича — это последовательное «пробивание» четырёх слоёв, каждый из которых по отдельности полностью перекрывает обои.

```
┌─ SurfaceFlinger ─────────────────────────────────────────────────┐
│  [0] Wallpaper surface   ← включается флагом FLAG_SHOW_WALLPAPER │
│  [1] Window surface      ← должна быть формата TRANSLUCENT       │
│  [2] Decor view bg       ← windowBackground / setBackgroundColor  │
│  [3] TerminalView        ← TerminalRenderer.drawColor(SRC)        │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1 `FLAG_SHOW_WALLPAPER` — единственный способ получить настоящие обои

```java
// android/view/WindowManager.java
/** Window flag: ... */
public static final int FLAG_SHOW_WALLPAPER = 0x00100000;
```

Этот флаг говорит WindowManager: «сделай моё окно wallpaper-target, держи wallpaper-surface живой и рисуй её подо мной».

Почему нельзя скопировать обои:

| Подход | Вердикт |
|---|---|
| `WallpaperManager.getDrawable()` / `getBuiltInDrawable()` | **Нет, и не только из-за T2.** Прямо в javadoc `WallpaperManager.java:1046-1068`: до Android 12 метод требует `READ_EXTERNAL_STORAGE`; **начиная с Android 13** он возвращает *дефолтные* обои вместо пользовательских (на части версий — `SecurityException`); **с Android 14 «should not be used and will always throw a `SecurityException`»**. Исключение — приложения с `MANAGE_EXTERNAL_STORAGE`, но это отдельное разрешение, которое пользователь включает руками. Плюс это **снимок**: не обновляется, не показывает живые обои, ест память на full-screen битмап. |
| `FLAG_SHOW_WALLPAPER` | Настоящая wallpaper-surface, композит делает SurfaceFlinger. Живые обои продолжают анимироваться. Память не расходуется в приложении. |

Дополнительно, в javadoc к флагу прямо описан побочный эффект:

> *When this flag is set, all touch events sent to this window is also sent to the wallpaper, which is used to interact with live wallpapers… When showing sensitive information on the window… use `LayoutParams#setWallpaperTouchEventsEnabled(boolean)`.*

Это важно для терминала (сторонний live-wallpaper не должен получать координаты нажатий), см. §9.3.

### 2.2 Формат поверхности окна: от `windowIsTranslucent` до `PixelFormat.TRANSLUCENT`

Обои не будут видны, если поверхность окна непрозрачна. Цепочка в AOSP (проверено по `sources/android-36.1`):

1. Тема: `PhoneWindow.java:2648`
   ```java
   mIsTranslucent = a.getBoolean(R.styleable.Window_windowIsTranslucent, false);
   ```
2. Полупрозрачное окно заставляет корневую view запросить «прозрачные регионы»: `ViewRootImpl.java:5288`
   ```java
   public void requestTransparentRegion(View child) {
       if ((mView.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0) {
           mView.mPrivateFlags |= View.PFLAG_REQUEST_TRANSPARENT_REGIONS;
           mWindowAttributesChanged = true;   // переоценить атрибуты окна
       }
   }
   ```
3. А при relayout — `ViewRootImpl.java:3833`:
   ```java
   if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0
           && !PixelFormat.formatHasAlpha(params.format)) {
       params.format = PixelFormat.TRANSLUCENT;
   }
   ```

**Следствие:** `android:windowIsTranslucent` — это **статический атрибут темы**. Переключить его «на лету» без пересоздания Activity нельзя → переключение 0% ↔ >0% требует `recreate()` (что и так уже делает существующий `updateStyling()`).

Альтернатива/подстраховка: `Window.setFormat(PixelFormat.TRANSLUCENT)` (`Window.java:1252`) пишет `attrs.format` напрямую и работает в рантайме. В `ViewRootImpl` TRANSLUCENT **форсируется** только если формат ещё не имеет альфы — явно выставленный `TRANSLUCENT` сохраняется. **Рекомендуется делать и то, и другое** (тема — основной путь, `setFormat` — страховка на случай OEM-правок).

### 2.3 Фон decor view

`PhoneWindow` вешает `android:windowBackground` на decor view. В `Theme.TermuxActivity.DayNight.NoActionBar` это `@color/white` — **непрозрачный белый** (`themes.xml:58`). Пока он там, обои не видны nunca (кроме того, `DecorView` считает `mDefaultOpacity` по фону — `DecorView.java:1807-1859`).

Поверх этого `TermuxActivity.applySystemBarColors()` делает `decorView.setBackgroundColor(surfaceBackground)` — **второй** непрозрачный слой (см. §3).

### 2.4 Фон самого терминала

Даже с прозрачным окном `TerminalRenderer` заливает **весь** canvas непрозрачным цветом схемы. Это последний барьер и самый важный (именно он определяет «процент прозрачности»).

---

## 3. Инвентаризация: что конкретно мешает сейчас

| # | Барьер | Файл : строка | Что мешает |
|---|---|---|---|
| B1 | Нет `FLAG_SHOW_WALLPAPER` | `TermuxActivity.java` (нет ни одного вхождения) | Обои под окном не рисуются вообще |
| B2 | Тема непрозрачна | `app/src/main/res/values/themes.xml:53-85` — `android:windowBackground=@color/white`, без `windowIsTranslucent` | Формат поверхности `OPAQUE` |
| B3 | decor view красится цветом схемы | `TermuxActivity.java:2697` — `decorView.setBackgroundColor(surfaceBackground)` | Непрозрачная подложка под всем содержимым |
| B4 | Рендерер заливает canvas непрозрачно | `TerminalRenderer.java:245-253` — `canvas.drawColor(bgColor, PorterDuff.Mode.SRC)` / `drawRect(dirtyRect, mBgPaint)` | Перекрывает всё, что под `TerminalView` |
| B5 | `TerminalView.isOpaque()` = `true` | `TerminalView.java:970-973` | Врёт фреймворку о том, что view — полный перекрыватель |
| B6 | Заглушка без эмулятора тоже непрозрачна | `TerminalView.java:1986-1991` — `canvas.drawColor(COLOR_SCHEME…BACKGROUND)` | Чёрная/белая вспышка на recreate вместо обоев |

Хорошая новость: **в разметке нет ни одного непрозрачного фона над терминалом** — проверено:

* `item_terminal_page.xml` — корень `FrameLayout` с `android:background="@android:color/transparent"`, внутри один `TerminalView` на `match_parent`.
* `activity_termux.xml` — `session_tabs_container`, `terminal_toolbar_container`, `activity_termux_bottom_space_view` — все `@android:color/transparent`; собственные фоны есть только у `new_session_tab_button`, `toggle_text_input_button` и `text_input_container`, и они **должны** остаться непрозрачными.
* `TermuxActivityRootView` не переопределяет `onDraw` и не задаёт фон.

И ещё одна удача: `TerminalRenderer` **уже** пропускает заливку ячеек с фоном «по умолчанию» —

```java
// TerminalRenderer.java:368-371 (pass A: фоны ран-блоков)
final int backColor = mRunBackColor[i];
if (backColor == palette[TextStyle.COLOR_INDEX_BACKGROUND]) continue;
```

— то есть прямоугольники рисуются **только** для ячеек с явно заданным цветом фона. Значит, достаточно «прозрачить» один-единственный базовый слой, и всё остальное сойдётся само (см. §6.3).

---

## 4. Архитектура решения

```
                      ┌──────────────────────────────────────────┐
   Settings screen    │ DisplayPreferencesFragment               │
   (0–50%, blur) ───► │  • configureSeekBarInt(...)              │
                      │  • configureSwitch(...)                  │
                      │  • updateStyling() → recreate            │
                      └───────────────┬──────────────────────────┘
                                      │ TermuxAppSharedPreferences
                                      ▼
                      ┌──────────────────────────────────────────┐
                      │ TermuxAppSharedProperties (mProperties)  │
                      └───────────────┬──────────────────────────┘
                                      │
                      ┌───────────────┴──────────────────────────┐
                      │ TermuxActivity                           │
                      │  • тема (windowIsTranslucent) ── recreate│
                      │  • FLAG_SHOW_WALLPAPER / touch-events    │
                      │  • blur behind (API 31+)                 │
                      │  • decor bg = transparent (если >0%)     │
                      │  • SessionPagerManager ──┐               │
                      └──────────────────────────┼───────────────┘
                                                 ▼
                      ┌──────────────────────────────────────────┐
                      │ TerminalPagerAdapter (mAttachedViews)    │
                      │  → TerminalView.setBackgroundTransparency│
                      └───────────────┬──────────────────────────┘
                                      ▼
                      ┌──────────────────────────────────────────┐
                      │ TerminalRenderer.mBackgroundAlpha        │
                      └──────────────────────────────────────────┘
```

Принципы:

1. **Одна точка истины по значению** — `TermuxAppSharedPreferences`, как и у всех остальных настроек. Кэш в `TermuxAppSharedProperties` (как `getTerminalMarginLeft()`), чтобы не ходить в SharedPreferences на каждом кадре.
2. **Распространение по образу полей (margins)** — `SessionPagerManager.setTerminalMargins()` → `TerminalPagerAdapter.setTerminalMargins()` → `applyTerminalMargins(view)` для каждой страницы + вызов в `onCreateViewHolder()`. Прозрачность идёт ровно по этому же, уже отлаженному пути.
3. **Ноль стоимости при 0%** — все новые ветки либо не вызываются, либо сравниваются с константой.

---

## 5. Настройки

### 5.1 Константы — `termux-shared/…/settings/preferences/TermuxPreferenceConstants.java`

Добавить в класс `TERMUX_APP` рядом с `KEY_TERMINAL_MARGIN_*` (строки 356-379):

```java
// int, percent: 0 = wallpaper disabled, 50 = maximum transparency
public static final String KEY_TERMINAL_BACKGROUND_TRANSPARENCY = "terminal-background-transparency";
public static final int DEFAULT_VALUE_TERMINAL_BACKGROUND_TRANSPARENCY = 0;
public static final int MIN_TERMINAL_BACKGROUND_TRANSPARENCY = 0;
public static final int MAX_TERMINAL_BACKGROUND_TRANSPARENCY = 50;

// string: "off" | "live" | "baked" — how the wallpaper behind the terminal is blurred
public static final String KEY_WALLPAPER_BLUR_MODE = "wallpaper-blur-mode";
public static final String DEFAULT_VALUE_WALLPAPER_BLUR_MODE = WALLPAPER_BLUR_MODE_OFF;
public static final String WALLPAPER_BLUR_MODE_OFF = "off";
/** Real-time system blur (FLAG_BLUR_BEHIND) behind a translucent window. Android 12+ only. */
public static final String WALLPAPER_BLUR_MODE_LIVE = "live";
/** Blur computed once from a wallpaper snapshot/derivative and cached. Android 12+ only. */
public static final String WALLPAPER_BLUR_MODE_BAKED = "baked";
```

> Ключи — в kebab-case, как `terminal-margin-left` / `terminal-cursor-blink-rate`. `MIN/MAX` кладутся прямо в константы (паттерн `MIN_TERMINAL_MARGIN_LEFT = TermuxPropertyConstants.…` тут не обязателен: значение не приходит из `termux.properties`).

### 5.2 `TermuxAppSharedPreferences.java`

Рядом с `getTerminalMarginLeft()` (строки 616-658):

```java
public int getTerminalBackgroundTransparency() {
    return SharedPreferenceUtils.getInt(mSharedPreferences,
        TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY,
        TERMUX_APP.DEFAULT_VALUE_TERMINAL_BACKGROUND_TRANSPARENCY);
}

public void setTerminalBackgroundTransparency(int value) {
    if (value < TERMUX_APP.MIN_TERMINAL_BACKGROUND_TRANSPARENCY)
        value = TERMUX_APP.MIN_TERMINAL_BACKGROUND_TRANSPARENCY;
    if (value > TERMUX_APP.MAX_TERMINAL_BACKGROUND_TRANSPARENCY)
        value = TERMUX_APP.MAX_TERMINAL_BACKGROUND_TRANSPARENCY;
    SharedPreferenceUtils.setInt(mSharedPreferences,
        TERMUX_APP.KEY_TERMINAL_BACKGROUND_TRANSPARENCY, value, false);
}

/** @return one of {@link #WALLPAPER_BLUR_MODE_OFF}, {@code _LIVE}, {@code _BAKED}. */
public String getWallpaperBlurMode() {
    String mode = SharedPreferenceUtils.getString(mSharedPreferences,
        TERMUX_APP.KEY_WALLPAPER_BLUR_MODE,
        TERMUX_APP.DEFAULT_VALUE_WALLPAPER_BLUR_MODE, true);
    // Anything unrecognised (or unsupported on this OS version) degrades to "off".
    if (!TERMUX_APP.WALLPAPER_BLUR_MODE_LIVE.equals(mode)
            && !TERMUX_APP.WALLPAPER_BLUR_MODE_BAKED.equals(mode)) {
        return TERMUX_APP.WALLPAPER_BLUR_MODE_OFF;
    }
    return mode;
}

public void setWallpaperBlurMode(String value) {
    SharedPreferenceUtils.setString(mSharedPreferences,
        TERMUX_APP.KEY_WALLPAPER_BLUR_MODE, value, false);
}
```

### 5.3 `TermuxAppSharedProperties.java`

Рядом с `getTerminalMarginLeft()` (строки 224-237), чтобы `mProperties` был единственным читателем:

```java
public int getTerminalBackgroundTransparency() {
    return prefs().getTerminalBackgroundTransparency();
}

/** @return one of {@code "off"} / {@code "live"} / {@code "baked"}. */
public String getWallpaperBlurMode() {
    return prefs().getWallpaperBlurMode();
}

/** Convenience: is any blur requested at all? */
public boolean isWallpaperBlurEnabled() {
    return !TermuxPreferenceConstants.TERMUX_APP.WALLPAPER_BLUR_MODE_OFF.equals(getWallpaperBlurMode());
}

/** Only in this mode does the window need FLAG_SHOW_WALLPAPER and a translucent surface. */
public boolean isWallpaperBlurLive() {
    return TermuxPreferenceConstants.TERMUX_APP.WALLPAPER_BLUR_MODE_LIVE.equals(getWallpaperBlurMode());
}
```

> `TermuxPropertyConstants`/`getPropertyValue()` (строки 330-337) трогать **не нужно** — эти ключи живут только в `SharedPreferences`, в `termux.properties` им не место.

### 5.4 Разметка — `app/src/main/res/xml/termux_display_preferences.xml`

В категорию **Appearance** (после `button_bg_active_alpha`, строка 42):

```xml
<SeekBarPreference
    app:key="terminal-background-transparency"
    app:title="@string/terminal_background_transparency_title"
    app:min="0"
    android:max="40"
    app:defaultValue="0"
    app:showSeekBarValue="true"
    app:iconSpaceReserved="false" />

<ListPreference
    app:key="wallpaper-blur-mode"
    app:title="@string/wallpaper_blur_mode_title"
    app:summary="@string/wallpaper_blur_mode_summary"
    app:entries="@array/wallpaper_blur_mode_entries"
    app:entryValues="@array/wallpaper_blur_mode_values"
    app:defaultValue="off"
    app:iconSpaceReserved="false" />
```

И в `app/src/main/res/values/arrays.xml` (рядом с `terminal_cursor_style_*`, строка 82):

```xml
<string-array name="wallpaper_blur_mode_entries">
    <item>@string/wallpaper_blur_mode_off</item>
    <item>@string/wallpaper_blur_mode_live</item>
    <item>@string/wallpaper_blur_mode_baked</item>
</string-array>
<string-array name="wallpaper_blur_mode_values">
    <item>off</item>
    <item>live</item>
    <item>baked</item>
</string-array>
```

### 5.5 Строки — `app/src/main/res/values/strings.xml`

```xml
<string name="terminal_background_transparency_title">Прозрачность фона</string>

<string name="wallpaper_blur_mode_title">Размытие обоев</string>
<string name="wallpaper_blur_mode_summary">Как размывать подложку под терминалом</string>
<string name="wallpaper_blur_mode_off">Выключено</string>
<string name="wallpaper_blur_mode_live">В реальном времени (настоящие обои)</string>
<string name="wallpaper_blur_mode_baked">Запечённое (кэш, без затрат на кадр)</string>
<string name="wallpaper_blur_unsupported">Требуется Android 12 или новее</string>
```

### 5.6 Проводка — `DisplayPreferencesFragment.java`

В `configureTerminalAppearancePreferences()` (после margins, строки 189-199):

```java
// --- Background transparency (wallpaper behind the terminal) ---
mTransparencyPref = findPreference("terminal-background-transparency");
mBlurModePref     = findPreference("wallpaper-blur-mode");

configureSeekBarInt("terminal-background-transparency",
    prefs.getTerminalBackgroundTransparency(),
    value -> {
        prefs.setTerminalBackgroundTransparency(value);
        // Blur is meaningless with an opaque background.
        updateBlurModeEnabledState();
    });

if (mBlurModePref != null) {
    mBlurModePref.setPersistent(false);
    // Both modes are Android 12+: FLAG_BLUR_BEHIND and RenderEffect are API 31.
    final boolean blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    mBlurModePref.setValue(blurSupported
            ? prefs.getWallpaperBlurMode()
            : TermuxPreferenceConstants.TERMUX_APP.WALLPAPER_BLUR_MODE_OFF);
    mBlurModePref.setSummary(blurSupported
            ? mBlurModePref.getEntry()
            : getString(R.string.wallpaper_blur_unsupported));
    updateBlurModeEnabledState();
    mBlurModePref.setOnPreferenceChangeListener((preference, newValue) -> {
        prefs.setWallpaperBlurMode((String) newValue);
        mBlurModePref.setSummary(mBlurModePref.getEntry());
        updateStyling();
        return true;
    });
}
```

И helper рядом с `configureSeekBarInt()`:

```java
private void updateBlurModeEnabledState() {
    if (mBlurModePref == null) return;
    final boolean blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    final int percent = TermuxAppSharedPreferences.build(requireContext(), true)
            .getTerminalBackgroundTransparency();
    mBlurModePref.setEnabled(blurSupported && percent > 0);
}
```

> Поля `mTransparencyPref` / `mBlurModePref` — `SeekBarPreference` и `ListPreference` уровня фрагмента, нужны только для того, чтобы слайдер прозрачности мог включать/выключать список режимов.

Почему так:

* `configureSeekBarInt()` уже делает `setPersistent(false)`, клампит значение по `pref.getMax()` и вызывает `updateStyling()` — переиспользуем как есть (строки 205-224).
* `updateStyling()` → `TermuxActivity.updateTermuxActivityStyling(ctx, true)` → broadcast `ACTION_RELOAD_STYLE` → `reloadActivityStyling(true)` → **`recreate()`**. Пересоздание здесь обязательно: тема окна (`windowIsTranslucent`) статична (§2.2).
* Слайдер дёргает `updateStyling()` на каждое значение → recreate на каждое движение пальца. Это плохо. **Нужно сделать как у `wireSliderListener()` (строки 361-368), а не как у margins** — то есть `updateTermuxActivityStyling(context, false)` (без recreate) во время драга, и один раз `true` по `onStopTrackingTouch`. Практически: завести в `DisplayPreferencesFragment` отдельный метод `configureTransparencySlider()`, который на изменение пишет значение и вызывает `updateStylingNoRecreate()`, а recreate делается один раз при выходе из экрана (или по `onPreferenceChange` с дебаунсом).

  Более простой и предсказуемый вариант — оставить recreate, но применить `setOnPreferenceChangeListener` с «применять при отпускании»: `SeekBarPreference` не даёт такого хука напрямую, поэтому либо брать `slider.getOnSeekBarChangeListener()` через `setOnPreferenceChangeListener` + флаг, либо писать значение сразу, а `updateStyling()` вызывать из `onPause()` фрагмента. **Рекомендуется:** писать значение сразу (`prefs.set…`) + вызывать `updateStyling()` один раз в `onStop()` фрагмента; при этом само окно (`FLAG_SHOW_WALLPAPER`, `setFormat`, блюр) обновлять *без* recreate из `reloadActivityStyling()`, а recreate — только когда изменился сам факт `percent > 0` (то есть переход через ноль).

  Ниже (§9.5) описана схема, при которой **recreate нужен только при переходе через 0**, а смена 0→50→20 внутри включённого состояния применяется живьём.

---

## 6. Рендерер: альфа-композитинг фона

### 6.1 Вычисление альфы

```java
// percent: 0..50
alpha = Math.round(255f * (100 - percent) / 100f);   // 0% → 255, 50% → 128
```

Цвета палитры приходят как `0xAARRGGBB` с `AA = FF`, поэтому:

```java
int translucentBg = (bgColor & 0x00FFFFFF) | (alpha << 24);
```

### 6.2 Почему `PorterDuff.Mode.SRC`, а не `SRC_OVER` — критично

`TerminalRenderer` рисует **не на чистом холсте**. И при software-рендеринге (`Surface.lockCanvas(dirty)`), и при HWUI содержимое кадра **сохраняется** между `onDraw()`; текущий код это уже учитывает, заливая полный репейнт через `drawColor(..., Mode.SRC)`.

Если при частичном репейнте (`dirtyRect != null`) заливать прямоугольник полупрозрачным цветом в режиме `SRC_OVER`, альфа будет **накапливаться от кадра к кадру**:

```
кадр 1: a = 0.5
кадр 2: 0.5 поверх 0.5 → 0.75
кадр 3: → 0.875  … → фактически непрозрачно
```

Симптом: при мерцании курсора или скролле «прозрачные» полосы постепенно становятся плотными, а картинка «гуляет». Поэтому **базовая заливка всегда должна быть в режиме `SRC`** — и на полном, и на частичном репейнте. `SRC` не читает destination, поэтому:

* корректно «сбрасывает» регион в `(цвет, альфа)` независимо от предыдущего кадра;
* уже используется в проде (`drawColor(bgColor, PorterDuff.Mode.SRC)` на каждом полном репейнте) → HWUI этот режим поддерживает, сюрпризов не будет.

`mBgPaint` переиспользуется для заливки цветных ячеек (строка 382), где нужен как раз `SRC_OVER`. Значит xfermode надо выставлять точечно и **обязательно сбрасывать**.

### 6.3 Прозрачим все фоны одинаково

Ячейки с явно заданным фоном (`ls --color`, `htop`, подсветка синтаксиса) **получают ту же
альфу, что и базовая заливка**, чтобы весь фон терминала просвечивал одинаково. Причины:

1. Ожиаемое поведение настройки «прозрачность фона»: пользователь просит прозрачный фон,
   а не «прозрачный фон за исключением мест, где что-то выведено». Неравномерная прозрачность
   выглядит как баг (пятна плотного цвета на полупрозрачном полотне).
2. Читаемость не страдает: альфа ограничена (0–50% прозрачности), текст рисуется поверх
   поверх непрозрачным цветом с теми же координатами, что и раньше.
3. Обратное (полная непрозрачность цветных ячеек) давалось «бесплатно» через сравнение
   `backColor == palette[COLOR_INDEX_BACKGROUND]` в pass A — но именно оно и создавало баг:
   ран с закрашенным фоном заливался `SRC_OVER` непрозрачным цветом поверх полупрозрачного
   базового фона.

**Важно: заливка цветных ран-блоков обязана быть в режиме `SRC`, а не `SRC_OVER`.**
Базовая заливка уже положила в canvas полупрозрачный цвет; `SRC_OVER` поверх неё
скомпозитил бы две альфы: A_итог = A + A·(1−A) = 2A−A² (например, 0x80 поверх 0x80
даёт 0xC0) — закрашенные ячейки стали бы плотнее соседних и приобрели бы подмес цвета
базового фона. `SRC` заменяет пиксель целиком: `(цвет ячейки, mBackgroundAlpha)`.

Сравнение-пропуск в pass A тоже использует **эффективный** цвет базовой заливки
(`rawBgColor`): при reverse video базовая заливка берётся из `COLOR_INDEX_FOREGROUND`,
и ячейка с дефолтным фоном после свапа несёт именно его — сравнение с
`COLOR_INDEX_BACKGROUND` закрашивало бы весь экран непрозрачным цветом, убивая
прозрачность в reverse-режиме целиком.

### 6.4 Код — `terminal-view/…/TerminalRenderer.java`

Поле (рядом с `mBgPaint`, строка 106):

```java
/** Alpha for the *default* background fill: 255 = opaque (feature off). */
private int mBackgroundAlpha = 255;

/** Reusable SRC xfermode for the base background fill (see setBackgroundTransparencyPercent). */
private static final PorterDuffXfermode SRC_XFERMODE =
        new PorterDuffXfermode(PorterDuff.Mode.SRC);
```

Сеттер:

```java
/**
 * Set how transparent the terminal background is.
 *
 * @param percent 0 (opaque, wallpaper disabled) .. 50 (maximum transparency).
 */
public void setBackgroundTransparencyPercent(int percent) {
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    mBackgroundAlpha = Math.round(255f * (100 - percent) / 100f);
}
```

Замена блока заливки фона (строки 241-253):

```java
// Background. A full repaint clears the entire canvas — this is what keeps theme /
// OSC color-scheme swaps correct (see the original comment). A partial repaint clears
// only the dirty region; the framework has already clipped the canvas to it, and we
// bound the fill explicitly so clean rows are never erased.
final int rawBgColor = reverseVideo
    ? palette[TextStyle.COLOR_INDEX_FOREGROUND]
    : palette[TextStyle.COLOR_INDEX_BACKGROUND];

// With wallpaper transparency the fill must stay in SRC mode: the canvas keeps the
// previous frame's pixels outside the clip (and, in practice, inside it too), so an
// SRC_OVER fill of a translucent colour would accumulate alpha every partial repaint
// and the terminal would creep towards opaque.
final int bgColor = (mBackgroundAlpha >= 255)
    ? rawBgColor
    : ((rawBgColor & 0x00FFFFFF) | (mBackgroundAlpha << 24));

if (dirtyRect == null) {
    canvas.drawColor(bgColor, PorterDuff.Mode.SRC);
} else {
    if (mBackgroundAlpha >= 255) {
        // Fast path: identical to the current behaviour, no xfermode churn.
        mBgPaint.setColor(bgColor);
        canvas.drawRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, mBgPaint);
    } else {
        mBgPaint.setXfermode(SRC_XFERMODE);
        mBgPaint.setColor(bgColor);
        canvas.drawRect(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom, mBgPaint);
        mBgPaint.setXfermode(null);   // mBgPaint is reused for per-run fills — restore SRC_OVER
    }
}
```

> **Остальное в рендерере менять не нужно — но только при соблюдении режима `SRC`.**
> Проверено по коду:
> * pass A — пропуск по `backColor == rawBgColor` (эффективный базовый цвет, см. 6.3);
>   цветные ран-блоки заливаются цветом с альфой `mBackgroundAlpha` в режиме `SRC`;
> * pass B / mismatch-runs — то же условие против эффективного базового цвета, та же альфа,
>   **и обязательно `SRC` через `mBgPaint`**. Исторически здесь рисовали `mTextPaint` без
>   xfermode, т.е. `SRC_OVER` → `2A−A²` + подмес базового цвета (см. R21);
> * курсор — тот же приём: `mBgPaint` + `SRC` + альфа `mBackgroundAlpha`. Непрозрачный
>   прямоугольник курсора пробивал плотную «дыру» в обоях в месте мигания (см. R22);
> * текст — рисуется `SRC_OVER` непрозрачным цветом, на прозрачность не влияет.
>
> Важно: **`SRC` нельзя вешать на сам `mTextPaint`**. Текст рисуется с частичным покрытием
> глифа, и `SRC` «протрёт» фон под полупрозрачными пикселями сглаживания — появятся тёмные
> ореолы вокруг символов. Поэтому все фоновые заливки идут через отдельный `mBgPaint`.

---

## 7. `TerminalView` — владелец состояния

### 7.1 Поле и сеттер

```java
/** Terminal background transparency in percent: 0 (opaque) .. 50. */
private int mBackgroundTransparencyPercent = 0;

/**
 * Show the device wallpaper through the terminal background.
 *
 * @param percent 0 = opaque (wallpaper disabled), 50 = maximum transparency.
 */
public void setBackgroundTransparencyPercent(int percent) {
    if (percent < 0) percent = 0;
    if (percent > 100) percent = 100;
    if (mBackgroundTransparencyPercent == percent) return;
    mBackgroundTransparencyPercent = percent;
    if (mRenderer != null) mRenderer.setBackgroundTransparencyPercent(percent);
    // Full invalidate: the whole surface changes alpha, a partial repaint would leave
    // the previous frame's pixels around the dirty rect.
    invalidate();
}

public int getBackgroundTransparencyPercent() {
    return mBackgroundTransparencyPercent;
}
```

### 7.2 `setTextSize()` / `setTypeface()` — ловушка

```java
// TerminalView.java:954-963
public void setTextSize(int textSize) {
    mRenderer = new TerminalRenderer(textSize, mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface);
    updateSize();
}

public void setTypeface(Typeface newTypeface) {
    mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface);
    updateSize();
    invalidate();
}
```

Оба метода **создают новый `TerminalRenderer`**, поэтому состояние прозрачности нельзя хранить только в рендерере — оно потеряется при смене шрифта/размера (а это происходит при зум-жесте). Решение: значение живёт в `TerminalView`, и оба метода обязаны его переприменить:

```java
public void setTextSize(int textSize) {
    mRenderer = new TerminalRenderer(textSize, mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface);
    mRenderer.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);   // ← добавить
    updateSize();
}

public void setTypeface(Typeface newTypeface) {
    mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface);
    mRenderer.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);   // ← добавить
    updateSize();
    invalidate();
}
```

### 7.3 `isOpaque()` (строки 970-973)

```java
@Override
public boolean isOpaque() {
    // The renderer fills the whole view, so with an opaque background the view really is
    // a full occluder. With wallpaper transparency it is not — and claiming otherwise
    // lets framework overdraw/transparent-region logic treat translucent pixels as solid.
    return mBackgroundTransparencyPercent == 0;
}
```

Оговорка: если на каких-то прошивках `false` приведёт к артефактам «прозрачных регионов» (наследие `gatherTransparentRegion`, `ViewRootImpl.java:4299`), безопасный фолбэк — оставить `return true`: рендерер всё равно закрашивает каждый пиксель своей площади, так что флаг влияет только на овердрафт-оптимизации. **Рекомендуется начать с честного варианта.**

### 7.4 Заглушка без эмулятора (строки 1985-1991)

```java
@Override
protected void onDraw(Canvas canvas) {
    if (mEmulator == null) {
        // …while no emulator is attached (recreate / day-night swap). Match the
        // configured transparency so the placeholder never flashes a solid colour over
        // the wallpaper (B6).
        int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        int alpha = Math.round(255f * (100 - mBackgroundTransparencyPercent) / 100f);
        canvas.drawColor(alpha >= 255 ? bg : ((bg & 0x00FFFFFF) | (alpha << 24)),
                         PorterDuff.Mode.SRC);
    } else {
        …
```

---

## 8. Распространение на все страницы пейджера

Путь ровно как у margins.

### `TerminalPagerAdapter.java`

```java
/** Terminal background transparency in percent (0 = disabled). */
private int mBackgroundTransparencyPercent = 0;

public void setTerminalBackgroundTransparency(int percent) {
    mBackgroundTransparencyPercent = percent;
    for (int i = 0; i < mAttachedViews.size(); i++) {
        applyTerminalTransparency(mAttachedViews.valueAt(i));
    }
}

private void applyTerminalTransparency(@Nullable TerminalView terminalView) {
    if (terminalView != null) terminalView.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);
}
```

В `onCreateViewHolder()` (строки 224-230), сразу после `applyTerminalMargins(holder.mTerminalView)`:

```java
// Carry the configured background transparency to every newly created page, the same
// way the margins are carried — a page created mid-swipe must not appear opaque.
applyTerminalTransparency(holder.mTerminalView);
```

> `mAttachedViews` уже поддерживается корректно при вставке/удалении сессий (строки 129-164) — дополнительной работы нет.

### `SessionPagerManager.java`

```java
public void setTerminalBackgroundTransparency(int percent) {
    if (mTerminalPagerAdapter != null)
        mTerminalPagerAdapter.setTerminalBackgroundTransparency(percent);
}
```

И в `setup()` (рядом со строками 194-197), чтобы значение применилось на холодном старте, когда `TermuxActivity.setMargins()` ещё не мог достучаться до адаптера:

```java
setTerminalBackgroundTransparency(mActivity.getProperties().getTerminalBackgroundTransparency());
```

---

## 9. `TermuxActivity` — окно

### 9.1 Тема

Добавить в `app/src/main/res/values/themes.xml`:

```xml
<!-- TermuxActivity theme variant used when the terminal background is transparent and the
     device wallpaper must show through. windowIsTranslucent is what ultimately forces
     PixelFormat.TRANSLUCENT on the window surface (ViewRootImpl.relayoutWindow() only
     upgrades the format when PFLAG_REQUEST_TRANSPARENT_REGIONS is set), so it is a static
     theme attribute and switching it requires an activity recreate. -->
<style name="Theme.TermuxActivity.DayNight.NoActionBar.Wallpaper"
       parent="Theme.TermuxActivity.DayNight.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowIsFloating">false</item>
    <item name="android:windowDimAmount">0</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowBackgroundBlurRadius">0dp</item>
</style>
```

Применять в `onCreate()` **до `super.onCreate()`** — там, где уже стоит `applyTermuxTheme()` (строка 600):

```java
applyTermuxTheme();     // existing: scheme/night-mode
applyWallpaperTheme();  // NEW: setTheme(…) when transparency > 0
super.onCreate(savedInstanceState);
```

```java
/**
 * Switch to the translucent theme variant *before* super.onCreate()/PhoneWindow read the
 * theme. No-op when the wallpaper feature is off (0%): keeps the opaque theme, the
 * OPAQUE surface format and today's zero-cost path.
 */
private void applyWallpaperTheme() {
    if (mProperties == null) return;
    if (mProperties.getTerminalBackgroundTransparency() > 0) {
        setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar_Wallpaper);
    }
}
```

`android:windowIsFloating=false` — обязательно: AppCompat-темы с `windowIsTranslucent` на некоторых конфигурациях наследуют floating-поведение, а оно меняет разметку окна (`wrap_content`, инсеты, dim) и нам не нужно.

### 9.2 `FLAG_SHOW_WALLPAPER` и флаги окна

Новый метод рядом с `setFullScreenFlags()` (строка 3930):

```java
/**
 * Show (or hide) the *real* device wallpaper behind the terminal and apply the
 * Android 12+ system blur.
 *
 * Called from onCreate() and from every {@link #reloadActivityStyling(boolean)}. The
 * theme half (android:windowIsTranslucent) cannot be toggled at runtime — it is applied
 * in {@link #applyWallpaperTheme()} before super.onCreate(), which is why crossing the
 * 0% boundary (0 -> >0 or >0 -> 0) requires an activity recreate.
 */
private void applyWallpaperAndBlur() {
    if (mProperties == null) return;

    final int percent = mProperties.getTerminalBackgroundTransparency();
    final boolean showWallpaper = percent > 0;

    final Window window = getWindow();
    if (window == null) return;

    if (showWallpaper) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        // Belt-and-braces in case an OEM theme/policy did not upgrade the surface format.
        window.setFormat(PixelFormat.TRANSLUCENT);
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.setFormat(PixelFormat.OPAQUE);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // With FLAG_SHOW_WALLPAPER every touch is *also* delivered to the wallpaper
        // (live-wallpaper interaction). A terminal must not leak tap coordinates to a
        // third-party wallpaper.
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.setWallpaperTouchEventsEnabled(!showWallpaper);
        window.setAttributes(attrs);
    }

    applyBackgroundBlur(showWallpaper && mProperties.isTerminalBackgroundBlurEnabled());
}
```

> **API 31-33**: `setWallpaperTouchEventsEnabled()` недоступен (добавлен в 34). Это известное ограничение платформы: на этих версиях тапы будут уходить в live-обои. Обойти нельзя; статические обои этого не делают. В UI про это писать не нужно — достаточно не включать `FLAG_SHOW_WALLPAPER` при 0%, что и так выполняется.

### 9.3 `applySystemBarColors()` — не красить decor view

Сейчас (`TermuxActivity.java:2691-2697`):

```java
decorView.setBackgroundColor(surfaceBackground);   // ← B3
```

Меняем сигнатуру и условие:

```java
public static void applySystemBarColors(Window window, int surfaceBackground,
                                        boolean isLight, boolean wallpaperVisible) {
    if (window == null) return;
    View decorView = window.getDecorView();

    // Paint the window surface with the scheme background so the (transparent) status and
    // navigation bars show exactly the terminal colour — *unless* the wallpaper is visible
    // behind the terminal, in which case the surface must stay transparent.
    if (wallpaperVisible) {
        decorView.setBackgroundColor(Color.TRANSPARENT);
        // Or, to keep a subtle tint of the scheme over the wallpaper:
        // decorView.setBackgroundColor(withAlpha(surfaceBackground, /*e.g.*/ 0x40));
    } else {
        decorView.setBackgroundColor(surfaceBackground);
    }
    …  // остальное без изменений
}
```

И в `applySchemeColors()` (строки 2663-2675):

```java
public void applySchemeColors() {
    TermuxColorSchemeManager csm = mColorSchemeManager;
    int schemeBg = csm.getSchemeBackground();
    boolean isLight = csm.isSchemeLight();

    Window window = getWindow();
    if (window != null) {
        applySystemBarColors(window, schemeBg, isLight,
            mProperties != null && mProperties.getTerminalBackgroundTransparency() > 0);
    }
}
```

**Иконки в барах.** `isLight` по-прежнему берётся из цветовой схемы, и это корректно: при прозрачности ≤50% фон терминала даёт **не меньше половины** итогового цвета, так что светлая схема остаётся светлой, тёмная — тёмной. Пересчитывать по яркости обоев (`WallpaperManager.getWallpaperColors()`, API 27+) для терминала избыточно — это добавит «мигания» иконок при смене обоев без реальной пользы. **Не делаем.**

### 9.4 Точки вызова

| Место | Что добавить |
|---|---|
| `onCreate()` строка 600 | `applyWallpaperTheme()` **до** `super.onCreate()` |
| `onCreate()` после `setFullScreenFlags()` (строка 671) | `applyWallpaperAndBlur()` |
| `setMargins()` (строка 1466) | рядом — `applyTerminalTransparency()` (см. ниже) |
| `reloadActivityStyling(boolean)` (строка 3938), рядом с `setMargins()` / `setFullScreenFlags()` | `applyTerminalTransparency()` + `applyWallpaperAndBlur()`; `applySchemeColors()` уже вызывается там же (строка 3987) |

```java
/** Push the configured background transparency to every terminal page. Mirrors setMargins(). */
private void applyTerminalTransparency() {
    if (mSessionPagerManager == null) return;
    mSessionPagerManager.setTerminalBackgroundTransparency(
        mProperties.getTerminalBackgroundTransparency());
}
```

### 9.5 Когда нужен `recreate()`

| Изменение | Нужен recreate? | Почему |
|---|---|---|
| 0% → N>0% | **Да** | меняется `windowIsTranslucent` → формат поверхности |
| N>0% → 0% | **Да** | то же |
| 10% → 35% | Нет (но безвреден) | меняется только альфа рендерера +半径 блюра |
| blur on/off | Нет | только флаги `WindowManager.LayoutParams` |

Реализация: в `reloadActivityStyling()` сравнивать «предыдущее» состояние `percent > 0` с новым и просить recreate только при переходе через ноль (поле `mWallpaperThemeApplied`). Для UI это видно как: при выключении/включении фичи — быстрая перезагрузка Activity (уже привычно по смене темы), при движении слайдера — мгновенное живое изменение прозрачности.

---

## 10. Системный блюр (только Android 12+)

### 10.1 Какой API выбрать

В Android 12 добавились **два** разных механизма:

| | `Window#setBackgroundBlurRadius(int)` | `FLAG_BLUR_BEHIND` + `LayoutParams#setBlurBehindRadius(int)` |
|---|---|---|
| Что блюрит | экран за окном **в границах окна** | **весь** экран за окном |
| Требования | окно должно быть **translucent И floating** (прямо в javadoc `Window.java:2009-2011`) | окно translucent |
| Подходит нам? | **Нет.** `windowIsFloating=true` меняет разметку окна (wrap_content, инсеты, dim) — для полноэкранного терминала неприемлемо | **Да** |

> `Window.java:2013-2015`: *«Note the difference with `WindowManager.LayoutParams#setBlurBehindRadius`, which blurs the whole screen behind the window. Background blur blurs the screen behind only within the bounds of the window.»*

Для полноэкранного окна терминала «весь экран за окном» — это ровно обои. Значит **выбираем `FLAG_BLUR_BEHIND` + `setBlurBehindRadius()`**.

Радиус: AOSP-примеры используют `20` для blur-behind (`80` — для background blur, >150 не рекомендуется из-за стоимости). Для подложки под текстом берём **`20`**, константой.

> **Альтернатива коду — тема.** AOSP допускает включение блюра разметкой: `R.attr.windowBlurBehindEnabled` (boolean) + `R.attr.windowBlurBehindRadius` (dimension), оба API 31, реально объявлены в `attrs.xml` (строки 90-95). Их можно положить прямо в тему `…NoActionBar.Wallpaper` (§9.1) и не трогать `WindowManager.LayoutParams` в коде. Предпочтительнее всё же код: радиус/флаг тогда переключаются живьём вместе с `applyWallpaperAndBlur()`, без `recreate()`.

### 10.2 Доступность блюра

```java
// android/view/WindowManager.java:1850
default boolean isCrossWindowBlurEnabled() { return false; }
```

`false` бывает когда: GPU не поддерживает (`ro.surface_flinger.supports_background_blur=0`), включён режим энергосбережения, используется multimedia tunneling, запрошен minimal post processing. Слушать изменения: `addCrossWindowBlurEnabledListener(Executor, Consumer<Boolean>)` — вызывается сразу с текущим значением при регистрации.

Если блюр недоступен — **просто не выставляем флаг**: обои останутся видимыми, но не размытыми. Никакого фолбэка (своего блюра) по требованию T3 не пишем.

### 10.3 Код — `TermuxActivity`

```java
/** Blur radius in pixels for FLAG_BLUR_BEHIND (AOSP sample value). */
private static final int WALLPAPER_BLUR_RADIUS_PX = 20;

@RequiresApi(Build.VERSION_CODES.S)
private void sSetBlurBehind(Window window, boolean enabled) {
    WindowManager.LayoutParams attrs = window.getAttributes();
    if (enabled) {
        attrs.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        attrs.setBlurBehindRadius(WALLPAPER_BLUR_RADIUS_PX);
    } else {
        attrs.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        attrs.setBlurBehindRadius(0);
    }
    window.setAttributes(attrs);
}

/**
 * Android 12+ system blur of whatever is behind the window (the wallpaper). Disabled on
 * older versions — there is deliberately no custom blur fallback.
 */
private void applyBackgroundBlur(boolean enabled) {
    Window window = getWindow();
    if (window == null) return;

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) sSetBlurBehind(window, false);
        return;
    }

    WindowManager wm = getSystemService(WindowManager.class);
    if (wm != null && !wm.isCrossWindowBlurEnabled()) {
        // Battery saver / GPU limitation / multimedia tunneling: blur would be silently
        // dropped anyway. Keep the wallpaper, just unblurred.
        sSetBlurBehind(window, false);
        return;
    }
    sSetBlurBehind(window, true);
}
```

Регистрация слушателя (в `onStart()` / снятие в `onStop()`), чтобы блюр возвращался после выключения энергосбережения:

```java
private Consumer<Boolean> mCrossWindowBlurListener;   // API 31+

@RequiresApi(Build.VERSION_CODES.S)
private void registerCrossWindowBlurListener() {
    WindowManager wm = getSystemService(WindowManager.class);
    if (wm == null) return;
    mCrossWindowBlurListener = enabled -> {
        if (mProperties == null) return;
        applyBackgroundBlur(mProperties.getTerminalBackgroundTransparency() > 0
                            && mProperties.isTerminalBackgroundBlurEnabled());
    };
    wm.addCrossWindowBlurEnabledListener(
        ContextCompat.getMainExecutor(this), mCrossWindowBlurListener);
}

@RequiresApi(Build.VERSION_CODES.S)
private void unregisterCrossWindowBlurListener() {
    if (mCrossWindowBlurListener == null) return;
    WindowManager wm = getSystemService(WindowManager.class);
    if (wm != null) wm.removeCrossWindowBlurEnabledListener(mCrossWindowBlurListener);
    mCrossWindowBlurListener = null;
}
```

> `Consumer<Boolean>` — `java.util.function.Consumer`, доступен с API 24; при `minSdk 21` вызов обёрнут в `if (Build.VERSION.SDK_INT >= 31)`, поэтому дешёгаринг R8 не тронет путь ниже 24… но сам тип в байткоде появится. Безопасно: класс загружается лениво. На всякий случай можно объявить поле как `Object` и кастовать внутри — но это ухудшает читаемость; рекомендуется оставить `Consumer` (AndroidX-дешёгаринг уже используется в проекте повсеместно).

### 10.4 Чего **не** делаем

* **Запечённый блюр** (снапшот обоев + `RenderEffect`, посчитать один раз и кэшировать) — **не делаем**: нарушает T2, и главное — «запекать» нечего: стороннему приложению снапшот обоев недоступен, `WallpaperManager.getDrawable()` с Android 14 всегда бросает `SecurityException`, а на Android 13 возвращает дефолтные обои вместо пользовательских (§2.1). Единственный рабочий источник — настоящая wallpaper-surface, а она по определению живая.
* Свой блюр (RenderScript / `RenderEffect` / downscale-upscale / `BlurMaskFilter`) — прямо запрещено требованием.
* `Window#setBackgroundBlurRadius()` — требует `windowIsFloating` (§10.1).
* Блюр при 0% прозрачности — флаг снимается, потому что фона под ним всё равно не видно.
* Попытки «не давать кадры, чтобы блюр не считался» в ущерб функциональности — нет. Допустимо только устранение **лишних** кадров (§10.6), но не задержка реального вывода.

### 10.5 Режим `baked` — запечённый блюр

**Идея:** один раз получить подложку, размыть её, закэшировать и рисовать как обычный статичный фон. Никакой wallpaper-surface, никакого `FLAG_BLUR_BEHIND`, никакой translucent-поверхности → **пересчёта нет никогда и ни при каких условиях**. Это сознательный обмен: T2 (настоящие обои) нарушается ради нулевой стоимости кадра.

#### 10.5.1 Что в `baked` **не** нужно (важно для §9)

| Не нужно | Почему |
|---|---|
| `FLAG_SHOW_WALLPAPER` | своей wallpaper-surface нет, рисуем свой битмап |
| Полупрозрачная поверхность окна (`windowIsTranslucent`) | мы непрозрачны, подложка — наш же View |
| `recreate()` при включении/выключении | тема окна не меняется, всё применяется живьём |
| `isCrossWindowBlurEnabled()` и его слушатель | блюр считаем сами, система тут ни при чём |
| `setWallpaperTouchEventsEnabled()` | побочно решает R12: тапы в live-wallpaper не утекают |

То есть **`baked` — это режим без единой оконной магии**, вся работа внутри приложения.

#### 10.5.2 Где рисуется

Подложка — обычный `ImageView` **первым ребёнком** `activity_termux_root_relative_layout` (в `activity_termux.xml`): в `RelativeLayout` порядок объявления задаёт z-порядок, поэтому первый ребёнок оказывается под вкладками, пейджером и тулбаром — а все они прозрачны (проверено).

```xml
<!-- in activity_termux.xml, first child of @id/activity_termux_root_relative_layout -->
<ImageView
    android:id="@+id/terminal_backdrop"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:scaleType="centerCrop"
    android:visibility="gone"
    android:importantForAccessibility="no" />
```

Decor view при этом остаётся прозрачным по уже существующему механизму §9.3 — симметрия с `live`: decor прозрачен в обоих режимах, но в `live` сквозь него видна wallpaper-surface системы, а в `baked` — наш кэш.

#### 10.5.3 Источник картинки: два, с приоритетом

| Приоритет | Источник | Когда доступен |
|---|---|---|
| 1 | `WallpaperManager.getDrawable()` — **настоящий** снапшот | только если `Environment.isExternalStorageManager()` (выдан `MANAGE_EXTERNAL_STORAGE`) или на API ≤ 32 выдан `READ_EXTERNAL_STORAGE` |
| 2 | `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` → синтез градиента по `getPrimaryColor()/getSecondaryColor()/getTertiaryColor()` | **всегда**, разрешений не требует (`WallpaperManager.java:1921`, аннотации `@RequiresPermission` нет) |

Почему нужен второй источник: на Android 13 без разрешения `getDrawable()` **молча** вернёт дефолтные обои, на 14+ бросит `SecurityException`. Если ограничиться только снапшотом, у большинства пользователей `baked` будет показывать не их обои. Поэтому:

```java
@Nullable
private Bitmap obtainWallpaperSource() {
    WallpaperManager wm = WallpaperManager.getInstance(this);
    if (wm == null) return null;

    // Real snapshot: only legal when all-files-access is granted (see §2.1 / §10.5.3).
    if (Environment.isExternalStorageManager() || Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        try {
            Drawable d = wm.getDrawable();
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (SecurityException | RuntimeException e) {
            Logger.logWarn(LOG_TAG, "Wallpaper snapshot unavailable, falling back to colors: " + e);
        }
    }
    return synthesizeFromWallpaperColors(wm);   // never null unless theme says otherwise
}
```

> **Независимо от версии, `getDrawable()` никогда не вернёт кадр живого wallpaper** — для живых обоев он отдаёт встроенную дефолтную картинку (`WallpaperManager.java:1087-1089`). В `baked` живые обои в принципе не воспроизводятся; это надо честно написать в описании режима.

#### 10.5.4 Конвейер: downscale → blur → кэш

```
source bitmap
   ↓ downscale ÷4 (результат всё равно размыт, качество неотличимо)
   ↓ RenderEffect.createBlurEffect(r/4, r/4, Shader.TileMode.CLAMP)   // API 31
   ↓ кэш Bitmap / hardware layer
   ↓ ImageView (scaleType=centerCrop) растягивает обратно на экран
```

* **Downscale — обязателен.** 1080×2400 ARGB_8888 = 10.4 МБ → при ÷4 это 0.65 МБ, т.е. в 16 раз меньше, и blur считается во столько же раз быстрее. Радиус масштабируется вместе с картинкой: при `scale=4` и целевом `20 px` берём `5`.
* **`RenderEffect` применяется к View, а не к Bitmap** — это самый простой путь:

```java
@RequiresApi(Build.VERSION_CODES.S)
private void applyBakedBackdrop(@Nullable Bitmap source) {
    ImageView backdrop = findViewById(R.id.terminal_backdrop);
    if (source == null || backdrop == null) { backdrop.setVisibility(View.GONE); return; }

    backdrop.setVisibility(View.VISIBLE);
    backdrop.setImageBitmap(downscale(source, BAKE_SCALE));

    // LAYER_TYPE_HARDWARE renders the filtered content once into a cached layer, so the
    // blur is not re-executed on every frame. Verify with §10.8.
    backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    backdrop.setRenderEffect(RenderEffect.createBlurEffect(
            WALLPAPER_BLUR_RADIUS_PX / BAKE_SCALE,
            WALLPAPER_BLUR_RADIUS_PX / BAKE_SCALE,
            Shader.TileMode.CLAMP));
}
```

> **Гипотеза, требующая замера:** что `LAYER_TYPE_HARDWARE` действительно кэширует отфильтрованный слой и блюр не пересчитывается при каждом кадре. Если замер (§10.8) покажет обратное — «запекать» по-настоящему: отрендерить результат в `Bitmap` один раз через `RenderNode` + `HardwareRenderer` и положить в `ImageView` уже готовым битмапом. Это надёжнее, но существенно больше кода.

#### 10.5.5 Инвалидация кэша

Ключ кэша: `wallpaperId + ширина + высота + radius`.

| Триггер | Как ловим |
|---|---|
| Смена обоев | `WallpaperManager.getWallpaperId(FLAG_SYSTEM)` — **без разрешений** (`WallpaperManager.java:2275`); сравнить с сохранённым |
| Смена обоев (дополнительно) | `WallpaperManager.addOnColorsChangedListener(...)` — тоже **без разрешений** |
| Поворот / смена плотности / раскрытие складного | `onConfigurationChanged()` → пересчитать под новые метрики |
| Возврат в приложение | `onResume()` — сверка `wallpaperId`, при расхождении пересчитать |
| Смена режима или прозрачности | `applyWallpaperAndBlur()` |

`Intent.ACTION_WALLPAPER_CHANGED` не используем: устарел, а `getWallpaperId()` даёт ту же информацию надёжнее и без ресивера.

#### 10.5.6 Что в `baked` плохо (честно)

* **Не настоящие обои** — нарушение T2, сознательное.
* **Живые обои не поддерживаются вообще** (только дефолтная картинка/градиент).
* **Рассинхрон**: после смены обоев подложка обновится только при срабатывании триггера (на практике — в `onResume`).
* **На Android 13/14 без `MANAGE_EXTERNAL_STORAGE`** режим деградирует до градиента по `WallpaperColors` — визуально «похоже по цвету», но это не рисунок обоев.
* Память: даже при downscale битмап живёт в куче процесса (∼0.7 МБ — терпимо).

#### 10.5.7 Версии

`RenderEffect` — **API 31**. Своего блюра для старых версий нет (T3), поэтому на API < 31 пункт `baked` недоступен (переключатель выключен, §5.6), как и `live`.

---

### 10.6 Считается ли блюр каждый кадр, или он «запечён»?

Разобрать этот вопрос нужно по частям, потому что в нём **два разных утверждения**, и из них верно только второе.

> **Утверждение А (неверное): «вывод терминала попадает в область блюра, поэтому блюр пересчитывается».**
> **Утверждение Б (верное): «пока терминал выдаёт кадры, экран перекомпонуется, а блюр — это проход рендер-движка внутри композитинга».**

**Почему А неверно.** `FLAG_BLUR_BEHIND` — это *cross-window* эффект: размывается содержимое **под** окном. Слой нашего терминала находится **над** wallpaper-слоем, поэтому в выборку блюра он не попадает никогда — ни при выводе, ни при вводе. Вход блюра = обои (и всё, что ниже нашего окна). Содержательно вывод терминала на результат размытия не влияет.

**Почему Б верно.** Блюр — это не закэшированная картинка с ключом «содержимое обоев», а **проход рендер-движка в момент композитинга**; AOSP прямо пишет: *«в стандартном механизме рендеринга Android 12 эта логика реализована в `BlurFilter.cpp`»*. У приложения нет API ни чтобы получить результат блюра, ни чтобы пометить его «не устарел». Поэтому вопрос «пересчитается ли блюр» сводится к другому вопросу: **компонуется ли кадр вообще**.

#### Что здесь доказано, а что — вывод

| Статус | Утверждение | Откуда |
|---|---|---|
| ✅ Доказано | Блюр делает рендер-движок SurfaceFlinger на GPU; стоимость растёт с радиусом (*«GPU считывает цвета из большей области»*) | AOSP «Размытие окна» |
| ✅ Доказано | Блюр может отключаться системой во время выполнения; есть `isCrossWindowBlurEnabled()`, `addCrossWindowBlurEnabledListener()`, `adb shell wm disable-blur`, `Settings.Global.DISABLE_WINDOW_BLURS` | `WindowManager.java:1850/1908`, `Settings.java:17364`, AOSP |
| ✅ Доказано | В приложении блюра нет: мы выставляем только флаг и радиус, никакого битмапа не получаем и не храним | Публичный API |
| ✅ Доказано | **Нет кадров → нет композитинга → стоимость блюра равна нулю.** SurfaceFlinger компонует по требованию; иначе любой телефон в покое жег бы батарею на 60 fps | Архитектура дисплейного конвейера |
| ⚠️ **Вывод, не факт** | При появлении кадра от нашего окна блюр-проход выполняется заново, **даже если вход (обои) не изменился** | Вывод из «это проход рендер-движка» + отсутствия content-hash кэша в публичном API. **Исходники SurfaceFlinger (C++) в SDK не входят**, поэтому строчкой кода это не подтверждено |

**Практический итог (честный):** да, во время активного вывода блюр, по всей видимости, считается на каждый скомпонованный кадр — но **не потому**, что вывод «попал в блюр», а потому что кадр вообще компонуется. Отменить это из приложения нельзя; это поведение системного композитора, и его нельзя обойти иначе чем «не выдавать кадры».

**Что это означает конкретно для терминала.** Драйвер стоимости — **не** «движутся ли обои», а «производит ли терминал кадры»:

| Состояние | Композитинг | Стоимость блюра |
|---|---|---|
| Терминал в покое, обои статичные, курсор не мигает | нет | **0** |
| Терминал в покое, **живые** обои | есть, постоянно | пересчёт каждый кадр — неизбежно, мы на это не влияем |
| Идёт вывод (`cat`, `top`, ввод с клавиатуры) | есть, пачками | пересчёт на каждый кадр пачки |
| Прозрачность 0% | — | 0, флаг снят |

| Фактор | Влияние |
|---|---|
| Радиус | Стоимость растёт с радиусом: *«GPU считывает цвета из большей области для увеличения радиуса размытия»* (AOSP). Наш выбор — **20 px**, самый дешёвый из рекомендованных; >150 px AOSP прямо запрещает по производительности. |
| Площадь | Блюр-бихайнд размывает **весь экран за окном**, а наше окно полноэкранное → площадь максимальна и **не регулируется** публичным API. Единственные рычаги — радиус и число кадров. |
| Кто платит | GPU компоновщика. Процесс Termux не тратит ни CPU, ни памяти на блюр. |
| Прозрачность 0% | Блюр выключен, стоимость нулевая. |

> **Важное следствие:** частичный репейнт (`invalidateRowRange`, dirty-rect в `TerminalRenderer`) экономит **наше** рисование, но **не** отменяет перекомпозицию всего экрана. Поэтому для стоимости блюра важна не *площадь* invalidate, а их **частота**.

**Практические меры (уже заложены или предлагаются):**

1. Радиус фиксированный, `20` — не делаем его настраиваемым, иначе пользователь легко выберет 150+ и получит просадки.
2. Блюр включается только при `percent > 0` (§10.4).
3. AOSP рекомендует *«complement the blur radius with a translucent layer of color»* для читаемости — у нас эта «прослойка» уже есть: это сам фон терминала с альфой 128…255 поверх размытых обоев. Отдельный scrim не нужен.
4. **Фолбэк при недоступном блюре.** AOSP рекомендует на случай `isCrossWindowBlurEnabled() == false` делать фон более плотным. Предлагается: если блюр запрошен, но системно недоступен (энергосбережение, слабый GPU), **временно снижать эффективную прозрачность вдвое**, чтобы текст остался читаемым на пёстрых обоях:

```java
/**
 * Transparency actually handed to the terminal. When the user asked for blur but the
 * system cannot provide it, halve the transparency: AOSP explicitly recommends making
 * the layer more opaque when cross-window blur is unavailable, otherwise text over a
 * busy wallpaper becomes unreadable.
 */
private int getEffectiveBackgroundTransparency() {
    final int percent = mProperties.getTerminalBackgroundTransparency();
    if (percent <= 0) return 0;
    if (mProperties.isTerminalBackgroundBlurEnabled() && !isCrossWindowBlurEnabledCompat()) {
        return percent / 2;
    }
    return percent;
}
```

   `applyTerminalTransparency()` должен использовать `getEffectiveBackgroundTransparency()`, а `applyWallpaperAndBlur()` — по-прежнему «сырой» `percent` (флаг `FLAG_SHOW_WALLPAPER` зависит только от того, включена ли фича).

5. Не включать блюр по умолчанию — дефолт `false` (уже так).

---

### 10.7 Статичные обои: как получить гарантированный ноль пересчёта (режим `live`)

Поскольку управлять блюром из приложения нельзя, единственный рычаг — **не производить кадры**. Это даёт точную и проверяемую формулировку требования T7:

> **Обои статичные + терминал не выдаёт кадров ⇒ композитинга нет ⇒ блюр не считается вообще.**
>
> (Это не «пересчитывается редко», а именно ноль: без invalidate не запускается VSYNC-цикл композитора.)

#### Что для этого должно быть true — аудит Termux

| Источник кадров в покое | Состояние сейчас | Что делать |
|---|---|---|
| Мигание курсора | **Выключено по умолчанию**: `TermuxPropertyConstants.java:213` → `DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE = 0`, при rate 0 блинкер не стартует (`TerminalView.java:2443-2447`) | Ничего. Если пользователь включит (100–2000 мс) — это 0.5–10 кадров/с в покое, т.е. 0.5–10 проходов блюра/с; приемлемо, но это стоит знать |
| Блинкер в фоне | Останавливается в `onStop()` (`TermuxTerminalViewClient.java:190`), стартует в `onResume()` (стр. 181) | Ничего — уже правильно |
| Периодические `postDelayed` | Только `mWheelImpulseRunnable` (после колеса) и разовый `getShowSoftKeyboardRunnable()` | Ничего |
| Анимации / scrim | Нет | **Не добавлять** (см. чек-лист) |
| Вывод работающей программы | Есть, когда программа реально пишет | Неизбежно и легитимно |

#### Чек-лист «не сломай»

1. **Не вызывать `invalidate()` безусловно** — ни в `onResume()`, ни в `onSizeChanged()`, ни в `onDraw()`. Каждый такой вызов = кадр = проход блюра.
2. **Не добавлять постоянно работающих анимаций** в окне терминала при включённом блюре (никаких «дышащих» подсветок, бегущих градиентов, анимированного scrim).
3. **Помнить, что важна частота, а не площадь**: маленький `invalidate()` курсора всё равно вызывает перекомпозицию всего экрана. Оптимизировать надо количество инвалидаций в секунду.
4. **Мигание курсора — единственный легитимный периодический источник**, и он по умолчанию выключен. Не добавлять новых.
5. **Живые обои — единственный случай гарантированного непрерывного пересчёта**, и он нам не подвластен (источник меняется сам). Тут уместно хотя бы *знать* об этом:
   ```java
   // WallpaperManager.java:2051-2067 — requires <queries> on API 34+ (QUERY_ALL_PACKAGES on <=33)
   boolean isLiveWallpaper() {
       WallpaperManager wm = WallpaperManager.getInstance(this);
       return wm != null && wm.getWallpaperInfo() != null;
   }
   ```
   В `AndroidManifest.xml` для API 34+ нужен `<queries><action android:name="android.service.wallpaper.WallpaperService"/></queries>`, иначе `getWallpaperInfo()` вернёт `null` и живые обои будут определены как статичные. **Это детект только для информационных целей** — отключать блюр на живых обоях автоматически не предлагается (пользователь их выбрал сам), но стоит показать подсказку в настройках.

#### Опциональная мера: троттлинг кадров вывода

Единственный реальный способ уменьшить число проходов блюра **во время** активного вывода — реже выдавать кадры. `TerminalView` сейчас репейнтится на каждое обновление эмулятора, т.е. при `yes`/`cat bigfile` может упираться в 60 fps. Ограничение до ~30 fps (`postDelayed`-коалесценция обновлений) вдвое снизит число проходов блюра при почти незаметной потере отзывчивости.

**Статус: не входит в обязательный scope.** Это изменение затрагивает существующее поведение терминала, поэтому включать его стоит только если замеры (§10.7) покажут реальные просадки на целевом устройстве.

---

### 10.7 Как убедиться в этом на устройстве (а не на словах)

Поскольку утверждение о пересчёте на каждый кадр — вывод, а не задокументированный факт, его нужно **проверить замером** до того, как полагаться на него в проектных решениях.

1. **Ноль кадров в покое** (главная проверка T7):
   ```
   adb shell dumpsys gfxinfo com.termux framestats
   ```
   Снять два снимка с интервалом 10 с на статичных обоях с включённым блюром. Разница в числе кадров должна быть **0**. Если кадры идут — искать лишний `invalidate()` по чек-листу §10.6.

2. **Оверхед блюра вообще** — A/B по официальному переключателю (AOSP: `adb shell wm disable-blur 1|0`):
   ```
   adb shell wm disable-blur 1   # блюр выключен системой
   adb shell wm disable-blur 0
   ```
   Сравнитьfps/джерк и показания GPU-профилировщика на одном и том же сценарии вывода.

3. **Профилирование GPU**: Настройки разработчика → «Профилирование GPU» (столбики) или оверлей частоты обновления. В покое график должен быть плоским нулём; при выводе — сравниться с `disable-blur 1`.

4. **Perfetto**: категории `gfx`, `view`, `sched`, `freq` + GPU-счётчики. Смотреть число `SurfaceFlinger`-композиций за интервал покоя и за интервал вывода, с блюром и без.

5. **Стресс-сценарий**: `yes | head -c 50M > /dev/null` или `cat` большого файла при `blur on` / `blur off` — сравнить достижимый fps и нагрев.

> Пункты 1 и 2 — обязательные: они превращают всё рассуждение §10.5 из предположения в измеренный факт.

---

## 11. Крайние случаи и риски

| # | Сценарий | Поведение / решение |
|---|---|---|
| R1 | **0%** | `FLAG_SHOW_WALLPAPER` снят, формат `OPAQUE`, decor красится схемой, рендерер — `alpha=255`. Поведение **побитово** как сегодня. T5 выполнено. |
| R2 | **50%** | `alpha=128`. Текст остаётся полностью непрозрачным (пишется `SRC_OVER` поверх), обои читаются как подложка. |
| R3 | Полноэкранный режим (`FLAG_FULLSCREEN`) | Флаги совместимы; обои видны на всю площадь. |
| R4 | `reverseVideo` (DECSCNM) | `bgColor` берётся из `palette[FOREGROUND]` — альфа применяется к нему, логика не ломается. |
| R5 | Смена цветовой схемы на ходу (Termux:Style) | `bgColor` читается из палитры каждый кадр; прозрачность — отдельный множитель. Совместимо. |
| R6 | Зум/смена шрифта | Переприменение в `setTextSize()`/`setTypeface()` (§7.2). **Без этого прозрачность пропадёт** — самый вероятный баг при реализации. |
| R7 | Частичный репейнт (курсор, скролл) | `SRC` + сброс xfermode (§6.2). Иначе накопление альфы. |
| R8 | Новая вкладка / placeholder page | `applyTerminalTransparency()` в `onCreateViewHolder()` (§8). |
| R9 | Многовкладочный свайп | Соседние страницы в `mAttachedViews` получают значение одновременно; плюс `offscreenPageLimit=1`. |
| R10 | Multi-window / freeform | Система может не показать обои (окно не wallpaper-target). Обои просто не появятся — не краш. В блюре за окном окажется соседнее приложение (ожидаемо). |
| R11 | Энергосбережение | `isCrossWindowBlurEnabled()==false` → флаг блюра снимается, обои остаются. |
| R12 | Live wallpaper | Анимируется (это плюс T2). На API 31-33 тапы уходят в обои — ограничение платформы; на 34+ отключаем (§9.2). **Но:** живые обои — единственный случай, где блюр гарантированно пересчитывается непрерывно (§10.6). Это не баг, а цена анимации; при жалобах на нагрев — первое, что стоит проверить. |
| R13 | Производительность/память | Дополнительный полноэкранный слой композитинга + живая wallpaper-surface. Заметно только на слабых GPU, и **только при включённой** фиче (0% — ноль стоимости). |
| R19 | **Вывод терминала вызывает пересчёт блюра** (§10.5) | Вывод **не** попадает в область блюра, но любой кадр окна = перекомпозиция = проход блюра. Из приложения это не отменяется. Митигация: радиус 20, ноль кадров в покое (§10.6), при необходимости — троттлинг (опционально). Замерять по §10.7. |
| R20 | Утверждение §10.5 о пересчёте — вывод, а не доказанный факт | Исходники SurfaceFlinger в SDK не входят. Перед принятием решений по производительности **обязателен замер** (§10.7, пункты 1-2). Если замер покажет, что композитор блюр кэширует — это только улучшит картину, решение не меняется. |
| R14 | Скриншоты, «Последние приложения» | В превью могут быть обои вместо чёрного фона. Приемлемо. Если появятся жалобы — рассмотреть `FLAG_SECURE` (тогда обои в превью скроются). |
| R15 | Бэкап настроек | Проверить, что новые ключи попадают в `TermuxSettingsBackupUtils`; при необходимости добавить в список бэкапа. |
| R16 | Поля (`terminal-margin-*`) | Внутри страницы за пределами `TerminalView` обои видны **на 100%**, а не на N%. Это корректно («поля» — вне терминала), но стоит иметь в виду: при больших полях контраст выглядит ступенчатым. |
| R17 | IME | Клавиатура — отдельное окно поверх; прозрачность терминала на неё не влияет. |
| R18 | Панель вкладок / ExtraKeys / панель ввода | Имеют собственные непрозрачные фоны — не затрагиваются. |
| R21 | **Глифы из fallback-шрифта** (рамки U+2500–257F, блоки ░▒▓█, брайль, powerline, emoji) | Их advance отличается от `mFontWidth = measureText("X")` → `fontWidthMismatch = true` → ран рвётся посимвольно, pass A его пропускает, фон рисуется в pass B. Если бы pass B заливал `SRC_OVER`, получилось бы `2A−A²` (при A=0.5 → 0.75) плюс `(1−A)/(2−A)` примеси базового цвета — визуально «плотные» ячейки ровно по линиям рамок. Лечение: `mBgPaint` + `SRC` (§6.4). Косвенный признак: при установке шрифта с полным покрытием рамок (`~/.termux/font.ttf`) симптом исчезает. |
| R22 | **Курсор** | Прямоугольник курсора — тоже фоновая заливка: `mBgPaint` + `SRC` + `mBackgroundAlpha`. Непрозрачный курсор был последним «сплошным» пятном на прозрачном экране. Символ под блочным курсором остаётся непрозрачным (это текст). |
| R23 | **Страница-заглушка «новая вкладка»** | `terminal_placeholder_hint_container` — `match_parent`, и раньше заливался непрозрачным цветом схемы поверх уже залитого `TerminalView` (у которого `mEmulator == null` → placeholder с альфой A). Контейнер сделан прозрачным: один слой вместо двух. Нельзя красить его «в ту же альфу» — получилось бы `2A−A²`. |
| R24 | **OSC 4/11/104 в фоновой сессии** | `onColorsChanged()` инвалидировал только активную view. Теперь репейнт идёт по всем привязанным страницам (`invalidateAllTerminalViews(null, false)`) — но **без** `onScreenUpdated()`, иначе прокрученная вкладка прыгает в конец (`mTopRow = 0`). |
| R25 | **Скроллбар, хандлы выделения, popup'ы** | Это UI-хром поверх терминала, рисуется `SRC_OVER` с собственной малой альфой (`0x0D/0x1F`) → над полупрозрачным фоном итог `a + A(1−a)`. Намеренно: оверлей должен смешиваться с содержимым. К фонам глифов не относится. |

---

## 12. План реализации

| Шаг | Файл | Что сделать |
|---|---|---|
| 1 | `termux-shared/…/TermuxPreferenceConstants.java` | 6 констант (`KEY_*`, `DEFAULT/MIN/MAX`) |
| 2 | `termux-shared/…/TermuxAppSharedPreferences.java` | 4 метода (get/set transparency, get/set blur) |
| 3 | `termux-shared/…/TermuxAppSharedProperties.java` | 2 геттера |
| 4 | `app/src/main/res/values/strings.xml` | 4 строки (прозрачность + 3 строки блюра) |
| 5 | `app/src/main/res/xml/termux_display_preferences.xml` | `SeekBarPreference` 0-50 + `SwitchPreferenceCompat` |
| 6 | `app/…/fragments/settings/DisplayPreferencesFragment.java` | проводка (§5.6), отключение блюра при 0% и на API <31 |
| 7 | `terminal-view/…/TerminalRenderer.java` | `mBackgroundAlpha`, сеттер, заливка в `SRC` (§6.4) |
| 8 | `terminal-view/…/TerminalView.java` | поле, сеттер, `isOpaque()`, `onDraw`-заглушка, переприменение в `setTextSize`/`setTypeface` (§7) |
| 9 | `app/…/terminal/TerminalPagerAdapter.java` | `setTerminalBackgroundTransparency()` + вызов в `onCreateViewHolder()` |
| 10 | `app/…/terminal/SessionPagerManager.java` | делегирование + вызов в `setup()` |
| 11 | `app/src/main/res/values/themes.xml` | `…NoActionBar.Wallpaper` |
| 12 | `app/…/TermuxActivity.java` | `applyWallpaperTheme()`, `applyWallpaperAndBlur()`, `applyBackgroundBlur()`, `applyTerminalTransparency()`, правка `applySchemeColors()`/`applySystemBarColors()`, вызовы в `onCreate()` и `reloadActivityStyling()` |
| 13 | `TermuxSettingsBackupUtils` | проверить/добавить ключи в бэкап |
| 14 | Сборка | `./gradlew :app:assembleDebug` — проверить, что `PixelFormat`, `FLAG_SHOW_WALLPAPER`, `setBlurBehindRadius`, `setWallpaperTouchEventsEnabled` компилируются при `compileSdk 34` с нужными `Build.VERSION` guard'ами |
| 15 | **Замер нулевых кадров в покое** | §10.7 п.1-2. Сделать **до** выкладки: подтвердить, что при статичных обоях и включённом блюре `gfxinfo` не показывает новых кадров за 10 с |

Порядок важен: шаги 7-8 можно отладить **независимо** от оконной части, временно задав прозрачность из кода — так проще всего увидеть, что рендерер не копит альфу.

---

## 13. Тестовый план

**Рендер (можно на любом API):**

1. Прозрачность 25% → фон терминала визually смешан с прошлым содержимым/фоном; текст плотный.
2. Включить мигание курсора и оставить на 30 секунд — прозрачность **не должна** «сгущаться» (проверка R7).
3. `ls --color`, `htop`, `vim` с подсветкой — цветные блоки остаются плотными, фон вокруг прозрачный.
4. Зум жестом / смена размера шрифта в настройках → прозрачность сохраняется (R6).
5. Прокрутка, выделение текста, переключение вкладок, новая вкладка — без артефактов.

**Окно:**

6. API 31+: 0% → обои не видны, `adb shell dumpsys window windows` показывает отсутствие `FLAG_SHOW_WALLPAPER`; >0% → флаг есть, формат поверхности `TRANSLUCENT`.
7. Смена 0% → 30% и обратно — пересоздание активити без чёрной вспышки (проверка B6).
8. Статические и живые обои; поворот экрана; сворачивание/разворачивание.
9. Тёмная и светлая схемы — иконки статус-бара остаются читаемыми.

**Блюр (API 31+):**

10. Блюр вкл → обои размыты; выкл → чёткие.
11. Включить энергосбережение при включённом блюре → блюр пропадает, обои остаются, краша нет.
12. Выключить энергосбережение → блюр возвращается (слушатель, §10.3).
13. API < 31 → переключатель блюра выключен с надписью «Требуется Android 12 или новее»; прозрачность работает.

**Отсутствие лишних кадров (T7, §10.6) — обязательно:**

14. **Статичные обои, блюр вкл, терминал молчит** → `adb shell dumpsys gfxinfo com.termux framestats`, два снимка с интервалом 10 с: **прирост кадров = 0**. Это главная проверка требования T7.
15. Повторить п.14 с **живыми** обоями → кадры идут (ожидаемо, R12), но без просадок ниже 60 fps на целевом устройстве.
16. Проверить, что мигание курсора **выключено по умолчанию** и не создаёт кадров (`terminal-cursor-blink-rate = 0`). Включить принудительно → убедиться, что кадры появляются ровно с частотой блинка, а не 60 fps.
17. Свернуть приложение (`onStop`) → блинкер остановлен, `gfxinfo` не растёт в фоне.

**Стоимость блюра (§10.5, §10.7):**

18. A/B через `adb shell wm disable-blur 1` / `0` на сценарии активного вывода: зафиксировать разницу в fps/джерке.
19. Стресс: `yes | head -c 50M > /dev/null` при блюре вкл/выкл — сравнить fps и нагрев.

**Регрессии:**

20. Снэпшот потребления памяти при 0% и при 50% (R13).
21. Проверить, что панель вкладок, ExtraKeys и панель ввода остались непрозрачными.

---

## 14. Альтернативы, которые отвергнуты

| Вариант | Почему нет |
|---|---|
| `WallpaperManager.getDrawable()` как подложка (в т.ч. «запечённый» блюр по снапшоту) | Нарушает T2 (копия, не обои; не живые; не обновляются; память). И главное — **технически недоступно**: с Android 14 метод «always throw a `SecurityException`», на Android 13 возвращает *дефолтные* обои вместо пользовательских (`WallpaperManager.java:1046-1068`). То есть «запечь» реальные обои нельзя в принципе |
| «Запечённый» блюр как режим/фолбэк | Нечего запекать (см. выше) + нарушает T2. Вместо кэширования результата устраняем лишние кадры (§10.6) — это даёт ту же нулевую стоимость в покое, но без копии обоев |
| `FLAG_BLUR_BEHIND` как единственный источник прозрачности | Блюр ≠ прозрачность; это только фильтр поверх обоев |
| `Window#setBackgroundBlurRadius()` | Требует `windowIsFloating` — ломает разметку полноэкранного окна (§10.1) |
| Свой блюр для API <31 (RenderScript / `RenderEffect` / downscale) | Прямо запрещено требованием T3; плюс стоимость кадра и рассинхрон с обоями |
| Всегда держать окно полупрозрачным, а «0%» эмулировать `alpha=255` | Нарушает T5: `FLAG_SHOW_WALLPAPER` держал бы wallpaper-surface живойalways, расходуя память и GPU у 100% пользователей |
| Прозрачить все фоны подряд (включая цветные ячейки) | Теряется читаемость цветного вывода, ломает семантику «программа явно задала фон» (§6.3) |
| Диапазон 0-100% | Требование жёстко задаёт 0-50; 50% — это уже предел, за которым текст на произвольных обоях перестаёт читаться |
