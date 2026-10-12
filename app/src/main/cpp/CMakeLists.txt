cmake_minimum_required(VERSION 3.22.1)
project("shadervulkan")

find_library(vulkan-lib vulkan)
find_library(android-lib android)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# R01 (rendimiento): las librerías de terceros se compilan SIEMPRE optimizadas y sin asserts ni información de depuración, también en
# Debug. En Debug el NDK no pone ningún -O (= -O0): glslang tardaba varias veces más en compilar cada preset (segundos de un núcleo
# entero compitiendo con el emulador) y el .so de Debug pasaba de 50 MB. Las opciones solo afectan a los subdirectorios de abajo.
get_directory_property(SV_SAVED_COMPILE_OPTIONS COMPILE_OPTIONS)
add_compile_options("$<$<CONFIG:Debug>:-O2>" "$<$<CONFIG:Debug>:-DNDEBUG>" "$<$<CONFIG:Debug>:-g0>")

# glslang como submódulo
set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/glslang)

# SPIRV-Cross para reflexión
set(SPIRV_CROSS_CLI OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_CPP OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_MSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_REFLECT ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_STATIC ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/SPIRV-Cross)

set_directory_properties(PROPERTIES COMPILE_OPTIONS "${SV_SAVED_COMPILE_OPTIONS}")

add_library(shader_engine SHARED
        native-lib.cpp
        SlangParser.cpp
        VulkanPipeline.cpp
        VulkanRenderer.cpp
        SlangCompiler.cpp
        SlangSemantics.cpp
        FramebufferPool.cpp
)

target_include_directories(shader_engine PRIVATE
        third_party/glslang
        third_party/SPIRV-Cross
)

# R01: el motor también optimizado en Debug (el render de cada frame corre aquí). Conserva -g y no define NDEBUG: los logs SV_DIAG
# de diagnóstico siguen saliendo en Debug.
target_compile_options(shader_engine PRIVATE "$<$<CONFIG:Debug>:-O2>")

target_link_libraries(shader_engine
        ${vulkan-lib}
        ${android-lib}
        ${log-lib}
        ${jnigraphics-lib}
        glslang
        SPIRV
        glslang-default-resource-limits
        spirv-cross-core
        spirv-cross-glsl
        spirv-cross-reflect
)
