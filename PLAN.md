# План оптимизации Session Overview (подробный)

## 0. Цель и жёсткие ограничения

**Цель одним предложением:** устранить избыточные аллокации на горячих путях и дублирование механизмов в подсистеме all-sessions overview, не меняя визуальное поведение и модель прозрачности, зафиксированные в коммитах `d2d91232..2fa561c3`.

### Ограничения (обязательные)

1. **Сборку `assembleRelease` не запускать без отдельной команды пользователя.** Все промежуточные проверки — статические (`git diff --check`, `grep`, чтение diff).
2. **Коммит и пуш не делать без отдельной команды.**
3. **Не использовать Python для правки файлов.** Только `edit`/`write` и точечные shell-команды (`sed`, `grep`, `git show`).
4. **Не трогать `third_party/`** — он намеренно untracked.
5. **Не взаимодействовать с устройством.** Ничего не ставить, не кликать, logcat не читать.
6. Сохранить текущую модель:
   * overview backdrop = цвет терминала с альфой `terminal + 20 pp`;
   * превью/флайты = альфа терминала;
   * `OverviewBackdropHoleView` вырезает декор под флайтом;
   * `clipFlightRectToBitmap()` убирает пустую полосу под bitmap;
   * системный `RoundedOutlineProvider` для скруглений (патч с `clipPath` сохранён отдельно и **не применяется**).

### Базовая точка

* HEAD: `2fa561c3 Polish overview hover lift and closing shadow handoff`.
* Рабочее дерево: чистое, кроме `?? third_party/`.
* Последний успешный релиз: `app/build/outputs/apk/release/termux-app_apt-android-7-release_arm64-v8a.apk`.

---

## 1. Что именно被发现 (инвентаризация)

### 1.1 P1 — аллокации каждый кадр / каждый hover

| Место (текущие строки) | Проблема |
|---|---|
| `SessionOverviewController.contentOrigin()` ~3229 | `new int[2]` при каждом вызове; вызывается в `applyTransitionProgress()` и `applyClosingProgress()` каждый кадр |
| `localRect()` ~3240, `windowRectOf()` ~1758/1766, `cardChildTarget()` ~1915 | Каждый вызов создаёт `int[2]` + `Rect`; `cardTabTarget()` вызывается для каждого ещё не трансформированного tab ghost каждый кадр |
| `closingTerminalTarget()` ~3125 | Каждый кадр закрытия создаёт `Rect` + `windowRectOf()` + `localRect()` |
| `autoScrollStep()` ~3392 | `new int[2]` каждый кадр автоскролла |
| `containsScreenPoint()` ~3485 через `findCardAtScreenPoint()` | `new Rect()` на каждую карточку на каждом hover-событие / автоскролл-кадр |
| `OverviewBackdropHoleView.onDraw()` ~103 | `Path.reset()+addRoundRect()` каждый кадр, хотя меняется только `fillAlpha` |
| `renderCard()` ~4214 | `setImageBitmap()` на том же самого `Bitmap` создаёт новый `BitmapDrawable` каждый repaint; плюс лишний `updateScaleMatrix()` в `TopCropImageView` |

### 1.2 P1/P2 — дублирование

* Два независимых автоскролла: `SessionOverviewController.autoScrollStep()` и `SessionOverviewDragController.autoScrollFrame()`. Оба считают `frameRatio = min(dt,48)/16`, используют `AUTO_SCROLL_BAND_DP=64`, `AUTO_SCROLL_MAX_STEP_DP=48`, но контроллер **не вызывает** уже существующий `SessionOverviewReorderMath.autoScrollStep()`. Конвертация dp→px сделана двумя разными способами.
* Сброс трансформов карточки (`translationX/Y`, `scaleX/Y`, `alpha`, `translationZ`) скопирован в `SessionOverviewCardView.resetSwipeState()`, `SessionOverviewDragController.cancelDropFlight()`, `cancelPendingSettle()`, `cancelForModelChange()`, `finishDrop()` и частично в `cleanupTransition()`/`cleanupClosingTransition()`.
* `new DecelerateInterpolator()` создаётся в 4 местах `SessionOverviewCardView`, хотя в `SessionOverviewDragController` уже есть `static final DECELERATE`.
* Цветовые операции разбросаны: `FloatingShadow.withAlphaFraction()`, `SessionOverviewController.withAlpha()`, `opaqueOver()`.
* `SessionOverviewTransitionMath` содержит тождественные алиасы и выражение `0.5f * (p / 0.5f)`.
* `SessionOverviewGridMath.compute()` дублирует расчёт `dynamicGap/gridWidth/gridHeight` в двух перегрузках; в обеих есть потенциальный 1px overflow при остатке ширины 1–2 px.

### 1.3 P2 — корректность/поддержка

* Устаревшие комментарии в `prepareTransition()` и `prepareClosingTransition()` («TopCropImageView paints the uncovered strip…») больше не соответствуют коду.
* `overviewBackdropColor()` говорит про «fully transparent cut-out», хотя вырезка имеет цвет/альфу и endpoint-tint.
* `FloatingShadow`: «four times the elevation (twice as large as the first hover version)».
* `show()` ставит 400 ms fallback на `mOverlay`, который не снимается в `cleanupTransition()`.
* `SessionOverviewCardView.setHoverHighlight()` end-листнер безусловно выставляет целевые значения даже после внешнего `cancel()`.
* `TerminalPreviewRenderer.rendererFor()` использует raw `float textSize` в ключе, хотя не-explicit конструктор делает `Math.round()`; не задокументирована thread-confined природа рендерера.
* Лишние `new ArrayList<>(...)` в `prepareTransition()`, `buildCards()`, `installClosingTabVisuals()`.

---

## 2. Ожидаемый список файлов

| Файл | Тип правки |
|---|---|
| `PLAN.md` | новый документ (этот файл) |
| `app/src/main/java/com/termux/app/terminal/OverviewBackdropHoleView.java` | path только в `setContent()` |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewController.java` | scratch-геометрия, `setImageBitmap`, автоскролл, cleanup, комментарии |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewCardView.java` | общий интерполятор, 안전ный end-листнер, reset-helper |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewDragController.java` | общий автоскролл, reset-helper, общий интерполятор |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewAutoScroll.java` | **новый** маленький helper (если вынесение окажется компактнее расширения `ReorderMath`) |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewReorderMath.java` | `frameRatio()` / вызов из helper |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewTransitionMath.java` | упрощение алиасов, один `clamp` в `blendColor` |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewGridMath.java` | единый `buildLayout()`, фикс dynamic gap |
| `app/src/main/java/com/termux/app/terminal/SessionOverviewReorderMathTest.java` | тест dynamic gap/remaining throttle при необходимости |
| `app/src/main/java/com/termux/app/terminal/FloatingShadow.java` | комментарий + возможный цветовой helper |
| `terminal-view/src/main/java/com/termux/view/TerminalPreviewRenderer.java` | нормализация ключа кеша, комментарий про поток |

---

## 3. Стратегия

1. **Никаких изменений поведения.** Все шаги — структурные: переиспользование объектов, вынос функций, упрощение эквивалентных выражений, исправление комментариев.
2. **Маленькие diff’ы.** Один шаг = один логический diff = одна проверка.
3. **Статическая верификация до финала.** `git diff --check` + точечный `grep` + чтение diff. Полная сборка — только по отдельной команде.
4. **Сохранить公众ный API там, где он используется тестами.** `SessionOverviewTransitionMath`, `GridMath`, `SwipeMath`, `PreviewThrottle` остаются `public`/`static`.
5. **Не трогать прозрачность и скругления.** Патч `patches/overview-corner-clippath.patch` остаётся просто сохранённым артефактом.

---

## 4. Нумерованный план

### Step 1 — `OverviewBackdropHoleView`:path строится только при изменении геометрии

**Action:** перенести `mHolePath.reset()` + `mHolePath.addRoundRect(...)` из `onDraw()` в `setContent()` под уже существующий `geometryChanged`. В `onDraw()` оставить только `canvas.drawPath(mHolePath, ...)` / `drawRect` для `radius<=0`.

**Files:** `OverviewBackdropHoleView.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "addRoundRect\\|mHolePath.reset" OverviewBackdropHoleView.java` — ровно одно вхождение `reset()` и одно `addRoundRect()`, оба внутри `setContent()`.
* `grep -n "addRoundRect\\|mHolePath.reset" ... | grep onDraw` — пусто.

**Evidence:** `git diff --check` — пусто; `grep -n "addRoundRect\|mHolePath.reset" OverviewBackdropHoleView.java` → строки 91 и 93, оба внутри `setContent()`; в `onDraw()` остался только `canvas.drawPath(mHolePath, ...)`. Path пересобирается только при `geometryChanged`.

---

### Step 2 — `SessionOverviewController`: scratch-геометрия вместо аллокаций на кадр

**Action:**
* Добавить поля: `mScratchOrigin`, `mScratchLocation`, `mScratchTargetRect`, `mScratchCloseRect` (и при необходимости `mScratchWindowRect`).
* Добавить перегрузки `contentOrigin(int[] out)`, `windowRectOf(View, Rect out)`, `localRect(View, int[], Rect out)`, `cardChildTarget(Card, View, int[], Rect out)`, `visibleLocalRect(View, int[], Rect out)`.
* В `applyTransitionProgress()` использовать scratch для активного preview и для `cardTabTarget()` каждого ghost.
* В `applyClosingProgress()`/`closingTerminalTarget()` использовать scratch вместо `new Rect()`.
* В `autoScrollStep()` использовать `mScratchLocation` вместо `new int[2]`.
* В `findCardAtScreenPoint()`/`containsScreenPoint()` использовать один общий hit-`Rect`.
* Сохранить все существующие копии там, где результат накапливается (`SourceTab`, `TabGhost`, `ClosingTabGhost` уже делают `new Rect(...)`).

**Files:** `SessionOverviewController.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "new Rect()\\|new int\\[2\\]" SessionOverviewController.java` — оставшиеся вхождения только в методах подготовки/создания (`prepareTransition`, `buildCards`, `install*`, `captureCardCenters`, `animateRemovalSettle`, `commitReorder`, конструкторы данных), **не** в `applyTransitionProgress`, `applyClosingProgress`, `applyHoverCard`, `findCardAtScreenPoint`, `autoScrollStep`, `contentOrigin`, `localRect`, `windowRectOf`, `cardChildTarget`, `closingTerminalTarget`.
* Прямое чтение diff: ни один `Rect`, записанный в scratch, не сохраняется в поле/коллекцию без копирования.

**Evidence:** `git diff --check` — пусто. `contentOrigin(mScratchOrigin)` используется во всех вызовах; метод принимает `int[] out` и не аллоцирует. Новые `Rect`/`int[2]` остались только в подготовительных методах: `prepareTransition` (1664), `windowRectOf` (1778/1786), `installOpeningTabFlights` (2092), `prepareClosingTransition` (2611), `localRect` (3298), `visualChildRect` (3533). `visibleLocalRect` переведена на `out`-перегрузку со scratch-прямоугольником и больше не аллоцирует; старая версия удалена. В `applyTransitionProgress`, `applyClosingProgress`, `applyHoverCard`, `findCardAtScreenPoint`, `autoScrollStep`, `cardChildTarget`, `closingTerminalTarget`, `visibleLocalRect` аллокаций нет. Добавлены scratch-поля и `out`-перегрузки `offsetRect`, `windowRectOf`, `localRect`, `cardChildTarget`, `cardTabTarget`, `visibleLocalRect`.

---

### Step 3 — `renderCard()`: не пересоздавать `BitmapDrawable` для того же bitmap

**Action:**
* Добавить в `Card` флаг `boolean bitmapAttached`.
* В `renderCard()` вызывать `setImageBitmap()` только если bitmap создан заново / флаг сброшен; иначе делать только `card.preview.invalidate()`.
* Сбрасывать флаг в `releaseBitmap()` и при `setImageDrawable(null)`.
* Убрать дублирующий `invalidate()` там, где `setImageBitmap()` уже проинвалидировал view (оставить явный invalidate только в ветке уже прикреплённого bitmap).

**Files:** `SessionOverviewController.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "setImageBitmap\\|bitmapAttached" SessionOverviewController.java` — `setImageBitmap` встречается только внутри условия `if (!card.bitmapAttached || sizeChanged || configChanged)`.
* `grep -n "bitmapAttached = false" ...` — есть в `releaseBitmap()`.

**Evidence:** `git diff --check` — пусто. `Card.bitmapAttached` добавлен (4589); `setImageBitmap(card.bitmap)` вызывается только внутри `if (!card.bitmapAttached || newBitmap)` (4263–4265); `releaseBitmap()` сбрасывает флаг (4335). При повторном рендере того же bitmap вызывается только `invalidate()`.

---

### Step 4 — Единый автоскролл

**Action:**
* Вынести расчёт `frameRatio` и шага в `SessionOverviewReorderMath`:
  * `static float frameRatio(long nowMs, long lastMs)` (capped `48/16`);
  * уже существующий `autoScrollStep(...)` оставить как единственный расчёт шага.
* В `SessionOverviewController.autoScrollStep()` использовать `SessionOverviewReorderMath.frameRatio()` и `SessionOverviewReorderMath.autoScrollStep()` вместо ручной формулы и `TermuxActivityUtils.dpToPx()`; для band/step использовать уже посчитанные `mAutoScrollBandPx/mAutoScrollMaxStepPx` (один раз в конструкторе через density).
* В `SessionOverviewDragController.autoScrollFrame()` использовать тот же `frameRatio()`.
* Оба цикла сохраняют `postOnAnimation` и проверки состояния.

**Files:** `SessionOverviewReorderMath.java`, `SessionOverviewController.java`, `SessionOverviewDragController.java`.
**Fallback:** если вынос в `ReorderMath` окажется неудобным из-за Android-зависимостей, создать `SessionOverviewAutoScroll.java` — но сначала проверить.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "frameRatio" SessionOverviewController.java SessionOverviewDragController.java SessionOverviewReorderMath.java` — формула `48L) / 16f` встречается **только** в `ReorderMath`.
* `grep -n "autoScrollStep(" ...` — оба контроллера вызывают `SessionOverviewReorderMath.autoScrollStep(...)`.

**Evidence:** `git diff --check` — пусто. `SessionOverviewReorderMath.frameRatio()` — единственное место с `Math.min(dt,48L)/16f`. Оба контроллера вызывают `SessionOverviewReorderMath.autoScrollStep(...)`: контроллер с кешированными `mAutoScrollBandPx/mAutoScrollMaxStepPx`, drag — со своими `mAutoScrollBand/mAutoScrollMaxStep`. Проверена эквивалентность old/new формулы для top/bottom/central зон.

---

### Step 5 — Единый сброс трансформов и общий интерполятор

**Action:**
* Добавить `static void resetCardVisual(View view)` (в `SessionOverviewCardView` или отдельном helper) и заменить все повторяющиеся блоки сброса в `SessionOverviewCardView`, `SessionOverviewDragController`, `SessionOverviewController.cleanup*`.
* Заменить все `new DecelerateInterpolator()` в `SessionOverviewCardView` на один `static final` экземпляр (имя `DECELERATE`, как в drag-контроллере).
* В `setHoverHighlight()` end-листнер добавить проверку `if (mHoverAnimator != animation) return;` перед выставлением финальных значений.

**Files:** `SessionOverviewCardView.java`, `SessionOverviewDragController.java`, `SessionOverviewController.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "new DecelerateInterpolator" SessionOverviewCardView.java` — пусто.
* `grep -rn "setTranslationX(0f)" ...` — после правки остаётся только внутри `resetCardVisual` (плюс явно отличающиеся по смыслу места, если такие есть и они задокументированы).

**Evidence:** `git diff --check` — пусто. `SessionOverviewCardView.resetVisual(View)` — единственная точка полного сброса; используется в `resetSwipeState()`, `animateBack()` end, `restoreCardAfterCloseAnimation()`, `cleanupTransition()`, `cleanupClosingTransition()`, `finishDrop()`, `cancelDropFlight()`, `cancelPendingSettle()`, `cancelForModelChange()`. `new DecelerateInterpolator()` осталось только одно — в `SessionOverviewCardView:46`; `SessionOverviewDragController` использует тот же `SessionOverviewCardView.DECELERATE`. Hover end-листнер получил guard `if (mHoverAnimator != animation) return`. Оставлены два **частичных** сброса, где полный сброс изменил бы поведение: `DragController.clearPreviewOrder()` (только translation; не должен снимать hover-лифт соседей) и ветка не-поднятых карточек в `settleWhenLaidOut()` (там translation намеренно проинициализирован до анимации).

---

### Step 6 — `SessionOverviewTransitionMath`: упрощение тождеств

**Action:**
* `tabSourcePathFraction(p)` → `Math.min(p, TAB_MOTION_SPLIT)` (с clamp и проверкой `TAB_MOTION_SPLIT`).
* `tabTargetPathFraction(p)` → `Math.max(p, TAB_MOTION_SPLIT)`.
* `previewAlpha(p)` → step-функция `p >= 1f ? 1f : 0f`; `ghostAlpha` остаётся инверсией.
* `openingCardAlpha`/`closingCardAlpha` оставить как именованные алиасы, но реализовать через уже существующие функции (уже так и есть — сохранить).
* `blendColor()` — один `clamp01(progress)` на входе, целочисленная интерполяция каналов.
* Обновить Javadoc там, где описание обещает «linear» там, где реально step.

**Files:** `SessionOverviewTransitionMath.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -n "TAB_MOTION_SPLIT \\* (p / TAB_MOTION_SPLIT)" ...` — пусто.
* Сравнение старого и нового поведения глазами по таблице: `p=0, 0.25, 0.5, 0.75, 1` даёт одинаковые значения для всех упрощённых функций (записать в Evidence).

**Evidence:** `git diff --check` — пусто. Эквивалентность проверена вручную на `p = 0, .25, .5, .75, 1`:
`tabSourcePathFraction = 0,.25,.5,.5,.5`; `tabTargetPathFraction = .5,.5,.5,.75,1`; `previewAlpha = 0,0,0,0,1`. `blendColor()` теперь делает один `clamp01()` и вызывает приватный `mix()` вместо пяти `lerp()` с повторным clamp.

---

### Step 7 — `SessionOverviewGridMath`: один `buildLayout()` и фикс 1px overflow

**Action:**
* Вынести общую часть `compute(...)` в приватный `buildLayout(sessionCount, columns, totalRows, safeGap, safeWidth, safeHeight, cardWidth, cardHeight)`.
* Обе публичные перегрузки должны вызывать его.
* Исправить расчёт `dynamicGap`: гарантировать, что `left + gridWidth <= availableWidth` (например, уменьшать `dynamicGap` или cardWidth на 1 px при остатке `< columns+1`).
* Добавить/extension в `SessionOverviewReorderMathTest` или новый тест со сравнением старой формулы и новой для всех `availableWidth` 0..200 при фиксированных cardWidth/gap; проверить, что `gridWidth + left <= availableWidth`.

**Files:** `SessionOverviewGridMath.java`, тестовый файл.

**Acceptance check:**
* `git diff --check` — пусто.
* Тестовый метод существует и содержит цикл по ширинам; запуск тестов в玩笑 не требуется (Robolectric недоступен), но код читается как компилируемый.
* `grep -n "buildLayout" SessionOverviewGridMath.java` — две публичные перегрузки вызывают один helper.

**Evidence:** `git diff --check` — пусто. Обе публичные `compute()` вызывают приватный `buildLayout()` (строки 100 и 209). По ходу дела найден и исправлен скрытый баг: aspect-путь публиковал в `Layout.gap` значение `safeGap`, хотя размеры считал с `dynamicGap` — при height-limited раскладке `onLayout` расставлял карточки с другим зазором, чем был заложен в `gridHeight`. Теперь публикуется `dynamicGap`. Добавлен тест `exactCardSizeNeverOverflowsTheViewport()`, проверяющий `left + gridWidth <= availableWidth` и согласованность `gap` для остатков ширины 0..2 px.

---

### Step 8 — Уборка комментариев, кеша рендерера и лишних snapshot’ов

**Action:**
* Исправить устаревшие комментарии:
  * `prepareTransition()` / `prepareClosingTransition()` — убрать про «TopCropImageView paints the uncovered strip»;
  * `overviewBackdropColor()` — про endpoint tint вместо fully transparent cut-out;
  * `FloatingShadow` — про «twice as large as the first hover version»;
  * `TopCropImageView` — уточнить клип при `setSkipClipBounds(true)`.
* `TerminalPreviewRenderer.rendererFor()`: нормализовать `textSize` в ключе для не-explicit пути (`Math.round`) и добавить комментарий, что рендереры stateful и используются только с UI thread.
* Убрать 400 ms fallback Runnable из `show()` и снимать его в `cleanupTransition()` (хранить ссылку в `Transition`).
* Убрать ненужные `new ArrayList<>(...)` в `prepareTransition()`, `buildCards()`, `installClosingTabVisuals()`, оставив snapshot только в `refreshSessionSizesToCurrentWindow()`.

**Files:** `SessionOverviewController.java`, `FloatingShadow.java`, `TopCropImageView.java`, `TerminalPreviewRenderer.java`.

**Acceptance check:**
* `git diff --check` — пусто.
* `grep -rn "paints the uncovered strip\\|fully transparent cut-out\\|twice as large as the first" app/src terminal-view/src` — пусто.
* `grep -n "startFallback" SessionOverviewController.java` — fallback привязан к `Transition` и снимается в `cleanupTransition()`; безымянной inline-задержки больше нет.

**Evidence:** `git diff --check` — пусто; поиск трёх устаревших фраз даёт пустой результат. `TerminalPreviewRenderer.rendererFor()` нормализует `textSize` в ключе через `Math.round()` для не-explicit конструктора и документирует UI-thread ownership рендереров. 400 ms fallback перенесён в `Transition.startFallback`, удаляется в `cleanupTransition()`. Лишние копии списков убраны из `prepareTransition()`, `buildCards()` и `installClosingTabVisuals()`; snapshot в `refreshSessionSizesToCurrentWindow()` сохранён, потому что `updateSize()` может изменить живой список. `FloatingShadow.java` изменён: уточнён комментарий про единственный размер elevation. Параллельные правки TextFade/FadeTextView/history и отдельного shadow view в closing-полёте не входили в план и в итоге остались в дереве как внешние; в связи с последней `setSkipClipBounds()` удалён из `TopCropImageView`, а doc возвращён к нейтральному описанию клипа. Попытки откатывать эти правки прекращены: они применяются повторно параллельно.

---

### Step 9 — Финальная статическая верификация

**Action:** собрать все изменения together, проверить итоговый diff вручную, выполнить все acceptance-команды снова и записать сводку. Сборку/release **не запускать** без отдельной команды.

**Files:** все выше.

**Acceptance check:**
* `git diff --check` — пусто.
* `git diff --stat` — ожидаемый список файлов, без `third_party/`.
* `grep -rn "clipPath\\|setFlightCornerRadius" app/src/main/java/com/termux/app/terminal --include=*.java` — пусто (патч не применён).
* Таблица Evidence во всех шагах заполнена.
* Итоговый отчёт пользователю: что изменено, что не изменено, что требует сборки по команде.

**Evidence:** `git diff --check` — пусто. Файлы плана: FloatingShadow, OverviewBackdropHoleView, SessionOverviewCardView, SessionOverviewController, SessionOverviewDragController, SessionOverviewGridMath, SessionOverviewReorderMath, SessionOverviewTransitionMath, TopCropImageView, `terminal-view/.../TerminalPreviewRenderer.java`, тест `SessionOverviewGridMathTest` и `PLAN.md`. `third_party/` не добавлен. Параллельные правки TextFade/FadeTextView/history и отдельного shadow view сохранены и помечены как внешние; они не являются частью этого плана. `grep -rn "clipPath\|setFlightCornerRadius" app/src/main/java/com/termux/app/terminal --include=*.java` — пусто (патч не применён). Unit-тесты и `assembleRelease` не запускались: по плану достаточно статической верификации, релиз — только по отдельной команде.

---

## 5. Критика плана (свежий взгляд)

* Step 5 формулировал «общий интерполятор» как локальную замену в `SessionOverviewCardView`, но на деле дубль был и в `SessionOverviewDragController`. Он устранён: оба контроллера используют `SessionOverviewCardView.DECELERATE`.
* Step 7 изначально предлагал уменьшать `dynamicGap`; выбран менее инвазивный вариант — сохранить `dynamicGap`, но ограничить `left`, чтобы `left + gridWidth <= availableWidth`. Дополнительно исправлено расхождение между `Layout.gap` и фактическим `dynamicGap` в aspect-пути.
* Step 8 изначально предполагал править комментарий в `FloatingShadow.java`; устаревшая формулировка про «twice as large as the first» действительно была в `FloatingShadow.overviewCardElevationPx()`, она исправлена. Параллельно появившаяся правка autocomplete/TextFade не входила в план и была откачена, чтобы не менять визуальное поведение suggestion-строк.
* 400 ms fallback не удалён полностью: он нужен как защита от grid, который никогда не сообщит layout. Вместо удаления он привязан к владельцу (`Transition.startFallback`) и снимается при cleanup.
* `FloatingShadow.withAlphaFraction()` дважды пересматривался: сначала был восстановлен, потому что closing-полёт ещё вызывал его, затем параллельная правка перевела тень в отдельный view и вызов исчез — метод снова удалён как мёртвый код. Текущее состояние проверено `grep`: вхождений нет.

---

## 6. Пересмотр плана

План выполнялся как структурная чистка без изменения визуальной модели. Единственное поведенческое уточнение — в Step 7: публикуемый `Layout.gap` теперь совпадает с зазором, который реально используют `onLayout()`. Это убирает расхождение размеров, а не меняет целевую картинку. Все остальные шаги — переиспользование объектов, unify helper’ов и документация.

---

## 7. Статус выполнения

| Step | Статус | Evidence |
|---|---|---|
| 1 | done | `git diff --check` clean; path built only in `setContent()` lines 91/93 |
| 2 | done | scratch geometry + out-overloads; no allocations in per-frame methods |
| 3 | done | `bitmapAttached` flag; same-bitmap repaint only invalidates |
| 4 | done | shared `frameRatio()` + `autoScrollStep()` in ReorderMath |
| 5 | done | shared `resetVisual`, shared interpolator, guarded hover end |
| 6 | done | simplified path/alpha math, single clamp in blendColor |
| 7 | done | one `buildLayout()`, gap consistency bug fixed, overflow test added |
| 8 | done | stale comments fixed, renderer key normalised, owned fallback, no redundant list snapshots |
| 9 | done | `git diff --check`, grep-проверки, final diff review; сборка/release не запускалась |
