#pragma once
#include <vector>
#include <string>
#include <map>
#include <cstdint>
#include <android/asset_manager.h>
#include "SlangParser.hpp"

enum ShaderStage { STAGE_VERTEX, STAGE_FRAGMENT };

// Semánticas estándar de RetroArch/Slang
enum class SlangSemantic {
    Unknown,
    MVP,
    SourceSize,
    OriginalSize,
    OutputSize,
    FinalViewportSize,
    FrameCount,
    FrameDirection,
    FrameTimeDelta,
    Rotation,
    TotalSubFrames,
    CurrentSubFrame,
    OriginalFPS,
    OriginalAspect,
    OriginalAspectRotated,
    Gyroscope,
    Accelerometer,
    AccelerometerRest, // vec3: media de las 30 primeras lecturas del acelerómetro tras activar los sensores
    FloatParameter, // Parámetros #pragma
    PassOutputSize,
    LUTSize,
    OriginalHistorySize
};

// Un campo dentro de un UBO o push constant, con su offset real reflejado.
struct ReflectedField {
    size_t offset;
    size_t size; // bytes del tipo (vec4=16, mat4=64, float=4, uint=4...)
    SlangSemantic semantic = SlangSemantic::Unknown;
    int index = -1; // Para OriginalHistoryN, PassOutputN, etc.
};

struct ReflectedBlock {
    bool present = false;
    uint32_t binding = 0;       // solo aplica a UBO, no a push constant
    size_t size = 0;
    std::map<std::string, ReflectedField> fields; // "MVP" -> {offset, size}
};

struct ReflectedSampler {
    uint32_t binding;
    std::string name; // "Source", "Original", "PassOutput0", nombre de LUT, etc.
};

struct SlangReflection {
    std::map<uint32_t, ReflectedBlock> ubos;
    ReflectedBlock pushConstant;
    std::vector<ReflectedSampler> samplers;
};

class SlangCompiler {
public:
    // Expande recursivamente los #include y #pragma include_optional, limpia CRLF
    // e inyecta directivas #line para depuración (estilo RetroArch).
    static std::string fullPreprocess(const std::string& rootPath, AAssetManager* assetManager);

    // Vacía las cachés de ficheros/preprocesado. Llamar al cargar un preset nuevo.
    static void clearCaches();

    // R07: carpeta de la caché de SPIR-V en disco (la caché de la app); "" = solo la de memoria. Se llama una vez al iniciar el motor.
    static void setDiskCacheDir(const std::string& dir);

    // Lee un fichero de texto (asset o content://) con la caché de la carga. "" si no existe o está vacío.
    static std::string readTextFile(const std::string& path, AAssetManager* assetManager);

    // Une dir + rel y resuelve "./" y "../" (también en URIs SAF). Si rel ya es una URI se devuelve tal cual.
    static std::string resolvePath(const std::string& dir, const std::string& rel);

    // Compila fuente GLSL cruda (ya con #version, #include resueltos) a SPIR-V.
    // NO inyecta ni modifica nada del texto del shader.
    // Con caché de SPIR-V (T15) y compilaciones serializadas.
    static std::vector<uint32_t> compileRaw(const std::string& source, ShaderStage stage);
    // La compilación en sí, sin caché (solo la usa compileRaw, que ya tiene el bloqueo).
    static std::vector<uint32_t> compileRawUncached(const std::string& source, ShaderStage stage);

    // Refleja un módulo SPIR-V ya compilado.
    static SlangReflection reflect(const std::vector<uint32_t>& spirv);

    // Mantenemos esto temporalmente para no romper la compilación de VulkanPipeline hasta el siguiente paso
    static std::vector<uint32_t> compileShader(const std::string& source, const std::string& filename, ShaderStage stage, const std::vector<std::string>& textureIds = {}, std::vector<ShaderParameter>* outParams = nullptr);

    // Escanea metadatos de parámetros sin compilar el shader completo.
    static void scanParameters(const std::string& source, std::vector<ShaderParameter>& outParams);
};
