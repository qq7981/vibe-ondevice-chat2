# MNN LLM 的 JNI 桥接层。
#
# 链接预编译库方案：libMNN.so 由 MNN 源码单独编译后放入
# app/src/main/jniLibs/<abi>/，这里只编译 JNI 层并链接它。
# 仓库里不需要 MNN 完整源码，构建也快。
#
# 头文件已裁剪到 app/src/main/cpp/mnn/include/。
# MNN 的编译方式见根目录 README「编译 MNN native 库」一节。

cmake_minimum_required(VERSION 3.22.1)
project(mnn_llm_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MNN_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/mnn/include)

# libMNN.so 的绝对路径。jniLibs 与 cpp 同级，都在 src/main/ 下。
set(MNN_SO_PATH
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so")

if(NOT EXISTS "${MNN_SO_PATH}")
    message(FATAL_ERROR
        "未找到 libMNN.so：${MNN_SO_PATH}\n"
        "请先编译 MNN 并把 libMNN.so 放到 app/src/main/jniLibs/${ANDROID_ABI}/，"
        "步骤见根目录 README。")
endif()
message(STATUS "链接 MNN: ${MNN_SO_PATH}")

add_library(mnn_llm_jni SHARED llm_jni.cpp)

target_include_directories(mnn_llm_jni PRIVATE ${MNN_INCLUDE_DIR})

# 直接以完整路径链接，避免 find_library 在不同 CMake 版本下的搜索差异。
target_link_libraries(mnn_llm_jni "${MNN_SO_PATH}" log android)
