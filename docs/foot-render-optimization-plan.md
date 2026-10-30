# План оптимизации рендеринга foot на Android

**Ветка:** `feature/foot-terminal-core`
**Версия:** `0.118.0+enhanced.22` (не меняется)
**Исходный foot:** `152487e494993ad7fb5633a27c7d7ad0f8810ea5`
**Текущая база:** этапы 1–14, коммит `e74efc11`

## 1. Цель и границы

Цель — убрать оставшуюся Android-специфичную работу из горячего пути, не меняя
семантику upstream foot. Приоритет: сначала корректность и устранение лишних
полных кадров, затем отсечение невидимой/неповреждённой работы, и только потом
агрессивные оптимизации вроде переноса пикселей при пользовательской прокрутке.

Ограничения:

- upstream `render_cell()`/`render_row()`, `sixel.c`, `hsl.c`, scroll/cursor/
  selection damage и selection text остаются источником истины;
- Android владеет только frame/damage/commit, retained Bitmap, Canvas-shim,
  шрифтами, JNI и lifecycle;
- версия, ABI и формат command-потока не ломаются без необходимости;
- оптимизация не должна маскировать расхождение native/Java damage: при
  сомнении выбирается полный кадр, а не «почти правильный» частичный;
- никакого tracing в этой ветке.

## 2. Что уже известно из анализа

Главные источники лишней работы:

1. `TerminalView.repaintAfterUpdate()` считает смену anchor полным повреждением.
   На live bottom (`mTopRow == 0`) каждый вывод со скроллом меняет
   `externalToInternalRow(0)`, поэтому `OP_SCROLL` фактически не используется,
   хотя native уже прислал корректный scroll damage.
2. Native `render_frame()` вызывает `render_canvas_row()` для каждой строки
   запрошенного диапазона, даже если Java затем пропускает чистые строки.
3. `doScroll()` вызывает `syncNativeViewport()` и `setAutoScrollDisabled()` на
   каждую строку колеса/жеста, хотя важен только конечный `mTopRow`.
4. Пользовательская прокрутка делает полный `invalidate()`, затем native
   помечает `full_damage`; retained Bitmap не сдвигается, хотя это мог бы быть
   обычный blit плюс отрисовка открывшихся строк.
5. Полоса scrollbar'а перерисовывает текст под собой, хотя retained surface
   валиден; нужен blit-only путь.
6. `nativeDestroy()` не вызывается из lifecycle, поэтому закрытые сессии
   удерживают native-буферы. Это отдельная проблема lifecycle, не горячего пути.

Отдельно подтверждено: per-handle JNI mutex не является значимым источником
задержки в steady-state. Все append/render/commit идут на main thread; cold start
невидим пользователю; неконкурентный mutex стоит пренебрежимо мало. Снимать его
можно только вместе с явной передачей ownership, а не «просто так».

## 3. Порядок этапов

### Этап 15.0 — защита command-буфера (P0, корректность)

**Проблема:** `render_model.c` резервирует `count + 2`, но пишет три int в
ветке null-row; `OP_FRAME_END` пишется без предварительного reserve. Оба случая —
latent out-of-bounds write, который может проявиться на границе capacity.

**Изменения:**

- резервировать `count + 3` перед `OP_ROW_BEGIN`/row/`OP_ROW_END`;
- одним reserve покрыть `OP_FRAME_END` + `OP_END` + длину текста + текст;
- сохранить рост буфера и возврат `-required` из JNI без изменений;
- добавить native-тест на поток команд у границы capacity (или стресс-тест
  малым буфером через тестовый hook).

**Критерий:** ASan/обычный прогон native-тестов не находит записи за границей;
`RenderPayloadTest` и scroll/selection-тесты проходят без изменения формата.

### Этап 15.1 — live-bottom без полного кадра (P0, latency)

**Проблема:** `anchorChanged` на `mTopRow == 0` вызывает `invalidate()` и
отменяет scroll replay.

**Изменения:**

- full invalidate по anchor — только если `mTopRow != 0`;
- на live bottom полагаться на native damage ranges + `OP_SCROLL`;
- full invalidate сохранить для `screen.isAllDirty()`, selection/scrollbar drag,
  смены буфера/размера и явного native full damage;
- обновить shadow-canvas regression test: при live-bottom newline retained
  surface сдвигается, Java рисует только открывшиеся/повреждённые строки.

**Критерий:** после `\r\n` на дне не растёт число full frames; canvas совпадает
с buffer; `mTopRow == 0`; scroll op доходит до renderer.

**Риск:** если native и Java dirty-set разойдутся, на экране останется старая
строка. Поэтому этап 15.2 (native authority) идёт сразу за ним, а при
несовпадении viewport остаётся fallback на полный кадр.

### Этап 15.2 — native пропускает чистые строки (P1, CPU/GC)

**Проблема:** native собирает `render_row()` для всех строк, Java отбрасывает
чистые. Это лишние shaping/command-записи и давление на command-буфер.

**Изменения:**

- добавить в render API явный `forceAllRows` (surface reset, отсутствие
  retained Bitmap, первый кадр, malformed/mismatched header);
- в обычном частичном кадре native эмитит только строки, которые считает
  damaged, плюс null-row (base fill) и sixel, если они реально повреждены;
- Java передаёт `forceAllRows = forceDraw`; при `forceAllRows` native обязан
  эмитить все строки диапазона и все видимые sixel;
- сохранить legacy `nativeRender` как «всегда все строки» для старого ABI и
  тестов; новый путь — `nativeRenderInto(..., forceAllRows, ...)`;
- добавить native-тест: после commit чистый кадр не содержит `OP_ROW_BEGIN`;
  с `forceAllRows` содержит все строки; damaged-строка присутствует.

**Критерий:** число `OP_ROW_BEGIN` в чистом кадре равно 0; при `forceDraw`
равно числу строк; canvas-тесты не регрессируют; формат `OP_*` не меняется.

**Риск:** parser `TerminalRenderer` сейчас не умеет структурно пропускать
payload неотрисованной строки. После этапа native не должен присылать payload
для строк, которые Java не рисует; для страховки добавить структурный skip
payload в parser (отдельным маленьким изменением) и тест на смешанный
damaged/clean кадр.

### Этап 15.3 — один viewport sync на жест (P1, micro)

**Проблема:** `doScroll()` синхронизирует native viewport и auto-scroll на
каждую строку.

**Изменения:**

- в цикле менять только `mTopRow`;
- после цикла один раз вызвать `syncNativeViewport()`,
  `setAutoScrollDisabled(mTopRow != 0)`, `awakenScrollBars()` и `invalidate()`;
- не считать жест «scrolled», если `mTopRow` не изменился (край истории).

**Критерий:** wheel/page scroll даёт один JNI viewport sync; поведение на
краях не меняется.

### Этап 15.4 — incremental viewport scroll blitting (P2)

**Проблема:** пользовательская прокрутка делает полный repaint, хотя retained
Bitmap уже содержит соседний viewport.

**Изменения:**

- при смене `mTopRow` на `delta` строк пытаться сдвинуть retained surface на
  `delta * lineHeight` пикселей;
- отрисовывать только открывшиеся строки и overlays (cursor, selection,
  scrollbar, sixel);
- запрещать blit, если: selection активна, scrollbar drag, overdrag != 0,
  surface invalid, `mSurfaceNeedsFull`, есть pending scroll replay, изменился
  размер/шрифт/фон, native сообщил full damage;
- иначе — существующий полный путь;
- добавить тесты на cursor, selection, sixel, scrollbar, overdrag и возврат
  к полному кадру при небезопасном состоянии.

**Критерий:** прокрутка на одну строку рисует O(1) строк, а не весь экран;
все fallback-сценарии дают корректный canvas.

**Риск:** scrollbar и sixel живут в разных системах координат; начинать только
после стабилизации 15.1–15.3 и с обязательным pixel-oracle тестом.

**Реализация (29.10.2026):**

- `foot_render_model_set_view()` при безопасном соседнем переходе двигает
  `grid->view`, помечает dirty только открывшиеся строки и накапливает
  `viewport_shift`; при pending scroll, full damage, `abs(delta) >= rows` или
  смене адресного пространства остаётся полный путь;
- `headless_consume_viewport_shift()` / `nativeConsumeViewportShift()` /
  `TerminalEmulator.consumeViewportShift()` передают сдвиг в Java; reset,
  resize и полный damage его сбрасывают;
- `TerminalRenderer` вызывает upstream scroll mover для retained Bitmap
  (`shift < 0` → `grid_render_scroll_reverse()`), затем просит native только
  открывшиеся строки; Java dirty-mirror для пользовательской прокрутки не
  обновляется, поэтому `forceDraw` не повышается до полного кадра;
- `TerminalView.doScroll()` делает один `syncNativeViewport()` на жест и
  использует `invalidateForScrollBlit()`; при selection, overdrag, отсутствии
  renderer или частичном clip — обычный полный путь (scrollbar drag с 15.5.2
  снова использует blit);
- `mPartialScrollFrame` сбрасывается любым обычным `invalidate()`, чтобы
  scroll-blit carrier не понижал настоящий полный кадр до partial.

**Проверка:** native `viewport_shift_tests` (история, live bottom, переход
размером с экран, pending output scroll, reset), `RenderPayloadTest` (JNI
consume), `ScrolledUpdateRepaintTest` и `terminal-view` — зелёные. Визуальная
проверка wheel/fling на устройстве остаётся за пользователем.

### Этап 15.5 — scrollbar-band blit-only (P2)

**Проблема:** `invalidateScrollbarBand()` помечает строки dirty и заставляет
перерисовывать текст, хотя достаточно вернуть валидные пиксели из retained
Bitmap и нарисовать thumb.

**Изменения:**

- если retained surface валиден и band не пересекает небезопасные overlays —
  blit только band из Bitmap;
- иначе — текущий путь с markRows;
- тест: thumb move не увеличивает число `drawRow()`.

**Реализация (30.10.2026):**

- `invalidateScrollbarBand(true)` включается только когда видимые строки не
  dirty, bitmap валиден, нет selection/scrollbar drag/overdrag и pending
  viewport shift;
- сохраняется точный damage rect; `onDraw()` применяет shortcut только если
  framework clip совпал с ним. Любая дополнительная invalidation (cursor,
  content, full) расширяет clip и автоматически возвращает обычный row path;
- `TerminalRenderer` получил `scrollbarBlitOnly`: при валидном retained
  surface он копирует strip из Bitmap и не запрашивает native row commands.
  Если surface/offset/top/background не совпали, renderer сам откатывается к
  обычной отрисовке строк в этом clip;
- thumb по-прежнему рисуется только на View canvas, поэтому Bitmap содержит
  чистый текст под старым и новым положением;
- добавлена защита: pending viewport shift на partial frame повышает кадр до
  полного repaint, иначе сдвинутые пиксели вне clip остались бы старыми;
- `TerminalEmulator.setViewport()` пропускает JNI, если native viewport уже
  совпадает с `mTopRow`; это убирает лишний вызов из `onDraw()` каждого кадра;
- если native на partial frame не вернул ни одной строки, renderer сам копирует
  запрошенную полосу из валидного Bitmap — общий fallback для overlay-only
  invalidation.

**Проверка:** `terminal-view` 48/48 (включая policy-тесты shortcut'а),
targeted JVM и native smoke — зелёные.
Отдельного shadow-canvas oracle для `drawRow()` пока нет; визуальная проверка
thumb на устройстве остаётся за пользователем.

### Этап 15.5.1 — native-authoritative row decisions (P1, hot path)

**Проблема:** после перехода на native damage ranges `TerminalRenderer` всё ещё
сканировал Java dirty-mirror и при пустой полосе ставил `forceAllRows=true`.
Это могло регенерировать весь clip, хотя native уже знал точный damage; на
полном кадре дополнительно вызывался `nativeCommitAllRows()` после того, как
renderer уже закоммитил каждую нарисованную строку.

**Изменения:**

- renderer больше не повышает `forceDraw` из пустого Java dirty-mirror:
  источником истины для частичного кадра остаётся frame header/native damage;
- legacy native без frame header определяется по первому opcode и один раз
  перезапрашивает весь band с `forceAllRows=true`; современный путь не платит
  за этот fallback;
- `TerminalView` на full-clip кадре очищает только Java-зеркало через
  `clearJavaDirtyState()`; native damage уже снят `commitRenderRows()`;
- `commitRenderRows()` не вызывается, если renderer не нарисовал ни одной
  строки;
- JVM-тест фиксирует, что чистый native-кадр содержит header, но не содержит
  `OP_ROW_BEGIN`, а `forceAllRows` снова выдаёт все строки.

**Критерий:** partial frame с пустым Java-зеркалом не генерирует все строки;
чистый band копируется из retained Bitmap; full frame не делает повторный
`nativeCommitAllRows`; canvas-поведение не меняется.

**Реализация (30.10.2026):**

- `TerminalRenderer` удалил `anyDirty`-скан и fallback `forceDraw = !anyDirty`;
  при отсутствии frame header команды перезапрашиваются с полным band;
- `TerminalBuffer.clearJavaDirtyState()` отделяет очистку Java-зеркала от
  `nativeCommitAllRows()`;
- `TerminalRenderer` пропускает JNI-commit при `mPaintedRowCount == 0`;
- `ScrolledUpdateRepaintTest` обновлён под native-authoritative модель;
  `RenderPayloadTest` получил проверку clean frame / `forceAllRows`.

**Проверка:** `RenderPayloadTest` 5/5, `ScrolledUpdateRepaintTest` 15/15,
`DirtyStateTest` 3/3, `terminal-view` 48/48, native
`foot_core_test: foot core smoke test: ok`.

### Этап 15.5.2 — scroll carrier, scrollbar drag и batch clamp (P1, hot path)

**Проблема:** full-view `mPartialScrollFrame` carrier намеренно выставлял
`isFullClip=false`, после чего общая защита «pending viewport shift на partial
frame» понижала его до полного repaint. В результате viewport blit из 15.4 не
доходил до renderer, и каждая прокрутка снова рисовала весь экран. Отдельно
scrollbar drag и обычная прокрутка всё ещё делали лишнюю работу.

**Изменения:**

- `onDraw()` различает настоящий partial clip и full-view scroll carrier:
  pending shift повышает до полного кадра только первый; carrier покрывает всю
  полосу, которую двигает retained surface, и может потребить shift;
- `invalidateForScrollBlit()` проверяет `mPixelsValid`, selection, overdrag и
  наличие renderer; при невалидном surface уходит в обычный `invalidate()`,
  чтобы partial carrier не оставил `mPixelsValid=false` навсегда;
- scrollbar drag использует viewport blit: thumb — overlay на View canvas,
  поэтому полный blit восстанавливает текст под старым thumb, а
  `drawScrollbar()` рисует новый;
- `doScroll()` для normal transcript применяет весь wheel/PageUp batch одной
  арифметической операцией `clampScrollTop()`; mouse tracking и alternate
  buffer по-прежнему получают событие на строку;
- policy-тесты закрепляют carrier/pending-shift, допустимость blit и кламп
  границ истории.

**Критерий:** wheel/finger scroll на устройстве не делает полный row repaint
при валидном surface; scrollbar drag не сбрасывает retained pixels; на краях
истории и в alternate buffer поведение не меняется.

**Реализация (30.10.2026):**

- `TerminalView.pendingViewportShiftRequiresFullRepaint()` и
  `canReuseViewportSurface()` вынесены в тестируемую policy;
- `TerminalView.clampScrollTop()` заменил per-row clamp для normal transcript;
- добавлен `TerminalViewScrollTest` (5 проверок) и расширен
  `TerminalViewScrollbarBlitTest`.

**Проверка:** `terminal-view` 61/61, targeted
`RenderPayloadTest`/`ScrolledUpdateRepaintTest`/`DirtyStateTest` 23/23, native
`foot_core_test: foot core smoke test: ok`.

### Этап 15.6 — lifecycle `nativeDestroy` (P1, отдельно)

**Проблема:** `TerminalEmulator.mNativeHandle` не освобождается при закрытии
сессии.

**Изменения:**

- определить точку destroy после последнего JNI-вызова, с сохранением
  transcript/scrollback до закрытия UI;
- запретить destroy при активном render/append и при возможном повторном
  обращении из pager;
- добавить idempotent guard и тест на двойной cleanup.

**Критерий:** закрытие N сессий не растёт RSS/native allocations; transcript
сохраняется до момента, когда он больше не нужен.

### Этап 15.7 — измерения и закрепление

- добавить debug-счётчики: full/partial frames, emitted rows, skipped rows,
  scroll ops, blit ops, JNI render time;
- прогнать p95 frame time на: streaming output, TUI sparse update, wheel scroll,
  fling, selection drag, sixel;
- зафиксировать baseline и результаты в `docs/foot-renderer-migration.md`;
- APK собирать только после зелёных native/JVM тестов; установку и перезапуск
  выполняет пользователь.

## 4. Матрица проверок

| Сценарий | Native | JVM shadow canvas | Ручная проверка |
|---|---|---|---|
| streaming на дне | scroll op + damage | full frames не растут | плавность, нет stale row |
| sparse TUI (top+bottom) | только damaged rows | 2 строки, не 48 | htop/top |
| scrolled-up output | 0 видимых damage | только scrollbar band | текст не прыгает |
| wheel/page scroll | один viewport sync | blit/fallback | края истории |
| selection drag | selection damage | overlay корректен | выделение и текст |
| sixel | OP_IMAGE при damage | pixels совпадают | картинка не мигает |
| resize/font/palette | full damage | full frame | без артефактов |
| закрытие сессии | destroy | cleanup | нет роста памяти |

## 5. Rollback

Каждый этап независим:

- 15.0 — чистый correctness fix, откат не нужен;
- 15.1 — вернуть `anchorChanged` в условие full invalidate;
- 15.2 — передавать `forceAllRows = true` всегда (старое поведение) или
  вернуть вызов legacy `nativeRender`;
- 15.3 — вернуть sync в цикл;
- 15.4/15.5 — отключить blit флагом и оставить полный/строчный путь;
- 15.5.1 — вернуть `forceDraw = !anyDirty` и `nativeCommitAllRows()` на full
  clip, если понадобится совместимость со старым native;
- 15.5.2 — вернуть scrollbar drag на полный `invalidate()`, убрать carrier
  exception из pending-shift guard и per-row clamp, если blit окажется
  небезопасным;
- 15.6 — не вызывать destroy до отдельного решения.

## 6. Текущий статус

- План создан.
- Реализованы и покрыты native/JVM-тестами: 15.0, 15.1, 15.2, 15.3, 15.4,
  15.5, 15.5.1, 15.5.2.
- 15.4, 15.5, 15.5.1 и 15.5.2 собраны в APK; последняя сборка —
  `Termux-Enhanced-0.118.0+enhanced.22-stage15.5.2-scroll-carrier.apk`.
  Установка и визуальная проверка wheel/fling, cursor/selection и scrollbar —
  за пользователем.
- 15.6 (lifecycle `nativeDestroy`) и 15.7 (счётчики/p95) ещё не начаты.
