cmake_minimum_required(VERSION 3.22.1)
project("glyphterrain")

add_library(glyphterrain SHARED terrain.cpp)

find_library(log-lib log)

target_link_libraries(glyphterrain ${log-lib})
