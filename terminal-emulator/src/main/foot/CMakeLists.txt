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

# render_cell()/render_row() are copied verbatim from the pinned upstream file.
# Refuse to configure if the generated block was edited or the submodule moved
# without re-pinning; the same check also runs before each library build.
execute_process(
    COMMAND sh "${FOOT_RENDER_CELL_CHECK}" --check
    RESULT_VARIABLE FOOT_RENDER_CELL_CHECK_RESULT
    OUTPUT_VARIABLE FOOT_RENDER_CELL_CHECK_OUTPUT
    ERROR_VARIABLE FOOT_RENDER_CELL_CHECK_ERROR
)
if(NOT FOOT_RENDER_CELL_CHECK_RESULT EQUAL 0)
    message(FATAL_ERROR
        "foot render_cell() extraction check failed:\n"
        "${FOOT_RENDER_CELL_CHECK_OUTPUT}${FOOT_RENDER_CELL_CHECK_ERROR}")
endif()

set(FOOT_COPIED_HEADERS
    terminal.h vt.h csi.h grid.h char32.h composed.h misc.h
    xmalloc.h xsnprintf.h log.h debug.h macros.h util.h
    base64.h uri.h osc.h dcs.h
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
    misc.c
    xmalloc.c
    xsnprintf.c
    log.c
    debug.c
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
    src/stubs.c
    src/vt_filter.c
    src/jni.c
    ${FOOT_BUILD_SOURCES}
)

add_custom_command(TARGET termux_terminal PRE_BUILD
    COMMAND sh "${FOOT_RENDER_CELL_CHECK}" --check
    COMMENT "Checking extracted foot render_cell() block"
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
        src/stubs.c
        src/vt_filter.c
        ${FOOT_BUILD_SOURCES}
    )
    add_custom_command(TARGET foot_core_test PRE_BUILD
        COMMAND sh "${FOOT_RENDER_CELL_CHECK}" --check
        COMMENT "Checking extracted foot render_cell() block"
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
