#pragma once
#include "DebugLog.hpp"
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include <string>
#include <map>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <functional>
#include <cmath>
#include "SlangParser.hpp"
#include "VulkanPipeline.hpp"
#include "FramebufferPool.hpp"

// Estructura de sistema compatible con RetroArch
struct SlangSystemUBO {
    float mvp[16];
    float sourceSize[4];
    float originalSize[4];
    float outputSize[4];
    uint32_t frameCount;
    uint32_t frameDir;
    float frameTimeDelta;
    uint32_t rotation;
    float gyroscope[3];
    float accelerometer[3];
};

struct VulkanTexture {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkDescriptorImageInfo descriptor = {};
    uint32_t width = 0;
    uint32_t height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
};

struct SwapchainImage {
    VkImage image;
    VkImageView view;
    VkFramebuffer framebuffer;
};

struct PassResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
};

struct CompiledPipelineSet {
    std::vector<std::unique_ptr<VulkanPipeline>> pipelines;
    std::vector<PooledFramebuffer> passResources; // Cambiado de PassResource
    std::vector<VkRenderPass> renderPasses;        // render pass de cada pase (el final directo usa el del swapchain)
    std::vector<PooledFramebuffer> feedbackResources; // PassFeedbackN (T43): copia "anterior" de la salida de cada pase que lo pide
    bool finalOffscreen = false; // T44: el último pase no es de tamaño viewport: escribe un framebuffer y se copia al swapchain
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> descriptorSets;
    std::vector<ShaderParameter> mergedParameters;
    std::map<std::string, VulkanTexture> lutTextures;
    std::vector<std::string> lutIds;
    uint64_t generation = 0; // generación del renderer con la que se compiló (descartar si ya no coincide)
    uint32_t inputW = 0, inputH = 0; // tamaño de entrada (Original) con el que se calcularon los tamaños de los pases
};

// Estado del renderer capturado al lanzar la compilación en segundo plano: el hilo de compilación NO lee
// miembros mutables del renderer (los render passes y el tamaño del viewport los destruye/cambia el hilo de render).
struct CompileEnv {
    uint32_t viewportW = 0, viewportH = 0;
    VkRenderPass rpFinal = VK_NULL_HANDLE;
    VkRenderPass rpUnorm = VK_NULL_HANDLE;
    VkRenderPass rpFloat = VK_NULL_HANDLE;
    VkRenderPass rpSrgb = VK_NULL_HANDLE;
    uint64_t generation = 0;
    bool glass = false; // modo Glass (PROYECTO.md §10): el último pase del preset escribe un intermedio y el pase interno el swapchain
    // R13: el swapchain está girado (pre-rotación): un último pase que use gl_FragCoord escribe un intermedio derecho que luego se copia
    // girado (el intermedio de T44 también se copia girado)
    bool preRotated = false;
};

// Mutex de la cola de Vulkan, compartido por todos los renderers (una instancia por salida, T15)
inline std::mutex& sharedQueueMutex() {
    static std::mutex m;
    return m;
}

class VulkanRenderer {
public:
    VulkanRenderer(VkInstance instance, VkPhysicalDevice physDev, VkDevice device, AAssetManager* assetManager);
    ~VulkanRenderer();

    void setSurface(ANativeWindow* window);
    // La salida tiene superficie (T15: solo las salidas con superficie compilan y entran en la barra de carga)
    bool hasSurface() const { return m_window != nullptr; }
    // Almacena un preset ya procesado por discoverParameters (rápido, sin I/O).
    void setPreset(const ShaderPreset& preset);
    // Lee y escanea los .slang de cada pase (lento: I/O por SAF). Estática a propósito:
    // no toca ningún miembro, así que puede ejecutarse sin locks y aunque el renderer se destruya.
    static void discoverParameters(ShaderPreset& preset, AAssetManager* assetManager);
    // Los setters de entrada (llamados desde el hilo principal / sensores) NO deben tomar m_renderMutex:
    // renderFrame lo mantiene durante la compilación de los pases (decenas de segundos en presets grandes)
    // y bloquear el hilo principal ahí provoca ANR. Usan m_inputMutex (nunca se mantiene mucho) o atómicos.
    void setVirtualNativeResolution(float w, float h) {
        SV_DIAG(ANDROID_LOG_INFO, "VulkanRenderer", "setVirtualNativeResolution: w=%.1f h=%.1f", w, h);
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_reqVirtualW = w;
        m_reqVirtualH = h;
        m_virtualResDirty = true; // se aplica al inicio del siguiente renderFrame
        // (sin frames de captura nuevos no se aplicaría nunca; el redibujado ocurre en un hilo aparte)
        requestRedraw();
    }
    // "Zona del shader" (T05): rectángulo de la captura que procesa el shader, en fracciones 0..1 de la pantalla
    // (izquierda, arriba, derecha, abajo). La ventana del overlay mide lo mismo que la zona, así que la salida la llena.
    // (0,0,1,1) = pantalla completa.
    // Detección automática de bandas negras (T07): activa o no, y en qué lados puede recortar (máscara de bits)
    void setAutoDetect(bool enabled, int sides) {
        if (m_autoDetect.load() != enabled || m_autoSides.load() != sides) {
            m_autoDetect.store(enabled);
            m_autoSides.store(sides);
            m_detResetPending.store(true); // se reinicia en el hilo de render
            if (enabled) requestRedraw();
        }
    }
    void setInputRegion(float l, float t, float r, float b) {
        SV_DIAG(ANDROID_LOG_INFO, "VulkanRenderer", "setInputRegion: %.4f %.4f %.4f %.4f", l, t, r, b);
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_reqRegion[0] = l; m_reqRegion[1] = t; m_reqRegion[2] = r; m_reqRegion[3] = b;
        m_regionDirty = true; // se aplica al inicio del siguiente renderFrame
        requestRedraw();
    }
    // Modo de salida (T30): 0 = Captura, 1 = Glass (capa con alfa, sin captura). La petición se aplica al inicio del siguiente
    // renderFrame (recrea el swapchain por el alfa y recompila), en el hilo de render.
    void setOutputMode(int mode) {
        m_reqOutputMode.store(mode == 1 ? 1 : 0);
        requestRedraw();
    }
    void setRotation(uint32_t rotation) {
        m_rotation.store(rotation);
    }
    void setSensors(float gx, float gy, float gz, float ax, float ay, float az) {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_gyro[0] = gx; m_gyro[1] = gy; m_gyro[2] = gz;
        m_accel[0] = ax; m_accel[1] = ay; m_accel[2] = az;
        // AccelerometerRest (input_sensor_update_rest_capture de RetroArch): media de 30 lecturas tras activar los sensores
        if (m_restCapturing) {
            for (int i = 0; i < 3; i++) m_restAccum[i] += m_accel[i];
            if (++m_restCount >= 30) {
                for (int i = 0; i < 3; i++) m_accelRest[i] = m_restAccum[i] / 30.0f;
                m_restCapturing = false;
            }
        }
    }
    void startAccelRestCapture() {
        std::lock_guard<std::mutex> lock(m_inputMutex);
        m_restAccum[0] = m_restAccum[1] = m_restAccum[2] = 0.0f;
        m_restCount = 0;
        m_restCapturing = true;
    }
    bool renderFrame(AHardwareBuffer* buf);
    // R06: Kotlin va a devolver al sistema los buffers de captura (fin de la captura, giro): el renderer espera a que la GPU deje de
    // leerlos, olvida la entrada actual y suelta los imports. Hasta la siguiente captura, los redibujados no tienen entrada.
    void releaseInput();

    // Nuevos métodos para parámetros (Síncronos y seguros)
    std::vector<ShaderParameter> getParameters() {
        std::lock_guard<std::mutex> lock(m_renderMutex);
        return m_pendingPreset.parameters;
    }
    std::map<std::string, float> getParameterOverrides() {
        std::lock_guard<std::mutex> lock(m_renderMutex);
        return m_pendingPreset.parameterOverrides;
    }
    // Valor actual de un parámetro (el tocado por el usuario o, si no, el del preset); `fallback` si el preset no lo declara. Con bloqueo.
    float getParameterValue(const std::string& name, float fallback) {
        if (name == "GLASS_BLUR") { // R22: lo pide el temporizador de Glass cada 300 ms: sin esperar al mutex (lo retiene una adopción)
            const float v = m_glassBlurCached.load(std::memory_order_relaxed);
            return std::isnan(v) ? fallback : v;
        }
        std::lock_guard<std::mutex> lock(m_renderMutex);
        return paramValueUnlocked(name, fallback);
    }
    // GLASS_ANIM_FPS (T34): los FPS a los que se redibuja la capa; 0 en Captura o si es estática.
    // true en Modo Glass (T66: ahí los frames de salida son redibujados, no frames de captura)
    bool isGlassMode() const { return m_outputMode == 1; }
    // R22: sin mutex (el temporizador de Glass la pide cada 300 ms); el valor se guarda al cambiar parámetros o modo
    float getGlassAnimFps() const { return m_glassAnimFpsCached.load(std::memory_order_relaxed); }
    void updateParameter(const std::string& name, float value);
    void resetParametersToPreset();

    // Se invoca desde el hilo de compilación cuando hay un set nuevo listo. Debe ser rápido y NO llamar a
    // renderFrame en ese mismo hilo (el redibujado puede lanzar otra compilación y haría join sobre sí mismo).
    void setOnCompileReady(std::function<void()> cb) { m_onCompileReady = std::move(cb); }
    // Pide un renderFrame(nullptr) en un hilo corto aparte: arranca/adopta compilaciones aunque no lleguen frames de captura.
    void requestRedraw() { if (m_onCompileReady) m_onCompileReady(); }
    // R23: redibujado por un cambio de parámetros. En Captura, con capturas llegando, se lo salta: la siguiente captura ya dibuja con
    // el valor nuevo (ver redrawLoop en native-lib.cpp). Sin callback propio, como requestRedraw.
    void setOnParamChanged(std::function<void()> cb) { m_onParamChanged = std::move(cb); }
    void requestParamRedraw() { if (m_onParamChanged) m_onParamChanged(); else requestRedraw(); }
    // R23: momento (steady_clock, µs) de la última captura que entró en renderFrame; 0 = ninguna
    int64_t lastCaptureMicros() const { return m_lastCaptureUs.load(std::memory_order_relaxed); }

private:
    std::function<void()> m_onCompileReady;
    std::function<void()> m_onParamChanged;
    std::atomic<int64_t> m_lastCaptureUs{0};
    bool initSwapchain();
    void cleanupSwapchainResources();
    void initSamplers();
    void createSystemResources();
    void createPassResources();
    void getVirtualNativeDimensions(uint32_t& outW, uint32_t& outH);
    VkRenderPass createOffscreenRenderPass(VkFormat format);
    std::map<std::string, TextureSemanticAlias> buildAliasMap(const ShaderPreset& preset);
    void getPassOutputSize(const ShaderPass& pass, uint32_t origW, uint32_t origH,
                            uint32_t srcW, uint32_t srcH, uint32_t viewportW, uint32_t viewportH,
                            uint32_t& outW, uint32_t& outH);
    // R02: lo que es igual para todos los pases de un frame se calcula una vez por frame, no una vez por binding y pase
    struct FrameConsts {
        float mvp[16];            // la del pase que escribe en el swapchain (girada con R13)
        float mvpIdentity[16];    // la de los pases que escriben un framebuffer propio (siempre derechos)
        bool glassStill = false;  // Glass estático (T30/T34): FrameCount 0 y FrameTimeDelta fijo
        float gyro[3] = {0, 0, 0};
        float accel[3] = {0, 0, 0};
        float accelRest[3] = {0, 0, 0};
    };
    void computeFrameConsts(FrameConsts& fc);
    void writePassSemantics(VulkanPipeline* pass, size_t passIndex, uint32_t frameIndex,
                             uint32_t outW, uint32_t outH,
                             uint32_t srcW, uint32_t srcH,
                             uint32_t origW, uint32_t origH, const FrameConsts& fc, bool toSwapchain);
    float resolveParamValue(const std::string& name);
    // --- Modo Glass (T30) ---
    std::atomic<int> m_reqOutputMode{0};  // petición desde otros hilos
    int m_outputMode = 0;                 // modo vigente (hilo de render, con m_renderMutex): 0 = Captura, 1 = Glass
    // Valor actual de un parámetro: el tocado por el usuario o el del preset; si el preset no lo declara, su override o 'fallback'.
    // R02: los declarados salen de m_paramValues (sin recorrer la lista por nombre).
    float paramValueUnlocked(const std::string& name, float fallback) const {
        auto it = m_paramIndex.find(name);
        if (it != m_paramIndex.end() && (size_t)it->second < m_paramValues.size()) return m_paramValues[it->second];
        auto ov = m_pendingPreset.parameterOverrides.find(name);
        if (ov != m_pendingPreset.parameterOverrides.end()) return ov->second;
        return fallback;
    }
    // R02: valor de cada parámetro por su índice en m_pendingPreset.parameters. Se rehace al cambiar la lista (setPreset, cambio de
    // modo) y se actualiza al tocar un valor (updateParameter, resetParametersToPreset). Todo con m_renderMutex.
    std::unordered_map<std::string, int> m_paramIndex;
    std::vector<float> m_paramValues;
    void rebuildParamCache();
    void refreshParamValues();
    // R22: GLASS_ANIM_FPS efectivo (0 en Captura) y GLASS_BLUR (NaN = ni declarado ni guardado), legibles sin m_renderMutex.
    // refreshGlassCache (con m_renderMutex) los pone al día tras cada cambio de valores o de modo.
    std::atomic<float> m_glassAnimFpsCached{0.0f};
    std::atomic<float> m_glassBlurCached{NAN};
    void refreshGlassCache();
    void resolveSetBindings(); // R02: deja resueltos los bindings y samplers del set recién adoptado
    // R02: escrituras de descriptores de un pase, en una sola llamada (la capacidad se conserva entre frames)
    std::vector<VkWriteDescriptorSet> m_dsWrites;
    std::vector<VkDescriptorImageInfo> m_dsInfos;
    std::vector<ShaderParameter> m_glassParams; // parámetros GLASS_* del pase interno (se leen una vez)
    bool m_glassParamsLoaded = false;
    PooledFramebuffer m_glassInput = {};  // entrada plana (Original) de Glass: gris GLASS_INPUT a la resolución virtual
    void ensureGlassParams();
    void applyGlassPass(ShaderPreset& preset);   // pone o quita el pase interno y los parámetros GLASS_* según el modo
    void applyOutputMode(int mode);              // cambia de modo (hilo de render, con m_renderMutex)
    void releaseGlassInput();
    CompiledPipelineSet compilePipelineSet(const ShaderPreset& preset, uint32_t iw, uint32_t ih, const CompileEnv& env);
    void startBackgroundCompile();                       // requiere m_renderMutex
    void adoptCompiledSet(CompiledPipelineSet& set);     // requiere m_renderMutex (hilo de render)
    void deviceWaitIdle();                               // vkDeviceWaitIdle serializado con las colas
    void reportFailedPasses(const ShaderPreset& preset, const CompiledPipelineSet& set); // avisa de los pases sin pipeline
    void disposeCompiledSet(CompiledPipelineSet& set);   // libera framebuffers (al pool), LUTs y pipelines de un set no usado
    void destroySystemResources();                       // render passes, vértices y UBO (solo al destruir el renderer)
    void invalidateCompile();                            // descarta compilaciones en curso y pide una nueva (requiere m_renderMutex)
    uint32_t m_compiledViewportW = 0, m_compiledViewportH = 0; // viewport con el que se lanzó la última compilación
    // Tamaño de entrada (resolución virtual o captura, según orientación) con el que se lanzó la última compilación y con
    // el que se compiló el set activo. Al rotar cambia aunque el viewport ya se haya recreado: si no se vigila, los pases
    // quedan dimensionados para la orientación anterior (imagen estirada hasta que se vuelva a rotar).
    uint32_t m_compiledInputW = 0, m_compiledInputH = 0;
    uint32_t m_activeInputW = 0, m_activeInputH = 0;
    bool loadLUT(const ShaderTexture& tex, const std::string& basePath, std::map<std::string, VulkanTexture>& outMap, std::vector<std::string>& outIds);
    void cleanupLUTs();
    void cleanupSwapchain();
    void cleanupPipelines();
    bool createInputTexture(AHardwareBuffer* buf);

    bool ensureImportPipeline(const VkAndroidHardwareBufferFormatPropertiesANDROID& fmtProps);
    void recordImportPass(VkCommandBuffer cmd, uint32_t width, uint32_t height);

    VkInstance m_instance;
    VkPhysicalDevice m_physDev;
    VkDevice m_device;
    VkQueue m_queue;
    uint32_t m_queueFamilyIndex = 0xFFFFFFFF;
    AAssetManager* m_assetManager;
    ANativeWindow* m_window = nullptr;

    float m_virtualNativeWidth = 0.0f;
    float m_virtualNativeHeight = 0.0f;

    VkSurfaceKHR m_surface = VK_NULL_HANDLE;
    VkSwapchainKHR m_swapchain = VK_NULL_HANDLE;
    std::vector<SwapchainImage> m_swapchainImages;
    // Tamaño LÓGICO de la salida (el de la ventana en su orientación): viewport de los pases, FinalViewportSize, OutputSize del último
    uint32_t m_width=0, m_height=0;
    VkSurfaceTransformFlagBitsKHR m_preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;

    // --- Pre-rotación (R13) ---
    // En horizontal el panel sigue en vertical: con el swapchain en IDENTITY el compositor tiene que girar nuestra capa en cada refresco y,
    // si el HWC no sabe girarla, la compone por GPU (un pase a pantalla completa que también paga el emulador). Con kPreRotate el
    // swapchain se crea con la rotación del dispositivo (currentTransform) y con el ancho y el alto del panel, y la imagen se dibuja ya
    // girada: el último pase con la MVP girada (como RetroArch con MVP) o, si usa gl_FragCoord o pasa por el intermedio de T44, a un
    // intermedio derecho que se copia girado con un pase interno (el blit no gira). false = IDENTITY como antes, para comparar.
    static constexpr bool kPreRotate = true;
    uint32_t m_swapW = 0, m_swapH = 0;   // tamaño físico de las imágenes del swapchain (el lógico con los ejes cambiados si gira 90/270)
    bool m_preRotated = false;           // m_preTransform no es IDENTITY
    bool m_compiledPreRotated = false;   // con qué orientación se lanzó la última compilación
    uint32_t m_rotationAtSwapchain = 0;  // m_rotation al crear el swapchain: si cambia, se mira si cambió la rotación de la superficie
    VkSurfaceTransformFlagBitsKHR chooseTransform(const VkSurfaceCapabilitiesKHR& cap) const;
    bool transformChanged();             // la superficie pide otra rotación que la del swapchain actual
    // Copia girada al swapchain: un cuadro a pantalla completa que muestrea 'view' (en SHADER_READ_ONLY) entre uv[0..1] y uv[2..3]
    VkDescriptorSetLayout m_rotDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_rotPipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_rotPipeline = VK_NULL_HANDLE;
    VkDescriptorPool m_rotDescPool = VK_NULL_HANDLE;
    VkDescriptorSet m_rotDescSets[2] = {};  // uno por frame en vuelo (FRAMES_IN_FLIGHT)
    bool m_rotFailed = false;
    // SPIR-V de la copia girada, compilado al crear el renderer: compilarlo en el hilo de render podía esperar al mutex de glslang mientras
    // el hilo de compilación (prioridad baja) compila un preset
    std::vector<uint32_t> m_rotVS, m_rotFS;
    bool ensureRotatePipeline();
    void destroyRotatePipeline();
    void rotation2x2(float out[4]) const; // columnas de la rotación de m_preTransform en coordenadas de recorte
    bool recordRotatedCopy(VkCommandBuffer cmd, uint32_t imageIndex, VkImageView view, const float uv[4], bool linear);

    VkRenderPass m_renderPass = VK_NULL_HANDLE;
    VkRenderPass m_offscreenRenderPass = VK_NULL_HANDLE;
    VkRenderPass m_offscreenRenderPassFloat = VK_NULL_HANDLE;
    VkRenderPass m_offscreenRenderPassSrgb = VK_NULL_HANDLE;

    VkBuffer m_vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_vertexMemory = VK_NULL_HANDLE;
    VkBuffer m_uboBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_uboMemory = VK_NULL_HANDLE;
    VkDeviceMemory m_paramMemory = VK_NULL_HANDLE;

    std::vector<std::unique_ptr<VulkanPipeline>> m_pipelines;
    std::vector<PooledFramebuffer> m_passResources; // Cambiado de PassResource
    std::vector<VkRenderPass> m_passRenderPasses;      // render pass con el que se compiló cada pase
    std::vector<PooledFramebuffer> m_feedbackResources; // PassFeedbackN (T43): salida del frame anterior; se intercambia al acabar cada frame
    bool m_feedbackNeedsInit = false;                   // limpiar a negro los de feedback antes de su primer uso
    bool m_finalOffscreen = false;                      // T44 (ver CompiledPipelineSet::finalOffscreen)
    std::map<VkFormat, VkRenderPass> m_extraRenderPasses; // render passes de formatos de #pragma format que no son los tres de siempre (T41)
    std::mutex m_extraRpMutex;
    VkRenderPass offscreenRenderPassFor(VkFormat format); // también desde el hilo de compilación
    VkFormat passFormat(const ShaderPass& pass, bool glassMid);

    std::unique_ptr<FramebufferPool> m_fbPool; // Hito C
    std::atomic<bool> m_abortCompilation{false}; // Fix Crash

    VkSamplerYcbcrConversion m_ycbcrConversion = VK_NULL_HANDLE;
    VkSampler m_ycbcrSampler = VK_NULL_HANDLE;
    VkDescriptorSetLayout m_importDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_importPipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_importPipeline = VK_NULL_HANDLE;
    VkDescriptorPool m_importDescPool = VK_NULL_HANDLE;
    VkDescriptorSet m_importDescSet = VK_NULL_HANDLE;
    VkFramebuffer m_importFb = VK_NULL_HANDLE;
    uint64_t m_importedExternalFormat = 0;
    bool m_needsImportPass = false;
    bool m_needsImportBarrier = false;

    VkSampler m_samplers[2][2] = {}; // [Linear][Repeat]

    VulkanTexture m_rawImportTex = {};
    struct CachedImportTex {
        AHardwareBuffer* keyPtr = nullptr;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t width = 0, height = 0;
        VkFormat format = VK_FORMAT_UNDEFINED; // R20: con RGBA8 el historial se copia sin blit
    };
    static const int MAX_IMPORT_CACHE = 8;
    std::vector<CachedImportTex> m_importCache;

    static const int FRAMES_IN_FLIGHT = 2; // (declarada antes de los arrays que la usan)
    // --- Frames en vuelo (T51) ---
    // true: el render no espera a la GPU al acabar cada frame (fences por frame en vez de vkQueueWaitIdle). false: espera como antes
    // (útil para comparar con Perfetto antes y después).
    static constexpr bool kAsyncFrames = true;
    // R04: presentar en MAILBOX si la superficie lo ofrece (siempre la captura más reciente, sin bloquear el hilo de captura).
    // false = FIFO como antes, para comparar.
    static constexpr bool kPreferMailbox = true;
    // R06: la captura se lee directamente del buffer del ImageReader, sin copiarla. Kotlin mantiene abierta la Image hasta que se
    // procesa la siguiente captura y, al volver de cada processFrame, todo lo enviado antes de ese frame ya terminó: así el sistema
    // nunca reescribe un buffer que la GPU aún lee (ni en un redibujado). Sin copia completa por frame (dos pasadas de memoria a
    // resolución de captura) ni espera de la CPU a la GPU antes de grabar. false = la copia de T51 (copyInput), para comparar.
    static constexpr bool kZeroCopyInput = true;
    // R06: espera a todo lo enviado salvo el frame del índice keepSlot (-1 = todo)
    void retirePreviousInput(int keepSlot);
    // R06/R10: destruye todos los imports de buffers de captura (con la GPU ya en reposo para ellos)
    void flushImportCache();
    VkFence m_frameFences[FRAMES_IN_FLIGHT] = {};          // se señala al acabar la GPU el frame de ese índice
    bool m_frameInFlight[FRAMES_IN_FLIGHT] = {};           // hay un envío pendiente con esa fence
    VkCommandBuffer m_frameCmds[FRAMES_IN_FLIGHT] = {};    // un command buffer por frame en vuelo (se resetea, no se crea cada frame)
    std::vector<PooledFramebuffer> m_pendingRelease[FRAMES_IN_FLIGHT]; // framebuffers del pool que la GPU aún puede estar usando
    VkCommandBuffer m_copyCmd = VK_NULL_HANDLE;            // copia de la captura a una imagen propia (se espera en el acto)
    VkFence m_copyFence = VK_NULL_HANDLE;
    // La captura se copia a una imagen propia en cuanto llega: así el ImageReader recupera su buffer enseguida (no hay que esperar a
    // que la GPU acabe todo el frame) y los redibujados internos ya no leen un buffer que el sistema puede estar reescribiendo.
    // Anillo de 3: con 2 frames en vuelo, el que se escribe nunca es uno de los que se están leyendo.
    VulkanTexture m_inputCopy[3];
    uint32_t m_inputSeq = 0;
    bool m_rawIsPrivate = false;                           // m_rawImportTex apunta a una copia propia (layout persistente GENERAL)
    VkImageLayout m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED; // layout actual de m_rawImportTex.image al grabar comandos
    void waitFrame(int slot);       // espera la fence del frame de ese índice y libera lo que tenía pendiente
    void drainFrames();             // espera todos los frames en vuelo (antes de destruir pipelines)
    bool copyInput();               // copia m_rawImportTex (captura importada) a la imagen propia siguiente del anillo
    void destroyInputCopies();
    static const int MAX_HISTORY_STEPS = 8;
    // OriginalHistory1..N: m_historyChain[0] es el frame anterior, [1] el anterior a ese, etc.
    std::vector<PooledFramebuffer> m_historyChain;
    uint32_t m_historyCount = 0;        // nº de frames de historial que usa el preset
    bool m_historyNeedsInit = true;     // hay que limpiar a negro las imágenes antes de usarlas
    bool m_pipelinesValid = false;      // todos los pases del preset compilaron
    // Calibración automática de la ganancia de Glass (T33): GLASS_GAIN = 0 mide el último pase del preset (sobre la entrada plana)
    // y elige la ganancia que deja lo más brillante (percentil 99,5) justo en transparente. Se mide una vez cada vez que cambia algo
    // que puede cambiar el patrón (set nuevo, parámetros del preset, reset, resolución o viewport).
    float m_glassGain = 1.0f;            // última ganancia calibrada (1 hasta la primera medida)
    bool m_glassCalibPending = true;     // hay que medir en el próximo frame de Glass (con GLASS_GAIN en Auto)
    VkBuffer m_calibBuf = VK_NULL_HANDLE;
    VkDeviceMemory m_calibMem = VK_NULL_HANDLE;
    uint8_t* m_calibMapped = nullptr;
    VkDeviceSize m_calibSize = 0;
    uint32_t m_calibW = 0, m_calibH = 0;
    bool ensureCalibBuffer(uint32_t w, uint32_t h);
    void releaseCalibBuffer();
    void analyzeGlassCalibration();
    std::map<std::string, VulkanTexture> m_lutTextures;
    std::vector<std::string> m_lutIds;

    ShaderPreset m_pendingPreset;
    std::map<std::string, float> m_presetOverrides; // parameterOverrides tal como llegaron con el preset (sin cambios del usuario)
    bool m_hasPendingPreset = false;
    uint32_t m_frameCount = 0;
    uint32_t m_frameTimeDelta = 0; // FrameTimeDelta: microsegundos desde el frame anterior (uint, como RetroArch)
    std::atomic<uint32_t> m_rotation{0};
    // Entrada recibida desde otros hilos (protegida por m_inputMutex, no por m_renderMutex)
    std::mutex m_inputMutex;
    float m_gyro[3] = {0,0,0};
    float m_accel[3] = {0,0,0};
    float m_accelRest[3] = {0,0,0};
    float m_restAccum[3] = {0,0,0};
    int m_restCount = 0;
    bool m_restCapturing = false;
    bool m_sensorsNeeded = false; // el preset activo usa Gyroscope/Accelerometer/AccelerometerRest (hilo de render)
    void updateSensorUsage(bool needed); // avisa a Kotlin solo si cambia
    float m_reqVirtualW = 0.0f, m_reqVirtualH = 0.0f;
    bool m_virtualResDirty = false;
    // Zona del shader (T05): la petición llega de otros hilos (m_inputMutex); m_region es la copia que usa el hilo de render
    float m_reqRegion[4] = {0.0f, 0.0f, 1.0f, 1.0f};
    bool m_regionDirty = false;
    float m_region[4] = {0.0f, 0.0f, 1.0f, 1.0f};
    bool regionIsFull() const;
    // Rectángulo de la textura capturada (en píxeles) que es la zona; con zona completa, la textura entera
    void regionSourceRect(uint32_t texW, uint32_t texH, VkOffset3D* start, VkOffset3D* end) const;
    std::chrono::steady_clock::time_point m_lastFrameTime;
    bool m_hasLastFrameTime = false;

    // --- Detección automática de bandas negras (T07) ---
    // Cada ~400 ms se reduce la captura a una miniatura, se lee en la CPU y se buscan los bordes casi negros. Solo se recorta en
    // los lados permitidos y con histéresis (ver analyzeDetectSample). El resultado vuelve a Kotlin (onAutoRegion).
    std::atomic<bool> m_autoDetect{false};
    std::atomic<int> m_autoSides{15};            // bits de ShaderZone.BAR_*: lados en los que se puede recortar
    std::atomic<bool> m_detResetPending{false};  // reiniciar el estado (cambió el modo o los lados)
    VkImage m_detImage = VK_NULL_HANDLE;
    VkDeviceMemory m_detImageMem = VK_NULL_HANDLE;
    VkBuffer m_detBuf = VK_NULL_HANDLE;
    VkDeviceMemory m_detBufMem = VK_NULL_HANDLE;
    uint8_t* m_detMapped = nullptr;
    bool m_detFailed = false;                    // no se pudieron crear los recursos: no reintentar
    std::chrono::steady_clock::time_point m_detLast;
    uint32_t m_detRawW = 0, m_detRawH = 0;       // tamaño de la captura analizada (un cambio = giro: se reinicia)
    float m_detApplied[4] = {0.0f, 0.0f, 1.0f, 1.0f}; // caja de contenido ya comunicada a Kotlin
    float m_detCand[4] = {0.0f, 0.0f, 1.0f, 1.0f};    // caja candidata a encoger
    int m_detStable = 0;                         // muestras consecutivas con la misma candidata
    int m_detGrow = 0;                           // muestras consecutivas con contenido fuera de la caja aplicada
    bool m_detNeedEmitFull = true;               // avisar a Kotlin de "sin recorte" (inicio, giro o cambio de modo)
    bool ensureDetectResources();
    bool recordDetectSample(VkCommandBuffer ca); // true si se grabó una muestra en este frame
    void analyzeDetectSample();                  // tras esperar a la cola: lee la miniatura y actualiza la caja
    // R11: índice del frame en vuelo que lleva una muestra sin analizar (-1 = ninguno). Se analiza al esperar a ese frame en waitFrame
    // (cuando de todos modos se reutiliza su índice), en vez de parar el render a esperar a la GPU en el mismo frame.
    int m_detPendingSlot = -1;
    bool m_destroying = false; // en el destructor: no se analizan muestras (avisarían a Kotlin a mitad del cierre)
    void resetDetectState();
    void destroyDetectResources();
    void emitDetectBox(const float box[4]);

    VkCommandPool m_cmdPool;
    VkCommandPool m_loadCmdPool = VK_NULL_HANDLE; // solo para el hilo de compilación (subida de LUTs)
    // Las colas de Vulkan exigen sincronización externa: el hilo de render (submit/present) y el de compilación
    // (subida de LUTs) la comparten.
    // T15: con varias salidas (modo dual) todos los renderers comparten el VkDevice y la ÚNICA VkQueue, así que el mutex es uno
    // solo para todos (vkQueueSubmit, vkQueuePresentKHR y vkDeviceWaitIdle exigen sincronización externa sobre la misma cola).
    std::mutex& m_queueMutex = sharedQueueMutex();
    std::atomic<uint64_t> m_compileGeneration{1};
    VkSemaphore m_imageAvailableSems[FRAMES_IN_FLIGHT];
    VkSemaphore m_renderFinishedSems[FRAMES_IN_FLIGHT];
    uint32_t m_currentFrame = 0;
    std::mutex m_renderMutex;

    // --- Hot Swap de Pipelines (Background compilation) ---
    std::thread m_compileThread;
    std::atomic<bool> m_compileInProgress{false};
    std::unique_ptr<CompiledPipelineSet> m_readyPipelineSet;
    std::mutex m_swapMutex;
};
