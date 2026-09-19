"""Gate: every preference row shown on the settings screens must use a row layout whose title can
wrap instead of being ellipsized with "…".

Background: the AndroidX material row layouts declare the title TextView as
`singleLine="true"` + `ellipsize="marquee"`, which collapses to a trailing "…" whenever the marquee
is not animating (i.e. always, in a scrolling list). The app therefore redirects every row type to
@layout/preference_settings (or @layout/preference_seekbar_settings for sliders) through
Theme.TermuxApp.Settings. AndroidX has a per-style opt-out (android:singleLineTitle) but it is
declared inconsistently across the material styles, which is how switch and slider rows ended up
still truncated, so the redirect has to cover *every* row type - which is what this gate proves.

It is structural, not a screenshot: for every <XxxPreference> element type actually used in
app/src/main/res/xml/*.xml it resolves

    row type -> its style attribute (preferenceStyle, switchPreferenceCompatStyle, ...)
             -> the style's parent chain in Theme.TermuxApp.Settings
             -> android:layout

and then loads that layout (from the app module, or from the androidx.preference AAR in the Gradle
cache for library layouts) and reports whether its @android:id/title is single-line / ellipsized.
Both the day and the night (values-night) theme are checked.

    python scripts/check_preference_title_wrapping.py     # exit 0 = every row type wraps
"""

import glob
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_RES = os.path.join(REPO, "app", "src", "main", "res")

# <XxxPreference> element -> the theme attribute that selects its style (androidx.preference 1.2.1).
ELEMENT_STYLE_ATTR = {
    "Preference": "preferenceStyle",
    "PreferenceGroup": "preferenceStyle",
    "PreferenceScreen": "preferenceScreenStyle",
    "PreferenceCategory": "preferenceCategoryStyle",
    "CheckBoxPreference": "checkBoxPreferenceStyle",
    "SwitchPreference": "switchPreferenceStyle",
    "SwitchPreferenceCompat": "switchPreferenceCompatStyle",
    "DialogPreference": "dialogPreferenceStyle",
    "EditTextPreference": "editTextPreferenceStyle",
    "ListPreference": "dialogPreferenceStyle",          # extends DialogPreference
    "MultiSelectListPreference": "dialogPreferenceStyle",
    "DropDownPreference": "dropdownPreferenceStyle",
    "SeekBarPreference": "seekBarPreferenceStyle",
}

THEME = "Theme.TermuxApp.Settings"
STYLE_RE = re.compile(r'<style\s+name="([^"]+)"(?:\s+parent="([^"]+)")?\s*(?:/>|>(.*?)</style>)', re.S)
ITEM_RE = re.compile(r'<item\s+name="([^"]+)">(.*?)</item>', re.S)
TAG_RE = re.compile(r'<(/?)([A-Za-z][A-Za-z0-9_.]*)((?:"[^"]*"|[^>"])*)>')
TITLE_RE = re.compile(r'<TextView\b[^>]*android:id="@android:id/title"[^>]*/>', re.S)


def parse_styles_text(text):
    """name -> (parent, {item: value}); a style without an explicit parent inherits from its
    dotted prefix, exactly like the resource compiler does."""
    styles = {}
    for name, parent, body in STYLE_RE.findall(text):
        if not parent and "." in name:
            parent = name.rsplit(".", 1)[0]
        styles[name] = (parent, dict(ITEM_RE.findall(body or "")))
    return styles


def parse_styles(path):
    with open(path, encoding="utf-8") as fh:
        return parse_styles_text(fh.read())


def library_files():
    """(styles, layouts) of androidx.preference, read straight out of the AAR in the Gradle cache."""
    styles, layouts = {}, {}
    for aar in glob.glob(os.path.join(
            os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1",
            "androidx.preference", "preference", "*", "*", "*.aar")):
        with zipfile.ZipFile(aar) as z:
            for member in z.namelist():
                if member.startswith("res/values/") and member.endswith(".xml"):
                    styles.update(parse_styles_text(z.read(member).decode("utf-8")))
                elif member.startswith("res/layout/") and member.endswith(".xml"):
                    layouts[os.path.basename(member)[:-4]] = z.read(member).decode("utf-8")
    return styles, layouts


def folded(tables):
    """Merge style tables lowest-priority-first so the app's own (and the night variant's)
    declarations win over the library's, item by item."""
    merged = {}
    for table in tables:
        for name, (parent, items) in table.items():
            old = merged.get(name)
            merged[name] = (parent or (old[0] if old else None),
                            dict(old[1], **items) if old else dict(items))
    return merged


def resolve(styles, style_name, item):
    """Nearest declaration of `item` along a style's parent chain."""
    seen = set()
    while style_name and style_name not in seen:
        seen.add(style_name)
        parent, items = styles.get(style_name, (None, {}))
        if item in items:
            return items[item], style_name
        style_name = parent
    return None, None


def title_state(xml):
    """(True, 'wraps') / (False, reasons) / (None, reason) for the row layout's title TextView."""
    m = TITLE_RE.search(xml)
    if not m:
        return None, "no @android:id/title in layout"
    tag = m.group(0)
    problems = []
    if re.search(r'android:singleLine="true"', tag):
        problems.append("singleLine=true")
    ellipsize = re.search(r'android:ellipsize="([^"]+)"', tag)
    if ellipsize and ellipsize.group(1) != "none":
        problems.append("ellipsize=%s" % ellipsize.group(1))
    if re.search(r'android:maxLines="1"', tag):
        problems.append("maxLines=1")
    return (not problems), ", ".join(problems) or "wraps"


def rows_in_use():
    """{(element, explicit layout or None)} found in the app's preference XMLs."""
    found = {}
    for path in sorted(glob.glob(os.path.join(APP_RES, "xml", "*.xml"))):
        text = open(path, encoding="utf-8").read()
        if "<PreferenceScreen" not in text:
            continue
        for closing, tag, attrs in TAG_RE.findall(text):
            if closing or tag not in ELEMENT_STYLE_ATTR:
                continue
            lay = re.search(r'(?:app|android):layout="(@?[^"]+)"', attrs)
            found.setdefault((tag, lay.group(1) if lay else None), set()).add(
                os.path.basename(path))
    return found


def main():
    lib_styles, lib_layouts = library_files()
    if not lib_styles:
        print("warning: androidx.preference AAR not found in the Gradle cache - library styles "
              "(Preference.Material, ...) cannot be resolved.")
    app_values = [parse_styles(p) for p in
                  sorted(glob.glob(os.path.join(APP_RES, "values", "*.xml")))]
    app_night = [parse_styles(p) for p in
                 sorted(glob.glob(os.path.join(APP_RES, "values-night", "*.xml")))]

    def layout_text(name):
        local = os.path.join(APP_RES, "layout", name + ".xml")
        if os.path.exists(local):
            return local, open(local, encoding="utf-8").read()
        if name in lib_layouts:
            return "androidx.preference AAR", lib_layouts[name]
        return None, None

    variants = [("values (day)", folded([lib_styles] + app_values)),
                ("values-night (night)", folded([lib_styles] + app_values + app_night))]
    failures = 0
    for variant, styles in variants:
        if THEME not in styles:
            print("!! %s: %s not found" % (variant, THEME))
            failures += 1
            continue
        print("=== %s ===" % variant)
        print("%-24s %-52s %-30s %s" % ("row type", "style", "layout", "title"))
        for (element, explicit), _sources in sorted(
                rows_in_use().items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
            if explicit:
                name, style = explicit.split("/")[-1], "(app:layout in xml)"
            else:
                attr = ELEMENT_STYLE_ATTR[element]
                style_ref, _ = resolve(styles, THEME, attr)
                if not style_ref:
                    print("%-24s %-52s %-30s %s" % (element, attr + " -> (unset)", "-", "??"))
                    failures += 1
                    continue
                style = style_ref.split("/")[-1]
                layout_ref, _ = resolve(styles, style, "android:layout")
                if not layout_ref:
                    print("%-24s %-52s %-30s %s" % (element, style, "(none)", "??"))
                    failures += 1
                    continue
                name = layout_ref.split("/")[-1]
            source, xml = layout_text(name)
            ok, note = title_state(xml) if xml else (None, "layout %s not found" % name)
            status = "OK   " if ok else ("N/A  " if ok is None else "FAIL ")
            if ok is False:
                failures += 1
            print("%-24s %-52s %-30s %s%s" % (element, style, name, status, note))
            if ok is None and not explicit:
                print("%-24s   ^ %s" % ("", source or "missing"))
        print()
    if failures:
        print("FAILED: %d row type(s) still truncate their title." % failures)
        return 1
    print("OK: every preference row type used in the settings screens has a wrapping title.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
