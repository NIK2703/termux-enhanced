# Анализ оптимальности системы истории сообщений

Область анализа: `MessageHistoryController`, `DirectoryHistoryController`,
`AutoCompleteController`, `AutoCompletePopupManager`, `AutoCompleteTextRenderer`,
`AutoCompleteSwipeHandler`, `DirectoryHistoryPopupController`,
`TermuxActivityPopupController` (history-popup), точки входа в `TermuxActivity`
(send / pick / clear / onHistoryDirectoryChanged), `TerminalSession.getCwd()`.

Все выводы проверены по исходникам (коммит `23ffdd38` — «perf: message-history
autocomplete hot paths» уже многое исправил; ниже — то, что осталось). Предложения
из секций P0–P3 не меняют наблюдаемое поведение — только убирают работу, результат
которой заведомо идентичен. Секция D — найденные дефекты, требующие отдельного
решения (они меняют выводимое/записываемое состояние, поэтому вынесены за скобки).

Параметры по умолчанию, от которых меряем: `suggestions_max_count` = 4 (макс. 10),
`message_history_max` = 20 (макс. слайдера 100), `directory_history_max` = 20.

---

## 0. Конвейеры (кто кого вызывает)

**A. Ввод символа в текстовое поле** — самый горячий путь (10–30 Гц при быстром
наборе, каждый IME-composition кадр):

```
TextWatcher.beforeTextChanged   ★ копия всей строки (s.toString())        [ACC:396]
TextWatcher.onTextChanged       (запись before/count)
TextWatcher.afterTextChanged
  ├─ hasComposingSpan()         ★ getSpans → Object[]-аллокация          [ACC:442/453/520]
  └─ updateAutoCompleteSuggestions()                                       [ACC:564]
       ├─ inputField.getText().toString()  ★ ВТОРАЯ копия всей строки     [ACC:582]
       ├─ ранние выходы (empty / caret не в конце / maxCount==0)
       ├─ Path A: fullRescanSuggestions → линейный скан / три            [ACC:870]
       │    → showAutoCompletePopup → rebuildHistoryViews
       ├─ Path B: filterSuggestionsByPrefix + top-up (O(maxCount))       [ACC:960/834]
       └─ updatePopupContent → rebuildHistoryViews
            ├─ rebind × N строк: computePopupWidth + buildSuggestionSpannable
            │    ★ StaticLayout / LruCache с ключом-конкатенацией        [REND:88-158]
            │    ★ "History: "+s, новый SpannableString                  [ACC:1096-1101]
            └─ applyPopupGeometry: measure ВСЕГО контента + popup.update [PM:339-385]
```

**B. Отправка сообщения (Enter)**:

```
onEditorAction → addToMessageHistory(text)          [ACT:1477 → 2158]
  ├─ getCurrentCwdForHistory() → readlink /proc/<pid>/cwd (только per-dir) [ACT:2184]
  ├─ list.remove(msg) O(N) + add(0) O(N)            [MHC:281-285]
  └─ schedulePersist() — debounce 250 мс            [MHC:79-85]
```

**C. Открытие попапа истории (свайп вверх по «карандашу») + drag-highlight**:

```
ACTION_MOVE → showMessageHistoryPopup(v)            [ACT:1895-1898]
  ├─ getCurrentCwdForHistory()  ★ readlink №1       [POP:129]
  ├─ onHistoryDirectoryChanged() → снова getCurrentCwdForHistory() ★ readlink №2 [ACT:2132-2145]
  ├─ N × TextView + replace("\n"," ") + trim()      [POP:195-213]
  ├─ showAsDropDown(anchor,0,0) ★ транзакция №1     [POP:317]
  └─ mHistoryPopup.update(anchor, -(...)) ★ транзакция №2                [POP:332]
ACTION_MOVE (попап открыт, 60–120 Гц)
  └─ updateHistoryHighlight
       ├─ N × tv.getLocationOnScreen() ★ O(N) обходов иерархии за кадр   [POP:381-389]
       └─ N × setBackgroundColor + setTextColor при смене подсветки      [POP:393-401]
```

**D. Переключение вкладки / смена CWD** (per-dir режим):

```
onSessionPageSelected
  └─ cwd = getCurrentSessionCwd()   — ОДИН readlink на обе истории (уже хорошо) [TSAC:558-560]
       ├─ recordCurrentDirectory(cwd) → save() при изменении           [DHC:80-91]
       └─ onHistoryDirectoryChanged(cwd) → save/clear/reload списка    [MHC:206-237]
```

**E. Персистентность**: debounce 250 мс + `flushPersist()` в `onStop` [ACT:857],
`cancelPersist()` в `onDestroy` [ACT:889]; сериализация ВСЕГО хранилища в JSON
на главном потоке (`saveNow` → `savePerDirectory`) [MHC:109-115/432-448].

---

## P0. Статический layout-конвейер рендера строк (каждый символ)

**Файл:** `AutoCompleteTextRenderer.java:88-181`, потребитель
`AutoCompleteController.rebindSuggestionTextViewInternal` (`:1096-1098`).

Что происходит на каждый символ для каждой показанной строки (до 10, по умолчанию 4):

1. `buildSuggestionSpannable` → `truncateToLines(displayText)`.
2. `fitsLines(text, w, maxLines)` строит **ключ кэша конкатенацией всей строки**:
   `text + "\0" + w + "\0" + maxLines` (`:40-42`, `:138`). Это:
   - аллокация строки длиной ≈ длина подсказки на каждый lookup;
   - `String.hashCode` у свежесозданной строки **не кэширован** → полный пробег
     по символам + `equals` по совпавшему ключу. При строке в 60–80 символов и
     4 строках это ~0,5–1 КБ хэширования на символ ввода.
3. При промахе — построение `StaticLayout` (дорогое измерение/шейпинг текста).
4. Если строка не влезает — **бинарный поиск** (`:123-132`): ~log₂(len) итераций,
   каждая — свой срез-строка + свой ключ + (при промахе) StaticLayout.

Что можно сделать без изменения результата (выход `truncateToLines` байт-в-байт
тот же):

- **Мемоизация `displayText` по (suggestion, wordStart, availWidth, maxLines)**:
  `displayText = prefix + suggestion.substring(wordStart)` зависит от ввода только
  через `wordStart`, который меняется редко (только при пересечении границы слова).
  Кэш «сырая подсказка → обрезанный текст» размером ~64 записи убирает из
  стэди-стейта ВЕСЬ конвейер (ключи, хэши, StaticLayout, бинарный поиск) — на
  символ остаются только `SpannableString` + `setText` (они нужны для смещения
  жирного префикса).
- **Дешёвый necessary-check до StaticLayout**: сумма ширин глифов строки не
  превосходит `maxLines × availWidth`, значит при
  `paint.measureText(text) > maxLines * availWidth` строка заведомо не влезает —
  можно сразу идти в бинарный поиск без первичного layout'а. Обратное
  («точно влезает») так дёшево не доказать, поэтому только одно направление.
- **Снизить `LAYOUT_CACHE_MAX` с 512 и перевести `sizeOf` с `lineCount+1` на
  длину текста** (`:33-39`): сейчас в LruCache может удерживаться до 512 готовых
  `StaticLayout` (тяжёлые объекты с копией текста и run-массивами; при 512
  записях это единицы МБ постоянной памяти), а кэш статический и не чистится
  никогда — переживает смену темы/ширины/жизнь активити.

---

## P0. Две полные копии строки на каждый символ

**Файл:** `AutoCompleteController.java:396` и `:582`.

- `beforeTextChanged` копирует ВСЮ строку (`s.toString()`) безусловно — даже
  когда обновление заведомо ничего не сделает: `mRestoringInput`, пустая история,
  `mDisplayMax == 0`, поле пустое.
- `updateAutoCompleteSuggestions` делает вторую копию (`getText().toString()`).

Копия в `:582` нужна (её жрут `regionMatches`-фильтры, ждущие `String`), а вот
первую можно не делать:

- **Вариант 1 (минимальный):** копировать только когда событие может привести к
  пересчёту (`!mRestoringInput && mDisplayMax > 0 && !mSuppressAutoComplete &&
  !mSwipeSuppressed`), а при пропуске выставлять флаг «prevText недействителен»,
  который в `updateAutoCompleteSuggestions` форсирует Path A (полный перескан) —
  ровно то поведение, которое и так возникло бы на пустом `mCurrentSuggestions`.
- **Вариант 2 (точный):** вместо полной копии хранить дельту из параметров
  watcher'а: `prevText = new[0..start) + removed + new[start+added)`, где
  `removed = s.subSequence(start, start+count)` — обычно 0–1 символ. Реконструкция
  точная (before/on/after описывают одно изменение), результат сравнений
  идентичен, аллокация — только удалённый диапазон.

Проверка «prevText == null/invalid → не additive, не deletion» уже совместима с
текущей логикой (`:597-599`, `:679-683` используют length/regionMatches).

---

## P1. Префиксное дерево — мёртвый код при штатных настройках

**Файл:** `AutoCompleteController.java:132, 779-821, 834-909`.

`TRIE_MIN_HISTORY = 128`, но максимум `message_history_max` в UI — 100
(`termux_terminal_io_preferences.xml:94-98`, androidx `SeekBarPreference`
default max = 100; значение по умолчанию 20). То есть через настройки дерево
**не строится никогда**; единственный путь — ручная правка XML преференсов.

Если оно всё же строится, оно дорогое: на каждый узел — `SparseArray` + `ArrayList`
ВСЕХ проходящих слов → память и время построения O(суммы длин всех команд), т.е.
для 100 команд × 40 символов ≈ 4000 узлов и ~4100 ссылок на слова; перестройка —
на каждое изменение версии истории (после каждой отправленной команды).

Линейный скан для ≤100 записей — это ≤100 `regionMatches` по коротким префиксам,
единицы микросекунд; оба ветвления (`:841-850` и `:856-863`) применяют одинаковые
предикаты (`length > tLen && !equals && regionMatches(ignoreCase)`) и сохраняют
порядок newest-first, поэтому **удаление дерева (или подъём порога до ~512) не
меняет результат ни на одном входе**. Рекомендация: удалить trie целиком вместе с
`mPrefixCacheVersion`/`getCandidatesForPrefix`/`buildTrie` и ветками `> TRIE_MIN_HISTORY`
(−~120 строк), оставив линейный скан; `EMPTY_LIST` после этого тоже не нужен.

---

## P1. Двойная оконная транзакция при открытии обоих попапов

**Файлы:** `TermuxActivityPopupController.java:317+332`,
`DirectoryHistoryPopupController.java:288+304/313`.

Оба попапа делают `showAsDropDown(anchor, 0, 0, Gravity.START)` (транзакция с
WindowManager №1), затем measure контента и `popup.update(anchor, 0, −(h+gap), w, h)`
(транзакция №2). Каждая транзакция — re-layout + IPC; между ними попап на один
кадр существует в неправильной позиции (ниже якоря), что и приходится маскировать.

Без изменения итоговой геометрии: измерить контент ДО показа (measure уже
выполняется), `popup.setHeight(popupHeight)` (ширина и так задаётся в конструкторе)
и показать один раз `showAsDropDown(anchor, 0, -(anchor.getHeight() + popupHeight
+ popupGap))`. Для inverted-режима каталога — yoff = 0. Итоговые X/Y/W/H и
ограничение по высоте (`maxHeight`, `roomAbove/roomBelow`) считаются той же
математикой до показа.

---

## P1. Hit-test подсветки: O(N) обходов иерархии на каждый ACTION_MOVE

**Файлы:** `TermuxActivityPopupController.java:381-389`,
`DirectoryHistoryPopupController.java:367-376`.

Пока палец ведёт по попапу, на каждый MOVE (60–120 Гц) для КАЖДОЙ строки
вызывается `tv.getLocationOnScreen(loc)` — обход дерева представлений до корня
окна. В попапе истории до 100+2 строк → до 12 000 обходов/сек.

Эквивалентная по результату замена (математика та же, преобразований/масштаба
в попапе нет): один `mScroll.getLocationOnScreen(loc)` на MOVE, далее сравнивать
`rawY` с `scrollTop + tv.getTop() - scrollY` и `+ tv.getHeight()`
(`getTop/getHeight` — чтения полей). Плюс ранний выход, если `rawY` вне
границ контента. То же для X-координаты при необходимости.

В `DirectoryHistoryPopupController` то же место дополнительно аллоцирует
`int[] loc = new int[2]` на каждый вызов (`:367`, и на каждый кадр автоскролла
`:405`) — в `TermuxActivityPopupController` буфер уже переиспользуется
(`mTmpLoc`, `:71`); привести к одному образцу.

---

## P1. Перекраска ВСЕХ строк при смене подсветки

**Файлы:** `TermuxActivityPopupController.java:393-401`,
`DirectoryHistoryPopupController.java:382-391`.

При каждом изменении индекса подсветки оба попапа в цикле вызывают
`setBackgroundColor` (старой — TRANSPARENT, новой — акцент) и `setTextColor`
(всем строкам, всегда одно и то же значение!) → N лишних invalidate/relayout
на смену строки. Идентичный визуальный результат: цвет текста выставить один
раз при построении строки, при смене подсветки трогать ровно две строки —
предыдущую активную и новую активную (включая граничный случай «подсветка
пропала», index = -1).

---

## P2. Двойной readlink /proc/<pid>/cwd при открытии попапа истории

**Файл:** `TermuxActivityPopupController.java:128-133` → `TermuxActivity.java:2132-2145`.

`showMessageHistoryPopup` resolves CWD (readlink №1), и при несовпадении зовёт
`mHost.onHistoryDirectoryChanged()` (безаргументный), который внутри вызывает
`getCurrentCwdForHistory()` ещё раз (readlink №2). Перегрузка с готовым CWD уже
существует (`TermuxActivity.onHistoryDirectoryChanged(String)`, `:2140`) —
достаточно прокинуть уже прочитанное значение (или добавить `onHistoryDirectoryChanged(String)`
в интерфейс `Host`). Экономия одного readlink на открытие попапа; в per-dir
режиме readlink — это системный вызов + канонизация пути на главном потоке.

---

## P2. hasComposingSpan: аллокация Object[] до 3 раз на событие

**Файл:** `AutoCompleteController.java:520+`, вызовы `:442`, `:453`, `:616`, `:921`;
также `AutoCompletePopupManager.repositionAutoCompletePopup` (`:328`).

`getSpans(0, length, Object.class)` аллоцирует массив и сканирует все спаны.
В composing-сессии (Gboard и т.п.) вызывается до трёх раз на одно событие.
Результат детерминирован в рамках цепочки одного события: посчитать один раз
в `afterTextChanged` (уже есть `mComposingChangePending`) и переиспользовать,
сбрасывая при выходе. Семантика идентична.

---

## P2. Мелкие аллокации/повторы на горячем пути

- `rebindSuggestionTextViewInternal`: `computePopupWidth(fieldWidth)` на КАЖДУЮ
  строку (`:1091`) — ширина не меняется в рамках одного пересборки; посчитать
  один раз на проход `rebuildHistoryViews` и передавать.
- `tv.setContentDescription("History: " + suggestion)` (`:1100`) — конкатенация
  на строку на символ; ставится при смене `tag` (сравнить с tag → пропустить).
- `filterSuggestionsByPrefix`: `text.endsWith("/")` внутри цикла по строкам
  (`:968`) — вынести перед циклом.
- `dpToPx(..., 12)` внутри `getOutline()` (`DirectoryHistoryPopupController:245,278`,
  `TermuxActivityPopupController:271,304`) — `getOutline` дергается на каждое
  изменение размера/фона; dpToPx лезет в `Resources` → вычислить в поле.

---

## P2. Загрузка/миграция истории: O(N²) дедупликация

**Файл:** `MessageHistoryController.java:337, 370, 246, 394`.

`loadGlobal`/`loadPerDirectory`/обе миграции проверяют уникальность через
`List.contains` на каждый элемент. При N=100 это ≤10 000 сравнений строк
(не критично, но бессмысленно): `HashSet<String>` даёт тот же порядок и тот же
результат дедупа за O(N). Каждая загрузка выполняется на старте/смене режима.

---

## P2. Построение попапа истории: до 100 TextView + 2 аллокации строк на ряд

**Файл:** `TermuxActivityPopupController.java:195-213`.

На каждое открытие строится TextView на каждую запись истории (до 100), плюс
`message.replace("\n", " ").trim()` — 2 промежуточные строки на ряд. Минимум без
изменений поведения: `replace`/`trim` только если в строке реально есть `\n`
или ведущий/хвостовой пробел (`indexOf('\n') >= 0`, `charAt(0)/charAt(len-1) <= ' '`).
Кап/рециклинг строк — уже рефакторинг с риском функциональных изменений, не
рекомендую в этом проходе.

---

## P2. Персистентность: сериализация всего хранилища на главном потоке

**Файл:** `MessageHistoryController.java:109-115, 426-448`.

Debounce (250 мс) уже убрал всплески записи, но `saveNow()` строит JSON всего
глобального списка или ВСЕЙ per-dir карты на главном потоке. В per-dir режиме с
десятками каталогов это единицы-десятки КБ JSON + полный rewrite XML-файла
`termux_prefs` (SharedPreferences.apply() перезаписывает весь файл). Вариант без
изменения семантики «последняя мутация всегда на диске»: сериализация+apply в
однопоточном executor'е с generation-счётчиком (поздние записи затирают ранние);
`flushPersist()` из `onStop` при незавершённой фоновой записи делает синхронный
`commit()` последнего поколения.

Сюда же: `DirectoryHistoryController.save()` (`:117-121`) без debounce — частота
низкая (смена каталога), быстрый путь при неизменном верхнем элементе уже стоит
(`:84`), можно оставить как есть.

---

## P3. Мёртвый код и устаревшие комментарии

- `AutoCompleteController.mAutoCompleteChangeStart` (`:140`, пишется `:397`, не читается).
- `AutoCompleteController.mLastAppliedPrefix` (`:146`, в этом классе не используется —
  живёт в `AutoCompletePopupManager`).
- `AutoCompletePopupManager.mLastBuiltHistoryVersion` (`:75`, пишется `:228`, не читается).
- `AutoCompletePopupManager.mLastPopupHeight` (`:67`, пишется `:383`, не читается).
- `AutoCompletePopupManager._padH/_padV` (`:105-107`) — «kept for symmetry».
- `EMPTY_LIST` (`ACC:125`) — общий статический MUTABLE ArrayList, возвращается наружу;
  сегодня безопасно (только чтение), лучше `Collections.emptyList()`.
- Javadoc `AutoCompleteController` упоминает несуществующие entry-points
  (`loadSuggestions()`, `addToMessageHistory(String)`) и описывает Path A как
  «creates a brand-new PopupWindow», хотя `showAutoCompletePopup` переиспользует
  живое окно (`PM:201-217`).
- debug-хуки `debugSetChangeState`/`debugSeedHistorySuggestions` (`ACC:1000-1016`)
  не используются ни одним тестом (`app/src/test` их не содержит) — оставить
  осознанно или удалить.

---

## D. Найденные дефекты (НЕ применять в рамках «без изменения функциональности»)

**D-1. Ручное усечение, возможно, никогда не срабатывает на API ≥ 23.**
`AutoCompleteTextRenderer.buildLayout` (`:160-175`) строит StaticLayout c
`.setMaxLines(maxLines)`, а `fitsLines` проверяет `layout.getLineCount() <= maxLines`.
По поведению AOSP `getLineCount()` ограничен maxLines → проверка всегда истинна →
`truncateToLines` всегда возвращает текст без `…`, и на API ≥ 23 работает только
«backup» `setEllipsize(END)` (тот самый, который комментарий `:74` называет
ненадёжным), а на API < 23 (старая ветка `new StaticLayout` без maxLines) —
ручное усечение. Несоответствие платформ и с заявленным намерением. Безопасный
унифицирующий фикс — строить с `maxLines + 1` и сравнивать `> maxLines` (корректен
в обоих случаях), но это **меняет выводимый текст** для длинных подсказок на
API ≥ 23 (появится гарантированный `…`) — принять отдельно. Требует проверки на
реальном устройстве/эмуляторе.

**D-2. Потеря истории при «clearCurrent» в per-dir без реального CWD.**
`MessageHistoryController.clearCurrent(cwd)` (`:175-188`): при `cwd == null`
(сессия недоступна) удаляется только in-memory список, а ключ текущего каталога
(`mHistoryCurrentDirectory`) остаётся в карте → после следующей смены каталога
записи «воскреснут». Сейчас `getCurrentCwdForHistory()` возвращает "." вместо
null, так что путь недостижим, но хрупок.

---

## Что уже сделано хорошо (проверено, не трогать)

- Инкрементальный Path B реально работает: `mLastBuiltHistoryVersion` обновляется
  во всех точках (`ACC:715/757/913`), фильтр O(maxCount) + top-up, backspace
  пере-выводится из источника истины, а не из усечённого кэша.
- Ребинд строк на месте без реаллокации view/listener'ов (`PM:418-453`).
- Debounce персистентности 250 мс с flush в `onStop` и cancel в `onDestroy`.
- Один readlink на переключение вкладки на обе истории (`TSAC:558-560`).
- Геометрические guard'ы (skip `popup.update` при неизменных X/Y; layout-listener
  только на поле ввода; проверка `l==ol && t==ot && r==or && b==ob`).
- `getCandidatesForPrefix` без `toLowerCase()`-копий, посимвольный fold.
- Swipe-handler: корректная машина состояний, try/finally на teardown, подавление
  автокомплита не протекает.

---

## Приоритеты внедрения

| # | Изменение | Файл(ы) | Эффект | Риск | | Статус |
|---|-----------|---------|--------|------||------|
| 1 | Мемоизация displayText + measureText-пречек + кэш 64 | TextRenderer | убирает StaticLayout/ключи из стэди-стейта набора | низкий  | ✅ Выполнено |
| 2 | Ленивый prevText (дельта или флаг невалидности) | ACC:388-406 | −1 копия строки на символ | низкий  | ✅ Выполнено |
| 3 | Удаление префикс-дерева | ACC | −120 строк, −память при N>128 | низкий (результат идентичен)  | ✅ Выполнено |
| 4 | Один `showAsDropDown` с готовой геометрией | оба попапа | −1 оконная транзакция на открытие | низкий  | ✅ Выполнено |
| 5 | Hit-test через scroll+getTop; покраска 2 строк | оба попапа | O(N)→O(1) на MOVE при драге | низкий  | ✅ Выполнено |
| 6 | Прокинуть CWD в onHistoryDirectoryChanged | POP/ACT | −1 readlink на открытие | низкий  | ✅ Выполнено |
| 7 | Кэш hasComposingSpan на событие | ACC | −аллокации при composing-наборе | низкий  | ✅ Выполнено |
| 8 | HashSet-дедуп загрузки; мелкие P2 | MHC/ACC/попапы | чистота и мелкая экономия | низкий  | ✅ Выполнено |
| 9 | P3-чистка мёртвых полей/комментариев | ACC/PM | читаемость | нет  | ✅ Выполнено |
| 10 | D-1 (maxLines+1) — отдельным коммитом | TextRenderer | устранение межплатформенного расхождения | меняет вывод — принять явно  | ✅ Выполнено |
| 11 | D-2 (сброс ключа каталога в clearCurrent) | MHC | воскрешение истории после clear без CWD | меняет состояние — принято | ✅ Выполнено |

Как проверять: Perfetto/systrace на набор текста в поле с открытым попапом
(до/после — кадры `Choreographer doFrame`, аллокации в
`updateAutoCompleteSuggestions`); Allocation Tracker на серию из 20 символов;
память процесса до/после (кэш StaticLayout); ручные сценарии: backspace,
composing-набор (кириллица/Gboard), swipe-to-select, смена каталога, per-dir
миграция, поворот экрана, kill процесса между flush.
