# Аудит оптимальности базовых систем приложения

Аудит проведён 2026-09-09 по четырём подсистемам, которые **не** были покрыты
предыдущими разборами (`tab-switching-performance-analysis.md`,
`message-history-performance-analysis.md`, `session-state-restore-analysis.md`,
`extra-keys-optimization-audit.md`):

| Подсистема | Файлы |
|---|---|
| Ядро эмулятора | `terminal-emulator/…/terminal/*.java` (2628 стр. парсера + буфер + сессия) |
| Рендеринг | `terminal-view/…/view/*.java` (2660 стр. `TerminalView`, 450 стр. `TerminalRenderer`) |
| Файловый I/O, бэкап, установка | `FileUtils` (2043), `TermuxBackupUtils` (659), `TermuxInstaller` (2107), `TermuxSettingsBackupUtils`, `TermuxDocumentsProvider` |
| Старт и жизненный цикл | `TermuxApplication`, `TermuxActivity` (4018), `TermuxService`, `ExecutionCommand`/`AppShell`, `Logger`, `TermuxShellEnvironment` |

Каждая позиция проверена чтением исходника; там, где утверждение не подтвердилось,
оно не вошло в отчёт.

---

## Сводная таблица

| # | Приоритет | Где | Суть | Эффект | Риск |
|---|---|---|---|---|---|
| A1 | **P0** | `TerminalRow` | «Липкий» флаг `mHasNonOneWidthOrSurrogateChars` → запись в строку деградирует до O(columns) на символ и не восстанавливается | до 10–50× на CJK/эмодзи-выводе | средний |
| A2 | **P0** | `TerminalBuffer` | `scrollDownOneLine()` → `markAllDirty()` обесценивает dirty-tracking на сплошном выводе | 5–20× на отрисовке | высокий |
| A3 | **P0** | `TerminalBuffer` | `resize()` жадно аллоцирует 2000–50000 `TerminalRow` (1.8–45 МБ) при повороте | −десятки МБ мусора, −GC-фриз | низкий |
| B1 | **P0** | `TerminalView`/`TextSelectionCursorController` | Рендер выделения + `ActionMode.invalidate()` внутри `onDraw()` на каждом кадре | −5 тяжёлых framework-вызовов/кадр | средний |
| B2 | **P0** | `TerminalView` | `setContentDescription(getText())` на каждое обновление экрана при включённой a11y | −1 МБ/с мусора | низкий |
| B3 | **P0** | `TerminalRenderer` | `float[0x10000]` (256 КБ) + 128 `measureText` на каждый шаг зума, на каждый `TerminalView` | −256 КБ на инстанс, без GC на зуме | средний |
| C1 | **P0** | `TermuxBackupUtils` | `GZIPOutputStream` без буферизации → записи по 512 Б в SAF-поток | ~4 млн syscall на 2 ГБ → −30–60× | минимальный |
| C2 | **P0** | `TermuxBackupUtils` | `logDirState/sizeOf` — полный рекурсивный stat-обход `$FILES` до 4 раз за restore без проверки уровня лога | минуты на 50k файлов | низкий |
| D1 | **P0** | `Logger` | Форматирование строки + полная нарезка `logExtendedMessage` **до** проверки уровня логирования | −десятки аллокаций на `onStartCommand` | низкий |
| D2 | **P0** | `TermuxApplication` | Сборка env + 2 записи на диск + 4 Binder-вызова синхронно в `Application.onCreate` | −30…80 мс холодного старта | средний |
| C3 | P1 | `TermuxInstaller` | Прогресс: `Handler.post` + пересборка Notification на каждый zip entry | 15–20 тыс. постов → ≤100 | низкий |
| C4 | P1 | `TermuxInstaller` | `getCanonicalPath()` (realpath) ×2 на каждый zip entry + рефлексия `getUnixMode` без кэша | −десятки тыс. syscall + −30 тыс. исключений | средний |
| C5 | P1 | `TermuxDocumentsProvider` | ~6 syscall + 2 `File` на строку листинга, `parent.canWrite()` внутри цикла | 6→1 syscall на файл | низкий |
| C6 | P1 | `TermuxInstaller` | `patchPrefixInDirectory`: 4 stat + полное чтение файла + 3 копии строки на файл | −75 % syscall, −50–80 % времени | средний |
| B4 | P1 | `TerminalRenderer` | 5 сеттеров `Paint` на каждый run на каждый кадр (до 7500 вызовов/кадр) | −80–95 % вызовов | низкий |
| B5 | P1 | `TextSelectionCursorController` | `getValidCurX()` собирает строку строки на каждое `ACTION_MOVE` | −1 строка/событие | средний |
| D3 | P1 | `TermuxInstaller` | `isBootstrapInstalled()` перечисляет `$PREFIX/bin` на главном потоке, без кэша | −5…25 мс на старт | низкий |
| D4 | P1 | `AppShell` | Новый поток на команду ×3, `waitFor()` без таймаута | утечка потоков/FD | средний |
| D5 | P1 | `TermuxService` | SHA-1 по многомегабайтному `proot-static` под `synchronized` на каждой сессии | −10…50 мс на создание сессии | низкий |
| D6 | P1 | `SystemEventReceiver` | `writeEnvironmentToFile` (Binder + reflection + диск) в `onReceive` на главном потоке | ANR-риск | низкий |

---

## A. Ядро эмулятора (`terminal-emulator`)

### A1 (P0). Запись в строку деградирует до O(columns) на символ, и флаг «липкий» на всю строку

`TerminalRow.java:172`, `:186`, `:192`, `:201`; вызывается из `TerminalEmulator.java:2496`.

Быстрый путь `TerminalRow.java:160-168` пишет `mText[columnToSet] = (char) codePoint` за O(1),
но работает **только** пока `mHasNonOneWidthOrSurrogateChars == false`. Как только в строку
попадает wide-символ, суррогатная пара (эмодзи) или даже один combining-акцент
(`newCodePointDisplayWidth != 1` → `:162`), флаг взводится и **не сбрасывается** нигде,
кроме `clear()` (`:148`).

После этого каждый `setChar` выполняет три линейных сканирования `mText` с нуля:
- `wideDisplayCharacterStartingAt(columnToSet - 1)` — `:130-142`;
- `findStartOfColumn(columnToSet)` — `:92-128`;
- `findStartOfColumn(columnToSet + oldCodePointDisplayWidth)` — `:192`;
- плюс `WcWidth.zeroWidthCharsCount(...)` для combining — `:201`.

Заполнение строки из N столбцов становится O(N²): при N=80 это ~200–300 чтений массива
на символ вместо одного. Деградация перманентна для всех строк, где хоть раз встретился
wide/ combining-символ — включая строки скроллбэка, пока они не переиспользованы через
`clear()`.

**Предложение.** Разделить флаги (wide/surrogate ломает индексацию, combining — нет) и
завести кэш последней позиции для последовательной записи (подавляющий вывод идёт
слева направо):

```java
boolean mHasWideOrSurrogateChars;   // требует findStartOfColumn
boolean mHasCombiningChars;         // только zeroWidthCharsCount
private int mCachedColumn = -1, mCachedCharIndex = -1;
```

Дополнительно: передавать уже посчитанную ширину из `emitCodePoint`
(`TerminalEmulator.java:2462`) в `TerminalRow.setChar(column, codePoint, style, knownWidth)`,
чтобы `WcWidth.width` не вызывался второй раз на тот же символ (`TerminalRow.java:158`).

**Эффект:** для чистого ASCII — 0. Для CJK/эмодзи-вывода — до 10–50× на заполнении экрана.
**Риск:** средний/высокий — инвалидация кэша обязательна при `clear()`, `copyInterval()` и
сдвигах `System.arraycopy` (`:224-234`, `:243-269`); нужны модульные тесты на wide/combining.

### A2 (P0). `markAllDirty()` при каждом скролле обесценивает dirty-tracking

`TerminalBuffer.java:444` (`scrollDownOneLine`), потребитель — `TerminalView.java:829-838`.

Механизм частичной перерисовки (`markRowDirty`, `TerminalBuffer.java:36-70`) корректен,
но `scrollDownOneLine()` первой строкой вызывает `markAllDirty()`. В
`TerminalView.repaintAfterUpdate()` это даёт `fullRepaint = true` → `invalidate()` всего экрана.

При `cat large_file` / `yes` / логах сборки скролл идёт на каждой строке: вместо одной новой
строки (80 ячеек) перерисовывается `mRows × mColumns` (24×80 = 1920). Реальная стоимость
кадра растёт в ~24×, и потолком throughput становится рендерер, а не парсер.

**Предложение.** Экспортировать дельту скролла вместо «всё грязное»:

```java
private int mPendingScrollLines = 0;   // сброс в clearDirtyState()
public int consumePendingScroll() { int s = mPendingScrollLines; mPendingScrollLines = 0; return s; }
```

В `scrollDownOneLine` — `mPendingScrollLines++` вместо `markAllDirty()`, если
`topMargin == 0 && bottomMargin == mScreenRows`. На стороне view — сдвинуть готовый кадр
на N строк и дорисовать только новые (минимум — сузить `invalidate(l, t, r, b)`).

**Эффект:** 5–20× на отрисовке при сплошном выводе.
**Риск:** высокий — синхронные правки в `terminal-view`, работа с частичным скроллом,
выделением и `mTopRow`. Делать отдельным изменением.

### A3 (P0). `resize()` жадно аллоцирует весь скроллбэк

`TerminalBuffer.java:292-294` (сравните с ленивым конструктором `:84`).

```java
mLines = new TerminalRow[newTotalRows];
for (int i = 0; i < newTotalRows; i++) mLines[i] = new TerminalRow(newColumns, currentStyle);
```

`newTotalRows` — это размер транскрипта: `DEFAULT = 2000`, `MAX = 50000`
(`TerminalEmulator.java:146-148`). Один `TerminalRow` на 80 колонок — это `char[120]` (240 Б)
+ `long[80]` (640 Б) + заголовок ≈ 900 Б. Итого **~1.8 МБ мусора за один поворот экрана**
(или ~45 МБ при 50000), плюс GC-пауза. При этом конструктор (`:84`) и
`allocateFullLineIfNecessary()` (`:509`) строки не аллоцируют — поведение непоследовательное.

**Предложение.** Оставить `null`-ы и аллоцировать лениво, как в конструкторе; живые строки
нужны только первые `mScreenRows`.
**Эффект:** устранение десятков МБ аллокаций и GC-фриза на повороте/смене шрифта.
**Риск:** низкий (проверить `:546`, где `mLines[externalToInternalRow(y)]` читается без аллокации).

### A4 (P1). Парсинг вывода целиком на UI-потоке

`TerminalSession.java:366-372`. Поток-читатель (`:134-149`) только кладёт байты в `ByteQueue`;
разбор, мутация буфера и `markRowDirty` идут в `Handler` того потока, где создан
`TerminalSession` — то есть UI. На `cat huge_file` UI-поток занят разбором мегабайт.

Архитектурный долг: перенос требует блокировки, потому что рендерер читает
`TerminalBuffer` без синхронизации (см. комментарий `TerminalView.java:808-819`).
Выносить можно только вместе с двойной буферизацией (rendering snapshot) или RCU.

### A5 (P1). Прочие находки ядра

- **`Character.getType(codePoint)` на каждый не-ASCII символ** — `TerminalEmulator.java:529-533`.
  Нужен только для `UNASSIGNED`/`SURROGATE`. Суррогаты отсекать арифметикой
  (`0xD800..0xDFFF`), для unassigned — ленивый BMP-кэш `byte[0x10000]` по образцу
  `WcWidth.BMP_WIDTH_CACHE` (`WcWidth.java:25`).
- **`selectGraphicRendition()`** — if-else цепочка на ~25 сравнений (`:1879-1987`).
  Заменить на `switch (code)` → `tableswitch`. SGR — самая частая последовательность.
- **`setCursorStyle()`**: `Arrays.asList(...).contains(...)` (`:443`) — аллокация `List`
  + автобоксинг на каждый вызов; заменить на `int[]` + цикл.
- **`paste()`**: два `String.replaceAll` (`:2602-2604`) — компиляция regex на каждую вставку;
  вынести в `static final Pattern`.
- **Три потока на сессию** (`:134`, `:151`, `:167`): InputReader + OutputWriter + Waiter.
  Waiter блокирует поток на `JNI.waitFor` (`termux.c:208`) ради `waitpid` всё время жизни
  сессии. При 10 сессиях — 30 потоков. Объединить reader+waiter через `Os.poll()`.
- **`mStyle` как `long[columns]`** (`TerminalRow.java:57`) — 8 байт/ячейку, 640 Б на строку.
  Радикально: индекс в палитре стилей (большинство строк используют 1–3 стиля) → −70 %
  памяти скроллбэка. Риск высокий (публичное поле).

---

## B. Рендеринг (`terminal-view`)

### B1 (P0). Рендер выделения + `ActionMode.invalidate()` внутри `onDraw()`

`TerminalView.java:1965-1968` → `TextSelectionCursorController.java:88-97`
(`mActionMode.invalidate()` на `:95`).

`repaintAfterUpdate()` принудительно включает полный repaint, если идёт выделение
(`TerminalView.java:829-833`). Итого: пока пользователь держит выделение и в терминал идёт
вывод, **каждый кадр** выполняет:

- 2× `getLocationInWindow()` (`TextSelectionHandleView.java:188, 200`);
- 2× `isPositionVisible()` → `parent.getChildVisibleRect()` (`:280-283`);
- 2× `PopupWindow.update(x, y, w, h)` (`:192`) — полноценный relayout окна через WindowManager;
- 1× `ActionMode.invalidate()` — пересборка меню CAB + reposition тулбара.

Всё это синхронно, в теле `onDraw()`, на UI-потоке. `PopupWindow.update()` и
`ActionMode.invalidate()` на порядок дороже самого `render()`.

**Предложение.** Кэшировать `(mSelX1, mSelY1, mSelX2, mSelY2, topRow)` и выходить, если
ничего не изменилось; `invalidate()` меню вызывать только тогда же. `renderTextSelection()`
вынести из `onDraw()` в `onScreenUpdated()`/`postOnAnimation`.
**Риск:** средний — сбрасывать кэш в `show()`/`hide()` (`:52-85`) и `updatePosition()` (`:334`).

### B2 (P0). `getText()` + `setContentDescription()` на каждом обновлении экрана

`TerminalView.java:793` — `if (mAccessibilityEnabled) setContentDescription(getText());`

`getText()` (`:2081-2083`) → `getSelectedText(0, mTopRow, mColumns, mTopRow + mRows)`
(`TerminalBuffer.java:109-144`): для каждого видимого ряда `StringBuilder`, проход по
`char[]`, `findStartOfColumn`, сканирование последнего печатаемого символа. Результат —
строка `rows × columns` (при 50×200 ≈ 20 КБ) + копия из `StringBuilder`. Затем
`setContentDescription()` шлёт a11y-событие.

При потоковом выводе это ~60 раз в секунду → **>1 МБ/с мусора** и постоянные GC, плюс
a11y-событие на кадр. Деградация есть всегда, когда TalkBack просто включён.

**Предложение:** троттлинг 1 раз/с (или только при изменении экрана):

```java
private long mLastA11yDescriptionMs;
private static final long A11Y_INTERVAL_MS = 1000L;
private void updateContentDescriptionIfNeeded() {
    if (!mAccessibilityEnabled) return;
    long now = SystemClock.uptimeMillis();
    if (now - mLastA11yDescriptionMs < A11Y_INTERVAL_MS) return;
    mLastA11yDescriptionMs = now;
    setContentDescription(getText());
}
```
**Риск:** низкий.

### B3 (P0). 256 КБ + 128 `measureText` на каждый инстанс рендерера

`TerminalRenderer.java:44` — `private final float[] bmpMeasures = new float[0x10000];` (262 144 Б);
конструктор `:88-92` — 128 × `mTextPaint.measureText(...)`.

`TerminalView.java:929`/`:934` создают новый `TerminalRenderer` при каждом
`setTextSize()` → на **каждый шаг pinch-зума** (`onScale` `:450-456` → `changeFontSize()` →
`TermuxTerminalViewClient.java:239-246`). Каждый шаг: аллокация 256 КБ (>large-object
threshold →几乎 гарантированный GC), 128 нативных измерений, запись preference. Плюс каждый
`TerminalView` в ViewPager2 держит свой массив.

**Предложение.** `static` кэш `float[]` по ключу `(typeface, textSize)` через
`LinkedHashMap(4, 0.75f, true)` с `removeEldestEntry`, переиспользовать между инстансами.
**Риск:** средний — ключ должен учитывать и шрифт, и размер (метрики от цветовой схемы не зависят).

### B4 (P1). Пять сеттеров `Paint` на каждый run на каждый кадр

`TerminalRenderer.java:430-434`. При цветном выводе (`ls --color`, подсветка синтаксиса,
`htop`) — десятки run'ов на строку: 50 × 30 = 1500 run'ов × 5 сеттеров = 7500 нативных
вызовов на кадр. Часть сеттеров (`setTextSkewX`, `setFakeBoldText`) сбрасывает
resolved-состояние Skia-кисти.

**Предложение:** кэш последнего применённого состояния, применять только изменения
(`mLastForeColor`, `mLastBold`, …), сброс в начале `render()`.
**Эффект:** −80–95 % вызовов. **Риск:** низкий.

### B5 (P1). `getValidCurX()` собирает строку целой строки на каждое `ACTION_MOVE`

`TextSelectionCursorController.java:337-338` — `screen.getSelectedText(0, cy, cx, cy)`
внутри `updatePosition()` (`:331`), который вызывается из `ACTION_MOVE`
(`TextSelectionHandleView.java:320-328`) и завершается полным `invalidate()` (`:334`).

**Предложение:** читать напрямую `TerminalRow.mText` (класс публичный), прерываясь на
нужной колонке, без `StringBuilder`/`String`. **Риск:** средний — сохранить семантику
wide/combining.

### B6 (P1). `doScroll()` вызывает `invalidate()` внутри цикла

`TerminalView.java:1305-1321`. На каждой итерации: `awakenScrollBars()` (всегда `false`,
полосы отключены на `:575`) → `invalidate()`. Для PageUp/PageDown (`:1828`) N ≈ `mRows` ≈ 50
лишних пар вызовов. В режиме mouse-tracking каждая итерация дополнительно аллоцирует
`new int[]{column, row}` (`:966`).

**Предложение:** один `invalidate()` после цикла; `getColumnAndRow(e, rel, int[] out)`
в переиспользуемый `mScratch2`. **Риск:** низкий.

### B7 (P2). Прочее

- `drawScrollbar()` аллоцирует `RectF` на кадр (`:1407`) и дважды считает range
  (`:2038` и `:1388`) → переиспользуемое поле `mThumbRect`.
- Dirty-region = bounding box (`:1957-1958`): два непересекающихся invalidate дают
  объединение → лишние строки. Копить `first/last` dirty-строки явно.
- Полная перерисовка на каждом кадре флинга (`:1213` → `:829`) — альтернатива со
  scroll-блиттингом даёт −60…90 % работы рендерера, но риск высокий.
- `onKeyDown`/`onKeyUp` инвалидируют весь вид (`:1668`, `:1854`) там, где достаточно
  `invalidateCursorCell()`.

---

## C. Файловый I/O, бэкап, установка

### C1 (P0). `GZIPOutputStream` без буферизации

`TermuxBackupUtils.java:573-581`:

```java
try (OutputStream gz = new java.util.zip.GZIPOutputStream(finalOut); ... ) {
    byte[] buf = new byte[32768];
    while ((n = p.read(buf)) > 0) { o.write(buf, 0, n); ... }
```

`GZIPOutputStream` использует внутренний буфер `DeflaterOutputStream.buf` размером
**512 байт** и пишет в `out` не более 512 Б за раз. `finalOut` получен из
`ContentResolver.openOutputStream(uri)` (`TermuxBackupService.java:394`), т.е. это
Binder/SAF-транзакция на каждые полкилобайта. На 2 ГБ бэкапа — **порядка 4 млн syscall**.
При этом рядом (`:451`, `:609`) буфер 32 КБ уже есть, а в `:557` — 4 КБ.

**Предложение:**

```java
try (OutputStream gz = new GZIPOutputStream(new BufferedOutputStream(finalOut, 1 << 16))) { ... }
// для GB-масштаба: Deflater(Deflater.BEST_SPEED, true)
```
**Эффект:** в 30–60 раз меньше syscall; на SAF-бэкапе 1.5–3× (ещё ~2× даёт level 1).
**Риск:** минимальный.

### C2 (P0). `logDirState/sizeOf` — полный stat-обход `$FILES` до 4 раз за restore

`TermuxBackupUtils.java:321-354`; вызовы `:393, :416, :421, :491` и ещё два в
`rollbackRestore` (`:522, :530`).

`logDirState` делает `listFiles()`, затем для **каждого** потомка — рекурсивный `sizeOf(c)`
с `isFile()` + `listFiles()` на каждом узле, т.е. ~2 syscall на файл. Вызывается без
проверки `Logger.isLoggable`, до и после tar. Для префикса из 50 000 файлов это
4 × ~100 000 syscall = минуты на холодном кэше, причём **до** начала распаковки.

**Предложение:** обернуть в `if (Logger.getLogLevel() <= Logger.LOG_LEVEL_DEBUG)`, а
`sizeOf` заменить на один `du -sb` (как уже сделано в `runDuSize`). **Риск:** низкий.

### C3 (P1). Прогресс установки бутстрапа — пост в UI на каждый zip entry

`TermuxInstaller.java:1345-1347`, `:1456-1459` → `BootstrapDownloadService.java:286-290`
→ `setState()` `:513-542`, который собирает и публикует Notification (`:535-539`).

При 15–20 тыс. entry — столько же `Handler.post`, `Notification.Builder.build()` и
`nm.notify()` (Binder). Главный поток залипает, экран установки фризится.

**Предложение:** публиковать только при смене целого процента:

```java
int pct = 10 + (int)(80L * doneEntries / totalEntries);
if (pct != lastPct) { lastPct = pct; listener.onProgress(stage, pct); }
```
**Эффект:** с тысяч постов до ≤100. **Риск:** низкий.

### C4 (P1). realpath ×2 + некэшируемая рефлексия на каждый zip entry

- `TermuxInstaller.java:1664-1672` (`safeChildFile`): два `File.getCanonicalPath()`
  (realpath = lstat каждого компонента пути) на entry, причём `base.getCanonicalPath()`
  пересчитывается каждый раз при постоянном `base`. Вызовы: `:1228, :1336, :1442`.
- `TermuxInstaller.java:1707-1713`: `ZipEntry.class.getMethod("getUnixMode")` — полное
  сканирование таблицы методов, без кэша; при отсутствии метода (на современных Android
  его нет) — `NoSuchMethodException` с `fillInStackTrace` **дважды на entry** (`:1266`, `:1687`).

**Предложение:** вычислить `basePath` один раз вне цикла; `Method` зарезолвить в static-поле
с флагом `sResolved`, при отсутствии возвращать 0 без исключений.
**Эффект:** −десятки тысяч syscall и −30 000 исключений на установку. **Риск:** средний
(сохранить защиту от path traversal — лексическая нормализация + `getCanonicalPath`
как финальная страховка для симлинков).

### C5 (P1). `TermuxDocumentsProvider.includeFile` — 6 syscall на строку

`TermuxDocumentsProvider.java:238-265`: `isDirectory()` (247), `canWrite()` (248/249),
`file.getParentFile().canWrite()` (252 — один parent на все строки!), `getMimeType()` →
повторный `isDirectory()` (255→217), `length()` (261), `lastModified()` (263).

**Предложение:** `parent.canWrite()` вынести в `queryChildDocuments`; использовать один
`Os.lstat(path)` и брать из `StructStat` тип, размер и mtime.
**Эффект:** 6→1 syscall на строку. **Риск:** низкий.

### C6 (P1). `patchPrefixInDirectory`/`patchFile` — 4 stat + полное чтение + 3 копии строки

`TermuxInstaller.java:640-704`. На файл: `isSymlink` (lstat) → `isDirectory` (stat) →
`isFile` (stat) → `length()` (stat). Затем файл читается целиком, для текстовых —
`new String(content, UTF_8)` и **три** `replacePathPrefix` (`:686-690`), каждый из которых
аллоцирует `StringBuilder(text.length()+64)` и новый `String`, затем `.getBytes(UTF_8)`.
Итого ~4 полные копии содержимого на файл, для всего `$PREFIX`, если пакет ≠ `com.termux`.

**Предложение:** один `Os.lstat` вместо трёх проверок; быстрый отказ по байтам
(`indexOfBytes(content, oldPrefixBytes) < 0` → пропустить файл) до декодирования в `String`.
**Эффект:** −75 % syscall, −50–80 % времени патчинга. **Риск:** средний.

### C7 (P2). Прочее

- `FileUtils.normalizePath` (`:102-113`) — три `replaceAll` (компиляция `Pattern` на вызов),
  вызывается из `isPathInDirPaths:174` для каждого элемента списка → `static final Pattern`.
- `FileUtils.isValidPermissionString` (`:1978-1981`) — `Pattern.compile` на вызов.
- `FileAttributes.get` (`:80-97`) — `new File(...).getAbsolutePath()` на каждый вызов;
  `FileTypes.java:89-97` использует `ErrnoException` + `e.getMessage().contains("ENOENT")`
  как control flow в горячих циклах.
- `FileUtils.nonIgnoredSubFileExists` (`:267-296`) — O(n·m) с `fileExists()` (lstat) во
  вложенном цикле → `HashSet` + строковая проверка без syscall.
- `TermuxBackupUtils.checkTarHealth` (`:75, :282, :381`) — fork/exec `tar --version`
  2–3 раза за операцию → кэшировать результат.
- `TermuxInstaller.debugLog` (`:71-78`) — `DateFormat.getDateTimeInstance` + `new FileWriter`
  (open/write/close) на каждый вызов; конкатенации для `Logger.logDebug` на каждый файл
  префикса (`:661, :674`) вне зависимости от уровня лога.
- `Sha256.hexOfFile` — `String.format("%02x", b)` на байт (`:32-38`), чтение без
  `BufferedInputStream`. Perf-эффект ~0, править для единообразия.
- `TermuxSettingsBackupUtils.java:610-618` — `readLine` читает `in.read()` по одному байту.

---

## D. Старт и жизненный цикл

### D1 (P0). `Logger`: форматирование строки выполняется до проверки уровня

`Logger.java:56-67` (`logMessage`), `:69-101` (`logExtendedMessage`).

Проверка `CURRENT_LOG_LEVEL` находится **внутри** `logMessage`, т.е. после того, как
вызывающий уже собрал строку. `logExtendedMessage` **полностью** выполняет цикл нарезки
(`:81-94`: `ArrayList`, `substring`, `lastIndexOf`) и только потом для каждого куска
вызывает `logMessage`, который может всё выбросить.

Горячие места:
- `TermuxService.java:140` — `"Intent Received:\n" + IntentUtils.getIntentString(intent)`:
  полная сериализация интента на каждом `onStartCommand` при дефолтном уровне `NORMAL` (`:30`).
- `RunCommandService.java:66`, `SystemEventReceiver.java:35`, `TermuxOpenReceiver.java:42-43`.
- `TermuxShellEnvironment.java:100, 109, 274, 283, 289, 296, 312` — на пути создания сессии.

**Предложение:** `Logger.isLoggable(int logPriority)` + первая строка
`if (!isLoggable(logLevel)) return;` в `logExtendedMessage`; тяжёлые вызовы обернуть
проверкой на месте. **Риск:** низкий.

### D2 (P0). Холодный старт: env + диск синхронно в `Application.onCreate`

`TermuxApplication.java:84` → `TermuxShellEnvironment.java:132-158` →
`TermuxAppShellEnvironment.java:89-151`. `writeEnvironmentToFile()` на главном потоке старта:

- 2 Binder-вызова в `PackageManager` (`:100`, `:102`);
- `getTermuxAppPID` (`:112`) → `activityManager.getRunningAppProcesses()` (Binder + перебор
  всех процессов системы, `PackageUtils.java:651-665`);
- `getSigningCertificateSHA256DigestForPackage` (`:156`) — PM + SHA-256 по сертификату;
- `SELinuxUtils.getContext()/getFileContext()` (`:138-139`) — `Class.forName` +
  `getDeclaredMethod` + reflective invoke, дважды (`SELinuxUtils.java:26-42, 78-94`);
- затем `writeTextToFile` + `moveRegularFile` (`:147-154`) — две синхронные записи на диск.

**Предложение:** вынести в фоновый executor; shell читает `termux.env` не на первой
миллисекунде. Для плагинов, читающих env сразу после `BOOT_COMPLETED`, — `CountDownLatch`.
**Эффект:** −30…80 мс холодного старта. **Риск:** средний.

### D3 (P0/P1). Прочее по старту

- **`SystemEventReceiver.onReceive`** (`app/.../event/SystemEventReceiver.java:58-66`)
  выполняет тот же `writeEnvironmentToFile` на главном потоке; ресивер висит на
  `PACKAGE_ADDED/REMOVED/REPLACED` (`:78-85`) → `goAsync()` + executor + `result.finish()`.
- **`SharedPreferences` до `super.onCreate()`** — `TermuxActivity.java:573-574`, `:624`.
  Первый `getSharedPreferences` = синхронное чтение XML с диска + парсинг; плюс
  `createPackageContext` (Binder) в конструкторе `TermuxAppSharedPreferences` (`:29-32`).
  Перенести ниже `setContentView`/прогрев в `Application`. −10…40 мс TTID.
- **`isBootstrapInstalled()`** (`TermuxInstaller.java:833-851`) — `binDir.list()`
  (полный readdir на сотни/тысячи записей) на главном потоке, без кэша, несколько раз за
  старт (`TermuxActivity.java:606`, `RunCommandService.java:83`). Кэш на 5 с или проверка
  одного известного бинарника.
- **`prepareNixSessionLocked`** (`TermuxService.java:580-586` → `TermuxInstaller.java:226-277`)
  — SHA-1 по многомегабайтному `proot-static` при каждом `createTermuxSession`, под
  `synchronized` на сервисе (блокирует `updateNotification`, `getTermuxSessionsSize`).
  Кэш по `(mtime, size)`.
- **`TermuxService.onDestroy`** (`:177`) — рекурсивная очистка `$TMPDIR` синхронно.
- **`AppShell`** (`:146-156`, `:212`) — 3 непереиспользуемых потока + 3 FD на команду,
  пула нет, `waitFor()` без таймаута → общий `ThreadPoolExecutor` + `waitFor(timeout)`.
- **`MODE_MULTI_PROCESS`** (`TermuxAppSharedPreferences.java:31` →
  `SharedPreferenceUtils.java:52-54`) — заставляет `ContextImpl` делать
  `startReloadIfChangedUnexpectedly()` (stat файла) при каждом обращении, а с API 23 флаг
  устарел и игнорируется: платим за поведение, которого нет.
- **`createPackageContext` 5–6 раз за старт** — статический кэш контекста в `TermuxUtils`.
- **`Logger.showToast`** (`Logger.java:398-402`) — `new Handler(Looper.getMainLooper())`
  на каждый вызов → static-поле.

---

## Дефекты (баги, приводящие к лишней работе или некорректности)

1. **`termux.c:180`** — `(*env)->ReleaseStringUTFChars(env, cmd, cmd_cwd)` освобождает не ту
   строку (должно быть `cwd`). Некорректная пара Get/Release.
2. **`TerminalRow.setChar` бросает `IllegalArgumentException`** при wide-символе в последней
   колонке (`TerminalRow.java:256`) — исключение улетает через `TerminalEmulator.append()`
   в UI-поток.
3. **Курсор-блинкер не останавливается при detach** — `onDetachedFromWindow()`
   (`TerminalView.java:2587-2603`) не вызывает `stopTerminalCursorBlinker()`; тикер
   (`:2451-2467`) продолжает инвалидировать откреплённую страницу ViewPager2.
4. **Блинкер перепланирует себя в `finally`** (`:2463-2466`) — любое исключение внутри
   `run()` даёт бесконечный тикер, который нельзя снять.
5. **Асимметрия `getPointY()`/`getCursorY()`** — захардкоженная `-40`
   (`TerminalView.java:2086-2091` vs `:2093-2102`): `getPointX/getCursorX` симметричны,
   `getPointY/getCursorY` — нет. Даёт систематическое смещение примерно на
   `40 / lineSpacing` строк при размере шрифта, отличном от «расчётного».
6. **`invalidateCursorCell()` деградирует в полный `invalidate()`** при выделении или
   протяжке скроллбара (`:899`) — в этих режимах курсор не нужен, достаточно `return`.
7. **`checkChangedOrientation`** — `SystemClock.currentThreadTimeMillis()`
   (`TextSelectionHandleView.java:224`) возвращает CPU-time потока, а не реальное время;
   троттлинг «не чаще 50 мс» работает случайно → `uptimeMillis()`.
8. **NPE в `TermuxDocumentsProvider.java:93`** — `parent.listFiles()` может вернуть `null`.
9. **Неограниченный `StringBuilder` для stderr** (`TermuxBackupUtils.java:432-441`,
   `:554-563`) — `tar -x` на 50 000 файлов с предупреждениями способен набрать десятки МБ
   в памяти; ограничить 64 КБ.
10. **Гонка «wipe после старта tar»** (`TermuxBackupUtils.java:413-421`) — tar стартует с
    `-C filesDir`, и только потом вызывается `clearDirectoryContents(filesDir)`.
11. **`patchFile` перезаписывает файл на месте** (`TermuxInstaller.java:701-703`) — при
    прерывании файл остаётся битым; писать во временный + `renameOrMove`.
12. **Двойная регистрация ресивера** — `SystemEventReceiver.registerPackageUpdateEvents`
    (`TermuxService.java:127`) использует статический `mInstance` (`:21-30`); без парного
    `onDestroy` (`:185`) `onReceive` будет вызываться дважды.
13. **Диагностика эмулятора собирает строки при `LOG_ESCAPE_SEQUENCES = false`** —
    `TerminalEmulator.java:2305-2313`, `:2301` (ещё и `String.format("%04x")`), `:2267`.
    Константа проверяется **внутри** `logError` (`:2316`), т.е. аргумент уже собран.
14. **`mTitleStack` — `java.util.Stack`** (`TerminalEmulator.java:134`) — синхронизированный
    `Vector`; заменить на `ArrayDeque`.
15. **`JNI.setPtyUTF8Mode`** реализован в C (`termux.c:199-206`), но не объявлен в
    `JNI.java` — мёртвый код.
16. **Противоречие в потоковых инвариантах**: `TerminalSession.java:72` и `:237` захватывают
    поток в момент конструирования; комментарий `TerminalView.java:808-819` утверждает, что
    сессия всегда создаётся на main, а `TermuxTerminalSessionActivityClient.java:257` —
    что колбэки приходят с output-потока. Оба не могут быть верны одновременно; открытый
    TODO о гонках курсора — `TerminalEmulator.java:2492-2494`.

---

## Что уже хорошо оптимизировано (не трогать)

**Эмулятор**
- `WcWidth`: BMP-кэш `byte[0x10000]` (`:25-33`) + бинарный поиск (`:509-527`). Lookup = одно
  обращение к массиву, без боксинга и `HashMap`.
- `ByteQueue`: корректный `wait/notify`, без busy-wait; 64 КБ на выход, backpressure через
  блокирующую запись, notify только на переходах пусто↔не пусто.
- Скролл через **кольцевой буфер ссылок** (`blockCopyLinesDown`, `TerminalBuffer.java:420-432`)
  — копируются только ссылки, не символьные данные.
- Ноль аллокаций в `processByte`/`processCodePoint`/`emitCodePoint`: нет `new`,
  `String.valueOf`, `String.format`, regex, боксинга; `switch` только по `int`.
- Переиспользование состояния парсера: `int[] mArgs`, `StringBuilder` с `setLength(0)`,
  `byte[4]` для UTF-8.
- Coalescing уведомлений через `Choreographer` (`TerminalSession.java:236-250`) — максимум
  один `onTextChanged` на кадр.
- Чтение PTY: `FileInputStream` с буфером 4096, приёмный буфер 64 КБ; никаких JNI-вызовов
  на байт.

**Рендеринг**
- Run-length батчинг с переиспользуемыми массивами — ноль аллокаций в цикле строк/колонок.
- Два прохода (фон → текст) со схлопыванием соседних фонов одного цвета в один `drawRect`;
  цвета резолвятся один раз на run.
- Partial repaint по dirty-rect (`TerminalRenderer.java:133-172`).
- Курсор-блинкер инвалидирует **одну клетку**, а не весь вид (`:2461`), rate 100–2000 мс.
- Один `canvas.save()/restore()` на кадр, нет `clipRect` на строку.
- `MotionEvent` корректно obtain/recycle; флинг самозавершающийся с `forceFinished(true)`.
- Кэш `measureText` на code point с пред-прогревом ASCII (цена — см. B3).

**I/O и установка**
- Backup/restore через нативный `tar` вместо Java-обходчика — ноль per-file overhead в Java.
- Буфер 32 КБ в `dataPump` + троттлинг прогресса по 1 МБ.
- `du -sb` на отдельном потоке параллельно tar + bounded `waitFor` + слив stderr в отдельном
  треде (deadlock по переполнению pipe устранён).
- Троттлинг нотификаций (250 мс при скачивании, по «ключу процента» при бэкапе); опрос
  диалога 300 мс, а не per-file посты.
- `renameOrMove` через `Os.rename`; `copyZipEntryToFile` с Buffered* и 64 КБ;
  `ZipFile` (random access) вместо `ZipInputStream` + валидация на zip-bomb.
- `BootstrapDownloadService`: `ExecutorService` с `shutdownNow()`, 64 КБ буферы, `StatFs`
  один раз, кэш по sha256.

**Старт**
- `TermuxAppSharedProperties.loadTermuxPropertiesFromDisk()` — **no-op**: чтение
  `termux.properties` с диска полностью убрано, конфиг в SharedPreferences.
- `resolveTerminalTypeface()` кэширует `Typeface` по `(mtime, size)`.
- Гейты по ключу схемы в `ensureColorSchemeLoaded`/`checkForFontAndColors`.
- `prefs()` возвращает закэшированное поле; `commit()` в горячих путях нет (везде `apply()`).
- Один процесс в манифесте, без `largeHeap`; оба `ContentProvider.onCreate` тривиальны.
- Критических статических утечек Activity/Context **не найдено** (`sInstance` обнуляется в
  `onDestroy`, клиент снимается в `onUnbind`).

**Не найдено:** `switch` по `String`, `Integer.parseInt` в цикле разбора, автобоксинг в
`HashMap` на горячем пути, `synchronized` в `TerminalBuffer`/`TerminalRow`/`TerminalEmulator`,
`StaticLayout`/`getTextWidths` в draw-пути, `setLayerType`.

---

## План внедрения (по убыванию выгода/риск)

1. **Быстрые победы (низкий риск, заметный эффект):** C1 (BufferedOutputStream в gzip),
   C2 (гейт `logDirState` уровнем лога), D1 (`Logger.isLoggable`), B2 (троттлинг a11y),
   C3/C4 (троттлинг прогресса + кэш realpath/Method), A3 (ленивая аллокация в `resize`).
2. **Средние:** B1 (кэш рендера выделения), B4 (кэш сеттеров Paint), B3 (static-кэш
   `bmpMeasures`), C5/C6 (lstat-батчинг), D2/D3 (асинхронный env, кэш bootstrap-флага),
   D6 (`goAsync` в ресивере).
3. **Долгие/рискованные (отдельные изменения с тестами):** A1 (разделение флагов
   `TerminalRow`), A2 (скролл-дельта вместо `markAllDirty`), A4 (вынос парсинга с UI-потока
   вместе с двойной буферизацией).
4. **Дефекты как separate PR:** №1 (`termux.c` Release), №3/4 (блинкер), №5 (асимметрия
   `getPointY`/`getCursorY`), №8 (NPE в DocumentsProvider), №9 (ограничение stderr).

Ожидаемый суммарный эффект первой волны: −30…80 мс холодного старта, ускорение бэкапа
в 1.5–3×, устранение GC-фризов при сплошном выводе на 120-Гц и на CJK/эмодзи-контенте,
снятие ANR-риска при установке пакетов.

---

## Статус внедрения (2026-09-09)

Собрано и установлено: `app/build/outputs/apk/release/termux-app_apt-android-7-release_arm64-v8a.apk`
(**BUILD SUCCESSFUL**, 0 ошибок компиляции; только lint-warning `UnspecifiedImmutableFlag` в
`TermuxService`). Установлено на device **192.168.129.248:5555** (`adb install -r`) — **Success**.

| Группа | Внедрено | Примечание |
|---|---|---|
| B (рендер) | B1, B2, B3, B4, B5, B6, B7 | полностью |
| A (ядро) | A1, A3, A5-миск | A5-миск: BMP-type кэш `Character.getType`, `setCursorStyle` цикл вместо `Arrays.asList`, `paste` через `Pattern`, `ArrayDeque` вместо `Stack`, `LOG_ESCAPE_SEQUENCES`-гейт, SGR `switch`. **A2 и A4 — не внедрены** (см. ниже) |
| C (I/O) | C1–C6 | выполнено ранее |
| D (старт) | D1–D6 | выполнено ранее |
| Дефекты | №1, №3, №4, №5, №6, №7 | №8, №9 — ранее; №15 — не трогали |

Дефекты, внедрённые в этой сессии:
- **№1** `termux.c:180`: `ReleaseStringUTFChars(env, cmd, cmd_cwd)` → `(env, cwd, cmd_cwd)`.
- **№3** `TerminalView.onDetachedFromWindow()`: добавлен `stopTerminalCursorBlinker()`.
- **№4** блинкер: `finally` больше не ре-постит безусловно — гейт по
  `TerminalEmulator.isCursorBlinkingEnabled()` (добавлен геттер). Раньше `stopTerminalCursorBlinker()`
  не мог остановить уже запущенную итерацию раннабла (она сама себя ре-армила в `finally`).
- **№5** `getCursorY(float y)`: убрана магия `- 40` (была асимметрия с `getPointY`).
- **№6** `invalidateCursorCell()`: при `isSelectingText() || mScrollbarDragging` — `return` вместо
  полного `invalidate()` (дёргало dirty-rect оптимизацию на каждый тик блинкера).
- **№7** `TextSelectionHandleView`: `SystemClock.currentThreadTimeMillis()` → `uptimeMillis()`.

Попутно исправлено: дублирующаяся сигнатура `getLogLevel()` в `termux-shared/.../Logger.java`
(добавлена в прошлой сессии рядом с `setLogLevel`, ломала компиляцию) — удалён дубликат.

Не внедрено (по плану — рискованные/долгие, требуют отдельных тестов):
- **A2** — `scrollDownOneLine()` вместо `markAllDirty()` считать дельту и бить только изменившиеся
  строки. Высокий риск поведенческого регресса отрисовки, нужен контрольный тест.
- **A4** — вынос парсинга escape-последовательностей с UI-потока + двойная буферизация. Архитектурно тяжело.
- **Дефект №15** — удаление мёртвого кода `JNI.setPtyUTF8Mode`. Риск пересборки native-слоя; не трогали.
