cmake_minimum_required(VERSION 3.22.1)
project(termux_foot_terminal C)

# foot is a pinned git submodule. Keep its sources out of this directory so
# upstream can be updated independently. CMake copies the unmodified sources
# into the build tree; compiling from there makes quote-includes resolve against
# the copied core headers, while the adapter's include/ directory supplies the
# shadow Wayland/fcft/pixman headers.
set(FOOT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/foot")
set(FOOT_BUILD_SRC "${CMAKE_CURRENT_BINARY_DIR}/foot-src")
set(FOOT_RENDER_CELL_CHECK "${CMAKE_CURRENT_SOURCE_DIR}/tools/check-render-cell.sh")
set(FOOT_RENDER_DAMAGE_CHECK "${CMAKE_CURRENT_SOURCE_DIR}/tools/check-render-damage.sh")
set(FOOT_SELECTION_REGION_CHECK "${CMAKE_CURRENT_SOURCE_DIR}/tools/check-selection-region.sh")
set(FOOT_SELECTION_TEXT_CHECK "${CMAKE_CURRENT_SOURCE_DIR}/tools/check-selection-text.sh")
set(FOOT_GRID_SCROLL_CHECK "${CMAKE_CURRENT_SOURCE_DIR}/tools/check-grid-scroll.sh")

# render_cell()/render_row(), the cursor-damage policy, the selection geometry,
# the selection-text walker and the scroll-damage pixel movers are copied
# verbatim from the pinned upstream files. Refuse to configure if a generated
# block was edited or the submodule moved without re-pinning; the same checks
# also run before each library build.
foreach(check_script IN ITEMS "${FOOT_RENDER_CELL_CHECK}" "${FOOT_RENDER_DAMAGE_CHECK}" "${FOOT_SELECTION_REGION_CHECK}" "${FOOT_SELECTION_TEXT_CHECK}" "${FOOT_GRID_SCROLL_CHECK}")
    execute_process(
        COMMAND sh "${check_script}" --check
        RESULT_VARIABLE check_result
        OUTPUT_VARIABLE check_output
        ERROR_VARIABLE check_error
    )
    if(NOT check_result EQUAL 0)
        message(FATAL_ERROR
            "foot extraction check failed (${check_script}):\n"
            "${check_output}${check_error}")
    endif()
endforeach()

set(FOOT_COPIED_HEADERS
    terminal.h vt.h csi.h grid.h char32.h composed.h misc.h
    xmalloc.h xsnprintf.h log.h debug.h macros.h util.h
    base64.h uri.h osc.h dcs.h
    extract.h hsl.h
)

set(FOOT_SOURCES
    terminal.c
    osc.c
    dcs.c
    base64.c
    uri.c
    vt.c
    csi.c
    grid.c
    char32.c
    composed.c
    extract.c
    misc.c
    xmalloc.c
    xsnprintf.c
    log.c
    debug.c
    hsl.c
    sixel.c
)

file(MAKE_DIRECTORY "${FOOT_BUILD_SRC}")
foreach(name IN LISTS FOOT_SOURCES FOOT_COPIED_HEADERS)
    file(COPY "${FOOT_ROOT}/${name}" DESTINATION "${FOOT_BUILD_SRC}")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${FOOT_ROOT}/${name}")
endforeach()

set(FOOT_BUILD_SOURCES)
foreach(name IN LISTS FOOT_SOURCES)
    list(APPEND FOOT_BUILD_SOURCES "${FOOT_BUILD_SRC}/${name}")
endforeach()

add_library(termux SHARED ../jni/termux.c)
target_compile_options(termux PRIVATE
    -std=c11 -Os -Wall -Wextra -fno-stack-protector
)
target_link_libraries(termux log)

add_library(termux_terminal SHARED
    src/headless.c
    src/render_model.c
    src/foot_canvas_bridge.c
    src/foot_canvas_shim.c
    src/foot_grid_scroll.c
    src/foot_selection_text.c
    src/stubs.c
    src/vt_filter.c
    src/jni.c
    ${FOOT_BUILD_SOURCES}
)

add_custom_command(TARGET termux_terminal PRE_BUILD
    COMMAND sh "${FOOT_RENDER_CELL_CHECK}" --check
    COMMAND sh "${FOOT_RENDER_DAMAGE_CHECK}" --check
    COMMAND sh "${FOOT_SELECTION_REGION_CHECK}" --check
    COMMAND sh "${FOOT_SELECTION_TEXT_CHECK}" --check
    COMMAND sh "${FOOT_GRID_SCROLL_CHECK}" --check
    COMMENT "Checking extracted foot renderer blocks"
    VERBATIM
)

target_include_directories(termux_terminal PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${FOOT_BUILD_SRC}"
)

target_compile_definitions(termux_terminal PRIVATE
    _GNU_SOURCE
    __STDC_ISO_10646__=201706L
)

target_compile_options(termux_terminal PRIVATE
    -std=c11
    -O2
    -include math.h
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    -fno-stack-protector
    -Wno-missing-field-initializers
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-function
)

set_target_properties(termux_terminal PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections"
)

target_link_libraries(termux_terminal log m)

# Standalone test executable, compiled with the same adapter and upstream
# sources. Useful on-device because the Android library itself needs a JVM.
option(TERMUX_FOOT_BUILD_TESTS "Build the standalone foot adapter test" OFF)
if(TERMUX_FOOT_BUILD_TESTS)
    add_executable(foot_core_test
        tests/foot_core_test.c
        src/headless.c
        src/render_model.c
        src/foot_canvas_bridge.c
        src/foot_canvas_shim.c
        src/foot_grid_scroll.c
        src/foot_selection_text.c
        src/stubs.c
        src/vt_filter.c
        ${FOOT_BUILD_SOURCES}
    )
    add_custom_command(TARGET foot_core_test PRE_BUILD
        COMMAND sh "${FOOT_RENDER_CELL_CHECK}" --check
        COMMAND sh "${FOOT_RENDER_DAMAGE_CHECK}" --check
        COMMAND sh "${FOOT_SELECTION_REGION_CHECK}" --check
        COMMAND sh "${FOOT_SELECTION_TEXT_CHECK}" --check
        COMMAND sh "${FOOT_GRID_SCROLL_CHECK}" --check
        COMMENT "Checking extracted foot renderer blocks"
        VERBATIM
    )
    target_include_directories(foot_core_test PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${FOOT_BUILD_SRC}"
    )
    target_compile_definitions(foot_core_test PRIVATE
        _GNU_SOURCE
        __STDC_ISO_10646__=201706L
    )
    target_compile_options(foot_core_test PRIVATE
        -std=c11
        -O1
        -include math.h
        -Wno-missing-field-initializers
        -Wno-unused-parameter
        -Wno-sign-compare
        -Wno-unused-function
    )
    target_link_libraries(foot_core_test log m)
endif()
