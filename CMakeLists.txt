cmake_minimum_required(VERSION 3.16)
project(pathfinder CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(PF_BUILD_TOOLS "Build extractor (needs libosmium)" ON)
option(PF_BUILD_TESTS "Build tests" ON)
option(PF_SANITIZE "Build with ASan + UBSan" OFF)

add_compile_options(-Wall -Wextra -Wpedantic -fstack-protector-strong)
if(PF_SANITIZE)
  add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
  add_link_options(-fsanitize=address,undefined)
else()
  add_compile_options(-D_FORTIFY_SOURCE=2)
endif()

# ---- shared headers ----
add_library(graph_format INTERFACE)
target_include_directories(graph_format INTERFACE core/include)

# ---- Phase 2: routing engine (libpathfinder.so) ----
add_library(pathfinder SHARED core/src/engine.cpp core/src/c_api.cpp)
set_target_properties(pathfinder PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_include_directories(pathfinder PUBLIC core/include PRIVATE core/src)

# ---- tools ----
add_executable(graph_info tools/graph_info/main.cpp)
target_link_libraries(graph_info graph_format)

add_executable(route_cli tools/route_cli/main.cpp)
target_link_libraries(route_cli pathfinder)

if(PF_BUILD_TOOLS)
  find_package(Threads REQUIRED)
  find_package(ZLIB REQUIRED)
  find_package(EXPAT REQUIRED)
  find_package(BZip2 REQUIRED)
  add_executable(extractor tools/extractor/main.cpp)
  target_link_libraries(extractor graph_format Threads::Threads ZLIB::ZLIB EXPAT::EXPAT BZip2::BZip2)
endif()

# ---- tests ----
if(PF_BUILD_TESTS)
  enable_testing()
  add_executable(test_engine tests/test_engine.cpp core/src/engine.cpp)
  target_include_directories(test_engine PRIVATE core/src core/include)
  target_link_libraries(test_engine pathfinder)
  add_test(NAME engine COMMAND test_engine)
endif()
