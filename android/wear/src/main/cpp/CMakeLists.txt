cmake_minimum_required(VERSION 3.22.1)
project(liftosaur_engine C)

# quickjs-ng v0.16.1, vendored in full (see quickjs-ng/UPSTREAM.md). We build upstream's own
# CMakeLists rather than a hand-picked source list: a survey of real Android embedders found
# vendoring beats submodules and FetchContent because everyone ends up patching, and a
# hand-maintained file list silently rots when upstream adds a translation unit (ticket 06).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(QJS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_WERROR OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(quickjs-ng EXCLUDE_FROM_ALL)

# -Os is deliberate and load-bearing: AGP's default release flags (-O2) reintroduce ~280KB
# for no measured runtime gain (ticket 04). Applied to the vendored target after the fact so
# upstream's CMakeLists stays unpatched.
target_compile_options(qjs PRIVATE -Os)

add_library(liftosaur_engine SHARED liftosaur_engine.c)

target_compile_options(liftosaur_engine PRIVATE -Os)

target_link_libraries(liftosaur_engine
    PRIVATE
    qjs
    android
    log
)
