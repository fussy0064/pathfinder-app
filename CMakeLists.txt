cmake_minimum_required(VERSION 3.16)
project(pathfinder CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
add_compile_options(-Wall -Wextra -Wpedantic -fstack-protector-strong -D_FORTIFY_SOURCE=2)

find_package(Threads REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT REQUIRED)
find_package(BZip2 REQUIRED)

add_library(graph_format INTERFACE)
target_include_directories(graph_format INTERFACE core/include)

add_executable(extractor tools/extractor/main.cpp)
target_link_libraries(extractor graph_format Threads::Threads ZLIB::ZLIB EXPAT::EXPAT BZip2::BZip2)

add_executable(graph_info tools/graph_info/main.cpp)
target_link_libraries(graph_info graph_format)
