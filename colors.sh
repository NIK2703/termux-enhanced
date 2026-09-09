#!/data/data/com.termux/files/usr/bin/bash

# 16 стандартных ANSI-цветов
echo "=== Стандартные 16 цветов ==="
for i in {0..7}; do
    printf "\033[%sm %3d \033[0m" "$((30+i))" "$((30+i))"
done
echo
for i in {0..7}; do
    printf "\033[%sm %3d \033[0m" "$((90+i))" "$((90+i))"
done
echo; echo

echo "=== Фон (16 цветов) ==="
for i in {0..7}; do
    printf "\033[%sm %3d \033[0m" "$((40+i))" "$((40+i))"
done
echo
for i in {0..7}; do
    printf "\033[%sm %3d \033[0m" "$((100+i))" "$((100+i))"
done
echo; echo

# 256 цветов: 16 базовых + 6x6x6 куб + градации серого
echo "=== 256 цветов ==="
for i in {0..15}; do
    printf "\033[48;5;%sm%4d\033[0m" "$i" "$i"
done
echo; echo
for row in {0..5}; do
    for col in {0..5}; do
        for col2 in {0..5}; do
            i=$((16 + row * 36 + col * 6 + col2))
            printf "\033[48;5;%sm%4d\033[0m" "$i" "$i"
        done
        echo
    done
    echo
done
for i in {232..255}; do
    printf "\033[48;5;%sm%4d\033[0m" "$i" "$i"
done
echo; echo

# Истинный цвет (24-bit): градиенты RGB
echo "=== TrueColor (24-bit) градиент ==="
for g in {0..255..5}; do
    printf "\033[48;2;0;%d;255m " "$g"
done
printf "\033[0m\n"
for g in {0..255..5}; do
    printf "\033[48;2;%d;0;%dm " "$g" "$((255-g))"
done
printf "\033[0m\n\n"

# Жирный и текстовые атрибуты
echo "=== Атрибуты текста ==="
printf "\033[1mЖирный\033[0m  "
printf "\033[2mТусклый\033[0m  "
printf "\033[3mКурсив\033[0m  "
printf "\033[4mПодчёркнутый\033[0m  "
printf "\033[7mИнверсия\033[0m  "
printf "\033[9mЗачёркнутый\033[0m\n"
