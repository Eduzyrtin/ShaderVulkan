#include "DebugLog.hpp"
#include "SlangParser.hpp"
#include "SlangCompiler.hpp"
#include <algorithm>
#include <cctype>
#include <set>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "SlangParser"
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) SV_DIAG(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Igual que SHADER_MAX_REFERENCE_DEPTH de RetroArch (video_shader_parse.c)
static const int MAX_REFERENCE_DEPTH = 16;

namespace {

// Un .slangp ya leído: claves/valores y las rutas de sus líneas "#reference"
struct PresetFile {
    std::map<std::string, std::string> kv;
    std::vector<std::string> refs;
    std::string dir; // carpeta del fichero (con '/' final), contra la que se resuelven sus rutas
};

std::string trimWs(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

// Quita unas comillas envolventes si las hay
std::string unquote(const std::string& s) {
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') return s.substr(1, s.size() - 2);
    return s;
}

// Estilo config_file de RetroArch: una línea que empieza por '#' es un comentario (salvo "#reference");
// en un valor sin comillas, el '#' abre un comentario; dentro de comillas se conserva.
PresetFile parseFile(const std::string& content, const std::string& dir) {
    PresetFile f;
    f.dir = dir;
    std::istringstream ss(content);
    std::string line;
    while (std::getline(ss, line)) {
        std::string t = trimWs(line);
        if (t.empty()) continue;
        if (t[0] == '#') {
            static const std::string kRef = "#reference";
            if (t.compare(0, kRef.size(), kRef) == 0 && t.size() > kRef.size() &&
                (t[kRef.size()] == ' ' || t[kRef.size()] == '\t')) {
                std::string path = trimWs(t.substr(kRef.size()));
                if (!path.empty() && path[0] == '"') {
                    size_t close = path.find('"', 1);
                    path = path.substr(1, close == std::string::npos ? std::string::npos : close - 1);
                } else {
                    size_t hash = path.find('#');
                    if (hash != std::string::npos) path = trimWs(path.substr(0, hash));
                }
                if (!path.empty()) f.refs.push_back(path);
            }
            continue;
        }
        size_t eq = t.find('=');
        if (eq == std::string::npos) continue;
        std::string key = trimWs(t.substr(0, eq));
        std::string val = trimWs(t.substr(eq + 1));
        if (!val.empty() && val[0] == '"') {
            size_t close = val.find('"', 1);
            val = val.substr(1, close == std::string::npos ? std::string::npos : close - 1);
        } else {
            size_t hash = val.find('#');
            if (hash != std::string::npos) val = trimWs(val.substr(0, hash));
            val = unquote(val);
        }
        if (!key.empty()) f.kv[key] = val;
    }
    return f;
}

std::string dirOf(const std::string& path) {
    size_t pos = path.find_last_of('/');
    return pos == std::string::npos ? "" : path.substr(0, pos + 1);
}

bool toFloat(const std::string& s, float& out) {
    if (s.empty()) return false;
    try {
        size_t used = 0;
        float v = std::stof(s, &used);
        if (used != s.size()) return false;
        out = v;
        return true;
    } catch (...) {
        return false;
    }
}

int toInt(const std::string& s, int def) {
    try { return std::stoi(s); } catch (...) { return def; }
}

bool toBool(const std::string& s) { return s == "true" || s == "1"; }

// Claves del propio formato de preset (por pase o globales). Cualquier otra clave numérica es,
// como mucho, un override de parámetro (y solo vale si algún shader declara ese #pragma parameter).
bool isPresetKey(const std::string& key, const std::set<std::string>& textureIds) {
    if (key == "shaders" || key == "textures" || key == "feedback_pass") return true;

    size_t digits = 0;
    while (digits < key.size() && isdigit((unsigned char)key[key.size() - 1 - digits])) digits++;
    if (digits > 0 && digits < key.size()) {
        static const char* kPassKeys[] = {
            "shader", "filter_linear", "wrap_mode", "frame_count_mod", "float_framebuffer",
            "srgb_framebuffer", "alias", "mipmap_input", "scale_type_x", "scale_type_y",
            "scale_type", "scale_x", "scale_y", "scale"
        };
        std::string stem = key.substr(0, key.size() - digits);
        for (const char* k : kPassKeys) if (stem == k) return true;
    }

    for (const auto& id : textureIds) {
        if (key == id || key == id + "_linear" || key == id + "_wrap_mode" || key == id + "_mipmap") return true;
    }
    return false;
}

// Ruta 'full' (ya resuelta) expresada respecto a 'dir'. Solo para rutas sin esquema (assets).
std::string relativeTo(const std::string& dir, const std::string& full) {
    auto split = [](const std::string& s) {
        std::vector<std::string> parts;
        std::stringstream ss(s);
        std::string seg;
        while (std::getline(ss, seg, '/')) if (!seg.empty()) parts.push_back(seg);
        return parts;
    };
    std::vector<std::string> d = split(dir), f = split(full);
    size_t common = 0;
    while (common < d.size() && common + 1 < f.size() && d[common] == f[common]) common++;
    std::string rel;
    for (size_t i = common; i < d.size(); i++) rel += "../";
    for (size_t i = common; i < f.size(); i++) rel += f[i] + (i + 1 < f.size() ? "/" : "");
    return rel;
}

} // namespace

ShaderPreset SlangParser::parse(const std::string& content, const std::string& basePath, const FileReader& read) {
    ShaderPreset preset;
    preset.basePath = basePath;
    LOGI("Parseando preset (%zu bytes) en '%s'", content.length(), basePath.c_str());

    // 1) Cadena de la primera referencia hasta el preset raíz (el que define 'shaders')
    std::vector<PresetFile> chain;
    chain.push_back(parseFile(content, basePath));
    std::set<std::string> visited;
    while (!chain.back().refs.empty()) {
        if ((int)chain.size() > MAX_REFERENCE_DEPTH) {
            preset.valid = false;
            preset.error = "cadena de #reference demasiado profunda (¿circular?)";
            LOGE("%s", preset.error.c_str());
            return preset;
        }
        std::string path = SlangCompiler::resolvePath(chain.back().dir, chain.back().refs[0]);
        if (!visited.insert(path).second) {
            preset.valid = false;
            preset.error = "#reference circular: " + path;
            LOGE("%s", preset.error.c_str());
            return preset;
        }
        std::string text = read(path);
        if (text.empty()) {
            preset.valid = false;
            preset.error = "no se pudo leer el preset de #reference: " + path;
            LOGE("%s", preset.error.c_str());
            return preset;
        }
        LOGI("#reference -> %s", path.c_str());
        chain.push_back(parseFile(text, dirOf(path)));
    }
    const PresetFile& root = chain.back();
    preset.basePath = root.dir;
    const auto& kv = root.kv;

    auto get = [&](const std::string& key) -> const std::string* {
        auto it = kv.find(key);
        return it == kv.end() ? nullptr : &it->second;
    };

    // 2) Pases del preset raíz
    if (!get("shaders")) {
        preset.valid = false;
        preset.error = chain.size() > 1 ? "el preset raíz de la cadena de #reference no define 'shaders'"
                                        : "el preset no define 'shaders'";
        LOGE("%s", preset.error.c_str());
    }
    int numShaders = 0;
    if (const std::string* s = get("shaders")) numShaders = toInt(*s, 0);
    if (const std::string* s = get("feedback_pass")) preset.feedbackPass = toInt(*s, -1);

    for (int i = 0; i < numShaders; ++i) {
        std::string idx = std::to_string(i);
        const std::string* file = get("shader" + idx);
        if (!file) continue;

        ShaderPass pass;
        pass.index = i;
        pass.filename = *file;

        size_t dotPos = pass.filename.find_last_of('.');
        if (dotPos != std::string::npos) {
            pass.extension = pass.filename.substr(dotPos);
            pass.filename = pass.filename.substr(0, dotPos);
        }

        if (auto v = get("filter_linear" + idx)) pass.filterLinear = toBool(*v);
        if (auto v = get("float_framebuffer" + idx)) pass.floatFramebuffer = toBool(*v);
        if (auto v = get("srgb_framebuffer" + idx)) pass.srgbFramebuffer = toBool(*v);
        if (auto v = get("alias" + idx)) pass.alias = *v;
        if (auto v = get("mipmap_input" + idx)) pass.mipmapInput = toBool(*v);
        if (auto v = get("frame_count_mod" + idx)) pass.frameCountMod = (uint32_t)std::max(0, toInt(*v, 0));
        if (auto v = get("wrap_mode" + idx)) pass.wrapModeRepeat = (*v == "repeat" || *v == "mirrored_repeat");

        auto parseScaleType = [](const std::string& st, ScaleType& out) {
            if (st == "source") out = SCALE_SOURCE;
            else if (st == "viewport") out = SCALE_VIEWPORT;
            else if (st == "absolute") out = SCALE_ABSOLUTE;
        };
        // scale_typeN fija ambos ejes; scale_type_xN / scale_type_yN los sobreescriben por separado
        pass.scaleSpecified = get("scale_type" + idx) || get("scale_type_x" + idx) || get("scale_type_y" + idx);
        if (auto v = get("scale_type" + idx)) {
            parseScaleType(*v, pass.scaleType);
            pass.scaleTypeX = pass.scaleTypeY = pass.scaleType;
        }
        if (auto v = get("scale_type_x" + idx)) parseScaleType(*v, pass.scaleTypeX);
        if (auto v = get("scale_type_y" + idx)) parseScaleType(*v, pass.scaleTypeY);

        float s = 1.0f;
        if (auto v = get("scale" + idx)) toFloat(*v, s);
        pass.scaleX = s;
        pass.scaleY = s;
        if (auto v = get("scale_x" + idx)) toFloat(*v, pass.scaleX);
        if (auto v = get("scale_y" + idx)) toFloat(*v, pass.scaleY);

        preset.passes.push_back(pass);
        LOGI("Pase %d: %s (escala %.1fx%.1f, lineal %d, frame_count_mod %u)", i, pass.filename.c_str(),
             pass.scaleX, pass.scaleY, pass.filterLinear, pass.frameCountMod);
    }

    // 3) Texturas (LUTs) del preset raíz
    std::set<std::string> textureIds;
    if (const std::string* list = get("textures")) {
        std::stringstream tss(*list);
        std::string texName;
        while (std::getline(tss, texName, ';')) {
            texName = trimWs(texName);
            const std::string* path = get(texName);
            if (texName.empty() || !path) continue;
            ShaderTexture st;
            st.id = texName;
            st.path = *path;
            if (auto v = get(texName + "_linear")) st.linear = toBool(*v);
            if (auto v = get(texName + "_mipmap")) st.mipmap = toBool(*v);
            if (auto v = get(texName + "_wrap_mode")) st.wrapModeRepeat = (*v == "repeat" || *v == "mirrored_repeat");
            preset.textures.push_back(st);
            textureIds.insert(texName);
            LOGI("Textura: %s -> %s", st.id.c_str(), st.path.c_str());
        }
    }

    // 4) Overrides: todos los presets de la cadena y de las demás #reference, en postorden (el más profundo
    //    primero, el preset cargado el último), como video_shader_gather_reference_path_list de RetroArch.
    std::vector<PresetFile> overrides;
    std::function<void(const PresetFile&, int)> gather = [&](const PresetFile& f, int depth) {
        if (depth > MAX_REFERENCE_DEPTH) return;
        for (const auto& ref : f.refs) {
            std::string path = SlangCompiler::resolvePath(f.dir, ref);
            std::string text = read(path);
            if (text.empty()) {
                LOGW("#reference no legible, se ignora: %s", path.c_str());
                continue;
            }
            gather(parseFile(text, dirOf(path)), depth + 1);
        }
        overrides.push_back(f);
    };
    gather(chain.front(), 0);

    for (const auto& layer : overrides) {
        for (const auto& [key, val] : layer.kv) {
            if (isPresetKey(key, textureIds)) continue;
            float v;
            if (toFloat(val, v)) preset.parameterOverrides[key] = v;
        }
        // Una textura redefinida por un preset de la cadena: su ruta es relativa a ese preset
        for (auto& tex : preset.textures) {
            auto it = layer.kv.find(tex.id);
            if (it == layer.kv.end() || (layer.dir == root.dir && layer.kv == root.kv)) continue; // el raíz ya aportó la suya
            std::string full = SlangCompiler::resolvePath(layer.dir, it->second);
            tex.path = (full.find("://") != std::string::npos) ? full : relativeTo(root.dir, full);
            LOGI("Textura %s sobreescrita por %s -> %s", tex.id.c_str(), layer.dir.c_str(), tex.path.c_str());
        }
    }

    LOGI("Preset: %zu pases, %zu texturas, %zu overrides, %zu presets en la cadena de overrides",
         preset.passes.size(), preset.textures.size(), preset.parameterOverrides.size(), overrides.size());
    return preset;
}

// Tope de pasadas de un conjunto combinado (GFX_MAX_SHADERS de RetroArch)
static const size_t MAX_COMBINED_PASSES = 64;

ShaderPreset SlangParser::combine(const ShaderPreset& first, const ShaderPreset& second) {
    ShaderPreset out;
    out.basePath = first.basePath;
    if (!first.valid || !second.valid) {
        out.valid = false;
        out.error = !first.valid ? first.error : second.error;
        return out;
    }
    if (first.passes.size() + second.passes.size() > MAX_COMBINED_PASSES) {
        out.valid = false;
        out.error = "el conjunto tiene demasiados pases (" + std::to_string(first.passes.size() + second.passes.size()) +
                    ", máximo " + std::to_string(MAX_COMBINED_PASSES) + ")";
        LOGE("%s", out.error.c_str());
        return out;
    }
    out.description = first.description.empty() ? second.description : first.description;

    // Pases: los de 'first' no cambian de sitio; los de 'second' se desplazan por los de 'first'. Cada pase recuerda su carpeta
    // (el preset combinado ya no tiene una sola) y, si el shader nombra PassOutputN con número, el desplazamiento que lo corrige.
    std::set<std::string> aliases;
    for (const auto& p : first.passes) {
        ShaderPass q = p;
        if (q.dir.empty()) q.dir = first.basePath;
        if (!q.alias.empty()) aliases.insert(q.alias);
        out.passes.push_back(q);
    }
    const int shift = (int)first.passes.size();
    for (const auto& p : second.passes) {
        ShaderPass q = p;
        if (q.dir.empty()) q.dir = second.basePath;
        q.index = shift + p.index;
        q.passBase = shift + p.passBase;
        if (!q.alias.empty() && !aliases.insert(q.alias).second)
            LOGW("alias '%s' repetido en los dos presets; los pases de la primera mitad que lo usen verán el segundo", q.alias.c_str());
        out.passes.push_back(q);
    }

    // Texturas: la primera con un id gana (como RetroArch). Las rutas de 'second' se reexpresan respecto a la carpeta de 'first'.
    out.textures = first.textures;
    std::set<std::string> ids;
    for (const auto& t : first.textures) ids.insert(t.id);
    for (const auto& t : second.textures) {
        if (!ids.insert(t.id).second) {
            LOGW("textura '%s' ya definida por el primer preset, se ignora la del segundo", t.id.c_str());
            continue;
        }
        ShaderTexture q = t;
        std::string full = SlangCompiler::resolvePath(second.basePath, t.path);
        q.path = (full.find("://") != std::string::npos) ? full : relativeTo(first.basePath, full);
        out.textures.push_back(q);
    }

    // Parámetros: se declaran de nuevo con los shaders del conjunto (discoverParameters); los valores fijados por los presets se
    // juntan y, si los dos fijan el mismo, gana 'second'
    out.parameterOverrides = first.parameterOverrides;
    for (const auto& [k, v] : second.parameterOverrides) out.parameterOverrides[k] = v;

    out.feedbackPass = first.feedbackPass >= 0 ? first.feedbackPass
                       : (second.feedbackPass >= 0 ? second.feedbackPass + shift : -1);
    LOGI("combine: %zu + %zu pases, %zu texturas, %zu valores de parámetros", first.passes.size(), second.passes.size(),
         out.textures.size(), out.parameterOverrides.size());
    return out;
}
