# Bibliothèque native de Bioread pour la traduction hors-ligne : bergamot-translator (moteur de
# Firefox Translations, Marian) et le pont JNI. Construite par tools/build_bergamot.sh.
cmake_minimum_required(VERSION 3.15)
project(bioread_bergamot CXX C)

set(BERGAMOT_SRC "" CACHE PATH "Sources de bergamot-translator (avec ses sous-modules)")
if(NOT BERGAMOT_SRC)
  message(FATAL_ERROR "-DBERGAMOT_SRC=... manquant")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Détectés avant Marian, dont les options de compilation font échouer le test (NDK : pthread est dans la libc).
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_PYTHON OFF CACHE BOOL "" FORCE)
set(COMPILE_WASM OFF CACHE BOOL "" FORCE)
set(USE_WASM_COMPATIBLE_SOURCE OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(${BERGAMOT_SRC} bergamot EXCLUDE_FROM_ALL)

add_library(bioread_bergamot SHARED bergamot_jni.cpp)
set_target_properties(bioread_bergamot PROPERTIES OUTPUT_NAME bergamot)
target_link_libraries(bioread_bergamot PRIVATE bergamot-translator log)
# Seuls les points d'entrée JNI sont exportés ; le reste est éliminé à l'édition des liens.
set_target_properties(bioread_bergamot PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(bioread_bergamot PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s)
