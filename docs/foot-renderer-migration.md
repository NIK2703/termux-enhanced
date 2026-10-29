# Перенос рендеринга foot на Android: план и состояние

**Ветка:** `feature/foot-terminal-core`
**Версия приложения:** `0.118.0+enhanced.22` (не меняется)
**Исходный foot:** `152487e494993ad7fb5633a27c7d7ad0f8810ea5`
**Статус:** выполнены этапы 1–5, Canvas-only cleanup этапа 6, этап 7
(OSC-8 underline и sixel) и архитектурное уплотнение этапа 8. Canvas-путь
переиспользует **настоящий upstream** `render_row()`/`render_cell()` из
`third_party/foot/render.c` через Canvas compatibility backend: извлечённый
блок компилируется как `foot-render-cell.inc`, а `pixman`/`fcft` заменены
тонким shim'ом, который выдаёт команды Canvas вместо растеризации. Bridge
вызывает upstream `render_row()` буквально; правый-налево порядок ячеек
переупорядочивается в Canvas-sink'е перед эмиссией LTR text run'ов. Legacy
planner, `OP_TEXT` и fallback-политика `OP_RECT`/`OP_CURSOR` удалены:
`render_cell()` — единственный источник цветов, selection и курсора. OSC-8
ссылки всегда подчёркиваются upstream-кодом, а sixel собирается upstream
`sixel.c`; Android получает готовые ARGB-пиксели через `OP_IMAGE` и рисует их
`Bitmap`. Пиксельный `memmove`-scroll по-прежнему требует retained/native
ARGB-backend (этап 4). Ограничения Canvas-пути зафиксированы в 6.4, 6.5 и 10.

---

## 1. Цель

Сделать Android-рендерер минимальной адаптацией рендеринга foot, а не параллельной
реализацией терминальной семантики:

1. VT, grid, reflow, scrollback, selection, cursor, palette и правила повреждения
   остаются в неизменённом foot.
2. Политика «что и где перерисовать» переносится из `render.c` в `FootRenderModel`.
3. Android отвечает только за растеризацию: shaping, шрифты, Canvas, clip и фактическую
   передачу пикселей на экран.
4. JNI/Java API сохраняет прежнее поведение; старый command-ABI живёт до достижения
   паритета.
5. Никакого tracing в этой ветке: диагностика остаётся в `diagnostic/terminal-trace`.

---

## 2. Почему `third_party/foot/render.c` не линкуется целиком

`render.c` — это не «чистый» рендерер, а Wayland-приложение:

- `pixman_image_t`, `fcft`, `shm`, `buffer_chain`, `wayland`, CSD, search, IME,
  URL-mode UI, overlays и render workers;
- `grid_render()` ожидает `struct buffer`, `shm_buffer`, `wl_surface` и pixel format;
- отрисовка ячеек завязана на `pixman` и `fcft`, которых в Android-сборке нет
  (в `include/` лежат только shadow-заголовки).

Практический вывод: `render.c` не линкуется целиком, но его **cell renderer**
переиспользуется буквально. Из pinned-файла извлекается непрерывный блок
`attrs_to_font()` … `render_row()` в `src/foot-render-cell.inc`; блок не
редактируется вручную. Для его зависимостей `include/pixman.h`,
`include/fcft/fcft.h` и `include/wayland.h` расширены до минимального
API-подмножества, а `foot_canvas_shim.c` реализует это подмножество поверх
команд Canvas. Всё, что не входит в блок (Wayland, workers, shm, CSD,
search, URL-mode UI, overlays), остаётся вне Android-рендера. При этом upstream
`sixel.c` и `hsl.c` компилируются: sixel-декодер, палитра, размещение, crop и
reflow переиспользуются, а pixman/fcft-совместимость и передача результата в
Canvas живут в `foot_canvas_shim.c` и `TerminalRenderer`. OSC-8 URL-mode UI на
Android отсутствует, но атрибут ссылки и подчёркивание остаются в upstream
`render_cell()`.

**Почему не `grid_render()` целиком.** После этапа 8 upstream `render_row()`
используется буквально, но `grid_render()` всё ещё не линкуется. Он владеет не
только циклом строк: `shm_get_buffer()`/`buffer_chain`, двойной буферизацией,
reapply старого damage, scroll-damage `memmove`, render workers, наложением
sixel на `buf->pix[0]`, overlay/IME/search/URL UI и Wayland-commit. Чтобы
подключить его без Wayland, понадобился бы полноценный `struct buffer`/shm
shim с retained ARGB-поверхностью и Java-commit backend, а не тонкая замена
pixman/fcft. Это отдельный retained-ARGB этап, а не текущая адаптация Canvas.
Поэтому граница переиспользования сейчас: upstream `render_row()`/`render_cell()`,
upstream `sixel.c`/`hsl.c`, а Android владеет frame/damage header, сортировкой
операций под Canvas, шрифтами и Bitmap-кэшем. URL-mode, search, IME и overlays
относятся к Android UI scope.

Извлечение проверяется на этапе configure и перед каждой сборкой:
`tools/check-render-cell.sh --check` сверяет SHA-256 исходника, SHA-256
сгенерированного файла и точный диапазон строк. Команда пересоздания:
`sh terminal-emulator/src/main/foot/tools/check-render-cell.sh --write`.

---

## 3. Целевая архитектура

```text
PTY bytes
   │
   ▼
foot terminal core (terminal.c, vt.c, csi.c, grid.c, ...)   [upstream, без изменений]
   │  grid->view, row->dirty, scroll_damage, cursor, selection
   ▼
FootRenderModel (src/render_model.c)                        [наш слой политики]
   │  viewport, full damage, commit
   │  frame/damage header, row selection, canvas sink
   │
   ├── foot-render-cell.inc                                 [upstream, verbatim]
   │      render_row() → render_cell(): fg/bg, attrs, cursor, selection
   │
   ├── sixel.c + hsl.c                                     [upstream, verbatim]
   │      sixel decode → grid->sixel_images → sync/scale/crop/reflow
   │
   ├── foot_canvas_bridge.c                                 [тонкий адаптер]
   │      synthetic fcft fonts, attrs.clean reset, selection spans,
   │      literal render_row()
   │
   └── foot_canvas_shim.c                                   [pixman/fcft shim]
          fills/glyphs/trapezoids → OP_FILL_RECT/OP_GLYPH/OP_FILL_TRAPEZOID
          sixel pixman surface    → OP_IMAGE + nativeSixelPixels()
   ▼
TerminalRenderer (Canvas)                                   [Android backend]
   │  glyph shaping, Paint, Canvas, Bitmap cache, clip, commit
   ▼
Surface

headless.c (адаптер) ── palette / append / events / snapshots / JNI wrappers
```

### Границы ответственности

| Слой | Владеет | Не владеет |
|---|---|---|
| foot core | grid, row, cell, cursor, selection, scroll damage, sixel placements | пикселями, Canvas, JNI |
| `FootRenderModel` | viewport, pending full damage, commit dirty, frame/damage header, выбор строк, row-op buffer/sort, damage-gate и `OP_IMAGE` | текстом, шрифтами, Canvas |
| `foot-render-cell.inc` | upstream-политика cell/row: цвета, attrs, cursor, selection, OSC-8 underline | Wayland, shm, workers |
| upstream `sixel.c` | sixel decode, палитра, размещение, crop, scale, reflow | Canvas, JNI, Bitmap |
| `foot_canvas_shim` | pixman/fcft-совместимость, region algebra, sixel-поверхности, команды | реальной растеризацией шрифтов |
| `headless.c` | append, палитра, события, snapshot, JNI-facing state, доступ к sixel-пикселям | решением «что и где рисовать» |
| `TerminalRenderer` | шрифты, Canvas, clip, Bitmap-кэш sixel, фактическая отрисовка | семантикой терминала |
| `TerminalView` | `mTopRow`, инвалидация, scrollbar, clip | dirty-состоянием native-ядра |

---

## 4. Инварианты

1. **Один владелец viewport.** `grid->view` синхронизируется с `mTopRow` только через
   `headless_set_view()`. `headless_render()` не имеет права менять viewport: при
   частичном кадре его `top_row` — это первая строка клипа, а не верх viewport.
2. **Dirty не очищается при append.** `row->dirty` снимается только после того, как
   строка реально попала на Canvas (`nativeCommitRows`/`nativeCommitAllRows`).
3. **Append отдаёт видимые строки viewport.** При прокрутке в историю native обязан
   вернуть внешние координаты `view_top..view_top+rows-1`, а не только live screen
   `0..rows-1`. Иначе изменения истории (OSC 4, reflow) не дойдут до Java.
4. **Full damage не теряется на частичном кадре.** Пока не закоммичены все видимые
   строки, `full_damage` остаётся установленным.
5. **Смена viewport — это смена содержимого адресов.** Пользовательский scroll
   помечает все видимые строки; компенсация `mTopRow` при выводе — нет.
6. **Координаты внешние.** `0` — верх live screen, отрицательные значения — history.
   Внутренние ring-индексы наружу не выходят.
7. **Sixel identity стабильна.** `OP_IMAGE` несёт id и revision поверхности.
   Java не угадывает изображение по координатам: id не переиспользуется, а
   revision меняется при любом изменении пикселей.
8. **Sixel рисуется после строк.** `OP_IMAGE` идёт после row/fill-команд кадра,
   чтобы фон частичной строки не закрасил ещё валидное изображение.

---

## 5. Viewport и координаты

### 5.1. Внешняя координата

`external = mTopRow + rowInViewport`

- `mTopRow == 0` — live bottom (верх экрана);
- `mTopRow == -N` — верхняя строка на `N` строк выше live screen;
- видимый диапазон всегда `mTopRow .. mTopRow + term->rows - 1`.

Внутренний абсолютный ring-индекс:

```c
abs = (grid->offset + external) & (grid->num_rows - 1)
```

### 5.2. Синхронизация с foot

```c
grid->view = (grid->offset + top_row) & (grid->num_rows - 1)
```

`FootRenderModel::set_view()` делает это при пользовательском изменении `mTopRow`
(сразу в обработчике scroll/fling/scrollbar, а также перед кадром).
`sync_view()` после append перечитывает `grid->view`, потому что:

- при выводе на дне foot сдвигает `offset` и `view` вместе — внешний адрес остаётся `0`;
- при выводе со прокруткой вверх foot сохраняет абсолютный `view`, а `offset`
  сдвигается — внешний адрес становится `top - rowShift`, и UI компенсирует
  `mTopRow -= rowShift` ровно на столько же.

`sync_view()` обычно не выставляет full damage: контент под viewport не изменился,
изменился только адрес. Исключение — viewport сдвинулся **вниз** (`new_top >
old_top`): foot не смог удержать старый адрес (например, один append прокрутил
ring больше чем на полный оборот), поэтому все видимые слоты могут содержать
другой контент. В этом случае native выставляет full damage.

UI компенсирует follow-text не по счётчику, а по точному адресу:
`rowShift = mTopRow - nativeViewTop`, где `nativeViewTop` приходит в append-state
после `sync_view()`. Это корректно для любого размера chunk, включая прокрутку
больше половины ring, где дельта `grid->offset` неоднозначна. Если
`nativeViewTop > mTopRow`, viewport ушёл вниз: UI принимает native-адрес и
прекращает selection.

Scroll counter (`absolute_scroll_count`) по-прежнему считает прокрутки вверх и при
прокрученном viewport (`DAMAGE_SCROLL.lines` или положительная дельта ring-offset).
Он сохранён для совместимости, тестов и fling-эвристик; основной путь
follow-text теперь использует `nativeViewTop`.

### 5.3. Клампы

`top_row` клампится в `[-history_size, 0]`. История берётся из активного grid:
alternate screen всегда `0`, normal — из ring и `transcript_rows`.

---

## 6. Damage и commit

### 6.1. Текущий цикл

```text
append(data)
  ├─ vt_from_slave()
  ├─ process_scroll_damage(): row->dirty для затронутых scroll-регионов
  │     (region clamp; *_IN_VIEW — относительно grid->view)
  ├─ viewport-before snapshot: сравнение row-указателей по слотам
  │     (ловит grid_swap_row без row->dirty; NULL-строка → full damage)
  ├─ scroll counter: DAMAGE_SCROLL.lines или дельта ring-offset
  │     (совместимость и тесты; UI использует native view top)
  ├─ sync_view(): перечитать grid->view; сдвиг вниз → full damage
  ├─ full_damage при alt-switch / history shrink / mode change / потере строки
  ├─ вернуть внешние строки, у которых full_damage || row->dirty
  │     (row->dirty НЕ очищается)
  └─ если full_damage ещё pending, выставить APPEND_FLAG_FULL_DAMAGE

render(top_row, clip, rows)
  ├─ set_viewport() уже вызван view-слоем
  ├─ planner выдаёт frame-заголовок с view_top/full_damage
  ├─ planner выдаёт OP_DAMAGE-диапазоны для запрошенного band
  ├─ planner выдаёт команды для запрошенных строк
  └─ TerminalRenderer рисует и собирает фактически нарисованные строки

commitRows(painted)
  ├─ row->dirty = false для каждой нарисованной видимой строки
  └─ full_damage снимается только когда закоммичены все видимые строки

commitAllRows() после full-clip кадра
  └─ снимает dirty со всех видимых строк и full_damage
```

### 6.2. Почему commit по фактически нарисованным строкам

`TerminalView` может отдать частичный clip, а `TerminalRenderer` может пропустить
строку, которая уже чистая по Java-состоянию. Если native очистит damage для всех
запрошенных строк, пропущенная строка больше никогда не перерисуется. Поэтому commit
идёт по списку `mPaintedRows`, который заполняется только на `OP_ROW_END` при
`rowDrawn == true`.

### 6.3. Full damage

Full damage выставляется при:

- создании/reset терминала;
- resize/reflow;
- переключении normal/alternate;
- уменьшении истории;
- изменении mode bits, влияющих на отрисовку;
- пользовательском изменении `mTopRow`.

Full damage снимается только после commit всех видимых строк. Это защищает от
ситуации «полный кадр был разрезан clip'ом, а dirty уже очищен».

Пока full damage pending, append сообщает `APPEND_FLAG_FULL_DAMAGE`; Java
заменяет отдельные dirty-строки на `markAllDirty()` и делает полный invalidate.

### 6.4. Upstream `render_row()` через Canvas backend

`foot_render_model_render_frame()` по-прежнему принимает уже
синхронизированный viewport, band и параметры Android-вида, не трогает
`grid->view` и не снимает dirty. Для каждой видимой строки он вызывает
`foot_canvas_render_row()`, а тот — **буквально** upstream `render_row()` из
`foot-render-cell.inc`. Никакого второго LTR-цикла по клеткам нет: upstream
сам обходит строку справа налево, а Canvas-sink складывает операции строки в
буфер и перед эмиссией сортирует их по x (при равном x сохраняется исходный
порядок). Поэтому логический порядок text run'ов остаётся LTR, а порядок
внутри клетки — background → cursor → glyph → underline/strikeout/URL.

```text
foot_canvas_render_row()
  └─ render_row()                     # upstream verbatim, right-to-left
       └─ render_cell()
            ├─ attrs.clean / confined
            ├─ fg/bg + reverse + selection + cursor colors
            ├─ fcft glyph lookup
            ├─ pixman fill/composite/trapezoid → sink callbacks
            └─ underline / strikeout / URL
  └─ canvas_flush_ops()               # stable sort by x, replay LTR
```

Android-специфичны только шесть вещей:

1. **Synthetic fcft fonts.** `foot_canvas_font_create()` создаёт четыре
   `fcft_font` (regular/bold/italic/bold-italic) с `max_advance.x =
   cell_width`, `height = cell_height`, `ascent = baseline`. Реальную
   гарнитуру, shaping и растеризацию по-прежнему делает Android Canvas;
   synthetic-глиф нужен upstream-коду только как носитель метрик и codepoint.
2. **pixman/fcft shim.** `foot_canvas_shim.c` не рисует пиксели. Он
   превращает `pixman_image_fill_rectangles()` в `OP_FILL_RECT`,
   `pixman_image_composite32()` с glyph-маской — в `OP_GLYPH`, а
   `pixman_composite_trapezoids()`/`triangles()` — в
   `OP_FILL_TRAPEZOID`. Поэтому upstream-код сам выбирает цвета, курсор,
   selection и стили подчёркивания, а Canvas лишь исполняет примитивы.
3. **Сброс `attrs.clean`.** Canvas не хранит поверхность ячеек между кадрами.
   `foot_canvas_prepare_row()` снимает `attrs.clean` со всей строки, иначе
   upstream пропустил бы «чистые» ячейки после заполнения фона. Единица
   commit'а — строка (`row->dirty`), а не ячейка. Bridge больше не фильтрует
   пустые клетки отдельным pre-scan'ом: upstream проходит строку целиком, а
   sink сам отбрасывает default-фон, который уже нарисован Java.
   `attrs.confined`/`overflowing_glyphs` отключены: глифы не должны вылезать
   за свою клетку.
4. **Согласование сетки.** Upstream оперирует целыми пикселями клетки
   (`round(font_width)`), а Java раскладывает колонки по дробному
   `mFontWidth`. `TerminalRenderer.canvasScaleX()` переводит x/width команд
   `OP_FILL_RECT`/`OP_GLYPH`/`OP_FILL_TRAPEZOID` в дробную сетку; иначе
   ошибка округления накапливается с каждой колонкой. Заодно
   `foot_canvas_prepare_frame()` выставляет `conf->dim.amount = 1.5`
   (значение по умолчанию из `config.c`), потому что headless-конфиг
   создаётся через `calloc`, а не через загрузчик конфигурации foot.
5. **Коалесинг и LTR-replay Canvas-команд.** Sink сначала складывает
   операции строки в `canvas_draw_op[]`, затем стабильно сортирует по x и
   только после этого эмитит команды. Это компенсирует right-to-left порядок
   upstream `render_row()` и сохраняет порядок операций внутри клетки. При
   replay соседние глифы с одинаковыми baseline, ARGB, effects и шириной
   клетки объединяются в один `OP_GLYPH`/`drawTextRun()`; пропущенные пробелы
   восстанавливаются в payload (до 256 клеток), поэтому обычная строка — это
   один text run, а не одна команда на символ. Полноячеечные fill-прямоугольники
   одинакового цвета и подряд идущие по x также сливаются в один
   `OP_FILL_RECT`. Это важно для selection, reverse video и цветных
   status-line: без слияния каждый такой фон разрывал бы glyph-run. Явно
   сменённый фон, underline/strikeout и trapezoid по-прежнему начинают новый
   run.
6. **Прозрачность фона.** Upstream в режиме `ALPHA_MODE_DEFAULT` применяет
   `term->colors.alpha` только к `COLOR_DEFAULT`-ячейкам, а Termux исторически
   делал полупрозрачными и явно закрашенные фоны. Поэтому Canvas-sink
   применяет настроенную alpha к любому непрозрачному full-cell fill
   (фон, reverse, selection, block cursor); тонкие подчёркивания/зачёркивания
   остаются непрозрачными, как foreground.

Selection передаётся в upstream через `cell->attrs.selected`. Bridge
расширяет диапазон колонок до целого wide-grapheme, если endpoint попал на
spacer-ячейку. Upstream сам инвертирует цвета, обрабатывает `fg == bg` и
рисует block/underline/bar cursor. `term->kbd_focus` временно выставляется
только на время синхронного `render_row()`; видимость курсора кодируется
`cursor_col`, а не состоянием фокуса VT-ядра. Отдельной fallback-отрисовки
курсора/selection в render model больше нет.

Combining marks: `render_cell()` выдаёт base-глиф и затем zero-width marks.
Sink добавляет mark в UTF-16 payload предыдущего `OP_GLYPH` и увеличивает его
`charCount`, чтобы Android шейпил base+marks вместе, а не рисовал mark
отдельно. Пустые клетки (`wc == 0`) не дают глифа: фон уже нарисован
fill-командой; при слиянии соседних глифов пропущенные пробелы добавляются в
payload, а `widthColumns` явно хранит ширину всего run.

`OP_RECT`/`OP_CURSOR` удалены из ABI вместе с fallback-политикой для строк
без native row. Legacy-планировщик и `OP_TEXT` тоже удалены: JNI-путь всегда
вызывает upstream `render_row()` через Canvas bridge. Runtime-флаг
`render.frame_abi` не вводится: Java уже умеет работать без frame header, а
полный откат делается revert'ом.

### 6.5. OSC-8 и sixel

**OSC-8.** У Android нет URL-mode UI, поэтому `conf->url.osc8_underline`
выставлен в `OSC8_UNDERLINE_ALWAYS`, а `conf->url.style = UNDERLINE_SINGLE`.
Подчёркивание рисует сам upstream `render_cell()` через обычный
`OP_FILL_RECT`; отдельного Java-декоратора нет. URL-режим (подсветка при
наведении, открытие ссылки) остаётся вне Android-рендера.

**Sixel.** `conf->tweak.sixel = true`; `sixel.c` и `hsl.c` компилируются из
pinned `third_party/foot`. VT340-палитра sixel (`colors_dark/light.sixel`)
заполняется рядом с обычной темой, а не оставляется нулевой. При изменении
размера клетки вызывается `sixel_cell_size_changed()`, при resize/reflow —
`sixel_reflow()`. Перед кадром `sixel_sync_cache()` создаёт/пересоздаёт
масштабированную поверхность; crop при перекрытии текстом выполняет region
algebra из shim'а.

Путь изображения в Canvas:

```text
upstream sixel.c
  └─ grid->sixel_images[i] → six->pix (ARGB32 pixman surface)
       └─ FootRenderModel: damage-gate по строкам изображения
            └─ OP_IMAGE(x, y, width, height, idLo, idHi, revision, opaque)
                 └─ TerminalRenderer: Bitmap cache по id/revision
                      └─ nativeSixelPixels(id, revision, w, h)
                           └─ копия ARGB под JNI-lock → Bitmap.createBitmap()
```

`x`/`y` — row-relative пиксели: `y = 0` — верх строки, в которой начинается
sixel. `idLo` идёт первым, затем `idHi`; Java собирает
`((long) idHi << 32) | (idLo & 0xffffffffL)`. Изображение передаётся после
row-команд, чтобы частичная перерисовка фона не закрыла валидный sixel. Если
изображение ушло из grid, изменило размер или revision, JNI возвращает `NULL`,
а Java удаляет устаревший `Bitmap`. Кэш ограничен 24 поверхностями.

Пиксели копируются в `jintArray` сразу под тем же mutex, что append/resize;
указатель `headless_sixel_pixels()` не живёт дольше JNI-вызова. Ширина и
высота в команде — intrinsic-размер поверхности; Java масштабирует только
горизонтальную сетку через `mCanvasScaleX`, как и остальные Canvas-команды.

---

## 7. ABI

### 7.1. Существующий append-ABI (сохранён)

`nativeAppendInto(handle, data, length, out)`:

```text
[ dirty_count, mode_bits, cursor_style, cursor_row, cursor_col,
  absolute_scroll_count, history_size, flags, view_top,
  dirty_external_row_0, ... ]
```

Заголовок занимает 9 `int` (`APPEND_STATE_HEADER_SIZE`). `view_top` — внешний
адрес viewport после append (`0` — live screen top, отрицательные значения —
history); по нему UI вычисляет точный `rowShift`. Dirty-строки теперь могут быть
отрицательными (history). Java `TerminalBuffer.markRowDirty()` уже принимает
такие координаты.

### 7.2. Новый viewport/commit-ABI

```java
int nativeSetView(long handle, int topRow)   // возвращает history-clamped top
int nativeViewTop(long handle)               // текущий foot viewport
void nativeCommitRows(long handle, int[] rows, int count)
void nativeCommitAllRows(long handle)
```

JNI-вызовы сериализованы тем же `pthread_mutex_t`, что и append/resize/destroy.
Список строк копируется в переиспользуемый scratch-буфер `jni_handle`, без
аллокации на кадр.

### 7.3. Frame/damage command-ABI (этап 2)

```text
OP_FRAME_BEGIN
  frameId, viewTop, fullDamage, termColumns, termRows,
  renderTop, renderRows, damageRangeCount
OP_DAMAGE(firstRow, lastRow)        # повторяется damageRangeCount раз
OP_ROW_BEGIN(row)
OP_FILL_RECT(x, y, width, height, argb)                 # Canvas bridge
OP_GLYPH(startChar, charCount, x, baseline, widthColumns, argb, effects)
OP_FILL_TRAPEZOID(x1, y1, x2, y2, x3, y3, x4, y4, argb)
OP_IMAGE(x, y, width, height, idLo, idHi, revision, opaque)
OP_ROW_END
...
OP_FRAME_END
OP_END
UTF-16 length + units
```

`OP_FILL_RECT`/`OP_GLYPH`/`OP_FILL_TRAPEZOID` используют row-relative
пиксельные координаты: `y = 0` — верх текущей строки. `argb` — обычный
unpremultiplied Android-цвет; shim снимает premultiply, которое делает
`color_hex_to_pixman_with_alpha()`. `OP_GLYPH.effects` содержит только
bold/italic: underline/strikeout/curly уже нарисованы upstream-кодом как
fill/trapezoid. `widthColumns` — явная ширина run в клетках; она не
выводится из первого codepoint, потому что run может содержать пробелы,
wide-символы, combining marks и ZWJ-кластеры. Sink сортирует операции строки
по x перед эмиссией, поэтому UTF-16 payload идёт в порядке колонок, хотя
upstream `render_row()` обходит ячейки справа налево; Java читает payload по
индексам.
`OP_IMAGE` использует ту же row-relative систему: `y = 0` — верх строки, в
которой начинается sixel. `idLo`/`idHi` образуют стабильный 64-битный id
поверхности, `revision` инкрементируется при изменении пикселей, `opaque`
сейчас информативный (Java всегда рисует ARGB с блендингом). Сами пиксели
забираются отдельным JNI-вызовом `nativeSixelPixels(handle, id, revision, w, h)`
только когда Java ещё не имеет актуального `Bitmap`.

Свойства:

- `OP_FRAME_BEGIN` всегда первый opcode кадра; `OP_FRAME_END` стоит непосредственно
  перед `OP_END`.
- `damageRangeCount` и `OP_DAMAGE` описывают только запрошенный band
  `[renderTop, renderTop + renderRows)`. Непрерывные dirty-строки схлопываются в
  один диапазон, поэтому число диапазонов не больше числа строк.
- `fullDamage == 1` означает, что native не снял полное повреждение viewport;
  renderer рисует все строки band независимо от списка диапазонов.
- `frameId` монотонно растёт; сейчас используется как задел для диагностики
  устаревших кадров и не влияет на решение о перерисовке.
- Курсор и selection остаются в per-row командах: их позиция и границы уже
  вычисляются native-планировщиком, дублировать их в заголовке не нужно.
- Java `TerminalRenderer` использует native damage как основной источник для
  решения «рисовать строку», но пока объединяет его с Java dirty-зеркалом как
  fallback. При отсутствии frame-заголовка (старая native-библиотека) поведение
  остаётся прежним.
- `TerminalView` по-прежнему владеет clip/invalidation и `mPixelsValid`;
  frame-ABI не меняет эту логику.

Append-ABI получил флаг `APPEND_FLAG_FULL_DAMAGE = 1 << 1`. Java при нём
заменяет набор отдельных dirty-строк на `markAllDirty()`, чтобы частичный
repaint не мог «потерять» полное повреждение.

---

## 8. Этапы

### Этап 0. Baseline — выполнен

- [x] foot core и JNI собираются без Rust.
- [x] Версия `.22`, подпись `CN=Winlator`.
- [x] Tracing изолирован в `diagnostic/terminal-trace`.
- [x] Native smoke test `foot_core_test` проходит.

### Этап 1. Viewport и native dirty ownership — выполнен в этом коммите

- [x] `FootRenderModel` (`src/render_model.c/.h`).
- [x] `grid->view` синхронизируется с `mTopRow` через `nativeSetView`.
- [x] Append больше не очищает `row->dirty`.
- [x] Append отдаёт dirty-строки видимого viewport, включая history.
- [x] `nativeCommitRows` / `nativeCommitAllRows`.
- [x] `TerminalRenderer` коммитит только реально нарисованные строки.
- [x] Full damage не снимается частичным кадром.
- [x] Scroll damage `*_IN_VIEW` помечает строки относительно `grid->view`.
- [x] Native-тесты на сохранение dirty между append, частичный commit,
      viewport и клампы.

### Этап 2. Frame/damage ABI — выполнен в этом коммите

- [x] Добавить `OP_FRAME_BEGIN` с `frame_id`, `view_top`, `full_damage`,
      `cols`, `rows`, `render_top`, `render_rows`, `damageRangeCount`.
      Курсор и selection остаются в per-row командах, где они уже вычисляются.
- [x] Добавить `OP_DAMAGE` (row band; column rect — задел на будущее) и
      `OP_FRAME_END`.
- [x] Перевести Java на чтение damage из frame-заголовка, оставив Java dirty
      как fallback.
- [x] Добавить native-тесты: чистый полный кадр, частичный band, смена viewport
      между кадрами, commit только одной строки, full damage flag в append.
- [x] Добавить `APPEND_FLAG_FULL_DAMAGE`; Java зеркало теперь получает полное
      повреждение из native, а не только через перечисление строк.
- [ ] Убрать Java dirty-зеркало полностью (этап 6); сейчас оно остаётся
      fallback-источником, поэтому критерий «один источник истины» ещё не закрыт.

### Этап 3. Первый frame/row/cell planner — заменён upstream-путём

Этот этап дал рабочий Canvas-рендер, но его семантика повторяла
`render_cell()`, не являясь буквальным upstream-кодом. После этапа 5
планировщик удалён; `foot_render_model_render_frame()` больше не содержит
второго обхода ячеек и `OP_TEXT`.

- [x] `grid_render()` → `foot_render_model_render_frame()`.
- [x] `render_row()`: обход ячеек, run-группировка, wide-символы, trailing
      spaces, cursor/selection split.
- [x] `render_cell()`: fg/bg, bold/dim/italic/underline/blink/reverse/conceal/
      strikethrough, base16/bright conversion, Termux bold-in-bright.
- [x] Cursor policy: block/underline/bar, cursor color, инверсия под block,
      wide-cell cursor width.
- [x] Selection policy: диапазоны строк/колонок, пересечение с wide-символами,
      инверсия цветов и защита от невидимого `fg == bg`.
- [x] Android-часть оставлена в `TerminalRenderer`: `drawTextRun`, `Paint`,
      `Canvas`, shaping, clip, commit.
- [x] Native-тесты planner'а: run grouping, bright, cursor policy, wide
      selection, selection при одинаковых fg/bg.

### Этап 4. Scroll damage и производительность — выполнен для Canvas-backend

- [x] `process_scroll_damage()` превращает `DAMAGE_SCROLL*` в row-damage.
      `DAMAGE_SCROLL_IN_VIEW` клампится к `region`, координаты переводятся через
      `grid->view`, а не помечают весь viewport.
- [x] Добавлено сравнение identity строк по видимым слотам до/после append.
      foot делает `grid_swap_row()` без `row->dirty`; Canvas не умеет двигать
      пиксели, поэтому слот, в котором сменился row-указатель, помечается
      dirty. Это покрывает partial scroll region при прокрученной истории.
      Если слот стал `NULL` или snapshot не выделился, запрашивается full
      damage.
- [x] Исправлен scroll counter для scrolled-up output: учитывается дельта
      ring-offset, даже когда foot не пишет `DAMAGE_SCROLL`.
- [x] Follow-text переведён на точный native `view_top` из append-state:
      `rowShift = mTopRow - nativeViewTop`. Это убирает неоднозначность дельты
      ring-offset при chunk больше половины ring. `nativeSetView()` возвращает
      фактический history-clamped top, поэтому кэш не расходится после
      пользовательского scroll.
- [x] Если native viewport ушёл вниз,`sync_view()` выставляет full damage, а UI
      принимает новый адрес и сбрасывает selection: старый контент был вытеснен.
- [x] `TerminalView` больше не повышает partial scroll до
      `markAllScreenRowsDirty()`: native row-damage уже содержит ровно те слоты,
      которые сдвинулись. Это убирает лишние строки/полные кадры при выводе в
      прокрученной истории.
- [x] `ScrolledUpdateRepaintTest` синхронизирует native viewport, как реальный
      `TerminalView`, и проверяет, что streaming в scrolled-up режиме
      перерисовывает только scrollbar band. Добавлены chunk больше половины ring
      (anchor сохраняется) и multi-wrap burst (UI следует native viewport вниз,
      full repaint).
- [x] Native-тесты: full-screen scroll в scrolled-up режиме не двигает видимые
      слоты, но увеличивает counter; partial region помечает только сдвинутые
      слоты.
- [ ] Отдельный `OP_SCROLL`/честный `memmove`-scroll не вводится для Canvas:
      у Canvas нет читаемой/перемещаемой поверхности, поэтому scroll всё равно
      требует перерисовки затронутых строк. Это возможно только после перехода
      на retained/native ARGB-backend; текущий row-damage — эквивалент политики
      `grid_render_scroll()` для Canvas.
- [ ] Сравнить на устройстве p95 кадра, `tmux`, `htop`, OpenCode TUI и быстрый
      поток вывода. Проверка заблокирована: текущая Termux-сессия является
      рабочим окружением, `adb install`/`force-stop` не применялись.

Критерии этапа:

- нет пропущенных строк и «замёрзших» scroll-регионов — покрыто native/JVM
  regression-тестами;
- нет лишних full repaint при scrolled-up output — на уровне unit-тестов
  подтверждено; device-профилирование остаётся;
- p95 кадра не хуже текущего — требует device-прогона.

### Этап 5. Upstream `render_row()`/`render_cell()` через Canvas bridge — выполнен

- [x] Расширить shadow-заголовки `pixman.h`, `fcft/fcft.h`, `wayland.h` до
      API, которое реально использует извлечённый блок.
- [x] Извлечь `attrs_to_font()` … `render_row()` из pinned `render.c` в
      `src/foot-render-cell.inc`; исходник и результат зафиксированы SHA-256.
- [x] `foot_canvas_shim.c`: solid/bits/target images, fills, glyph composites,
      trapezoids/triangles, synthetic fcft fonts и glyph cache.
- [x] `foot_canvas_bridge.c`: synthetic fonts из `font_width`/`font_line_spacing`/
      `font_ascent`, сброс `attrs.clean`, selection spans, буквальный вызов
      upstream `render_row()`; `render_cell()` остаётся извлечённым и
      проверяется хэшем.
- [x] Canvas sink: `OP_FILL_RECT`, `OP_GLYPH`, `OP_FILL_TRAPEZOID`;
      row-relative координаты и unpremultiplied ARGB.
- [x] `TerminalRenderer`: исполнение новых opcode, clip строки, Path для
      trapezoid, точная позиция glyph baseline, пропуск text payload.
- [x] Согласование целой native-сетки с дробным `mFontWidth` через
      `canvasScaleX()`; `dim.amount` выставлен в upstream-default 1.5.
- [x] Canvas-путь является единственным: `headless_set_use_canvas()` и
      `foot_render_model_set_use_canvas()` удалены, legacy planner удалён.
- [x] Termux bold-in-bright перенесён в bridge: brighten только по индексу
      0..7 (base16/base256), независимо от совпадения RGB с базовой палитрой;
      foot-овский palette-match отключён, чтобы `38;5;16` не становился
      bright из-за совпадения с palette[0].
- [x] Combining marks сливаются в предыдущий `OP_GLYPH`; пустые клетки
      (`wc == 0`) не дают глифа; `OP_GLYPH` несёт явный `widthColumns`.
- [x] Sink буферизует операции строки и стабильно сортирует их по x перед
      LTR-эмиссией, компенсируя right-to-left обход upstream `render_row()`.
- [x] LTR-батчинг: соседние глифы с одинаковым стилем/шириной сливаются в один
      text run, пропущенные пробелы восстанавливаются в payload.
- [x] Полноячеечные fills одинакового цвета сливаются по x, поэтому
      selection/reverse/status-line не разрывают glyph-run.
- [x] Native canvas-тесты: обычный глиф, block cursor, selection inversion,
      wide CJK positioning, слитый цветной фон и один glyph-run.
- [x] `tools/check-render-cell.sh --check` на configure и перед сборкой
      библиотеки/теста; ручное редактирование `.inc` ломает сборку.
- [ ] Визуальная проверка на устройстве (`tmux`, `htop`, OpenCode TUI, emoji,
      CJK, styled underline, transparency) и p95-профилирование. Заблокировано
      активной Termux-сессией; `adb install`/`force-stop` не применялись.
- [x] Legacy planner удалён; Canvas bridge — единственный путь отрисовки
      клеток.

### Этап 6. Cleanup

- [ ] Удалить Java dirty-зеркало после стабилизации.
- [x] Удалить legacy planner и `OP_TEXT` из native/Java command-ABI;
      `OP_RECT`/`OP_CURSOR` и fallback-политика для строк без native row тоже
      удалены.
- [x] Обновить документацию и тесты; native/JVM-тесты покрывают Canvas-only
      поток и Termux bold-in-bright.
- [ ] Проверить, что `third_party/foot` остаётся pinned и не патчится.
      SHA-256 extracted-блока по-прежнему проверяется при configure/сборке.

### Этап 7. OSC-8 underline и sixel — реализован

- [x] `conf->tweak.sixel = true`; upstream `sixel.c`/`hsl.c` собираются из
      pinned submodule, sixel-стабы удалены.
- [x] VT340 sixel-палитра заполняется вместе с terminal theme; включены
      нужные shadow-поля `wayland.h` и `hsl.h`.
- [x] `sixel_cell_size_changed()` на смену метрик клетки, `sixel_reflow()` на
      resize/reflow.
- [x] `FootRenderModel` выдаёт `OP_IMAGE` после row-команд, только для
      повреждённых строк и с обрезкой по видимому band.
- [x] `headless_sixel_pixels()` проверяет id, revision и размеры; JNI копирует
      ARGB под lock в `jintArray`.
- [x] `TerminalRenderer` кэширует `Bitmap` по id/revision, удаляет устаревшие
      записи и ограничивает кэш 24 изображениями.
- [x] OSC-8: `OSC8_UNDERLINE_ALWAYS` + `UNDERLINE_SINGLE`; подчёркивание
      рисует upstream `render_cell()`, URL-mode UI отсутствует.
- [x] Native-тесты: красный sixel и `OP_IMAGE`, чтение пикселей, отсутствие
      повторной отправки на чистом кадре, перекрытие/обрезка текстом, OSC-8
      underline.
- [ ] Визуальная проверка sixel/OSC-8 на устройстве: масштаб при смене
      размера клетки, прокрутка, перекрытие текстом, прозрачность фона.

### Этап 8. Архитектурное уплотнение — выполнен

- [x] `foot_canvas_render_row()` вызывает upstream `render_row()` буквально;
      второго LTR-цикла по `render_cell()` больше нет.
- [x] Canvas-sink буферизует операции одной строки (`canvas_draw_op[]`),
      стабильно сортирует по x и только затем эмитит `OP_FILL_RECT`/
      `OP_GLYPH`/`OP_FILL_TRAPEZOID`; combining marks наследуют `sort_x`
      базового глифа.
- [x] Удалены fallback-политика и opcode `OP_RECT`/`OP_CURSOR`; курсор,
      selection и цвета теперь приходят только из upstream
      `render_cell()`/`draw_cursor()`.
- [x] Исправлено сопоставление курсора при scrollback: `cursor.point.row` —
      ring-индекс, а render-строка — внешний адрес. Сравнение row-указателей
      через `grid_row()` не теряет курсор при `grid->offset != 0`.
- [x] Native regression: курсор на верхней строке live screen при непустой
      истории должен дать ровно один cursor-fill в `y == 0`.
- [x] Проверено, что `grid_render()` целиком пока не интегрируется без
      retained `struct buffer`/shm backend; оценка зафиксирована в разделе 2.
- [ ] Визуальная проверка на устройстве и p95 после сборки APK.

---

## 9. Тестирование

### Native (`foot_core_test`)

- dirty сохраняется между append до commit;
- частичный commit не очищает соседнюю строку;
- `commitAllRows` снимает full damage;
- `setView(negative)` возвращает dirty-строки history;
- клампы `top_row` к `[-history, 0]`;
- alternate screen: history `0`, top `0`;
- resize: full damage и корректный viewport;
- OSC 4 при scrolled-up viewport;
- frame header и `OP_DAMAGE` для полного/частичного band;
- run grouping и bold-in-bright;
- cursor: underline не перекрашивает ячейку, block перекрашивает, wide-cell
  курсор занимает обе колонки;
- selection: начало на spacer'е wide-символа выделяет весь grapheme;
- selection при `fg == bg` получает видимый инвертированный фон;
- full-screen scroll при прокрученной истории увеличивает scroll counter, но не
  помечает неизменившиеся видимые слоты;
- partial scroll region помечает только слоты, у которых сменился row-указатель;
- Canvas bridge: обычный глиф, block cursor с contrast glyph, selection
  inversion, wide CJK на позиции 0/2 колонки;
- Canvas bridge: курсор на верхней строке live screen при непустой истории
  рисуется ровно один раз в `y == 0`, а не теряется из-за ring/external
  несовпадения;
- Canvas bridge: три соседних ASCII-глифа дают один `OP_GLYPH` с `charCount=3`
  и `widthColumns=3`;
- Canvas bridge: три клетки с одинаковым явным фоном дают один fill шириной
  24 px и один glyph-run;
- Canvas bridge: явно закрашенный фон при `background_alpha=128` получает
  alpha 128, а не остаётся непрозрачным;
- sixel: красный sixel даёт один `OP_IMAGE` с ненулевым id, `headless_sixel_pixels()`
  возвращает поверхность нужного размера и красный пиксель;
- sixel: чистый кадр после commit не повторяет `OP_IMAGE`;
- sixel: перекрытие широкого изображения текстом проходит через crop/region
  subtraction и не теряет `OP_FRAME_END`;
- OSC-8: `\e]8;;URL\e\\X\e]8;;\e\\` даёт underline-fill цвета foreground,
  нарисованный upstream `render_cell()`;
- `check-render-cell.sh --check` подтверждает source/output SHA-256 и точный
  диапазон извлечения.

### Java/Canvas

- `TerminalRenderer` коммитит только `rowDrawn`-строки;
- `canvasScaleX()` переводит целую native-сетку в дробный `mFontWidth`;
- новые opcode корректно пропускаются в `loadRenderText` и исполняются в
  основном цикле (`OP_FILL_RECT`, `OP_GLYPH`, `OP_FILL_TRAPEZOID`, `OP_IMAGE`);
- `OP_IMAGE` использует `Bitmap`-кэш по id/revision, повторно не дергает JNI
  для неизменившейся поверхности и удаляет bitmap при `NULL`/новой revision;
- `nativeSixelPixels()` копирует только актуальную поверхность и отклоняет
  несовпадение revision/размеров;
- `RenderPayloadTest` проверяет LTR-порядок UTF-16 payload, новый
  `OP_GLYPH.widthColumns`, совпадение с `getRenderText()`, корректную сборку
  64-битного id из `idLo`/`idHi` и получение sixel-пикселей через JNI;
- full clip → `clearDirtyState` → native commit all;
- partial clip оставляет dirty вне clip;
- scrolled-up streaming не вызывает full-screen dirty и перерисовывает только
  scrollbar band;
- один chunk больше половины ring сохраняет видимый anchor через native view top;
- multi-wrap burst, после которого foot уводит viewport вниз, делает full repaint
  и не оставляет stale-строк;
- partial scroll region при прокрученной истории не оставляет stale-строк;
- scrollbar band и cursor blink не теряют native damage;
- `mPixelsValid == false` → рисуются все видимые строки, commit all.

Локальный JVM-прогон `terminal-emulator` требует Android `libtermux_terminal.so`:
`System.loadLibrary()` ищет его в `java.library.path`, а Termux-linker не
открывает `.so` из `/storage`. Для проверки библиотеку из
`terminal-emulator/build/intermediates/cxx/Debug/.../obj/arm64-v8a/` кладут в
`$PREFIX/lib`, а Gradle test task запускают с `-Djava.library.path=$PREFIX/lib`
(init script). Иначе тесты падают с `UnsatisfiedLinkError`, не из-за кода
рендерера.

### Устройство

1. `top`/`htop` в scrolled-up режиме.
2. OpenCode TUI: быстрый вывод, стирание строк, курсор.
3. `tmux` с несколькими панелями (partial scroll regions).
4. История: прокрутка вверх, вывод внизу, возврат на дно.
5. Смена темы/палитры при прокрученной истории.
6. Resize/поворот, alternate screen (`less`, `vim`).
7. Sixel-изображение: отрисовка, смена размера шрифта/клетки, прокрутка,
   перекрытие текстом и прозрачный фон.
8. OSC-8 ссылка: подчёркивание и его сохранение после прокрутки/перерисовки.

---

## 10. Риски и откат

| Риск | Митигация |
|---|---|
| Java и native dirty расходятся | commit только painted rows; тест на пропущенную строку |
| Смена viewport без full repaint | `set_view` выставляет full damage, UI делает `invalidate()` |
| Частичный clip теряет dirty | full damage не снимается до commit всех строк |
| `grid->view` уезжает при append | `sync_view()` перечитывает foot после каждого append |
| Один chunk прокручивает больше половины ring | follow-text использует точный `view_top`, а не дельту offset; сдвиг вниз → full damage |
| Full damage вызывает лишние кадры | full снимается только после полного commit; на scrolled-up output full не ставится |
| Selection на wide-символе выделяет половину | bridge расширяет selection до всей wide-ячейки; native canvas-тест |
| Выделение невидимо при `fg == bg` | upstream `render_cell()` инвертирует фон; native canvas-тест |
| Canvas-путь регрессирует | native canvas-тесты; откат — `git revert` этапа, legacy-планировщика больше нет |
| `.inc` разошёлся с pinned `render.c` | configure/PRE_BUILD проверяют source/output SHA-256; `--write` только после осознанного re-pin |
| shim расходится с pixman/fcft | API ограничен фактическими вызовами блока; `-Wno-unused` не скрывает несовпадение типов |
| Полупрозрачный фон темнеет | shim снимает premultiply перед передачей ARGB в Canvas |
| sixel-пиксели меняются после `OP_IMAGE` | JNI копирует под lock и сверяет revision/размеры; Java удаляет устаревший Bitmap |
| `Bitmap`-кэш sixel растёт | кэш ограничен 24 записями, старые `Bitmap.recycle()` |
| sixel не переживает resize/reflow | `sixel_cell_size_changed()`/`sixel_reflow()` + `sixel_sync_cache()` перед кадром |
| кроп sixel при перекрытии текстом ломает поверхность | region algebra shim'а; native-тест с перекрытием |
| OSC-8 ссылка не видна без URL-mode UI | всегда включён upstream underline; native-тест fill'а |

Откат этапа 1: вернуть очистку `row->dirty` в append, убрать `nativeSetView`/
`nativeCommit*` вызовы, вернуть `headless_render` без viewport-модели. Версия и
JNI-поверхность при этом не меняются.

Откат этапа 2: Java снова принимает решение только по `TerminalBuffer` dirty,
native всё ещё коммитит painted rows; frame header можно игнорировать.

Откат этапа 3: вернуть planner-код в `headless.c`, оставив `FootRenderModel`
владельцем viewport/damage; command-ABI и Java при этом не меняются.

Откат этапа 5: убрать `foot_canvas_*` из CMake и вернуть `render_model.c` к
старому планировщику из истории git; Java-парсер новых opcode можно оставить:
они просто не появятся в потоке.

Откат этапа 7: выключить `conf->tweak.sixel`, вернуть sixel-стабы вместо
`upstream sixel.c`/`hsl.c`, убрать `OP_IMAGE` из render model и Java-парсер
`Bitmap`; OSC-8 вернуть к `OSC8_UNDERLINE_URL_MODE` (ссылки перестанут быть
видны без URL-mode UI).

---

## 11. Изменённые файлы этапов 1–8

- `terminal-emulator/src/main/foot/src/render_model.c` — viewport, full damage,
  commit, frame/damage header, row-op buffer/sort, Canvas sink; переиспользуемые
  command/text buffers. Legacy planner удалён; добавлен damage-gated
  `OP_IMAGE` и pointer-сопоставление курсора при scrollback.
- `terminal-emulator/src/main/foot/src/render_model.h` — API модели и palette
  layout.
- `terminal-emulator/src/main/foot/src/foot-render-cell.inc` — verbatim
  upstream `attrs_to_font()` … `render_row()`; генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot_canvas_backend.h` — sink,
  frame/row API Canvas-моста и id/revision sixel-изображений.
- `terminal-emulator/src/main/foot/src/foot_canvas_bridge.c` — synthetic fonts,
  сброс `attrs.clean`, selection spans, буквальный вызов upstream
  `render_row()`; инвалидация sixel-кэша при смене клетки.
- `terminal-emulator/src/main/foot/src/foot_canvas_shim.c` — pixman/fcft
  compatibility backend, glyph cache, Canvas-примитивы, region algebra,
  sixel-поверхности и их id/revision.
- `terminal-emulator/src/main/foot/tools/check-render-cell.sh` — извлечение,
  SHA-256 и режим `--check` для configure/PRE_BUILD.
- `terminal-emulator/src/main/foot/src/headless.c` — append, dirty ownership,
  палитра, события, snapshot, тонкие JNI-обёртки; VT340 sixel-палитра,
  `sixel_reflow()`/`sixel_cell_size_changed()` и доступ к пикселям.
- `terminal-emulator/src/main/foot/src/headless.h` — новые entry points,
  включая `headless_sixel_pixels()`.
- `terminal-emulator/src/main/foot/src/jni.c` — JNI setView/commit и
  `nativeSixelPixels`.
- `terminal-emulator/src/main/foot/tests/foot_core_test.c` — damage/frame/planner,
  Canvas-bridge, OSC-8 underline и sixel regression tests.
- `terminal-emulator/src/main/foot/CMakeLists.txt` — сборка модуля, теста,
  upstream `sixel.c`/`hsl.c` и проверка извлечённого блока.
- `terminal-emulator/src/main/foot/include/{pixman,wayland,srgb}.h` — shadow-API
  для upstream sixel/pixman-пути.
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` — frame
  parser, native damage, painted-row commit, новые Canvas opcode и Bitmap-кэш
  sixel.
- `terminal-view/src/main/java/com/termux/view/TerminalView.java` — viewport
  synchronization, clip/invalidation.
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalNative.java` —
  native-методы viewport/commit, флаг полного повреждения и sixel-пиксели.
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` —
  `setViewport`, `commitRenderRows`, append flags, `getSixelPixels()`.
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java` —
  native commit all при сбросе dirty state.
- `terminal-emulator/src/test/java/com/termux/terminal/RenderPayloadTest.java` —
  frame-заголовок, damage-диапазоны, `OP_IMAGE` и sixel-пиксели через JNI.
- `terminal-emulator/src/test/java/com/termux/terminal/AppendStateBufferTest.java` —
  native commit/viewport.
- `terminal-emulator/src/test/java/com/termux/terminal/ScrolledUpdateRepaintTest.java` —
  shadow-canvas regression для scrolled-up streaming и partial scroll region;
  native viewport синхронизируется так же, как в `TerminalView`.

---

## 12. Критерии готовности этапа 1

- `foot_core_test` зелёный на NDK arm64.
- `:terminal-emulator:compileDebugJavaWithJavac` и
  `:terminal-view:compileDebugJavaWithJavac` зелёные.
- Release APK собирается, версия остаётся `.22`.
- На устройстве нет пропущенных строк при scrolled-up выводе и после смены
  normal/alternate screen.

### Фактическая проверка (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`.
- Java-компиляция `terminal-emulator`, `terminal-view` и unit-test-исходников: успешно.
- `:app:assembleRelease`: успешно, 0 errors.
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись `CN=Winlator`.
- Сборка без tracing: совпадений `terminal-trace`/`trace_` в изменённых main-исходниках нет.
- Готовый APK: `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-render-model.apk`.


---

## 13. Фактическая проверка этапа 2 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`, включая frame/damage,
  частичный band, смену viewport и commit одной строки.
- `:terminal-emulator:compileDebugJavaWithJavac` и
  `:terminal-view:compileDebugJavaWithJavac`: успешно.
- Целевые JVM-тесты `RenderPayloadTest` и `AppendStateBufferTest`: 4/4 прошли.
- Полный legacy-набор `terminal-emulator` содержит 48 падений в старых тестах
  Java-семантики; они не затрагивают frame/damage-путь и не использовались как
  критерий этапа 2.
- `:app:assembleRelease`: успешно, 0 errors, 3 warning (PendingIntent, вне
  рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`.
- Готовый APK:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-frame-abi.apk`.
- Устройство не перезапускалось: текущая Termux-сессия является рабочим
  окружением, поэтому `adb install`/`force-stop` не применялись. Визуальная
  проверка на устройстве остаётся за пользователем.

### Откат этапа 2

Убрать frame-заголовок и `OP_DAMAGE` из `headless_render`, вернуть
`APPEND_FLAG_FULL_DAMAGE` и его обработку в Java, убрать frame-parser из
`TerminalRenderer`. Native commit-ABI этапа 1 при этом сохраняется; версия и
JNI-поверхность не меняются.

---

## 14. Фактическая проверка этапа 4 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`, включая новые
  проверки full-screen scroll в scrolled-up режиме и partial scroll region.
- `ScrolledUpdateRepaintTest`: 14/14. Тест синхронизирует native viewport, как
  `TerminalView`, проверяет отсутствие stale-строк и локальную перерисовку
  scrollbar band, chunk больше половины ring и multi-wrap burst.
- `ScrollFollowTextTest` + `FlingLiveOutputTest`: 11/11.
- `RenderPayloadTest` + `AppendStateBufferTest`: 4/4.
- `:app:assembleRelease`: успешно, 0 errors, 3 warning (PendingIntent, вне
  рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `a4f0b20e5f1edf9e5134d9758a588481a09825a7d50d6b7fbce50b609b204f50`.
- Готовый APK:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage4-native-view-top.apk`.
- Устройство не перезапускалось: текущая Termux-сессия является рабочим
  окружением, поэтому `adb install`/`force-stop` не применялись. Визуальная
  проверка `tmux`/`htop`/OpenCode и device-профилирование p95 остаются за
  пользователем.

### Откат этапа 4

Вернуть в `TerminalView.onScreenUpdated()` повышение partial scroll до
`markAllScreenRowsDirty()`, вернуть follow-text по `getScrollCounter()`, убрать
`view_top` из append-state и `nativeViewTop`, снова сделать `nativeSetView()`
`void`, удалить viewport-snapshot и pointer-diff из `headless_append()`, вернуть
`count_scroll_damage()` и полное viewport-помечивание для
`DAMAGE_SCROLL_IN_VIEW`. Frame/damage ABI, commit-ABI и версия приложения не
меняются.

---

## 15. Фактическая проверка этапа 5 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`, включая
  `canvas_render_tests()`: обычный глиф, block cursor с contrast glyph,
  selection inversion, wide CJK positioning и проверку наличия
  `OP_FRAME_END` после роста command-буфера.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, source/output
  SHA-256 совпадают; проверка выполняется на configure и как PRE_BUILD для
  `termux_terminal` и `foot_core_test`.
- JVM: 29/29 в `ScrolledUpdateRepaintTest` (14), `ScrollFollowTextTest` (5),
  `FlingLiveOutputTest` (6), `RenderPayloadTest` (2),
  `AppendStateBufferTest` (2). Новые opcode и LTR-порядок UTF-16 payload
  покрыты `RenderPayloadTest`.
- JVM `terminal-view`: 37/37, включая 3 новых теста `canvasScaleX()`
  (целая/дробная/субпиксельная ширина клетки); остальные 34 — существующие
  тесты clip/damage/fast-path/mismatch-scale/scroll impulse.
- `:app:assembleRelease`: успешно, 0 errors, 3 warning (PendingIntent, вне
  рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `34ee259f1c9cba405b044851128b4a8482ab72c737d7be9b0c56193726daaa87`.
- Готовый APK:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage5-upstream-canvas.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so`; версия приложения и
  JNI-поверхность не менялись.
- Устройство не перезапускалось: текущая Termux-сессия является рабочим
  окружением, поэтому `adb install`/`force-stop` не применялись. Визуальная
  проверка `tmux`/`htop`/OpenCode TUI, emoji/CJK, styled underline и
  transparency, а также device-профилирование p95 остаются за пользователем.
- На момент этого APK legacy planner ещё оставался; в следующем этапе он
  удалён вместе с `OP_TEXT` и `headless_set_use_canvas()`.

### Дополнительно: Canvas batching и отзывчивость прокрутки (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены проверки
  LTR-слияния `abc` в один `OP_GLYPH` (`charCount=3`, `widthColumns=3`) и
  слияния трёх одинаковых явных фонов в один fill шириной 24 px с одним
  glyph-run.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, source/output
  SHA-256 не изменились.
- Целевые JVM-тесты (`RenderPayloadTest`, `AppendStateBufferTest`,
  `ScrolledUpdateRepaintTest`, `DirtyStateTest`, `FlingLiveOutputTest`,
  `ScrollFollowTextTest`): 32/32.
- `terminal-view`: 37/37. Полный `terminal-emulator`: 244 теста, 48 известных
  падений в старых тестах Java-семантики, не связанных с render/frame/damage
  путём.
- `:app:assembleRelease`: успешно, 0 errors, 3 warning (PendingIntent, вне
  рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `1601a038fbabe774875db196d7c5ed60e94f4b08a869d08d11baf764b2aa6a5e`.
- Готовый APK:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage5-canvas-batching.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so`; версия приложения и
  JNI-поверхность не менялись.
- Устройство не перезапускалось: текущая Termux-сессия является рабочим
  окружением, поэтому `adb install`/`force-stop` не применялись. Проверка
  плавности прокрутки на устройстве остаётся за пользователем.

### Исправление артефактов блочной графики после glyph batching (29.10.2026)

Причина регрессии — не TUI и не шрифт, а сочетание stage-5 batching с Java-путём
`OP_GLYPH`:

- нативный sink сливает соседние одинаково оформленные глифы в один `OP_GLYPH`;
  в блочном wordmark это full block `█`, half block `▀` и вставленные пробелы;
- `TerminalRenderer.drawGlyph()` сразу вызывал `drawGlyphs()`, который при
  несовпадении измеренной ширины масштабирует **весь run** целиком;
- у fallback-глифов блоков и у основного пробела/текста разные advance, поэтому
  общий scale сжимал блоки относительно их клеточных фонов и оставлял шов на
  каждой границе клетки;
- прежний `OP_TEXT`-путь в этой ситуации уже использовал `drawTextClusters()`
  и подгонял каждый code point/cluster в свою клетку.

Исправление: `drawGlyph()` и `drawTextRun()` теперь идут через общий `drawRun()`;
`runNeedsClusterFallback()` выбирает per-cluster путь при любом несовпадении
ширины, которое можно исправить масштабом. Для точного совпадения (обычный
ASCII и другие однородные run) сохраняется быстрый whole-run `drawTextRun()`.

Проверки:

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`; добавлен run
  `█ █` → один `OP_GLYPH` с `charCount=3`, `widthColumns=3` и пробелом в
  UTF-16 payload.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`; SHA-256
  извлечённого upstream-блока не изменился.
- JVM `terminal-view`: 42/42, включая 5 новых тестов
  `TerminalRendererGlyphRunClusterTest` на mixed block/space run, точное
  совпадение, недоступный scale и вырожденные входы.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных warning
  (PendingIntent).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `dd0aeffb139526fe30de54de77a5b6af92145fa4ca84052130d9eb88950a06c8`.
- Готовый APK:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage5-blockart-fix.apk`.
- Устройство не перезапускалось: установка и визуальная проверка wordmark,
  прокрутки и остального TUI остаются за пользователем.

### Прозрачность блочных глифов в Canvas-пути (29.10.2026)

После исправления wordmark пользователь включил прозрачность фона и обнаружил
непрозрачную горизонтальную полосу в поле ввода OpenCode
(`Screenshot_20261029-180046_TermuxEnhanced.png`). Это не фон и не обычный
прямоугольник: попиксельный разбор показал строку из клеток `▀` (U+2580,
upper half block) шириной 14 px и высотой 14 px каждая, цвет `#1e1e1e`.

Причина — потеря effect-бита при переходе на upstream Canvas-путь:

- прежний `OP_TEXT` получал эффекты из `effects_for_cell()`, где для
  U+2580..U+259F выставлен `EFFECT_BLOCK_FILL` (1 << 7);
- `canvas_draw_glyph()` для `OP_GLYPH` заново собирал effects только из
  `font_index` (bold/italic) и терял этот бит;
- поэтому `TerminalRenderer.drawGlyph()` не включал H1-путь `SRC_ATOP` и
  рисовал блочный глиф обычным непрозрачным foreground поверх
  полупрозрачного фона.

Исправление:

- в `render_model.c` добавлен общий `EFFECT_BLOCK_FILL`;
- `canvas_draw_glyph()` выставляет его для codepoint U+2580..U+259F;
- пробел наследует флаг предыдущего block-run, чтобы `█ █` по-прежнему
  сливался в один `OP_GLYPH` и не терял batching;
- effect-бит также разрывает run между block-art и обычным текстом, чтобы
  `SRC_ATOP` не применялся к ASCII.

Проверки:

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`; run `█ █` остаётся
  одним `OP_GLYPH` (`charCount=3`, `widthColumns=3`) с `EFFECT_BLOCK_FILL`;
- новый regression `A█B`: 3 отдельных glyph-run, block-бит только у `█`;
- `terminal-view` JVM: 42/42;
- `terminal-emulator`: 244 теста, прежние 48 падений в старых Java-тестах
  семантики, не связанных с renderer/frame/damage;
- `foot-render-cell.inc` SHA-256 не изменился: `dcf1ba...efe5`.

- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `31b29fbea37b644ea5f5077331f3d0801e53b3ceb51c8f9aee9ac314503c32ce`;
- готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage5-blockfill-alpha-fix.apk`.

Устройство не перезапускалось. Установка APK, визуальная проверка
прозрачности полосы `▀` в OpenCode и остального TUI остаются за
пользователем.

### Canvas-only cleanup и bold-in-bright parity (29.10.2026)

Удалён второй, legacy-планировщик клеток. `FootRenderModel` теперь всегда
вызывает upstream `render_row()` через Canvas bridge; `use_canvas`,
`headless_set_use_canvas()`, `foot_render_model_set_use_canvas()`, `OP_TEXT`
и его Java-обработчик удалены. Позже, на этапе 8, удалены и `OP_RECT`/
`OP_CURSOR`: fallback-строк без native row больше нет, а курсор и selection
рисует только upstream `render_cell()`.

При переносе обнаружилась регрессия bold-in-bright: foot сравнивает
разрешённый RGB с первыми восемью цветами палитры, поэтому `38;5;16` (чёрный,
совпадающий с palette[0]) ошибочно становился palette[8]. Termux исторически
повышает яркость только по индексу: base16/base256 `fg < 8`. Bridge теперь
отключает foot-овский цветовой проход и временно сдвигает `attrs.fg` на +8
только для этих клеток, сохраняя bold-шрифт; после строки атрибуты
восстанавливаются.

Проверки:

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`; старые
  semantic-тесты переведены на Canvas opcodes, проверка `38;5;16` больше не
  ловит ложный bright;
- `terminal-view` JVM: 42/42;
- `terminal-emulator` `RenderPayloadTest`: успешно;
- `foot-render-cell.inc` SHA-256 не изменился: `dcf1ba...efe5`;
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning;
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `4383e39d04a2b445c61d159e7910204b1ab121b96f555ac1c9bc5e926c1f57ac`;
- готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage6-canvas-only.apk`;
- device-проверка не выполнялась: установка APK остаётся за пользователем.

---

## 16. Фактическая проверка этапа 7 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`; включены
  sixel-проверки (`OP_IMAGE`, пиксели, отсутствие повторной отправки на чистом
  кадре, перекрытие/обрезка текстом) и OSC-8 underline.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, SHA-256
  `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
- `RenderPayloadTest`: 3/3, включая end-to-end `OP_IMAGE` →
  `nativeSixelPixels()` и проверку красного пикселя; `AppendStateBufferTest`:
  2/2. Итого целевые JVM-тесты: 5/5.
- `terminal-view` JVM: 42/42; `:terminal-emulator:compileDebugJavaWithJavac` и
  `:terminal-view:compileDebugJavaWithJavac`: успешно.
- При проверке найден и исправлен реальный баг ABI: native кладёт `idLo`
  первым, а Java собирала id в обратном порядке. Без исправления
  `nativeSixelPixels()` всегда возвращал `NULL`, хотя `OP_IMAGE` уже
  появлялся в потоке. Regression-тест теперь получает пиксели через JNI.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `fc8cc5a2963c166dd83d8c1ff574c068f773cf515f3283d6069c269933c18c0a`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage7-sixel-url.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` с
  `Java_com_termux_terminal_TerminalNative_nativeSixelPixels`; debug-вывод
  sixel в библиотеке отсутствует.
- Устройство не перезапускалось: текущая Termux-сессия является рабочим
  окружением, поэтому `adb install`/`force-stop` не применялись. Установка и
  визуальная проверка sixel/OSC-8 остаются за пользователем.

---

## 17. Фактическая проверка этапа 8 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Bridge вызывает
  upstream `render_row()`; sink сортирует row-операции по x и сохраняет
  порядок внутри клетки. Добавлен regression на курсор при непустой истории:
  ровно один cursor-fill в `y == 0`, без потери из-за сравнения ring-индекса
  `cursor.point.row` с внешним адресом строки.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, SHA-256
  `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
  Извлечённый upstream-блок не менялся.
- Целевые JVM-тесты: `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2
  (5/5).
- `terminal-view` JVM: 42/42; Java-компиляция `terminal-emulator` и
  `terminal-view` успешна.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `701ca9e01ceed6648689b3b56ac44f86fbe04cc4d2828f0d1a9134997c5516ae`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage8-render-row.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` с обновлённым
  `render_row()`-путём и исправлением курсора.
- Устройство не перезапускалось: установка APK и визуальная проверка
  `tmux`/`htop`/OpenCode TUI, sixel/OSC-8, курсора в history и p95 остаются за
  пользователем.
