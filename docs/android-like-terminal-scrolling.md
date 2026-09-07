# Прокрутка терминала «как в Android» в рамках глифовой сетки

**Проект:** `D:\projects\termux-enhanced` (форк `NIK2703/termux-enhanced`)
**Слепок кода:** коммит `0fab152e` («fix: edge-decelerated terminal scroll without overscroll bounce»), detached HEAD.
**Ограничение:** прокрутка только целыми строками (глифами). Никакого субглифового (пиксельного) сдвига рендера — модель рендера `TerminalRenderer` этого не допускает, и это осознанное требование.
**Цель:** чтобы жест прокрутки по ощущениям (трекинг пальца, дистанция и характер инерции, остановка касанием, поведение у краёв) совпадал с системной прокруткой Android (списки, Chrome и т.п.), насколько это возможно при дискретном рендере строк.

---

## 1. Инвентаризация текущей реализации (что уже есть в коде)

Вся механика живёт в `terminal-view/src/main/java/com/termux/view/TerminalView.java` (далее — `TerminalView`) и `terminal-view/src/main/java/com/termux/view/GestureAndScaleRecognizer.java`.

### 1.1. Распознавание жеста — уже системное

`GestureAndScaleRecognizer` (119 строк) — тонкая обёртка над **стандартным** `android.view.GestureDetector` (`ignoreMultitouch=true`) плюс `ScaleGestureDetector`. Это значит, что «входная» часть ощущений уже Android-нативная:

- **touch slop** — системный (`ViewConfiguration.getScaledTouchSlop()`, ~8dp). `GestureDetector` сам вычитает slop из первой дельты `onScroll`, т.е. трекинг 1:1 начинается сразу после порога, без скачка.
- **velocity tracking** — системный `VelocityTracker` внутри `GestureDetector`; `onFling` получает скорость в **px/s**, уже отфильтрованную и ограниченную `[minFlingVelocity, maxFlingVelocity]` (50 dp/s … 8000 dp/s, масштабированы под density).
- **long-press → выделение текста**, double-tap, quickScale выключен (`mScaleDetector.setQuickScaleEnabled(false)`).

Выбор оси прокрутки — свой: `mScrollAxis` решается по `mScrollAxisSlop` (`TerminalView.java:297-304`), вертикальный жест отбирается у ViewPager2 через `requestDisallowInterceptTouchEvent(true)` (`:305-309`), горизонтальный отдаётся pager'у (`:311-313`).

### 1.2. Drag (палец на экране) — уже правильная модель «px → квантование в строки»

`onScroll` (`TerminalView.java:287-321`):

```java
distanceY += mScrollRemainder;
int deltaRows = (int) (distanceY / mRenderer.mFontLineSpacing);
mScrollRemainder = distanceY - deltaRows * mRenderer.mFontLineSpacing;
doScroll(e, deltaRows);
```

Палец движется в пикселях, дельты накапливаются в `mScrollRemainder`, рендер сдвигается только на целые строки (`mFontLineSpacing` = `ceil(paint.getFontSpacing())`, `TerminalRenderer.java:82`). Дробная часть **не теряется**, а переносится в следующее событие — это математически корректное квантование без дрейфа. Это эталон, к которому дальше приводится и fling.

`mScrollRemainder` сбрасывается в `onUp` (`:260`) и при перехвате fling (`:896`) — суб-строковый остаток между жестами не сохраняется (незаметно, см. §5.1).

### 1.3. Fling — физика `OverScroller`, но в **строковых** координатах (главный дефект)

`startFling` (`TerminalView.java:934-999`):

```java
int scrollerVelocityY = -Math.round(rawVelocity * FLING_VELOCITY_SCALE); // 0.25
...
startY = mTopRow; minY = -transcriptRows; maxY = 0;                      // единицы — СТРОКИ
mScroller.fling(0, startY, 0, scrollerVelocityY, 0, 0, minY, maxY);
```

`runFlingFrame` (`:1001-1027`) каждый кадр (через `postOnAnimation`, т.е. Choreographer/VSync) берёт `computeScrollOffset()` и скроллит на `diff = newY - mTopRow` строк — позиционная (абсолютная) схема, без накопления ошибки. Для mouseTracking-режима — отдельный `mFlingDeltaMode` с малым диапазоном `±mRows/2` (`:968-973`).

Коммит `0fab152e` заменил `Scroller` на `OverScroller` и выставил `setOverScrollMode(OVER_SCROLL_NEVER)` (`:375-377`): fling замедляется сплайном к краю и останавливается жёстко, без bounce и edge-glow. Это осознанное дизайн-решение форка — **не откатываем его**, см. §5.4.

### 1.4. Остановка касанием и «подхват» — уже есть, даже богаче системы

- `onDown` → `interruptFlingForNewTouch()` (`:340-341`, `:892-897`): fling гасится в тот же кадр касания — как в Android.
- Сверх системы: остаточная скорость **захватывается** (`captureCurrentFlingVelocity`), затухает экспоненциально с `FLING_CAPTURE_TAU_MS=150` (`getDecayedCapturedVelocity`) и **суммируется** со скоростью следующего жеста (`combineFlingVelocity`, фактор `FLING_RESIDUAL_FACTOR=0.6`). Повторный свайп по катящемуся тексту ускоряет его — очень «физично».
  **⚠️ Устарело:** это НЕ системное поведение и оно выключено. См. `docs/android-like-terminal-acceleration.md` §2 (двойной учёт импульса) и §2.1 (баг знака: `getCurrVelocity()` возвращает норму). Теперь за флагом `FLING_RESIDUAL_ENABLED = false`.
- Потолок: `mMaxCombinedFlingVelocity = getScaledMaximumFlingVelocity() * 1.75` (`:383`).

### 1.5. Прочие каналы прокрутки

- **Колесо мыши/тачпад:** `onGenericMotionEvent` (`:1070-1078`) — мгновенный `doScroll(event, ±3)` на любой тик, дробные значения `AXIS_VSCROLL` (precision touchpad) игнорируются. Инерции нет.
- **`accelerateFling(float)`** (`:1029-1043`) — публичный API «добавить скорость во fling», **сейчас никем не вызывается** (задел, видимо, под колесо).
- **Скроллбар (свой интерактивный):** drag thumb'а управляет `mTopRow` напрямую, fling гасится (`:1169`, `:1212`) — вне scope этого документа, оставляем как есть.
- **Клавиатура:** PgUp/PgDn → `doScroll(±mRows)` (`:1551`).
- **Режимы** (`doScroll`, `:1046-1066`): mouseTracking → шлёт wheel-коды приложению; alt-buffer (less/vim без мыши) → `KEYCODE_DPAD_UP/DOWN`; иначе — `mTopRow` с клампом и `setAutoScrollDisabled(mTopRow != 0)`.

### 1.6. Компенсация при выводе (follow-text)

`onScreenUpdated(boolean)` (`:600-646`): пока пользователь выше низа, при переполнении истории `mTopRow -= rowShift`, чтобы текст под вьюпортом не «уезжал». Покрыто юнит-тестом `terminal-emulator/src/test/java/com/termux/terminal/ScrollFollowTextTest.java`. Взаимодействие этой компенсации с активным fling — см. §5.7 (там конфликт).

---

## 2. Главный дефект: физика fling считается в строках, а не в пикселях

`OverScroller` внутри использует `SplineOverScroller` — физическую модель вязкого трения AOSP:

```
DECELERATION_RATE = ln(0.78)/ln(0.9) ≈ 2.358
physicalCoeff     = 9.80665 · 39.37 · ppi · 0.84        (ppi = density·160)
distance(v)       = friction·physicalCoeff · exp( DECELERATION_RATE/(DECELERATION_RATE−1) · ln(0.35·|v| / (friction·physicalCoeff)) )
duration(v)       = 1000 · exp( ln(0.35·|v|/(friction·physicalCoeff)) / (DECELERATION_RATE−1) )   [ms]
friction          = SCROLL_FRICTION = 0.015
```

Ключевое свойство: **дистанция зависит от скорости нелинейно** (логарифмически в показателе). Поэтому «подобрать коэффициент» `FLING_VELOCITY_SCALE=0.25`, чтобы перевести px/s в «строки/с», нельзя — ни один постоянный множитель не совместит кривые во всём диапазоне скоростей.

Численная иллюстрация (устройство ~440 ppi, density 2.75, `mFontLineSpacing` ≈ 45 px, свайп `v = 3000 px/s`):

| | системный fling (ось в px) | текущий fling (ось в строках, ×0.25) |
|---|---|---|
| подача в scroller | 3000 px/s | 750 «строк/с» |
| дистанция | ≈ 620 px ≈ **14 строк** | ≈ 56 строк ≈ **2520 px** |
| длительность | ≈ 590 мс | ≈ 210 мс |

(Прикидка по формулам выше, ±10%.) То есть одна и та же скорость пальца даёт пролёт **в ~4 раза дальше и почти в 3 раза резче**, чем в любом системном списке. При других скоростях соотношение иное — отсюда «чужое» ощущение: fling в терминале непредсказуем относительно остальной системы.

Второй дефект строковой оси: **дистанция в пикселях зависит от размера шрифта.** Увеличил шрифт — та же скорость пальца пролетает в пикселях пропорционально больше (строки стали выше, а их количество за fling не изменилось). В системе дистанция fling в px не зависит от контента.

Третий дефект: `mMaxCombinedFlingVelocity = maxFling·1.75` (`:383`) — компенсация заниженной «дальности» строковой модели, ещё один признак подгонки.

**Вывод:** вход (жест, скорость) у нас системный, физика — нет. Чинить надо не жесты, а **единицу измерения оси fling**.

---

## 3. Целевая модель: «физика в пикселях, рендер в строках»

Принцип: **вся физика (drag-аккумулятор, fling, края) работает в пиксельном пространстве, идентичном системному; квантование в строки — только на последнем шаге, при выводе в `mTopRow`.**

Состояние позиции:

- `mTopRow` (строки, ≤ 0, 0 = низ) остаётся **источником истины** о позиции — его читают рендер (`rowToPixelTop`, `:735`), скроллбар, `getColumnAndRow`, тесты.
- Виртуальная пиксельная ось `scrollPx ∈ [-maxScrollPx, 0]` существует **только внутри жеста/fling** (внутри `OverScroller` и `mScrollRemainder`), знак тот же, что у `mTopRow`.
- Инвариант в покое: `mTopRow = -( -scrollPx / mFontLineSpacing )` (округление к строке; дробный остаток не рендерится).
- `maxScrollPx = getActiveTranscriptRows() * mFontLineSpacing` — пересчитывается на старте каждого fling (история могла вырасти).

Почему абсолютное, а не инкрементальное маппингование кадра: `diff_rows = pxToRows(currY_px) - mTopRow` — дробный остаток живёт внутри `OverScroller`, ошибка не накапливается, дрейф невозможен (та же схема уже используется в non-delta ветке `runFlingFrame`, меняется только единица оси).

Что это даёт:

1. **Дистанция и длительность fling совпадают с системой с точностью до квантования** (ошибка позиции ≤ 1 строка, скорость и торможение — точные), потому что используется тот же `SplineOverScroller` на тех же единицах и той же скорости.
2. **Независимость от размера шрифта:** дистанция в px фиксирована физикой, строки — производная.
3. Исчезают эвристики `FLING_VELOCITY_SCALE` и `×1.75` — меньше магических чисел, предсказуемее тюнинг.
4. Drag и fling становятся одной моделью (оба «px внутри, строки снаружи»), их стык бесшовен: палец оторвался со скоростью `v` px/s — fling продолжается с `v` px/s по той же оси.

---

## 4. Анатомия «ощущения Android» → статус в коде → действие

| # | Элемент ощущения | Где в AOSP | Статус в форке | Действие |
|---|---|---|---|---|
| 1 | Трекинг 1:1 после touch slop | `GestureDetector` | ✅ уже есть (`onScroll`+`mScrollRemainder`) | оставить |
| 2 | Fling с системным затуханием | `SplineOverScroller` | ⚠️ есть, но ось в строках | **перевести ось в px** (§5.2) |
| 3 | Остановка fling касанием в тот же кадр | `abortAnimation` в lists | ✅ `interruptFlingForNewTouch` на `onDown` | оставить |
| 4 | min/max fling velocity системные | `ViewConfiguration` | ⚠️ max ×1.75 | вернуть системный max (§6) — сделано |
| 5 | Повторный свайп подхватывает движение | измерение абсолютной скорости пальца | ❌ residual-combine (τ=150мс, ×0.6) | **выключено**: `FLING_RESIDUAL_ENABLED=false` (см. acceleration.md §2) |
| 6 | Край: жёсткий стоп / glow | `EdgeEffect` | ✅ жёсткий стоп (`0fab152e`, `OVER_SCROLL_NEVER`) | решение уже принято; glow — опция (§5.4) |
| 7 | VSync-тайминг, независимость от FPS | `Choreographer` | ✅ `postOnAnimation` + time-based scroller | оставить |
| 8 | Нет «лесенки» в хвосте fling | непрерывные px | ⚠️ квантование даёт редеющие прыжки | tail-cut (§5.5) |
| 9 | Колесо/тачпад: дробные дельты | `AXIS_VSCROLL`×scrollFactor | ⚠️ жёсткие ±3 строки | px-путь + remainder (§5.6) |
| 10 | Живой вывод не рвёт жест | — | ⚠️ конфликт follow-text ↔ scroller | правило авторитета (§5.7) |

---

## 5. Детальный дизайн по компонентам

### 5.1. Drag (`onScroll`, `:287-321`) — почти без изменений

Модель уже правильная (px → remainder → строки). Два уточнения:

- **Не сбрасывать `mScrollRemainder` при горизонтальной оси.** Сейчас при `SCROLL_AXIS_HORIZONTAL` делается ранний `return` (`:311-313`) — до накопления remainder, ок; но если ось ещё `UNDECIDED`, дельты молча проглатываются (`:300-302`). Это системно-подобно (slop), оставляем.
- **Сброс remainder в `onUp` (`:260`)** — потеря до `mFontLineSpacing−1` px между жестами. В системных списках суб-пиксель тоже не «долговечен», так что поведение близко к системному. **Оставить.** (Если когда-нибудь захочется идеальной непрерывности drag→drag — хранить remainder как часть `scrollPx`, а не обнулять; сейчас не требуется.)

### 5.2. Fling в пиксельной оси — ядро изменений

Переписываем `startFling`/`runFlingFrame` (`:934-1027`). Поля `FLING_VELOCITY_SCALE`, `mFlingLastY` (в строковом смысле) и ветка mouseTracking range в строках — пересматриваются.

Новые поля (заменяют смысл существующих):

```java
// px-ось fling: 0 = низ, отрицательное — вглубь истории. Знак как у mTopRow.
private int mFlingLastRowPx;   // последнее pxToRows(scroller.getCurrY()), для deltaMode
```

Старт fling (псевдокод, сигнатуры сохранены):

```java
private boolean startFling(MotionEvent e, float velocityX, float velocityY) {
    if (mEmulator == null) return true;
    // ... выбор оси как сейчас (:936-946) ...

    if (isFlingActive() && mCapturedFlingVelocityY == 0f) captureCurrentFlingVelocity();
    long now = SystemClock.uptimeMillis();
    float rawVelocity = combineFlingVelocity(getDecayedCapturedVelocity(now), velocityY);
    clearCapturedFlingVelocity();

    MotionEvent newFlingEvent = MotionEvent.obtain(e);
    stopFlingAnimation();
    if (Math.abs(rawVelocity) < mMinFlingVelocity) {          // системный min, px/s
        newFlingEvent.recycle();
        return true;
    }

    final int rowHeight = mRenderer.mFontLineSpacing;          // px на строку
    boolean mouseTracking = mEmulator.isMouseTrackingActive();
    // Mouse tracking И alt-buffer — одинаково «приложение владеет позицией»: строки fling
    // стримятся ему относительными дельтами (wheel при mouse tracking, DPAD иначе).
    boolean appScrolled = mouseTracking || mEmulator.isAlternateBufferActive();
    int startPx, minPx, maxPx;
    if (appScrolled) {
        startPx = 0;
        // Диапазон НЕ ограничивает прокрутку — это только страховка от переполнения.
        // См. §5.2.1: любой достижимый предел укорачивает fling.
        minPx = -FLING_DELTA_AXIS_LIMIT_PX; maxPx = FLING_DELTA_AXIS_LIMIT_PX;
    } else {
        int transcriptRows = mEmulator.getScreen().getActiveTranscriptRows();
        if (transcriptRows <= 0) { newFlingEvent.recycle(); return true; }
        startPx = mTopRow * rowHeight;                         // позиция -> px
        minPx = -transcriptRows * rowHeight; maxPx = 0;
    }

        mFlingDeltaMode = appScrolled;
    mFlingLastRowPx  = pxToRows(startPx, rowHeight);
    mFlingRawVelocity = rawVelocity;                            // px/s, БЕЗ scale
    mFlingEvent = newFlingEvent;

    mScroller.fling(0, startPx, 0,
                    -Math.round(rawVelocity),                 // px/s напрямую: никакого FLING_VELOCITY_SCALE
                    0, 0, minPx, maxPx);
    mFlingRunnable = this::runFlingFrame;  // (Runnable, как сейчас)
    postOnAnimation(mFlingRunnable);
    return true;
}

/** Квантование px-позиции в строки с тем же знаком, что у mTopRow (отрицательные вверх). */
private static int pxToRows(int px, int rowHeight) {
    // trunc к нулю == поведение (int)(px/rowHeight) в onScroll; для отрицательной оси
    // trunc означает «к низу», что совпадает с текущей семантикой mScrollRemainder.
    return px / rowHeight; // целочисленное деление Java = trunc toward zero
}
```

Кадр fling:

```java
private void runFlingFrame() {
    // ... проверки mEmulator/mFlingEvent/mouseTracking как сейчас (:1002-1009) ...

    boolean more = mScroller.computeScrollOffset();
    final int rowHeight = mRenderer.mFontLineSpacing;
    int newRow = pxToRows(mScroller.getCurrY(), rowHeight);

    int diff;
    if (mFlingDeltaMode) {
        diff = newRow - mFlingLastRowPx;
        mFlingLastRowPx = newRow;
    } else {
        diff = newRow - mTopRow;      // абсолютная схема — как сейчас
    }
    if (diff != 0) doScroll(mFlingEvent, diff);

    if (more) {
        postOnAnimation(mFlingRunnable);
    } else {
        stopFlingAnimation();
        // опционально: EdgeEffect absorb, см. §5.4; tail-cut см. §5.5
    }
}
```

Важные свойства:

- **Дробный остаток не нужен** в px-модели fling: он хранится внутри `OverScroller.getCurrY()` (float внутри AOSP), абсолютное маппингование не накапливает ошибку.
- `doScroll` не трогаем — он уже принимает строки и умеет все три режима.
- Удаляется `FLING_VELOCITY_SCALE` (`:145`) и вся конвертация; `mFlingRawVelocity` теперь честные px/s — это упрощает и `captureCurrentFlingVelocity` (`:883`: сейчас делит на scale — убрать деление).
- **Старт позиции fling из `mTopRow * rowHeight`**: суб-строковый остаток drag'а (`mScrollRemainder`) теряется на старте fling — ровно как сейчас, несущественно (≤ 1 строки, визуально ноль, т.к. остаток никогда не рендерился).

### 5.2.1. Ловушка: любой предел диапазона укорачивает fling (корень бага TUI-прокрутки)

`SplineOverScroller.fling()` при выходе `mFinal` за `[min, max]` делает **две** вещи:

```java
if (mFinal > max) { adjustDuration(mStart, mFinal, max); mFinal = max; }
```

`adjustDuration` срезает не только дистанцию, но и **длительность** — ровно до момента, когда
та же кривая скорости доедет до усечённой точки. Кривая расстояния вогнутая (скорость максимальна
в начале и падает), поэтому 43 % дистанции набираются уже за ~17 % времени. Итог:

| v жеста | естеств. дистанция | диапазон ±mRows/2 (=20 строк) | длительность | что видит пользователь |
|---|---|---|---|---|
| 2500 px/s | 9 строк | не достигнут | 417 мс (полная кривая) | ✅ нормальный импульс |
| 4000 px/s | 22 строки | срезан до 19 | 731 → **388 мс** | заметно резче |
| 6000 px/s | 45 строк | срезан до 19 | 986 → **167 мс** | «остановился сразу» |
| 9000 px/s | 92 строки | срезан до 19 | 1329 → **102 мс** | «остановился сразу» |
| 22 000 px/s (предел) | 439 строк | срезан до 16 | 2566 → **40 мс** | импульс умер за 2–3 кадра |

Чем сильнее свайп, тем **короче** анимация — инверсия системного ощущения. Именно это
наблюдалось в TUI (opencode): спокойный свайп (дистанция < ±20 строк) не касался стенки и крутил
полную кривую, резкий — выплёвывал весь импульс за 50–180 мс и замолкал.

Диапазон ±mRows/2 исторически появился как «на всякий случай ограничить число wheel-событий», но
в delta-режиме абсолютная позиция не имеет смысла — ограничивать нечего. Ось сделана
практически неограниченной (`FLING_DELTA_AXIS_LIMIT_PX`), реальные пределы — сама физика
(≤ ~30k px при максимальной скорости жеста) и `FLING_DELTA_MAX_ROWS_PER_FRAME` (8 строк/кадр,
который в симуляции не достигается ни при одной реальной скорости).

**Инвариант:** в абсолютном (mTopRow) режиме границы — это реальные края истории, и усечение
там корректно (это и есть «притормозить у края»). В delta-режиме границ нет, и усечение — баг.

### 5.3. Подхват скорости между жестами — сохранить механику, сменить единицы

`captureCurrentFlingVelocity`/`getDecayedCapturedVelocity`/`combineFlingVelocity` (`:879-925`) — отличная механика (в системе такого нет явно, это улучшение). Меняется только трактовка: всё в px/s, без `FLING_VELOCITY_SCALE`:

- `:883` `mCapturedFlingVelocityY = -scrollerVelocity;` (деление на scale убрать);
- `clampFlingVelocity` (`:921-925`) — клампить к **системному** `getScaledMaximumFlingVelocity()` (см. §6, пункт про `×1.75`);
- `combineFlingVelocity`: противоположные знаки → гашение (`result=0` ниже minVelocity) — оставить как есть; это физично: свайп навстречу катящемуся тексту гасит его, а не телепортирует в другую сторону.

### 5.4. Края: жёсткий стоп (принято в `0fab152e`) vs системный glow

Android-список при ударе fling в край: `EdgeEffect.onAbsorb(velocity)` — свечение, **без смещения контента** (post-ICS); при drag за край — `onPull`, тоже только визуал. Pixel-растяжение (Android 12+) — субпиксельный эффект, нам недоступен и не нужен.

В форке осознанно выбран **жёсткий стоп без glow** (`setOverScrollMode(OVER_SCROLL_NEVER)`, `:377`). Это легитимное отклонение от системы (часть OEM/приложений ведут себя так же). Решение:

- **Дефолт: оставить как есть.** Никаких изменений, поведение у края детерминированное, «дорогая» остановка уже достигается сплайном OverScroller (он сам плавно притухает к границе, а не бьётся о неё).
- **Опция (если захочется максимального системного ощущения):** glow без движения контента:
  - поля `EdgeEffect mEdgeGlowTop/Bottom`; `setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS)`;
  - drag у края: в `onScroll`, если `mTopRow==0 && distanceY<0` (тянем за низ) или `mTopRow==-transcript && distanceY>0` → `edgeGlow.onPull(distanceY/getHeight(), e.getX()/getWidth())`, remainder не накапливать;
  - fling в край: в `runFlingFrame`, когда `more==false` и `mScroller.getCurrVelocity()>0` (удар, а не дотухание) → `edgeGlow.onAbsorb((int)mScroller.getCurrVelocity())`;
  - рендер: в `dispatchDraw`/`draw` — `edgeGlow.draw(canvas)` по верхней/нижней кромке, `postInvalidateOnAnimation` пока `!isFinished`; `onRelease` в `onUp`/`onCancel`.
  - Это чисто визуальный слой поверх, **глифовая модель не нарушается**.

### 5.5. «Хвост» fling: квантованная лесенка и tail-cut

При квантовании в строки конец fling вырождается: скорость падает, строки «щёлкают» по одной с растущими паузами (при v < ~4 строк/с шаги различимы индивидуально), а остаток дистанции < 1–2 строк. В пиксельных списках этой проблемы нет.

Решение — **tail-cut**: завершать fling, когда мгновенная скорость падает ниже порога:

```java
// в runFlingFrame, после computeScrollOffset():
float v = mScroller.getCurrVelocity();                       // px/s
float tailV = TAIL_ROWS_PER_SEC * mRenderer.mFontLineSpacing; // рекоменд. 3..5 строк/с
if (more && v < tailV) {
    // оставшаяся дистанция при такой скорости < ~1 строки — не докручиваем
    stopFlingAnimation();
    return;
}
```

Обоснование: по модели вязкого трения остаток пути при скорости `v` ≈ `v / λ` (λ — эффективный декремент); при `v = 4·rowHeight` это порядка 1–2 строк. Потеря дистанции ≤ 2 строк неощутима, а «дребезг» в конце исчезает полностью. Порог — настраиваемый (§6). Заметим: **drag это не касается** — там палец управляет темпом сам.

### 5.6. Колесо мыши и precision-тачпад (`onGenericMotionEvent`, `:1070-1078`)

Сейчас: любой тик → `doScroll(±3)` мгновенно, дробная часть `AXIS_VSCROLL` выбрасывается. На обычном колесе это приемлемо и близко к системе (ScrollView/RecyclerView колесо анимируют слабо или мгновенно), но на hi-res тачпадах (дробные дельты) — дёргано и теряется точность.

Целевое поведение — тот же px-путь, что drag:

```java
@Override
public boolean onGenericMotionEvent(MotionEvent event) {
    if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE)
            && event.getAction() == MotionEvent.ACTION_SCROLL) {
        float axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float px = -axis * mWheelScrollFactorPx;   // ViewConfiguration.getScaledVerticalScrollFactor()
        px += mScrollRemainder;                    // общий remainder с drag-путём
        int deltaRows = (int) (px / mRenderer.mFontLineSpacing);
        mScrollRemainder = px - deltaRows * mRenderer.mFontLineSpacing;
        if (deltaRows != 0) doScroll(event, deltaRows);
        return true;
    }
    return false;
}
```

- `getScaledVerticalScrollFactor()` — системный калибр «px на тик» (зависит от density) — заменяет магическую тройку; при желании оставить «ровно 3 строки на тик» — тогда `px = -axis * 3 * mFontLineSpacing`, главное — прогонять через remainder, чтобы дробные тики копились.
- **Инерцию для колеса не включаем по умолчанию** — системные списки её не делают. Опционально (под тумблер): вместо `doScroll` → `accelerateFling(velocityY)` (`:1029`, сейчас мёртвый код — наконец найдётся вызывающий), где импульс подобран так, чтобы 1 тик ≈ 3–5 строк пролёта по той же friction-модели. Это «chromebook-style» ощущение, к системному Android не относится.

### 5.7. Живой вывод во время fling: правило одного авторитета

Конфликт в текущем коде: во время fling `mTopRow` меняется **двумя** хозяевами — scroller'ом (через `diff = newY - mTopRow` в `runFlingFrame`) и follow-text компенсацией (`mTopRow -= rowShift` в `onScreenUpdated`, `:633`). Пример: история на лимите, `yes` печатает, fling вверх — компенсация сдвигает `mTopRow` вверх, следующий кадр scroller возвращает его назад (`diff` вырастает на rowShift). Итог: либо микроджиттер, либо фактическая отмена follow-text во время fling (зависит от порядка кадров) — недетерминированно.

Правило (одно предложение): **пока `isFlingActive()` — позицией владеет только scroller; follow-text компенсация `mTopRow` не применяется.**

Реализация: в `onScreenUpdated(boolean)` (`:600-646`) ветку компенсации обернуть в `if (!isFlingActive())`. Когда fling завершается — компенсация снова работает. Побочный эффект: текст под вьюпортом во время fling при активном выводе чуть «плывёт» с выводом — это нормально и физично (как в системном списке с добавляющимися элементами).

Расширить `ScrollFollowTextTest` кейсом «fling активен → компенсация отключена → после `stopFling` компенсация возвращается». Также проверить переполнение истории во время fling: `transcriptRows` не меняется (лимит), `minPx/maxPx` остаются валидны; если лимит не достигнут и история растёт — `minPx` устаревает (становится меньше возможного), что безопасно: fling просто не дотянется до новых строк, следующий жест пересчитает диапазон.

### 5.8. Зум шрифта и смена размера view

`mFontLineSpacing` меняется при pinch-зуме (`onScale` `:324-330` → `stopFlingAndClear()` → `TermuxTerminalViewClient.onScale` → `changeFontSize` → `setTextSize`). Это уже корректно: fling гасится, позиция хранится в строках (`mTopRow`) и переживает смену `rowHeight` без скачков (строка остаётся строкой). В px-модели это свойство сохраняется, т.к. px-ось живёт только внутри жеста и пересоздаётся на старте следующего fling. **Менять нечего — зафиксировать как инвариант:** «источник истины о позиции — строки; px — производная, пересчитываемая на старте жеста».

`attachSession` (`:427-444`) уже делает `stopFlingAndClear(); mTopRow = 0` — ок.

### 5.9. Режимы mouseTracking / alt-buffer

- **mouseTracking** (vim с мышью): fling должен слать wheel-события с той же динамикой. deltaMode сохраняем, но ось — в px и **без диапазона** (§5.2.1); квантование — `pxToRows(diff)` как в §5.2. Ощущение скорости унифицируется с обычной прокруткой. Сброс при смене режима уже есть (`:1006-1008`).
- **alt-buffer → DPAD_UP/DOWN** (`doScroll`, `:1052-1055`): каждая «строка» fling превращается в нажатие. Раньше такой fling **не запускался вовсе** — `startFling` требовал `transcriptRows > 0`, а в alt-буфере истории нет (0) → `less`/vim без мыши не получали инерции вообще. Теперь alt-buffer идёт по тому же delta-пути, что и mouse tracking (`appScrolled`), и получает системную физику. Кламп `FLING_DELTA_MAX_ROWS_PER_FRAME` (8 строк/кадр) ограничивает пачку key events; на реальных скоростях он не срабатывает (≤ ~3 строки/кадр).
- Обычный буфер: без изменений.

### 5.10. Nested scrolling / ViewPager2

Уже решено: вертикаль отбирается у pager'а (`:305-309`), горизонталь отдаётся (`:311-313`), при DOWN/CANCEL/UP флаги возвращаются. В px-модели не затрагивается. Оставить.

---

## 6. Параметры и тюнинг

| Параметр | Сейчас | Целевое | Комментарий |
|---|---|---|---|
| touch slop / ось | `getScaledTouchSlop()` (`:381`) | без изменений | системный |
| min fling velocity | `getScaledMinimumFlingVelocity()` (`:382`) | без изменений | 50 dp/s, px/s |
| max fling velocity | `maxFling × 1.75` (`:383`) | **системный `getScaledMaximumFlingVelocity()`** | 8000 dp/s; ×1.75 был компенсацией строковой модели |
| `FLING_VELOCITY_SCALE` | 0.25 (`:145`) | **удалить** | физика в px, масштаб не нужен |
| friction | `SCROLL_FRICTION=0.015` (внутри OverScroller) | без изменений | системное; доступно через `OverScroller.setFriction()` при желании «покороче» |
| `FLING_RESIDUAL_FACTOR` | 0.6 (`:146`) | 0.6, только при `FLING_RESIDUAL_ENABLED` | подхват в px/s; сам флаг теперь **OFF** |
| `FLING_CAPTURE_TAU_MS` | 150 (`:147`) | 150, только при `FLING_RESIDUAL_ENABLED` | окно подхвата |
| `WHEEL_IMPULSE_SETTLE_MS` | — | 100 | = AOSP `HORIZON`, для импульса колеса (acceleration.md §4.2) |
| `TAIL_ROWS_PER_SEC` (новое) | — | **4** (диапазон 3–5) | tail-cut, §5.5 |
| wheel factor (новое) | жёсткие ±3 строки | `getScaledVerticalScrollFactor()` или `3×mFontLineSpacing` через remainder | §5.6 |
| alt-buffer key-rate clamp (новое) | — | 8 строк/кадр | §5.9 |

Тюнинг «ощущения» после внедрения (на устройстве, POCO X5 Pro): side-by-side с системным списком (Настройки → любой длинный список). Если fling в терминале субъективно «длиннее» (квантованный рендер визуально сглаживает торможение меньше, чем пиксельный) — поднять friction до 0.017–0.02 через `mScroller.setFriction()` в конструкторе (`:375`). Это единственный ручной параметр калибровки.

---

## 7. План внедрения (по файлам)

Всё — в `terminal-view/src/main/java/com/termux/view/TerminalView.java`, кроме тестов.

1. **px-ось fling** (ядро): `startFling` (`:934-999`), `runFlingFrame` (`:1001-1027`), поля `:113-147`; добавить `pxToRows()`; удалить `FLING_VELOCITY_SCALE`; `captureCurrentFlingVelocity` (`:883`) — убрать деление на scale; `clampFlingVelocity`/`mMaxCombinedFlingVelocity` (`:383`, `:921-925`) — системный max.
2. **tail-cut**: в `runFlingFrame`, константа `TAIL_ROWS_PER_SEC=4`.
3. **follow-text во время fling**: `onScreenUpdated` (`:600-646`) — компенсация под `!isFlingActive()`; расширить `ScrollFollowTextTest`.
4. **колесо**: `onGenericMotionEvent` (`:1070-1078`) — px+remainder; общий `mScrollRemainder`.
5. **alt-buffer rate clamp**: в `runFlingFrame` для delta/alt ветки.
6. **(опция, отдельным коммитом) EdgeEffect glow**: поля, `onPull` в `onScroll`, `onAbsorb` в `runFlingFrame`, `draw`, `onRelease` в `onUp`/`onCancel`; переключатель настройки, дефолт OFF (уважаем решение `0fab152e`).
7. **(опция) инерция колеса**: `onGenericMotionEvent` → `accelerateFling` с калиброванным импульсом, дефолт OFF.

Порядок важен: 1 → 2 → 3 обязательно одной поставкой (ядро и два его следствия), 4–7 независимы.

---

## 8. Тестирование и метрики «ощущения»

**Юнит-тесты (JVM, рядом с `ScrollFollowTextTest`):**
- квантование: эталонная реализация `pxToRows`/`remainder`-цепочки против наивной; инвариант «сумма отрендеренных строк == trunc(сумма px / rowHeight)» на случайных последовательностях дельт (property-based стиль);
- `startFling`-подобная логика на поддельном scroller'е: дистанция в px совпадает с `SplineOverScroller`-эталоном (AOSP-код детерминирован) ±1 строка;
- follow-text × fling: компенсация отключена при активном fling, включена после (расширение `ScrollFollowTextTest`).

**Инструментальные (эмулятор/устройство):**
- реплей записанного `MotionEvent`-потока (adb `input swipe` недостаточен — нужен `MotionEvent.obtain`+`dispatchTouchEvent` в тесте): свайп 3000 px/s → дистанция пролёта в px совпадает с системным `ScrollView` на том же событии ±10%;
- остановка касанием: DOWN во время fling → `mTopRow` заморожен в тот же кадр (лог через `onScreenUpdated`);
- край: fling в верх → последняя строка истории видна, ни одного «отскока» (массив `mTopRow` по кадрам монотонен).

**Ручной чек-лист (POCO X5 Pro, LineageOS):**
1. Один и тот же свайп в терминале и в системном списке — пролёт визуально одинаковый (±полэкрана на резком свайпе — допустимо, квантование грубит дистанцию к строке).
2. Медленный drag — нет рывков, строка щёлкается ровно когда палец прошёл её высоту.
3. Касание летящего текста — мгновенный стоп, без «докатки».
4. Повторный свайп по летящему тексту — ускорение, а не перезапуск.
5. Конец fling — нет редеющего «тик-тик-тик» (tail-cut).
6. `yes` во время fling вверх — без джиттера; после остановки fling follow-text работает.
7. Тачпад/мышь: дробные дельты копятся, медленный скролл колесом не скачет по 3 строки рывками.

**Отладочный оверлей (временный, за `if (DEBUG)`):** текст поверх — `v px/s`, `строк/с`, `currY px`, `mTopRow`, `remainder`. Снимается перед мерджем.

---

## 9. Отклонённые альтернативы

- **Субглифовая прокрутка (пиксельный сдвиг рендера).** Вне scope по требованию; к тому же ломает dirty-rect оптимизации рендера (`e5e2031d`, `8dc8040f` в истории) и резкость глифов.
- **Физика в строках (текущее) с подгонкой `FLING_VELOCITY_SCALE`.** Нелинейность `SplineOverScroller.distance(v)` делает любую константу верной только в узком диапазоне скоростей; зависимость дистанции от размера шрифта. См. §2.
- **Rubber-band/bounce в строках.** Смещение контента за край целыми строками выглядит как баг (прыжок на строку и обратно), а не как bounce; Android с ICS использует glow, а не смещение. Коммит `0fab152e` это уже осознал (`OVER_SCROLL_NEVER`).
- **Своя реализация экспоненциального затухания вместо `OverScroller`.** Бессмысленно на Android: `OverScroller` — ровно та физика, которую мы копируем, бесплатно и с VSync-таймингом.
- **Инкрементальное (delta) маппингование кадров для обычного буфера.** Накапливает ошибку квантования; абсолютное `newRow - mTopRow` — безошибочно. Delta остаётся только там, где абсолютной позиции нет (mouseTracking).

---

## 10. Риски и открытые вопросы

1. **Дистанция fling vs квантование:** системная дистанция квантуется к строке (±1). На мелком шрифте (много строк) незаметно; на крупном — шаг дискретизации дистанции грубее. Не лечится без субглифа; компенсируется тем, что *скорость и торможение* точные — глаз это считывает сильнее, чем ±1 строку в финальной позиции.
2. **`getScaledVerticalScrollFactor()` на колесе** может дать менее «круглые» значения, чем привычные 3 строки/тик — вкусовщина, решается пунктом §5.6 (вариант `3×rowHeight` через remainder).
3. **Совместимость с интерактивным скроллбаром форка:** thumb-drag пишет `mTopRow` напрямую и гасит fling (`:1169`, `:1212`) — конфликтов нет, но после px-рефакторинга прогнать ручной тест drag thumb'а → fling пальцем сразу после.
4. **alt-buffer приложения** (less): rate clamp 8/кадр — эмпирика, возможно поднять до 12 на быстрых устройствах.
5. **Glow-опция:** если включать — проверить, что `EdgeEffect` рисуется поверх собственного фона терминала (canvas-трансляции в `dispatchDraw`), и что он не конфликтует с `OVER_SCROLL_NEVER`-привычкой пользователей; поэтому — за настройкой.

---

*Документ описывает целевой дизайн и план. Код не изменён. Привязка: `TerminalView.java` @ `0fab152e`, `GestureAndScaleRecognizer.java`, `TerminalRenderer.java`, `TermuxTerminalViewClient.java`.*
