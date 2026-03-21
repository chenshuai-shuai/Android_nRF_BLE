cmake_minimum_required(VERSION 3.22.1)

project(speex_dsp LANGUAGES C CXX)

set(SPEEXDSP_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/speexdsp)
set(SPEEXDSP_SRC ${SPEEXDSP_DIR}/libspeexdsp)

add_library(
    speexdsp
    STATIC
    ${SPEEXDSP_SRC}/preprocess.c
    ${SPEEXDSP_SRC}/resample.c
    ${SPEEXDSP_SRC}/mdf.c
    ${SPEEXDSP_SRC}/fftwrap.c
    ${SPEEXDSP_SRC}/filterbank.c
    ${SPEEXDSP_SRC}/kiss_fft.c
    ${SPEEXDSP_SRC}/kiss_fftr.c
)

target_compile_definitions(speexdsp PRIVATE
    FLOATING_POINT
    USE_KISS_FFT
    EXPORT=
)

target_include_directories(speexdsp PRIVATE
    ${SPEEXDSP_DIR}/include
    ${SPEEXDSP_SRC}
)

add_library(
    speex_jni
    SHARED
    speex_jni.cpp
)

target_include_directories(speex_jni PRIVATE
    ${SPEEXDSP_DIR}/include
    ${SPEEXDSP_SRC}
)

target_compile_definitions(speex_jni PRIVATE
    FLOATING_POINT
    USE_KISS_FFT
    EXPORT=
)

target_link_libraries(
    speex_jni
    speexdsp
    log
    m
)
