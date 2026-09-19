# Заголовки строк настроек: почему усекались и как починено

**Дата:** 2026-09-19
**Симптом (пользователь):** «заголовки слайдеров усекаются с `…` в конце»; после первой правки —
«как минимум для `Смахните последнюю вкладку для ново…` не исправлено».
**Требование:** заголовок строки переносится, если не влезает в ширину экрана.
**Статус:** закрыто для **всех** типов строк настроек; гейт —
`scripts/check_preference_title_wrapping.py` (exit 0).

---

## 1. Причина

Строка `Preference` рисуется лейаутом из `androidx.preference` (material-варианты). Во всех таких
лейаутах заголовок объявлен так:

```xml
<TextView android:id="@android:id/title"
          android:singleLine="true"
          android:ellipsize="marquee" .../>
```

`marquee` анимируется только у сфокусированного/выделенного `TextView`, а сам лейаут строится с
`TruncateAt.END` ⇒ в прокручиваемом списке не влезающий заголовок **всегда** обрезается `…`.

У AndroidX есть штатный опт-аут — `app:singleLineTitle` (в material-стилях выставлен в `false`), но он
применяется **только если атрибут объявлен явно**: `Preference.onBindViewHolder` (проверено
`javap`-ом по `preference-1.2.1.aar`) вызывает `TextView.setSingleLine()` под флагом
`mHasSingleLineTitleAttr`. А material-стили объявляют его **непоследовательно**:

| стиль | `singleLineTitle` |
|---|---|
| `Preference.Material` | `false` |
| `Preference.SwitchPreference.Material` | `false` |
| `Preference.DialogPreference.EditTextPreference.Material` | `false` |
| `Preference.SwitchPreferenceCompat.Material` | **нет** |
| `Preference.DialogPreference.Material` | **нет** |
| `Preference.SeekBarPreference.Material` | **нет** |

Отсюда ровно то, что видел пользователь: обычные `<Preference>` переносились, а **свитч
`<SwitchPreferenceCompat>` и слайдер `<SeekBarPreference>` — нет**. Отсюда же провал первой правки:
она переопределяла только `seekBarPreferenceStyle`.

## 2. Решение

Каждый тип строки резолвит **свой** theme-атрибут (`preferenceStyle`, `switchPreferenceCompatStyle`,
`dialogPreferenceStyle`, …), поэтому переопределить надо все, и проще это сделать через лейаут, а не
через `singleLineTitle` (лейаут не зависит от того, объявлен атрибут или нет).

* `@layout/preference_settings` — копия `preference_material` (все строки, кроме слайдеров) без
  `singleLine`/`ellipsize` у заголовка, ширина заголовка `match_parent`.
* `@layout/preference_seekbar_settings` — копия `preference_widget_seekbar_material` (слайдеры), там же.
  Дополнительно у блока заголовок+summary высота переведена с `0dp`+`weight=1` на `wrap_content`:
  при весовой высоте высота блока не обязана расти под вторую строку, а `RelativeLayout` обрезает
  детей по своей высоте — заголовок мог быть срезан, а не перенесён.
* `values/themes.xml` + `values-night/themes.xml`: в `Theme.TermuxApp.Settings` восемь item'ов
  (`preferenceStyle`, `switchPreferenceStyle`, `switchPreferenceCompatStyle`, `checkBoxPreferenceStyle`,
  `dialogPreferenceStyle`, `editTextPreferenceStyle`, `preferenceScreenStyle`, `seekBarPreferenceStyle`)
  и восемь стилей `Preference.*.Settings` с родителем-оригиналом (виджет, разделители, icon space,
  значение слайдера наследуются без изменений).

Обычный `<Preference>` переносился и до правки, но тоже переведён на общий лейаут — иначе поведение
зависело бы от наличия `singleLineTitle` в стиле.

## 3. Не покрыто (осознанно)

* `preference_dropdown_material` (`DropDownPreference`) — в приложении не используется; подменять
  лейаут нельзя, у него на месте заголовка `Spinner`.
* `preference_information_material` — не используется, `preferenceInformationStyle` не задан.
* Подзаголовок категории (`@android:id/summary` в `preference_category_settings.xml`) по-прежнему
  `singleLine`+`ellipsize=end`, но summary у категорий в приложении не задаются ни в одном XML.

## 4. Гейт

`scripts/check_preference_title_wrapping.py` — структурная проверка (не скриншот): для каждого
`<XxxPreference>` из `app/src/main/res/xml/*.xml` резолвит
`theme-атрибут → стиль → цепочка parent'ов → android:layout`, поднимает лейаут (из `app/res/layout`
или из AAR `androidx.preference` в gradle-кэше) и падает, если у `@android:id/title` есть
`singleLine="true"` / `ellipsize` / `maxLines="1"`. Проверяются оба варианта темы — `values` и
`values-night`. Самопроверен: при возврате `Preference.Settings` на `@layout/preference_material`
даёт `FAIL singleLine=true, ellipsize=marquee`, exit 1.

Гейты сборки: `:app:processDebugResources` и `:app:processReleaseResources`
(`--offline --no-daemon --rerun`) — `BUILD SUCCESSFUL`, список `warn: removing resource …` не изменился
(строки не трогались).
