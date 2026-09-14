# Сборка, окружение и рабочие грабли (termux-enhanced)

Вынесено из `.workbuddy-ai/memory/MEMORY.md` 14.09, чтобы уложить MEMORY.md в лимит
инъекции. Здесь — долгоживущие факты об окружении; механика правки `strings.xml` —
в навыке `android-strings-diff-apply`.

## Java / Gradle
- `JAVA_HOME=C:/Users/Nikita/tools/jdk-21.0.12.1+1` — экспортировать перед `./gradlew`.
- `minSdk 21`, `targetSdk 28`. `lintVitalRelease` = **error**: квалифицированному ресурсу
  нужен дефолт в `values/` (актуально только при добавлении нового ключа).
  `ExtraTranslation` выключен.
- Песочница рубит **запись** в `build/intermediates` и в `~/.gradle/caches/**.lock` —
  это ложное падение; ориентир — строка `BUILD SUCCESSFUL`, а не exit code.
- Gradle **врёт про свежесть** (`UP-TO-DATE` при реально изменённом файле) ⇒ всегда
  `--rerun -Dorg.gradle.caching=false`.

## Гейты
- Основной: `:app:processReleaseResources --offline --rerun -Dorg.gradle.caching=false`.
- Набор `warn: removing resource … without required default value` (~15 штук) — это функция
  **множеств ключей по локалям**, поэтому при правке без смены имён ключей он неизменен.
  Сверять `added=={} / removed=={}` + «ни один изменённый ключ не в warn-списке», а **не**
  diff с прошлым прогоном (соседи правят локали параллельно).
- `aapt2` = `C:/Users/Nikita/AppData/Local/Android/Sdk/build-tools/37.0.0/aapt2.exe`.
  И `-o`, и `--dir` требуют **Windows-формы** пути; POSIX-путь даёт
  «Не удается найти указанный файл. (2)».
- `aapt2 link` на полном `res/` падает (темы/стили) ⇒ рендер доказывать harness'ом только
  со строками: `res/values/strings.xml` (+ целевая локаль) + минимальный манифест +
  `platforms/android-34/android.jar`, ожидать exit 0.

## Тесты и устройство
- Тесты: `:terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
  :app:testReleaseUnitTest`.
- Дымовой прогон: `am start -n com.termux/.app.TermuxActivity` → `pidof` →
  `logcat -d | grep -iE "FATAL|AndroidRuntime"` пусто.
- `adb` = `C:/Users/Nikita/AppData/Local/Android/Sdk/platform-tools/adb.exe`; передавать
  Windows-пути (`cygpath -w`).
- Устройство: LineageOS `marble`, 1080×2400, wireless adb. IP динамический ⇒ искать через
  `adb mdns services`. Есть root.

## Git в этой песочнице
- `git commit` **из Bash** удаляет `.git/refs/heads/<branch>` → восстанавливать loose-ref
  полным 40-символьным хешем.
- `git diff --no-index` с относительными путями из не-репозитория даёт **пустой** diff
  (exit 1, без stderr) — всегда давать абсолютные пути.

## Копии репозитория
- **Актуальна только эта копия.** `termux-baseline`, `wt-a3`, `wt-f`, `wt-g` устарели
  (с 09-11) — не анализировать.
- Источник для сверки перевода подтверждать по `md5`/`mtime`, а не по имени каталога.
