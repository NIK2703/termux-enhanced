#!/data/data/com.termux/files/usr/bin/bash

chr() {
    local esc
    printf -v esc '\\U%08X' "$1"
    printf '%b' "$esc"
}

print_block() {
    local start=$1 end=$2 title=$3
    echo
    echo "=== $title (U+$start–U+$end) ==="
    local cp i=0
    for ((cp = 0x$start; cp <= 0x$end; cp++, i++)); do
        printf "%s " "$(chr "$cp")"
        ((i % 16 == 15)) && echo
    done
    ((i % 16 != 0)) && echo
}

print_block 1F000 1F0FF "Маджонг и карты"
print_block 1F100 1F1FF "Доп. символы"
print_block 1F200 1F2FF "Enclosed"
print_block 1F300 1F5FF "Пиктограммы"
print_block 1F600 1F64F "Смайлики"
print_block 1F680 1F6FF "Транспорт и карты"
print_block 1F700 1F77F "Алхимия"
print_block 1F780 1F7FF "Геометрия"
print_block 1F800 1F8FF "Стрелки"
print_block 1F900 1F9FF "Доп. пиктограммы"
print_block 1FA00 1FA6F "Chess Symbols"
print_block 1FA70 1FAFF "Расширенные пиктограммы"
print_block 2600 26FF "Разные символы"
print_block 2700 27BF "Дингбаты"
print_block 2B00 2BFF "Стрелки и формы"

# Флаги: пары regional indicators U+1F1E6–U+1F1FF
echo
echo "=== Флаги (региональные индикаторы) ==="
for a in {0..25}; do
    for b in {0..25}; do
        printf "%s%s " "$(chr $((0x1F1E6 + a)))" "$(chr $((0x1F1E6 + b)))"
    done
    echo
done

# Модификаторы тона кожи
echo
echo "=== Модификаторы тона кожи ==="
for i in {0..5}; do
    printf "%s " "$(chr $((0x1F466 + i)))"
done
echo

# Примеры ZWJ-последовательностей (составные эмодзи)
echo
echo "=== ZWJ-последовательности ==="
printf '\U0001F469‍\U0001F4BB  женщина-программист\n'
printf '\U0001F468‍\U0001F469‍\U0001F467  семья\n'
printf '\U0001F9D1‍\U0001F91D‍\U0001F9D1  держатся за руки\n'
printf '\U0001F3F3️‍\U0001F308  радужный флаг\n'
