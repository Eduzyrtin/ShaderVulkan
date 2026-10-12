#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>
#include <map>

enum ScaleType {
    SCALE_SOURCE,
    SCALE_VIEWPORT,
    SCALE_ABSOLUTE
};

struct ShaderPass {
    int index;
    std::string filename;
    std::string extension;
    std::string type; // "vert" or "frag"

    // Slang standard properties
    bool filterLinear = false;
    ScaleType scaleType = SCALE_SOURCE;  // tipo "global" (scale_typeN), solo informativo
    ScaleType scaleTypeX = SCALE_SOURCE; // scale_type_xN, o scale_typeN si no existe
    ScaleType scaleTypeY = SCALE_SOURCE; // scale_type_yN, o scale_typeN si no existe
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    std::string alias;
    bool floatFramebuffer = false;
    bool srgbFramebuffer = false;
    bool wrapModeRepeat = false; // true = repeat, false = clamp_to_edge
    bool mipmapInput = false;    // mipmap_inputN: la entrada de este pase necesita cadena de mipmaps
    uint32_t frameCountMod = 0;  // frame_count_modN: FrameCount % frameCountMod (0 = sin módulo)
    bool internalGlass = false;  // pase interno del Modo Glass (glass_resolve): lo añade el motor, no viene del preset
    bool scaleSpecified = false; // el preset trae scale_type / scale_type_x / scale_type_y para este pase (FBO_SCALE_FLAG_VALID de RetroArch)
    std::string pragmaFormat;    // #pragma format del shader (T41), p. ej. "R16G16B16A16_SFLOAT"; vacío = no declara
    bool usesFragCoord = false;  // R13: el shader usa gl_FragCoord (depende de la orientación del framebuffer en el que escribe)
    std::string dir;             // T45: carpeta de la que se leen sus shaders (vacío = la del preset); distinta en los pases de un preset combinado
    int passBase = 0;            // T45: sumar a los PassOutputN / PassFeedbackN numéricos del shader (su posición en el preset de origen)
};

struct ShaderTexture {
    std::string id;
    std::string path;
    bool linear = true;
    bool wrapModeRepeat = false;
    bool mipmap = false;         // <id>_mipmap: generar cadena de mipmaps para la LUT
};

struct ShaderParameter {
    int shaderId;
    std::string name;
    std::string description;
    float defaultValue;
    float min;
    float max;
    float step;
};

struct ShaderPreset {
    std::string description;
    std::string basePath;
    std::vector<ShaderPass> passes;
    std::vector<ShaderParameter> parameters;
    std::vector<ShaderTexture> textures;
    std::map<std::string, float> parameterOverrides; // Nuevo: Para valores como "bright = 1.0"
    int feedbackPass = -1;      // feedback_pass: pase cuya salida se realimenta (-1 = no definido)
    bool valid = true;          // false si una cadena de #reference no se pudo resolver
    std::string error;          // motivo cuando valid == false
};

class SlangParser {
public:
    using FileReader = std::function<std::string(const std::string& path)>;

    // Interpreta un .slangp. 'basePath' es la carpeta del preset (con '/' final); 'read' lee los
    // presets referenciados con #reference. Como en RetroArch, el preset raíz (el último de la cadena
    // de #reference) aporta pases y texturas, y cada preset de la cadena sobreescribe parámetros y rutas
    // de texturas. 'preset.basePath' queda en la carpeta del preset raíz.
    static ShaderPreset parse(const std::string& content, const std::string& basePath, const FileReader& read);

    // Une dos presets ya analizados (T45, "Shader Preset Prepend/Append" de RetroArch): los pases de 'first' van primero y los de
    // 'second' después, sobre su salida. Las texturas se juntan (la primera con un id gana), los parámetros se resuelven en el conjunto
    // y, si los dos fijan el mismo, vale el valor de 'second'. Cada pase conserva su carpeta; los PassOutputN numéricos de 'second' se
    // desplazan. Si el resultado no es válido (pases de más), queda valid = false con el motivo en error.
    static ShaderPreset combine(const ShaderPreset& first, const ShaderPreset& second);
};
