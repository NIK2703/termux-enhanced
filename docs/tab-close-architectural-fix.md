# Архитектурное исправление: после закрытия вкладки остаётся экран мёртвой сессии

Статус: **исправлено и проверено на устройстве** (LineageOS `marble`, debug `com.termux.debug`).
DEBUG и RELEASE компилируются.

**Итог двух итераций.** Первая итерация (шаги 0–E + H: единый владелец активной страницы
`SessionPagerManager.mActiveIndex`, вью по сессии вместо позиции, событийная перепривязка
`onPageBound`, отказ от отложенных «догонялок») убрала все *латентные* дефекты, но сам баг
**не вылечила** — потому что настоящая причина оказалась не в состоянии приложения, а в том,
какую страницу реально раскладывает `RecyclerView`. Настоящая причина и лечение — в §3.7-бис
и §5-J; подтверждена трассой, воспроизведена и устранена на устройстве (§6).

Побочная регрессия первой итерации (мгновенное открытие новой вкладки без анимации, §3.8) —
исправлена (§5-K).

Отклонение от плана: шаг F (перенос `updateTabs()` после пейджерного синкa) **не сделан**
сознательно. После шага A `getCurrentSession()` всегда возвращает живую сессию, а финальную
подсветку всё равно ставит пейджерный синк (`setCurrentSessionForSession`), так что корректность
от порядка больше не зависит; а перенос ломает резервацию end-scroll (комментарий
`TermuxActivity:3672`) и вернул бы «двойное движение» панели при создании вкладки.

## 1. Симптом

```
1 вкладка → свайп вправо ×3 → 4 вкладки
→ переключиться на 3-ю, закрыть её   (переключение происходит)
→ закрыть следующую                  (переключения НЕ происходит)
   • на экране остаётся терминал только что закрытой сессии (видна строка "signal 9")
   • в панели вкладок активной подсвечивается соседняя вкладка
   • первая же попытка прокрутить пейджер — экран мгновенно (не плавно) заменяется
     на первую вкладку
```

`signal 9` — это `SIGKILL`, который `TerminalSession.finishIfRunning()` пишет в трансрипт при
выходе процесса. То есть **на экране физически вью уже убитой сессии** — доказательство того, что
активный `TerminalView` не был переключён.

## 2. Модель состояния: четыре независимых владельца и отсутствующий инвариант

«Какая вкладка активна» сегодня хранится в четырёх местах, которые синхронизируются вручную:

| # | Состояние | Владелец | Ключ |
|---|-----------|----------|------|
| 1 | `ViewPager2.getCurrentItem()` | ViewPager2 | позиция |
| 2 | `TermuxActivity.mTerminalView` | `setTerminalView()` (`TermuxActivity:3725`) | вью |
| 3 | подсветка вкладки | `TermuxSessionTabsController.mCurrentSessionIndex` | **позиция** (`:1099`) |
| 4 | список живых сессий | `TermuxService` | сессия |

Инвариант, который нигде не проверяется:

```
pager.getCurrentItem() == позиция(mTerminalView.getCurrentSession())
                       == позиция(подсвеченной вкладки)
                       == ЖИВАЯ сессия в списке сервиса
```

Хуже того, «активная сессия» — не состояние, а **производная от кэша вью**:

```java
// TermuxActivity:3858
public TerminalSession getCurrentSession() {
    if (mTerminalView != null) return mTerminalView.getCurrentSession();
    else return null;
}
```

Кэш не имеет проверки живости. Пока `mTerminalView` указывает на вью убитой сессии,
`getCurrentSession()` возвращает мёртвую сессию — и её потребляют `updateTabs()`
(`TermuxSessionTabsController:226`), маршрутизация ввода, IME, экстра-кнопки.

## 3. Механика: почему «книпка переключения» не нажимается

### 3.1 Путь закрытия

Кнопка `(x)` видна только на выбранной вкладке (`TermuxSessionTabsController:1086`), поэтому
закрытие всегда идёт так:

1. тап по `(x)` → `closeSession(session)` (`:624`) → `animateTabClose()` (100 мс) →
   `removeClosingTab()` (`:722`) → `mTabsContainer.removeView(tabView)` → `session.finishIfRunning()`.
2. сессия завершается → `TermuxTerminalSessionActivityClient.removeFinishedSession()` (`:1125`):
   поднимает `mTerminalPageSwitchInProgress` + `beginSessionUiChurn`,
   `int index = service.removeTermuxSession(finishedSession)` — **индекс в СТАРОМ списке**,
   `if (index >= size) index = size - 1;`, затем `termuxSessionListNotifyUpdated(index)`.
3. `TermuxActivity.termuxSessionListNotifyUpdated()` (`:3667`) — `updateTabs()` **первым**,
   затем `SessionPagerManager.termuxSessionListNotifyUpdated(preferredIndex)`.
4. `SessionPagerManager.termuxSessionListNotifyUpdated()` (`:1223`) — синк адаптера,
   `setCurrentItem(restoreIndex, false)`, `onTerminalPageSelected(activeIndex)`.

### 3.2 `setCurrentItem()` при совпадении индекса — молчаливый no-op

Подтверждено байткодом `androidx.viewpager2:viewpager2:1.1.0` (`ViewPager2.setCurrentItemInternal`):

```
if (item == mCurrentItem && mScrollEventAdapter.isIdle()) return;   // ранний выход
...
mCurrentItem = item;
notifyProgrammaticScroll(...);
```

Закрытие **не последней** вкладки по текущей политике даёт `restoreIndex == getCurrentItem()`
(`SessionPagerManager:1259-1265`: `restoreIndex = preferredIndex` — правый сосед занимает
освободившийся слот). Индекс не меняется ⇒ `setCurrentItem()` ничего не делает ⇒
**`onPageSelected()` не приходит** ⇒ вся перекладка состояния ложится на ручной вызов
`onTerminalPageSelected(activeIndex)` (`:1303`).

Закрытие **последней** вкладки меняет индекс (срабатывает кламп), `onPageSelected` приходит,
всё работает. Этим и объясняется «первое закрытие прошло, второе — нет».

### 3.3 `onTerminalPageSelected()` — три молчаливых выхода

```java
// SessionPagerManager:974
private void onTerminalPageSelected(int position) {
    ...
    if (selected == null) return;                    // (а) :983 — выход №1
    ...
    mActivity.setTerminalPageSwitchInProgress(true); // :1004 — флаг уже поднят
    ...
    TerminalView pageView = getPagerPageView(position);
    if (pageView == null) {                          // (б) :1027 — выход №2
        ... attach-листенер + post + postDelayed(300) ...
        return;                                      // mTerminalView НЕ переключён
    }
    mActivity.setTerminalPageView(pageView);         // :1091 — единственное место переключения
    withTabsController(tabs -> tabs.setCurrentSession(position)); // :1103 — подсветка ПО ПОЗИЦИИ
    mActivity.getTermuxTerminalSessionClient().onSessionPageSelected(selected); // :1111
    ...
}
```

**Ключевой дефект ветки (б): она не может восстановиться по построению.**

* `attach`-листенер не сработает: после `notifyItemRangeRemoved` страница, занявшая
  освободившийся слот, **уже присоединена** (это вью бывшего соседа), повторного
  `onChildViewAttachedToWindow()` для неё не будет.
* `post`-фолбэк проверяет `getPagerPageView(pos) != null` — ровно то условие, которое уже
  не выполнилось.
* `postDelayed(…, 300)` (`:1075`) — единственное, что реально происходит: снимает флаг
  `mTerminalPageSwitchInProgress`.

Итог: `mTerminalView` остаётся на вью убитой сессии, подсветка остаётся там, где её оставил
последний удачный вызов.

### 3.4 Подсветка вкладки — по позиции, а не по сессии

`onTerminalPageSelected` зовёт `tabs.setCurrentSession(position)` (`:1103`), а `updateTabs()`
(`:223`) вычисляет `currentSessionIndex` из `mActivity.getCurrentSession()`. После закрытия
`getCurrentSession()` — мёртвая сессия ⇒ `currentSessionIndex = -1` ⇒ `updateTabs()` снимает
подсветку со всех, а затем позиционный `setCurrentSession` подсвечивает **соседа, который
переехал в освободившийся слот**. Два разных ключа — расхождение гарантировано.

### 3.5 `getItemCount()` считает плейсхолдер

```java
// TerminalPagerAdapter:577
public int getItemCount() { return mSessions.size() + (mPlaceholderActive ? 1 : 0); }
```

А гард структурного синка сравнивает его с числом живых сессий:

```java
// SessionPagerManager:1243
int newSize = service.getTermuxSessionsSize();
if (mTerminalPagerAdapter.getItemCount() != newSize) {
    int oldSize = mTerminalPagerAdapter.getItemCount();   // :1244 — тоже с плейсхолдером
```

При активном плейсхолдере `getItemCount()` на 1 больше ⇒ сравнение врёт в обе стороны: синк
может не запуститься там, где список изменился, и запуститься там, где не изменился.
`oldSize` (`:1244`) участвует в выборе ветки «добавили вкладку» (`:1249`) — тоже врёт.

### 3.6 `mAttachedViews` — позиционный ключ и ложный авторитет

```java
// TerminalPagerAdapter:86
private final SparseArray<TerminalView> mAttachedViews = new SparseArray<>();
```

Позиционный ключ приходится вручную пересчитывать при каждом удалении
(`TerminalPagerAdapter:237-261`) — 25 строк, которые существуют только чтобы компенсировать
неправильный ключ. А `getPagerPageView()` (`:1195`) **предпочитает этот кэш** живому
RecyclerView, то есть «что на экране» определяется по карте, которая может быть устаревшей.

### 3.7 Почему «прокрутка мгновенно показывает первую вкладку»

`mCurrentItemDirty` + `updateCurrentItem()` внутри `ViewPager2.onLayout()` (подтверждено
байткодом 1.1.0) могут доставить `onPageSelected` на **следующем** проходе layout. Любое
касание пейджера вызывает layout/fling ⇒ снэп к живой странице без анимации. То есть
«само починилось при касании» — не совпадение, а тот самый отложенный `onPageSelected`,
который не пришёл синхронно.

### 3.7-бис. Настоящая причина (замерено на устройстве, трасса `PAGERDBG`)

Состояние приложения было **правильным**, а экран — нет. Трасса закрытия активной вкладки
(3 вкладки, закрываем среднюю):

```
SLNU old=3 new=2 heir=98a2fde restore=1 curBefore=1 activeBefore=1
SLNU afterSync cur=1 (setCurrentItem target was 1)     <- setCurrentItem() — молчаливый no-op
OTPS done pos=1 tv=98a2fde                             <- состояние приземлилось верно…
PAGEDUMP … |child0 pos=0 x=-1080 |child1 pos=-1 x=0 |child2 pos=1 x=1080
```

`pos=-1` — это `ViewHolder` **удалённой** страницы: он всё ещё привязан (`FLAG_REMOVED`) и стоит
ровно в середине экрана, а живая страница, на которую указывает пейджер, отодвинута вправо за
кадр. `RecyclerView` оставил мёртвый child якорем раскладки и сам это не исправил — за 3 секунды
ни одного layout-прохода, который бы его убрал.

Механика: `notifyItemRangeRemoved` помечает child removed и выставляет `mCurrentItemDirty`
(`ViewPager2$1.onChanged()`), а `ViewPager2.updateCurrentItem()` (вызывается из `onLayout` и из
IDLE-колбэка) **не трогает `mCurrentItem`** — он только рассылает `onPageSelected(position)`, если
позиция снап-вью разошлась с `mCurrentItem`. Итог: пока пейджер стоит на удаляемой странице, и
«текущий item», и якорь раскладки — мёртвые, и синхронный `setCurrentItem(restoreIndex, false)`
не может это исправить: он возвращается досрочно, потому что `restoreIndex == mCurrentItem` и
пейджер idle (байткод 1.1.0, `setCurrentItemInternal`, офсеты 52-70).

Почему «иногда работает»: в соседнем прогоне та же последовательность дала чистую раскладку
(`pos=0,1,2`, все валидные), но приземлилась на **левую** соседку — потому что `ViewPager2`
успел доставить свой `onPageSelected(1)` через 6 мс после нашего. То есть результат зависел от
того, чей `onPageSelected` окажется последним, — гонка, а не логика.

### 3.8. Регрессия первой итерации: новая вкладка открывалась мгновенно, без анимации

Шаг E заменил гард структурного синка `getItemCount() != newSize` на честное сравнение числа
**реальных** сессий (`getSessionCount()` + `sameSessions()`). Это правильно для добавления через
«+», но сломало свайп-добавление: `commitPlaceholderToSession()` создаёт сессию, сервис
синхронно дёргает `termuxSessionListNotifyUpdated()`, и старый гард **случайно** глушил этот
вызов (плейсхолдер накручивал `getItemCount()` ровно на единицу). С новым гардом синк пошёл
внутри коммита: `stopScroll()` + `setCurrentItem(restoreIndex, false)` = мгновенный прыжок,
плейсхолдер выброшен из-под селла — «открывается мгновенно, без анимации».

## 4. Архитектурный принцип

> **Сессия — идентичность. Позиция — производная от порядка списка.
> Один владелец перехода. Кэш вью инвалидируется, а не «угадывается».**

Пять правил:

1. **Один владелец.** `SessionPagerManager` единственный, кто решает «где мы приземлились».
   Остальное — чтение.
2. **Активная сессия — состояние, а не производная кэша.** Она не может быть мёртвой, потому что
   вычисляется как `service.getTermuxSession(mActiveIndex)` с валидацией индекса.
3. **Подсветка и книпка — по сессии**, позиция используется только для поиска вью.
4. **Вью — кэш, который инвалидируется.** Нет вью на активной позиции ⇒ `mTerminalView = null`,
   а не «оставим старую, вдруг пригодится». Перепривязка — по событию `onBindViewHolder`.
5. **Никаких отложенных «догонялок».** Никаких новых `postDelayed` и `post`-рекавери: восстановление
   либо синхронное, либо событийное (bind/attach). Существующий `postDelayed(300)` удаляется.

## 5. Конкретные изменения

### A. `SessionPagerManager` получает `mActiveIndex` и становится источником «активной сессии»

```java
/** Индекс подтверждённой активной страницы. Всегда валиден относительно ЖИВОГО списка. */
private int mActiveIndex = -1;

@Nullable
public TerminalSession getActiveSession() {
    TermuxService service = mActivity.getTermuxService();
    if (service == null) return null;
    int size = service.getTermuxSessionsSize();
    if (size == 0) { mActiveIndex = -1; return null; }
    if (mActiveIndex < 0 || mActiveIndex >= size) mActiveIndex = size - 1;  // самовосстановление
    TermuxSession ts = service.getTermuxSession(mActiveIndex);
    return ts != null ? ts.getTerminalSession() : null;
}
```

`TermuxActivity.getCurrentSession()` (`:3858`) делегирует сюда:

```java
@Nullable
public TerminalSession getCurrentSession() {
    return mSessionPagerManager != null ? mSessionPagerManager.getActiveSession()
                                        : (mTerminalView != null ? mTerminalView.getCurrentSession() : null);
}
```

Эффект: класс багов «активная сессия — мёртвая» исчезает целиком, вместе с расхождением
`updateTabs()`/подсветки. Это изменение с самой широкой зоной влияния — **ставить первым и
проверять отдельно**.

`mActiveIndex` пишется ровно в трёх местах: `onPageSelected()` (жест), `termuxSessionListNotifyUpdated()`
(структурный синк), `setCurrentSession()`-путь (явный переход). Всё остальное — чтение.

### B. `getPagerPageView()` → по сессии, с живым RecyclerView как авторитетом

```java
// TerminalPagerAdapter: ключ — сессия, не позиция
private final Map<TerminalSession, TerminalView> mSessionViews = new IdentityHashMap<>();
public TerminalView getViewForSession(TerminalSession s) {
    TerminalView v = mSessionViews.get(s);
    if (v != null && v.isAttachedToWindow()) return v;      // кэш валиден
    return null;
}
```

В `onBindViewHolder` (`:494`) вместо `mAttachedViews.put(position, terminalView)`:

```java
mSessionViews.put(session, terminalView);
// Событийная перепривязка активной вью — никаких post/postDelayed.
if (mOnPageBoundListener != null) mOnPageBoundListener.onPageBound(session, terminalView);
```

В `onViewRecycled` (`:518`) — `mSessionViews.remove(session)`.
**Удаляются**: весь блок сдвига ключей `:237-261`, чистка хвоста `:269-274`,
`mAttachedViews.remove(mSessions.size())` в `:217` и `:619`.

`getPagerPageView(position)` остаётся как тонкая обёртка: `сессия = service.getTermuxSession(position)`
→ `getViewForSession(сессия)`; фолбэк — `rv.findViewHolderForAdapterPosition(position)`.

### C. `onTerminalPageSelected()` — тотальная и идемпотентная

Убрать оба молчаливых выхода и ветку с задержками:

```java
private void onTerminalPageSelected(int position) {
    TermuxService service = mActivity.getTermuxService();
    if (service == null) return;

    int size = service.getTermuxSessionsSize();
    if (size == 0) {                                   // (а) вместо `return` — честный сброс
        mActiveIndex = -1;
        mActivity.setTerminalView(null);
        withTabsController(TermuxSessionTabsController::clearSelection);
        mActivity.setTerminalPageSwitchInProgress(false);
        return;
    }
    if (position < 0 || position >= size) position = Math.min(position < 0 ? 0 : size - 1, size - 1);

    TermuxSession termuxSession = service.getTermuxSession(position);
    if (termuxSession == null || termuxSession.getTerminalSession() == null) return;
    final TerminalSession selected = termuxSession.getTerminalSession();

    mActiveIndex = position;                            // владелец зафиксировал приземление

    // Уходящую сессию берём из предыдущего mActiveIndex, а не из mTerminalView —
    // к этому моменту та могла быть только что убитой.
    ... saveScrollState / saveTextInputForCurrentSession (ключ — уходящая сессия) ...

    mActivity.setTerminalPageSwitchInProgress(true);

    // Вью — кэш. Нет вью ⇒ null ⇒ маршрутизация берёт getActiveTerminalView() лениво,
    // а перепривязка придёт из onPageBound(). Старую (мёртвую) вью НЕ оставляем.
    mActivity.setTerminalView(getViewForSessionSafe(selected));   // может быть null

    withTabsController(tabs -> tabs.setCurrentSessionForSession(selected));   // по сессии
    mActivity.getTermuxTerminalSessionClient().onSessionPageSelected(selected);

    mTerminalPager.post(() -> mActivity.setTerminalPageSwitchInProgress(false));
    mActivity.consumePendingKeyboardRestoreIfReady();
}
```

Идемпотентность обязательна: ViewPager2 вправе доставить `onPageSelected` позже, из layout
(§3.7). Повторный вызов для того же индекса теперь безопасен (сохранение состояния уходящей
сессии ключуется сессией, подсветка — сессией, `mActiveIndex` пишется тем же значением).

**Удалить**: `:1027-1084` целиком (attach-листенер, `post`, `postDelayed(…, 300)`).

### D. Наследник закрытой вкладки выбирается по сессии, до удаления

```java
// TermuxTerminalSessionActivityClient.removeFinishedSession()
final int removedIndex = service.getIndexOfSession(finishedSession);
TerminalSession heir = pickHeir(service, removedIndex);   // явная политика, одно место

int index = service.removeTermuxSession(finishedSession);
int size = service.getTermuxSessionsSize();
if (size == 0) mActivity.finishActivityIfNotFinishing();
else mActivity.termuxSessionListNotifyUpdated(heir);       // СЕССИЯ, а не индекс
```

```java
/** ЛЕВАЯ соседка (та, что визуально встаёт на освободившееся место), иначе правая.
 *  Единственное место, где определена политика. */
private static TerminalSession pickHeir(TermuxService s, int removedIndex) {
    if (removedIndex < 0) return null;
    if (removedIndex - 1 >= 0)
        return s.getTermuxSession(removedIndex - 1).getTerminalSession();
    if (removedIndex + 1 < s.getTermuxSessionsSize())
        return s.getTermuxSession(removedIndex + 1).getTerminalSession();
    return null;
}
```

Направление (левая, а не правая) — сознательное; обоснование и замеры в §5-J. Обратите
внимание: `removeFinishedSession` дополнительно зовёт
`SessionPagerManager.parkOnSessionBeforeRemoval(heir)` **до** удаления — без этого индекс
цели не важен, потому что экран останется на мёртвой странице (§3.7-бис).

Внутри синк-метода индекс цели вычисляется **после** удаления, поэтому он корректен
по построению:

```java
public void termuxSessionListNotifyUpdated(@Nullable TerminalSession heir) {
    ...
    int restoreIndex = (heir != null) ? service.getIndexOfSession(heir) : -1;
    if (restoreIndex < 0) restoreIndex = clamp(mActiveIndex >= 0 ? mActiveIndex : mTerminalPager.getCurrentItem(),
                                              service.getTermuxSessionsSize() - 1);
    ...
}
```

Сравните с текущим `:1259-1273`: `preferredIndex` — индекс в старом списке + кламп, который
уже дважды в этом файле комментировался как источник регрессий (см. `:1166-1175`).

### E. Гард структурного синка — по числу реальных сессий

```java
// TerminalPagerAdapter
public int getSessionCount() { return mSessions.size(); }   // без плейсхолдера

// SessionPagerManager:1243
int newSize = service.getTermuxSessionsSize();
int oldSize = mTerminalPagerAdapter.getSessionCount();
if (oldSize != newSize || !mTerminalPagerAdapter.sameSessions(service.getTermuxSessions())) {
```

`oldSize` для ветки «добавили вкладку» (`:1249`) берётся оттуда же. `syncWithServiceList()`
уже сбрасывает плейсхолдер на входе (`:211-219`), так что diff всегда идёт по чистому списку.

**Важно:** честное сравнение реальных сессий само по себе ломает свайп-добавление — см. §3.8;
обязательная пара к этому шагу — глушение синка в окне коммита (K).

### F. Порядок: пейджер первым, панель вкладок — второй

`TermuxActivity.termuxSessionListNotifyUpdated()` (`:3667`) сегодня зовёт `updateTabs()` **до**
синка пейджера — комментарий `:3672-3678` объясняет это двумя причинами: (1) `getCurrentSession()`
возвращает мёртвую сессию, поэтому подсветку всё равно правит пейджер; (2) `scrollStripToEnd()`
должен поднять `mEndScrollActive` до `setCurrentItem(false)`.

После (A) причина (1) исчезает. Причину (2) нужно сохранить: переносим резервацию end-scroll
из `updateTabs()` в явный вызов до синкa пейджера (`tabs.setEndScrollReserved(true)` при
`newSize > oldSize`), а сам `updateTabs()` — **после** пейджерного синкa, чтобы панель
отрисовывала финальное состояние:

```java
if (mSessionPagerManager != null) mSessionPagerManager.termuxSessionListNotifyUpdated(heir);
if (mTermuxSessionTabsController != null)
    mTermuxSessionTabsController.updateTabs(service.getTermuxSessions());
saveSessionSnapshot();
```

### G. Плейсхолдер: перекладка состояния после его (пере)вставки

`managePlaceholderForPosition(activeIndex)` (`:1312`) меняет `getItemCount()`, а при снятии
плейсхолдера — и текущий индекс. Так как `onTerminalPageSelected` теперь идемпотентна,
добавить после `:1321`:

```java
onTerminalPageSelected(mTerminalPager.getCurrentItem());   // повторная фиксация — безопасно
```

### H. Подсветка вкладок по сессии

```java
// TermuxSessionTabsController
public void setCurrentSessionForSession(@Nullable TerminalSession session) {
    if (mTabsContainer == null) return;
    for (int i = 0, n = getTabCount(); i < n; i++) {
        View child = getTabAt(i);
        if (child != null && child.getTag(R.id.session_tab_session_tag) == session) {
            applyTabSelectionState(i);
            mCurrentSessionIndex = i;
            return;
        }
    }
    clearSelection();     // сессии нет в панели — честно снимаем подсветку
}
```

Тег `R.id.session_tab_session_tag` уже пишется в `populateTabView` (`:450`), поиск по нему уже
используется в `closeSession()` (`:633`) — переиспользуем существующий механизм.

### I. Отладочный оракул `pagedump`

В `TermuxDebugCommandReceiver` добавить команду, печатающую в logcat (тег `TIPanelCmd`):

```
pager.currentItem, adapter.getSessionCount(), placeholder{active,index},
для каждой позиции: bound? + session.mHandle + view.isAttached,
mActiveIndex, mTerminalView → session,
tabs.mCurrentSessionIndex + выбранная вкладка → session,
service: [handle, handle, ...]
```

Это то, без чего остальное проверять нечем: чурн вью не виден ни в `uistate`, ни в
`dumpsys input_method` (см. `2026-09-19.md`).

Дополнено после второй итерации: `pagedump` печатает ещё и **фактическую** раскладку —
`scrollX` и для каждого привязанного child: `pos`, `x` и сессию его `TerminalView`. Именно
`|child1 pos=-1 x=0` и оказался доказательством §3.7-бис: без этой части оракул показывал
только то, что приложение *думает* о себе, а не то, что на экране.

### J. Пейджер уводится с удаляемой страницы ДО удаления; наследник — левая соседка

Два изменения в паре:

1. `TermuxTerminalSessionActivityClient.removeFinishedSession()` вызывает
   `SessionPagerManager.parkOnSessionBeforeRemoval(heir)` **до** `service.removeTermuxSession(...)`
   и только если удаляется именно активная страница. `setCurrentItem(heirOldIndex, false)`
   гарантированно не no-op (цель отличается от текущей страницы), поэтому пейджер реально
   уезжает, и удаление превращается в штатный случай «контент сдвинулся под стабильным якорем»
   — мёртвый child не остаётся.
2. `pickHeir()` теперь предпочитает **левую** соседку (правую — только если закрыли первую
   вкладку). Это то, что приложение и так показывало на практике, и то, что ожидает
   пользователь: вкладки 1,2,3,4, закрываем 3 → 2, затем закрываем 2 → 1. Прежнее намерение
   «правая соседка» никогда не достигалось (раскладка пейджера всегда выигрывала), так что
   явная фиксация ничего не меняет в наблюдаемом поведении — только убирает гонку.
   Бонус: при удалении элемента справа индекс наследницы **не сдвигается**, поэтому
   `setCurrentItem(restoreIndex, false)` в синке остаётся no-op и пересчёт не нужен.

### K. Синк структурного списка глушится на время коммита плейсхолдера

`SessionPagerManager.mPlaceholderCommitInFlight` — `true` ровно на время вызова
`client.createSessionForPlaceholder(...)`, и `termuxSessionListNotifyUpdated()` в этом окне
выходит сразу. Коммит сам владеет обновлением адаптера (`commitPlaceholder()`), а структурный
синк — его противоположность: он выбрасывает страницу плейсхолдера, останавливает скролл и
прыгает через `setCurrentItem(..., false)`. Это не «эвристика по совпадению размера» (как было
раньше), а явное утверждение: в этом окне единственный писатель адаптера — коммит.

## 6. Проверка на устройстве (выполнена)

Сценарий из §1 воспроизведён реальными жестами (`input swipe 1000 900 60 900 400` — свайп
вперёд, добавляющий вкладку) и закрытием через `run exit` (команда `close tab` не годится:
`TermuxSession.finish()` досрочно выходит, пока процесс жив, поэтому убивает вкладку только
крестик — он сначала зовёт `finishIfRunning()`).

| Прогон | Итог |
|--------|------|
| 4 вкладки → закрыть 3-ю | `sessions=3 pager=1 active=1 tv=4542bcf tabSel=1`, children `pos=0,1,2` при `x=-1080,0,1080` |
| …затем закрыть 2-ю | `sessions=2 pager=0 active=0 tv=c0e14be tabSel=0`, child `pos=0 x=0` = `c0e14be` |
| закрыть первую вкладку (3 шт.) | пре-парк ушёл на `pos=1`, затем `setCurrentItem(0,false)` реально прокрутил → `pager=0 active=0` |
| закрыть последнюю при активном плейсхолдере | `sessions=2 pager=1 active=1`, плейсхолдер перевзведён на `2`, раскладка чистая |
| свайп-добавление вкладки | `VP2.onPageSelected pos=N ph=N userScroll=true` и **ни одной** строки `SLNU` → коммит не тронут, анимация сохранена |

Инвариант §2 (`active == сессия tv == подсвеченная вкладка == живая сессия`) выполняется на каждом
шаге; ни одного `pos=-1` в раскладке; ни одного «позднего» `onPageSelected`, перебивающего наше
решение. Проверялось на `com.termux.debug`; релиз не трогался (только компиляция).

Состояние после двух закрытий — `docs/img/tab-close-fixed.png`: подсвечена первая вкладка, на
экране её живой терминал, строка `signal 9` отсутствует.

## 7. Порядок внедрения (каждый шаг проверяется отдельно на debug)

| Шаг | Содержание | Что проверять |
|-----|-----------|---------------|
| 0 | `pagedump` (I) | снять следы ДО — оба закрытия, состояния 1–4 из §2 |
| 1 | (A) `mActiveIndex` + `getCurrentSession()` | ввод, IME, экстра-кнопки, контекстное меню, `signal 9` больше не «активная сессия» |
| 2 | (B) карта сессия→вью, `onPageBound`, удаление сдвига ключей | страницы не теряют вью после закрытия/добавления; нет «uninitialised Terminal page» |
| 3 | (C) тотальная `onTerminalPageSelected`, удаление `postDelayed(300)` | клавиатура не гаснет при переключении; нет «клавиатура всплывает сама» |
| 4 | (D) наследник по сессии | закрытие любой вкладки (первой/средней/последней) → правильная цель; нет «новая вкладка выбирает предыдущую» |
| 5 | (E) гард по `getSessionCount()` | нет пропущенного/лишнего синкa при активном плейсхолдере |
| 6 | (G) повторная фиксация, (H) подсветка по сессии | нет двойного движения панели при добавлении; подсветка == экран |
| 7 | (J) пре-парк + левая соседка | закрытие любой вкладки: `child pos=0 x=0` — это наследница; ни одного `pos=-1` |
| 8 | (K) глушение синка в коммите | свайп-добавление: раскрытие плейсхолдера анимировано, `SLNU` в окне коммита нет |

(F) не делалось — см. «Отклонение от плана» выше.
Шаги 7-8 — вторая итерация: без них 0-6 баг не лечат (см. §3.7-бис).

Сценарий проверки (устройство, debug-сборка `com.termux.debug`):
1 вкладка → 3 свайпа вперёд → закрыть 3-ю → `pagedump` → закрыть следующую → `pagedump` →
сверять инвариант §2. Свайп-создание воспроизводится только реальным жестом
(`input swipe 1000 900 60 900 400`; y≈900 — внутри области терминала, а не панели вкладок);
debug-команда `new tab` идёт другим путём. Закрытие — только `run exit`: `close tab` зовёт
`removeFinishedSession` у живого процесса, а `TermuxSession.finish()` в этом случае выходит
досрочно, так что вкладка не убивается (крестик сначала зовёт `finishIfRunning()`).

## 8. Чего делать НЕ надо

* **Никаких новых `post` / `postDelayed` как «догонялки».** Именно они создали ветку `:1027-1084`,
  которая не восстанавливается по построению (§3.3). Восстановление — синхронное (C) или
  событийное (B, `onPageBound`).
* **Не возвращать `notifyDataSetChanged()`** — инкрементальные уведомления в
  `syncWithServiceList()` поставлены не просто так (`:200-206`, краши «Inconsistency detected»).
* **Не лечить через `requestFocus()` / принудительный `setCurrentItem` «ещё раз»** — это маскирует
  расхождение, а не устраняет его.
* **Не расширять кэш `mTerminalView` новыми условиями.** Он либо актуален, либо `null` (правило 4).
* **Не удалять страницу, на которой стоит пейджер.** Это единственный случай, который
  `RecyclerView`+`ViewPager2` обрабатывают плохо (§3.7-бис): мёртвый child остаётся на экране, а
  `mCurrentItem` становится протухшим. Сначала уйти на выживающую страницу (J), потом удалять.
* **Не использовать `getItemCount()` как прокси числа сессий.** Плейсхолдер делает его на единицу
  больше, из-за чего «правильный на вид» гард молча съедает нужный синк (§3.8).
* **Не трогать релиз, пока сценарий §1 не пройден на debug.** Пройден — см. §6.

* `docs/tab-close-stuck-in-dead-session.md` — предыдущие разборы (§7, §9: откатанные проходы).
* `SessionPagerManager.java`: `:479` `onPageSelected`, `:974` `onTerminalPageSelected`,
  `:1195` `getPagerPageView`, `:1223` `termuxSessionListNotifyUpdated`.
* `TerminalPagerAdapter.java`: `:208` `syncWithServiceList`, `:494` bind, `:577` `getItemCount`,
  `:613` `setPlaceholderActive`.
* `TermuxSessionTabsController.java`: `:223` `updateTabs`, `:624` `closeSession`,
  `:1071` `applyTabSelectionState`, `:1099` `setCurrentSession`.
* `TermuxActivity.java`: `:3667` `termuxSessionListNotifyUpdated`, `:3725` `setTerminalView`,
  `:3858` `getCurrentSession`.
## 9. Ссылки

* `TermuxTerminalSessionActivityClient.java`: `:1125` `removeFinishedSession`, `pickHeir`.
* `SessionPagerManager.java`: `parkOnSessionBeforeRemoval`, `mPlaceholderCommitInFlight`, `dumpPageState`.
