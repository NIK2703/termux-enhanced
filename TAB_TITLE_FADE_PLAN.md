# План: затухание заголовков вкладок вместо многоточия

## Цель

1. Убрать `...` при усечении заголовка вкладки.
2. Последние три видимых символа заголовка должны иметь альфу 75%, 50% и 25%
   (от предпоследнего к последнему: 75%, 50%, 25%).
3. Неактивная вкладка без крестика должна использовать всё доступное место:
   заголовок продолжается до конца вкладки, а не останавливается там, где
   находился бы крестик.

## Текущее состояние

Реализовано:

- `app/src/main/res/values/dimens.xml`: добавлен
  `terminal_tab_close_size = 20dp`.
- `app/src/main/res/layout/item_session_tab.xml`:
  - убран `android:ellipsize="end"` у `session_tab_title`;
  - размер крестика берётся из `@dimen/terminal_tab_close_size`;
  - неактивный крестик по умолчанию `gone`.
- `TermuxSessionTabsController.java`:
  - `mCloseWidthPx` и его инициализация в `ensureDimens()`;
  - `TabRenderState.title` хранит полный исходный заголовок;
  - `AlphaSpan`, `TitleRender`, `fadeAlphaForPosition()`,
    `renderTitle()` и `buildFadedTitle()`;
  - `applyTitleLayout()` — выбор ширины, отступа, текста и
    `contentDescription`;
  - `populateTabView()`, `applyTabSelectionState()` и `onPageScrolled()`
    обновляют заголовок при смене выбора/видимости крестика;
  - старый `isTitleTruncated()` удалён.
- `app/src/test/java/com/termux/app/terminal/TermuxSessionTabsControllerTest.java`:
  проверка шагов альфы.
- `:app:compileReleaseJavaWithJavac` и `:app:assembleRelease` проходят.
- `:app:testReleaseUnitTest --tests ...TermuxSessionTabsControllerTest`
  проходит (2/2).

## Ключевые решения

### 1. Ручное усечение вместо `TextView.setEllipsize()`

`ellipsize` удалён из XML. Заголовок усекается вручную:

- если весь текст помещается по ширине — оставить как есть;
- в однострочном режиме использовать `Paint.breakText()`, чтобы заполнить
  строку посимвольно, а не обрывать её на пробеле;
- в двухстрочном режиме построить `StaticLayout` с `maxLines + 1`
  (лишняя строка нужна, чтобы отличить «помещается» от «обрезано»);
- взять конец последней разрешённой строки;
- обрезать хвостовые пробелы/переводы строк, не разрывая surrogate pair;
- к последним трём code point применить alpha-спаны.

Для API 23+ используется `StaticLayout.Builder` с `setMaxLines()`,
`setBreakStrategy()` и `setHyphenationFrequency()` от `TextView`;
для API 21–22 — старый конструктор `StaticLayout`.
Усечённый двухстрочный заголовок получает точную ширину, чтобы
`TextView` не переразбил уже подготовленный префикс.

### 2. Alpha-спаны

Добавить приватный `CharacterStyle`:

```java
private static final class AlphaSpan extends CharacterStyle {
    private final int alpha;

    AlphaSpan(int alpha) {
        this.alpha = alpha;
    }

    @Override
    public void updateDrawState(TextPaint tp) {
        tp.setAlpha(tp.getAlpha() * alpha / 255);
    }
}
```

Значения альфы для трёх последних символов:

- третий с конца: 75% (`191`);
- второй с конца: 50% (`128`);
- последний: 25% (`64`).

Спан должен менять только alpha, не цвет, чтобы сохранялись цвет схемы,
красный цвет ошибки и зачёркивание завершённой сессии.

### 3. Фиксированная ширина усечённой вкладки

Ключевое отличие от промежуточной версии: «усечённость» определяется
**только по базовому лимиту** `mTitleMaxWidthPx`, а не по текущей
расширенной ширине заголовка. Если полный заголовок шире базового лимита,
вкладка считается усечённой и получает фиксированную ширину:

```text
lockedWidth = mTitleMaxWidthPx + mCloseGapPx + mCloseWidthPx
```

Для такой вкладки:

- активная: заголовок получает точную ширину
  `mTitleMaxWidthPx + mCloseGapPx`, крестик `VISIBLE`, отступ крестика `0`;
  сумма = `lockedWidth`;
- неактивная: заголовок получает точную ширину `lockedWidth`, крестик
  `GONE`; сумма = `lockedWidth`;
- `layout_width` заголовка задаётся в пикселях (`maxWidth`), а не
  `wrap_content`, поэтому даже если префикс помещается раньше края,
  вкладка не сжимается.

Для короткого заголовка, который помещается в базовый лимит, остаётся
естественная ширина `wrap_content`; неактивная короткая вкладка может
сжаться, освободив место крестика. Это не «усечённая» вкладка, поэтому
требование фиксированной длины на неё не распространяется.

Для неактивной усечённой вкладки:

- `session_tab_close` получает `View.GONE` (а не `INVISIBLE`);
- заголовок занимает всю `lockedWidth`, включая место крестика.

Итог: у усечённых вкладок ширина одинакова и при активном, и при
неактивном состоянии, и не зависит от того, появился ли крестик.

### 4. Обновление при смене выбора

Так как у усечённых вкладок ширина заголовка зависит от наличия крестика,
при смене активной вкладки нужно пересчитать заголовок, но итоговая
ширина вкладки остаётся неизменной.

Добавлено в `TabRenderState`:

- `String title` — исходный полный заголовок;
- удалён неиспользуемый `boolean truncated`;
- сохранены `maxWidthApplied` и `closeMarginApplied`.

Добавлен метод:

```java
private void applyTitleLayout(TabRenderState state, boolean closeVisible)
```

Он:

- выбирает ширину и отступ крестика;
- вызывает ручное усечение/затухание;
- применяет `setMaxWidth()`, `marginStart`, ширину и `setText()`;
- сохраняет полный заголовок в `contentDescription` для screen reader'ов.

Вызывать его:

- из `populateTabView()` при смене текста, числа строк, выбора или
  видимости крестика;
- из `applyTabSelectionState()` при смене выбора/видимости;
- из `onPageScrolled()` при переключении крестика между соседними
  вкладками, чтобы ширина не прыгала во время свайпа.

### 5. Кэш размеров

В `ensureDimens()` добавить:

```java
mCloseWidthPx = Math.round(r.getDimension(R.dimen.terminal_tab_close_size));
```

Поле `private int mCloseWidthPx = 0;` рядом с `mCloseGapPx`.

## Порядок работ

1. Добавить `mCloseWidthPx` и его инициализацию в `ensureDimens()`.
2. Обновить `TabRenderState`.
3. Добавить `AlphaSpan`, `TitleRender` и `renderTitle()`.
4. Добавить `applyTitleLayout()`.
5. Переписать блок заголовка в `populateTabView()`.
6. Обновить `applyTabSelectionState()`: `VISIBLE/GONE` и пересчёт заголовка.
7. Обновить `onPageScrolled()`: `VISIBLE/GONE` и пересчёт заголовка при
   смене ближайшей вкладки.
8. Удалить старый `isTitleTruncated()`.
9. Добавить unit-тест на шаги альфы (75/50/25).
10. Собрать и проверить.

## Критерии приёмки

- При длинном заголовке на вкладке нет `...`.
- Последние три видимых символа имеют альфу 75%, 50%, 25%.
- Цвет текста, красный цвет ошибки и зачёркивание сохраняются.
- Неактивная вкладка без крестика использует освободившееся место:
  длинный заголовок доходит до правого края вкладки.
- Активная вкладка по-прежнему показывает крестик и не наезжает на него.
- У усечённой вкладки ширина одинакова с крестиком и без него, а также
  при переключении вкладок и свайпе.
- Короткие заголовки не получают фиксированную ширину и остаются
  компактными.
- Однострочный и двухстрочный режимы работают.
- `git diff --check` чистый.
- `:app:testReleaseUnitTest` проходит.
- `:app:assembleRelease` проходит.

## Проверка

```bash
GRADLE_USER_HOME=$HOME/.gradle sh gradlew \
  :app:testReleaseUnitTest \
  --tests com.termux.app.terminal.TermuxSessionTabsControllerTest

GRADLE_USER_HOME=$HOME/.gradle sh gradlew :app:assembleRelease

git diff --check
```

## Фактическая проверка

Выполнено:

```text
:app:compileReleaseJavaWithJavac — BUILD SUCCESSFUL
:app:testReleaseUnitTest --tests ...BottomStripAnchorsTest
    --tests ...TermuxSessionTabsControllerTest — 6/6 passed
:app:assembleRelease — BUILD SUCCESSFUL
git diff --check — чисто
```

Визуальная проверка на устройстве не выполнялась: по условию задачи нельзя
устанавливать/запускать приложение, использовать adb или трогать UI.

## Риски и края

- На API 21–22 `StaticLayout.Builder` недоступен — используется запасной
  конструктор.
- При двухстрочном режиме усечённый заголовок получает точную ширину;
  окончательный вид стоит проверить на устройстве.
- Если заголовок короче трёх code point, затухание применяется к тому, что
  есть; последний символ всегда 25%.
- `onPageScrolled()` вызывается часто: пересчёт заголовка выполняется только
  при фактической смене видимости крестика, не каждый кадр.
- Смена ширины коротких вкладок при выборе проходит через существующий
  `LayoutTransition.CHANGING`; это стоит оценить визуально.
- Не устанавливать и не запускать приложение на устройстве без разрешения
  пользователя.
