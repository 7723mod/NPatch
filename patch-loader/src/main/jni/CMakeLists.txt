project(npatch)
cmake_minimum_required(VERSION 3.31.1)

set(CMAKE_CXX_STANDARD 23)
add_compile_definitions(
        VERSION_CODE=${VERSION_CODE}
        VERSION_NAME=\"${VERSION_NAME}\")
add_subdirectory(${CORE_ROOT} core)

aux_source_directory(src SRC_LIST)
aux_source_directory(src/jni SRC_LIST)
set(SRC_LIST ${SRC_LIST} api/patch_main.cpp)
set(SRC_LIST ${SRC_LIST} src/jni/bypass_sig.cpp)
set(SRC_LIST ${SRC_LIST} src/jni/funpatch_seccomp.cpp)

add_library(${PROJECT_NAME} SHARED ${SRC_LIST})

target_include_directories(${PROJECT_NAME} PUBLIC include)
target_include_directories(${PROJECT_NAME} PRIVATE src)
target_include_directories(${PROJECT_NAME} PRIVATE ${CORE_ROOT}/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/fmt/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/dobby/include)
target_include_directories(${PROJECT_NAME} PRIVATE ${EXTERNAL_ROOT}/lsplant/lsplant/src/main/jni/include)
target_compile_definitions(${PROJECT_NAME} PRIVATE
        VERSION_CODE=${VERSION_CODE}
        VERSION_NAME=\"${VERSION_NAME}\")

target_link_libraries(${PROJECT_NAME} native log)

if (DEFINED DEBUG_SYMBOLS_PATH)
    set(DEBUG_SYMBOLS_PATH ${DEBUG_SYMBOLS_PATH}/${API})
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()