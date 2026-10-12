cmake_minimum_required(VERSION 3.10)
project(pathfinder_jni LANGUAGES CXX)

# The engine lives in <repo>/core (single source of truth, also built and tested on the desktop).
set(CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core")

add_library(pathfinder_jni SHARED
  "${CORE}/jni/pathfinder_jni.cpp"
  "${CORE}/src/engine.cpp"
  "${CORE}/src/nav.cpp"
  "${CORE}/src/c_api.cpp")
target_include_directories(pathfinder_jni PRIVATE "${CORE}/include" "${CORE}/src")
target_compile_options(pathfinder_jni PRIVATE -Wall -Wextra -fstack-protector-strong)
set_target_properties(pathfinder_jni PROPERTIES
  CXX_STANDARD 17
  CXX_STANDARD_REQUIRED ON
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  # 16 KB page size (required for Android 15+ / Play Store) + hardened relocations
  LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now")
