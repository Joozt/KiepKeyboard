cmake_minimum_required(VERSION 3.20)
project(KiepTC002 CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)  # presenter is linked into the shared libzkgui.so
set(KIEP_SDK ${CMAKE_SOURCE_DIR}/.cache/sdk)
option(KIEP_DEVICE "Build the device library (cross)" ON)
option(KIEP_FRAME_TAP "Write frames to /tmp/kiep/frames.bin" OFF)
option(KIEP_SOUND "Link audio-utility for the beep" ON)

# Pure logic, host-testable: protocol -> framebuffer
add_library(presenter STATIC src/Presenter.cpp)
target_include_directories(presenter PUBLIC src)

if(KIEP_DEVICE)
  file(GLOB SDK_INCLUDE_DIRS ${KIEP_SDK}/*/include)
  file(GLOB SDK_LIBS ${KIEP_SDK}/*/lib/*.so ${KIEP_SDK}/*/lib/*.a)
  list(FILTER SDK_LIBS EXCLUDE REGEX "libgatt-server\\.a$")
  if(NOT KIEP_SOUND)
    list(FILTER SDK_LIBS EXCLUDE REGEX "audio-utility|ffmpeg|/z/|mi_|cam_os|base-json|base-utility")
  endif()
  add_library(zkgui SHARED
    src/Main.cpp src/Device.cpp src/Panel.cpp src/Mcu.cpp src/Input.cpp
    src/Sound.cpp src/BtBringup.cpp src/KiepGatt.c src/FrameTap.cpp)
  set_target_properties(zkgui PROPERTIES PREFIX "lib" OUTPUT_NAME "zkgui")
  target_include_directories(zkgui PRIVATE ${SDK_INCLUDE_DIRS} ${KIEP_SDK}/bluez/include ${KIEP_SDK}/bluez/include/ble)
  target_link_libraries(zkgui PRIVATE presenter ${KIEP_SDK}/bluez/lib/libgatt-server.a ${SDK_LIBS} pthread)
  target_compile_definitions(zkgui PRIVATE
    $<$<BOOL:${KIEP_FRAME_TAP}>:KIEP_FRAME_TAP=1>
    $<$<BOOL:${KIEP_SOUND}>:KIEP_SOUND=1>)
  target_link_options(zkgui PRIVATE -Wl,-z,now -Wl,-z,relro -Wl,--as-needed)
  add_custom_command(TARGET zkgui POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_BINARY_DIR}/device/lib ${CMAKE_BINARY_DIR}/device/ui
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:zkgui> ${CMAKE_BINARY_DIR}/device/lib/libzkgui.so
    COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_SOURCE_DIR}/res/ui ${CMAKE_BINARY_DIR}/device/ui
    COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_SOURCE_DIR}/res/EasyUI.cfg.json ${CMAKE_BINARY_DIR}/device/EasyUI.cfg.json)
else()
  add_executable(presenter_test tests/presenter_test.cpp)
  target_link_libraries(presenter_test PRIVATE presenter)
  enable_testing()
  add_test(NAME presenter COMMAND presenter_test)
endif()
