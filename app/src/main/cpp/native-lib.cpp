#include "DebugLog.hpp"
#include <jni.h>
#include <string>
#include <cstdio>
#include <cstring>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vector>
#include <memory>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>
#include <sys/resource.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstdlib>
#include <android/api-level.h>
#include "SlangParser.hpp"
#include "SlangCompiler.hpp"
#include "VulkanPipeline.hpp"
#include "VulkanRenderer.hpp"
#include "LoadProgress.hpp"

#define LOG_TAG "ShaderVulkan_Native"
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Salidas del motor (T15): una instancia de VulkanRenderer por salida, todas sobre el mismo VkInstance/VkDevice/cola. La salida 0
// es la de siempre (Captura/Glass en una ventana); la 1 existe solo en modo dual. Cada salida tiene su superficie, swapchain,
// región de entrada, resolución virtual, set de pipelines compilado e historial.
static constexpr int kMaxOutputs = 2;

struct VulkanContext {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    AAssetManager* assetManager = nullptr;
    jobject appContext = nullptr;
    std::shared_ptr<VulkanRenderer> renderers[kMaxOutputs];
} g_ctx;

// g_ctxMutex: ciclo de vida (init/release). g_ptrMutex: SOLO para copiar/cambiar el puntero del renderer,
// nunca se mantiene durante trabajo real. Así processFrame (que compila pases durante segundos) no
// bloquea al resto de llamadas JNI que llegan desde el hilo principal.
static std::mutex g_ctxMutex;
static std::mutex g_ptrMutex;

static std::shared_ptr<VulkanRenderer> getRenderer(int output = 0) {
    if (output < 0 || output >= kMaxOutputs) return nullptr;
    std::lock_guard<std::mutex> l(g_ptrMutex);
    return g_ctx.renderers[output];
}

// Copia de los renderers que existen (salida 0 primero). Preset, parámetros, modo, giro y sensores van a todas las salidas (P2).
static std::vector<std::shared_ptr<VulkanRenderer>> getAllRenderers() {
    std::vector<std::shared_ptr<VulkanRenderer>> all;
    std::lock_guard<std::mutex> l(g_ptrMutex);
    for (auto& r : g_ctx.renderers) if (r) all.push_back(r);
    return all;
}

// Estado que una salida creada más tarde (modo dual) tiene que recibir para igualarse a las que ya existen
static std::mutex g_replayMutex;
static ShaderPreset g_lastPreset;
static bool g_hasLastPreset = false;

// Preset propio de la zona B (T18): mientras exista, la salida 1 no sigue al preset de la salida 0 y los parámetros se editan en la
// salida elegida con setEditOutput. Sin él (P2) todo va a todas las salidas, como siempre.
static ShaderPreset g_presetB;
static std::atomic<bool> g_hasPresetB{false};
static std::atomic<int> g_editOutput{0};

// Renderers a los que van los cambios de parámetros: todos, o solo la salida en edición si la zona B tiene su propio preset
static std::vector<std::shared_ptr<VulkanRenderer>> paramTargets() {
    if (!g_hasPresetB.load()) return getAllRenderers();
    auto r = getRenderer(g_editOutput.load());
    if (!r) r = getRenderer(0);
    return r ? std::vector<std::shared_ptr<VulkanRenderer>>{r} : std::vector<std::shared_ptr<VulkanRenderer>>{};
}
// Renderer del que se leen los parámetros: el de la salida en edición si la zona B tiene su propio preset; si no, la salida 0
static std::shared_ptr<VulkanRenderer> paramSource() {
    if (g_hasPresetB.load()) {
        auto r = getRenderer(g_editOutput.load());
        if (r) return r;
    }
    return getRenderer(0);
}
static std::atomic<int> g_outputMode{0};
static std::atomic<uint32_t> g_rotation{0};
JavaVM* g_vm = nullptr;

// Cache de clases y métodos para evitar ClassNotFoundException en hilos nativos
jclass g_bridgeClass = nullptr;
jclass g_paramClass = nullptr;
jmethodID g_fetchContentMid = nullptr;
jmethodID g_fetchTextureMid = nullptr;
jmethodID g_sensorsNeededMid = nullptr;
jmethodID g_autoRegionMid = nullptr;
jmethodID g_paramConstructor = nullptr;

// Test de reflexión
extern "C" void runReflectionSanityCheck();

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return -1;

    // Cacheamos la clase bridge y sus métodos
    jclass bridgeLocal = env->FindClass("com/sanabria/shadervulkan/native/VulkanEngineBridge");
    if (bridgeLocal) {
        g_bridgeClass = (jclass)env->NewGlobalRef(bridgeLocal);
        g_fetchContentMid = env->GetStaticMethodID(g_bridgeClass, "fetchExternalContent", "(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;");
        g_fetchTextureMid = env->GetStaticMethodID(g_bridgeClass, "fetchTexture", "(Landroid/content/Context;Ljava/lang/String;)Landroid/graphics/Bitmap;");
        g_sensorsNeededMid = env->GetStaticMethodID(g_bridgeClass, "onSensorsNeeded", "(Z)V");
        g_autoRegionMid = env->GetStaticMethodID(g_bridgeClass, "onAutoRegion", "(FFFFZ)V");
    }

    // Cacheamos la clase de parámetros
    jclass paramLocal = env->FindClass("com/sanabria/shadervulkan/model/ShaderParameter");
    if (paramLocal) {
        g_paramClass = (jclass)env->NewGlobalRef(paramLocal);
        g_paramConstructor = env->GetMethodID(g_paramClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;FFFFF)V");
    }

    return JNI_VERSION_1_6;
}

std::string fetchFromJava(const std::string& path) {
    if (!g_vm || !g_ctx.appContext || !g_bridgeClass || !g_fetchContentMid) return "";
    JNIEnv* env;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr); attached = true;
    }
    std::string result = "";
    jstring jpath = env->NewStringUTF(path.c_str());
    jstring jcontent = (jstring)env->CallStaticObjectMethod(g_bridgeClass, g_fetchContentMid, g_ctx.appContext, jpath);
    if (jcontent) {
        const char* nativeStr = env->GetStringUTFChars(jcontent, nullptr);
        result = nativeStr;
        env->ReleaseStringUTFChars(jcontent, nativeStr);
    }
    env->DeleteLocalRef(jpath);
    if (attached) g_vm->DetachCurrentThread();
    return result;
}

// Avisa a Kotlin de que el preset activo empieza o deja de usar los sensores (puede llamarse desde cualquier hilo nativo)
void notifySensorsNeeded(bool needed) {
    if (!g_vm || !g_bridgeClass || !g_sensorsNeededMid) return;
    JNIEnv* env;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr); attached = true;
    }
    env->CallStaticVoidMethod(g_bridgeClass, g_sensorsNeededMid, needed ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

// La detección de bandas negras (T07) comunica la caja de contenido encontrada: fracciones 0..1 de la pantalla y la
// orientación de la captura analizada. Se llama desde el hilo de render.
void notifyAutoRegion(float l, float t, float r, float b, bool landscape) {
    if (!g_vm || !g_bridgeClass || !g_autoRegionMid) return;
    JNIEnv* env;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr); attached = true;
    }
    env->CallStaticVoidMethod(g_bridgeClass, g_autoRegionMid, l, t, r, b, landscape ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

// --- Cachés en disco (R07) ---
// La de SPIR-V vive en SlangCompiler. Aquí la de pipelines del driver (VkPipelineCache): una para todo el dispositivo, cargada al
// iniciar y guardada tras cada compilación. Sin ella, cada carga de un preset volvía a pasar todos sus pases por el compilador de la
// GPU aunque el SPIR-V fuera el mismo. Un fichero de otro driver u otra GPU (actualización del sistema) se descarta por su cabecera.
static std::string g_cacheDir;
static VkPipelineCache g_pipelineCache = VK_NULL_HANDLE;
static std::mutex g_pipelineCacheFileMutex;
static size_t g_pipelineCacheSavedSize = 0;

VkPipelineCache sharedPipelineCache() { return g_pipelineCache; }

static std::string pipelineCachePath() { return g_cacheDir + "/pipeline_cache.bin"; }

static std::string cacheDirFromContext(JNIEnv* env, jobject context) {
    std::string out;
    if (!context) return out;
    jclass ctxCls = env->GetObjectClass(context);
    jmethodID getCacheDir = ctxCls ? env->GetMethodID(ctxCls, "getCacheDir", "()Ljava/io/File;") : nullptr;
    jobject file = getCacheDir ? env->CallObjectMethod(context, getCacheDir) : nullptr;
    if (env->ExceptionCheck()) { env->ExceptionClear(); file = nullptr; }
    if (file) {
        jclass fileCls = env->GetObjectClass(file);
        jmethodID getPath = env->GetMethodID(fileCls, "getAbsolutePath", "()Ljava/lang/String;");
        jstring jpath = getPath ? (jstring)env->CallObjectMethod(file, getPath) : nullptr;
        if (env->ExceptionCheck()) { env->ExceptionClear(); jpath = nullptr; }
        if (jpath) {
            const char* c = env->GetStringUTFChars(jpath, nullptr);
            if (c) { out = c; env->ReleaseStringUTFChars(jpath, c); }
            env->DeleteLocalRef(jpath);
        }
        env->DeleteLocalRef(fileCls);
        env->DeleteLocalRef(file);
    }
    if (ctxCls) env->DeleteLocalRef(ctxCls);
    return out;
}

static void createPipelineCache() {
    std::vector<uint8_t> data;
    if (!g_cacheDir.empty()) {
        if (FILE* f = fopen(pipelineCachePath().c_str(), "rb")) {
            fseek(f, 0, SEEK_END);
            const long size = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (size > 0 && size < (256L << 20)) {
                data.resize((size_t)size);
                if (fread(data.data(), 1, data.size(), f) != data.size()) data.clear();
            }
            fclose(f);
        }
    }
    // Cabecera de la versión 1: tamaño, versión, vendorID, deviceID y el UUID de la caché del driver
    if (data.size() >= 32) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(g_ctx.physicalDevice, &props);
        uint32_t hdr[4];
        memcpy(hdr, data.data(), sizeof(hdr));
        const bool same = hdr[0] >= 32 && hdr[1] == VK_PIPELINE_CACHE_HEADER_VERSION_ONE && hdr[2] == props.vendorID &&
                          hdr[3] == props.deviceID && memcmp(data.data() + 16, props.pipelineCacheUUID, VK_UUID_SIZE) == 0;
        if (!same) { LOGI("Caché de pipelines: de otro driver o GPU, se descarta"); data.clear(); }
    } else {
        data.clear();
    }
    VkPipelineCacheCreateInfo ci = {VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO, nullptr, 0, data.size(), data.empty() ? nullptr : data.data()};
    if (vkCreatePipelineCache(g_ctx.device, &ci, nullptr, &g_pipelineCache) != VK_SUCCESS) {
        ci.initialDataSize = 0;
        ci.pInitialData = nullptr;
        if (vkCreatePipelineCache(g_ctx.device, &ci, nullptr, &g_pipelineCache) != VK_SUCCESS) g_pipelineCache = VK_NULL_HANDLE;
    }
    g_pipelineCacheSavedSize = data.size();
    LOGI("Caché de pipelines: %s (%zu bytes leídos)", g_pipelineCache ? "lista" : "no disponible", data.size());
}

// Desde el hilo de compilación al terminar un set. Solo escribe si el driver añadió algo.
void savePipelineCacheToDisk() {
    std::lock_guard<std::mutex> l(g_pipelineCacheFileMutex);
    if (g_pipelineCache == VK_NULL_HANDLE || g_cacheDir.empty() || g_ctx.device == VK_NULL_HANDLE) return;
    size_t size = 0;
    if (vkGetPipelineCacheData(g_ctx.device, g_pipelineCache, &size, nullptr) != VK_SUCCESS || size == 0) return;
    if (size == g_pipelineCacheSavedSize) return;
    std::vector<uint8_t> data(size);
    if (vkGetPipelineCacheData(g_ctx.device, g_pipelineCache, &size, data.data()) != VK_SUCCESS) return;
    const std::string path = pipelineCachePath();
    const std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    bool ok = fwrite(data.data(), 1, size, f) == size;
    ok = (fclose(f) == 0) && ok;
    if (ok && rename(tmp.c_str(), path.c_str()) == 0) {
        g_pipelineCacheSavedSize = size;
        LOGI("Caché de pipelines: guardada (%zu bytes)", size);
    } else {
        remove(tmp.c_str());
    }
}

jobject fetchBitmapFromJava(const std::string& path) {
    if (!g_vm || !g_ctx.appContext || !g_bridgeClass || !g_fetchTextureMid) return nullptr;
    JNIEnv* env;
    bool attached = false;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(&env, nullptr); attached = true;
    }
    jobject result = nullptr;
    jstring jpath = env->NewStringUTF(path.c_str());
    jobject bitmap = env->CallStaticObjectMethod(g_bridgeClass, g_fetchTextureMid, g_ctx.appContext, jpath);
    if (bitmap) result = env->NewGlobalRef(bitmap);
    env->DeleteLocalRef(jpath);
    if (attached) g_vm->DetachCurrentThread();
    return result;
}

// Rendimiento (T66). El contador cuenta los frames que salen de la salida 0 y el tiempo de CPU de cada renderFrame; con el contador
// apagado no se mide nada. El tope de FPS descarta frames de captura que llegan antes de tiempo.
static std::atomic<bool> g_perfEnabled{false};
static std::atomic<uint64_t> g_perfFrames{0};
static std::atomic<uint64_t> g_perfMicros{0};
static std::atomic<int> g_fpsCap{0};          // 0 = sin tope

static int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// --- Pistas de rendimiento al sistema (R16, ADPF) ---
// Android 13+ (APerformanceHint del NDK, API 33): por cada hilo que dibuja (el de captura y el de redibujado de Glass animado) una sesión
// con la duración objetivo (el intervalo entre frames) y lo que tardó cada frame. El planificador sube la frecuencia o pasa el hilo a un
// núcleo mayor solo cuando el frame se acerca al objetivo (junto al emulador, un núcleo pequeño a baja frecuencia alarga el frame) y la
// baja cuando sobra. Se resuelve con dlsym: el motor tiene que seguir cargando en Android 10–12, donde no existe.
namespace {
struct HintApi {
    void* (*getManager)() = nullptr;
    void* (*createSession)(void*, const int32_t*, size_t, int64_t) = nullptr;
    int (*updateTarget)(void*, int64_t) = nullptr;
    int (*reportActual)(void*, int64_t) = nullptr;
    void (*closeSession)(void*) = nullptr;
    void* manager = nullptr;
};

const HintApi* hintApi() {
    static const HintApi* api = []() -> const HintApi* {
        if (android_get_device_api_level() < 33) return nullptr;
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return nullptr;
        static HintApi a;
        a.getManager = (void* (*)())dlsym(lib, "APerformanceHint_getManager");
        a.createSession = (void* (*)(void*, const int32_t*, size_t, int64_t))dlsym(lib, "APerformanceHint_createSession");
        a.updateTarget = (int (*)(void*, int64_t))dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
        a.reportActual = (int (*)(void*, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
        a.closeSession = (void (*)(void*))dlsym(lib, "APerformanceHint_closeSession");
        if (!a.getManager || !a.createSession || !a.updateTarget || !a.reportActual || !a.closeSession) return nullptr;
        a.manager = a.getManager();
        return a.manager ? &a : nullptr;
    }();
    return api;
}

// Una sesión por hilo (thread_local): se crea con el primer frame y se cierra al terminar el hilo
class PerfHintSession {
public:
    ~PerfHintSession() { if (m_session) hintApi()->closeSession(m_session); }
    // workNs: lo que tardó este frame; targetNs: el intervalo entre frames que se quiere sostener
    void report(int64_t workNs, int64_t targetNs) {
        if (m_failed || workNs <= 0 || targetNs <= 0) return;
        const HintApi* api = hintApi();
        if (!api) { m_failed = true; return; }
        if (!m_session) {
            const int32_t tid = gettid();
            m_session = api->createSession(api->manager, &tid, 1, targetNs);
            if (!m_session) { m_failed = true; LOGI("ADPF: sin sesión de rendimiento en este dispositivo"); return; }
            m_targetNs = targetNs;
            LOGI("ADPF: sesión para el hilo %d, objetivo %.1f ms", (int)tid, targetNs / 1e6);
        } else if (std::llabs(targetNs - m_targetNs) * 10 > m_targetNs) { // se actualiza solo con cambios de más de un 10 %
            api->updateTarget(m_session, targetNs);
            m_targetNs = targetNs;
        }
        api->reportActual(m_session, workNs);
    }
private:
    void* m_session = nullptr;
    int64_t m_targetNs = 0;
    bool m_failed = false;
};

thread_local PerfHintSession t_perfHint;

// Intervalo entre capturas (media móvil), acotado a 8–50 ms: es el objetivo del hilo de captura. Solo lo usa ese hilo.
int64_t g_hintLastCaptureUs = 0;
double g_hintIntervalUs = 16667.0;
int64_t captureTargetNs(int64_t now) {
    if (g_hintLastCaptureUs) {
        const double dt = std::min(std::max((double)(now - g_hintLastCaptureUs), 8000.0), 50000.0);
        g_hintIntervalUs += (dt - g_hintIntervalUs) * 0.1;
    }
    g_hintLastCaptureUs = now;
    return (int64_t)(g_hintIntervalUs * 1000.0);
}
} // namespace

// Tope de FPS de salida (T66). R09: por "crédito", no por un umbral fijo desde el último frame: cada captura que llega suma el tiempo
// transcurrido por el tope y se dibuja si hay al menos un frame de crédito. Con 60 FPS de entrada y tope 45 se dibujan 3 de cada 4
// (con el umbral fijo salían 30: una captura a 16,7 ms de la anterior no llegaba a los 20 ms y la siguiente sí). Solo lo llama el hilo
// de captura (processFrame).
static double g_capCredit = 1.0;
static int64_t g_capLastUs = 0;
static bool capAllowsFrame(int cap) {
    const int64_t now = nowUs();
    const double dt = g_capLastUs ? (double)(now - g_capLastUs) / 1e6 : 1.0;
    g_capLastUs = now;
    g_capCredit = std::min(g_capCredit + dt * cap, 1.5); // como mucho medio frame de margen acumulado tras una pausa
    if (g_capCredit < 0.98) return false;                // 0,98: tolerancia al temblor con el que llegan las capturas
    g_capCredit -= 1.0;
    return true;
}

// R05: hilo de redibujado de una salida. Redibuja con la última captura al terminar una compilación (si la pantalla está quieta no
// llegaría ningún frame nuevo), al tocar un parámetro y, en Glass animado, a cada frame. Antes cada petición lanzaba un std::thread
// nuevo (en Glass animado, uno por frame); ahora hay uno por renderer que duerme hasta que se le pide algo.
// Las peticiones se agrupan: un slider genera decenas por segundo. 'pending' baja justo antes de renderizar, así un cambio posterior
// programa otro redibujado. El estado es compartido con el hilo: sigue vivo aunque el renderer (y su RedrawWorker) desaparezcan.
struct RedrawState {
    std::mutex m;
    std::condition_variable cv;
    bool pending = false;
    bool paramPending = false; // R23: solo cambió un parámetro (puede no hacer falta redibujar)
    int64_t paramReqUs = 0;    // R23: cuándo se pidió el último
    bool stop = false;
    std::weak_ptr<VulkanRenderer> renderer;
};

// R23: en Captura, si ha llegado una captura hace menos de esto, la siguiente recogerá el parámetro nuevo: no se redibuja aparte
static constexpr int64_t kParamGraceUs = 50000;

static void redrawLoop(std::shared_ptr<RedrawState> s) {
    setpriority(PRIO_PROCESS, gettid(), -4); // como el hilo de captura (THREAD_PRIORITY_DISPLAY): en Glass es el que dibuja
    std::unique_lock<std::mutex> l(s->m);
    for (;;) {
        s->cv.wait(l, [&] { return s->pending || s->paramPending || s->stop; });
        if (s->stop) return;
        if (!s->pending) {
            // R23: solo un cambio de parámetro. Mientras se arrastra un slider en Captura, las capturas siguen llegando y ya dibujan
            // con el valor nuevo: redibujar además duplicaba el trabajo. Se redibuja solo si dejan de llegar (pantalla quieta).
            // El renderer se mira sin el mutex del estado (si esta copia fuera la última, destruirlo aquí volvería a cogerlo)
            const int64_t req = s->paramReqUs;
            bool capture = false;
            int64_t last = 0;
            l.unlock();
            if (auto r = s->renderer.lock()) { capture = !r->isGlassMode(); last = r->lastCaptureMicros(); }
            l.lock();
            if (s->stop) return;
            if (capture && !s->pending) {
                if (last >= req) { // ya se dibujó una captura con el valor nuevo
                    if (s->paramReqUs == req) s->paramPending = false;
                    continue;
                }
                if (nowUs() - last < kParamGraceUs) {
                    s->cv.wait_for(l, std::chrono::microseconds(kParamGraceUs)); // se vuelve a mirar después
                    continue;
                }
            }
        }
        s->pending = false;
        s->paramPending = false;
        l.unlock();
        if (auto r = s->renderer.lock()) {
            // En Glass no llegan frames de captura: cada redibujado de la salida 0 es un frame de salida
            const bool count = g_perfEnabled.load(std::memory_order_relaxed) && r->isGlassMode() && getRenderer(0) == r;
            const float animFps = r->getGlassAnimFps(); // R16: solo la animación de Glass tiene un ritmo que sostener
            const int64_t t0 = nowUs();
            const bool ok = r->renderFrame(nullptr);
            if (count && ok) { g_perfFrames.fetch_add(1, std::memory_order_relaxed); g_perfMicros.fetch_add((uint64_t)(nowUs() - t0), std::memory_order_relaxed); }
            if (ok && animFps > 0.0f) t_perfHint.report((nowUs() - t0) * 1000, (int64_t)(1e9f / animFps));
        }
        l.lock();
    }
}

class RedrawWorker {
public:
    explicit RedrawWorker(std::weak_ptr<VulkanRenderer> r) : m_state(std::make_shared<RedrawState>()) {
        m_state->renderer = std::move(r);
        m_thread = std::thread(redrawLoop, m_state);
    }
    ~RedrawWorker() {
        { std::lock_guard<std::mutex> l(m_state->m); m_state->stop = true; }
        m_state->cv.notify_one();
        if (!m_thread.joinable()) return;
        // Si el último dueño del renderer fuera el propio hilo (release() espera a que no quede ninguna copia, así que no debería
        // pasar), no puede esperarse a sí mismo: se suelta y termina solo al ver 'stop'
        if (m_thread.get_id() == std::this_thread::get_id()) m_thread.detach();
        else m_thread.join();
    }
    void request() {
        { std::lock_guard<std::mutex> l(m_state->m); m_state->pending = true; }
        m_state->cv.notify_one();
    }
    void requestParam() { // R23
        { std::lock_guard<std::mutex> l(m_state->m); m_state->paramPending = true; m_state->paramReqUs = nowUs(); }
        m_state->cv.notify_one();
    }
private:
    std::shared_ptr<RedrawState> m_state;
    std::thread m_thread;
};

// Crea un renderer sobre el dispositivo común (salida 0 al iniciar, o la 1 en modo dual).
static std::shared_ptr<VulkanRenderer> createRenderer() {
    auto newRenderer = std::make_shared<VulkanRenderer>(g_ctx.instance, g_ctx.physicalDevice, g_ctx.device, g_ctx.assetManager);
    auto worker = std::make_shared<RedrawWorker>(std::weak_ptr<VulkanRenderer>(newRenderer));
    newRenderer->setOnCompileReady([worker]() { worker->request(); });
    newRenderer->setOnParamChanged([worker]() { worker->requestParam(); });
    return newRenderer;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_initVulkan(JNIEnv *env, jobject thiz, jobject context, jobject assetManager) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    if (getRenderer()) return JNI_TRUE;

    g_ctx.assetManager = AAssetManager_fromJava(env, assetManager);
    // Una referencia global de un ciclo anterior (iniciar/cerrar) no se pierde: se libera antes de crear la nueva
    if (g_ctx.appContext) env->DeleteGlobalRef(g_ctx.appContext);
    g_ctx.appContext = env->NewGlobalRef(context);

    VkApplicationInfo appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "ShaderVulkan", 1, "NoEngine", 1, VK_API_VERSION_1_1};
    std::vector<const char*> ext = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ci = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &appInfo, 0, nullptr, (uint32_t)ext.size(), ext.data()};
    if (vkCreateInstance(&ci, nullptr, &g_ctx.instance) != VK_SUCCESS) return JNI_FALSE;

    uint32_t count = 0; vkEnumeratePhysicalDevices(g_ctx.instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devs(count); vkEnumeratePhysicalDevices(g_ctx.instance, &count, devs.data());
    g_ctx.physicalDevice = devs[0];

    uint32_t qCount = 0; vkGetPhysicalDeviceQueueFamilyProperties(g_ctx.physicalDevice, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> qProps(qCount); vkGetPhysicalDeviceQueueFamilyProperties(g_ctx.physicalDevice, &qCount, qProps.data());
    uint32_t gIdx = 0; for(uint32_t i=0; i<qCount; i++) if(qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { gIdx=i; break; }

    float qp = 1.0f; VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, gIdx, 1, &qp};
    std::vector<const char*> devExt = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME, VK_KHR_BIND_MEMORY_2_EXTENSION_NAME, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME, VK_KHR_MAINTENANCE1_EXTENSION_NAME};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, nullptr, 0, 1, &qci, 0, nullptr, (uint32_t)devExt.size(), devExt.data(), nullptr};
    if (vkCreateDevice(g_ctx.physicalDevice, &dci, nullptr, &g_ctx.device) != VK_SUCCESS) return JNI_FALSE;

    // R07: cachés en disco (SPIR-V y pipelines del driver) en la carpeta de caché de la app
    g_cacheDir = cacheDirFromContext(env, context);
    SlangCompiler::setDiskCacheDir(g_cacheDir);
    createPipelineCache();

    LOGI("initVulkan: Creando renderer...");
    auto newRenderer = createRenderer();
    {
        std::lock_guard<std::mutex> pl(g_ptrMutex);
        g_ctx.renderers[0] = std::move(newRenderer);
    }
    {
        std::lock_guard<std::mutex> rl(g_replayMutex);
        g_hasLastPreset = false;
    }
    LOGI("initVulkan: Renderer creado OK");

    return JNI_TRUE;
}

// Segunda salida (modo dual, T15). Comparte dispositivo y cola con la 0 y recibe su estado: preset, valores de los parámetros,
// modo y giro. La superficie, la región de entrada y la resolución virtual son de cada salida y las pone quien la activa.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_enableOutput(JNIEnv *env, jobject thiz, jint output) {
    if (output <= 0 || output >= kMaxOutputs) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    auto first = getRenderer(0);
    if (!first) return JNI_FALSE;
    if (getRenderer(output)) return JNI_TRUE;

    LOGI("enableOutput(%d): creando renderer", (int)output);
    auto r = createRenderer();
    r->setOutputMode(g_outputMode.load());
    r->setRotation(g_rotation.load());
    {
        std::lock_guard<std::mutex> rl(g_replayMutex);
        if (output == 1 && g_hasPresetB.load()) {
            r->setPreset(g_presetB); // zona B con preset propio (T18)
        } else if (g_hasLastPreset) {
            r->setPreset(g_lastPreset);
            // Los valores actuales (los del preset y los tocados por el usuario) viven en la salida 0
            for (const auto& kv : first->getParameterOverrides()) r->updateParameter(kv.first, kv.second);
        }
    }
    std::lock_guard<std::mutex> pl(g_ptrMutex);
    g_ctx.renderers[output] = std::move(r);
    return JNI_TRUE;
}

// Quita una salida. Quien la usaba debe haberle quitado antes la superficie (setOutputSurface(output, null)).
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_disableOutput(JNIEnv *env, jobject thiz, jint output) {
    if (output <= 0 || output >= kMaxOutputs) return;
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    std::shared_ptr<VulkanRenderer> old;
    {
        std::lock_guard<std::mutex> pl(g_ptrMutex);
        old = std::move(g_ctx.renderers[output]);
    }
    if (!old) return;
    LOGI("disableOutput(%d): destruyendo renderer", (int)output);
    // Un frame o una carga en vuelo puede tener todavía una copia del puntero (ver release())
    for (int i = 0; i < 3000 && old.use_count() > 1; i++) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    old.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setOutputSurface(JNIEnv *env, jobject thiz, jint output, jobject surface) {
    LOGI("JNI: setOutputSurface(%d) llamado, surface=%p", (int)output, (void*)surface);
    auto renderer = getRenderer(output);
    if (!renderer) {
        LOGE("JNI: setOutputSurface error - no hay renderer para la salida %d", (int)output);
        return JNI_FALSE;
    }

    if (surface == nullptr) {
        LOGI("JNI: setOutputSurface recibido con surface=null (probablemente surfaceDestroyed)");
        renderer->setSurface(nullptr);
        return JNI_TRUE;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    LOGI("JNI: ANativeWindow_fromSurface devolvió %p", (void*)window);

    renderer->setSurface(window);
    if (window) ANativeWindow_release(window);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_processFrame(JNIEnv *env, jobject thiz, jobject buffer) {
    // Copia del puntero, sin mantener ningún mutex global durante el render (puede tardar segundos
    // al compilar un preset grande); release() espera a que esta copia se libere.
    auto renderer = getRenderer();

    // DEBUG DIAGNOSTICO - QUITAR DESPUÉS
    if (!renderer) {
        static bool warned = false;
        if (!warned) {
            __android_log_print(ANDROID_LOG_ERROR, "SVDIAG_NATIVE", "ERROR: g_ctx.renderer es NULL!");
            warned = true;
        }
        return JNI_FALSE;
    }

    AHardwareBuffer* hwb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!hwb) {
        __android_log_print(ANDROID_LOG_ERROR, "SVDIAG_NATIVE", "AHardwareBuffer_fromHardwareBuffer devolvió NULL");
        return JNI_FALSE;
    }

    static int frameCounter = 0;
    if (frameCounter++ % 60 == 0) {
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(hwb, &desc);
        SV_DIAG(ANDROID_LOG_INFO, "SVDIAG_NATIVE", "JNI Recibido HWB=%p, %ux%u, fmt=%u, usage=%llu",
                           (void*)hwb, desc.width, desc.height, desc.format, (unsigned long long)desc.usage);
    }

    // Tope de FPS de salida (T66, R09): una captura que sobra se descarta (la captura sigue a su ritmo). No se ha tocado el buffer:
    // Kotlin lo devuelve ya y el motor sigue con el anterior.
    const int cap = g_fpsCap.load(std::memory_order_relaxed);
    if (cap > 0 && !capAllowsFrame(cap)) return JNI_FALSE;
    const bool perf = g_perfEnabled.load(std::memory_order_relaxed);
    const int64_t tStart = nowUs();
    const int64_t hintTargetNs = captureTargetNs(tStart); // R16
    bool ok = renderer->renderFrame(hwb);
    if (perf && ok) { g_perfFrames.fetch_add(1, std::memory_order_relaxed); g_perfMicros.fetch_add((uint64_t)(nowUs() - tStart), std::memory_order_relaxed); }
    // Modo dual (T15): el mismo buffer alimenta a las demás salidas con superficie; cada una importa el AHardwareBuffer en su
    // caché (sin copia) y hace su blit desde su rectángulo de la captura.
    for (int i = 1; i < kMaxOutputs; i++) {
        auto extra = getRenderer(i);
        if (extra && extra->hasSurface()) extra->renderFrame(hwb);
    }
    if (ok) t_perfHint.report((nowUs() - tStart) * 1000, hintTargetNs); // R16: todo el frame, con las salidas extra
    // R06: true = el motor lee ahora este buffer (y ya no el anterior, cuyos frames han terminado): Kotlin lo retiene hasta la
    // siguiente captura que se procese y devuelve el anterior. Se devuelve aunque no se haya podido dibujar (el renderer lo importó).
    return JNI_TRUE;
}

// R06: Kotlin va a cerrar el lector de la captura (fin de la sesión, giro) o a devolver el buffer que retenía: todas las salidas dejan de
// leer buffers de captura (esperan a la GPU) y sueltan sus imports.
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_releaseCaptureInput(JNIEnv *env, jobject thiz) {
    for (auto& r : getAllRenderers()) r->releaseInput();
}

// Analiza un .slangp (sin discoverParameters). Quien llama vacía antes la caché de #reference.
static ShaderPreset parseOnePreset(JNIEnv *env, jstring content, jstring basePath) {
    AAssetManager* assetManager = g_ctx.assetManager;
    const char* nativeContent = env->GetStringUTFChars(content, nullptr);
    const char* nativeBasePath = env->GetStringUTFChars(basePath, nullptr);
    ShaderPreset preset = SlangParser::parse(nativeContent, nativeBasePath,
        [assetManager](const std::string& path) { return SlangCompiler::readTextFile(path, assetManager); });
    env->ReleaseStringUTFChars(content, nativeContent);
    env->ReleaseStringUTFChars(basePath, nativeBasePath);
    return preset;
}

// Segunda mitad de la lectura de un preset (ya único o combinado): progreso de carga y descubrimiento de parámetros.
static void finishReadPreset(ShaderPreset& preset) {
    AAssetManager* assetManager = g_ctx.assetManager;
    if (!preset.valid) __android_log_print(ANDROID_LOG_ERROR, "SVDIAG_NATIVE", "Preset inválido: %s", preset.error.c_str());
    // Progreso de carga: 1 paso por pase en el análisis + 1 por textura y 1 por pase en la compilación posterior
    LoadProgress::begin((int)(preset.passes.size() * 2 + preset.textures.size()), true, "Leyendo shaders");
    if (!preset.valid) LoadProgress::setStatus("Preset no válido: " + preset.error);
    else if (preset.passes.empty()) LoadProgress::setStatus("El preset no define ningún pase");
    // Lo lento (I/O por SAF) va SIN g_ctxMutex y sin tocar el renderer: no bloquea al resto de llamadas JNI (ANR) ni se rompe si
    // ACTION_STOP_FULL destruye el renderer durante la carga.
    VulkanRenderer::discoverParameters(preset, assetManager);
}

// Lee y analiza un preset (lo lento: I/O por SAF), sin g_ctxMutex y sin tocar ningún renderer. Deja el progreso de carga en marcha.
static ShaderPreset readPreset(JNIEnv *env, jstring content, jstring basePath) {
    // Caché vacía antes de parsear: los presets referenciados con #reference se releen por si cambiaron
    SlangCompiler::clearCaches();
    ShaderPreset preset = parseOnePreset(env, content, basePath);
    finishReadPreset(preset);
    return preset;
}

// Conjunto de presets (T45): el primero va delante y cada siguiente se añade a continuación (prepend/append de RetroArch)
static ShaderPreset readPresetStack(JNIEnv *env, jobjectArray contents, jobjectArray basePaths) {
    SlangCompiler::clearCaches();
    const jsize n = env->GetArrayLength(contents);
    ShaderPreset combined;
    for (jsize i = 0; i < n; i++) {
        jstring c = (jstring)env->GetObjectArrayElement(contents, i);
        jstring b = (jstring)env->GetObjectArrayElement(basePaths, i);
        ShaderPreset one = parseOnePreset(env, c, b);
        env->DeleteLocalRef(c);
        env->DeleteLocalRef(b);
        combined = (i == 0) ? std::move(one) : SlangParser::combine(combined, one);
        if (!combined.valid) break;
    }
    finishReadPreset(combined);
    return combined;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_loadPreset(JNIEnv *env, jobject thiz, jstring content, jstring basePath) {
    if (!getRenderer()) return JNI_FALSE;
    ShaderPreset preset = readPreset(env, content, basePath);

    auto renderers = getAllRenderers();
    if (renderers.empty()) { LoadProgress::finish(); return JNI_FALSE; }
    LoadProgress::setLabel("Preparando compilación");
    {
        std::lock_guard<std::mutex> rl(g_replayMutex);
        g_lastPreset = preset;
        g_hasLastPreset = true;
    }
    // Mismo preset y mismo perfil en todas las salidas (P2); cada una compila con sus tamaños. Con preset propio en la zona B (T18),
    // la salida 1 se queda con el suyo.
    for (int i = 0; i < kMaxOutputs; i++) {
        if (i == 1 && g_hasPresetB.load()) continue;
        if (auto r = getRenderer(i)) r->setPreset(preset);
    }
    return JNI_TRUE;
}

// Preset propio para una salida (T18: la zona B con otro shader). Solo la salida 1.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_loadPresetForOutput(JNIEnv *env, jobject thiz, jstring content, jstring basePath, jint output) {
    if (output != 1 || !getRenderer()) return JNI_FALSE;
    ShaderPreset preset = readPreset(env, content, basePath);
    LoadProgress::setLabel("Preparando compilación");
    g_presetB = preset;
    g_hasPresetB.store(true);
    if (auto r = getRenderer(1)) r->setPreset(preset); // si la salida 1 aún no existe, enableOutput usa g_presetB
    else LoadProgress::finish();
    return JNI_TRUE;
}

// Conjunto de presets combinados (T45) para la salida 0 (o para la zona B con output = 1)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_loadPresetStack(JNIEnv *env, jobject thiz, jobjectArray contents, jobjectArray basePaths, jint output) {
    if (!getRenderer() || (output != 0 && output != 1)) return JNI_FALSE;
    ShaderPreset preset = readPresetStack(env, contents, basePaths);
    if (output == 1) {
        LoadProgress::setLabel("Preparando compilación");
        g_presetB = preset;
        g_hasPresetB.store(true);
        if (auto r = getRenderer(1)) r->setPreset(preset);
        else LoadProgress::finish();
        return JNI_TRUE;
    }
    auto renderers = getAllRenderers();
    if (renderers.empty()) { LoadProgress::finish(); return JNI_FALSE; }
    LoadProgress::setLabel("Preparando compilación");
    {
        std::lock_guard<std::mutex> rl(g_replayMutex);
        g_lastPreset = preset;
        g_hasLastPreset = true;
    }
    for (int i = 0; i < kMaxOutputs; i++) {
        if (i == 1 && g_hasPresetB.load()) continue;
        if (auto r = getRenderer(i)) r->setPreset(preset);
    }
    return JNI_TRUE;
}

// La zona B vuelve a seguir al preset de la A (P2): se le da el de la salida 0 con los valores que tenga ahora
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_clearOutputPreset(JNIEnv *env, jobject thiz, jint output) {
    if (output != 1) return;
    g_hasPresetB.store(false);
    g_editOutput.store(0);
    auto first = getRenderer(0);
    auto r = getRenderer(1);
    if (!first || !r) return;
    std::lock_guard<std::mutex> rl(g_replayMutex);
    if (!g_hasLastPreset) return;
    r->setPreset(g_lastPreset);
    for (const auto& kv : first->getParameterOverrides()) r->updateParameter(kv.first, kv.second);
}

// Qué salida editan los controles de parámetros (0 = A, 1 = B). Solo tiene efecto si la zona B tiene preset propio.
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setEditOutput(JNIEnv *env, jobject thiz, jint output) {
    g_editOutput.store(output == 1 ? 1 : 0);
}

// -1 = no hay carga en curso; 0..99 = porcentaje
extern "C" JNIEXPORT jint JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getLoadProgress(JNIEnv *env, jobject thiz) {
    return LoadProgress::percent();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getLoadLabel(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(LoadProgress::currentLabel().c_str());
}

// Motivo del último fallo de carga ("" si no hubo). Se entrega una sola vez: lo recoge quien lo muestre.
extern "C" JNIEXPORT jstring JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_consumeLoadStatus(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(LoadProgress::consumeStatus().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setVirtualNativeResolution(JNIEnv *env, jobject thiz, jint output, jfloat width, jfloat height) {
    if (auto r = getRenderer(output)) r->setVirtualNativeResolution(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setInputRegion(JNIEnv *env, jobject thiz, jint output, jfloat l, jfloat t, jfloat r, jfloat b) {
    if (auto rend = getRenderer(output)) rend->setInputRegion(l, t, r, b);
}

// Modo de salida (T30): 0 = Captura, 1 = Glass (capa con alfa sin captura). Se aplica en el siguiente renderFrame.
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setRenderMode(JNIEnv *env, jobject thiz, jint mode) {
    g_outputMode.store(mode == 1 ? 1 : 0);
    for (auto& r : getAllRenderers()) r->setOutputMode((int)mode);
}

// Contador de FPS (T66): activa la medida
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setPerfEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    g_perfEnabled.store(enabled == JNI_TRUE);
}

// Frames de salida acumulados y microsegundos de CPU gastados en renderizarlos: [frames, micros]. Quien llama calcula las diferencias.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getPerfCounters(JNIEnv *env, jobject thiz) {
    jlong v[2] = {(jlong)g_perfFrames.load(), (jlong)g_perfMicros.load()};
    jlongArray out = env->NewLongArray(2);
    if (out) env->SetLongArrayRegion(out, 0, 2, v);
    return out;
}

// Tope de FPS de salida (T66): 0 = sin tope
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setFpsCap(JNIEnv *env, jobject thiz, jint fps) {
    g_fpsCap.store(std::max(0, (int)fps));
}

// FPS a los que hay que redibujar la capa Glass (T34, parámetro GLASS_ANIM_FPS); 0 si es estática o no es Glass.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getGlassAnimFps(JNIEnv *env, jobject thiz) {
    auto r = getRenderer();
    return r ? r->getGlassAnimFps() : 0.0f;
}

// Valor actual de un parámetro por nombre (T35: GLASS_BLUR); `fallback` si el preset no lo declara.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getParameterValue(JNIEnv *env, jobject thiz, jstring name, jfloat fallback) {
    auto r = paramSource();
    if (!r || !name) return fallback;
    const char* n = env->GetStringUTFChars(name, nullptr);
    float v = r->getParameterValue(n, fallback);
    env->ReleaseStringUTFChars(name, n);
    return v;
}

// Pide un redibujado en todas las salidas (T34: la animación de Glass lo pide a su ritmo).
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_requestRedraw(JNIEnv *env, jobject thiz) {
    for (auto& r : getAllRenderers()) r->requestRedraw();
}

// Detección automática de bandas negras (T07): activa o no, y lados en los que puede recortar (bits de ShaderZone.BAR_*)
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_setAutoDetect(JNIEnv *env, jobject thiz, jboolean enabled, jint sides) {
    if (auto r = getRenderer()) r->setAutoDetect(enabled == JNI_TRUE, (int)sides);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_updateRotation(JNIEnv *env, jobject thiz, jint rotation) {
    g_rotation.store((uint32_t)rotation);
    for (auto& r : getAllRenderers()) r->setRotation((uint32_t)rotation);
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_updateSensors(JNIEnv *env, jobject thiz, jfloat gx, jfloat gy, jfloat gz, jfloat ax, jfloat ay, jfloat az) {
    for (auto& r : getAllRenderers()) r->setSensors(gx, gy, gz, ax, ay, az);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_getShaderParameters(JNIEnv *env, jobject thiz) {
    auto renderer = paramSource();
    if (!renderer || !g_paramClass || !g_paramConstructor) return nullptr;

    const auto params = renderer->getParameters();
    auto overrides = renderer->getParameterOverrides();

    jobjectArray array = env->NewObjectArray(params.size(), g_paramClass, nullptr);

    for (size_t i = 0; i < params.size(); i++) {
        const auto& p = params[i];
        float current = overrides.count(p.name) ? overrides[p.name] : p.defaultValue;

        jstring jname = env->NewStringUTF(p.name.c_str());
        jstring jdesc = env->NewStringUTF(p.description.c_str());

        jobject obj = env->NewObject(g_paramClass, g_paramConstructor, jname, jdesc, p.defaultValue, current, p.min, p.max, p.step);
        env->SetObjectArrayElement(array, i, obj);

        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jdesc);
        env->DeleteLocalRef(obj);
    }

    return array;
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_updateShaderParameter(JNIEnv *env, jobject thiz, jstring name, jfloat value) {
    auto renderers = paramTargets();
    if (renderers.empty()) return;

    const char* nativeName = env->GetStringUTFChars(name, nullptr);
    // Los valores de los parámetros son los mismos en todas las salidas (P2)
    for (auto& r : renderers) r->updateParameter(nativeName, value);
    env->ReleaseStringUTFChars(name, nativeName);
    // Solo guarda el valor: el redibujado va en un hilo aparte (y agrupado). Antes se renderizaba aquí, en el hilo que
    // llama (a menudo el principal), esperando al mutex del renderer y a la GPU.
    for (auto& r : renderers) r->requestParamRedraw(); // R23
}

// Vuelve a los valores del preset recién cargado (descarta los cambios hechos con sliders o perfiles) y redibuja.
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_resetShaderParameters(JNIEnv *env, jobject thiz) {
    for (auto& r : paramTargets()) {
        r->resetParametersToPreset();
        r->requestParamRedraw(); // R23
    }
}

// Varios parámetros de golpe (cargar un perfil, restaurar valores): un único redibujado al final.
extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_updateShaderParameters(JNIEnv *env, jobject thiz, jobjectArray names, jfloatArray values) {
    auto renderers = paramTargets();
    if (renderers.empty() || !names || !values) return;

    jsize n = std::min(env->GetArrayLength(names), env->GetArrayLength(values));
    if (n <= 0) return;
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    for (jsize i = 0; i < n; i++) {
        jstring jname = (jstring)env->GetObjectArrayElement(names, i);
        if (!jname) continue;
        const char* nativeName = env->GetStringUTFChars(jname, nullptr);
        for (auto& r : renderers) r->updateParameter(nativeName, vals[i]);
        env->ReleaseStringUTFChars(jname, nativeName);
        env->DeleteLocalRef(jname);
    }
    env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
    for (auto& r : renderers) r->requestParamRedraw(); // R23
}

extern "C" JNIEXPORT void JNICALL
Java_com_sanabria_shadervulkan_native_VulkanEngineBridge_release(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    std::shared_ptr<VulkanRenderer> old[kMaxOutputs];
    {
        std::lock_guard<std::mutex> pl(g_ptrMutex);
        // a partir de aquí las nuevas llamadas JNI ven renderer == null (las salidas extra primero)
        for (int i = kMaxOutputs - 1; i >= 0; i--) old[i] = std::move(g_ctx.renderers[i]);
    }
    // Esperar a que termine cualquier frame/carga en vuelo que aún tenga una copia del puntero:
    // destruir el VkDevice debajo de un renderFrame activo sería un use-after-free.
    for (int k = kMaxOutputs - 1; k >= 0; k--) {
        for (int i = 0; i < 3000 && old[k].use_count() > 1; i++) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        old[k].reset();
    }
    {
        std::lock_guard<std::mutex> rl(g_replayMutex);
        g_lastPreset = ShaderPreset();
        g_hasLastPreset = false;
        g_presetB = ShaderPreset();
        g_hasPresetB.store(false);
        g_editOutput.store(0);
    }
    LoadProgress::finish();
    // R07: los renderers (y sus hilos de compilación) ya no existen: nadie más usa la caché de pipelines
    if (g_pipelineCache != VK_NULL_HANDLE) {
        savePipelineCacheToDisk();
        std::lock_guard<std::mutex> pl(g_pipelineCacheFileMutex);
        vkDestroyPipelineCache(g_ctx.device, g_pipelineCache, nullptr);
        g_pipelineCache = VK_NULL_HANDLE;
    }
    if (g_ctx.device != VK_NULL_HANDLE) vkDestroyDevice(g_ctx.device, nullptr);
    if (g_ctx.instance != VK_NULL_HANDLE) vkDestroyInstance(g_ctx.instance, nullptr);
    g_ctx.device = VK_NULL_HANDLE; g_ctx.instance = VK_NULL_HANDLE;
}
