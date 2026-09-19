# Единицы измерения у слайдеров настроек

**Дата:** 2026-09-19
**Требование (пользователь), экран Display:** у слайдера размера шрифта показывать `px`, у слайдеров
прозрачности — `%`, у радиуса размытия — `px`, у отступов терминала — `dp`.
**Требование (пользователь), экран доп. кнопок:** «Масштаб панели» — `%`, «Радиус скругления» — `dp`,
«Отступы между кнопками» — `dp`, делённое на 10, «Базовый размер шрифта» — `sp`.
**Статус:** сделано для девяти слайдеров экрана Display и четырёх слайдеров экрана доп. кнопок.

## 1. Что показывает какую единицу (и почему именно её)

### Экран Display (`res/xml/termux_display_preferences.xml`)

| ключ | единица | почему |
|---|---|---|
| `terminal-font-size` | `px` | размер шрифта терминала хранится и применяется в **px** (`TerminalView.setTextSize`, метрики шрифта в px) |
| `button_bg_inactive_alpha` | `%` | `ColorSchemeUtils.getButtonBackground(isLight, alphaPercent)` кладёт процент прямо в **alpha-канал** (`percentToAlpha` → `alpha << 24`) |
| `button_bg_active_alpha` | `%` | то же, `getButtonActiveBackground`, 10‑20 % |
| `terminal-background-transparency` | `%` | 0 % — обои выключены, 40 % — максимум (комментарий в `termux_display_preferences.xml`) |
| `terminal-background-blur-radius` | `px` | `Window.setBlurBehindRadius` — радиус в px |
| `terminal-margin-left/top/right/bottom` | `dp` | уходят в `TerminalPagerAdapter` как `mMarginLeftDp`/`mMarginTopDp`/… |

### Почему у слайдеров фона кнопок «Непрозрачность», а не «Прозрачность»

Значение этих двух слайдеров — это **alpha**, а не «насколько прозрачно»:
`ColorSchemeUtils.percentToAlpha(percent)` считает `percent * 255 / 100`, и результат
уходит в старший байт цвета (`(alpha << 24) | base`). То есть **больше значение ⇒
непрозрачнее кнопка**, и подпись «Прозрачность фона …» описывала величину, обратную
движению ползунка. Поэтому заголовки переименованы в «**Не**прозрачность фона …» во всех
14 файлах `values*/strings.xml` (RU — источник истины, остальные локали получили
соответствующий местный термин: `opacity` / `Deckkraft` / `Opacidad` / `Opacité` /
`Opacidade` / `Opasitas` / `不透明度` / `불투명도` / `अपारदर्शिता` / `عتامة` / `opaklık`).
Ключи ресурсов (`termux_button_bg_{inactive,active}_alpha_title`) и `app:key`
(`button_bg_{inactive,active}_alpha`) намеренно **не** переименованы — они завязаны на код
и `SharedPreferences`.

Отдельно: `terminal-background-transparency` — это **настоящая** прозрачность
(0 % = обои выключены), её трогать не нужно.

### Экран доп. кнопок (`res/xml/extra_keys_editor_preferences.xml`)

| ключ | единица | почему |
|---|---|---|
| `terminal-toolbar-height` | `%` | фрагмент зовёт `setTerminalToolbarHeightScaleFactor(value / 100f)`, т.е. хранится **процент** (100 = штатная высота) |
| `extra-keys-corner-radius` | `dp` | значение уходит в `ExtraKeysView` как `mButtonCornerRadiusDp` |
| `extra-keys-button-margin` | `dp` **/10** | фрагмент пишет `Math.round(margin * 10f)`, т.е. слайдер считает **десятые доли dp**; в `ExtraKeysView` это `mButtonMarginHorizontalDp` |
| `extra-keys-font-size` | `sp` | фрагмент отдаёт значение в `setBaseFontSizeSp(...)`, а `ExtraKeysView` применяет его как `button.setTextSize(TypedValue.COMPLEX_UNIT_SP, mBaseFontSizeSp)` |

`extra_keys_editor_columns` / `extra_keys_editor_rows` — просто количество, единицы не нужны.
Остальные слайдеры приложения (`directory_history_max`) единиц не получили — их никто не просил;
добавление — одна строка `app:valueUnit="…"` в XML.

## 2. Как это сделано

* `app:valueUnit` (`values/attrs.xml`) — строка-суффикс, читается через
  `obtainStyledAttributes(attrs, new int[]{R.attr.valueUnit})` (declare-styleable не нужен).
* `com.termux.app.fragments.settings.UnitSeekBarPreference` — наследник `SeekBarPreference`.
  Единица, начинающаяся с буквы, отделяется пробелом (`42 px`, `12 dp`), символьная приклеивается
  (`5%`). Без `app:valueUnit` класс ведёт себя как обычный `SeekBarPreference`.
* XML: девять слайдеров переведены на этот класс (`res/xml/termux_display_preferences.xml`).

**Почему `TextWatcher`, а не переопределение метода.** Значение в лейбл пишет сам
`SeekBarPreference` из `updateLabelValue(int)` — метод **package-private** (`javap` по
`preference-1.2.1.aar`), переопределить нельзя; коллбэк прогресса — приватный внутренний
listener, перехватить тоже нельзя. Лейбл (`androidx.preference.R.id.seekbar_value`) — единственная
точка, через которую проходят **все** обновления: перетаскивание, программный `setValue` из
фрагмента, `setMin`/`setMax` и каждый ребайнд переиспользованной строки. Поэтому watcher
нормализует текст лейбла («число + единица», идемпотентно), не трогая само значение preference —
`getValue()` и слушатели работают как раньше.

**Рециклинг строк.** `RecyclerView` переиспользует лейбл между preference'ами, поэтому на каждом
bind предыдущий watcher снимается (его идентичность лежит в теге `seekbar_value_watcher_tag`),
иначе два слайдера дрались бы за один лейбл. Атрибут `@Keep` на классе — он ссылается из XML
(проверено: в `seeds.txt` релизного R8 класс и все три конструктора, включая
`(Context, AttributeSet)`, присутствуют, в `usage.txt` его нет).

## 3. Проверка

`scripts/check_preference_title_wrapping.py` теперь ещё и печатает единицу каждой строки и падает,
если `app:valueUnit` стоит не на `UnitSeekBarPreference` (там он молча игнорировался бы):

```
UnitSeekBarPreference    %      Preference.SeekBarPreference.Settings  preference_seekbar_settings  OK  wraps
UnitSeekBarPreference    dp     ...                                    ...                          OK  wraps
UnitSeekBarPreference    px     ...                                    ...                          OK  wraps
```

Сборка: `:app:compileDebugJavaWithJavac`, `:app:processDebugResources`, `:app:minifyReleaseWithR8` —
`BUILD SUCCESSFUL`. На устройстве не проверялось (телефон не был подключён).
