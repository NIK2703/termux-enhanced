#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "headless.h"

#define PALETTE_SIZE 259

struct jni_handle {
    pthread_mutex_t lock;
    struct headless_terminal *terminal;
};

static struct jni_handle *
handle_from(jlong handle)
{
    return (struct jni_handle *)(intptr_t)handle;
}

static int32_t *
read_int_array(JNIEnv *env, jintArray array, int expected, int *out_length)
{
    int32_t *values = calloc((size_t)expected, sizeof(values[0]));
    if (values == NULL)
        return NULL;

    jsize length = 0;
    if (array != NULL)
        length = (*env)->GetArrayLength(env, array);
    const jsize count = length < expected ? length : expected;
    if (count > 0)
        (*env)->GetIntArrayRegion(env, array, 0, count, (jint *)values);

    if (out_length != NULL)
        *out_length = count;
    return values;
}

static jintArray
make_int_array(JNIEnv *env, const int32_t *values, int count)
{
    if (count < 0)
        count = 0;
    jintArray array = (*env)->NewIntArray(env, (jsize)count);
    if (array != NULL && count > 0)
        (*env)->SetIntArrayRegion(env, array, 0, (jsize)count, (const jint *)values);
    return array;
}

static jstring
utf8_to_jstring(JNIEnv *env, const char *text)
{
    if (text == NULL)
        return NULL;

    const size_t length = strlen(text);
    jchar *units = malloc((length + 1) * sizeof(units[0]));
    if (units == NULL)
        return NULL;

    size_t i = 0;
    size_t o = 0;
    while (i < length) {
        uint32_t cp = (uint8_t)text[i++];
        if (cp < 0x80) {
        } else if ((cp & 0xe0) == 0xc0 && i < length) {
            cp = ((cp & 0x1f) << 6) | ((uint8_t)text[i++] & 0x3f);
        } else if ((cp & 0xf0) == 0xe0 && i + 1 < length) {
            cp = ((cp & 0x0f) << 12) |
                (((uint8_t)text[i] & 0x3f) << 6) |
                ((uint8_t)text[i + 1] & 0x3f);
            i += 2;
        } else if ((cp & 0xf8) == 0xf0 && i + 2 < length) {
            cp = ((cp & 0x07) << 18) |
                (((uint8_t)text[i] & 0x3f) << 12) |
                (((uint8_t)text[i + 1] & 0x3f) << 6) |
                ((uint8_t)text[i + 2] & 0x3f);
            i += 3;
        } else {
            cp = 0xfffd;
        }

        if (cp <= 0xffff) {
            units[o++] = (jchar)cp;
        } else {
            cp -= 0x10000;
            units[o++] = (jchar)(0xd800 + (cp >> 10));
            units[o++] = (jchar)(0xdc00 + (cp & 0x3ff));
        }
    }

    jstring result = (*env)->NewString(env, units, (jsize)o);
    free(units);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_termux_terminal_TerminalNative_nativeCreate(
    JNIEnv *env, jclass clazz, jint columns, jint rows, jint transcript_rows,
    jint cell_width, jint cell_height, jintArray palette_array)
{
    (void)clazz;
    struct jni_handle *handle = calloc(1, sizeof(*handle));
    if (handle == NULL)
        return 0;

    pthread_mutex_init(&handle->lock, NULL);
    int32_t *palette = read_int_array(env, palette_array, PALETTE_SIZE, NULL);
    if (palette == NULL) {
        pthread_mutex_destroy(&handle->lock);
        free(handle);
        return 0;
    }

    handle->terminal = headless_create(
        columns, rows, transcript_rows, cell_width, cell_height, palette);
    free(palette);
    if (handle->terminal == NULL) {
        pthread_mutex_destroy(&handle->lock);
        free(handle);
        return 0;
    }
    return (jlong)(intptr_t)handle;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_TerminalNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return;
    pthread_mutex_lock(&handle->lock);
    headless_destroy(handle->terminal);
    handle->terminal = NULL;
    pthread_mutex_unlock(&handle->lock);
    pthread_mutex_destroy(&handle->lock);
    free(handle);
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_TerminalNative_nativeAppend(
    JNIEnv *env, jclass clazz, jlong handle_value, jbyteArray data, jint length)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL || data == NULL || length <= 0)
        return NULL;

    jsize array_length = (*env)->GetArrayLength(env, data);
    if (length > array_length)
        length = array_length;
    uint8_t *bytes = malloc((size_t)length);
    if (bytes == NULL)
        return NULL;
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)bytes);

    int dirty_count = 0;
    jint header[8];
    int *dirty = NULL;
    pthread_mutex_lock(&handle->lock);
    dirty = headless_append(handle->terminal, bytes, (size_t)length, &dirty_count);
    if (dirty_count < 0)
        dirty_count = 0;
    header[0] = (jint)dirty_count;
    header[1] = (jint)headless_mode_bits(handle->terminal);
    header[2] = (jint)headless_cursor_style(handle->terminal);
    header[3] = (jint)headless_cursor_row(handle->terminal);
    header[4] = (jint)headless_cursor_col(handle->terminal);
    header[5] = (jint)headless_absolute_scroll_count(handle->terminal);
    header[6] = (jint)headless_history_size(handle->terminal);
    header[7] = (jint)headless_last_append_flags(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    free(bytes);

    const jsize total = (jsize)(8 + dirty_count);
    jintArray result = (*env)->NewIntArray(env, total);
    if (result == NULL) {
        free(dirty);
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, result, 0, 8, header);
    if (dirty_count > 0)
        (*env)->SetIntArrayRegion(
            env, result, 8, (jsize)dirty_count, (const jint *)dirty);
    free(dirty);
    return result;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_TerminalNative_nativeReset(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return;
    pthread_mutex_lock(&handle->lock);
    headless_reset(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_TerminalNative_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle_value,
    jint columns, jint rows, jint cell_width, jint cell_height)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return;
    pthread_mutex_lock(&handle->lock);
    headless_resize(handle->terminal, columns, rows, cell_width, cell_height);
    pthread_mutex_unlock(&handle->lock);
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_TerminalNative_nativeDrainEvents(
    JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return utf8_to_jstring(env, "");

    pthread_mutex_lock(&handle->lock);
    char *events = headless_drain_events(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    jstring result = utf8_to_jstring(env, events != NULL ? events : "");
    free(events);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_TerminalNative_nativePalette(
    JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    int32_t palette[PALETTE_SIZE] = {0};
    if (handle != NULL) {
        pthread_mutex_lock(&handle->lock);
        headless_palette(handle->terminal, palette);
        pthread_mutex_unlock(&handle->lock);
    }
    return make_int_array(env, palette, PALETTE_SIZE);
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_TerminalNative_nativeSetPalette(
    JNIEnv *env, jclass clazz, jlong handle_value,
    jintArray palette_array, jintArray defaults_array)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return JNI_FALSE;

    int32_t *palette = read_int_array(env, palette_array, PALETTE_SIZE, NULL);
    int32_t *defaults = read_int_array(env, defaults_array, PALETTE_SIZE, NULL);
    if (palette == NULL || defaults == NULL) {
        free(palette);
        free(defaults);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&handle->lock);
    bool changed = headless_set_palette(handle->terminal, palette, defaults);
    pthread_mutex_unlock(&handle->lock);
    free(palette);
    free(defaults);
    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeHistorySize(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_history_size(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeRows(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_rows(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeColumns(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_columns(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeCursorRow(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_cursor_row(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeCursorCol(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_cursor_col(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeModeBits(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_mode_bits(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeScrollCounter(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_scroll_counter(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_TerminalNative_nativeClearScrollCounter(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return;
    pthread_mutex_lock(&handle->lock);
    headless_clear_scroll_counter(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeAnchorRow(
    JNIEnv *env, jclass clazz, jlong handle_value, jint external_row)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_anchor_row(handle->terminal, external_row);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_termux_terminal_TerminalNative_nativeAbsoluteScrollCount(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int64_t result = headless_absolute_scroll_count(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return (jlong)result;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_TerminalNative_nativeIsAlternate(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return JNI_FALSE;
    pthread_mutex_lock(&handle->lock);
    bool result = headless_is_alternate(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNative_nativeCursorStyle(JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return 0;
    pthread_mutex_lock(&handle->lock);
    int result = headless_cursor_style(handle->terminal);
    pthread_mutex_unlock(&handle->lock);
    return result;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_TerminalNative_nativeSetCursorStyle(
    JNIEnv *env, jclass clazz, jlong handle_value, jint shape)
{
    (void)env;
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return;
    pthread_mutex_lock(&handle->lock);
    headless_set_cursor_style(handle->terminal, shape);
    pthread_mutex_unlock(&handle->lock);
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_TerminalNative_nativeRow(
    JNIEnv *env, jclass clazz, jlong handle_value, jint external_row)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return NULL;

    int count = 0;
    pthread_mutex_lock(&handle->lock);
    int32_t *row = headless_row(handle->terminal, external_row, &count);
    pthread_mutex_unlock(&handle->lock);
    if (row == NULL)
        return make_int_array(env, NULL, 0);
    jintArray result = make_int_array(env, row, count);
    free(row);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_TerminalNative_nativeSelectedText(
    JNIEnv *env, jclass clazz, jlong handle_value,
    jint x1, jint y1, jint x2, jint y2,
    jboolean join_back_lines, jboolean join_full_lines)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return utf8_to_jstring(env, "");

    pthread_mutex_lock(&handle->lock);
    char *text = headless_selected_text(
        handle->terminal, x1, y1, x2, y2,
        join_back_lines != JNI_FALSE, join_full_lines != JNI_FALSE);
    pthread_mutex_unlock(&handle->lock);
    jstring result = utf8_to_jstring(env, text != NULL ? text : "");
    free(text);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_TerminalNative_nativeTranscriptText(
    JNIEnv *env, jclass clazz, jlong handle_value, jint mode)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return utf8_to_jstring(env, "");

    pthread_mutex_lock(&handle->lock);
    char *text = headless_transcript_text(handle->terminal, mode);
    pthread_mutex_unlock(&handle->lock);
    jstring result = utf8_to_jstring(env, text != NULL ? text : "");
    free(text);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_TerminalNative_nativeWordAtLocation(
    JNIEnv *env, jclass clazz, jlong handle_value, jint x, jint y)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return utf8_to_jstring(env, "");

    pthread_mutex_lock(&handle->lock);
    char *text = headless_word_at_location(handle->terminal, x, y);
    pthread_mutex_unlock(&handle->lock);
    jstring result = utf8_to_jstring(env, text != NULL ? text : "");
    free(text);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_TerminalNative_nativeRender(
    JNIEnv *env, jclass clazz, jlong handle_value,
    jint top_row, jint selection_y1, jint selection_y2,
    jint selection_x1, jint selection_x2, jboolean cursor_visible,
    jint cursor_shape, jint background_alpha, jfloat font_width,
    jint font_line_spacing, jint font_ascent, jint columns, jint rows)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return make_int_array(env, NULL, 0);

    int count = 0;
    pthread_mutex_lock(&handle->lock);
    int32_t *commands = headless_render(
        handle->terminal, top_row, selection_y1, selection_y2,
        selection_x1, selection_x2, cursor_visible != JNI_FALSE,
        cursor_shape, background_alpha, font_width, font_line_spacing,
        font_ascent, columns, rows, &count);
    pthread_mutex_unlock(&handle->lock);
    if (commands == NULL)
        return make_int_array(env, NULL, 0);
    jintArray result = make_int_array(env, commands, count);
    free(commands);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_TerminalNative_nativeRenderText(
    JNIEnv *env, jclass clazz, jlong handle_value)
{
    (void)clazz;
    struct jni_handle *handle = handle_from(handle_value);
    if (handle == NULL)
        return make_int_array(env, NULL, 0);

    int count = 0;
    pthread_mutex_lock(&handle->lock);
    int32_t *text = headless_render_text(handle->terminal, &count);
    pthread_mutex_unlock(&handle->lock);
    if (text == NULL)
        return make_int_array(env, NULL, 0);
    jintArray result = make_int_array(env, text, count);
    free(text);
    return result;
}
