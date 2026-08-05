cmake_minimum_required(VERSION 3.22)
project(CubicalCreate VERSION 0.2.6 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_library(cubical_core STATIC
  native/core/src/project.cpp
  native/core/src/process.cpp
)
target_include_directories(cubical_core PUBLIC native/core/include)

if(WIN32)
  add_executable(CubicalCreate WIN32
    native/windows/main.cpp
    native/windows/CubicalCreate.manifest
  )
  target_compile_definitions(CubicalCreate PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)
  target_link_libraries(CubicalCreate PRIVATE cubical_core comctl32 shell32 ole32 uuid comdlg32)
else()
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GTK4 REQUIRED IMPORTED_TARGET gtk4)
  add_executable(CubicalCreate native/linux-gtk/main.cpp)
  target_link_libraries(CubicalCreate PRIVATE cubical_core PkgConfig::GTK4 pthread)
endif()
