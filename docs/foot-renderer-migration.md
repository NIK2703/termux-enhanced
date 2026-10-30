# Перенос рендеринга foot на Android: план и состояние

**Ветка:** `feature/foot-terminal-core`
**Версия приложения:** `0.118.0+enhanced.22` (не меняется)
**Исходный foot:** `152487e494993ad7fb5633a27c7d7ad0f8810ea5`
**Статус:** выполнены этапы 1–14: Canvas-путь, retained-ARGB scroll replay,
upstream scroll-damage mover, upstream cursor-damage policy, upstream
selection-damage policy и upstream-разметка видимого selection.
Canvas-путь переиспользует **настоящий upstream** `render_row()`/`render_cell()` из
`third_party/foot/render.c` через Canvas compatibility backend: извлечённый
блок компилируется как `foot-render-cell.inc`, а `pixman`/`fcft` заменены
тонким shim'ом, который выдаёт команды Canvas вместо растеризации. Bridge
вызывает upstream `render_row()` буквально; правый-налево порядок ячеек
переупорядочивается в Canvas-sink'е перед эмиссией LTR text run'ов. Legacy
planner, `OP_TEXT` и fallback-политика `OP_RECT`/`OP_CURSOR` удалены:
`render_cell()` — единственный источник цветов, selection и курсора. OSC-8
ссылки всегда подчёркиваются upstream-кодом, а sixel собирается upstream
`sixel.c`; Android получает готовые ARGB-пиксели через `OP_IMAGE` и рисует их
`Bitmap`. Повреждение прокрутки проигрывается на retained ARGB-поверхности
через `OP_SCROLL` **тем же** upstream `grid_render_scroll()`/
`grid_render_scroll_reverse()`, что и в `render.c`: извлечённый
`foot-grid-scroll.inc` делает `memmove` непосредственно по pixels, полученным
из `AndroidBitmap_lockPixels()`. Moved-строки не перерисовываются.
Повреждение курсора тоже вынесено из Android-адаптера: `dirty_old_cursor()` и
`dirty_cursor()` извлечены verbatim в `foot-render-damage.inc`, а модель лишь
определяет момент вызова с учётом частичных кадров и отложенного commit.
Selection-damage использует upstream `pixman_region_for_coords*()` и
`mark_selected_region()` из `foot-selection.inc`: Android передаёт
transcript-relative координаты, адаптер переводит их в absolute ring rows и
выполняет тот же diff старой/новой области, что `selection_modify()`.
Видимые selected-биты также выставляет upstream-политика
`MARK_SELECTION_MARK_FOR_RENDER`; адаптер применяет её к каждой уже
перерисовываемой строке, поэтому trailing пустые клетки не подсвечиваются, как
в foot.
Ограничения Canvas-пути зафиксированы в 6.4, 6.5, 10 и 11.

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

Помимо cell renderer'а, из того же pinned-файла извлекаются scroll-damage
pixel movers: `grid_render_scroll()` … `grid_render_scroll_reverse()` в
`src/foot-grid-scroll.inc`. Они используют upstream `struct buffer`, но
`buf->data` указывает на pixels, полученные из Android `Bitmap`; SHM-ветка
отключена, поэтому исполняется upstream `memmove` fallback. `render_margin()`
в Android-сборке недостижим (его вызывает только успешный SHM scroll), поэтому
для компиляции извлечённого блока оставлен пустой shim.

Политика повреждения курсора извлекается отдельным непрерывным блоком
`dirty_old_cursor()` … `dirty_cursor()` в `src/foot-render-damage.inc`.
Android-адаптер не дублирует правила «какие строки пометить»: он только
сравнивает overlay-состояние с предыдущим кадром, подставляет видимость
курсора на время синхронного вызова и защищает upstream `last_cursor` от
переиспользованного ring-row. Это необходимо потому, что Android рисует
частичные band'ы и снимает `row->dirty` только после Java-commit.

Текст обычного selection тоже идёт через upstream: `extract.c` компилируется
без изменений, а `foreach_selected_normal()` … `selection_to_text()` извлекаются
в `src/foot-selection-text.inc`. Android-адаптер только переводит
transcript-relative координаты в absolute ring rows, расширяет wide-endpoint до
целого grapheme и сохраняет Termux-совместимый trim печатных пробелов в конце
hard-line. Transcript-режимы и word lookup остаются на отдельном walker'е,
поскольку upstream `extract.c` не выражает их флаги.

Все пять извлечений проверяются на этапе configure и перед каждой сборкой:
`tools/check-render-cell.sh --check`, `tools/check-render-damage.sh --check`,
`tools/check-selection-region.sh --check`, `tools/check-selection-text.sh --check`
и `tools/check-grid-scroll.sh --check` сверяют SHA-256 исходника, SHA-256
сгенерированного файла и точный диапазон строк. Команды пересоздания:
`sh terminal-emulator/src/main/foot/tools/check-render-cell.sh --write`,
`sh terminal-emulator/src/main/foot/tools/check-render-damage.sh --write`,
`sh terminal-emulator/src/main/foot/tools/check-selection-region.sh --write`,
`sh terminal-emulator/src/main/foot/tools/check-selection-text.sh --write` и
`sh terminal-emulator/src/main/foot/tools/check-grid-scroll.sh --write`.

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
   │  frame/damage header, per-row selection marking, canvas sink,
   │  pending scroll queue
   │
   ├── foot-render-cell.inc                                 [upstream, verbatim]
   │      render_row() → render_cell(): fg/bg, attrs, cursor, selection
   │
   ├── foot-render-damage.inc                               [upstream, verbatim]
   │      dirty_old_cursor() / dirty_cursor(): cursor damage policy
   │
   ├── foot-selection.inc                                  [upstream, verbatim]
   │      pixman_region_for_coords() / mark_selected_region(): selection
   │      damage diff and MARK_FOR_RENDER policy
   │
   ├── sixel.c + hsl.c                                     [upstream, verbatim]
   │      sixel decode → grid->sixel_images → sync/scale/crop/reflow
   │
   ├── foot_canvas_bridge.c                                 [тонкий адаптер]
   │      synthetic fcft fonts, attrs.clean reset, literal render_row()
   │
   └── foot_canvas_shim.c                                   [pixman/fcft shim]
          fills/glyphs/trapezoids → OP_FILL_RECT/OP_GLYPH/OP_FILL_TRAPEZOID
          sixel pixman surface    → OP_IMAGE + nativeSixelPixels()
   ▼
TerminalRenderer (Canvas)                                   [Android backend]
   │  glyph shaping, Paint, Canvas, retained view Bitmap, upstream scroll mover, commit
   ▼
Surface

headless.c (адаптер) ── palette / append / events / snapshots / JNI wrappers
```

### Границы ответственности

| Слой | Владеет | Не владеет |
|---|---|---|
| foot core | grid, row, cell, cursor, selection, scroll damage, sixel placements | пикселями, Canvas, JNI |
| `FootRenderModel` | viewport, pending full damage, pending scroll, commit dirty, frame/damage header, выбор строк, row-op buffer/sort, damage-gate, `OP_SCROLL` и `OP_IMAGE` | текстом, шрифтами, Canvas |
| `foot-render-cell.inc` | upstream-политика cell/row: цвета, attrs, cursor, selection, OSC-8 underline | Wayland, shm, workers |
| `foot-selection.inc` | upstream geometry/damage selection: region diff, selected bits, dirty rows | Canvas, JNI, Java-координаты |
| upstream `sixel.c` | sixel decode, палитра, размещение, crop, scale, reflow | Canvas, JNI, Bitmap |
| `foot_canvas_shim` | pixman/fcft-совместимость, region algebra, sixel-поверхности, команды | реальной растеризацией шрифтов |
| `headless.c` | append, палитра, события, snapshot, JNI-facing state, доступ к sixel-пикселям, scroll-region dirty | решением «что и где рисовать» |
| `TerminalRenderer` | шрифты, Canvas, clip, retained view Bitmap, upstream scroll replay, Bitmap-кэш sixel, фактическая отрисовка | семантикой терминала |
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
9. **Scroll replay идёт до строк и только на retained surface.** `OP_SCROLL`
   адресует viewport-relative row-регион; native core применяет upstream
   `grid_render_scroll()`/`grid_render_scroll_reverse()` к pixels
   view-размерного ARGB `Bitmap`, затем Java рисует только реально
   повреждённые строки кадра и блитит затронутый band. Full damage отменяет
   pending scroll, а регион, заменённый целиком, эмитится обычными
   `OP_DAMAGE`-строками. Exposed-строки не очищаются в native memmove: они
   обязаны прийти обычным dirty-damage и быть полностью закрашены row-путём.

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
  ├─ process_scroll_damage(): применимые DAMAGE_SCROLL* копируются в
  │     model->pending_scroll; row->dirty для затронутых строк; scroll-списки
  │     обоих grid освобождаются, чтобы не считать их дважды
  ├─ viewport-before snapshot: сравнение row-указателей по слотам
  │     (ловит grid_swap_row без row->dirty; NULL-строка → full damage)
  ├─ scroll counter: DAMAGE_SCROLL.lines или дельта ring-offset
  │     (совместимость и тесты; UI использует native view top)
  ├─ sync_view(): перечитать grid->view; сдвиг вниз → full damage
  ├─ full_damage при alt-switch / history shrink / mode change / потере строки
  ├─ вернуть внешние строки, у которых full_damage || row->dirty ||
  │     строка входит в pending scroll region
  │     (row->dirty НЕ очищается)
  └─ если full_damage ещё pending, выставить APPEND_FLAG_FULL_DAMAGE

render(top_row, clip, rows)
  ├─ set_viewport() уже вызван view-слоем
  ├─ planner выдаёт frame-заголовок с view_top/full_damage
  ├─ planner выдаёт OP_SCROLL-регионы (до row-команд)
  ├─ planner выдаёт OP_DAMAGE-диапазоны для запрошенного band
  ├─ planner выдаёт команды для запрошенных строк
  └─ TerminalRenderer применяет upstream scroll mover к retained Bitmap, рисует строки и
      собирает фактически нарисованные строки

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

Selection передаётся в upstream через `cell->attrs.selected`. Render model
расширяет диапазон колонок до целого wide-grapheme, если endpoint попал на
spacer-ячейку, строит регион через upstream `pixman_region_for_coords()` и
вызывает upstream `mark_selected_region()` с
`MARK_SELECTION_MARK_FOR_RENDER` для каждой уже перерисовываемой строки.
Bridge больше не вычисляет selection span: он только сбрасывает
`attrs.clean`. Upstream сам инвертирует цвета, обрабатывает `fg == bg` и
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

### 6.6. Retained surface и scroll replay

Canvas больше не является единственным носителем предыдущего кадра. Каждый
`TerminalRenderer` владеет view-размерным `Bitmap` в `ARGB_8888`; в него
рисуются row-команды, а в конце кадра нужный band блитится на View-canvas с
`PorterDuff.Mode.SRC`. Поэтому прозрачный фон не композитится повторно, а
retained-пиксели остаются авторитетными и за пределами текущего clip'а.

`OP_SCROLL` (opcode 13) эмитится **до** row-команд и содержит
`start, end, lines, reverse`. `start`/`end` — viewport-relative строки, а не
внешние адреса. Java передаёт их вместе с native handle и retained `Bitmap` в
`nativeScrollSurfaceRows()`; JNI берёт pixels через
`AndroidBitmap_lockPixels()` и передаёт их в
`foot_grid_scroll_apply_rows()`. Native core сам выводит пиксельный band из
`term->margins.top`, `term->cell_height` и `term->rows`, проверяет, что band
целиком помещается в Bitmap, и вызывает извлечённые upstream
`grid_render_scroll()`/`grid_render_scroll_reverse()` с `struct buffer`,
указывающим на locked pixels. Никакого второго `memmove` в JNI больше нет.
`libjnigraphics.so` резолвится через `dlopen()`/`dlsym()` при первом scroll, а
не через `DT_NEEDED`: иначе host-JVM-тесты Termux пытаются загрузить Android
graphics runtime и падают в `ProtoLogNative`.

Для обычного scroll'а upstream двигает содержимое вверх (`start+lines →
start`), для `reverse` — вниз. Upstream **не очищает** освободившийся band:
он остаётся старым содержимым Bitmap, пока обычный frame не закрасит
exposed-строки целиком. Это безопасно, потому что эти строки входят в
frame-damage и рисуются row-командами; при прозрачном фоне
`PorterDuff.Mode.SRC` заменяет старые пиксели, а не композитит поверх них.
Если grid переведён elastic over-drag'ом (`mSurfaceYOffset` не соответствует
обычному row origin), Java не применяет `OP_SCROLL`, а просит полный кадр:
частично видимую band нельзя двигать построчным memmove.

Порядок и правила:

1. `process_scroll_damage()` копирует только применимые повреждения активного
   grid в `pending_scroll`; `DAMAGE_SCROLL*` применяется при
   `grid->view == grid->offset`, `*_IN_VIEW` — всегда.
2. `headless_append()` добавляет все строки scroll-региона в Java dirty mirror.
   Это нужно, чтобы `TerminalView` инвалидировал именно band, содержимое
   которого передвинулось, хотя native `row->dirty` у moved-строк нет.
3. Когда pending scroll есть, pointer-based `mark_moved_viewport_rows()` не
   дублирует ту же работу.
4. `render_frame()` не эмитит `OP_SCROLL`, если `lines >= region_size`:
   такой регион заменён целиком и должен быть перерисован обычными строками.
   Full damage отбрасывает `pending_scroll` до формирования заголовка.
5. После успешной сборки кадра pending scroll очищается независимо от того,
   был ли кадр частичным: Java уже применила move к retained surface.
6. Полный кадр очищает весь `Bitmap` фоном и блитит его целиком; частичный —
   только band `renderStartRow..renderEndRow`. Если `Bitmap` ещё не создан,
   изменился размер/viewport/фон или Java потеряла валидность пикселей,
   surface пересоздаётся/очищается и все видимые строки рисуются заново.
7. При нехватке памяти под `Bitmap` рендерер откатывается на прямой canvas:
   scroll не проигрывается, а все строки кадра принудительно перерисовываются.

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
OP_SCROLL(startRow, endRow, lines, reverse)             # viewport-relative
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
`OP_SCROLL` идёт до первой row-команды и применяется к retained surface
через `nativeScrollSurfaceRows(handle, bitmap, startRow, endRow, lines,
reverse)`. `startRow`/`endRow` — viewport-relative, `lines` — целые строки;
native core сам вычисляет пиксельный band по `term->cell_height`/`margins` и
вызывает upstream `grid_render_scroll()`/`grid_render_scroll_reverse()`.
Операция не эмитится для региона, который заменён целиком, и отменяется full
damage.

Свойства:

- `OP_FRAME_BEGIN` всегда первый opcode кадра; `OP_FRAME_END` стоит непосредственно
  перед `OP_END`.
- `damageRangeCount` и `OP_DAMAGE` описывают только запрошенный band
  `[renderTop, renderTop + renderRows)`. Непрерывные dirty-строки схлопываются в
  один диапазон, поэтому число диапазонов не больше числа строк.
- `OP_SCROLL` описывает сдвиг целого региона независимо от запрошенного band;
  строки самого региона попадают в Java dirty mirror, чтобы view
  инвалидировал весь передвинутый band. `OP_SCROLL` не заменяет `OP_DAMAGE`
  для новых/изменённых строк.
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

### Этап 4. Scroll damage и производительность — выполнен

- [x] `process_scroll_damage()` классифицирует `DAMAGE_SCROLL*` и складывает
      применимые повреждения активного grid в `pending_scroll`. Регион
      клампится, `DAMAGE_SCROLL_IN_VIEW` считается относительно `grid->view`;
      scroll-списки обоих grid освобождаются после queueing.
- [x] Добавлено сравнение identity строк по видимым слотам до/после append.
      foot делает `grid_swap_row()` без `row->dirty`; при отсутствии pending
      scroll слот, в котором сменился row-указатель, помечается dirty. Это
      покрывает partial scroll region при прокрученной истории. Если слот стал
      `NULL` или snapshot не выделился, запрашивается full damage.
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
- [x] `OP_SCROLL` и честный `memmove` введены на retained ARGB surface:
      native queue → frame op → `nativeScrollSurfaceRows()` под
      `AndroidBitmap_lockPixels()` → band blit. Full damage отменяет pending
      scroll, а полностью заменённый регион откатывается на row-damage.
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
      `font_ascent`, сброс `attrs.clean`, буквальный вызов upstream
      `render_row()`; `render_cell()` остаётся извлечённым и проверяется
      хэшем. Selection spans того этапа заменены upstream-разметкой на этапе
      14.
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

### Этап 9. Retained ARGB surface и scroll replay — выполнен

- [x] `TerminalRenderer` владеет view-размерным `ARGB_8888 Bitmap`; row-команды
      рисуются в него, а на View-canvas блитится только изменённый band.
- [x] `OP_SCROLL` эмитится до row-команд; pending scroll отбрасывается при full
      damage и для региона, заменённого целиком.
- [x] `nativeDamageRows()` позволяет Android overlays (scrollbar band) вернуть
      native damage без второго источника истины.
- [x] Ошибка `lockPixels`/отсутствие Bitmap bridge приводит к полной
      перерисовке кадра, а не к потере scroll-содержимого.

### Этап 10. Upstream scroll-damage mover — выполнен

- [x] `grid_render_scroll()`/`grid_render_scroll_reverse()` извлечены verbatim
      в `src/foot-grid-scroll.inc`; диапазон и SHA-256 проверяются
      `tools/check-grid-scroll.sh`.
- [x] `foot_grid_scroll_apply_rows()` строит upstream `struct buffer` поверх
      pixels из `AndroidBitmap_lockPixels()` и вызывает эти функции; JNI
      больше не содержит собственного `memmove`.
- [x] JNI/Java ABI передаёт `handle, bitmap, start, end, lines, reverse`;
      native core сам выводит пиксельный band из `term->cell_height`/`margins`
      и проверяет его границы.
- [x] Убрано обнуление exposed-band: upstream semantics требует, чтобы эти
      строки пришли обычным dirty-damage и были закрашены row-путём.
- [x] Некорректная геометрия, elastic over-drag или отсутствие Bitmap bridge
      возвращают `false`, после чего Java запрашивает полный кадр.
- [x] Native regression: forward/reverse/partial scroll, stride padding,
      полностью заменённый регион и invalid geometry.
- [ ] Визуальная проверка scroll replay на устройстве и p95 после сборки APK.

### Этап 11. Upstream cursor-damage policy — выполнен

- [x] `dirty_old_cursor()`/`dirty_cursor()` извлечены verbatim из pinned
      `render.c` в `src/foot-render-damage.inc`; диапазон и SHA-256
      проверяются `tools/check-render-damage.sh`.
- [x] `FootRenderModel` больше не дублирует правила пометки строк: он только
      определяет, когда overlay-состояние изменилось, и вызывает upstream
      функции.
- [x] `term->hide_cursor` подставляется из Android `cursor_visible` только на
      время синхронного damage-вызова и затем восстанавливается, чтобы не
      менять DECTCEM-состояние VT-ядра.
- [x] Upstream `last_cursor` защищён от переиспользованного ring-row: при
      несовпадении сохранённого row-указателя и индекса он инвалидируется до
      вызова.
- [x] Повторный частичный кадр с неизменившимся курсором не переотмечает его
      строку: `row->dirty` снимается только после Java-commit.
- [x] Native regression: движение видимого курсора помечает старую и новую
      строки; скрытие/показ, смена shape и неизменный курсор в partial band.
- [ ] Визуальная проверка курсора (block/underline/beam/hollow, blink, history,
      selection) на устройстве.

### Этап 12. Upstream selection-damage policy — выполнен

- [x] `pixman_region_for_coords_normal()` … `mark_selected_region()` извлечены
      verbatim из pinned `selection.c` в `src/foot-selection.inc`; диапазон и
      SHA-256 проверяются `tools/check-selection-region.sh`.
- [x] `sync_native_selection()` переводит transcript-relative координаты Java
      в absolute ring rows и выполняет diff старой/новой области через
      upstream `MARK_SELECTION_UNMARK_AND_DIRTY`/`MARK_SELECTION_MARK_AND_DIRTY`.
      Это повторяет `selection_modify()` без вызова `render_refresh()`.
- [x] Selection активен, если `x1 >= 0 && x2 >= 0`; отсутствие selection —
      все четыре координаты `-1`. Благодаря этому выделение в history,
      включая строку `-1`, больше не теряется.
- [x] При reset/viewport change absolute selection сбрасывается; full damage
      перерисовывает видимые строки и заново применяет координаты Java.
- [x] Canvas bridge на этом этапе ещё вычислял видимый span строки; этап 14
      заменил его upstream-разметкой `MARK_SELECTION_MARK_FOR_RENDER`.
- [x] Native regression: пустые клетки внутри selection дают один fill;
      снятие selection убирает selected-биты; selection в history помечает
      строки `-2/-1`; повторный кадр без изменений не помечает строки.
- [ ] Визуальная проверка selection (обычное, history, wide/CJK, снятие) на
      устройстве.

### Этап 13. Upstream selection text — выполнен

- [x] `extract.c` собирается без изменений из pinned submodule;
      `foreach_selected_normal()` … `selection_to_text()` извлечены verbatim
      в `src/foot-selection-text.inc`; диапазон и SHA-256 проверяются
      `tools/check-selection-text.sh`.
- [x] `headless_selected_text()` для обычного copy (`join_back_lines=true`,
      `join_full_lines=false`) больше не обходит grid через
      `row_text_init()`: адаптер временно выставляет absolute ring coords,
      вызывает upstream `selection_to_text()` и восстанавливает selection
      state.
- [x] Концы selection на spacer/base wide-символа расширяются до целого
      grapheme; координаты клампятся к history/screen.
- [x] Termux-совместимость сохранена: upstream отбрасывает never-written
      trailing cells, а адаптер дополнительно обрезает печатные пробелы в
      конце hard-line; wrapped-строки склеиваются, hard linebreak остаются.
- [x] Transcript modes (`join_back_lines=false` / `join_full_lines=true`) и
      word lookup остаются на прежнем walker'е: upstream `extract.c` не
      выражает эти два режима.
- [x] Native regression: wide/spacer selection, wrapped vs hard newline,
      interior spaces, trailing printed spaces, empty-only selection.
- [x] JVM: selection-тесты `ScreenBufferTest` 3/3 (добавлен Unicode case),
      целевые rendering/scroll тесты 19/19, `terminal-view` 42/42.
- [ ] Визуальная проверка copy на устройстве: обычный текст, history,
      wide/CJK, wrapped-строки, URL и вставка в другое приложение.

### Этап 14. Upstream selection visual policy — выполнен

- [x] `mark_row_selection()` в `render_model.c` строит регион через upstream
      `pixman_region_for_coords()` и применяет
      `MARK_SELECTION_MARK_FOR_RENDER` к пересечению selection с одной
      отрисовываемой строкой. Это тело `selection_dirty_cells()` из
      `selection.c`, ограниченное одной строкой.
- [x] Полный `selection_dirty_cells()` не вызывается каждый кадр: upstream
      помечает `row->dirty` для пустых selected-клеток, а Android снимает
      dirty только после Java-commit, из-за чего такие строки перерисовывались
      бы бесконечно. Разметка выполняется только для строк, которые уже
      входят в текущий frame damage.
- [x] `sync_native_selection()` расширяет оба endpoint'а до whole grapheme:
      spacer → base для start/end и base → spacer для end. Это то же
      правило, что в upstream `selection_modify()`.
- [x] `foot_canvas_prepare_row()` больше не перебирает selection spans и не
      выставляет `attrs.selected`; bridge только сбрасывает `attrs.clean`
      перед буквальным `render_row()`.
- [x] Визуальная семантика пустых клеток теперь foot-совместимая: внутренние
      пустые клетки перед непустой клеткой подсвечиваются, trailing пустые
      клетки — нет. Прежнее Termux-поведение «выделять весь span до конца»
      удалено вместе с custom span-кодом.
- [x] Native regression: trailing blanks не дают selection fill; wide-endpoint
      на spacer'е подсвечивает обе колонки grapheme; прежние проверки
      инверсии цветов, `fg == bg`, history-damage и снятия selection проходят.
- [ ] Визуальная проверка selection на устройстве: обычное, history, wide/CJK,
      пустые клетки, снятие.

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
- selection: начало/конец на spacer'е wide-символа выделяет весь grapheme;
- selection при `fg == bg` получает видимый инвертированный фон;
- selection visual policy: внутренние пустые клетки перед непустой
  подсвечиваются и сливаются в fill, trailing пустые клетки не
  подсвечиваются; снятие selection снимает selected-биты перед перерисовкой;
- selection в history: отрицательные transcript-координаты (включая строку
  `-1`) переводятся в absolute ring rows и помечают старые/новые строки;
  повторный кадр без изменения selection не переотмечает строки;
- full-screen scroll при прокрученной истории увеличивает scroll counter, но не
  помечает неизменившиеся видимые слоты;
- partial scroll region помечает только слоты, у которых сменился row-указатель;
- Canvas path: обычный глиф, block cursor с contrast glyph, selection
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
- `headless_scroll_surface_rows()`/upstream scroll mover: forward, reverse и
  partial scroll двигают целые terminal-строки с учётом `stride`, padding
  едет вместе со строкой, exposed-band остаётся нетронутым до row-damage;
- полностью заменённый scroll-регион не трогает pixels и возвращает успех;
  invalid `end`/пустой регион возвращают `false` для полного repaint;
- sixel: красный sixel даёт один `OP_IMAGE` с ненулевым id, `headless_sixel_pixels()`
  возвращает поверхность нужного размера и красный пиксель;
- sixel: чистый кадр после commit не повторяет `OP_IMAGE`;
- sixel: перекрытие широкого изображения текстом проходит через crop/region
  subtraction и не теряет `OP_FRAME_END`;
- OSC-8: `\e]8;;URL\e\\X\e]8;;\e\\` даёт underline-fill цвета foreground,
  нарисованный upstream `render_cell()`;
- selection text: обычный copy идёт через upstream `extract.c`; wide-символ
  выбирается с любой половины, wrapped-строки склеиваются, hard linebreak
  сохраняются, внутренние пробелы не теряются, печатные пробелы в конце
  hard-line обрезаются, empty-only selection даёт пустую строку;
- transcript modes и word lookup по-прежнему используют прежний walker и не
  меняют свою семантику;
- `check-render-cell.sh --check`, `check-render-damage.sh --check`,
  `check-selection-region.sh --check`, `check-selection-text.sh --check` и
  `check-grid-scroll.sh --check` подтверждают source/output SHA-256 и точные
  диапазоны извлечения.

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
| Смена viewport без full repaint | `set_view` оптимизирует только соседний переход без pending scroll/full damage; Java проверяет retained surface и иначе делает полный кадр; native `viewport_shift_tests` |
| Частичный clip теряет dirty | full damage не снимается до commit всех строк; scroll-blit carrier при частичном clip откатывается к full row repaint |
| `grid->view` уезжает при append | `sync_view()` перечитывает foot после каждого append |
| Один chunk прокручивает больше половины ring | follow-text использует точный `view_top`, а не дельту offset; сдвиг вниз → full damage |
| Full damage вызывает лишние кадры | full снимается только после полного commit; на scrolled-up output full не ставится |
| Selection на wide-символе выделяет половину | render model расширяет оба endpoint'а до whole grapheme, затем upstream `mark_selected_region()`; native canvas-тест |
| Selection в history с `y = -1` раньше считался неактивным | active определяется по `x1/x2 >= 0`, отсутствие selection — все четыре `-1`; native regression на строки `-2/-1` |
| Большое selection в history перебирает много ring-rows | region строится один раз на строку, а не на кадр; marking выполняется только для строк текущего frame damage |
| Selection visual расходится с foot для пустых клеток | используется `MARK_SELECTION_MARK_FOR_RENDER`: trailing blanks не подсвечиваются; native regression |
| Выделение невидимо при `fg == bg` | upstream `render_cell()` инвертирует фон; native canvas-тест |
| Canvas-путь регрессирует | native canvas-тесты; откат — `git revert` этапа, legacy-планировщика больше нет |
| `.inc` разошёлся с pinned `render.c` | configure/PRE_BUILD проверяют source/output SHA-256; `--write` только после осознанного re-pin |
| scroll-band не совпадает с геометрией terminal core | native проверяет `margins.top`/`cell_height`/`rows` и границы Bitmap; ошибка → полный repaint |
| upstream memmove не очищает exposed-band | эти строки входят в frame damage и полностью закрашиваются row-путём; native regression проверяет границы |
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

## 11. Изменённые файлы этапов 1–14

- `terminal-emulator/src/main/foot/src/render_model.c` — viewport, full damage,
  commit, frame/damage header, row-op buffer/sort, Canvas sink; переиспользуемые
  command/text buffers. Legacy planner удалён; добавлен damage-gated
  `OP_IMAGE`, pointer-сопоставление курсора при scrollback, вызов upstream
  cursor-damage policy, upstream selection-damage diff и per-row
  `MARK_SELECTION_MARK_FOR_RENDER` policy.
- `terminal-emulator/src/main/foot/src/render_model.h` — API модели и palette
  layout.
- `terminal-emulator/src/main/foot/src/foot-render-cell.inc` — verbatim
  upstream `attrs_to_font()` … `render_row()`; генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot-render-damage.inc` — verbatim
  upstream `dirty_old_cursor()` … `dirty_cursor()`; генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot-selection.inc` — verbatim upstream
  `pixman_region_for_coords_normal()` … `mark_selected_region()`;
  генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot-selection-text.inc` — verbatim
  upstream `foreach_selected_normal()` … `selection_to_text()`;
  генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot_selection_text.c` — Android adapter:
  компилирует upstream `extract.c` и извлечённый selection-text walker без
  Wayland/clipboard-зависимостей.
- `terminal-emulator/src/main/foot/src/foot-grid-scroll.inc` — verbatim
  upstream `grid_render_scroll()` … `grid_render_scroll_reverse()`;
  генерируется скриптом.
- `terminal-emulator/src/main/foot/src/foot_grid_scroll.c` — Android adapter:
  `struct buffer` поверх locked Bitmap pixels, validation пиксельного band,
  вызов извлечённых upstream scroll-функций.
- `terminal-emulator/src/main/foot/src/foot_grid_scroll.h` — entry point
  `foot_grid_scroll_apply_rows()`.
- `terminal-emulator/src/main/foot/src/foot_canvas_backend.h` — sink,
  frame/row API Canvas-моста и id/revision sixel-изображений.
- `terminal-emulator/src/main/foot/src/foot_canvas_bridge.c` — synthetic fonts,
  сброс `attrs.clean`, буквальный вызов upstream `render_row()`; selection
  spans удалены, инвалидация sixel-кэша при смене клетки.
- `terminal-emulator/src/main/foot/src/foot_canvas_shim.c` — pixman/fcft
  compatibility backend, glyph cache, Canvas-примитивы, region algebra,
  sixel-поверхности и их id/revision.
- `terminal-emulator/src/main/foot/tools/check-render-cell.sh` — извлечение,
  SHA-256 и режим `--check` для configure/PRE_BUILD.
- `terminal-emulator/src/main/foot/tools/check-render-damage.sh` — то же для
  upstream cursor-damage policy.
- `terminal-emulator/src/main/foot/tools/check-selection-region.sh` — то же для
  upstream selection geometry/damage.
- `terminal-emulator/src/main/foot/tools/check-selection-text.sh` — то же для
  upstream selection-text walker'а.
- `terminal-emulator/src/main/foot/tools/check-grid-scroll.sh` — то же для
  upstream scroll-damage mover'ов.
- `terminal-emulator/src/main/foot/src/headless.c` — append, dirty ownership,
  палитра, события, snapshot, тонкие JNI-обёртки; VT340 sixel-палитра,
  `sixel_reflow()`/`sixel_cell_size_changed()`, доступ к пикселям,
  `headless_scroll_surface_rows()` и upstream selection-text adapter с
  wide-endpoint expansion и Termux-совместимым trim.
- `terminal-emulator/src/main/foot/src/headless.h` — новые entry points,
  включая `headless_sixel_pixels()` и scroll-surface replay.
- `terminal-emulator/src/main/foot/src/jni.c` — JNI setView/commit, damage rows,
  `nativeSixelPixels` и retained-surface scroll через upstream mover.
- `terminal-emulator/src/main/foot/tests/foot_core_test.c` — damage/frame/planner,
  Canvas-bridge, cursor/selection damage, OSC-8 underline, sixel и
  scroll-surface regression tests.
- `terminal-emulator/src/main/foot/CMakeLists.txt` — сборка модуля, теста,
  upstream `extract.c`, `sixel.c`/`hsl.c` и проверки всех пяти извлечённых
  блоков.
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
  frame-заголовок, damage-диапазоны, `OP_IMAGE`, sixel-пиксели через JNI и
  no-selection sentinel `{-1,-1,-1,-1}`.
- `terminal-emulator/src/test/java/com/termux/terminal/AppendStateBufferTest.java` —
  native commit/viewport.
- `terminal-emulator/src/test/java/com/termux/terminal/ScreenBufferTest.java` —
  upstream selection text, включая wide/CJK endpoint и join-full-lines
  compatibility.
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

---

## 18. Фактическая проверка этапа 9 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на:
  - forward-scroll `OP_SCROLL(0, 3, 1, false)` и dirty нижней строки;
  - отсутствие повторного scroll-op на следующем чистом кадре;
  - reverse-scroll на alternate screen (`reverse == true`);
  - fallback для региона, заменённого целиком: `OP_SCROLL` не эмитится,
    все строки региона приходят обычным damage;
  - full damage отменяет pending scroll и не эмитит `OP_SCROLL`.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, SHA-256
  `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
  Извлечённый upstream-блок не менялся.
- Целевые JVM-тесты: `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2,
  `ScrolledUpdateRepaintTest` 14/14 (19/19). `terminal-view` JVM: 42/42.
- Найден и исправлен интеграционный дефект: статическая линковка с
  `jnigraphics` добавляла `DT_NEEDED libjnigraphics.so`, из-за чего
  host-JVM-тесты в Termux падали с
  `Native registration unable to find class ... ProtoLogNative`. Теперь
  `AndroidBitmap_*` резолвится лениво через `dlopen()`/`dlsym()`, а библиотека
  не зависит от Android graphics runtime при загрузке.
- `nativeScrollSurfaceRows()` возвращает `boolean`: если bitmap bridge
  недоступен или `lockPixels` не удался, renderer перерисовывает полосу
  обычными row-командами и просит `TerminalView` назначить полный кадр. Тот же
  fallback используется, если retained Bitmap вообще не удалось выделить.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `011f0e44f0a3b424c86ad57cdce2e7a9cb46623de5bd9dde5de64a84a5f40cc3`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage9-scroll-replay.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` с
  `Java_com_termux_terminal_TerminalNative_nativeScrollSurfaceRows` и
  `...nativeDamageRows`; `DT_NEEDED` содержит только `liblog`, `libm`,
  `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка
  `tmux`/`htop`/OpenCode TUI, sixel/OSC-8, курсора в history и p95 остаются за
  пользователем.

---

## 19. Фактическая проверка этапа 10 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на retained-surface scroll mover:
  - forward scroll двигает целые terminal-строки вверх, reverse — вниз;
  - partial region не трогает строки вне `start..end`;
  - `stride` с padding переносится вместе со строкой;
  - полностью заменённый регион (`lines >= end-start`) не меняет pixels;
  - invalid `end` и пустой регион возвращают `false` для full repaint;
  - reverse scroll по-прежнему помечает верхнюю exposed-строку dirty.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, SHA-256
  `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
- `check-grid-scroll.sh --check`: `foot-grid-scroll.inc: ok`, SHA-256
  `d95c3c4489b15b39ce0c73b5c1369eea3e9aed26817f0a67984ff96d496ccaba`.
  Извлечённый upstream `grid_render_scroll()`/`grid_render_scroll_reverse()`
  не редактировался.
- Целевые JVM-тесты: `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2,
  `ScrolledUpdateRepaintTest` 14/14 (19/19). `terminal-view` JVM: 42/42;
  Java-компиляция обоих модулей успешна.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `e9c53e66983df9661c8ac145e4ccf474f2f34266522c5b42ba509b6a1f7b3f95`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage10-upstream-scroll.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `3c5b9a67ebe4e89b27e168c9772b4c33d5854d1c515e96b0c86dd8ecb044a979`) с
  `Java_com_termux_terminal_TerminalNative_nativeScrollSurfaceRows` и
  `...nativeDamageRows`; `DT_NEEDED` содержит только `liblog`, `libm`,
  `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка scroll
  replay в `tmux`/`htop`/OpenCode TUI, sixel/OSC-8, курсора в history и p95
  остаются за пользователем.

---

## 20. Фактическая проверка этапа 11 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на upstream cursor-damage policy:
  - движение видимого курсора помечает старую и новую строки;
  - скрытие и повторный показ курсора помечают его строку;
  - смена shape перерисовывает строку без изменения позиции;
  - неизменившийся курсор в partial band не переотмечает строку, поскольку
    `row->dirty` снимается только после Java-commit.
- `check-render-cell.sh --check`: `foot-render-cell.inc: ok`, SHA-256
  `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
- `check-render-damage.sh --check`: `foot-render-damage.inc: ok`, SHA-256
  `c8ae73c223872482f2aff03df471b246c7eb0e1e5233a151db08b290ad98f9e2`.
  Извлечённые upstream `dirty_old_cursor()`/`dirty_cursor()` не редактировались.
- `check-grid-scroll.sh --check`: `foot-grid-scroll.inc: ok`, SHA-256
  `d95c3c4489b15b39ce0c73b5c1369eea3e9aed26817f0a67984ff96d496ccaba`.
- Целевые JVM-тесты: `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2,
  `ScrolledUpdateRepaintTest` 14/14 (19/19). `terminal-view` JVM: 42/42;
  Java-компиляция обоих модулей успешна.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `44dfda7fb8360c4226f598fdf4ec2eac4a255ab5b49cf9dfdca1130a9007efcd`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage11-upstream-cursor-damage.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `f4ce598f8cef5bc88bf79283214ebd712351ff000716abd3ac143f315db15909`) с
  `Java_com_termux_terminal_TerminalNative_nativeScrollSurfaceRows` и
  `...nativeDamageRows`; `DT_NEEDED` содержит только `liblog`, `libm`,
  `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка курсора
  (block/underline/beam/hollow, blink, history, selection) остаются за
  пользователем.

---

## 21. Фактическая проверка этапа 12 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на upstream selection-damage policy:
  - пустые клетки внутри диапазона получают selected-биты и сливаются в один
    fill шириной 24 px;
  - снятие selection убирает selected-биты до перерисовки строки;
  - selection в history (`y = -2..-1`) переводится в absolute ring rows и
    помечает обе видимые строки;
  - повторный кадр с теми же координатами не помечает строки;
  - снятие history-selection снова помечает те же строки.
- `check-selection-region.sh --check`: `foot-selection.inc: ok`, SHA-256
  `4bd93815e1bd226030acedeaa8783dca407ea4c28d2208a614b254d75cc9121f`.
  Извлечённые upstream `pixman_region_for_coords*()` и
  `mark_selected_region()` не редактировались.
- `check-render-cell.sh --check`: `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`.
- `check-render-damage.sh --check`: `c8ae73c223872482f2aff03df471b246c7eb0e1e5233a151db08b290ad98f9e2`.
- `check-grid-scroll.sh --check`: `d95c3c4489b15b39ce0c73b5c1369eea3e9aed26817f0a67984ff96d496ccaba`.
- Целевые JVM-тесты: `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2,
  `ScrolledUpdateRepaintTest` 14/14 (19/19). `terminal-view` JVM: 42/42.
- В Java no-selection теперь передаётся как все четыре `-1`; active selection
  определяется по `x1/x2 >= 0`. Это позволяет выделять transcript-row `-1`,
  которая раньше совпадала с sentinel-значением. `TerminalRenderer` уже
  использует `{-1,-1,-1,-1}` через `mDefaultSelectors`, тесты обновлены.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `3d4f53674673e1781771cb9c416f27943ec96f50a01a14eac1ae5a2573b7b0cb`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage12-upstream-selection-damage.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `717ad59b0796546c97d717ef22af3d4471c3708aa6c3042c86b5a8348e388e03`) с
  `nativeRender`, `nativeRenderInto`, `nativeRenderText`,
  `nativeScrollSurfaceRows` и `nativeDamageRows`; `DT_NEEDED` содержит только
  `liblog`, `libm`, `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка selection
  (обычное, history, wide/CJK, снятие), а также cursor/scroll из этапов 10–11
  остаются за пользователем.

---

## 22. Фактическая проверка этапа 13 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на upstream selection text:
  - wide-символ копируется при выборе base- или spacer-колонки;
  - wrapped-строки склеиваются, hard linebreak сохраняется;
  - внутренние пробелы не теряются;
  - печатные trailing spaces в конце hard-line обрезаются;
  - selection только из пустых клеток даёт пустую строку.
- `check-selection-text.sh --check`: `foot-selection-text.inc: ok`, SHA-256
  `87df5b285c1cb8203386ac693d3492134daccf792f2c261c480b7c786a2e8394`.
  Извлечённые upstream `foreach_selected_normal()` … `selection_to_text()` не
  редактировались; `extract.c` собран из pinned submodule без патчей.
- Остальные extraction checks:
  - `check-render-cell.sh --check`: `dcf1ba3b1562d2703e05e153998a4fbc07ad4073504dc324666656a0ace9efe5`;
  - `check-render-damage.sh --check`: `c8ae73c223872482f2aff03df471b246c7eb0e1e5233a151db08b290ad98f9e2`;
  - `check-selection-region.sh --check`: `4bd93815e1bd226030acedeaa8783dca407ea4c28d2208a614b254d75cc9121f`;
  - `check-grid-scroll.sh --check`: `d95c3c4489b15b39ce0c73b5c1369eea3e9aed26817f0a67984ff96d496ccaba`.
- JVM: selection-тесты `ScreenBufferTest` 3/3 (normal, Unicode wide/CJK,
  join-full-lines compatibility); целевые rendering/scroll тесты
  `RenderPayloadTest` 3/3, `AppendStateBufferTest` 2/2,
  `ScrolledUpdateRepaintTest` 14/14 (19/19); `terminal-view` 42/42.
- Полный `terminal-emulator` suite: 246 тестов, 48 failures. Набор падающих
  тестов идентичен stage 12 (проверено diff'ом списков); это прежние
  legacy-семантические расхождения native-порта, не регрессия этапа 13.
  В частности, `testGetWordAtLocation` падал и на stage 12.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `1af191b21ad976034199f960c3e53dcf8b48e65ba3188decc9ebf5adc6c47efc`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage13-upstream-selection-text.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `7f23a3be607279aaf4666cf0302597eaf886882c4c336387cc2eeec4e51eaf6c`) с
  `nativeRender`, `nativeRenderInto`, `nativeRenderText`,
  `nativeScrollSurfaceRows` и `nativeDamageRows`; `DT_NEEDED` содержит только
  `liblog`, `libm`, `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка copy
  (обычный текст, history, wide/CJK, wrapped-строки, URL, вставка в другое
  приложение) остаются за пользователем.

---

## 23. Фактическая проверка этапа 14 (29.10.2026)

- `foot_core_test` (NDK arm64): `foot core smoke test: ok`. Добавлены
  regressions на upstream selection visual policy:
  - trailing пустые клетки внутри диапазона не получают selection fill;
  - внутренние пустые клетки перед непустой клеткой по-прежнему сливаются в
    один fill;
  - endpoint на spacer'е wide-символа подсвечивает весь grapheme;
  - запись в клетку при неизменном selection восстанавливает selected-биты
    при перерисовке повреждённой строки.
- Все extraction checks без изменений:
  `check-render-cell.sh`, `check-render-damage.sh`,
  `check-selection-region.sh`, `check-selection-text.sh`,
  `check-grid-scroll.sh` — `ok`.
- JVM: `RenderPayloadTest` 3/3; selection-тесты `ScreenBufferTest` 3/3
  (normal, Unicode wide/CJK, join-full-lines). `testGetWordAtLocation`
  остаётся известным pre-existing падением, не связанным с этапом 14.
- Целевые rendering/scroll тесты: `ScrolledUpdateRepaintTest` 14/14,
  `ScrollFollowTextTest` 6/6, `DirtyStateTest` 3/3 (23/23);
  `terminal-view` 42/42.
- Полный `terminal-emulator` suite на этом этапе не перезапускался: stage 14
  меняет только native selection visual path, а stage 13 зафиксировал
  48 прежних legacy-падений.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning
  (вне рендерера).
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `e4c605bc2bcfa0f3901229154e6ca9e85b51708ab58d68dde3a87eff355d2fd9`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage14-upstream-selection-visual.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `89fa85c22a10eff68d44d41de8f7ca0e7f5f2c15725c38fad4d963822a0d95c2`) с
  `nativeRender`, `nativeRenderInto`, `nativeRenderText`,
  `nativeScrollSurfaceRows` и `nativeDamageRows`; `DT_NEEDED` содержит только
  `liblog`, `libm`, `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка selection
  (обычное, history, wide/CJK, пустые клетки, снятие) остаются за
  пользователем.

---

## 24. Фактическая проверка этапа 15.4 (29.10.2026)

- Native `foot_render_model_set_view()` получил безопасный соседний путь:
  двигает `grid->view`, помечает dirty только строки, открывшиеся сверху или
  снизу, и накапливает `viewport_shift`. Полный damage остаётся при pending
  output scroll, уже выставленном full damage, `abs(delta) >= rows` и смене
  адресного пространства.
- Добавлены `headless_consume_viewport_shift()`,
  `nativeConsumeViewportShift()` и
  `TerminalEmulator.consumeViewportShift()`; reset/resize сбрасывают сдвиг.
- `TerminalRenderer` перед кадром сдвигает retained Bitmap через upstream
  `grid_render_scroll()`/`grid_render_scroll_reverse()` и затем просит native
  только открывшиеся строки. Для пользовательской прокрутки Java dirty-mirror
  пуст, поэтому `forceDraw` не превращает такой кадр в полный.
- `TerminalView.doScroll()` синхронизирует viewport один раз на жест и
  использует `invalidateForScrollBlit()`; selection, overdrag, отсутствующий
  renderer и частичный clip оставляют обычный полный путь. (Запрет для
  scrollbar drag снят в 15.5.2.) `mPartialScrollFrame` сбрасывается обычным
  `invalidate()`, а частично клипованный carrier не применяет blit и требует
  полного repaint.
- `foot_core_test`: `foot core smoke test: ok`. Добавлены
  `viewport_shift_tests`: движение в историю и к live bottom, переход на
  размер экрана, отказ при pending scroll, reset; проверяется точный набор
  emitted rows через filtered frame.
- JVM: `RenderPayloadTest` 4/4 (добавлен JNI consume-тест),
  `ScrolledUpdateRepaintTest` 15/15, `terminal-view` 42/42 — зелёные.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning.
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `c57fce3764786e9cf6b340c379ee1ac4683b0398b15830bbc6505a044090a635`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage15.4-viewport-blit.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `fca28e88c0bc6952a7c4e67ac625aefe2761108e634c3cb3882c1f41220c21cc`);
  `DT_NEEDED` содержит только `liblog`, `libm`, `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка wheel,
  fling, краёв истории, selection/scrollbar/overdrag и sixel остаются за
  пользователем. До этой проверки этап 15.4 считается реализованным, но не
  подтверждённым на экране.

---

## 25. Фактическая проверка этапа 15.5 (30.10.2026)

- `invalidateScrollbarBand(true)` теперь включается только когда видимые
  строки не dirty, retained Bitmap валиден, нет selection, scrollbar drag,
  overdrag и pending viewport shift.
- Damage rect сохраняется в `mScrollbarBlitOnlyRect`; `onDraw()` применяет
  shortcut только при точном совпадении framework clip. Любая дополнительная
  invalidation (cursor blink, content damage, full invalidate) расширяет clip
  или сбрасывает флаг и возвращает обычный row path.
- `TerminalRenderer` получил флаг `scrollbarBlitOnly`: при валидном surface он
  копирует strip из Bitmap и не вызывает `fillRenderCommands()`; thumb
  по-прежнему рисуется только на View canvas, поэтому в Bitmap остаётся
  чистый текст под старым и новым положением.
- Если surface/top/offset/background не совпали, renderer сам откатывается к
  обычной отрисовке строк в этом clip; если bitmap вообще недоступен, остаётся
  прямой Canvas-путь.
- Общий fallback для `commandCount == 0`: если native не сгенерировал ни одной
  строки, но retained surface валиден, renderer копирует запрошенную полосу из
  Bitmap. Это закрывает любые overlay-only partial invalidation, не только
  scrollbar fast path.
- Partial frame с pending viewport shift теперь повышается до полного
  repaint: сдвинутые пиксели вне clip иначе не дошли бы до экрана. Это
  закрывает гонку «пользовательский scroll + scrollbar/output invalidation
  до следующего кадра».
- `TerminalEmulator.setViewport()` получил fast path: если `mTopRow` уже равен
  `mNativeViewTop`, JNI `nativeSetView` не вызывается. `onDraw()` вызывает
  sync каждый кадр, поэтому это убирает лишний JNI round-trip в устойчивом
  состоянии.
- JVM: `RenderPayloadTest` 4/4, `ScrolledUpdateRepaintTest` 15/15,
  `terminal-view` 48/48 (включая новый `TerminalViewScrollbarBlitTest`).
  Native `foot_core_test`: `foot core smoke test: ok`.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning.
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `0597c39fe749e77e7aa4978a49ea74540cd463eea78587728c9779fca9a09e65`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage15.5-scrollbar-blit.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` (SHA-256
  `fca28e88c0bc6952a7c4e67ac625aefe2761108e634c3cb3882c1f41220c21cc`);
  `DT_NEEDED` содержит только `liblog`, `libm`, `libdl`, `libc`.
- Устройство не перезапускалось: установка APK и визуальная проверка thumb,
  wheel/fling, cursor/selection, overdrag и sixel остаются за пользователем.

---

## 26. Фактическая проверка этапа 15.5.1 (30.10.2026)

- `TerminalRenderer` больше не сканирует Java dirty-mirror перед native-вызовом
  и не включает `forceAllRows` из-за пустой полосы. Для частичного кадра
  решение о строках принимается по frame header/native damage; чистый band
  копируется из retained Bitmap.
- Legacy native без frame header распознаётся по первому opcode: команды один
  раз перезапрашиваются с `forceAllRows=true`. Современный native этот
  дополнительный вызов не делает.
- На full-clip кадре `TerminalView` вызывает
  `TerminalBuffer.clearJavaDirtyState()`: Java-зеркало очищается, но
  `nativeCommitAllRows()` не повторяется, потому что renderer уже закоммитил
  все нарисованные строки через `commitRenderRows()`. Это также не маскирует
  damage, если кадр завершился ошибкой до полной отрисовки.
- `TerminalRenderer` не вызывает `commitRenderRows()` при
  `mPaintedRowCount == 0`, убирая пустой JNI round-trip.
- `ScrolledUpdateRepaintTest` переведён на native-authoritative модель;
  `RenderPayloadTest` проверяет, что чистый кадр содержит заголовок, но не
  содержит `OP_ROW_BEGIN`, а `forceAllRows` снова выдаёт все строки.
- JVM: `RenderPayloadTest` 5/5, `ScrolledUpdateRepaintTest` 15/15,
  `DirtyStateTest` 3/3; `terminal-view` 48/48 — зелёные.
- Native `foot_core_test`: `foot core smoke test: ok`; native-код не менялся,
  SHA-256 `libtermux_terminal.so` остался
  `fca28e88c0bc6952a7c4e67ac625aefe2761108e634c3cb3882c1f41220c21cc`.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning.
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `56111b708d328b9b024c16cf9439e0636c0d8f9de16806de9aa5fb3c198583d5`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage15.5.1-native-damage.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` с прежним SHA-256;
  `DT_NEEDED` не менялся (только `liblog`, `libm`, `libdl`, `libc`).
- Устройство не перезапускалось: установка APK и визуальная проверка
  streaming/TUI, cursor blink, selection, wheel/fling, scrollbar и sixel
  остаются за пользователем. До этой проверки этап считается реализованным,
  но не подтверждённым на экране.

---

## 27. Фактическая проверка этапа 15.5.2 (30.10.2026)

- Исправлено главное условие viewport blit: full-view `mPartialScrollFrame`
  carrier намеренно выставляет `isFullClip=false`, из-за чего общая защита
  «pending viewport shift на partial frame» понижала его до полного row
  repaint. Теперь `onDraw()` различает настоящий partial clip и full-view
  carrier: только первый требует полного кадра, carrier покрывает всю полосу
  сдвига retained Bitmap и может потребить shift.
- `invalidateForScrollBlit()` проверяет `mPixelsValid`, selection, overdrag и
  наличие renderer. Если retained pixels невалидны, вызов уходит в обычный
  `invalidate()`: иначе partial carrier оставлял бы `mPixelsValid=false`
  навсегда и каждый следующий scroll снова рисовал бы все строки.
- Scrollbar drag снова использует viewport blit. Thumb рисуется только на
  View canvas после полного копирования retained Bitmap, поэтому старый thumb
  стирается, а новый рисуется в `drawScrollbar()`. Selection и overdrag
  по-прежнему требуют полного пути.
- `doScroll()` для обычного transcript применяет весь wheel/PageUp batch одной
  операцией `clampScrollTop()` вместо цикла по строкам; mouse tracking и
  alternate buffer сохраняют событие на строку. Это убирает O(amount) проверок
  режима и повторных clamp'ов на больших wheel-событиях.
- Policy-тесты: `TerminalViewScrollbarBlitTest` расширен проверками
  carrier/pending-shift и допустимости viewport reuse; добавлен
  `TerminalViewScrollTest` на batch-clamp и края истории.
- JVM: `terminal-view` 61/61, targeted
  `RenderPayloadTest` 5/5, `ScrolledUpdateRepaintTest` 15/15,
  `DirtyStateTest` 3/3 — зелёные.
- Native `foot_core_test`: `foot core smoke test: ok`; native-код не менялся,
  SHA-256 `libtermux_terminal.so` остался
  `fca28e88c0bc6952a7c4e67ac625aefe2761108e634c3cb3882c1f41220c21cc`.
- `:app:assembleRelease`: успешно, 0 errors, 3 известных PendingIntent warning.
- APK: `versionCode=118022`, `versionName=0.118.0+enhanced.22`, подпись
  `CN=Winlator`, SHA-256
  `4fada09b469583f2e13e52cc5185e9c2e2ec808b5e54c9f2b4a362056f908e55`.
- Готовый файл:
  `/storage/emulated/0/Download/Termux-Enhanced-0.118.0+enhanced.22-stage15.5.2-scroll-carrier.apk`.
- В APK входит `lib/arm64-v8a/libtermux_terminal.so` с прежним SHA-256;
  `DT_NEEDED` не менялся (только `liblog`, `libm`, `libdl`, `libc`).
- Устройство не перезапускалось: установка APK и визуальная проверка
  wheel/finger scroll, fling, scrollbar drag, краёв истории, selection и
  alternate buffer остаются за пользователем. До этой проверки этап считается
  реализованным, но не подтверждённым на экране.
