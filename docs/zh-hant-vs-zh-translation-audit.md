# ZH-Hant vs ZH: аудит пары переводов — где реальный недоперевод

**Дата:** 2026-09-19
**Запрос:** «сравни традиционный китайский и китайский переводы в этом проекте и найди недоперевод»
**Инструмент:** `scripts/zh_hant_audit.py` (нужен `opencc-python-reimplemented`)
**Итог:** `app/` — **полный паритет, отставания нет**; недоперевод есть в двух других местах.

---

## 1. Короткий ответ

| Что сравнивалось | Вердикт |
|---|---|
| `app/src/main/res/values-zh` ↔ `values-b+zh+Hant` | **чисто**: 565 ключей против 565, наборы ключей совпадают в обе стороны, 0 упрощённых иероглифов, 0 английских остатков |
| 19 строк базового `values/` | **недоперевод**: их нет **ни** в `zh`, **ни** в `zh-Hant` → английский текст обоим |
| `terminal-view` (3 строки), `termux-shared` (64 строки) | **недоперевод**: есть `values-zh`, нет `values-b+zh+Hant` → пользователь zh-Hant видит **английский** |
| `values-b+zh+Hant/arrays.xml` | файла нет, но это **безвредно** (доказано ниже) |

---

## 2. `app/` — паритета достигли

- `values-zh/strings.xml` = 565 `<string>`, `values-b+zh+Hant/strings.xml` = 565.
  Ключей, которые есть только в одном файле, — **ноль** в обе стороны.
- **493 из 565** значений отличаются формулировкой. То есть Hant — это настоящий
  независимый тайваньский перевод, а не механическая конвертация:
  `自動填入` / `使用者名稱` / `建立` / `開啟` / `儲存` / `預設` / `資訊` / `背景` / `結束`.
  Материковых терминов (`信息` `默认` `后台` `保存` `退出` `数据` `软件` `硬件`) — **0**.
- Оставшиеся 31 идентичных значения — лексика, одинаковая в обеих системах письма
  (`取消` `完成` `全部` `深色` `空格` `毫秒` `向下` `向左` `附加` `清除` `底部` …). Не дефект.
- Обратная проверка `t2s` по `values-zh`: **0** попаданий — в упрощённом файле нет
  традиционных иероглифов.

### 7 «находок», которые находками не являются

Единственное, что выдаёт механическая проверка на упрощённые иероглифы:

```
terminal_cursor_blink_enabled_title / _rate_title / _rate_summary
terminal_cursor_style_title / _summary
text_input_restore_mode_option_insert / _summary       →  游 -> 遊
```

Все 7 — одно и то же **ложное срабатывание**: `游標` («курсор») — правильная
тайваньская форма; `遊` — это глагол «путешествовать». Менять не нужно.
(Ровно та же природа, что у ложного `面` внутри `面板`, — см. навык
`android-strings-diff-apply`, ZH-Hant pass 4.)

---

## 3. Недоперевод №1 — 19 ключей, которых нет ни в `zh`, ни в `zh-Hant`

Пользователь с любым из двух китайских языков видит эти строки **по-английски**:

| ключ | английский текст |
|---|---|
| `back_key_back` | Back button |
| `back_key_escape` | Escape key |
| `back_key_summary` | What the hardware/software back key does in the terminal. |
| `extra_keys_button_margin_summary` | Space between buttons. |
| `extra_keys_edge_indicators_summary` | Show swipe direction indicators on extra keys. |
| `extra_keys_editor_columns_summary` | The number of buttons per row. |
| `extra_keys_editor_rows_summary` | The number of button rows. |
| `extra_keys_font_size_summary` | Base text size for extra key labels. |
| `extra_keys_text_all_caps_summary` | Show extra key labels in uppercase. |
| `locale_override_entry_english` | English |
| `suggestions_max_count_summary` | Number of auto-complete suggestions from input history. |
| `terminal_toolbar_height_summary` | The scale factor for the extra keys / terminal panel height. |
| `termux_soft_keyboard_enabled_off` | The soft keyboard will be disabled. |
| `termux_soft_keyboard_enabled_on` | The soft keyboard will be enabled. |
| `termux_soft_keyboard_enabled_only_if_no_hardware_off` | …even when a hardware keyboard is connected. |
| `termux_soft_keyboard_enabled_only_if_no_hardware_on` | …only when no hardware keyboard is connected. |
| `text_input_append_enter_summary` | When sending from the input field, also submit the line… |
| `text_input_show_summary_off` | The input panel toggle button is hidden. |
| `text_input_show_summary_on` | The input panel toggle button is visible above the extra keys. |

**Это не отставание zh-Hant от zh** — этих ключей нет и в `values-ru`, `values-es`,
`values-fr`. То есть база `values/` получила новые ключи, а переводы не обновили нигде.
По конвенции проекта **RU — источник истины**, поэтому чинить надо в порядке
`values-ru` → остальные локали, а не начинать с китайского.

Видимый эффект подтверждён: `back_key_back` / `back_key_escape` подставляются из
`back_key_entries` (есть в `arrays.xml` **всех** локалей, включая `values-zh`), а
`termux_soft_keyboard_enabled_on` — из `res/xml/termux_terminal_io_preferences.xml`.

---

## 4. Недоперевод №2 — библиотеки без традиционного варианта

`values-b+zh+Hant` существует **только в `app/`**. В двух других модулях есть
`values-zh`, но нет `values-b+zh+Hant`, поэтому запрос локали `zh-Hant` **не находит
китайского ресурса вообще** и падает на английский дефолт из `values/` (проверено на
устройстве владельцем: и панель выделения терминала, и список уровней журнала
показываются по-английски, тогда как заголовок «Уровень журнала» переведён — он
приходит из `app/`).

| модуль | строк | примеры того, что видит пользователь zh-Hant |
|---|---|---|
| `termux-shared` | 64 | `Copy`, `Share`, `Yes` / `No`, `Cancel`, `Save To File`, `Share With`, `Open URL With`, `Select a font`, `Text Input`, `Crash Report`, а также **все** варианты диалога «Log level» (`Off` / `Normal` / `Debug` / `Verbose` / `*Unknown*`) |
| `terminal-view` | 3 | панель выделения текста: `Copy`, `Paste`, `More…` |

Это диалоги, отчёты о падении, экраны разрешений, выбор шрифта, названия каналов
уведомлений — то есть видимый текст, а не служебный.

При создании файлов помнить: `lintVitalRelease` = **error**, каждому
квалифицированному ключу нужен дефолт в `values/` соответствующего модуля.

---

## 5. Что проверил и счёл безвредным

- **`values-b+zh+Hant/arrays.xml` отсутствует.** Безвредно: все 15 массивов
  `*_entries` в `values/arrays.xml` состоят из ссылок `@string/…` (проверено —
  литеральные значения есть только у служебных `*_values`), а ссылка `@string`
  разрешается по **текущей** конфигурации. То есть fallback-массив отдаёт
  традиционный текст.
- **`values-zh/arrays.xml` избыточен**: 14 из 15 массивов побайтово равны `values/`.
- **`volume_keys_entries`** (есть в `arrays.xml` всех локалей, кроме `values/` и
  `values-ru`) не упоминается ни в одном layout/java — мёртвый ресурс. К переводу
  отношения не имеет.
- **36 значений, совпадающих с английским**, одинаковы в обеих китайских локалях и
  переводом не являются по природе: `F1`…`F12`, `ALT`, `CTRL`, `Tab`, `Esc`,
  `%.1f %s`, `termux-data-backup.tar.gz`, `(↓)`.
- **`locale_entry_*` (13 шт., `translatable="false"`)** — эндонимы в списке языков,
  в локалях и не должны быть. `locale_entry_zh_hant` = `繁體中文`, `locale_entry_zh` = `中文`.

---

## 6. Что сделано 19.09 (после жалобы владельца)

Симптом на устройстве: при выборе 繁體中文 панель выделения терминала показывает
`Copy` / `Paste` / `More…`, а список уровней журнала — `Off` / `Normal` / `Debug` /
`Verbose` / `*Unknown*`, хотя заголовок «Log level» переведён.

| файл | что |
|---|---|
| `terminal-view/src/main/res/values-b+zh+Hant/strings.xml` | **новый**, 3 строки: `貼上` / `複製` / `更多…` |
| `termux-shared/src/main/res/values-b+zh+Hant/strings.xml` | **новый**, 64 строки |
| `Logger.java` | убран хвост `" (default)"` и вместе с ним параметр `addDefaultTag` у `getLogLevelLabel()` / `getLogLevelLabelsArray()` |
| `TermuxPreferenceFragmentBase.java` | вызов обновлён под новую сигнатуру |

Генератор — `scripts/make_zh_hant.py`: он не переписывает строки заново, а
преобразует **упрощённого брата** (`opencc s2twp` + карта терминов под house style
`app/values-b+zh+Hant`), поэтому `\n`, отступы многострочных значений, `\"` и
сущности (`&TERMUX_APP_NAME;`) остаются побайтово теми же. Гейт на выходе —
s2t-round-trip (не должно остаться упрощённых иероглифов; в белом списке только
`群` из `社群論壇`).

Ручная вычитка 67 новых строк дала 7 правок: `請授予所要求的權限`, `以要求代碼`,
`請從 Android 設定 -> …`, `請張貼到…` (было `請將其張貼到`, повтор `張貼`),
`我們不支援任何與駭客相關的工具/指令碼。` (был кальк «不提供…的支援»),
перестановка в `%1$s 應用程式無法存取 &TERMUX_APP_NAME; 應用程式的 $PREFIX 目錄。`
(было «…目錄對於 %1$s 應用程式無法存取»), и `選擇字型 (.ttf)` — под пунктуацию
одноимённого ключа в `app/`.

Открытый вопрос для носителя: `套件上下文` (calque «package context»). Оставлено
для консистентности с `values-zh`; альтернатива — `套件 Context`.

Гейт `:app:processReleaseResources --offline --rerun` — `BUILD SUCCESSFUL`,
15 warn-ов (тот же известный набор, новых нет), CRLF сохранён.

---

## 7. Воспроизведение

```bash
pip install opencc-python-reimplemented
python scripts/zh_hant_audit.py        # секции [1]..[8] по всем четырём модулям
python scripts/check_zh_hant_quality.py # гейт качества для новых Hant-файлов
python scripts/make_zh_hant.py <module> --write   # сгенерировать Hant из values-zh
```

### Грабли парсера (важно)

`<string\b([^>]*)>` матчит и `<string-array`, а затем ищет ближайший `</string>` —
которого там нет (`</string-array>` ≠ `</string>`), поэтому весь блок массива
проглатывается вместе со всеми `<string>` внутри. Симптом: `terminal_font_size_title`
объявляется «отсутствующим в values-zh», хотя `grep` находит его в строке 722.
Правильно — **`<string\s`** (пробел, а не `\b`) плюс вырезание `<!--…-->` перед
разбором, и сверка счётчика с `grep -c '<string name='`.
