cmake_minimum_required(VERSION 3.10)
project(pathfinder_ffi LANGUAGES CXX)

# Engine sources live in <repo>/core (single source of truth)
set(CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../core")

add_library(pathfinder SHARED "${CORE}/src/engine.cpp" "${CORE}/src/c_api.cpp")
target_include_directories(pathfinder PRIVATE "${CORE}/include" "${CORE}/src")
target_compile_options(pathfinder PRIVATE -Wall -Wextra -fstack-protector-strong)
set_target_properties(pathfinder PROPERTIES
  CXX_STANDARD 17
  CXX_STANDARD_REQUIRED ON
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  # 16 KB page size (required for Android 15+ devices / Play Store) + hardened relocations
  LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now")
