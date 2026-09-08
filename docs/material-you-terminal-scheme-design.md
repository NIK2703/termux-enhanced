# Material You — цветовая схема терминала по обоям

**Статус:** проектное предложение (design doc), код не написан.
**Target:** Android 12+ (API 31). Android 11 и ниже — функция не предлагается.
**Зависимость от Termux:Styling:** нет. Это самостоятельный источник схемы, который
дополняет существующие («Default» + схемы из Termux:Style) ещё одним пунктом выбора.

---

## 1. Постановка задачи

Нужна схема терминала (16 ANSI-цветов + `background` / `foreground` / `cursor`), которая:

1. выводится из обоев пользователя в духе Material You;
2. имеет **свою light- и свою dark-версию**, выбираемые по текущему ночному режиму Termux
   (так же, как сейчас выбираются `colors.light.properties` / `colors.dark.properties`);
3. строится **только на системных библиотеках** — без чтения файла обоев, без Python,
   без собственной реализации Monet и без внешнего пакета Termux:Style;
4. добавляется как **отдельный пункт выбора** рядом с базовыми темами, а не заменяет их;
5. алгоритм получения *терминальных* цветов повторяет
   [kde-material-you-colors](https://github.com/luisbocanegra/kde-material-you-colors).

Ключевое отличие от kde-material-you-colors: тот **сам** квантует обои
(`QuantizeCelebi` → `Score` → seed → `TonalPalette`). На Android всё это уже сделано
**системой** — результат лежит в публичных ресурсах `android.R.color.system_*`.
Значит вместо «посчитать Monet» задача сводится к «прочитать готовую палитру системы и
грамотно разложить её в 16 ANSI-слотов».

---

## 2. Что реально отдаёт система на Android 12+

Всё ниже проверено по `android-34/android.jar` и по ресурсам `material-1.12.0.aar`.

### 2.1 Тональные палитры (API 31+)

Публичные ресурсы:

```
android.R.color.system_{accent1,accent2,accent3,neutral1,neutral2}_{0,10,50,100,200,...,900,1000}
```

Соответствие Material 3:

| ресурс            | роль M3           | M3-tone |
|-------------------|-------------------|---------|
| `system_accent1_*`| `primary`         | —       |
| `system_accent2_*`| `secondary`       | —       |
| `system_accent3_*`| `tertiary`        | —       |
| `system_neutral1_*`| `neutral`        | —       |
| `system_neutral2_*`| `neutralVariant` | —       |

**Формула перевода:** `tone = 100 - X / 10`, где `X` — суффикс ресурса.

| X    | 0   | 10  | 50  | 100 | 200 | 300 | 400 | 500 | 600 | 700 | 800 | 900 | 1000 |
|------|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|-----|------|
| tone | 100 | 99  | 95  | 90  | 80  | 70  | 60  | 50  | 40  | 30  | 20  | 10  | 0    |

Это не догадка, а буквальное содержимое `material-1.12.0.aar`:

```xml
<!-- res/values-v31/values-v31.xml (material-components-android) -->
<color name="m3_ref_palette_dynamic_primary40">@android:color/system_accent1_600</color>
<color name="m3_ref_palette_dynamic_primary90">@android:color/system_accent1_100</color>
<color name="m3_ref_palette_dynamic_neutral99"> @android:color/system_neutral1_10</color>
<color name="m3_ref_palette_dynamic_primary100">@android:color/system_accent1_0</color>
```

То есть система отдаёт **13 опорных тонов**: `{0,10,20,30,40,50,60,70,80,90,95,99,100}`.

### 2.2 Как получить *любой* тон, а не только эти 13

`TonalPalette` в Monet — это пара `(hue, chroma)`; тон свободный:
`TonalPalette.fromInt(seed)` = `(Hct.fromInt(seed).getHue(), Hct.fromInt(seed).getChroma())`,
а `palette.tone(t)` = `Hct.from(hue, chroma, t).toInt()`.

Следовательно:

```java
int argb500 = getColor(android.R.color.system_accent1_500); // tone 50
Hct  h      = Hct.fromInt(argb500);
TonalPalette primary = TonalPalette.fromHueAndChroma(h.getHue(), h.getChroma());
int anyTone = primary.tone(87);   // бит-в-бит то, что сгенерировала система
```

Это важно: мы **не аппроксимируем** палитру, а восстанавливаем её точно и получаем
непрерывную шкалу тонов для точной подгонки контраста.

Все нужные классы уже лежат в `com.google.android.material:material:1.12.0`
(в `classes.jar` AAR, проверено):

```
com.google.android.material.color.utilities.Hct            from/fromInt/getHue/getChroma/getTone/toInt/setTone
com.google.android.material.color.utilities.TonalPalette   fromInt/fromHct/fromHueAndChroma/tone(int)
com.google.android.material.color.utilities.Contrast       ratioOfTones(DD) / lighter(DD) / darker(DD)
com.google.android.material.color.utilities.Blend          cam16Ucs(III) / hctHue / harmonize
com.google.android.material.color.utilities.TemperatureCache / DislikeAnalyzer / QuantizerCelebi / Score
```

> **Риск:** пакет `color.utilities` отсутствует в `public.txt` Material Components
> (проверено: 0 совпадений) — это внутренний, не публичный API. См. §8.

### 2.3 Семантические роли (API 34+)

`android.R.color.system_surface_dark`, `system_on_surface_variant_light`,
`system_primary_fixed`, `system_outline_dark`, `system_palette_key_color_primary_light`,
… — полный набор M3-ролей в вариантах `_light` / `_dark`.

**Не используем**: минимальная версия у них выше 31, а нам нужен единый путь для всех 12+.
Роли считаем сами из палитр (§5), благо это детерминированно.

### 2.4 Чего в системе нет

| потребность                        | статус                                                        |
|------------------------------------|---------------------------------------------------------------|
| Oklab (`ColorSpace.Named.OK_LAB`)  | только с API 36 → не годится, используем CAM16-UCS            |
| `WallpaperColors.getAllColors()`   | не публично. Публичны только `getPrimary/Secondary/TertiaryColor()` |
| Ранжированные «лучшие» цвета обоев | нет публичного API → см. §5.2                                  |

`WallpaperColors.getSecondaryColor()` / `getTertiaryColor()` — API 31+, `@Nullable`.
Это **реальные цвета, выбранные системой из обоев** (те же кандидаты, из которых Monet
строит палитры). Их и возьмём как основу для ANSI-акцентов.

---

## 3. Эталон: как это делает kde-material-you-colors

Разбор `schemeconfigs.py`, `m3_scheme_utils.py`, `extra_image_utils.py`, `color_utils.py`.

### 3.1 Пайплайн получения исходных цветов

```python
# extra_image_utils.py — буквально
SCORE_OPTIONS = ScoreOptions(desired=7,                 # «ANSI colors target»
                             fallback_color_argb=0xFF4285F4,  # Google Blue
                             filter=True)
result = QuantizeCelebi(pixel_array, 128)   # 128 кластеров
ranked = Score.score(result, SCORE_OPTIONS) # топ-7 «лучших» цветов → colors["best"]
```

Затем `themeFromSourceColor(seed)` строит `SchemeTonalSpot` (вариант по умолчанию) в двух
режимах → `colors["schemes"]["light"|"dark"]` и палитры
`tones_primary/secondary/tertiary/neutral/neutralVariant` (101 тон).

### 3.2 Разложение в терминальную палитру

Обозначения: `blend(A, B, r)` — смешивание **в Oklab**, `r` — доля `B`.

```
bg  = scheme.surface

# 7 акцентов
c_i = best_colors[i]                      # или tones_primary[50+8i] / tones_tertiary[50+8i]
dark : c = lighteen(c_i, 0.2, neutral[99])                # поднять слишком тёмные
       c = blend2contrast(c, bg, neutral[99], 2.5, 0.01)  # контраст >= 2.5:1
light: c = scale_saturation(c_i, 1)
       c = blend2contrast(c, bg, neutral[10], 2.0, 0.01)  # контраст >= 2.0:1

sort_colors_luminance(c_1..c_7)           # монотонная рампа по светлоте

ref      = dark ? neutral[99] : neutral[1]
normal_i = blend(ref, c_i, 0.95)          # → color1..color7
intense_i= blend(ref, c_i, 0.82)          # → color9..color15   («bright»)
faint_i  = blend(bg,  c_i, 0.70)          # → color16..         (приглушённые)

color0   = bg
color8   = blend(bg, secondary[90|25], 0.8)

foreground        = blend(bg, secondary[dark?90:25], 0.98)
foregroundIntense = secondary[dark?90:25]
cursor            = scheme.onSurface
```

Три вывода, которые надо перенести:

1. **`surface` → фон**, а не «чёрный»: фон терминала = поверхность темы.
2. **Жёсткий порог контраста** каждого акцента к фону (2.5:1 в тёмной теме, 2.0:1 в светлой)
   с подмешиванием neutral'и до достижения порога. Именно это отличает «читаемую» схему от
   «красивой, но нечитаемой».
3. **Три уровня одной и той же семёрки** (normal / intense / faint) дают согласованную рампу
   вместо 16 независимых цветов.

---

## 4. Архитектура

```
                 ┌──────────────────────────────────────────────────────┐
                 │  Источник (только системные API, API 31+)            │
                 ├──────────────────────────────────────────────────────┤
                 │ A. android.R.color.system_accent{1,2,3}_*, _neutral* │  ← тональные палитры
                 │ B. WallpaperManager.getWallpaperColors(FLAG_SYSTEM)  │  ← 3 реальных цвета обоев
                 │ C. WallpaperManager.getWallpaperId(FLAG_SYSTEM)      │  ← детектор смены обоев
                 │ D. OnColorsChangedListener                           │  ← нотификация о смене
                 └───────────────┬──────────────────────────────────────┘
                                 │
                    MaterialYouSource (immutable snapshot)
                    { P, S, T, N, NV : TonalPalette ; accents[≤7] ; token }
                                 │
                    TerminalPaletteBuilder.build(source, isDark, opts)
                                 │
                    ┌────────────┴────────────┐
                    │  Properties (тот же     │   ← ровно тот формат, что и
                    │  формат, что у          │     ~/.termux/colors.properties:
                    │  colors.properties)     │     background/foreground/cursor/color0..colorN
                    └────────────┬────────────┘
                                 │
                    TerminalColors.COLOR_SCHEME.updateWith(props)
                                 │
                    TermuxColorSchemeManager.recompute()   ← без изменений
```

**Ключевое решение:** генератор выдаёт `Properties` того же вида, что и
`~/.termux/colors.properties`. Тогда не меняется вообще ничего ниже по стеку —
ни `TerminalColorScheme`, ни рендерер, ни `TermuxColorSchemeManager`, ни панель
клавиш. Схема Material You — просто ещё один источник `Properties`.

**Второе решение:** схема **не пишется на диск**. `colors.light.properties` /
`colors.dark.properties` остаются владением Termux:Style; запись туда сгенерированной
схемы сломала бы семантику «Default» и перезаписала бы пользовательский файл.
Выбор хранится в `termux.properties` как значение `color-scheme-light` / `color-scheme-dark`.

---

## 5. Алгоритм построения палитры

### 5.1 Вход

```java
public final class MaterialYouSource {
    public final TonalPalette primary, secondary, tertiary, neutral, neutralVariant;
    public final int[] accents;      // до 7 ARGB — кандидаты в ANSI 1..7
    public final long token;         // версия снапшота для инвалидации кеша
}
```

Сборка (`SystemPaletteSource`):

```java
static MaterialYouSource read(Context ctx) {
    TonalPalette P  = paletteFrom(ctx, android.R.color.system_accent1_500);
    TonalPalette S  = paletteFrom(ctx, android.R.color.system_accent2_500);
    TonalPalette T  = paletteFrom(ctx, android.R.color.system_accent3_500);
    TonalPalette N  = paletteFrom(ctx, android.R.color.system_neutral1_500);
    TonalPalette NV = paletteFrom(ctx, android.R.color.system_neutral2_500);

    WallpaperColors wc = WallpaperManager.getInstance(ctx)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
    List<Integer> acc = new ArrayList<>();
    if (wc != null) {
        addNonNull(acc, wc.getPrimaryColor());
        addNonNull(acc, wc.getSecondaryColor());   // API 31+
        addNonNull(acc, wc.getTertiaryColor());    // API 31+
    }
    accentsFromPalette(acc, P);   // добиваем до 7 (см. ниже)
    return new MaterialYouSource(P, S, T, N, NV, acc, tokenOf(wc, N));
}

static TonalPalette paletteFrom(Context ctx, @ColorRes int resId) {
    int argb = ContextCompat.getColor(ctx, resId);     // тон 50
    Hct h = Hct.fromInt(argb);
    return TonalPalette.fromHueAndChroma(h.getHue(), h.getChroma());
}
```

`token` = хеш из `WallpaperManager.getWallpaperId(FLAG_SYSTEM)` + всех пяти `tone(50)`
+ набора акцентов. Этого достаточно: смена обоев меняет и id, и палитры; переключение
«обои-темы» в системе меняет палитры при том же id.

### 5.2 Кандидаты в ANSI-акценты (7 штук)

В системе нет публичного «топа цветов обоев», поэтому:

1. Берём до 3 реальных цветов из `WallpaperColors` (если не null и не «неподходящие»
   по `DislikeAnalyzer`).
2. Добиваем до 7 поворотом hue от ключевого цвета `primary` — ровно тот приём, которым
   Material 3 строит многоцветные варианты (`SchemeRainbow` / `SchemeFruitSalad` /
   `DynamicScheme.getRotatedHue`):

```java
double C_ACC = 48.0;                    // хрома акцента
double T_ACC = isDark ? 80.0 : 40.0;    // тон акцента (светлый/тёмный)
double[] rot = {0, 60, -60, 120, -120, 180, 30, -30, 90, -90};
for (double r : rot) {
    if (acc.size() >= 7) break;
    Hct c = Hct.from(sanitizeDegrees(seedHue + r), C_ACC, T_ACC);
    if (hueTooClose(c, acc, 15)) continue;   // отсечь почти одинаковые оттенки
    acc.add(c.toInt());
}
```

Результат: 7 гармоничных, заведомо различимых оттенков в «языке» системной палитры
(та же хрома/тон, что у системы) — без чтения пикселей обоев.

> Альтернатива, если захочется буквальной точности к kde: `WallpaperManager.getDrawable()`
> → `Bitmap` 128 px → `QuantizerCelebi.quantize(pixels, 128)` → `Score.score(map, 7, 0xFF4285F4, true)`
> (оба класса уже в material 1.12). Не рекомендую как путь по умолчанию: нужен доступ к
> bitmap'у обоев (на части прошивок возвращает null/nothing), дороже по CPU и может
> разойтись с тем, что реально сгенерировала система. Оставить как опцию
> `material-you-accent-source=wallpaper`.

### 5.3 Фон, текст, курсор

По M3 (spec 2025), тон нейтральной палитры:

| роль                      | light | dark |
|---------------------------|-------|------|
| `surface` (фон терминала) | 98    | 10   |
| `surfaceContainerLowest`  | 100   | 4    |
| `surfaceContainerLow`     | 96    | 10   |
| `onSurface`               | 10    | 90   |
| `outline`                 | NV 50 | NV 60|

```java
int bgTone     = isDark ? 10 : 98;
int fgTone     = isDark ? 90 : 10;      // onSurface
int bg         = N.tone(bgTone);
int fg         = Contrast-safe: N.tone(fgTone), но с гарантией ratioOfTones >= 7
int cursor     = S.tone(isDark ? 90 : 25);   // мягкий акцент вместо «чёрного/белого»
```

Дополнительно — настройка глубины фона (`material-you-background`):
`surface | container-low | container-lowest`, по умолчанию `surface`
(в точности как у kde).

### 5.4 Акценты: порог контраста без циклов

kde ищет подмешивание итеративно в RGB. В Java это решается в один шаг примитивом M3:
`Contrast.lighter(tone, ratio)` / `Contrast.darker(tone, ratio)` возвращают тон,
обеспечивающий заданный WCAG-контраст к исходному тону.

```java
double MIN = isDark ? 2.5 : 2.0;
double bgT = Hct.fromInt(bg).getTone();

for (int i = 0; i < 7; i++) {
    Hct c = Hct.fromInt(accents[i]);
    c.setTone(isDark ? Contrast.lighter(bgT, MIN)      // светлее фона на >= 2.5:1
                     : Contrast.darker (bgT, MIN));    // темнее фона на >= 2.0:1
    if (isDark  && Hct.fromInt(accents[i]).getTone() > c.getTone())
        c.setTone(Hct.fromInt(accents[i]).getTone());  // уже достаточно светлый — не портить
    if (!isDark && Hct.fromInt(accents[i]).getTone() < c.getTone())
        c.setTone(Hct.fromInt(accents[i]).getTone());
    c.setChroma(min(c.getChroma(), MAX_CHROMA_AT_TONE));
    out[i] = c.toInt();
}
sortByTone(out);   // sort_colors_luminance → монотонная рампа
```

Преимущества перед портом питонячьего цикла: детерминированность, O(1) вместо
цикла с шагом 0.01, работа в HCT (перцептивно равномерно), не нужна своя реализация
WCAG-контраста.

### 5.5 Три уровня и раскладка по слотам

```java
int ref = N.tone(isDark ? 99 : 1);
int brightRef = S.tone(isDark ? 90 : 25);

props.put("background", hex(bg));
props.put("foreground", hex(blend(bg, brightRef, 0.98f)));
props.put("cursor",     hex(cursor));

props.put("color0", hex(bg));
for (i = 0..6) props.put("color" + (i+1), hex(blend(ref, out[i], 0.95f)));
props.put("color8", hex(blend(bg, brightRef, 0.80f)));
for (i = 0..6) props.put("color" + (i+9), hex(blend(ref, out[i], 0.82f)));
for (i = 0..6) props.put("color" + (i+16), hex(blend(bg, out[i], 0.70f)));
```

`blend(A, B, r)` — `Blend.cam16Ucs(A, B, r)` (перцептивное пространство CAM16-UCS,
родное для Material; у kde — Oklab, разница на глаз неразличима, зато CAM16-UCS есть
в material-библиотеке на всех API, а системный Oklab — только с API 36).

**Гарантии, которые надо проверить перед записью:**

| пара                        | минимум | действие при невыполнении |
|-----------------------------|---------|---------------------------|
| `foreground` vs `background`| 7.0:1   | сдвинуть тон fg к `onSurface` |
| `cursor` vs `background`    | 3.0:1   | взять `onSurface`          |
| каждый акцент vs `background`| 2.5 / 2.0:1 | уже обеспечено §5.4    |

Проверка — `Contrast.ratioOfTones(Hct.fromInt(a).getTone(), Hct.fromInt(b).getTone())`.

### 5.6 Что ещё заполнить

`TerminalColorScheme` хранит 256 индексов + 3 служебных. Слоты 16..21 заняты faint-версией.
Остальные 22..255 оставляем дефолтными (xterm-куб и серая рампа) — их почти никто не использует,
а их «материализация» даст мутные, неотличимые друг от друга оттенки.

---

## 6. Интеграция в код

### 6.1 Новые файлы

Всё в `termux-shared` (там же, где `ColorSchemeUtils`, — чтобы потом мог пользоваться
и Termux:Float):

```
termux-shared/src/main/java/com/termux/shared/termux/materialyou/
├── MaterialYouSource.java           // неизменяемый снапшот + token
├── SystemPaletteSource.java         // чтение android.R.color.system_* + WallpaperColors (@RequiresApi 31)
├── TerminalPaletteBuilder.java      // §5 — source + isDark + options → Properties
├── MaterialYouSchemeStore.java      // кеш light/dark + фоновый executor + слушатели
├── MaterialYouOptions.java          // разбор termux.properties-настроек
└── ColorMath.java                   // тонкая обёртка над Hct/Contrast/Blend/TonalPalette
```

`ColorMath` — единственное место, где упоминается
`com.google.android.material.color.utilities`. Если пакет исчезнет из Material —
чинится один файл.

### 6.2 Точки в существующем коде

| файл | изменение |
|---|---|
| `ColorSchemeUtils` | новая константа-сентинел `SCHEME_MATERIAL_YOU = "MaterialYou"`; `listStylingColorSchemes()` добавляет её **второй** после `Default` и только при `SDK_INT >= 31`; `applyStylingScheme()` для неё не пишет файл, а только сохраняет выбор; `schemeDisplayName()` → «Material You» |
| `ColorSchemeUtils.ensureColorSchemeForTheme()` | если выбор == `MaterialYou` → `MaterialYouSchemeStore.get(isNight)` вместо чтения файла |
| `TermuxTerminalSessionActivityClient.buildSchemeKey()` | добавить в ключ `MaterialYouSchemeStore.token()` — иначе смена обоев не перекрасит уже открытые сессии (ключ сейчас = ночной режим + mtime/size файлов) |
| `TermuxTerminalSessionActivityClient.ensureColorSchemeLoaded()` | ветка для `MaterialYou` |
| `TermuxActivity` | `onCreate`: регистрация `WallpaperManager.addOnColorsChangedListener` (API 31+); `onResume`: сверка `token`, при расхождении — `invalidateAppliedScheme()` + `updateTermuxActivityStyling()` |
| `DisplayPreferencesFragment` | без изменений —picker уже generic; при `SDK_INT < 31` пункт просто не показывается |
| `TermuxColorSchemeManager` | без изменений (он читает `TerminalColors.COLOR_SCHEME`) |

### 6.3 Настройки (`termux.properties`, все необязательные)

```properties
# surface | container-low | container-lowest   (по умолчанию surface)
material-you-background=surface
# wallpaper | palette — источник акцентов (по умолчанию wallpaper = WallpaperColors)
material-you-accent-source=wallpaper
# порог контраста акцентов; 0 = значения kde (2.5 тёмная / 2.0 светлая)
material-you-accent-contrast=0
# множитель хромы акцентов, 0.5..2.0
material-you-chroma=1.0
```

Ключи добавить в `TermuxPropertyConstants` и в список известных ключей
(иначе `TermuxAppSharedProperties` будет ругаться на неизвестное свойство).

### 6.4 Поведение на Android 11 и ниже

Функция не предлагается: `listStylingColorSchemes()` не добавляет пункт;
если `termux.properties` содержит `MaterialYou` (например, перенесён с другого
устройства), `getSelectedSchemeName()` возвращает его, но резолвер схемы
падает обратно на `Default`. Никаких `@RequiresApi`-веток, никаких чтений
`WallpaperColors` на старых API.

---

## 7. Производительность

| операция | стоимость | где |
|---|---|---|
| 5 × `getColor(system_*)` + `Hct.fromInt` | ~0.2 мс | main thread, OK |
| 5 × `TonalPalette.fromHueAndChroma` | пренебрежимо | main thread |
| построение 16 цветов (HCT + Contrast + Blend) | ~0.5 мс | main thread, OK |
| `WallpaperManager.getWallpaperColors()` | IPC, ~1–5 мс | **один раз** при старте и по `OnColorsChanged` |
| (опция) Celebi по bitmap 128² | 50–200 мс | только фоновый поток, только opt-in |

`buildSchemeKey()` вызывается **на каждом переключении вкладки**, поэтому всё дорогое
уже закэшировано в `MaterialYouSchemeStore`: ключ читает готовый `token` из статики.
Обновление снапшота — только по `OnColorsChanged`, `onResume` и смене ночного режима.

light и dark версии считаются **сразу обе** из одного снапшота (один `MaterialYouSource`
→ два `Properties`). Переключение ночного режима тогда бесплатное: просто берётся
другой закэшированный `Properties`.

---

## 8. Риски и ограничения

1. **`com.google.android.material.color.utilities` — не публичный API.**
   Его нет в `public.txt` Material Components; между версиями он может измениться.
   *Митигация:* версия material зафиксирована (`1.12.0`); весь доступ изолирован в
   `ColorMath`; при обновлении material — точечная правка. *План Б:* перенести
   в проект ~7 классов MCU (`Hct`, `HctSolver`, `Cam16`, `ViewingConditions`,
   `ColorUtils`, `MathUtils`, `Contrast`, `Blend`) — Apache-2.0, ~1500 строк, зависимость
   от material тогда исчезает.

2. **OEM-прошивки.** MIUI/HyperOS/OneUI могут не реализовать динамические цвета —
   тогда `system_*` отдаёт статическую палитру (синий/фирменный цвет) или бросает
   исключение. Обязателен `try/catch` вокруг всего чтения с падением на `Default`.

3. **`WallpaperColors` может вернуть null**, а `getSecondaryColor/getTertiaryColor` —
   null по отдельности (монохромные обои). Добивка до 7 по §5.2 обязательна.

4. **`color0 == background`** (конвенция pywal/kde). Часть TUI рисует «чёрным» по фону
   и становится невидимой. Оставить как есть (совместимость с эталоном), но вынести
   в настройку `material-you-color0=bg|dim`.

5. **Android 12/13 vs 14+:** Monet в 12/13 считает палитры по spec 2021, в 14+ — по 2025.
   Мы генерируем тона сами (§5.3), поэтому вид схемы одинаков на всех версиях — это плюс,
   но фон может слегка отличаться от системных `surfaces` на 12/13.

6. **Переключение «Тема обоев» в системе** не меняет `getWallpaperId()`, но меняет
   `system_*`. Поэтому `token` обязан включать сами палитры, а не только id.

---

## 9. План внедрения

**Фаза 1 — ядро (без UI-настроек).**
`ColorMath` + `SystemPaletteSource` + `TerminalPaletteBuilder` + `MaterialYouSchemeStore`;
сентинел `MaterialYou` в `ColorSchemeUtils`; инвалидация через `buildSchemeKey`.
Результат: пункт «Material You» в picker'е, работает light/dark, перекрашивается при
смене обоев.

**Фаза 2 — нотификации.**
`OnColorsChangedListener` в `TermuxActivity`, сверка `token` в `onResume`,
обе версии (light+dark) считаются заранее.

**Фаза 3 — настройки.** Ключи из §6.3, глубина фона, множитель хромы, порог контраста.

**Фаза 4 (опционально) — хром терминала.** Те же разрешённые тона отдать в
`TermuxColorSchemeManager`, чтобы панель/диалоги/табы красились из
`primary`/`surface`, а не только из `background`/`foreground` схемы. Это уже
выходит за рамки «схемы терминала» — отдельная задача.

**Критерии приёмки Фазы 1:**
- на 5 разных обоях (монохромные, пастельные, насыщенные, тёмные, светлые) схема
  читаема: контраст fg/bg ≥ 7:1, каждого акцента ≥ 2.5:1 (dark) / 2.0:1 (light);
- переключение ночного режима мгновенно перекрашивает терминал;
- смена обоев перекрашивает открытые сессии без перезапуска;
- выбор «Default» или любой схемы Termux:Style полностью отключает Material You;
- на Android 11 пункта нет, на 12+ — есть.
