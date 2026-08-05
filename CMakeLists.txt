cmake_minimum_required(VERSION 3.22)
project(CubicalCompare VERSION 1.0.0 LANGUAGES CXX)

include(GNUInstallDirs)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CUBICAL_COMPARE_ENGINE_PATH "" CACHE FILEPATH "Absolute path to the private Cubical Compare rendering engine")

if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_library(cubical_core STATIC
  native/core/src/project.cpp
  native/core/src/process.cpp
)
target_include_directories(cubical_core PUBLIC native/core/include)
target_compile_features(cubical_core PUBLIC cxx_std_20)
if(CUBICAL_COMPARE_ENGINE_PATH)
  target_compile_definitions(cubical_core PRIVATE
    CUBICAL_COMPARE_ENGINE_PATH="${CUBICAL_COMPARE_ENGINE_PATH}"
  )
endif()

if(WIN32)
  add_executable(cubical_compare_app WIN32
    native/windows/main.cpp
    native/windows/CubicalCreate.manifest
  )
  set_target_properties(cubical_compare_app PROPERTIES OUTPUT_NAME CubicalCompare)
  target_compile_definitions(cubical_compare_app PRIVATE
    UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN
  )
  target_link_libraries(cubical_compare_app PRIVATE
    cubical_core comctl32 shell32 ole32 uuid comdlg32
  )
else()
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GTK4 REQUIRED IMPORTED_TARGET gtk4)
  add_executable(cubical_compare_app native/linux-gtk/main.cpp)
  set_target_properties(cubical_compare_app PROPERTIES OUTPUT_NAME cubical-compare)
  target_link_libraries(cubical_compare_app PRIVATE
    cubical_core PkgConfig::GTK4 pthread
  )
endif()

install(TARGETS cubical_compare_app
  RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

if(UNIX AND NOT APPLE)
  install(FILES packaging/flatpak/io.github.retrofrost.CubicalCompare.desktop
    DESTINATION ${CMAKE_INSTALL_DATADIR}/applications
  )
  install(FILES packaging/flatpak/io.github.retrofrost.CubicalCompare.metainfo.xml
    DESTINATION ${CMAKE_INSTALL_DATADIR}/metainfo
  )
  install(FILES packaging/flatpak/io.github.retrofrost.CubicalCompare.svg
    DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/scalable/apps
  )
  install(FILES LICENSE
    DESTINATION ${CMAKE_INSTALL_DATADIR}/licenses/io.github.retrofrost.CubicalCompare
  )
endif()
