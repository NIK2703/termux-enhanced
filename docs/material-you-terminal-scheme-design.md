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

Публичность и модификаторы проверены разбором байткода (`classes.jar`), а не по
документации — её на этот пакет нет:

| символ | модификаторы |
|---|---|
| `Hct.from(double,double,double)` / `Hct.fromInt(int)` | `public static` |
| `Hct#getTone/setTone/getChroma/setChroma/getHue/setHue/toInt` | `public` |
| `TonalPalette.fromHueAndChroma(double,double)` | `public static` |
| `TonalPalette#tone(int)` / `#getHct(double)` | `public` |
| `Contrast.ratioOfTones/lighter/darker(double,double)` | `public static` |
| `Blend.cam16Ucs(int,int,double)` | `public static` |
| `new DynamicScheme(Hct, Variant, boolean, double, TonalPalette×5)` | **`public`** |
| `new SchemeX(Hct, boolean, double)` — все 9 вариантов | **`public`** |
| `new MaterialDynamicColors()` | `public` |
| `MaterialDynamicColors#surface()` и остальные 67 ролей | `public`, **НЕ static** |
| `DynamicColor#getArgb(DynamicScheme)` / `#getHct` / `#getTone` | `public` |

Две ловушки, которые стоит запомнить: конструктор `Hct` — **не** публичный
(только `from` / `fromInt`), а роли `MaterialDynamicColors` — методы **экземпляра**
(нужно создавать `new MaterialDynamicColors()`).

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
| Ранжированные «лучшие» цвета обоев | нет публичного API → см. §5.3                                  |

`WallpaperColors.getSecondaryColor()` / `getTertiaryColor()` — API 31+, `@Nullable`.
Это **реальные цвета, выбранные системой из обоев** (те же кандидаты, из которых Monet
строит палитры). Их и возьмём как основу для ANSI-акцентов.

---

## 3. Эталон: как это делает kde-material-you-colors

Разбор по исходникам: `m3_scheme_utils.py`, `schemeconfigs.py` (`ThemeConfig.__init__`,
строки 52–195 и 534–600), `color_utils.py`, `extra_image_utils.py`, `kde_main.py`.

### 3.1 Seed-цвет из обоев

```python
# extra_image_utils.py — буквально
SCORE_OPTIONS = ScoreOptions(desired=7,                        # «ANSI colors target»
                             fallback_color_argb=0xFF4285F4,   # Google Blue
                             filter=True)
result = QuantizeCelebi(pixel_array, 128)   # 128 кластеров
ranked = Score.score(result, SCORE_OPTIONS) # топ-7 «лучших» цветов → colors["best"]
```

### 3.2 Выбор варианта схемы (`scheme_variant`) — ключевой раздел

```python
# m3_scheme_utils.py
schemes = [
    SchemeContent,      # 0
    SchemeExpressive,   # 1
    SchemeFidelity,     # 2
    SchemeMonochrome,   # 3
    SchemeNeutral,      # 4
    SchemeTonalSpot,    # 5  ← по умолчанию
    SchemeVibrant,      # 6
    SchemeRainbow,      # 7
    SchemeFruitSalad,   # 8
]

def getScheme(scheme_variant, source, isDark, contrastLevel, spec):
    return schemes[scheme_variant](
        source_color_hct=source,
        is_dark=isDark,
        contrast_level=contrastLevel,
        spec_version=spec if spec is not None else "2025",
    )
```

`kde_main.py`, справка `--scheme-variant` / `-sv`:
> `0 = Content, 1 = Expressive, 2 = Fidelity, 3 = Monochrome, 4 = Neutral, 5 = TonalSpot,
> 6 = Vibrant, 7 = Rainbow, 8 = FruitSalad (default is 5)`

`kde_config.py`: `"scheme_variant": [args.scheme_variant, 5, 1]`.

**Вариант выбирается один раз и определяет сразу все пять палитр**
(`primary / secondary / tertiary / neutral / neutralVariant`) — это и есть «тип цветовой
схемы». Всё остальное (роли, ANSI) считается одинаково для любого варианта.

### 3.3 Роли

```python
def getColors(scheme, chroma_mult, tone_mult, is_dark):
    colors = {}
    for color in vars(MaterialDynamicColors).keys():
        color_name = getattr(MaterialDynamicColors, color)
        if hasattr(color_name, "get_hct"):          # это роль, а не служебный метод
            hct = color_name.get_hct(scheme)
            if color_name.is_background is True:    # множитель тона — только фонам
                hct.tone = int(hct.tone * clip(tone_mult, 0.0 if is_dark else 0.5, 1.5, 1))
            hct.chroma = hct.chroma * clip(chroma_mult, 0, 10, 1)
            colors[color] = hexFromArgb(hct.to_int())
    return colors
```

То есть kde **не хардкодит тона** — он берёт все роли из `MaterialDynamicColors`
(их 68) и только домножает хрому/тон.

Из всех ролей терминальной схеме реально нужны:
`surface`, `onSurface`, `primary`, `secondary`, `surfaceContainer`, `surfaceContainerHighest`,
`onBackground`. Плюс «экстры» KDE (`link`, `visited`, `negative`, `neutral`, `positive`) —
это `static_color()`: `Blend.harmonize(argb, scheme.source_color_argb)` + подмена
`scheme.error_palette` на палитру этого цвета.

### 3.4 Разложение в ANSI-палитру (дословно)

Обозначения: `blend(A, B, r)` — смешивание **в Oklab**, `r` — доля `B`
(`color_utils.blendColors`).

```python
# ── DARK ────────────────────────────────────────────────────────────────────
pywal_dark         = (colors_dark["surface"],)                     # color0 = фон
pywal_dark_intense = (blend(bg, tones_secondary[90], 0.8),)        # color8
pywal_dark_faint   = (blend(bg, tones_secondary[90], 0.7),)        # color16
tone = 50
for x in range(7):
    if len(pywal_dark) <= 7:
        if x < best_colors_count:                   # цвет из обоев
            c = lighteen_color(colors_best[x], 0.2, tones_neutral[99])
        else:                                       # добивка из палитр
            c = lighteen_color(tones_primary[tone],  0.2, tones_neutral[99])
            # ... и сразу же tones_tertiary[tone] — по два за итерацию
            if tone < 91: tone += 8                 # 50, 58, 66, 74, 82, 90, 90
        pywal_dark += (blend2contrast(c, bg, tones_neutral[99], 2.5, 0.01, True),)

accents = sort_colors_luminance(pywal_dark[-7:])    # сортировка по светлоте
for n in range(7):
    pywal_dark         += (blend(tones_neutral[99], accents[n], 0.95),)
    pywal_dark_intense += (blend(tones_neutral[99], accents[n], 0.82),)
    pywal_dark_faint   += (blend(bg,                accents[n], 0.70),)

# ── LIGHT ───────────────────────────────────────────────────────────────────
# то же, но: c = scale_saturation(best[x], 1) → lighteen_color(...)
#            blend2contrast(c, bg, tones_neutral[10], 2.0, 0.01, False)
#            ref = tones_neutral[1], secondary = tones_secondary[25]
```

Итоговая раскладка — **три уровня по 8 слотов**, а не «7 акцентов + color8»:

| слоты        | содержимое                                  |
|--------------|---------------------------------------------|
| `color0`     | `bg` = `surface`                            |
| `color1..7`  | `blend(neutral[99│1], accent[i], 0.95)`     |
| `color8`     | `blend(bg, secondary[90│25], 0.80)`         |
| `color9..15` | `blend(neutral[99│1], accent[i], 0.82)`     |
| `color16`    | `blend(bg, secondary[90│25], 0.70)`         |
| `color17..23`| `blend(bg, accent[i], 0.70)`                |

(`color16` — это «faint»-версия `color0`, то есть приглушённый secondary, а не акцент.)

Служебные (в `special`, для Konsole):

```
background        = bg
backgroundIntense = blend(tones_neutral[8], primary, 0.0)   # == neutral[8]
backgroundFaint   = blend(tones_neutral[8], primary, 0.35)
foreground        = blend(bg, secondary[90│25], 0.98)
foregroundIntense = secondary[90│25]
foregroundFaint   = blend(bg, secondary[90│25], 0.88)
cursor            = colors_dark["onSurface"]                # см. примечание ниже
```

### 3.5 Детали, которые легко упустить

1. **`blend2contrast` всегда подмешивает 12 %** нейтрального цвета — даже когда порог
   контраста уже достигнут:
   ```python
   if contrast < min_contrast:
       ... цикл с шагом 0.01 ...
   else:
       return blendColors(lighter_color, blend_color, 0.12)
   ```
   Это не «оставить как есть»: все акценты слегка приглушаются к нейтрали.
2. **Порог разный по сторонам**: dark — «акцент **светлее** фона на 2.5:1»,
   light — «акцент **темнее** фона на 2.0:1» (`contrast_ratio(lighter, darker)` вызывается
   с переставленными аргументами).
3. **В светлой теме kde выкручивает насыщенность в максимум**: `scale_saturation(c, 1)`
   — это HSV с `s = 1`. В тёмной теме этого нет.
4. **`lighteen_color` тоже всегда блендит**: `blend(color, neutral[99], 0.2)`, а-port
   повышение светлоты делается только при `luminance < 0.2`.
5. **Добивка до 7 идёт парами** `primary[tone]` + `tertiary[tone]`, тон `50 → 90` шагом 8,
   так что 7 слотов заполняются за 3–4 итерации.
6. **Квирк kde**: в **светлой** схеме `"cursor": colors_dark["onSurface"]` — курсор берётся
   из *тёмной* схемы (тон 90, почти белый) и на светлом фоне почти не виден.
   Это баг эталона; у нас берём `onSurface` своей темы (см. §5.5).

### 3.6 Что переносим

1. **`surface` → фон**, а не «чёрный»: фон терминала = поверхность темы.
2. **Жёсткий порог контраста** каждого акцента к фону + постоянное приглушение на 12 %.
   Именно это отличает «читаемую» схему от «красивой, но нечитаемой».
3. **Три уровня одной и той же семёрки** (normal / intense / faint) дают согласованную рампу
   вместо 16 независимых цветов.
4. **Вариант схемы выбирается пользователем** и меняет все палитры разом — см. §5.1.

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

### 5.1 Вариант цветовой схемы (`scheme variant`)

Это и есть те самые «типы цветовых схем», которые нужно воспроизвести по kde
(§3.2). Вариант определяет **все пять палитр сразу**, поэтому выбирается первым шагом.

#### Проблема

`android.R.color.system_*` уже посчитаны системой **в одном-единственном варианте**
(Android 12/13 — по spec 2021, 14+ — по spec 2025). Спросить у системы «а дай-ка
вариант Rainbow» нельзя: публичного API с таким параметром не существует. Значит,
нужны два разных пути, и пользователь выбирает между ними.

#### Решение: два режима

| режим | откуда палитры | когда |
|---|---|---|
| `system` | напрямую `android.R.color.system_*` | по умолчанию: терминал бит-в-бит совпадает с системной темой |
| `content` … `fruit-salad` | пересборка из seed-цвета через `Scheme*` | точное воспроизведение kde |

Оба режима дают на выходе один и тот же объект `Palettes {P,S,T,N,NV}`, дальше
алгоритм (§5.3–5.7) не знает, какой режим был выбран.

#### Режим «system»

```java
static Palettes fromSystem(Context ctx) {
    return new Palettes(
        paletteFrom(ctx, android.R.color.system_accent1_500),
        paletteFrom(ctx, android.R.color.system_accent2_500),
        paletteFrom(ctx, android.R.color.system_accent3_500),
        paletteFrom(ctx, android.R.color.system_neutral1_500),
        paletteFrom(ctx, android.R.color.system_neutral2_500));
}
```

#### Режим «вариант» — точное воспроизведение kde

```java
public enum SchemeVariant {
    SYSTEM      (-1, "system",      null),              // «андроидный» режим, см. ниже
    CONTENT     (0, "content",      Variant.CONTENT),
    EXPRESSIVE  (1, "expressive",   Variant.EXPRESSIVE),
    FIDELITY    (2, "fidelity",     Variant.FIDELITY),
    MONOCHROME  (3, "monochrome",   Variant.MONOCHROME),
    NEUTRAL     (4, "neutral",      Variant.NEUTRAL),
    TONAL_SPOT  (5, "tonal-spot",   Variant.TONAL_SPOT),
    VIBRANT     (6, "vibrant",      Variant.VIBRANT),
    RAINBOW     (7, "rainbow",      Variant.RAINBOW),
    FRUIT_SALAD (8, "fruit-salad",  Variant.FRUIT_SALAD);
    // kdeIndex — тот же номер, что в kde --scheme-variant; принимаем и его
}

static Palettes fromVariant(int seedArgb, SchemeVariant v, boolean isDark, double contrast) {
    DynamicScheme s = v.create(Hct.fromInt(seedArgb), isDark, contrast);
    return new Palettes(s.primaryPalette, s.secondaryPalette, s.tertiaryPalette,
                        s.neutralPalette, s.neutralVariantPalette);
}
```

Поле `Variant` нужно только для режима `system` (см. «Роли» ниже); KDE-номера
(`0..8`) и имена взаимозаменяемы при разборе `material-you-variant`.

`v.create(...)` — единственный `switch` на весь проект:

```java
DynamicScheme create(Hct src, boolean isDark, double contrast) {
    switch (this) {
        case CONTENT:     return new SchemeContent(src, isDark, contrast);
        case EXPRESSIVE:  return new SchemeExpressive(src, isDark, contrast);
        case FIDELITY:    return new SchemeFidelity(src, isDark, contrast);
        case MONOCHROME:  return new SchemeMonochrome(src, isDark, contrast);
        case NEUTRAL:     return new SchemeNeutral(src, isDark, contrast);
        case TONAL_SPOT:  return new SchemeTonalSpot(src, isDark, contrast);
        case VIBRANT:     return new SchemeVibrant(src, isDark, contrast);
        case RAINBOW:     return new SchemeRainbow(src, isDark, contrast);
        default:          return new SchemeFruitSalad(src, isDark, contrast);
    }
}
```

**Почему это точное воспроизведение, а не «похожее».**

1. Все 9 классов реально есть в уже подключённой `material:1.12.0`
   (проверено по `classes.jar`): `SchemeContent`, `SchemeExpressive`, `SchemeFidelity`,
   `SchemeMonochrome`, `SchemeNeutral`, `SchemeTonalSpot`, `SchemeVibrant`,
   `SchemeRainbow`, `SchemeFruitSalad` + `enum Variant` с теми же девятью константами.
2. Конструктор у всех — `(Hct sourceColorHct, boolean isDark, double contrastLevel)`,
   т. е. ровно сигнатура kde, **минус `spec_version`**.
3. Отсутствие `spec_version` — не потеря, а совпадение: связка material 1.12.0 — это
   порт **spec 2025** (а он у kde по умолчанию). Подтверждено декодированием
   байткода `MaterialDynamicColors`:
   - `surface` = **6** / 98, `background` = 6 / 98 (в spec 2021 тёмный `surface` был 10);
   - `onSurface` = 90 / 10, `outline` = 60 / 50, `surfaceVariant` = 30 / 90;
   - присутствуют роли, которых нет в spec 2021: `surfaceContainer*`, `surfaceBright`,
     `surfaceDim`, `primaryFixed`, `primaryFixedDim`;
   - присутствует `findDesiredChromaByTone` — функция, добавленная только в spec 2025.
4. Seed берём у системы: `WallpaperColors.getPrimaryColor()` — это тот самый цвет,
   который Monet выделил из обоев. Пиксели не читаем, но «язык» обоев сохраняем.

> Иными словами: kde делает `SchemeTonalSpot(source=best_color, is_dark, contrast, "2025")`
> на Python, мы делаем `new SchemeTonalSpot(Hct.fromInt(primary), isDark, contrast)`
> на Java. Один и тот же материал-колор-ютилити, одна и та же версия спецификации —
> результат совпадает.

#### Роли

Вместо хардкода тонов вызываем роли так же, как kde:

```java
// ВАЖНО: роли MaterialDynamicColors — это методы ЭКЗЕМПЛЯРА, не static
// (проверено по байткоду: public, ACC_STATIC не выставлен).
MaterialDynamicColors mdc = new MaterialDynamicColors();

int bg     = mdc.surface().getArgb(scheme);
int fg     = mdc.onSurface().getArgb(scheme);
int accent = mdc.primary().getArgb(scheme);
int refSec = mdc.secondary().getArgb(scheme);
```

Для режима `system` объекта `DynamicScheme` нет — собираем его вручную
(конструктор публичный):

```java
new DynamicScheme(Hct.fromInt(seedArgb), Variant.TONAL_SPOT, isDark, contrast,
                  P, S, T, N, NV);      // errorPalette создаётся внутри
```

тогда один и тот же код чтения ролей работает в обоих режимах. Это важно: тона
`variant = TONAL_SPOT`-заглушка не влияют на `surface`/`onSurface` (они берутся из
`neutralPalette`), а `primary`/`secondary` приходят из наших палитр.

#### Множители kde

`chroma_mult` и `tone_mult` (§3.3) переносятся дословно — это пост-обработка HCT:

```java
Hct h = new MaterialDynamicColors().surface().getHct(scheme);
if (isBackground) h.setTone(h.getTone() * toneMult);
h.setChroma(h.getChroma() * chromaMult);
```

### 5.2 Вход

```java
public final class MaterialYouSource {
    public final TonalPalette primary, secondary, tertiary, neutral, neutralVariant;
    public final int[] accents;      // до 7 ARGB — кандидаты в ANSI 1..7
    public final long token;         // версия снапшота для инвалидации кеша
}
```

Сборка (`SystemPaletteSource`) — режим `system` (§5.1):

```java
static MaterialYouSource read(Context ctx) {
    // палитры: либо system_* (режим SYSTEM), либо SchemeVariant.fromVariant(...)
    Palettes pal = (opts.variant == SYSTEM)
            ? Palettes.fromSystem(ctx)
            : Palettes.fromVariant(seedArgb(ctx), opts.variant, isDark, opts.contrast);
    TonalPalette P = pal.primary, S = pal.secondary, T = pal.tertiary,
                 N = pal.neutral, NV = pal.neutralVariant;

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

### 5.3 Кандидаты в ANSI-акценты (7 штук)

В системе нет публичного «топа цветов обоев», поэтому повторяем логику kde:

1. Берём до 3 реальных цветов из `WallpaperColors` (если не null и не «неподходящие»
   по `DislikeAnalyzer`) — это аналог `colors["best"]`.
2. Добиваем до 7 **в точности как kde** (§3.4): парами `primary[tone]` + `tertiary[tone]`,
   тон от 50 до 90 шагом 8:

```java
int tone = 50;
for (int x = 0; x < 7 && acc.size() < 7; x++) {
    if (x < bestCount) {
        acc.add(best[x]);
    } else {
        if (acc.size() < 7) acc.add(P.tone(tone));
        if (acc.size() < 7) acc.add(T.tone(tone));
        if (tone < 91) tone += 8;      // 50, 58, 66, 74, 82, 90, 90
    }
}
```

Результат: 7 гармоничных оттенков в «языке» системной палитры (та же хрома/тон,
что у системы) — без чтения пикселей обоев.

> Если добивка палитрами кажется слишком однообразной (primary и tertiary у Monet
> часто близки по hue), есть альтернатива — поворот hue, тот приём, которым M3 строит
> `SchemeRainbow` / `SchemeFruitSalad` (`DynamicScheme.getRotatedHue`):
> `rot = {0, 60, -60, 120, -120, 180, 30, -30, 90, -90}` при `C_ACC = 48`,
> `T_ACC = isDark ? 80 : 40`, с отсечением близких оттенков (<15°).
> Вынести в `material-you-accent-source=wallpaper|palette|rotational`.

> Альтернатива, если захочется буквальной точности к kde: `WallpaperManager.getDrawable()`
> → `Bitmap` 128 px → `QuantizerCelebi.quantize(pixels, 128)` → `Score.score(map, 7, 0xFF4285F4, true)`
> (оба класса уже в material 1.12). Не рекомендую как путь по умолчанию: нужен доступ к
> bitmap'у обоев (на части прошивок возвращает null/nothing), дороже по CPU и может
> разойтись с тем, что реально сгенерировала система. Оставить как опцию
> `material-you-accent-source=wallpaper`.

### 5.4 Фон, текст, курсор

Тоны ниже **декодированы из байткода** `MaterialDynamicColors` в `material-1.12.0`
(см. §5.1) — это spec 2025, тот же, что по умолчанию у kde:

| роль                        | light | dark |
|-----------------------------|-------|------|
| `surface` = `background`    | 98    | **6** |
| `surfaceContainerLowest`    | 100   | 4    |
| `surfaceContainer`          | 94    | 12   |
| `surfaceContainerHighest`   | 90    | 22   |
| `surfaceVariant`            | 90    | 30   |
| `onSurface`                 | 10    | 90   |
| `outline`                   | NV 50 | NV 60|
| `primary` / `secondary`     | 40    | 80   |

Отсюда же правила: фон берём ролью, а не константой —

```java
MaterialDynamicColors mdc = new MaterialDynamicColors();   // роли — методы экземпляра
int bg     = mdc.surface().getArgb(scheme);                // 98 / 6
int fg     = mdc.onSurface().getArgb(scheme);              // 10 / 90
int cursor = mdc.onSurface().getArgb(scheme);              // не colors_dark!
```

`fg` дополнительно проверяем на `Contrast.ratioOfTones >= 7.0` и при невыполнении
доводим через `Contrast.lighter/darker`. Курсор = `onSurface` **своей** темы
(у kde в светлой схеме стоит `colors_dark["onSurface"]` — это баг эталона, см. §3.5).

Резервный путь (если `DynamicScheme` по какой-то причине недоступен) — те же числа
напрямую: `bgTone = isDark ? 6 : 98`, `fgTone = isDark ? 90 : 10`.

Дополнительно — настройка глубины фона (`material-you-background`):
`surface | container-low | container-lowest`, по умолчанию `surface`
(в точности как у kde).

### 5.5 Акценты: порог контраста без циклов

kde ищет подмешивание итеративно в RGB. В Java это решается в один шаг примитивом M3:
`Contrast.lighter(tone, ratio)` / `Contrast.darker(tone, ratio)` возвращают тон,
обеспечивающий заданный WCAG-контраст к исходному тону.

```java
double MIN = isDark ? 2.5 : 2.0;          // как у kde
double bgT = Hct.fromInt(bg).getTone();

for (int i = 0; i < 7; i++) {
    Hct c = Hct.fromInt(accents[i]);

    // 1) порог контраста — одним вызовом вместо цикла kde с шагом 0.01
    double need = isDark ? Contrast.lighter(bgT, MIN)    // светлее фона на >= 2.5:1
                         : Contrast.darker (bgT, MIN);   // темнее  фона на >= 2.0:1
    boolean alreadyOk = isDark ? c.getTone() >= need : c.getTone() <= need;
    if (!alreadyOk) c.setTone(need);

    // 2) постоянное приглушение на 12 % — kde делает это ВСЕГДА, даже когда
    //    порог пройден (ветка else в blend2contrast)
    out[i] = Blend.cam16Ucs(c.toInt(), ref, 0.12f);

    // 3) в светлой теме kde выкручивает насыщенность: scale_saturation(c, 1)
    if (!isDark) {
        Hct h = Hct.fromInt(out[i]);
        h.setChroma(MAX_CHROMA_AT_TONE);
        out[i] = h.toInt();
    }
}
sortByTone(out);   // sort_colors_luminance → монотонная рампа
```

Преимущества перед портом питонячьего цикла: детерминированность, O(1) вместо
цикла с шагом 0.01, работа в HCT (перцептивно равномерно), не нужна своя реализация
WCAG-контраста.

### 5.6 Три уровня и раскладка по слотам

```java
int ref       = N.tone(isDark ? 99 : 1);     // «бумага» для normal/intense
int brightRef = S.tone(isDark ? 90 : 25);    // secondary[90|25]

props.put("background", hex(bg));
props.put("foreground", hex(blend(bg, brightRef, 0.98f)));
props.put("cursor",     hex(cursor));

// Три уровня по 8 слотов — как у kde (§3.4)
props.put("color0", hex(bg));
props.put("color8", hex(blend(bg, brightRef, 0.80f)));
props.put("color16", hex(blend(bg, brightRef, 0.70f)));
for (i = 0..6) {
    props.put("color" + (i + 1),  hex(blend(ref, out[i], 0.95f)));
    props.put("color" + (i + 9),  hex(blend(ref, out[i], 0.82f)));
    props.put("color" + (i + 17), hex(blend(bg,  out[i], 0.70f)));
}
```

`blend(A, B, r)` — `Blend.cam16Ucs(A, B, r)` (перцептивное пространство CAM16-UCS,
родное для Material; у kde — Oklab, разница на глаз неразличима, зато CAM16-UCS есть
в material-библиотеке на всех API, а системный Oklab — только с API 36).

`TerminalColorScheme.updateWith()` понимает только `background` / `foreground` /
`cursor` / `color0..color255`, поэтому `backgroundIntense`, `backgroundFaint`,
`foregroundIntense` и `foregroundFaint` из kde (§3.4) не выражаются —Termux'у они
и не нужны. Исключение — при желании `foregroundFaint` можно положить в `color16`
(это то же «приглушённое» значение, 0.70).

**Гарантии, которые надо проверить перед записью:**

| пара                        | минимум | действие при невыполнении |
|-----------------------------|---------|---------------------------|
| `foreground` vs `background`| 7.0:1   | сдвинуть тон fg к `onSurface` |
| `cursor` vs `background`    | 3.0:1   | взять `onSurface`          |
| каждый акцент vs `background`| 2.5 / 2.0:1 | уже обеспечено §5.5    |

Проверка — `Contrast.ratioOfTones(Hct.fromInt(a).getTone(), Hct.fromInt(b).getTone())`.

### 5.7 Что ещё заполнить

`TerminalColorScheme` хранит 256 индексов + 3 служебных. Слоты 16..23 заняты faint-версией.
Остальные 24..255 оставляем дефолтными (xterm-куб и серая рампа) — их почти никто не использует,
а их «материализация» даст мутные, неотличимые друг от друга оттенки.

---

## 6. Интеграция в код

### 6.1 Новые файлы

Всё в `termux-shared` (там же, где `ColorSchemeUtils`, — чтобы потом мог пользоваться
и Termux:Float):

```
termux-shared/src/main/java/com/termux/shared/termux/materialyou/
├── SchemeVariant.java               // enum: 9 вариантов kde + SYSTEM; create(Hct,Z,D)
├── MaterialYouSource.java           // неизменяемый снапшот: Palettes + accents + token
├── SystemPaletteSource.java         // чтение android.R.color.system_* + WallpaperColors (@RequiresApi 31)
├── TerminalPaletteBuilder.java      // §5 — source + isDark + options → Properties
├── MaterialYouSchemeStore.java      // кеш light/dark + фоновый executor + слушатели
├── MaterialYouOptions.java          // разбор termux.properties-настроек
└── ColorMath.java                   // тонкая обёртка над Hct/Contrast/Blend/TonalPalette
```

`ColorMath` — единственное место, где упоминается
`com.google.android.material.color.utilities`. Если пакет исчезнет из Material —
чинится один файл. `SchemeVariant` — единственное место, где упоминаются
`Scheme*`-классы (один `switch` на 9 кейсов).

### 6.2 Точки в существующем коде

| файл | изменение |
|---|---|
| `ColorSchemeUtils` | новая константа-сентинел `SCHEME_MATERIAL_YOU = "MaterialYou"`; `listStylingColorSchemes()` добавляет её **второй** после `Default` и только при `SDK_INT >= 31`; `applyStylingScheme()` для неё не пишет файл, а только сохраняет выбор; `schemeDisplayName()` → «Material You» |
| `ColorSchemeUtils.ensureColorSchemeForTheme()` | если выбор == `MaterialYou` → `MaterialYouSchemeStore.get(isNight)` вместо чтения файла |
| `TermuxTerminalSessionActivityClient.buildSchemeKey()` | добавить в ключ `MaterialYouSchemeStore.token()` — иначе смена обоев не перекрасит уже открытые сессии (ключ сейчас = ночной режим + mtime/size файлов) |
| `TermuxTerminalSessionActivityClient.ensureColorSchemeLoaded()` | ветка для `MaterialYou` |
| `TermuxActivity` | `onCreate`: регистрация `WallpaperManager.addOnColorsChangedListener` (API 31+); `onResume`: сверка `token`, при расхождении — `invalidateAppliedScheme()` + `updateTermuxActivityStyling()` |
| `termux_display_preferences.xml` | новый `ListPreference app:key="material_you_variant"` сразу после `color_scheme_dark`; `entries` = «Системная / Content / Expressive / …», `entryValues` = `system,content,…`; `defaultValue="system"` |
| `DisplayPreferencesFragment` | показать/скрыть `material_you_variant` через `setVisible(...)` в зависимости от выбранной схемы; при `SDK_INT < 31` скрыть обе |
| `TermuxColorSchemeManager` | без изменений (он читает `TerminalColors.COLOR_SCHEME`) |

#### Единая цепочка разрешения схемы

Все точки входа (активити, `ensureColorSchemeLoaded()` сессий, редактор extra-keys) обязаны
идти через `ColorSchemeUtils.applyColorSchemeForTheme(context, isNight, lightScheme)`:

1. персональный файл Termux:Style (`colors.light/dark.properties`), если есть;
2. сгенерированная Material You — **только если тема выбрала** `MaterialYou` /
   `MaterialYou-<variant>`;
3. встроенная схема: `lightScheme` в светлой теме, «чёрный фон» в тёмной. Это поведение
   пункта **Default**, и оно намеренно **не** Material.

Раньше `applyMaterialYouScheme()` не проверял выбор темы, из-за чего Material You подменяла
собой Default везде, где не было файла схемы, а выбор варианта ни на что не влиял. Теперь
`applyMaterialYouScheme(context, isNight)` сама возвращает `false`, если тема не выбрала
Material You, а явная перегрузка `applyMaterialYouScheme(context, isNight, variant)` даёт
тему ровно того варианта, который выбран в списке.

**Вариант определяется только именем пункта**, никогда свойством `material-you-variant`:
`MaterialYou` — это всегда System, `MaterialYou-rainbow` — всегда Rainbow. Раньше «голое»
значение `MaterialYou` разрешалось через свойство, из-за чего первый пункт списка после
выбора любого варианта превращался в его копию (и подпись, и цвета). Свойство оставлено
как запасной вход для рукописных конфигов, но на пункты списка оно больше не влияет.

Порядок в списке выбора: **Default → Material You (system, content, expressive, …) →
схемы Termux:Style**. Material You идёт сразу после Default: плагин ей не нужен, это
основной новый вариант, а схемы плагина остаются после неё.

### 6.3 Настройки (`termux.properties`, все необязательные)

```properties
# system | content | expressive | fidelity | monochrome | neutral | tonal-spot |
# vibrant | rainbow | fruit-salad          (по умолчанию system)
# принимаются и номера kde: 0..8
material-you-variant=system
# surface | container-low | container-lowest   (по умолчанию surface)
material-you-background=surface
# wallpaper | palette — источник акцентов (по умолчанию wallpaper = WallpaperColors)
material-you-accent-source=wallpaper
# порог контраста акцентов; 0 = значения kde (2.5 тёмная / 2.0 светлая)
material-you-accent-contrast=0
# множитель хромы акцентов, 0.5..2.0
material-you-chroma=1.0
# множитель тона фонов (аналог tone_mult у kde), 0.5..1.5
material-you-tone=1.0
```

`material-you-variant` меняет все пять палитр сразу; это и есть «тип цветовой схемы»
из kde-material-you-colors (§3.2). Значение `system` — десятый, «андроидный» режим:
палитры читаются прямо из `android.R.color.system_*`, без пересборки.

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
| `new SchemeX(hct, isDark, contrast)` (режим «вариант») | ~0.3 мс ×2 (light+dark) | только при смене снапшота, не в `buildSchemeKey()` |
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
   null по отдельности (монохромные обои). Добивка до 7 по §5.3 обязательна.

4. **`color0 == background`** (конвенция pywal/kde). Часть TUI рисует «чёрным» по фону
   и становится невидимой. Оставить как есть (совместимость с эталоном), но вынести
   в настройку `material-you-color0=bg|dim`.

5. **Android 12/13 vs 14+:** Monet в 12/13 считает палитры по spec 2021, в 14+ — по 2025.
   Мы генерируем тона сами (§5.4), поэтому вид схемы одинаков на всех версиях — это плюс,
   но фон может слегка отличаться от системных `surfaces` на 12/13.

6. **Переключение «Тема обоев» в системе** не меняет `getWallpaperId()`, но меняет
   `system_*`. Поэтому `token` обязан включать сами палитры, а не только id.

7. **`Contrast.lighter/darker` vs итерации kde.** kde ищет подмешивание к нейтрали
   в Oklab, мы двигаем тон в HCT. Результат совпадает по контрасту (обе метрики
   сводятся к WCAG-яркости), но не бит-в-бит по оттенку: у kde акцент слегка
   теряет насыщенность при подмешивании, у нас — нет. Разница на глаз неразличима,
   зато мы детерминированы и O(1). Если понадобится буквальное совпадение —
   `Blend.cam16Ucs(c, ref, ratio)` с бинарным поиском `ratio` по
   `Contrast.ratioOfTones` (10 итераций, всё ещё дёшево).

8. **Режим «вариант» расходится с системной темой.** Это ожидаемо и является целью
   настройки: пользователь consciously выбирает `rainbow` вместо того, что выбрала
   система. Но на Android 12/13 системный Monet считает по spec 2021, а наши
   `Scheme*` — по 2025, поэтому даже `tonal-spot` не даст бит-в-бит совпадения с
   системной темой на 12/13. На 14+ расхождение минимально.

---

## 9. План внедрения

**Фаза 1 — ядро (режим `system`, без UI-настроек).**
`ColorMath` + `SystemPaletteSource` + `TerminalPaletteBuilder` + `MaterialYouSchemeStore`;
сентинел `MaterialYou` в `ColorSchemeUtils`; инвалидация через `buildSchemeKey`.
Результат: пункт «Material You» в picker'е, работает light/dark, перекрашивается при
смене обоев.

**Фаза 2 — нотификации.**
`OnColorsChangedListener` в `TermuxActivity`, сверка `token` в `onResume`,
обе версии (light+dark) считаются заранее.

**Фаза 3 — варианты схем.**
`SchemeVariant` + `ListPreference material_you_variant` + разбор `material-you-variant`
в `MaterialYouOptions`; в `MaterialYouSchemeStore` — выбор между `fromSystem()` и
`fromVariant(seed, ...)`. Seed = `WallpaperColors.getPrimaryColor()`.

**Фаза 4 — остальные настройки.** Глубина фона, множители хромы/тона, порог контраста,
`material-you-color0`.

**Фаза 5 (опционально) — хром терминала.** Те же разрешённые тона отдать в
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

**Критерии приёмки Фазы 3:**
- все 9 вариантов дают визуально разные, но одинаково читаемые схемы;
- `tonal-spot` на Android 14+ почти неотличим от `system` на тех же обоях;
- `monochrome` и `neutral` дают нулевую/почти нулевую хрому акцентов — контраст
  при этом держится за счёт тона;
- номер варианта из kde (`0..8`) и его имя взаимозаменяемы;
- неизвестное значение в `material-you-variant` = `system`, в логи — предупреждение.
