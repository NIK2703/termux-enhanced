# Аудит: плейсхолдер правого свайпа — что происходит на каждый пиксель пальца

**Дата:** 2026-09-12
**Предмет:** путь кадра жеста «свайп вправо с последней вкладки» — `SessionPagerManager.onPageScrolled()`
→ `TerminalPagerAdapter.setPlaceholderScrollOffset()` → `DirectoryPickerController` → `DirectoryPickerView.onDraw()`
**Метод:** только чтение кода + сверка с исходниками AOSP (`android-36.1` в SDK: `View.java`; HWUI:
`libs/hwui/pipeline/skia/RenderNodeDrawable.cpp`). Замеры на устройстве в этом проходе не делались.
**Связанные документы:** `docs/directory-picker-swipe-design.md` (проект фичи),
`docs/render-optimality-audit-5.md` (общий аудит отрисовки),
`docs/placeholder-picker-swipe-optimality-audit-2.md` (второй проход).
**Статус внедрения (2026-09-12):** §4.1, §4.2, §4.3, §4.5, §4.6, §4.8 — внедрены.
§4.7 (гейт оверлея по `reveal`) — **откачен**: внедрялся, проверен на устройстве и снят, потому что
ломал обратную анимацию отменённого свайпа (см. аудит 2, §3.3 и §7.2). §4.4 (коалесинг по кадру) —
**откачен** по той же проверке: задержка на кадр разъезжается со settle-анимацией ViewPager2.
Обе конструкции расписаны в аудите 2 и помечены там как непригодные в этой форме.

---

## 1. Короткий вывод

1. **Пер-кадровый путь уже хорошо вычищен.** Аллокаций в кадре нет ни одной, все дешёвые сеттеры
   имеют early-out, инвалидация коалесится фреймворком в одну отрисовку на кадр. Половину
   «очевидных» оптимизаций здесь делать не надо — они уже сделаны (см. §3).
2. **Главная находка — не в пикере, а в затухании оверлея.** `parent.setAlpha(reveal)` на контейнере
   `terminal_placeholder_hint_container` — это `FrameLayout` с дефолтным
   `hasOverlappingRendering() == true`, а значит **при `alpha < 1` весь кадр рисуется через
   offscreen-буфер размером во всю страницу** (`saveLayerAlpha`, HWUI `setViewProperties`). Это
   происходит на **каждом** кадре, пока `0 < reveal < 1`, то есть почти весь жест. Лечится одним
   атрибутом в layout (§4.1).
3. **Вторая находка — `requestLayout()` на каждый кадр** из-за плавающей кнопки
   (`setFloatingButtonMarginEnd` → `setLayoutParams`), причём срабатывает она именно на этом жесте:
   плейсхолдер всегда рапортует «скроллбара нет» (`mEmulator == null`), поэтому маргин двух страниц
   почти всегда различается (§4.2).
4. **Третья — строки пикера перерисовываются целиком каждый кадр, хотя содержимое не меняется:**
   экранная позиция подписи постоянна весь жест, растёт только видимый срез. Доказуемо из геометрии
   (§4.3). Абсолютная экономия небольшая — это не лечение jank, а устранение заведомо лишней работы.
5. Расхождение документации и кода: `directory-picker-swipe-design.md` §4.9 всё ещё описывает рампу
   как `alpha = min(1, offset·1.5)`; в коде (и по факту) — линейная `min(1, pageOffset)` (§6).

---

## 2. Полная трассировка: что исполняется на каждый пиксель движения

Точка входа — `ViewPager2.OnPageChangeCallback.onPageScrolled()` (`SessionPagerManager.java:380`).
Она вызывается **на каждый скролл внутреннего RecyclerView**, то есть на каждый `ACTION_MOVE`
(частота сэмплирования тача — до 120–240 Гц, то есть **чаще кадра**) и на каждый кадр анимации
settle. Всё нижеперечисленное повторяется на каждый такой вызов; инвалидации коалесит фреймворк,
поэтому отрисовка всё равно одна на кадр.

| # | Что | Где | Стоимость на вызов |
|---|---|---|---|
| 1 | `mLastScrollPos/Offset = …` + `withTabsController(mScrollForwarder)` | `:386-388` | поле + вызов закешированного non-capturing `Consumer` (`:148-149`), без боксинга |
| 2 | `TermuxSessionTabsController.onPageScrolled()` | `TermuxSessionTabsController.java:1126` | 2× `blendColors` (int-математика) |
| 3 | `mTabsScroll.scrollTo(targetScrollX, 0)` | `:1158` | `View.scrollTo` → `invalidateParentCaches()` + invalidate: пере-запись display list полосы вкладок и её родителя |
| 4 | `setTabBackground()` ×2 | `:1162-1165`, impl `:1244` | `GradientDrawable.setColor()` **без early-out** → `invalidateSelf()` на вкладке |
| 5 | `setVisibility()` на двух close-кнопках | `:1171-1172` | early-out внутри `View` |
| 6 | `getPagerPageView(pos)` / `(pos+1)` | `:905-906`, impl `:951` | `SparseArray.get` (без боксинга), fallback на `findViewHolder` только при промахе |
| 7 | `computeMarginEnd()` ×2 | `:921-922`, `TermuxActivity.java:2713` | `hasScrollbar()` (чтение `mEmulator`) + `getDisplayMetrics().density` |
| 8 | `mActivity.setFloatingButtonMarginEnd()` | `TermuxActivity.java:2746` | `findViewById` (обход дерева) + `setLayoutParams` → **`requestLayout()` на всю иерархию**, только если маргины различаются |
| 9 | `getPlaceholderOverlayPage()` | `TerminalPagerAdapter.java:619` | два чтения полей |
| 10 | `reveal = clamp((pos+off) − (overlayPage−1), 0, 1)` | `:406-408` | арифметика |
| 11 | `picker.setRevealedFraction(reveal)` | `DirectoryPickerController.java:140` | early-out при том же значении; иначе `getWidth()` + `setRevealedWidth()` → **`invalidate()` на вьюху во всю страницу** |
| 12 | `adapter.setPlaceholderScrollOffset(reveal)` | `TerminalPagerAdapter.java:762` | `getParent()`, `getWidth()`, `setTranslationX` (свойство рендер-ноды), `setAlpha(1f)` (no-op), **`parent.setAlpha(reveal)`** (свойство рендер-ноды) |
| 13 | Гейт защёлкивания якоря | `:430-432` | четыре чтения флагов |
| 14 | Отрисовка кадра | `DirectoryPickerView.onDraw()` `:158` | до 10 `drawText` + скруглённый прямоугольник подсветки + до 9 разделителей; `TerminalView.onDraw()` несвязанной страницы = один `drawColor` |
| 15 | Layout-проход ViewPager2 | — | inherent для ViewPager2: RecyclerView раскладывает детей на каждом кадре скролла |

Отдельно от кадров, но на каждый `ACTION_MOVE` (то есть тоже «на пиксель пальца»):

* `mPickerTouchListener.onInterceptTouchEvent()` (`:202-208`): `e.getRawY()` и, если якорь защёлкнут,
  `picker.updateFinger(rawToPageY(...))` → **`mTerminalPager.getLocationOnScreen()`** + `indexAt()` +
  early-out по неизменившейся строке + `setHighlight()`.

---

## 3. Что уже оптимально (не трогать)

Фиксирую явно — чтобы эти места не «оптимизировали» повторно.

| Место | Факт |
|---|---|
| Аллокации в кадре | **Ноль.** `mScrollForwarder` — переиспользуемая non-capturing лямбда вместо `Consumer`+боксинга (`:141-149`); `mPagerLocation` (`:175`), `mRowRect` (`DirectoryPickerView.java:55`) — поля. В `onDraw` нет ни одного `new`. |
| `setRevealedFraction` / `setRevealedWidth` / `setHighlight` / `updateFinger` / `View.scrollTo` | Все с early-out по неизменившемуся значению — повторные вызовы в одном кадре бесплатны. |
| Коалесинг отрисовки | `invalidate()` в `onPageScrolled` не рисует: сколько бы MOVE ни пришло за кадр, отрисовка одна. |
| Затухание через `View.setAlpha()` | Идёт свойством рендер-ноды, **не** пере-записывает display list подсказки и списка. (Но см. §4.1 — композитинг не бесплатен.) |
| Затухание на **контейнере**, а не на каждом слое | Правильно: иначе альфы перемножились бы (`0.5·0.5`). Держать так. |
| Обрезка подписи окклюзией | `ellipsize` не вызывается, ширины меряются один раз на набор пунктов (`DirectoryPickerView.java:150`), на кадр — только сложение `textRight − labelWidth`. |
| Пикер — `Canvas`, а не список `TextView` | Нет инфляции и layout-прохода на жест; геометрия из `DirectoryPickerLayout.Result` общая для отрисовки и хит-теста. |
| `TerminalView.onDraw()` несвязанной страницы | Один `drawColor` — фон страницы бесплатен. |
| `DirectoryPickerLayout` | Чистая геометрия без Android-типов; всё placement-решение считается **один раз** на жест (`show()`). |

---

## 4. Найденные возможности

### 4.1. `saveLayer` во всю страницу на каждом кадре — из-за альфы контейнера (главное)

**Что в коде.** `TerminalPagerAdapter.setPlaceholderScrollOffset()` (`:783`):

```java
if (!mPlaceholderFadingOut) parent.setAlpha(Math.min(1f, pageOffset));
```

`parent` — это `terminal_placeholder_hint_container`, `FrameLayout` из `item_terminal_page.xml`.
То же самое делает 150-мс затухание при коммите (`fadeOutPlaceholderOverlay()`, `:831-833`).

**Почему это дорого.** Проверено по исходникам:

1. `View.hasOverlappingRendering()` возвращает **`true`** и это дефолт
   (`android/view/View.java`, AOSP: *«The default implementation returns true»*).
2. `ViewGroup` и `FrameLayout` его **не переопределяют** (проверено `javap` по `android-34/android.jar`:
   объявление есть только у `ImageView` и `TextView`) ⇒ у контейнера `getHasOverlappingRendering() == true`.
3. `View.setDisplayListProperties()` кладёт это прямо в рендер-ноду:
   `renderNode.setHasOverlappingRendering(getHasOverlappingRendering())` (`View.java:24877`).
4. HWUI `RenderNodeDrawable::setViewProperties()`:

```cpp
if (properties.getAlpha() < 1) {
    if (isLayer && !ignoreLayer) clipFlags &= ~CLIP_TO_BOUNDS;
    if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering()) || ignoreLayer) {
        *alphaMultiplier = properties.getAlpha();          // ← дёшево: альфа в пейнт каждого опа
    } else {
        // savelayer needed to create an offscreen buffer
        Rect layerBounds(0, 0, properties.getWidth(), properties.getHeight());
        if (clipFlags) { properties.getClippingRectForFlags(clipFlags, &layerBounds); clipFlags = 0; }
        canvas->saveLayerAlpha(&bounds, (int)(properties.getAlpha() * 255));   // ← offscreen во всю страницу
    }
}
```

`clipFlags` содержит `CLIP_TO_BOUNDS` (родитель — `FrameLayout` с `clipChildren=true`), а границы
контейнера — вся страница ⇒ буфер = вся страница (1080×2400 ≈ 10 МБ), плюс его очистка и обратный
композит. И это **на каждый кадр, в котором `0 < reveal < 1`**, то есть почти весь жест; при коммите —
ещё ~9 кадров затухания.

**Что делать.** Одна строка в `item_terminal_page.xml`:

```xml
<FrameLayout
    android:id="@+id/terminal_placeholder_hint_container"
    android:forceHasOverlappingRendering="false"
    ... />
```

(эквивалент в коде — `container.forceHasOverlappingRendering(false)` в `onBindViewHolder`;
атрибут/метод публичные, метод только выставляет флаг — `View.java:19712-19722`).

Тогда альфа пойдёт по ветке `*alphaMultiplier = properties.getAlpha()` и раскроется в пейнт каждого
вложенного опа через `AlphaFilterCanvas` (`drawContent()`), без offscreen-буфера.

**Что при этом меняется визуально.** Ровно там, где два опа оверлея перекрываются. Внутри контейнера
это единственное место — **подпись подсвеченной строки поверх собственной заливки строки**
(`mHistoryHighlightFill = withAlpha(mHistoryTextColor, 0x26)` — заливка полупрозрачная, текст
непрозрачный). Разница в области текста: `0.15·A(1−A)·(text − bg)`, максимум **3.75 %** контраста
при `A = 0.5`. Подсказка и список не перекрываются (подсказка живёт в полосе над списком), строки
между собой — тоже. Прочие слои рисуются по одному разу ⇒ результат идентичен.

**Проверка перед/после (когда дойдёт до устройства):** Perfetto с категориями `gfx`/`view`/`hwui` —
искать `saveLayer` / трейс-тег `alpha caused saveLayer %dx%d` и аллокации render target в кадрах
свайпа; либо A/B по `dumpsys gfxinfo <pkg> framestats` (Prepare/Draw) между сборкой с атрибутом и без.

### 4.2. `requestLayout()` на каждый кадр из-за плавающей кнопки

`updateFloatingButtonMarginForScroll()` (`:900`) интерполирует `marginEnd` между двумя страницами и
зовёт `TermuxActivity.setFloatingButtonMarginEnd()`, который делает `findViewById(...)` (обход дерева)
и `setLayoutParams(params)` — а это **`requestLayout()`, то есть полный measure+layout иерархии
Activity в этом кадре**.

Триггер — `leftMargin != rightMargin`. И тут важно, что **именно на этом жесте** условие почти всегда
истинно: `getScrollbarState()` (`TermuxActivity.java:2654`) возвращает `null`, если у страницы
`mEmulator == null`, то есть у страницы-плейсхолдера скроллбара «нет никогда» ⇒ её маргин = 6 dp,
а у соседней реальной вкладки со скроллбеком — 30 dp−2 + правый инсет. Свайп на плейсхолдер — ровно
случай «одна страница связана, другая нет».

**Что делать.** Убрать layout-проход, оставив ту же формулу:
* держать `rightMargin` на устоявшемся значении **уходящей** страницы (`leftMargin`) и применять
  интерполяцию через `setTranslationX((rightMargin − leftMargin) · positionOffset)` — это свойство
  рендер-ноды, без layout;
* в `updateFloatingButtonMargin()` (путь IDLE) обнулять `translationX` вместе с пересчётом устоявшегося
  маргина — иначе кнопка останется смещённой после отменённого свайпа (там уже есть комментарий про
  ровно этот класс ошибок);
* попутно закешировать `ImageButton` вместо `findViewById` на каждый вызов.

Единственный источник истины `computeSettledFloatingButtonMarginEnd()` при этом сохраняется — меняется
только способ применения промежуточного значения.

### 4.3. Строки пикера перерисовываются каждый кадр, хотя содержимое не меняется

**Доказательство постоянства.** Плейсхолдер вползает справа: левый край страницы на экране равен
`(1−f)·W`, где `f = reveal`. `applyReveal()` даёт `mRevealedWidth = f·W`, а `onDraw()` рисует подпись
по правому краю среза: `textRight = rowWidth − paddingH` (`DirectoryPickerView.java:171`).
Экранная позиция правого края подписи:

```
screenX = (1−f)·W + f·W − paddingH = W − paddingH        ← не зависит от f
```

То есть **подпись не двигается по экрану вообще**; с ростом `f` строка растёт влево, и меняется только
видимая часть. Правильная картинка кадра = канонический рендер (строка `[0, W]`, подпись по правому
краю `W − paddingH`) со сдвигом на `(f−1)·W`; обрезка головы подписи при этом обеспечивается
**собственными границами вьюхи** — тем же механизмом, который уже используется для усечения головы
пути (см. §4.8 проектного документа).

**Что делать (опционально).** Держать строки в `android.graphics.RenderNode` (публичный с API 29:
`beginRecording(int,int)`/`endRecording()`/`setTranslationX()`) или в дочерней вьюхе, и в
`setRevealedWidth(px)` писать только `setTranslationX(px − getWidth())`. Тогда пер-кадровая работа
сжимается до одной записи свойства; пере-запись нужна только при смене подсветки (раз на 48 dp
движения пальца) и при `setItems()`.

**Честная оценка выигрыша.** Сам по себе `onDraw` пикера — это ~10 `drawText` коротких строк, то есть
десятки микросекунд, доли процента кадра: **это не лечение jank**, а устранение заведомо лишней
работы. Делать имеет смысл, если профилирование покажет `DirectoryPickerView.onDraw` в кадре, или
если пикер будет расширен (больше строк/иконок). Требует API-развилки (`RenderNode` — API 29+, при
`minSdk 21` нужен фолбэк на текущую прямую отрисовку).

### 4.4. `onPageScrolled` исполняется чаще, чем раз в кадр

RecyclerView дёргает `onScrolled` на каждый `ACTION_MOVE`, а MOVE приходят с частотой сэмплирования
тача (до 120–240 Гц) — то есть **2–4 раза на отображаемый кадр**. Отрисовка коалесится, а вот
Java-работа из §2 (п. 2–8, 10–12) повторяется целиком. Дешёвое лечение — «токен кадра»: тяжёлые
подшаги выполнять один раз на `Choreographer`-кадр, лёгкие (запись полей) оставить как есть. Риск —
задержка визуального отклика до одного кадра; на практике незаметно, но это надо проверять глазами.

### 4.5. `measureLabels()` попадает в кадр появления меню

`DirectoryPickerController.show()` вызывается из **первого кадра раскрытия** плейсхолдера
(`SessionManager:430-437`) — в том же кадре вьюха пикера уходит из `GONE` в `VISIBLE` и её display list
записывается с нуля. Внутри `show()` → `setItems()` → `measureLabels()` меряет до 10 подписей
(`measureText` = шейпинг текста). Это единственная «тяжёлая» работа на критическом кадре.

**Что делать.** Мерять один раз при `bind()` (размер текста там же и задаётся) и при изменении
истории, а в `show()` только копировать ссылки на уже посчитанные ширины. Список ограничен
`MAX_ITEMS = 10`, так что предзамер — те же 10 вызовов, но на устоявшемся кадре, вне жеста.

### 4.6. `getLocationOnScreen()` на каждый MOVE

`rawToPageY()` (`:702`) вызывает `mTerminalPager.getLocationOnScreen()` на каждом `ACTION_MOVE`, хотя
вертикальное положение пейджера во время горизонтального свайпа не меняется. Достаточно защёлкнуть
`mPagerLocation` один раз за жест (на `ACTION_DOWN` или в момент защёлкивания якоря). Буфер уже
переиспользуется, аллокации нет — экономия только на обходе цепочки родителей.

### 4.7. Оверлей двигается и на свайпах между реальными вкладками

`getPlaceholderOverlayPage()` возвращает `>= 0`, пока плейсхолдер **взведён** — то есть пока
пользователь стоит на последней реальной вкладке. Поэтому свайп между двумя реальными вкладками
(например, 0 → 1 при двух сессиях) тоже каждый кадр считает `reveal = clamp(отрицательное) = 0` и
толкает его в `setRevealedFraction(0)` и `setPlaceholderScrollOffset(0)`. Почти всё гасится early-out
(и `hintContent == null`, если страница не связана), но `setTranslationX(0)` + `setAlpha(0)` на
`GONE`-контейнере всё равно исполняются. Лечение: гейтить ветку не по `overlayPage >= 0`, а по факту
начала раскрытия (`(position + positionOffset) > overlayPage − 1`), с запоминанием состояния, чтобы
сброс в 0 всё же происходил один раз при уходе с последней вкладки.

### 4.8. `setTabBackground()` без early-out

`TermuxSessionTabsController.setTabBackground()` (`:1244`) вызывает `GradientDrawable.setColor()`
безусловно; при неизменном цвете это `invalidateSelf()` на вкладке и её пере-запись. Добавить
`if (d.getColor() == color) return;` (`GradientDrawable.getColor()` — API 24+).

---

## 5. Инварианты, которые нельзя ломать

Перечислено, потому что все правки выше — про путь отрисовки, и каждая из них легко «задевает» одно
из этих правил:

1. **Затухание — ровно один раз.** Альфа живёт на контейнере; если переносить её куда-то ещё, вторая
   альфа на вложенном слое перемножится (`A·A`).
2. **`mPlaceholderFadingOut` владеет видимостью и альфой.** Пока флаг поднят,
   `setPlaceholderScrollOffset()` применяет только `translationX`, а `bind()` / `hide()` /
   `onViewRecycled()` не трогают `visibility`/`alpha` (см. таблицу в §4.10 проектного документа).
   Правки в §4.1 и §4.3 не должны добавить сюда третий «владелец» свойства.
3. **Порядок при коммите:** `fadeOutPlaceholderOverlay()` **до** `commitPlaceholder()`.
4. **`getItemCount()` не меняется на коммите**, `setItemAnimator(null)`, `setHasStableIds(true)`,
   id слота переживает коммит, bind — напрямую через `findViewHolderForAdapterPosition`.
5. **Единственный источник истины по геометрии** — `DirectoryPickerLayout.Result`: `indexAt()` и
   `itemIndexAt()` используют и отрисовка, и хит-тест. Любая правка отрисовки обязана читать оттуда же.
6. **Инвариант «палец вне списка»**: `listBottom = yA − gap`, `rows = min(n, ⌊availUp/rowH⌋)`,
   `rows < 1 → Mode.NONE` + центрированная подсказка. `rowH` фиксирован (48 dp).
7. **Позиция подсказки по горизонтали выводится из её центрирования** `layout_gravity="center"`
   (`TerminalPagerAdapter:771`) — вертикальный сдвиг обязан оставаться `translationY`, а не re-layout.
8. **Правки рендера проверять на release-сборке глазами** (в release нет `ENABLE_FULL_LOGGING`).

---

## 6. Дефекты документации

* `docs/directory-picker-swipe-design.md` §4.9 описывает рампу как `alpha = min(1, offset · 1.5)`.
  В коде — линейная `Math.min(1f, pageOffset)` (`TerminalPagerAdapter.java:783`). Вариант с `·1.5`
  откатывался: он сатурировал на `0.667` и ломал старт затухания с фактической альфы вытягивания.
  Документ надо привести к коду.
* Там же в §4.9 утверждение «ни одного лишнего прохода отрисовки» верно для **пере-записи** display
  list, но не для **композитинга**: см. §4.1. Формулировку стоит уточнить.

---

## 7. Порядок внедрения

| Приоритет | Пункт | Риск | Ожидаемый эффект |
|---|---|---|---|
| 1 | §4.1 `forceHasOverlappingRendering="false"` | очень низкий (одна строка; визуальная дельта ≤ 3.75 % контраста на подсвеченной строке в середине вытягивания) | убирает offscreen-буфер во всю страницу с каждого кадра жеста и из 150-мс затухания |
| 2 | §4.2 `translationX` вместо `setLayoutParams` | низкий (формула та же, добавляется сброс в IDLE) | убирает полный measure+layout Activity с каждого кадра свайпа на плейсхолдер |
| 3 | §4.5 предзамер подписей | низкий | снимает шейпинг текста с кадра появления меню |
| 4 | §4.6, §4.7, §4.8 | низкий | мелкие пер-пиксельные издержки |
| 5 | §4.3 `RenderNode`/дочерняя вьюха для строк | средний (новый путь отрисовки, API-развилка) | убирает пере-запись и перерисовку строк из кадра |
| 6 | §4.4 коалесинг по кадру | средний (задержка отклика до кадра) | убирает 2–4-кратное дублирование Java-работы |

Проверять после каждого пункта — на release-сборке, глазами (правило проекта), плюс
`dumpsys gfxinfo <pkg> framestats` до/после: интересны `Prepare`/`Draw`, а не `Total` — фон терминала
и blur вносят шум. Базовая линия для сравнения — свайп между двумя реальными вкладками (тот же код,
минус оверлей).
