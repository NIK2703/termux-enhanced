# GameActivity и полностью нативный контроллер терминала: оценка возможности

**Ветка:** `feature/foot-terminal-core`
**Версия:** `0.118.0+enhanced.22`
**Дата:** 2026-10-30
**Статус:** исследование, архитектурных изменений нет

## 1. Краткий вывод

`GameActivity` технически можно добавить в проект, но он **не является заменой
текущему окну терминала и сам по себе не ускоряет рендеринг**. Это Activity-хост
для одного `SurfaceView` с нативным окном, вводом и lifecycle. Всё, что сейчас
делает `TerminalView` через Android `Canvas`, он не заменяет: для выигрыша нужен
отдельный нативный растеризатор, шрифтовой стек и нативный ввод.

Для текущей архитектуры Termux Enhanced перенос в `GameActivity` с полностью
нативным контроллером — это не optimisation, а новый terminal UI/движок. Объём
работы и риск несопоставимы с ожидаемым выигрышем, который к тому же не измерен.
Рекомендация: **не принимать GameActivity как новую архитектуру сейчас**.
Сначала закрыть измерения (этап 15.7), lifecycle `nativeDestroy` (15.6) и
дешёвые горячие точки. Если профиль всё же покажет, что основная цена — это
UI-thread Canvas/JNI, то сначала рассматривать `SurfaceView` с нативным
рендерером внутри существующей Activity, а не полный перенос в `GameActivity`.

## 2. Что именно проверено

- `androidx.games:games-activity` в репозитории не используется; зависимость
  отсутствует.
- В Maven доступна версия `4.4.2`; AAR имеет `minSdkVersion=21`, то есть
  формально совместим с текущим `minSdk 21`.
- Java-класс называется `com.google.androidgamesdk.GameActivity`, наследует
  `androidx.appcompat.app.AppCompatActivity`; базовый layout — `FrameLayout` с
  одним `InputEnabledSurfaceView`, который устанавливается через
  `setContentView()`.
- `nativeHandle` и `nativeHandleOwner` в `GameActivity` — **статические**
  поля. Это один нативный instance на процесс/Activity-класс, а не view,
  которую можно создать на каждую страницу pager'а.
- GameActivity предоставляет нативные колбэки `onNativeWindowCreated`,
  `onNativeWindowResized`, `onNativeWindowDestroyed`, touch/key events,
  `onTextInputEvent`, insets и software keyboard visibility. Это хороший
  фундамент для игры, но это не готовый терминальный контроллер.
- Текущий проект — обычная `AppCompatActivity` (`TermuxActivity`) с
  `ViewPager2`, где **каждая страница — отдельный `TerminalView`** и отдельная
  `TerminalSession`. Есть также `TermuxBubbleActivity`, наследующая
  `TermuxActivity`.
- Текущий рендер: `TerminalView.onDraw()` → `TerminalRenderer.render()` →
  JNI `fillRenderCommands()` → команды foot → Android `Canvas`, который рисует
  текст в retained `Bitmap` (`ARGB_8888`), после чего Bitmap копируется на
  экран. Шрифты, emoji, CJK, fallback и shaping делает Android/Skia.
- `pixman_image_create_bits*()` и `fcft_*()` в текущем адаптере — заглушки,
  возвращающие `NULL`/пустые значения. Настоящего нативного framebuffer'а и
  нативного шрифтового стека в сборке сейчас нет.

## 3. Что даёт и чего не даёт GameActivity

| Возможность | GameActivity | Комментарий |
|---|---|---|
| `ANativeWindow` / `SurfaceView` | да | Но EGL/GLES/Vulkan и растеризатор нужно писать самим |
| Нативный lifecycle окна | да | `onNativeWindowCreated/Changed/Destroyed` |
| Touch/key events в C | да | Общий `InputEvent`-подобный API, без терминальной семантики |
| Text input / IME | частично | `GameTextInput` со state/composing; это не `BaseInputConnection` Termux |
| Нативные шрифты и текст | **нет** | Нужны FreeType/HarfBuzz/fcft/pixman или собственный glyph atlas |
| Tabs / ViewPager2 / swipe | **нет** | Это Java UI; GameActivity — одна Activity с одной SurfaceView |
| Extra keys, toolbar, text input panel | **нет** | Остаются Java-оверлеями или переписываются нативно |
| Selection handles / ActionMode | **нет** | Нужен Java-слой или новая нативная реализация |
| Clipboard / accessibility | **нет** | Нужны JNI-мосты или Java-оверлеи |
| Transparent wallpaper / insets / cutout | частично | SurfaceView и окно нужно настраивать отдельно; текущая логика завязана на View/insets |
| Несколько окон (main + bubble) | **ограничено** | Статический `nativeHandle` не рассчитан на несколько live-инстансов |

Ключевой момент: **GameActivity не содержит рендерера**. Если оставить текущий
`Canvas`/`Bitmap`-путь, перенос в GameActivity даст примерно те же команды,
тот же JNI и ту же цену, но добавит новый lifecycle, загрузку библиотеки и
ограничения на UI. Если делать нативный рендер, то 80% работы — это не
GameActivity, а шрифты, растеризация, ввод и UI.

## 4. Несовпадение с текущей архитектурой

### 4.1. Одна Activity против N TerminalView

Сейчас `TerminalPagerAdapter` держит `TerminalView` на каждую сессию; видны
соседние страницы при свайпе, каждая имеет свой emulator, dirty state,
selection и IME-фокус. `GameActivity` предоставляет **одну** SurfaceView и один
статический native handle. Чтобы сохранить pager, есть только два пути:

1. Оставить Java `ViewPager2`, а внутри страниц использовать не GameActivity, а
   обычные `SurfaceView`/`TextureView`. Тогда GameActivity не нужен.
2. Рисовать весь pager, tabs и переходы нативно в одной SurfaceView. Это уже
   новый UI-движок, а не перенос окна терминала.

### 4.2. SurfaceView и анимация страниц

`SurfaceView` — отдельный compositor-layer. При трансляции/клиппинге страницы
`ViewPager2` он может отставать от View-иерархии, не обрезаться как обычный View
и давать разрывы на свайпе. Для анимируемых страниц обычно нужен `TextureView`,
но это дополнительная GPU-копия и не лучший путь для минимальной задержки. В
полноэкранном GameActivity этой проблемы нет, но тогда исчезают tabs и
подглядывание соседней сессии.

### 4.3. Ввод и IME

Терминальный ввод — это не просто «текстовое поле»:

- `KeyHandler` (373 строки) и Android `KeyCharacterMap`/termcap-таблицы;
- hardware key events, Ctrl/Alt/Shift/Fn, one-shot extra keys;
- `BaseInputConnection` с composing text, `commitText`, удалением, codepoint;
- mouse tracking, wheel, fling, long press, selection handles;
- clipboard, context menu, URL selection, accessibility.

`GameTextInput` даёт state с текстом и selection/composing, но не терминальную
кодировку. Чтобы получить полный контроль, нужно либо портировать эту логику в
C, либо оставить Java-слой и пробрасывать события в native. Второй вариант
дешевле, но это уже не «полностью нативный контроллер».

### 4.4. Жизненный цикл и несколько окон

`TermuxActivity` — не единственное окно: `TermuxBubbleActivity` наследует её и
может сосуществовать с основным окном. Статический `nativeHandle` GameActivity
плохо ложится на несколько Activity/окон. Кроме того, `TerminalSession`
принадлежит `TermuxService`, а не Activity; перенос окна не должен менять
владение сессией и transcript'ом. Это отдельный риск для закрытия/пересоздания
Activity.

## 5. Что нужно для действительно нативного рендера

### 5.1. Шрифты и shaping

Сейчас качество текста — сильная сторона Android `Canvas`: системный fallback,
CJK, emoji, combining marks, лигатуры, hinting. Нативный путь требует:

- FreeType для растеризации;
- HarfBuzz для shaping (если нужны лигатуры/комплексные script'ы);
- fallback-цепочку шрифтов и выбор шрифта по codepoint;
- поддержку color emoji (COLR/CPAL, CBDT и т.п.);
- кэш глифов и glyph atlas для производительности.

В NDK 29 есть `AFont`/`AFontMatcher`/`ASystemFontIterator`, но они появились в
API 29, дают доступ к системным шрифтам и не заменяют shaping/растеризацию. При
`minSdk 21` всё равно нужен запасной путь. Это не «подключить одну библиотеку»,
а отдельный шрифтовой слой с большим набором визуальных регрессий.

### 5.2. Пиксельный backend

Варианты:

- **CPU-растеризация** (pixman + FreeType/fcft) в буфер, затем
  `ANativeWindow`/EGL. Ближе к upstream foot, но на телефоне это может быть
  медленнее и энергозатратнее, чем аппаратный Skia Canvas, особенно на 120 Гц.
- **GPU glyph atlas** (FreeType/HarfBuzz + GLES/Vulkan). Потенциально быстрее,
  но это уже небольшой текстовый движок.
- **Java Canvas на нативном потоке** через JNI. Не даёт «полной нативности» и
  сохраняет текущие ограничения; GameActivity для этого не нужен.

Кроме того, полный upstream `grid_render()` ожидает buffer/shm/worker/commit
модель Wayland. Текущий адаптер специально обходит `grid_render()` и вызывает
`render_row()` через Canvas-sink. Для нативного backend'а нужно либо строить
совместимый слой, либо писать новый render loop.

### 5.3. Presentation

`ANativeWindow_lock()` с software-буфером — самый простой путь, но он может
дать полнокадровую запись/копирование и не лучшую задержку. EGL/GLES с
`eglSwapBuffersWithDamageKHR` или Vulkan требует отдельного кода, синхронизации
и обработки resize/context loss. GameActivity не предоставляет ни EGL, ни
композитор — только окно.

## 6. Оценка стоимости и риска

Оценки ориентировочные, для одного разработчика, уже знакомого с проектом.

| Вариант | Что делается | Срок | Риск | Совместимость с текущим UI |
|---|---|---:|---|---|
| **A. Полный GameActivity + нативный контроллер** | Новый Activity, нативный render loop, шрифты, ввод, tabs/pager, selection, IME, accessibility | MVP 4–6 недель; parity 3–6 месяцев | Очень высокий | Низкая: фактически новый terminal UI |
| **B. SurfaceView/TextureView + нативный рендер внутри текущей Activity** | Java UI и ввод остаются; меняется только пиксельный путь | 4–8 недель + шрифты 3–6 недель | Высокий | Средняя: pager/transforms требуют TextureView или отдельного слоя |
| **C. Продолжать Canvas-путь** | 15.6, 15.7, точечные hot spots | 1–3 недели | Низкий | Полная |
| **D. Отдельный full-screen «performance mode»** | GameActivity только для одной сессии, без tabs/toolbar | 6–10 недель | Высокий | Низкая, но можно сохранить обычный режим |

«MVP» здесь — одна сессия, базовый ввод и рендер. Он не включает pager, bubble,
extra keys, выделение, clipboard, accessibility и все настройки. Для продукта
нужен именно parity, а не MVP.

## 7. Ожидаемый выигрыш

Сам по себе GameActivity не уменьшает:
- число JNI-вызовов на кадр;
- объём command-потока;
- стоимость Android `Canvas`/Skia;
- стоимость парсинга VT на main thread;
- число полных перерисовок.

Нативный рендер может убрать:
- сериализацию команд и их разбор в Java;
- вызовы `Canvas.drawText`/`drawRect` на UI-thread;
- двойную работу retained `Bitmap` + blit (если сразу рисовать в window);
- часть задержки ввода, если ввод и рендер уходят в нативный поток.

Но он добавляет:
- CPU-растеризацию текста (если не GPU atlas);
- собственную работу с шрифтами/fallback;
- новый render thread и синхронизацию с VT-состоянием;
- риск, что программный растеризатор будет **медленнее** аппаратного Canvas.

Реалистичный порядок выигрыша: единицы миллисекунд на кадр в худшем случае
полноэкранного streaming/TUI и почти ноль на sparse update. Это не гарантирует
улучшения p95. Если текущий p95 уже укладывается в vsync и jank не связан с
Canvas/JNI, нативный rewrite не даст заметного эффекта.

**Gate для решения:** начинать нативный путь имеет смысл, только если stage
15.7 покажет, что на проблемных сценариях (streaming, `htop`/OpenCode TUI,
wheel/fling, selection) p95 кадра превышает бюджет vsync, а доля
Canvas+JNI+command-parse в кадре велика (ориентир — несколько миллисекунд или
больше). Иначе это архитектурные изменения без измеримой цели.

## 8. Что делать вместо этого

1. **15.7 — счётчики и p95.** Зафиксировать full/partial frames, emitted/skipped
   rows, scroll/blit ops, JNI render time и frame time на реальных сценариях.
   Без этого нельзя выбирать архитектуру.
2. **15.6 — `nativeDestroy`.** Отдельная корректность lifecycle и памяти, не
   зависит от GameActivity.
3. **Дешёвые hot spots:**
   - native-authoritative `forceDraw` вместо `forceDraw = !anyDirty`;
   - убрать лишний `nativeCommitAllRows` на полном кадре;
   - проверить, не дороже ли retained `Bitmap` + blit прямого рисования в
     hardware Canvas для частичных кадров;
   - сократить полные перерисовки при selection и pager-clip, где это возможно.
4. **Если нативный рендер всё же нужен:** сначала сделать 2–4-дневный spike:
   отдельная debug-activity/модуль, `androidx.games:games-activity:4.4.2`,
   `GameActivity_onCreate`, `ANativeWindow`, заливка цветом и один кадр текста.
   Проверить сборку, загрузку библиотеки, lifecycle, размер APK, input latency.
   Это даст ответ на вопрос «интегрируется ли хост», не трогая production.
5. **Предпочтительный нативный путь, если spike успешен:** не полный
   GameActivity-контроллер, а `SurfaceView`/`TextureView` с нативным рендером
   внутри существующей Activity, с Java-вводом и Java-UI. Это сохраняет tabs,
   bubble, extra keys и accessibility; GameActivity не даёт для них ничего.

## 9. Итоговая рекомендация

- **Технически:** перенос возможен, но только как новый полноэкранный
  terminal-only режим или после замены всего View-слоя. Вставить GameActivity
  в текущий `ViewPager2` без потери tabs/swipe нельзя.
- **Практически:** не начинать с GameActivity. Это не узкое место и не
  ускоритель; это другой host. Сначала нужны измерения и независимые
  улучшения.
- **Если цель — максимально низкая задержка любой ценой:** делать нативный
  `SurfaceView` + GPU glyph atlas внутри текущей Activity, сохраняя Java-ввод;
  оценивать после spike и только при подтверждённом профиле.
- **Если цель — стабильность и функции Termux:** завершать Canvas-путь
  (15.6/15.7 и точечные оптимизации). Он уже использует upstream damage/scroll
  и даёт хорошее качество текста без нового шрифтового стека.

## 10. Ссылки на код

- `terminal-view/src/main/java/com/termux/view/TerminalView.java` — 3500 строк,
  onDraw, IME, touch, selection, scrollbar, pager integration.
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java` — 1096
  строк, retained `Bitmap`, command replay, scroll blit.
- `terminal-emulator/src/main/foot/src/jni.c` — JNI, `AndroidBitmap_lockPixels`,
  render command arrays.
- `terminal-emulator/src/main/foot/src/render_model.c` — frame/damage/row
  commands, upstream `render_row()` adapter.
- `terminal-emulator/src/main/foot/src/stubs.c` — pixman/fcft заглушки.
- `app/src/main/java/com/termux/app/TermuxActivity.java` — 5584 строки, Activity,
  pager, extra keys, IME, insets.
- `app/src/main/java/com/termux/app/terminal/TerminalPagerAdapter.java` — N
  TerminalView, по одной на сессию.
- `app/src/main/java/com/termux/app/terminal/SessionPagerManager.java` — 1641
  строка, ViewPager2 и сессии.
- `app/src/main/java/com/termux/app/bubble/TermuxBubbleActivity.java` — второе
  окно, наследующее TermuxActivity.
- `docs/foot-render-optimization-plan.md` — этапы 15.6/15.7 и текущий план.
