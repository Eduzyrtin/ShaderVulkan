#include "DebugLog.hpp"
#include "SlangCompiler.hpp"
#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <SPIRV/GlslangToSpv.h>
#include "spirv_glsl.hpp"
#include <android/log.h>
#include <sstream>
#include <regex>

#define LOG_TAG "SlangCompiler"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include <set>
#include <mutex>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unordered_map>
#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>
#include <utime.h>
#include <android/asset_manager.h>

static bool g_glslangInit = false;

// --- Utilidades de ruta (estilo RetroArch) ---
static std::string dirOf(const std::string& path) {
    size_t pos = path.find_last_of('/');
    return (pos == std::string::npos) ? "" : path.substr(0, pos + 1);
}

static std::string collapseDotDot(const std::string& path) {
    std::vector<std::string> parts;
    std::stringstream ss(path);
    std::string seg;
    while (std::getline(ss, seg, '/')) {
        if (seg.empty() || seg == ".") continue;
        if (seg == "..") {
            if (!parts.empty() && parts.back() != "..") parts.pop_back();
            else parts.push_back(seg);
        } else {
            parts.push_back(seg);
        }
    }
    std::string result;
    for (size_t i = 0; i < parts.size(); i++) {
        result += parts[i];
        if (i + 1 < parts.size()) result += "/";
    }
    return result;
}

static std::string normalizePath(const std::string& path) {
    const std::string marker = "/document/";
    size_t pos = path.find(marker);
    if (pos == std::string::npos) return collapseDotDot(path);
    std::string prefix = path.substr(0, pos + marker.size());
    std::string tail = path.substr(pos + marker.size());
    std::string unified;
    for (size_t i = 0; i < tail.size(); ) {
        if (tail.compare(i, 3, "%2F") == 0) { unified += '/'; i += 3; }
        else { unified += tail[i]; i += 1; }
    }
    return prefix + collapseDotDot(unified);
}

// Cachés válidas durante la carga de un preset (se vacían en clearCaches()).
// Evitan releer por SAF el mismo include decenas de veces (crt-royale: ~300 lecturas)
// y repreprocesar el mismo .slang para descubrir parámetros y luego para compilar.
static std::mutex g_cacheMutex;
static std::unordered_map<std::string, std::string> g_fileCache;
static std::unordered_map<std::string, std::string> g_preprocessCache;

void SlangCompiler::clearCaches() {
    std::lock_guard<std::mutex> l(g_cacheMutex);
    g_fileCache.clear();
    g_preprocessCache.clear();
}

static std::string readShaderFileUncached(const std::string& path, AAssetManager* assetManager) {
    extern std::string fetchFromJava(const std::string& path);
    if (path.find("content://") == 0) return fetchFromJava(path);
    if (!assetManager) return "";
    AAsset* a = AAssetManager_open(assetManager, path.c_str(), AASSET_MODE_BUFFER);
    if (!a) return "";
    off_t l = AAsset_getLength(a);
    if (l <= 0) { AAsset_close(a); return ""; }
    std::string c(l, '\0');
    AAsset_read(a, &c[0], l);
    AAsset_close(a);
    return c;
}

static std::string readShaderFile(const std::string& path, AAssetManager* assetManager) {
    {
        std::lock_guard<std::mutex> l(g_cacheMutex);
        auto it = g_fileCache.find(path);
        if (it != g_fileCache.end()) return it->second;
    }
    std::string content = readShaderFileUncached(path, assetManager);
    if (!content.empty()) {
        std::lock_guard<std::mutex> l(g_cacheMutex);
        g_fileCache[path] = content;
    }
    return content;
}

static std::string preprocessRecursive(const std::string& path, AAssetManager* assetManager, std::set<std::string>& seen, int depth, bool isRoot) {
    if (depth > 16 || seen.count(path)) return "";
    seen.insert(path);

    std::string source = readShaderFile(path, assetManager);
    if (source.empty()) return "";

    // Limpieza de CRLF (\r)
    source.erase(std::remove(source.begin(), source.end(), '\r'), source.end());

    std::istringstream stream(source);
    std::string line, result;
    std::string currentDir = dirOf(path);
    std::string filename = path.substr(path.find_last_of('/') + 1);

    static const std::regex includeRegex(R"(^\s*#include\s+["<]([^">]+)[">])");
    static const std::regex optionalRegex(R"(^\s*#pragma\s+include_optional\s+["<]([^">]+)[">])");

    int lineNum = 1;
    bool firstLine = true;

    while (std::getline(stream, line)) {
        if (isRoot && firstLine) {
            firstLine = false;
            // RetroArch exige que la primera línea sea el #version
            if (line.find("#version") != std::string::npos) {
                result += line + "\n";
                // Inyectamos la extensión necesaria para usar nombres de archivo en #line
                result += "#extension GL_GOOGLE_cpp_style_line_directive : require\n";

                // Macros de entorno estándar Slang/RetroArch con guardas para evitar redefinición
                result += "#ifndef SLANG\n#define SLANG 1\n#endif\n";
                result += "#ifndef VULKAN\n#define VULKAN 1\n#endif\n";
                result += "#ifndef GLSL\n#define GLSL 1\n#endif\n";
                result += "#ifndef PARAMETER_UNIFORM\n#define PARAMETER_UNIFORM\n#endif\n";

                // Macros de capacidades del motor (Feature Defines)
                result += "#ifndef _HAS_ORIGINALASPECT_UNIFORMS\n#define _HAS_ORIGINALASPECT_UNIFORMS\n#endif\n";
                result += "#ifndef _HAS_FRAMETIME_UNIFORMS\n#define _HAS_FRAMETIME_UNIFORMS\n#endif\n";
                result += "#ifndef _HAS_SENSOR_UNIFORMS\n#define _HAS_SENSOR_UNIFORMS\n#endif\n";
                result += "#ifndef _HAS_ROTATION_UNIFORM\n#define _HAS_ROTATION_UNIFORM\n#endif\n";

                // Iniciamos el mapeo de líneas para el archivo principal
                result += "#line " + std::to_string(lineNum + 1) + " \"" + filename + "\"\n";
                lineNum++;
                continue;
            }
        }

        // Filtro barato: std::regex por línea era el cuello de botella de CPU.
        // Solo las líneas cuyo primer carácter no blanco es '#' y que contienen "include" pueden coincidir.
        size_t firstNonWs = line.find_first_not_of(" \t");
        bool maybeDirective = firstNonWs != std::string::npos && line[firstNonWs] == '#' &&
                              line.find("include", firstNonWs) != std::string::npos;

        std::smatch m;
        bool isInclude = maybeDirective && std::regex_search(line, m, includeRegex);
        bool isOptional = false;
        if (maybeDirective && !isInclude) isOptional = std::regex_search(line, m, optionalRegex);

        if (isInclude || isOptional) {
            std::string incPath = m[1].str();
            std::string fullIncPath = normalizePath(currentDir + incPath);
            std::string incFilename = incPath.substr(incPath.find_last_of('/') + 1);

            // Guard para el inicio del archivo incluido
            result += "#line 1 \"" + incFilename + "\"\n";

            std::string expanded = preprocessRecursive(fullIncPath, assetManager, seen, depth + 1, false);
            if (!expanded.empty()) {
                result += expanded + "\n";
            } else if (!isOptional) {
                LOGE("Error: Include no encontrado: %s", fullIncPath.c_str());
                result += "// MISSING INCLUDE: " + fullIncPath + "\n";
            }

            // RESTAURACIÓN CRÍTICA: Volvemos al archivo padre y a la línea exacta después del include
            result += "#line " + std::to_string(lineNum + 1) + " \"" + filename + "\"\n";
        } else {
            result += line + "\n";
        }
        lineNum++;
    }

    seen.erase(path);
    return result;
}

std::string SlangCompiler::fullPreprocess(const std::string& rootPath, AAssetManager* assetManager) {
    {
        std::lock_guard<std::mutex> l(g_cacheMutex);
        auto it = g_preprocessCache.find(rootPath);
        if (it != g_preprocessCache.end()) return it->second;
    }
    std::set<std::string> seen;
    std::string result = preprocessRecursive(rootPath, assetManager, seen, 0, true);
    if (!result.empty()) {
        std::lock_guard<std::mutex> l(g_cacheMutex);
        g_preprocessCache[rootPath] = result;
    }
    return result;
}

std::string SlangCompiler::readTextFile(const std::string& path, AAssetManager* assetManager) {
    return readShaderFile(path, assetManager);
}

std::string SlangCompiler::resolvePath(const std::string& dir, const std::string& rel) {
    if (rel.find("://") != std::string::npos) return rel;
    std::string joined = dir + rel;
    // Una URI SAF sin "/document/" perdería el "//" del esquema al colapsar segmentos vacíos
    size_t scheme = joined.find("://");
    if (scheme != std::string::npos && joined.find("/document/") == std::string::npos) {
        return joined.substr(0, scheme + 3) + collapseDotDot(joined.substr(scheme + 3));
    }
    return normalizePath(joined);
}

// Memoria residente del proceso en MB (diagnóstico: un pase que dispare la memoria acaba matado por lmkd sin dejar traza)
static long residentMB() {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof line, f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) { kb = atol(line + 6); break; }
    }
    fclose(f);
    return kb < 0 ? -1 : kb / 1024;
}

// Caché de SPIR-V (T15): compilar el GLSL es lo caro (segundos en crt-royale) y no depende de los tamaños, así que con varias
// salidas (modo dual) la segunda reutiliza lo que ya compiló la primera y solo crea sus pipelines. Clave: hash del fuente ya
// preprocesado + etapa (+ longitud). Pocas entradas, descartando la más antigua. g_compileMutex serializa las compilaciones:
// si dos salidas piden lo mismo a la vez, la segunda espera y acierta en la caché (y el pico de memoria no se duplica).
static std::mutex g_compileMutex;
struct SpirvCacheEntry { uint64_t key; size_t length; std::vector<uint32_t> spirv; };
static std::vector<SpirvCacheEntry> g_spirvCache;
static constexpr size_t kSpirvCacheMax = 64;

// Versión de la caché: forma parte de la clave. Subirla si cambia cómo se compila (versión de glslang, entorno de destino, cabecera que
// añade compileShader...) para que no se reutilice SPIR-V de antes.
static constexpr uint64_t kSpirvCacheVersion = 1;

static uint64_t fnv1a(const void* data, size_t n, uint64_t h = 1469598103934665603ULL) { // FNV-1a de 64 bits
    const unsigned char* p = (const unsigned char*)data;
    for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}

static uint64_t spirvKey(const std::string& source, ShaderStage stage) {
    uint64_t h = fnv1a(source.data(), source.size());
    h ^= kSpirvCacheVersion * 0xC2B2AE3D27D4EB4FULL;
    return h ^ (stage == STAGE_VERTEX ? 0x9E3779B97F4A7C15ULL : 0ULL);
}

// --- Caché de SPIR-V en disco (R07) ---
// La de memoria (arriba) muere con el proceso: tras cerrar la app, o con un perfil por app que cambia de preset, cada carga volvía a
// pasar todo por glslang (segundos de CPU en crt-royale, compitiendo con el emulador). En disco, en la caché de la app (el sistema la
// vacía si le falta espacio), un fichero por etapa: cabecera (marca, versión, suma de comprobación) + palabras de SPIR-V. Un fichero
// dañado no pasa la comprobación: se borra y se compila. Todo con g_compileMutex.
static std::string g_spirvDiskDir;
static constexpr uint32_t kDiskMagic = 0x43535653u;   // "SVSC"
static constexpr size_t kDiskMaxFiles = 1024;          // al pasar de aquí se borran los menos usados hasta kDiskKeepFiles
static constexpr size_t kDiskKeepFiles = 768;

struct DiskHeader { uint32_t magic; uint32_t version; uint64_t checksum; uint64_t words; };

static std::string spirvDiskPath(uint64_t key, size_t length) {
    char name[64];
    snprintf(name, sizeof name, "/%016llx_%zx.spv", (unsigned long long)key, length);
    return g_spirvDiskDir + name;
}

static std::vector<uint32_t> readSpirvFromDisk(uint64_t key, size_t length) {
    if (g_spirvDiskDir.empty()) return {};
    const std::string path = spirvDiskPath(key, length);
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return {};
    std::vector<uint32_t> out;
    DiskHeader h = {};
    bool ok = fread(&h, sizeof h, 1, f) == 1 && h.magic == kDiskMagic && h.version == (uint32_t)kSpirvCacheVersion &&
              h.words >= 5 && h.words < (64u << 20);
    if (ok) {
        out.resize((size_t)h.words);
        ok = fread(out.data(), 4, out.size(), f) == out.size() && out[0] == 0x07230203u &&
             fnv1a(out.data(), out.size() * 4) == h.checksum;
    }
    fclose(f);
    if (!ok) {
        LOGE("SPIR-V cache: fichero dañado, se borra (%s)", path.c_str());
        remove(path.c_str());
        return {};
    }
    utime(path.c_str(), nullptr); // usado ahora: la poda borra primero los que llevan más tiempo sin usarse
    return out;
}

static void writeSpirvToDisk(uint64_t key, size_t length, const std::vector<uint32_t>& spirv) {
    if (g_spirvDiskDir.empty() || spirv.empty()) return;
    const std::string path = spirvDiskPath(key, length);
    const std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    DiskHeader h = {kDiskMagic, (uint32_t)kSpirvCacheVersion, fnv1a(spirv.data(), spirv.size() * 4), (uint64_t)spirv.size()};
    bool ok = fwrite(&h, sizeof h, 1, f) == 1 && fwrite(spirv.data(), 4, spirv.size(), f) == spirv.size();
    ok = (fclose(f) == 0) && ok;
    // Escribir aparte y renombrar: un cierre a medias nunca deja un fichero con el nombre bueno y el contenido cortado
    if (!ok || rename(tmp.c_str(), path.c_str()) != 0) remove(tmp.c_str());
}

// Si hay demasiados ficheros, se borran los que llevan más tiempo sin usarse
static void pruneSpirvDiskCache() {
    DIR* d = opendir(g_spirvDiskDir.c_str());
    if (!d) return;
    std::vector<std::pair<time_t, std::string>> files;
    while (dirent* e = readdir(d)) {
        std::string n = e->d_name;
        if (n.size() < 5) continue;
        const std::string full = g_spirvDiskDir + "/" + n;
        if (n.compare(n.size() - 4, 4, ".tmp") == 0) { remove(full.c_str()); continue; } // restos de una escritura cortada
        if (n.compare(n.size() - 4, 4, ".spv") != 0) continue;
        struct stat st;
        if (stat(full.c_str(), &st) == 0) files.emplace_back(st.st_mtime, full);
    }
    closedir(d);
    if (files.size() <= kDiskMaxFiles) return;
    std::sort(files.begin(), files.end());
    for (size_t i = 0; i + kDiskKeepFiles < files.size(); i++) remove(files[i].second.c_str());
    LOGI("SPIR-V cache: poda de la caché en disco (%zu ficheros, quedan %zu)", files.size(), kDiskKeepFiles);
}

void SlangCompiler::setDiskCacheDir(const std::string& dir) {
    std::lock_guard<std::mutex> l(g_compileMutex);
    if (dir.empty()) { g_spirvDiskDir.clear(); return; }
    const std::string d = dir + "/spirv";
    mkdir(d.c_str(), 0700); // puede existir ya
    struct stat st;
    if (stat(d.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) { LOGE("SPIR-V cache: no se pudo usar %s", d.c_str()); g_spirvDiskDir.clear(); return; }
    g_spirvDiskDir = d;
    pruneSpirvDiskCache();
}

std::vector<uint32_t> SlangCompiler::compileRaw(const std::string& source, ShaderStage stage) {
    std::lock_guard<std::mutex> compileLock(g_compileMutex);
    const uint64_t key = spirvKey(source, stage);
    for (const auto& e : g_spirvCache) {
        if (e.key == key && e.length == source.size()) {
            LOGI("SPIR-V cache: hit (%s, %zu palabras)", stage == STAGE_VERTEX ? "VS" : "FS", e.spirv.size());
            return e.spirv;
        }
    }
    // R07: antes de compilar, la caché en disco (un preset ya usado en otra sesión no vuelve a pasar por glslang)
    std::vector<uint32_t> spirv = readSpirvFromDisk(key, source.size());
    const bool fromDisk = !spirv.empty();
    if (!fromDisk) spirv = compileRawUncached(source, stage);
    if (!spirv.empty()) {
        LOGI("SPIR-V cache: %s (%s, %zu palabras)", fromDisk ? "disco" : "miss", stage == STAGE_VERTEX ? "VS" : "FS", spirv.size());
        if (g_spirvCache.size() >= kSpirvCacheMax) g_spirvCache.erase(g_spirvCache.begin());
        g_spirvCache.push_back({key, source.size(), spirv});
        if (!fromDisk) writeSpirvToDisk(key, source.size(), spirv);
    }
    return spirv;
}

std::vector<uint32_t> SlangCompiler::compileRawUncached(const std::string& source, ShaderStage stage) {
    if (!g_glslangInit) { glslang::InitializeProcess(); g_glslangInit = true; }

    using clk = std::chrono::steady_clock;
    auto ms = [](clk::time_point a) { return (long long)std::chrono::duration_cast<std::chrono::milliseconds>(clk::now() - a).count(); };
    const char* stageName = (stage == STAGE_VERTEX) ? "VS" : "FS";
    auto t0 = clk::now();

    EShLanguage lang = (stage == STAGE_VERTEX) ? EShLangVertex : EShLangFragment;
    glslang::TShader shader(lang);
    const char* src = source.c_str();
    shader.setStrings(&src, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, lang, glslang::EShClientVulkan, 450);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);

    EShMessages messages = static_cast<EShMessages>(EShMsgDefault | EShMsgVulkanRules | EShMsgSpvRules);
    TBuiltInResource res = *GetDefaultResources();
    LOGI("SVDIAG_COMPILE %s: parse (%zu bytes de fuente, RSS=%ld MB)", stageName, source.size(), residentMB());
    if (!shader.parse(&res, 100, false, messages)) {
        LOGE("compileRaw ERROR en %s: %s", (lang == EShLangVertex ? "Vertex" : "Fragment"), shader.getInfoLog());
        return {};
    }
    long long tParse = ms(t0);

    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages)) {
        LOGE("compileRaw LINK ERROR: %s", program.getInfoLog());
        return {};
    }

    std::vector<uint32_t> spirv;
    glslang::GlslangToSpv(*program.getIntermediate(lang), spirv);
    LOGI("SVDIAG_COMPILE %s: OK parse=%lld ms total=%lld ms SPIR-V=%zu palabras RSS=%ld MB",
         stageName, tParse, ms(t0), spirv.size(), residentMB());
    return spirv;
}

static SlangSemantic nameToSemantic(const std::string& name, int& outIndex) {
    outIndex = -1;
    if (name == "MVP") return SlangSemantic::MVP;
    if (name == "SourceSize") return SlangSemantic::SourceSize;
    if (name == "OriginalSize") return SlangSemantic::OriginalSize;
    if (name == "OutputSize") return SlangSemantic::OutputSize;
    if (name == "FinalViewportSize") return SlangSemantic::FinalViewportSize;
    if (name == "FrameCount") return SlangSemantic::FrameCount;
    if (name == "FrameDirection") return SlangSemantic::FrameDirection;
    if (name == "FrameTimeDelta") return SlangSemantic::FrameTimeDelta;
    if (name == "TotalSubFrames") return SlangSemantic::TotalSubFrames;
    if (name == "CurrentSubFrame") return SlangSemantic::CurrentSubFrame;
    if (name == "Rotation") return SlangSemantic::Rotation;
    if (name == "OriginalFPS") return SlangSemantic::OriginalFPS;
    if (name == "OriginalAspect") return SlangSemantic::OriginalAspect;
    if (name == "OriginalAspectRotated") return SlangSemantic::OriginalAspectRotated;
    if (name == "Gyroscope") return SlangSemantic::Gyroscope;
    if (name == "Accelerometer") return SlangSemantic::Accelerometer;
    if (name == "AccelerometerRest") return SlangSemantic::AccelerometerRest;

    // Soporte para PassOutputNSize
    if (name.find("PassOutput") == 0 && name.find("Size") != std::string::npos) {
        try {
            std::string idxStr = name.substr(10);
            idxStr = idxStr.substr(0, idxStr.find("Size"));
            outIndex = std::stoi(idxStr);
            return SlangSemantic::PassOutputSize;
        } catch(...) {}
    }

    // Soporte para OriginalHistoryNSize
    if (name.find("OriginalHistory") == 0 && name.find("Size") != std::string::npos) {
        try {
            std::string idxStr = name.substr(15);
            idxStr = idxStr.substr(0, idxStr.find("Size"));
            outIndex = std::stoi(idxStr);
            return SlangSemantic::OriginalHistorySize;
        } catch(...) {}
    }

    // Soporte para <NombreLUT>Size
    if (name.find("Size") != std::string::npos && name.size() > 4) {
        // En Slang, si una textura se llama "Foo", su tamaño es "FooSize"
        // Intentaremos marcarlo como LUTSize y el motor buscará la textura por el nombre (name sin "Size")
        return SlangSemantic::LUTSize;
    }

    return SlangSemantic::FloatParameter;
}

SlangReflection SlangCompiler::reflect(const std::vector<uint32_t>& spirv) {
    SlangReflection out;
    if (spirv.empty()) return out;

    LOGI("SlangCompiler::reflect: Iniciando reflexión de SPIR-V...");
    spirv_cross::CompilerGLSL comp(spirv);
    auto resources = comp.get_shader_resources();

    for (auto& ubo : resources.uniform_buffers) {
        ReflectedBlock block;
        auto type = comp.get_type(ubo.type_id);
        block.present = true;
        block.binding = comp.get_decoration(ubo.id, spv::DecorationBinding);
        block.size = comp.get_declared_struct_size(type);
        for (uint32_t i = 0; i < type.member_types.size(); i++) {
            std::string name = comp.get_member_name(type.self, i);
            size_t offset = comp.type_struct_member_offset(type, i);
            size_t msize = comp.get_declared_struct_member_size(type, i);
            int idx = -1;
            block.fields[name] = {offset, msize, nameToSemantic(name, idx), idx};
        }
        out.ubos[block.binding] = block;
    }

    for (auto& pc : resources.push_constant_buffers) {
        auto type = comp.get_type(pc.type_id);
        out.pushConstant.present = true;
        out.pushConstant.size = comp.get_declared_struct_size(type);
        for (uint32_t i = 0; i < type.member_types.size(); i++) {
            std::string name = comp.get_member_name(type.self, i);
            size_t offset = comp.type_struct_member_offset(type, i);
            size_t msize = comp.get_declared_struct_member_size(type, i);
            int idx = -1;
            out.pushConstant.fields[name] = {offset, msize, nameToSemantic(name, idx), idx};
        }
        break;
    }

    for (auto& img : resources.sampled_images) {
        uint32_t binding = comp.get_decoration(img.id, spv::DecorationBinding);
        out.samplers.push_back({binding, img.name});
    }

    return out;
}

// Implementación temporal para no romper VulkanPipeline.cpp
std::vector<uint32_t> SlangCompiler::compileShader(const std::string& source, const std::string& filename, ShaderStage stage, const std::vector<std::string>& textureIds, std::vector<ShaderParameter>* outParams) {
    // Escaneamos parámetros para el preset manager (mismo parser que el descubrimiento principal)
    if (outParams && stage == STAGE_FRAGMENT) scanParameters(source, *outParams);

    // Por ahora, usamos el header mínimo que sugirió el usuario
    std::string head = "#version 450\n";
    head += "#ifndef SLANG\n #define SLANG 1\n#endif\n";
    head += "#ifndef VULKAN\n #define VULKAN 1\n#endif\n";
    head += "precision highp float;\n";

    // Separamos stages (lógica simplificada de antes)
    std::string body;
    std::istringstream stream(source);
    std::string line;
    bool inTarget = false;
    bool inOther = false;
    std::string targetTag = (stage == STAGE_VERTEX) ? "#pragma stage vertex" : "#pragma stage fragment";

    while (std::getline(stream, line)) {
        if (line.find("#version") != std::string::npos) continue;
        if (line.find("#pragma stage") != std::string::npos) {
            if (line.find(targetTag) != std::string::npos) { inTarget = true; inOther = false; }
            else { inTarget = false; inOther = true; }
            continue;
        }
        if (inTarget || (!inOther && line.find("#pragma") == std::string::npos)) {
            body += line + "\n";
        }
    }

    return compileRaw(head + body, stage);
}

void SlangCompiler::scanParameters(const std::string& source, std::vector<ShaderParameter>& outParams) {
    // Formato: #pragma parameter ID "Description" initial min max [step]
    // Mismo criterio que slang_process.c de RetroArch: la línea empieza por "#pragma parameter", el ID llega hasta el
    // primer espacio/tab (admite '-' y otros símbolos: "ntsc-row0") y los números se leen con strtod (notación
    // científica, '+'...). Los "parámetros" informativos (min == max, descripción "-----" o en blanco) también se
    // devuelven: la UI decide cómo mostrarlos. Sin std::regex: el fuente ya preprocesado puede pesar cientos de KB.
    static const char kTag[] = "#pragma parameter ";
    constexpr size_t kTagLen = sizeof(kTag) - 1;
    const char* const base = source.c_str();
    const size_t n = source.size();
    size_t pos = 0;
    while (pos < n) {
        size_t eol = source.find('\n', pos);
        if (eol == std::string::npos) eol = n;
        const char* s = base + pos;
        const char* end = base + eol;
        pos = eol + 1;

        while (s < end && (*s == ' ' || *s == '\t')) s++;
        if ((size_t)(end - s) < kTagLen || strncmp(s, kTag, kTagLen) != 0) continue;
        s += kTagLen;
        while (s < end && (*s == ' ' || *s == '\t')) s++;

        const char* idStart = s;
        while (s < end && *s != ' ' && *s != '\t' && *s != '\r') s++;
        if (s == idStart) continue;
        std::string name(idStart, s);

        while (s < end && (*s == ' ' || *s == '\t')) s++;
        if (s >= end || *s != '"') continue;
        const char* descStart = ++s;
        while (s < end && *s != '"') s++;
        if (s >= end) continue;
        std::string desc(descStart, s);
        s++;

        // strtod necesita un búfer terminado en NUL que no se salga de la línea
        std::string rest(s, end);
        const char* p = rest.c_str();
        float v[4] = {0, 0, 0, 0};
        int count = 0;
        for (; count < 4; count++) {
            char* e = nullptr;
            double d = strtod(p, &e);
            if (e == p) break;
            v[count] = (float)d;
            p = e;
        }
        if (count < 3) continue;
        float stepVal = (count == 4) ? v[3] : 0.01f;

        bool exists = false;
        for (const auto& q : outParams) if (q.name == name) { exists = true; break; }
        if (!exists) outParams.push_back({0, name, desc, v[0], v[1], v[2], stepVal});
    }
}
