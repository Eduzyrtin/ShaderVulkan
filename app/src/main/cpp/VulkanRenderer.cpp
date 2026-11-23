#include "DebugLog.hpp"
#include "VulkanRenderer.hpp"
#include "LoadProgress.hpp"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <jni.h>
#include <chrono>
#include <algorithm>
#include <cstdio>
#include <cmath>
#include <sys/resource.h>
#include <unistd.h>

#define LOG_TAG "VulkanRenderer"
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) SV_DIAG(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) SV_DIAG(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static void blitRect(VkOffset3D* start, VkOffset3D* end, uint32_t w, uint32_t h) {
    start->x = 0; start->y = 0; start->z = 0;
    end->x = (int32_t)w; end->y = (int32_t)h; end->z = 1;
}

bool VulkanRenderer::regionIsFull() const {
    const float eps = 0.0005f;
    return m_region[0] <= eps && m_region[1] <= eps && m_region[2] >= 1.0f - eps && m_region[3] >= 1.0f - eps;
}

void VulkanRenderer::regionSourceRect(uint32_t texW, uint32_t texH, VkOffset3D* start, VkOffset3D* end) const {
    if (regionIsFull()) { blitRect(start, end, texW, texH); return; }
    auto clampi = [](int32_t v, int32_t lo, int32_t hi) { return std::max(lo, std::min(v, hi)); };
    int32_t x0 = clampi((int32_t)lroundf(m_region[0] * texW), 0, (int32_t)texW - 1);
    int32_t y0 = clampi((int32_t)lroundf(m_region[1] * texH), 0, (int32_t)texH - 1);
    int32_t x1 = clampi((int32_t)lroundf(m_region[2] * texW), x0 + 1, (int32_t)texW);
    int32_t y1 = clampi((int32_t)lroundf(m_region[3] * texH), y0 + 1, (int32_t)texH);
    start->x = x0; start->y = y0; start->z = 0;
    end->x = x1; end->y = y1; end->z = 1;
}

static uint32_t maxMipLevelsFor(uint32_t w, uint32_t h) {
    uint32_t m = std::max(w, h), n = 1;
    while (m > 1) { m >>= 1; n++; }
    return n;
}

// Genera la cadena de mipmaps de 'image' con blits lineales (equivale a vulkan_framebuffer_generate_mips
// de RetroArch). Entrada: nivel 0 en 'level0Old' con contenido escrito por (srcAccess, srcStage); los niveles
// 1.. no importan. Salida: TODOS los niveles en SHADER_READ_ONLY_OPTIMAL.
static void recordGenerateMips(VkCommandBuffer cmd, VkImage image, uint32_t w, uint32_t h, uint32_t levels,
                               VkImageLayout level0Old, VkAccessFlags srcAccess, VkPipelineStageFlags srcStage) {
    if (levels <= 1) return;
    auto bar = [&](uint32_t base, uint32_t count, VkImageLayout o, VkImageLayout n, VkAccessFlags sa, VkAccessFlags da) {
        VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, sa, da, o, n,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, image, {VK_IMAGE_ASPECT_COLOR_BIT, base, count, 0, 1}};
        return b;
    };

    VkImageMemoryBarrier first[2] = {
        bar(0, 1, level0Old, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, srcAccess, VK_ACCESS_TRANSFER_READ_BIT),
        bar(1, levels - 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT)};
    vkCmdPipelineBarrier(cmd, srcStage | VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 2, first);

    int32_t mw = (int32_t)w, mh = (int32_t)h;
    for (uint32_t l = 1; l < levels; l++) {
        int32_t nw = std::max(1, mw / 2), nh = std::max(1, mh / 2);
        VkImageBlit blit = {};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l - 1, 0, 1};
        blit.srcOffsets[1] = {mw, mh, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, l, 0, 1};
        blit.dstOffsets[1] = {nw, nh, 1};
        vkCmdBlitImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       1, &blit, VK_FILTER_LINEAR);
        if (l < levels - 1) {
            VkImageMemoryBarrier mid = bar(l, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                           VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 0, 0, nullptr, 0, nullptr, 1, &mid);
        }
        mw = nw; mh = nh;
    }

    VkImageMemoryBarrier last[2] = {
        bar(0, levels - 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT),
        bar(levels - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT)};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 2, last);
}

// --- Calibración automática de la ganancia de Glass (T33) ---

void VulkanRenderer::releaseCalibBuffer() {
    if (m_calibMapped) { vkUnmapMemory(m_device, m_calibMem); m_calibMapped = nullptr; }
    if (m_calibBuf) vkDestroyBuffer(m_device, m_calibBuf, nullptr);
    if (m_calibMem) vkFreeMemory(m_device, m_calibMem, nullptr);
    m_calibBuf = VK_NULL_HANDLE; m_calibMem = VK_NULL_HANDLE; m_calibSize = 0; m_calibW = m_calibH = 0;
}

// Buffer visible por la CPU para copiar el último pase del preset (RGBA8). Se crea una vez por tamaño.
bool VulkanRenderer::ensureCalibBuffer(uint32_t w, uint32_t h) {
    if (m_calibBuf != VK_NULL_HANDLE && m_calibW == w && m_calibH == h) return true;
    releaseCalibBuffer(); // la cola está en reposo tras cada frame (vkQueueWaitIdle): nada lo está usando
    const VkDeviceSize size = (VkDeviceSize)w * h * 4;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
    if (vkCreateBuffer(m_device, &bi, nullptr, &m_calibBuf) != VK_SUCCESS) { m_calibBuf = VK_NULL_HANDLE; return false; }
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(m_device, m_calibBuf, &mr);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
    uint32_t mt = 0xFFFFFFFF;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((mr.memoryTypeBits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { mt = i; break; }
    }
    if (mt == 0xFFFFFFFF) { releaseCalibBuffer(); return false; }
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
    if (vkAllocateMemory(m_device, &mai, nullptr, &m_calibMem) != VK_SUCCESS) { m_calibMem = VK_NULL_HANDLE; releaseCalibBuffer(); return false; }
    vkBindBufferMemory(m_device, m_calibBuf, m_calibMem, 0);
    void* p = nullptr;
    if (vkMapMemory(m_device, m_calibMem, 0, size, 0, &p) != VK_SUCCESS || !p) { releaseCalibBuffer(); return false; }
    m_calibMapped = (uint8_t*)p;
    m_calibSize = size; m_calibW = w; m_calibH = h;
    return true;
}

// Con la cola en reposo: histograma de luminancia (256 cajas) del último pase del preset, percentil 99,5 -> p, y ganancia
// GLASS_INPUT / p (lo más brillante del patrón queda justo en transparente). Si cambia más de un 2 %, se redibuja.
void VulkanRenderer::analyzeGlassCalibration() {
    m_glassCalibPending = false;
    if (!m_calibMapped || m_calibW == 0 || m_calibH == 0) return;
    const bool colorMask = paramValueUnlocked("GLASS_COLOR", 0.0f) > 0.001f; // igual que el shader: con color, el mínimo de los canales
    uint32_t hist[256] = {0};
    const size_t n = (size_t)m_calibW * m_calibH;
    for (size_t i = 0; i < n; i++) {
        const uint8_t* px = m_calibMapped + i * 4;
        float l = colorMask ? (float)std::min(px[0], std::min(px[1], px[2]))
                            : 0.2126f * px[0] + 0.7152f * px[1] + 0.0722f * px[2];
        hist[(int)std::min(255.0f, std::max(0.0f, l + 0.5f))]++;
    }
    // Percentil 99,5: se baja desde el brillo máximo hasta acumular el 0,5 % de los píxeles
    const uint64_t tail = (uint64_t)(n * 0.005);
    uint64_t acc = 0;
    int bin = 255;
    for (; bin > 0; bin--) { acc += hist[bin]; if (acc > tail) break; }
    const float p = std::max((float)bin / 255.0f, 1.0f / 255.0f);
    const float input = paramValueUnlocked("GLASS_INPUT", 0.7f);
    const float gain = std::min(4.0f, std::max(0.25f, input / p));
    const bool changed = std::fabs(gain - m_glassGain) > 0.02f * m_glassGain;
    LOGI("SVDIAG_GLASS gain=%.3f (antes %.3f) p99.5=%.3f entrada=%.2f%s", gain, m_glassGain, p, input, changed ? " -> redibujo" : "");
    if (changed) {
        m_glassGain = gain;
        requestRedraw();
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Detección automática de bandas negras (T07)
// ---------------------------------------------------------------------------------------------------------------------

// Definida en native-lib.cpp: llama a VulkanEngineBridge.onAutoRegion(FFFFZ) en Kotlin
extern void notifyAutoRegion(float l, float t, float r, float b, bool landscape);
// R07, definidas en native-lib.cpp: la caché de pipelines del driver (una para todo el dispositivo) y su volcado a disco
extern VkPipelineCache sharedPipelineCache();
extern void savePipelineCacheToDisk();

static constexpr uint32_t DET_SIZE = 160;                 // miniatura cuadrada: los bordes se miden en fracciones
static constexpr int DET_LUMA_MAX = 16;                   // un píxel con brillo (R+G+B)/3 <= esto cuenta como negro
static constexpr float DET_MIN_CONTENT = 0.20f;           // la caja detectada ocupa al menos el 20 % de cada eje
static constexpr float DET_TOLERANCE = 0.012f;            // diferencia (fracción) que ya cuenta como un cambio de borde
static constexpr int DET_SHRINK_SAMPLES = 6;              // ~2,4 s con la misma candidata antes de encoger la zona
static constexpr int DET_GROW_SAMPLES = 2;                // ~0,8 s con contenido fuera de la zona antes de ampliarla
static constexpr std::chrono::milliseconds DET_INTERVAL{400};

bool VulkanRenderer::ensureDetectResources() {
    if (m_detImage != VK_NULL_HANDLE && m_detBuf != VK_NULL_HANDLE && m_detMapped) return true;
    if (m_detFailed) return false;

    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
    auto findMem = [&](uint32_t bits, VkMemoryPropertyFlags want) {
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
        return -1;
    };
    auto fail = [&](const char* why) {
        LOGE("Detección de bandas: %s; se desactiva", why);
        destroyDetectResources();
        m_detFailed = true;
        return false;
    };

    // Miniatura en la GPU (destino del blit, origen de la copia al buffer)
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
        {DET_SIZE, DET_SIZE, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr,
        VK_IMAGE_LAYOUT_UNDEFINED};
    if (vkCreateImage(m_device, &ii, nullptr, &m_detImage) != VK_SUCCESS) { m_detImage = VK_NULL_HANDLE; return fail("vkCreateImage"); }
    VkMemoryRequirements mr; vkGetImageMemoryRequirements(m_device, m_detImage, &mr);
    int mt = findMem(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mt < 0) return fail("sin memoria local para la imagen");
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, (uint32_t)mt};
    if (vkAllocateMemory(m_device, &mai, nullptr, &m_detImageMem) != VK_SUCCESS) { m_detImageMem = VK_NULL_HANDLE; return fail("vkAllocateMemory (imagen)"); }
    vkBindImageMemory(m_device, m_detImage, m_detImageMem, 0);

    // Buffer legible desde la CPU
    const VkDeviceSize size = (VkDeviceSize)DET_SIZE * DET_SIZE * 4;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
    if (vkCreateBuffer(m_device, &bi, nullptr, &m_detBuf) != VK_SUCCESS) { m_detBuf = VK_NULL_HANDLE; return fail("vkCreateBuffer"); }
    VkMemoryRequirements bmr; vkGetBufferMemoryRequirements(m_device, m_detBuf, &bmr);
    int bmt = findMem(bmr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (bmt < 0) return fail("sin memoria visible para la CPU");
    VkMemoryAllocateInfo bmai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, bmr.size, (uint32_t)bmt};
    if (vkAllocateMemory(m_device, &bmai, nullptr, &m_detBufMem) != VK_SUCCESS) { m_detBufMem = VK_NULL_HANDLE; return fail("vkAllocateMemory (buffer)"); }
    vkBindBufferMemory(m_device, m_detBuf, m_detBufMem, 0);
    void* p = nullptr;
    if (vkMapMemory(m_device, m_detBufMem, 0, size, 0, &p) != VK_SUCCESS || !p) return fail("vkMapMemory");
    m_detMapped = (uint8_t*)p;
    return true;
}

void VulkanRenderer::destroyDetectResources() {
    if (m_detMapped) { vkUnmapMemory(m_device, m_detBufMem); m_detMapped = nullptr; }
    if (m_detBuf) { vkDestroyBuffer(m_device, m_detBuf, nullptr); m_detBuf = VK_NULL_HANDLE; }
    if (m_detBufMem) { vkFreeMemory(m_device, m_detBufMem, nullptr); m_detBufMem = VK_NULL_HANDLE; }
    if (m_detImage) { vkDestroyImage(m_device, m_detImage, nullptr); m_detImage = VK_NULL_HANDLE; }
    if (m_detImageMem) { vkFreeMemory(m_device, m_detImageMem, nullptr); m_detImageMem = VK_NULL_HANDLE; }
}

void VulkanRenderer::resetDetectState() {
    const float full[4] = {0.0f, 0.0f, 1.0f, 1.0f};
    for (int i = 0; i < 4; i++) { m_detApplied[i] = full[i]; m_detCand[i] = full[i]; }
    m_detStable = 0;
    m_detGrow = 0;
    m_detNeedEmitFull = true; // Kotlin puede conservar una caja de antes: se le dice que no hay recorte
    m_detPendingSlot = -1;    // R11: una muestra sin analizar es de antes del reinicio (otro giro, otros lados): no vale
}

void VulkanRenderer::emitDetectBox(const float box[4]) {
    for (int i = 0; i < 4; i++) m_detApplied[i] = box[i];
    m_detStable = 0;
    m_detGrow = 0;
    LOGI("Detección de bandas: caja de contenido %.3f %.3f %.3f %.3f (captura %ux%u)", box[0], box[1], box[2], box[3], m_detRawW, m_detRawH);
    notifyAutoRegion(box[0], box[1], box[2], box[3], m_detRawW >= m_detRawH);
}

// Graba en el command buffer del frame la reducción de la captura entera a la miniatura y su copia al buffer. Solo cada
// DET_INTERVAL: el coste (un blit pequeño) es despreciable. Devuelve true si hay una muestra que leer tras el frame.
bool VulkanRenderer::recordDetectSample(VkCommandBuffer ca) {
    if (m_detResetPending.exchange(false)) resetDetectState();
    const uint32_t rw = m_rawImportTex.width, rh = m_rawImportTex.height;
    if (rw == 0 || rh == 0) return false;
    if (rw != m_detRawW || rh != m_detRawH) { // la captura cambió de tamaño (giro): lo medido ya no vale
        m_detRawW = rw; m_detRawH = rh;
        resetDetectState();
    }
    if (m_detNeedEmitFull) {
        const float full[4] = {0.0f, 0.0f, 1.0f, 1.0f};
        emitDetectBox(full);
        m_detNeedEmitFull = false;
    }
    const auto now = std::chrono::steady_clock::now();
    if (now - m_detLast < DET_INTERVAL) return false;
    if (!ensureDetectResources()) return false;
    m_detLast = now;
    // R11: la muestra anterior, si aún no se analizó, se analiza antes de que esta reescriba el buffer (su frame terminó hace tiempo)
    if (m_detPendingSlot >= 0) waitFrame(m_detPendingSlot);

    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier pre[2] = {
        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
         m_rawLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, rng},
        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_detImage, rng}};
    // Va al FINAL del frame: espera a todo lo anterior (que ya usó la captura) para no pisar sus transiciones de layout
    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
    m_rawLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkImageBlit blit = {};
    blitRect(&blit.srcOffsets[0], &blit.srcOffsets[1], rw, rh); // SIEMPRE la captura entera, no la zona
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blitRect(&blit.dstOffsets[0], &blit.dstOffsets[1], DET_SIZE, DET_SIZE);
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    vkCmdBlitImage(ca, m_rawImportTex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_detImage,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

    VkImageMemoryBarrier toSrc = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_detImage, rng};
    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toSrc);

    VkBufferImageCopy region = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {DET_SIZE, DET_SIZE, 1}};
    vkCmdCopyImageToBuffer(ca, m_detImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_detBuf, 1, &region);

    VkBufferMemoryBarrier toHost = {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_HOST_READ_BIT, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_detBuf, 0, VK_WHOLE_SIZE};
    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &toHost, 0, nullptr);
    return true;
}

// Lee la miniatura (ya copiada y con la cola en reposo) y decide si cambia la caja de contenido.
//  - Mide el rectángulo que contiene todo lo que no es casi negro, SOLO en los lados permitidos.
//  - Ampliar es rápido (contenido fuera de la zona = el shader no se vería ahí); encoger es lento y exige una candidata
//    estable, y un fotograma casi negro (escena oscura, pantalla de carga) se ignora: no se encoge por una escena oscura.
void VulkanRenderer::analyzeDetectSample() {
    if (!m_detMapped) return;
    const int S = (int)DET_SIZE;
    const uint8_t* px = m_detMapped;

    std::vector<int> rowCnt(S, 0), colCnt(S, 0);
    int lit = 0;
    for (int y = 0; y < S; y++) {
        for (int x = 0; x < S; x++) {
            const uint8_t* p = px + ((size_t)y * S + x) * 4;
            if ((p[0] + p[1] + p[2]) / 3 > DET_LUMA_MAX) { rowCnt[y]++; colCnt[x]++; lit++; }
        }
    }
    if (lit < S * S / 100) return; // casi todo negro: no se aprende nada de este fotograma

    const int minLit = std::max(2, S / 50); // una fila/columna cuenta como contenido si tiene al menos esto de píxeles claros
    int top = -1, bottom = -1, left = -1, right = -1;
    for (int i = 0; i < S; i++) {
        if (rowCnt[i] >= minLit) { if (top < 0) top = i; bottom = i + 1; }
        if (colCnt[i] >= minLit) { if (left < 0) left = i; right = i + 1; }
    }
    if (top < 0 || left < 0) return;

    float box[4] = {(float)left / S, (float)top / S, (float)right / S, (float)bottom / S};
    // Sin tocar los lados no permitidos
    const int sides = m_autoSides.load();
    if (!(sides & 1)) box[0] = 0.0f;   // izquierda
    if (!(sides & 4)) box[1] = 0.0f;   // arriba
    if (!(sides & 2)) box[2] = 1.0f;   // derecha
    if (!(sides & 8)) box[3] = 1.0f;   // abajo
    // Un margen de una muestra hacia fuera para no morder el borde del contenido (la miniatura es una reducción)
    const float pad = 1.5f / S;
    if (box[0] > 0.0f) box[0] = std::max(0.0f, box[0] - pad);
    if (box[1] > 0.0f) box[1] = std::max(0.0f, box[1] - pad);
    if (box[2] < 1.0f) box[2] = std::min(1.0f, box[2] + pad);
    if (box[3] < 1.0f) box[3] = std::min(1.0f, box[3] + pad);
    if (box[2] - box[0] < DET_MIN_CONTENT || box[3] - box[1] < DET_MIN_CONTENT) return; // medición absurda

    const float* ap = m_detApplied;
    const float tol = DET_TOLERANCE;
    const bool outside = box[0] < ap[0] - tol || box[1] < ap[1] - tol || box[2] > ap[2] + tol || box[3] > ap[3] + tol;
    if (outside) {
        // Hay contenido fuera de la zona actual: ampliarla (la unión de las dos cajas) tras un par de muestras
        m_detStable = 0;
        if (++m_detGrow >= DET_GROW_SAMPLES) {
            float grown[4] = {std::min(ap[0], box[0]), std::min(ap[1], box[1]), std::max(ap[2], box[2]), std::max(ap[3], box[3])};
            emitDetectBox(grown);
        }
        return;
    }
    m_detGrow = 0;

    const bool smaller = box[0] > ap[0] + tol || box[1] > ap[1] + tol || box[2] < ap[2] - tol || box[3] < ap[3] - tol;
    if (!smaller) { m_detStable = 0; return; } // igual que la caja actual

    bool sameCand = m_detStable > 0;
    for (int i = 0; i < 4 && sameCand; i++) sameCand = std::fabs(box[i] - m_detCand[i]) <= tol;
    if (sameCand) {
        m_detStable++;
    } else {
        for (int i = 0; i < 4; i++) m_detCand[i] = box[i];
        m_detStable = 1;
    }
    if (m_detStable >= DET_SHRINK_SAMPLES) emitDetectBox(m_detCand);
}

static void compileRotateShaders(std::vector<uint32_t>& vs, std::vector<uint32_t>& fs); // R13, más abajo

VulkanRenderer::VulkanRenderer(VkInstance instance, VkPhysicalDevice physDev, VkDevice device, AAssetManager* assetManager)
    : m_instance(instance), m_physDev(physDev), m_device(device), m_assetManager(assetManager) {
    LOGI("VulkanRenderer: Iniciando...");
    if (kPreRotate) compileRotateShaders(m_rotVS, m_rotFS); // R13: dos shaders mínimos (y en la caché de SPIR-V desde la segunda vez)
    uint32_t qCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(m_physDev, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> qProps(qCount);
    vkGetPhysicalDeviceQueueFamilyProperties(m_physDev, &qCount, qProps.data());
    for(uint32_t i=0; i<qCount; i++) {
        if(qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { m_queueFamilyIndex = i; break; }
    }
    vkGetDeviceQueue(m_device, m_queueFamilyIndex, 0, &m_queue);
    m_fbPool = std::make_unique<FramebufferPool>(m_device, m_physDev);
    initSamplers();
    VkCommandPoolCreateInfo cpci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr, VK_COMMAND_BUFFER_LEVEL_PRIMARY, m_queueFamilyIndex};
    {
        VkCommandPoolCreateInfo frameCpci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, m_queueFamilyIndex};
        vkCreateCommandPool(m_device, &frameCpci, nullptr, &m_cmdPool); // T51: los command buffers del frame se resetean uno a uno
    }
    vkCreateCommandPool(m_device, &cpci, nullptr, &m_loadCmdPool);
    for(int i=0; i<FRAMES_IN_FLIGHT; i++) {
        VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        vkCreateSemaphore(m_device, &sci, nullptr, &m_imageAvailableSems[i]);
        vkCreateSemaphore(m_device, &sci, nullptr, &m_renderFinishedSems[i]);
    }
    // T51: una fence (empieza señalada) y un command buffer por frame en vuelo, y los de la copia de la captura
    {
        VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT};
        VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, m_cmdPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            vkCreateFence(m_device, &fci, nullptr, &m_frameFences[i]);
            vkAllocateCommandBuffers(m_device, &cai, &m_frameCmds[i]);
            m_frameInFlight[i] = false;
        }
        VkFenceCreateInfo fci2 = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        vkCreateFence(m_device, &fci2, nullptr, &m_copyFence);
        vkAllocateCommandBuffers(m_device, &cai, &m_copyCmd);
    }
}

VulkanRenderer::~VulkanRenderer() {
    m_destroying = true; // R11: las muestras pendientes de la detección ya no se analizan
    // Parar la compilación en segundo plano antes de tocar nada (usa el dispositivo y el pool)
    m_abortCompilation = true;
    m_compileGeneration++;
    if (m_compileThread.joinable()) m_compileThread.join();
    {
        std::unique_ptr<CompiledPipelineSet> leftover;
        { std::lock_guard<std::mutex> l(m_swapMutex); leftover = std::move(m_readyPipelineSet); }
        if (leftover) disposeCompiledSet(*leftover);
    }

    deviceWaitIdle();
    cleanupPipelines();
    releaseGlassInput();
    cleanupLUTs();
    cleanupSwapchain();
    destroyRotatePipeline(); // R13 (usa el render pass del swapchain)
    destroySystemResources();
    // Muestreadores [filtro][wrap] creados en initSamplers (nada los usa ya: pipelines y descriptores están destruidos)
    for (int i = 0; i < 2; i++)
        for (int j = 0; j < 2; j++)
            if (m_samplers[i][j] != VK_NULL_HANDLE) { vkDestroySampler(m_device, m_samplers[i][j], nullptr); m_samplers[i][j] = VK_NULL_HANDLE; }
    flushImportCache();

    destroyDetectResources();

    releaseCalibBuffer();

    for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
        for (auto& fb : m_pendingRelease[i]) if (fb.image != VK_NULL_HANDLE && m_fbPool) m_fbPool->release(fb);
        m_pendingRelease[i].clear();
        if (m_frameFences[i]) { vkDestroyFence(m_device, m_frameFences[i], nullptr); m_frameFences[i] = VK_NULL_HANDLE; }
    }
    if (m_copyFence) { vkDestroyFence(m_device, m_copyFence, nullptr); m_copyFence = VK_NULL_HANDLE; }
    destroyInputCopies();
    vkDestroyCommandPool(m_device, m_cmdPool, nullptr);

    if (m_loadCmdPool) vkDestroyCommandPool(m_device, m_loadCmdPool, nullptr);
    for(int i=0; i<FRAMES_IN_FLIGHT; i++) {
        vkDestroySemaphore(m_device, m_imageAvailableSems[i], nullptr);
        vkDestroySemaphore(m_device, m_renderFinishedSems[i], nullptr);
    }
}

void VulkanRenderer::initSamplers() {
    for(int i=0; i<2; i++) {
        for(int j=0; j<2; j++) {
            VkSamplerCreateInfo sci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            sci.magFilter = i ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            sci.minFilter = i ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            // Como RetroArch: maxLod sin límite (si la textura tiene mipmaps se usan) y el filtro entre
            // niveles sigue al filtro del pase (LINEAR salvo que el pase use NEAREST).
            sci.mipmapMode = i ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
            sci.minLod = 0.0f;
            sci.maxLod = VK_LOD_CLAMP_NONE;
            sci.addressModeU = j ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            sci.addressModeV = j ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            vkCreateSampler(m_device, &sci, nullptr, &m_samplers[i][j]);
        }
    }
}

void VulkanRenderer::createSystemResources() {
    if (m_renderPass != VK_NULL_HANDLE) return;
    VkAttachmentDescription ad = {0, VK_FORMAT_R8G8B8A8_UNORM, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR};
    VkAttachmentReference ar = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sd = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, nullptr, 1, &ar};
    VkRenderPassCreateInfo rpi = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, nullptr, 0, 1, &ad, 1, &sd, 0, nullptr};
    vkCreateRenderPass(m_device, &rpi, nullptr, &m_renderPass);

    m_offscreenRenderPass = createOffscreenRenderPass(VK_FORMAT_R8G8B8A8_UNORM);
    m_offscreenRenderPassFloat = createOffscreenRenderPass(VK_FORMAT_R16G16B16A16_SFLOAT);
    m_offscreenRenderPassSrgb = createOffscreenRenderPass(VK_FORMAT_R8G8B8A8_SRGB);

    if (m_vertexBuffer == VK_NULL_HANDLE) {
        float verts[] = {
            -1.0f, -1.0f,  0.0f, 0.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
            -1.0f,  1.0f,  0.0f, 1.0f,
             1.0f,  1.0f,  1.0f, 1.0f
        };
        VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, sizeof(verts), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
        vkCreateBuffer(m_device, &bi, nullptr, &m_vertexBuffer);
        VkMemoryRequirements mr; vkGetBufferMemoryRequirements(m_device, m_vertexBuffer, &mr);
        VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
        uint32_t mt = 0xFFFFFFFF;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
            if ((mr.memoryTypeBits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) {
                mt = i; break;
            }
        }
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
        vkAllocateMemory(m_device, &mai, nullptr, &m_vertexMemory);
        vkBindBufferMemory(m_device, m_vertexBuffer, m_vertexMemory, 0);
        void* data = nullptr;
        vkMapMemory(m_device, m_vertexMemory, 0, sizeof(verts), 0, &data);
        memcpy(data, verts, sizeof(verts));
        vkUnmapMemory(m_device, m_vertexMemory);
    }

    if (m_uboBuffer == VK_NULL_HANDLE) {
        VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, 256 * 1024, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
        vkCreateBuffer(m_device, &bi, nullptr, &m_uboBuffer);
        VkMemoryRequirements mr; vkGetBufferMemoryRequirements(m_device, m_uboBuffer, &mr);
        VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
        uint32_t mt = 0xFFFFFFFF;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
            if ((mr.memoryTypeBits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) {
                mt = i; break;
            }
        }
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
        vkAllocateMemory(m_device, &mai, nullptr, &m_uboMemory);
        vkBindBufferMemory(m_device, m_uboBuffer, m_uboMemory, 0);
    }
}

// --- #pragma format (T41): nombres de formato de RetroArch (glslang_util.c) -> VkFormat ---
static VkFormat vkFormatFromPragma(const std::string& n) {
    static const std::map<std::string, VkFormat> kFormats = {
        {"R8_UNORM", VK_FORMAT_R8_UNORM}, {"R8_UINT", VK_FORMAT_R8_UINT}, {"R8_SINT", VK_FORMAT_R8_SINT},
        {"R8G8_UNORM", VK_FORMAT_R8G8_UNORM}, {"R8G8_UINT", VK_FORMAT_R8G8_UINT}, {"R8G8_SINT", VK_FORMAT_R8G8_SINT},
        {"R8G8B8A8_UNORM", VK_FORMAT_R8G8B8A8_UNORM}, {"R8G8B8A8_UINT", VK_FORMAT_R8G8B8A8_UINT},
        {"R8G8B8A8_SINT", VK_FORMAT_R8G8B8A8_SINT}, {"R8G8B8A8_SRGB", VK_FORMAT_R8G8B8A8_SRGB},
        {"A2B10G10R10_UNORM_PACK32", VK_FORMAT_A2B10G10R10_UNORM_PACK32}, {"A2B10G10R10_UINT_PACK32", VK_FORMAT_A2B10G10R10_UINT_PACK32},
        {"R16_UINT", VK_FORMAT_R16_UINT}, {"R16_SINT", VK_FORMAT_R16_SINT}, {"R16_SFLOAT", VK_FORMAT_R16_SFLOAT},
        {"R16G16_UINT", VK_FORMAT_R16G16_UINT}, {"R16G16_SINT", VK_FORMAT_R16G16_SINT}, {"R16G16_SFLOAT", VK_FORMAT_R16G16_SFLOAT},
        {"R16G16B16A16_UINT", VK_FORMAT_R16G16B16A16_UINT}, {"R16G16B16A16_SINT", VK_FORMAT_R16G16B16A16_SINT},
        {"R16G16B16A16_SFLOAT", VK_FORMAT_R16G16B16A16_SFLOAT},
        {"R32_UINT", VK_FORMAT_R32_UINT}, {"R32_SINT", VK_FORMAT_R32_SINT}, {"R32_SFLOAT", VK_FORMAT_R32_SFLOAT},
        {"R32G32_UINT", VK_FORMAT_R32G32_UINT}, {"R32G32_SINT", VK_FORMAT_R32G32_SINT}, {"R32G32_SFLOAT", VK_FORMAT_R32G32_SFLOAT},
        {"R32G32B32A32_UINT", VK_FORMAT_R32G32B32A32_UINT}, {"R32G32B32A32_SINT", VK_FORMAT_R32G32B32A32_SINT},
        {"R32G32B32A32_SFLOAT", VK_FORMAT_R32G32B32A32_SFLOAT},
    };
    auto it = kFormats.find(n);
    return it == kFormats.end() ? VK_FORMAT_UNDEFINED : it->second;
}

// Lee #pragma name y #pragma format del código ya preprocesado de un pase (como slang_preprocess_parse_parameters_meta).
// El nombre hace de alias si el preset no puso uno (slang_process.c:1448); el formato lo usa passFormat().
// R13: y si usa gl_FragCoord (las máscaras de crt-guest-advanced, por ejemplo): ese pase no puede escribir girado en el swapchain.
static void scanShaderPragmas(const std::string& src, ShaderPass& pass) {
    pass.usesFragCoord = src.find("gl_FragCoord") != std::string::npos;
    auto trim = [](std::string s) {
        size_t a = s.find_first_not_of(" \t\r"); if (a == std::string::npos) return std::string();
        size_t b = s.find_last_not_of(" \t\r"); return s.substr(a, b - a + 1);
    };
    size_t pos = 0;
    while (pos < src.size()) {
        size_t e = src.find('\n', pos); if (e == std::string::npos) e = src.size();
        std::string line = trim(src.substr(pos, e - pos));
        pos = e + 1;
        if (line.compare(0, 13, "#pragma name ") == 0) {
            std::string v = trim(line.substr(13));
            if (pass.alias.empty() && !v.empty()) pass.alias = v;
        } else if (line.compare(0, 15, "#pragma format ") == 0) {
            if (pass.pragmaFormat.empty()) pass.pragmaFormat = trim(line.substr(15));
        }
    }
}

// Render pass de salida para un formato: los tres de siempre ya existen; los demás (T41) se crean al pedirlos y se guardan.
VkRenderPass VulkanRenderer::offscreenRenderPassFor(VkFormat format) {
    if (format == VK_FORMAT_R8G8B8A8_UNORM) return m_offscreenRenderPass;
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) return m_offscreenRenderPassFloat;
    if (format == VK_FORMAT_R8G8B8A8_SRGB) return m_offscreenRenderPassSrgb;
    std::lock_guard<std::mutex> lock(m_extraRpMutex); // lo pide el hilo de compilación
    auto it = m_extraRenderPasses.find(format);
    if (it != m_extraRenderPasses.end()) return it->second;
    VkRenderPass rp = createOffscreenRenderPass(format);
    m_extraRenderPasses[format] = rp;
    return rp;
}

// Formato de la salida de un pase. Como RetroArch (shader_vulkan.c:2370): el #pragma format del shader manda, salvo que el preset
// traiga escala (scale_type) y marque float/srgb_framebuffer, que entonces tienen prioridad; sin nada, RGBA8. Un formato que la GPU no
// pueda usar como destino y textura se descarta (se usa el de los indicadores).
VkFormat VulkanRenderer::passFormat(const ShaderPass& pass, bool glassMid) {
    if (glassMid) return VK_FORMAT_R8G8B8A8_UNORM; // como el pase final de Captura
    const VkFormat flagFmt = pass.floatFramebuffer ? VK_FORMAT_R16G16B16A16_SFLOAT
                           : pass.srgbFramebuffer  ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_UNDEFINED;
    if (!pass.pragmaFormat.empty() && !(flagFmt != VK_FORMAT_UNDEFINED && pass.scaleSpecified)) {
        VkFormat f = vkFormatFromPragma(pass.pragmaFormat);
        if (f == VK_FORMAT_UNDEFINED) {
            LOGW("#pragma format desconocido '%s'", pass.pragmaFormat.c_str());
        } else {
            VkFormatProperties fp; vkGetPhysicalDeviceFormatProperties(m_physDev, f, &fp);
            const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((fp.optimalTilingFeatures & need) == need) return f;
            LOGW("#pragma format %s no se puede usar como salida en esta GPU: se usa el de los indicadores", pass.pragmaFormat.c_str());
        }
    }
    return flagFmt != VK_FORMAT_UNDEFINED ? flagFmt : VK_FORMAT_R8G8B8A8_UNORM;
}

VkRenderPass VulkanRenderer::createOffscreenRenderPass(VkFormat format) {
    VkAttachmentDescription ad = {0, format, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkAttachmentReference ar = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sd = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, nullptr, 1, &ar};
    VkRenderPassCreateInfo rpi = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, nullptr, 0, 1, &ad, 1, &sd, 0, nullptr};
    VkRenderPass rp = VK_NULL_HANDLE;
    vkCreateRenderPass(m_device, &rpi, nullptr, &rp);
    return rp;
}

bool VulkanRenderer::initSwapchain() {
    if (!m_surface || !m_window) {
        LOGE("initSwapchain: m_surface o m_window es NULL (m_surface=%p, m_window=%p)", (void*)m_surface, (void*)m_window);
        return false;
    }

    LOGI("initSwapchain: Recreando swapchain de Vulkan...");
    cleanupSwapchainResources();

    VkSurfaceCapabilitiesKHR cap;
    VkResult capRes = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physDev, m_surface, &cap);
    if (capRes != VK_SUCCESS) {
        LOGE("initSwapchain: vkGetPhysicalDeviceSurfaceCapabilitiesKHR falló: %d", capRes);
        return false;
    }

    if (cap.currentExtent.width == 0xFFFFFFFF || cap.currentExtent.height == 0xFFFFFFFF) {
        m_width = ANativeWindow_getWidth(m_window);
        m_height = ANativeWindow_getHeight(m_window);
    } else {
        m_width = cap.currentExtent.width;
        m_height = cap.currentExtent.height;
    }

    LOGI("initSwapchain: Capabilities obtenidas - Extent: %ux%u, Transform: %u",
         m_width, m_height, (uint32_t)cap.currentTransform);

    if (m_width == 0 || m_height == 0) {
        LOGE("initSwapchain: Dimensiones de superficie inválidas (%ux%u)", m_width, m_height);
        return false;
    }

    createSystemResources();

    // R13: con kPreRotate, la rotación que pide la superficie; el tamaño de arriba (currentExtent) es el lógico, el de la ventana en su
    // orientación, y las imágenes del swapchain tienen los ejes cambiados si gira 90 o 270
    const VkSurfaceTransformFlagBitsKHR preTransform = chooseTransform(cap);
    m_preTransform = preTransform;
    m_preRotated = preTransform != VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    const bool swapAxes = preTransform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR || preTransform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
    m_swapW = swapAxes ? m_height : m_width;
    m_swapH = swapAxes ? m_width : m_height;
    m_rotationAtSwapchain = m_rotation.load();
    LOGI("initSwapchain: transform de la superficie %u, swapchain %s (%ux%u físico, %ux%u lógico; ventana %dx%d)",
         (uint32_t)cap.currentTransform, m_preRotated ? "pre-rotado (R13)" : "IDENTITY", m_swapW, m_swapH, m_width, m_height,
         ANativeWindow_getWidth(m_window), ANativeWindow_getHeight(m_window));

    // Alfa de composición (T30). En ventanas Android el driver suele anunciar solo INHERIT (el alfa lo decide la ventana).
    {
        static bool alphaLogged = false;
        if (!alphaLogged) {
            alphaLogged = true;
            LOGI("initSwapchain: supportedCompositeAlpha=0x%x (1=OPAQUE 2=PRE_MULTIPLIED 4=POST_MULTIPLIED 8=INHERIT)",
                 (unsigned)cap.supportedCompositeAlpha);
        }
    }
    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (m_outputMode == 1) {
        if (cap.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) compositeAlpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        else if (cap.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        else LOGE("initSwapchain: Glass sin PRE_MULTIPLIED ni INHERIT en supportedCompositeAlpha: la capa saldrá opaca");
    }

    // R04: MAILBOX si la superficie lo ofrece (Android lo anuncia siempre junto a FIFO). Con FIFO cada frame entra en cola y se ve en
    // orden: si llegan dos capturas en el mismo refresco, la segunda espera (un frame más de retraso) y, con la cola llena,
    // vkAcquireNextImageKHR bloquea el hilo de captura y se pierden capturas. Con MAILBOX el frame nuevo sustituye al que aún no se
    // había mostrado: siempre se ve la captura más reciente, sin bloqueos. Android nunca rompe la imagen (compone frames enteros).
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    if (kPreferMailbox) {
        uint32_t pmCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(m_physDev, m_surface, &pmCount, nullptr);
        std::vector<VkPresentModeKHR> modes(pmCount);
        if (pmCount) vkGetPhysicalDeviceSurfacePresentModesKHR(m_physDev, m_surface, &pmCount, modes.data());
        if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
    }

    // T51: al menos 2 (doble buffer) y siempre dentro de lo que anuncia la superficie (minImageCount ≤ n ≤ maxImageCount; 0 = sin tope).
    // R04: con MAILBOX, al menos 3 (una en pantalla, una esperando y una para dibujar), si no, el siguiente acquire esperaría igual.
    uint32_t imageCount = std::max(presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? 3u : 2u, cap.minImageCount);
    if (cap.maxImageCount > 0) imageCount = std::min(imageCount, cap.maxImageCount);
    LOGI("initSwapchain: imágenes de la swapchain: %u (mín %u, máx %u), presentación %s", imageCount, cap.minImageCount,
         cap.maxImageCount, presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO");

    VkSwapchainCreateInfoKHR sw = {
        VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, nullptr, 0,
        m_surface,
        imageCount,
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_COLOR_SPACE_SRGB_NONLINEAR_KHR,
        {m_swapW, m_swapH}, // R13: físico
        1,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_SHARING_MODE_EXCLUSIVE,
        0, nullptr,
        preTransform,
        compositeAlpha,
        presentMode,
        VK_TRUE,
        VK_NULL_HANDLE
    };

    VkResult resSwap = vkCreateSwapchainKHR(m_device, &sw, nullptr, &m_swapchain);
    if (resSwap != VK_SUCCESS && sw.presentMode == VK_PRESENT_MODE_MAILBOX_KHR) {
        LOGW("initSwapchain: MAILBOX falló (%d), se reintenta con FIFO", resSwap);
        sw.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        resSwap = vkCreateSwapchainKHR(m_device, &sw, nullptr, &m_swapchain);
    }
    if (resSwap != VK_SUCCESS && compositeAlpha == VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR &&
        (cap.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)) {
        LOGW("initSwapchain: OPAQUE falló (%d), se reintenta con INHERIT", resSwap);
        sw.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        resSwap = vkCreateSwapchainKHR(m_device, &sw, nullptr, &m_swapchain);
    }
    LOGI("initSwapchain: vkCreateSwapchainKHR resultado=%d, extent=%ux%u, m_swapchain=%p",
         resSwap, m_width, m_height, (void*)m_swapchain);

    if (resSwap != VK_SUCCESS) {
        LOGE("initSwapchain: Error creando swapchain: %d", resSwap);
        return false;
    }

    uint32_t count = 0;
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &count, nullptr);
    std::vector<VkImage> images(count);
    vkGetSwapchainImagesKHR(m_device, m_swapchain, &count, images.data());

    for (auto img : images) {
        SwapchainImage si = {img};
        VkImageViewCreateInfo vi = {
            VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, img,
            VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
            {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}
        };
        vkCreateImageView(m_device, &vi, nullptr, &si.view);
        VkFramebufferCreateInfo fi = {
            VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, nullptr, 0,
            m_renderPass, 1, &si.view, m_swapW, m_swapH, 1
        };
        vkCreateFramebuffer(m_device, &fi, nullptr, &si.framebuffer);
        m_swapchainImages.push_back(si);
    }

    // Los render passes sobreviven a la swapchain, así que solo hay que recompilar si cambió el tamaño del viewport
    // (los pases con scale_type viewport dependen de él) o si todavía no hay nada compilado para este preset.
    // R13: también si cambia entre derecho y girado con el mismo tamaño (0 y 180 grados): cambia qué último pase va por intermedio
    if (!m_pendingPreset.passes.empty() &&
        (m_width != m_compiledViewportW || m_height != m_compiledViewportH || m_preRotated != m_compiledPreRotated)) {
        invalidateCompile();
        LOGI("initSwapchain: viewport %ux%u (compilado para %ux%u) -> se recompila el preset", m_width, m_height, m_compiledViewportW, m_compiledViewportH);
    }

    return true;
}

// R13: la rotación con la que se crea el swapchain. Con kPreRotate, la que pide la superficie si es un giro puro (90, 180, 270);
// si no, IDENTITY (como siempre: la gira el compositor). Sin IDENTITY entre las admitidas (no pasa en Android), la que pide.
VkSurfaceTransformFlagBitsKHR VulkanRenderer::chooseTransform(const VkSurfaceCapabilitiesKHR& cap) const {
    const VkSurfaceTransformFlagBitsKHR ct = cap.currentTransform;
    const bool pureRotation = ct == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR || ct == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR ||
                              ct == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
    if (kPreRotate && pureRotation && (cap.supportedTransforms & ct)) return ct;
    if (cap.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) return VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    return ct;
}

// R13: al girar 180 grados (de horizontal a horizontal) la ventana no cambia de tamaño y no llega una superficie nueva: hay que darse
// cuenta de que la superficie pide otra rotación. Solo se pregunta cuando hay motivo (SUBOPTIMAL o un aviso de giro de Kotlin).
bool VulkanRenderer::transformChanged() {
    if (!kPreRotate || !m_surface) return false;
    VkSurfaceCapabilitiesKHR cap;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(m_physDev, m_surface, &cap) != VK_SUCCESS) return false;
    return chooseTransform(cap) != m_preTransform;
}

// --- Copia girada (R13) ---
// Un cuadro a pantalla completa (sin buffer de vértices) que muestrea una imagen derecha entre uv.xy y uv.zw y la escribe en el swapchain
// con el giro de m_preTransform (la misma rotación que la MVP del último pase).
static const char* const kRotateVS = R"(#version 450
layout(push_constant) uniform PC { vec4 rot; vec4 uv; } pc;
layout(location = 0) out vec2 vUV;
void main() {
    vec2 c = vec2(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1));
    vUV = mix(pc.uv.xy, pc.uv.zw, c);
    gl_Position = vec4(mat2(pc.rot.xy, pc.rot.zw) * (c * 2.0 - 1.0), 0.0, 1.0);
}
)";
static const char* const kRotateFS = R"(#version 450
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 FragColor;
void main() { FragColor = texture(tex, vUV); }
)";

static void compileRotateShaders(std::vector<uint32_t>& vs, std::vector<uint32_t>& fs) {
    vs = SlangCompiler::compileRaw(kRotateVS, STAGE_VERTEX);
    fs = SlangCompiler::compileRaw(kRotateFS, STAGE_FRAGMENT);
}

void VulkanRenderer::rotation2x2(float out[4]) const {
    // Columnas de la parte 2x2 de kR90/kR180/kR270 (computeFrameConsts)
    switch (m_preTransform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  out[0] = 0;  out[1] = 1;  out[2] = -1; out[3] = 0;  break;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: out[0] = -1; out[1] = 0;  out[2] = 0;  out[3] = -1; break;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: out[0] = 0;  out[1] = -1; out[2] = 1;  out[3] = 0;  break;
        default:                                      out[0] = 1;  out[1] = 0;  out[2] = 0;  out[3] = 1;  break;
    }
}

void VulkanRenderer::destroyRotatePipeline() {
    if (m_rotPipeline) vkDestroyPipeline(m_device, m_rotPipeline, nullptr);
    if (m_rotPipelineLayout) vkDestroyPipelineLayout(m_device, m_rotPipelineLayout, nullptr);
    if (m_rotDescPool) vkDestroyDescriptorPool(m_device, m_rotDescPool, nullptr); // libera también los sets
    if (m_rotDescLayout) vkDestroyDescriptorSetLayout(m_device, m_rotDescLayout, nullptr);
    m_rotPipeline = VK_NULL_HANDLE; m_rotPipelineLayout = VK_NULL_HANDLE; m_rotDescPool = VK_NULL_HANDLE; m_rotDescLayout = VK_NULL_HANDLE;
    for (auto& s : m_rotDescSets) s = VK_NULL_HANDLE;
}

bool VulkanRenderer::ensureRotatePipeline() {
    if (m_rotPipeline != VK_NULL_HANDLE) return true;
    if (m_rotFailed || m_renderPass == VK_NULL_HANDLE) return false;
    auto fail = [&](const char* why) {
        LOGE("R13: sin copia girada (%s): lo que no sea el último pase directo sale negro en horizontal", why);
        destroyRotatePipeline();
        m_rotFailed = true;
        return false;
    };
    if (m_rotVS.empty() || m_rotFS.empty()) return fail("SPIR-V");

    VkDescriptorSetLayoutBinding b = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dli = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0, 1, &b};
    if (vkCreateDescriptorSetLayout(m_device, &dli, nullptr, &m_rotDescLayout) != VK_SUCCESS) return fail("vkCreateDescriptorSetLayout");
    VkPushConstantRange pcr = {VK_SHADER_STAGE_VERTEX_BIT, 0, 8 * sizeof(float)};
    VkPipelineLayoutCreateInfo pli = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1, &m_rotDescLayout, 1, &pcr};
    if (vkCreatePipelineLayout(m_device, &pli, nullptr, &m_rotPipelineLayout) != VK_SUCCESS) return fail("vkCreatePipelineLayout");
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, FRAMES_IN_FLIGHT};
    VkDescriptorPoolCreateInfo dpi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0, FRAMES_IN_FLIGHT, 1, &ps};
    if (vkCreateDescriptorPool(m_device, &dpi, nullptr, &m_rotDescPool) != VK_SUCCESS) return fail("vkCreateDescriptorPool");
    VkDescriptorSetLayout layouts[FRAMES_IN_FLIGHT];
    for (auto& l : layouts) l = m_rotDescLayout;
    VkDescriptorSetAllocateInfo dai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr, m_rotDescPool, FRAMES_IN_FLIGHT, layouts};
    if (vkAllocateDescriptorSets(m_device, &dai, m_rotDescSets) != VK_SUCCESS) return fail("vkAllocateDescriptorSets");

    VkShaderModule mods[2] = {};
    const std::vector<uint32_t>* code[2] = {&m_rotVS, &m_rotFS};
    for (int i = 0; i < 2; i++) {
        VkShaderModuleCreateInfo smi = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0, code[i]->size() * sizeof(uint32_t), code[i]->data()};
        if (vkCreateShaderModule(m_device, &smi, nullptr, &mods[i]) != VK_SUCCESS) {
            if (mods[0]) vkDestroyShaderModule(m_device, mods[0], nullptr);
            return fail("vkCreateShaderModule");
        }
    }
    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, mods[0], "main", nullptr},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, mods[1], "main", nullptr}};
    VkPipelineVertexInputStateCreateInfo vin = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, nullptr, 0,
                                                 VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, VK_FALSE};
    VkPipelineViewportStateCreateInfo vps = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, nullptr, 0, 1, nullptr, 1, nullptr};
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo msi = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    msi.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba = {};
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &cba;
    const VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dsi = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, nullptr, 0, 2, dyn};
    VkGraphicsPipelineCreateInfo gp = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gp.stageCount = 2;
    gp.pStages = stages;
    gp.pVertexInputState = &vin;
    gp.pInputAssemblyState = &ia;
    gp.pViewportState = &vps;
    gp.pRasterizationState = &rs;
    gp.pMultisampleState = &msi;
    gp.pColorBlendState = &cb;
    gp.pDynamicState = &dsi;
    gp.layout = m_rotPipelineLayout;
    gp.renderPass = m_renderPass;
    const VkResult pr = vkCreateGraphicsPipelines(m_device, sharedPipelineCache(), 1, &gp, nullptr, &m_rotPipeline);
    vkDestroyShaderModule(m_device, mods[0], nullptr);
    vkDestroyShaderModule(m_device, mods[1], nullptr);
    if (pr != VK_SUCCESS) { m_rotPipeline = VK_NULL_HANDLE; return fail("vkCreateGraphicsPipelines"); }
    LOGI("R13: copia girada lista");
    return true;
}

// Escribe en la imagen 'imageIndex' del swapchain (render pass del swapchain: la deja en PRESENT_SRC) la imagen 'view' girada.
bool VulkanRenderer::recordRotatedCopy(VkCommandBuffer cmd, uint32_t imageIndex, VkImageView view, const float uv[4], bool linear) {
    if (!ensureRotatePipeline() || view == VK_NULL_HANDLE) return false;
    VkDescriptorSet ds = m_rotDescSets[m_currentFrame]; // su frame anterior ya terminó (waitFrame al empezar renderFrame)
    VkDescriptorImageInfo ii = {m_samplers[linear ? 1 : 0][0], view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, ds, 0, 0, 1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &ii, nullptr, nullptr};
    vkUpdateDescriptorSets(m_device, 1, &w, 0, nullptr);

    VkClearValue cv = {};
    cv.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkRenderPassBeginInfo rpbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, nullptr, m_renderPass, m_swapchainImages[imageIndex].framebuffer,
                                  {{0, 0}, {m_swapW, m_swapH}}, 1, &cv};
    vkCmdBeginRenderPass(cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_rotPipeline);
    VkViewport vp = {0.0f, 0.0f, (float)m_swapW, (float)m_swapH, 0.0f, 1.0f};
    vkCmdSetViewport(cmd, 0, 1, &vp);
    VkRect2D sc = {{0, 0}, {m_swapW, m_swapH}};
    vkCmdSetScissor(cmd, 0, 1, &sc);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_rotPipelineLayout, 0, 1, &ds, 0, nullptr);
    float pc[8];
    rotation2x2(pc);
    for (int i = 0; i < 4; i++) pc[4 + i] = uv[i];
    vkCmdPushConstants(cmd, m_rotPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(pc), pc);
    vkCmdDraw(cmd, 4, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    return true;
}

void VulkanRenderer::invalidateCompile() {
    m_compileGeneration++;       // cualquier compilación en curso queda obsoleta
    m_hasPendingPreset = true;
}

void VulkanRenderer::setSurface(ANativeWindow* window) {
    LOGI("setSurface iniciado: window=%p", (void*)window);
    {
        std::lock_guard<std::mutex> l(m_renderMutex);
        cleanupSwapchain();
        m_window = window;
        if (!window) {
            // R06: sin superficie esta salida no dibuja ni recibe capturas (processFrame se la salta): que no siga apuntando a un buffer
            // de captura que Kotlin puede devolver al sistema (lo leería el primer redibujado al volver la superficie)
            drainFrames();
            m_rawImportTex = {};
            m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            m_rawIsPrivate = false;
        }
    }
    if (!window) {
        LOGI("setSurface recibido NULL, limpiando swapchain y terminando");
        return;
    }
    {
        std::lock_guard<std::mutex> l(m_renderMutex);

        VkAndroidSurfaceCreateInfoKHR as = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, nullptr, 0, window};
        VkResult resSurface = vkCreateAndroidSurfaceKHR(m_instance, &as, nullptr, &m_surface);
        LOGI("vkCreateAndroidSurfaceKHR resultado=%d, surface=%p", resSurface, (void*)m_surface);

        if (resSurface == VK_SUCCESS) {
            initSwapchain();
        } else {
            LOGE("Error creando superficie Android Vulkan: %d", resSurface);
        }
    }
    // Con la swapchain lista puede haber una compilación pendiente (preset cargado antes que la superficie). Si la pantalla
    // capturada está quieta no llega ningún frame que la dispare, así que se fuerza un renderFrame.
    requestRedraw();
}

// --- Frames en vuelo (T51) ---

void VulkanRenderer::waitFrame(int slot) {
    if (m_frameInFlight[slot]) {
        VkResult r = vkWaitForFences(m_device, 1, &m_frameFences[slot], VK_TRUE, 2000000000ull);
        if (r != VK_SUCCESS) {
            LOGE("waitFrame(%d): la fence no se señaló (%d); se espera a toda la GPU", slot, (int)r);
            deviceWaitIdle();
        }
        m_frameInFlight[slot] = false;
    }
    // Lo que el frame de ese índice tenía en uso ya puede volver al pool
    for (auto& fb : m_pendingRelease[slot]) if (fb.image != VK_NULL_HANDLE && m_fbPool) m_fbPool->release(fb);
    m_pendingRelease[slot].clear();
    // R11: ese frame llevaba una muestra de la detección de bandas negras: ya está en el buffer
    if (m_detPendingSlot == slot) {
        m_detPendingSlot = -1;
        if (!m_destroying) analyzeDetectSample();
    }
}

void VulkanRenderer::drainFrames() {
    for (int i = 0; i < FRAMES_IN_FLIGHT; i++) waitFrame(i);
}

// --- Captura sin copia (R06) ---

void VulkanRenderer::retirePreviousInput(int keepSlot) {
    for (int i = 0; i < FRAMES_IN_FLIGHT; i++) if (i != keepSlot) waitFrame(i);
}

void VulkanRenderer::flushImportCache() {
    for (auto& entry : m_importCache) {
        if (entry.view) vkDestroyImageView(m_device, entry.view, nullptr);
        if (entry.image) vkDestroyImage(m_device, entry.image, nullptr);
        if (entry.memory) vkFreeMemory(m_device, entry.memory, nullptr); // suelta también la referencia al buffer de la captura
    }
    m_importCache.clear();
}

void VulkanRenderer::releaseInput() {
    std::lock_guard<std::mutex> l(m_renderMutex);
    drainFrames();
    m_rawImportTex = {};
    m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    m_rawIsPrivate = false;
    flushImportCache();
}

void VulkanRenderer::destroyInputCopies() {
    for (auto& t : m_inputCopy) {
        if (t.view) vkDestroyImageView(m_device, t.view, nullptr);
        if (t.image) vkDestroyImage(m_device, t.image, nullptr);
        if (t.memory) vkFreeMemory(m_device, t.memory, nullptr);
        t = {};
    }
}

// Copia la captura importada (AHardwareBuffer) a la siguiente imagen propia del anillo y espera a que termine la copia (solo la copia:
// el resto del frame sigue en la GPU). Después m_rawImportTex apunta a la copia y el buffer de la captura ya se puede devolver.
bool VulkanRenderer::copyInput() {
    const VulkanTexture imp = m_rawImportTex;
    if (imp.image == VK_NULL_HANDLE || imp.width == 0 || imp.height == 0 || !m_copyCmd) return false;
    VulkanTexture& dst = m_inputCopy[(++m_inputSeq) % 3];
    if (dst.image == VK_NULL_HANDLE || dst.width != imp.width || dst.height != imp.height) {
        drainFrames(); // por si la imagen vieja aún la lee algún frame
        if (dst.view) vkDestroyImageView(m_device, dst.view, nullptr);
        if (dst.image) vkDestroyImage(m_device, dst.image, nullptr);
        if (dst.memory) vkFreeMemory(m_device, dst.memory, nullptr);
        dst = {};
        VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0, VK_IMAGE_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM, {imp.width, imp.height, 1}, 1, 1,
            VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};
        if (vkCreateImage(m_device, &ii, nullptr, &dst.image) != VK_SUCCESS) { dst = {}; return false; }
        VkMemoryRequirements mr; vkGetImageMemoryRequirements(m_device, dst.image, &mr);
        VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
        uint32_t mt = 0xFFFFFFFF;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((mr.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { mt = i; break; }
        if (mt == 0xFFFFFFFF)
            for (uint32_t i = 0; i < mp.memoryTypeCount; i++) if (mr.memoryTypeBits & (1u << i)) { mt = i; break; }
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
        if (mt == 0xFFFFFFFF || vkAllocateMemory(m_device, &mai, nullptr, &dst.memory) != VK_SUCCESS) {
            vkDestroyImage(m_device, dst.image, nullptr); dst = {}; return false;
        }
        vkBindImageMemory(m_device, dst.image, dst.memory, 0);
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, dst.image, VK_IMAGE_VIEW_TYPE_2D, VK_FORMAT_R8G8B8A8_UNORM,
            {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        if (vkCreateImageView(m_device, &vi, nullptr, &dst.view) != VK_SUCCESS) {
            vkFreeMemory(m_device, dst.memory, nullptr); vkDestroyImage(m_device, dst.image, nullptr); dst = {}; return false;
        }
        dst.width = imp.width; dst.height = imp.height; dst.format = VK_FORMAT_R8G8B8A8_UNORM;
        LOGI("T51: copia propia de la captura %ux%u (anillo %u)", dst.width, dst.height, (m_inputSeq) % 3);
    }

    vkResetCommandBuffer(m_copyCmd, 0);
    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr};
    vkBeginCommandBuffer(m_copyCmd, &cbi);
    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier pre[2] = {
        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, imp.image, rng},
        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, dst.image, rng}};
    vkCmdPipelineBarrier(m_copyCmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
    VkImageBlit bl = {};
    bl.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    bl.srcOffsets[1] = {(int32_t)imp.width, (int32_t)imp.height, 1};
    bl.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    bl.dstOffsets[1] = {(int32_t)imp.width, (int32_t)imp.height, 1};
    vkCmdBlitImage(m_copyCmd, imp.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bl, VK_FILTER_NEAREST);
    // La copia queda en GENERAL: layout "de reposo" que no obliga a descartar el contenido en la siguiente transición
    VkImageMemoryBarrier post = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, dst.image, rng};
    vkCmdPipelineBarrier(m_copyCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &post);
    vkEndCommandBuffer(m_copyCmd);

    vkResetFences(m_device, 1, &m_copyFence);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &m_copyCmd, 0, nullptr};
    VkResult sr;
    {
        std::lock_guard<std::mutex> ql(m_queueMutex);
        sr = vkQueueSubmit(m_queue, 1, &si, m_copyFence);
    }
    if (sr != VK_SUCCESS) { LOGE("T51: vkQueueSubmit de la copia falló (%d)", (int)sr); return false; }
    if (vkWaitForFences(m_device, 1, &m_copyFence, VK_TRUE, 2000000000ull) != VK_SUCCESS) {
        LOGE("T51: la copia de la captura no terminó; se espera a toda la GPU");
        deviceWaitIdle();
    }

    m_rawImportTex.image = dst.image; m_rawImportTex.memory = dst.memory; m_rawImportTex.view = dst.view;
    m_rawImportTex.width = dst.width; m_rawImportTex.height = dst.height; m_rawImportTex.format = dst.format;
    m_rawIsPrivate = true;
    m_rawLayout = VK_IMAGE_LAYOUT_GENERAL;
    return true;
}

bool VulkanRenderer::createInputTexture(AHardwareBuffer* buf) {
    if (!buf) return false;

    // Buscar en caché por identidad de puntero
    for (auto& entry : m_importCache) {
        if (entry.keyPtr == buf) {
            m_rawImportTex.image  = entry.image;
            m_rawImportTex.memory = entry.memory;
            m_rawImportTex.view   = entry.view;
            m_rawImportTex.width  = entry.width;
            m_rawImportTex.height = entry.height;
            m_rawImportTex.format = entry.format;
            return true;
        }
    }

    // No está en caché: importar por primera vez
    AHardwareBuffer_Desc hd; AHardwareBuffer_describe(buf, &hd);
    VkAndroidHardwareBufferFormatPropertiesANDROID fp = {VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID ps = {VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, &fp};
    auto fn = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(m_device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!fn || fn(m_device, buf, &ps) != VK_SUCCESS) return false;

    CachedImportTex entry;
    entry.keyPtr = buf;

    VkFormat targetFormat = (hd.format == 1) ? VK_FORMAT_R8G8B8A8_UNORM : fp.format;
    VkExternalMemoryImageCreateInfo em = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, nullptr, VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID};
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &em, 0, VK_IMAGE_TYPE_2D, targetFormat, {hd.width, hd.height, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};

    if (vkCreateImage(m_device, &ii, nullptr, &entry.image) != VK_SUCCESS) return false;

    VkImportAndroidHardwareBufferInfoANDROID ih = {VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, nullptr, buf};
    VkMemoryDedicatedAllocateInfo da = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, &ih, entry.image};
    VkMemoryAllocateInfo ma = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &da, ps.allocationSize, 0};
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
    for(uint32_t i=0; i<mp.memoryTypeCount; i++) if(ps.memoryTypeBits&(1<<i)) { ma.memoryTypeIndex=i; break; }

    if (vkAllocateMemory(m_device, &ma, nullptr, &entry.memory) != VK_SUCCESS) {
        vkDestroyImage(m_device, entry.image, nullptr);
        return false;
    }
    vkBindImageMemory(m_device, entry.image, entry.memory, 0);

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, entry.image, VK_IMAGE_VIEW_TYPE_2D, targetFormat, {VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    if (vkCreateImageView(m_device, &vi, nullptr, &entry.view) != VK_SUCCESS) {
        vkFreeMemory(m_device, entry.memory, nullptr);
        vkDestroyImage(m_device, entry.image, nullptr);
        return false;
    }

    entry.width = hd.width;
    entry.height = hd.height;
    entry.format = targetFormat;

    // R10: un import retiene la memoria de su buffer. Los de otro tamaño son de un lector ya cerrado (giro, otra resolución de captura):
    // fuera ya, sin esperar a que los saque la FIFO (con 4 buffers por lector y 8 entradas se quedaban). Si la caché está llena, la
    // entrada más antigua. R06: un frame en vuelo puede estar leyendo el buffer de una entrada, así que antes se espera a la GPU
    // (pasa solo cuando aparecen buffers nuevos, no en cada frame).
    bool drained = false;
    auto evict = [&](size_t i) {
        if (!drained) { drainFrames(); drained = true; }
        CachedImportTex& e = m_importCache[i];
        vkDestroyImageView(m_device, e.view, nullptr);
        vkDestroyImage(m_device, e.image, nullptr);
        vkFreeMemory(m_device, e.memory, nullptr);
        m_importCache.erase(m_importCache.begin() + (std::ptrdiff_t)i);
    };
    for (size_t i = 0; i < m_importCache.size();) {
        if (m_importCache[i].width != entry.width || m_importCache[i].height != entry.height) evict(i);
        else i++;
    }
    if (m_importCache.size() >= MAX_IMPORT_CACHE) evict(0);
    m_importCache.push_back(entry);

    m_rawImportTex.image  = entry.image;
    m_rawImportTex.memory = entry.memory;
    m_rawImportTex.view   = entry.view;
    m_rawImportTex.width  = entry.width;
    m_rawImportTex.height = entry.height;
    m_rawImportTex.format = entry.format;
    return true;
}

bool VulkanRenderer::renderFrame(AHardwareBuffer* buf) {
    std::lock_guard<std::mutex> l(m_renderMutex);

    // Aplicar cambios de resolución virtual solicitados desde otros hilos
    {
        std::lock_guard<std::mutex> il(m_inputMutex);
        if (m_virtualResDirty) {
            m_virtualResDirty = false;
            if (m_virtualNativeWidth != m_reqVirtualW || m_virtualNativeHeight != m_reqVirtualH) {
                m_virtualNativeWidth = m_reqVirtualW;
                m_virtualNativeHeight = m_reqVirtualH;
                if (!m_pendingPreset.passes.empty()) invalidateCompile(); // recrear pipelines para ajustar tamaños de framebuffers
            }
        }
        // Zona del shader: si cambia, también cambia el tamaño de la entrada cuando la resolución es "Auto" (la de la zona);
        // createPassResources lo detecta (entrada distinta de la compilada) y recompila solo si hace falta.
        if (m_regionDirty) {
            m_regionDirty = false;
            for (int i = 0; i < 4; i++) m_region[i] = m_reqRegion[i];
        }
    }

    // Cambio de modo Captura <-> Glass (T30): antes de mirar el swapchain, que se recrea aquí por el alfa
    if (m_reqOutputMode.load() != m_outputMode) applyOutputMode(m_reqOutputMode.load());
    const bool glass = (m_outputMode == 1);
    // R23: con el mutex ya cogido: esta captura ve cualquier parámetro cambiado antes de este momento
    if (buf != nullptr && !glass)
        m_lastCaptureUs.store(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count(), std::memory_order_relaxed);

    // R06: con una captura nueva el renderer pasa a leer ESE buffer, que Kotlin mantiene abierto hasta la siguiente captura que se
    // procese, y deja de leer el anterior, que Kotlin devuelve al sistema en cuanto vuelve esta llamada. Por eso se importa lo primero,
    // aunque luego no se pueda dibujar, y antes de volver todo lo enviado antes de este frame tiene que haber terminado
    // (retirePreviousInput, en cada salida de la función).
    const bool newInput = kZeroCopyInput && buf != nullptr && !glass;
    if (newInput) {
        if (createInputTexture(buf)) {
            m_rawIsPrivate = false;
            m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED; // la imagen importada llega sin contenido "conocido" por Vulkan, como siempre
        } else {
            m_rawImportTex = {}; // no se pudo importar: ningún redibujado debe leer el buffer anterior
        }
    }

    static int skipCounter = 0;
    if (!m_swapchain || !m_window) {
        if (skipCounter++ % 60 == 0) {
            __android_log_print(ANDROID_LOG_ERROR, "SVDIAG_NATIVE",
                               "renderFrame SKIPPED: m_swapchain=%p, m_window=%p (surface o window faltante)",
                               (void*)m_swapchain, (void*)m_window);
        }
        if (newInput) retirePreviousInput(-1);
        return false;
    }

    // T51: el frame que usó este índice (hace FRAMES_IN_FLIGHT envíos) tiene que haber acabado: sus semáforos, command buffer,
    // UBO y descriptores son los que se van a reutilizar
    waitFrame(m_currentFrame);

    if (!glass && !kZeroCopyInput) { // T51 con copia (R06 desactivado). En Glass no hay captura: la entrada es una textura plana
        if (createInputTexture(buf)) { // true solo con una captura nueva (buf != null)
            m_rawIsPrivate = false;
            m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED; // la imagen importada llega sin contenido "conocido" por Vulkan, como siempre
            if (kAsyncFrames && !copyInput()) {
                m_rawIsPrivate = false;
                m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            }
        }
    }
    createPassResources();

    if (!glass && m_rawImportTex.image == VK_NULL_HANDLE) {
        // No hay fotograma capturado aún en memoria
        if (newInput) retirePreviousInput(-1);
        return false;
    }

    // R13: Kotlin avisó de un giro: si la superficie pide ya otra rotación (180 grados: misma ventana, no llega superficie nueva), el
    // swapchain se rehace antes de dibujar
    if (kPreRotate && m_rotation.load() != m_rotationAtSwapchain) {
        m_rotationAtSwapchain = m_rotation.load();
        if (transformChanged()) {
            LOGI("renderFrame: la superficie pide otra rotación: se recrea el swapchain (R13)");
            if (!initSwapchain() || !m_swapchain) {
                if (newInput) retirePreviousInput(-1);
                return false;
            }
        }
    }

    uint32_t idx;
    bool suboptimal = false; // R13: puede ser que la superficie pida otra rotación
    VkSemaphore sA = m_imageAvailableSems[m_currentFrame];
    VkSemaphore sR = m_renderFinishedSems[m_currentFrame];
    const auto tAcqStart = std::chrono::steady_clock::now();
    VkResult res = vkAcquireNextImageKHR(m_device, m_swapchain, 1000000000, sA, VK_NULL_HANDLE, &idx);
    {
        // Diagnóstico: esperas largas en acquire (SurfaceFlinger sin liberar buffers) y huecos entre frames consecutivos
        static std::chrono::steady_clock::time_point tLastEnd = tAcqStart;
        const auto tAcqEnd = std::chrono::steady_clock::now();
        const double acqMs = std::chrono::duration<double, std::milli>(tAcqEnd - tAcqStart).count();
        const double gapMs = std::chrono::duration<double, std::milli>(tAcqStart - tLastEnd).count();
        if (acqMs > 40.0) LOGW("SVDIAG_STALL vkAcquireNextImageKHR tardó %.0f ms (res=%d)", acqMs, (int)res);
        if (gapMs > 300.0 && !glass) LOGW("SVDIAG_GAP %.0f ms sin ningún renderFrame (entrada=%s)", gapMs, buf ? "captura nueva" : "redibujo interno");
        tLastEnd = tAcqEnd;
    }
    if (res == VK_ERROR_OUT_OF_DATE_KHR) {
        LOGI("renderFrame: vkAcquireNextImageKHR devolvió VK_ERROR_OUT_OF_DATE_KHR. Recreando swapchain...");
        initSwapchain();
        if (newInput) retirePreviousInput(-1);
        return false;
    } else if (res == VK_SUBOPTIMAL_KHR) {
        LOGD("renderFrame: vkAcquireNextImageKHR devolvió VK_SUBOPTIMAL_KHR");
        suboptimal = true;
    } else if (res != VK_SUCCESS) {
        LOGE("renderFrame: vkAcquireNextImageKHR error: %d", res);
        if (newInput) retirePreviousInput(-1);
        return false;
    }

    // FrameTimeDelta: tiempo en microsegundos entre este frame y el anterior (0 en el primero)
    {
        const auto now = std::chrono::steady_clock::now();
        if (m_hasLastFrameTime) {
            const long long us = std::chrono::duration_cast<std::chrono::microseconds>(now - m_lastFrameTime).count();
            m_frameTimeDelta = (uint32_t)std::min<long long>(us, 0xFFFFFFFFLL);
        } else {
            m_frameTimeDelta = 0;
            m_hasLastFrameTime = true;
        }
        m_lastFrameTime = now;
    }

    VkCommandBuffer ca = m_frameCmds[m_currentFrame];
    vkResetCommandBuffer(ca, 0);
    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr};
    vkBeginCommandBuffer(ca, &cbi);
    // Con frames en vuelo, las imágenes de los pases, el historial y los buffers de lectura son los mismos de un frame a otro: nada
    // de este frame empieza hasta que el anterior haya terminado en la GPU (la CPU sí puede ir por delante grabando).
    {
        VkMemoryBarrier gb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &gb, 0, nullptr, 0, nullptr);
    }
    // Con la copia de T51 (R06 desactivado), una captura que no se pudo copiar se lee directamente del buffer del sistema: hay que acabar
    // antes de devolverlo. Con R06 no hace falta: Kotlin retiene el buffer hasta la siguiente captura (ver retirePreviousInput).
    const bool syncEnd = !kAsyncFrames || (!kZeroCopyInput && buf != nullptr && !glass && !m_rawIsPrivate);

    PooledFramebuffer retroBuffer{};
    bool usedRetroBuffer = false;
    bool swapchainInPresentLayout = false; // el pase final ya dejó la imagen en PRESENT_SRC (finalLayout del render pass)

    bool detectRecorded = false;      // se grabó una muestra de la detección de bandas negras en este frame (T07)
    bool calibRecorded = false;       // se copió el último pase del preset para calibrar la ganancia de Glass (T33)

    if (glass || m_rawImportTex.image != VK_NULL_HANDLE) {
        // El set activo solo vale para el tamaño de entrada con el que se compiló: tras rotar, hasta que llegue el set
        // nuevo se muestra la captura sin procesar en vez de pasar la imagen girada por pases dimensionados al revés.
        uint32_t inW, inH;
        getVirtualNativeDimensions(inW, inH);
        const bool inputMatchesSet = (inW == m_activeInputW && inH == m_activeInputH);
        if (!inputMatchesSet && !m_pipelines.empty() && m_pipelinesValid) {
            static int warned = 0;
            if (warned++ < 10) LOGI("renderFrame: entrada %ux%u distinta de la del set activo %ux%u: captura sin procesar hasta recompilar",
                                    inW, inH, m_activeInputW, m_activeInputH);
        }
        if (!m_pipelines.empty() && m_pipelinesValid && inputMatchesSet) {
            VulkanTexture original;
            uint32_t targetW, targetH;
            getVirtualNativeDimensions(targetW, targetH);

            if (glass) {
                // Entrada plana: gris GLASS_INPUT a la resolución virtual (hace de Original). Se recrea si cambia el tamaño.
                if (m_glassInput.image != VK_NULL_HANDLE && (m_glassInput.width != targetW || m_glassInput.height != targetH))
                    releaseGlassInput();
                if (m_glassInput.image == VK_NULL_HANDLE)
                    m_glassInput = m_fbPool->acquire(targetW, targetH, VK_FORMAT_R8G8B8A8_UNORM, m_offscreenRenderPass);
                if (m_glassInput.image != VK_NULL_HANDLE) {
                    const float v = paramValueUnlocked("GLASS_INPUT", 0.7f);
                    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                    VkImageMemoryBarrier b1 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_glassInput.image, rng};
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b1);
                    VkClearColorValue grey = {{v, v, v, 1.0f}};
                    vkCmdClearColorImage(ca, m_glassInput.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &grey, 1, &rng);
                    VkImageMemoryBarrier b2 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_glassInput.image, rng};
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b2);
                    original.view = m_glassInput.view; original.width = targetW; original.height = targetH;
                } else {
                    LOGE("Glass: no se pudo crear la entrada plana %ux%u", targetW, targetH);
                }
            }
            // Con una zona (no pantalla completa) siempre hay blit: es el que recorta la captura a la zona
            if (!glass && (!regionIsFull() || targetW != m_rawImportTex.width || targetH != m_rawImportTex.height)) {
                retroBuffer = m_fbPool->acquire(targetW, targetH, VK_FORMAT_R8G8B8A8_UNORM, m_offscreenRenderPass);
                if (retroBuffer.image != VK_NULL_HANDLE) {
                    VkImageMemoryBarrier pre[2] = {
                        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_READ_BIT,
                         m_rawLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}},
                        {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                         VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, retroBuffer.image, {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}}
                    };
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
                    m_rawLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

                    VkImageBlit blitDown = {};
                    regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &blitDown.srcOffsets[0], &blitDown.srcOffsets[1]);
                    blitDown.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    blitRect(&blitDown.dstOffsets[0], &blitDown.dstOffsets[1], targetW, targetH);
                    blitDown.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    vkCmdBlitImage(ca, m_rawImportTex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   retroBuffer.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blitDown, VK_FILTER_LINEAR);

                    VkImageMemoryBarrier post = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, retroBuffer.image, {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &post);

                    usedRetroBuffer = true;
                    original.view = retroBuffer.view; original.width = targetW; original.height = targetH;
                }
            }
            if (!glass && !usedRetroBuffer) {
                original.view = m_rawImportTex.view; original.width = m_rawImportTex.width; original.height = m_rawImportTex.height;

                VkImageMemoryBarrier initBarrier = {
                    VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr,
                    0, VK_ACCESS_SHADER_READ_BIT,
                    m_rawLayout, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                    m_rawImportTex.image,
                    {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}
                };
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &initBarrier);
                m_rawLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
            VulkanTexture source = original;
            const VkImage originalImage = glass ? m_glassInput.image : usedRetroBuffer ? retroBuffer.image : m_rawImportTex.image;
            const VkFormat originalFormat = glass ? m_glassInput.format : usedRetroBuffer ? retroBuffer.format : m_rawImportTex.format;

            // --- Historial de frames (OriginalHistory1..N) ---
            // Se (re)crea si cambia el tamaño del Original (p. ej. rotación) y se limpia a negro la primera vez.
            if (m_historyCount > 0) {
                bool mismatch = m_historyChain.size() != m_historyCount ||
                    (!m_historyChain.empty() && (m_historyChain[0].width != original.width || m_historyChain[0].height != original.height));
                if (mismatch) {
                    for (auto& h : m_historyChain) m_fbPool->release(h);
                    m_historyChain.clear();
                    for (uint32_t k = 0; k < m_historyCount; k++)
                        m_historyChain.push_back(m_fbPool->acquire(original.width, original.height, VK_FORMAT_R8G8B8A8_UNORM, m_offscreenRenderPass));
                    m_historyNeedsInit = true;
                    LOGI("Historial: %u frames de %ux%u", m_historyCount, original.width, original.height);
                }
                if (m_historyNeedsInit) {
                    VkClearColorValue black = {{0.0f, 0.0f, 0.0f, 1.0f}};
                    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                    for (auto& h : m_historyChain) {
                        VkImageMemoryBarrier b1 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, h.image, rng};
                        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b1);
                        vkCmdClearColorImage(ca, h.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &rng);
                        VkImageMemoryBarrier b2 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, h.image, rng};
                        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b2);
                    }
                    m_historyNeedsInit = false;
                }
            }

            // PassFeedbackN (T43): antes del primer uso, los framebuffers de feedback se limpian a negro (como RetroArch)
            if (m_feedbackNeedsInit) {
                for (auto& fb : m_feedbackResources) {
                    if (fb.image == VK_NULL_HANDLE) continue;
                    VkClearColorValue zero = {{0.0f, 0.0f, 0.0f, 0.0f}};
                    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, fb.levels, 0, 1};
                    VkImageMemoryBarrier b1 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, fb.image, rng};
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b1);
                    vkCmdClearColorImage(ca, fb.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &rng);
                    VkImageMemoryBarrier b2 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, fb.image, rng};
                    vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b2);
                }
                m_feedbackNeedsInit = false;
            }

            // R02: MVP, modo estático de Glass y sensores, una vez por frame (antes, una vez por binding y pase)
            FrameConsts fc;
            computeFrameConsts(fc);

            for (size_t i = 0; i < m_pipelines.size(); i++) {
                bool isFinal = (i + 1 == m_pipelines.size());
                const bool toSwapchain = isFinal && !m_finalOffscreen; // T44: con intermedio, el último pase escribe un framebuffer propio
                const bool glassMid = glass && (i + 2 == m_pipelines.size()); // último pase del preset en Glass: escribe un intermedio
                VulkanPipeline* pass = m_pipelines[i].get();
                VkDescriptorSet ds = pass->getDescriptorSet(m_currentFrame);

                // --- Escribir descriptor set: samplers resueltos ---
                // El VkSampler (filtro y wrap por origen, T42) y la vista de cada LUT se resuelven al adoptar el set (resolveSetBindings);
                // aquí solo se eligen las vistas que cambian de un frame a otro y se escriben todas en una sola llamada (R02).
                const auto& samplers = pass->getResolvedSamplers();
                m_dsInfos.clear();
                m_dsWrites.clear();
                m_dsInfos.reserve(samplers.size()); // sin realojar: los punteros a m_dsInfos de m_dsWrites tienen que seguir valiendo
                m_dsWrites.reserve(samplers.size());
                for (const auto& rs : samplers) {
                    VkImageView view = VK_NULL_HANDLE;
                    if (rs.semantic == TextureSemantic::Original) view = original.view;
                    else if (rs.semantic == TextureSemantic::Source) view = source.view;
                    else if (rs.semantic == TextureSemantic::PassOutput) {
                        if (rs.index >= 0 && rs.index < (int)i) {
                            view = m_passResources[rs.index].view;
                        } else {
                             // Intento de lectura adelantada o a sí mismo sin feedback
                             view = original.view;
                        }
                    }
                    else if (rs.semantic == TextureSemantic::OriginalHistory) {
                        // OriginalHistory0 == Original; 1..N = frames anteriores (0 = el más reciente)
                        if (rs.index >= 1 && (size_t)rs.index <= m_historyChain.size()) view = m_historyChain[rs.index - 1].view;
                        else view = original.view;
                    }
                    else if (rs.semantic == TextureSemantic::PassFeedback) {
                        // Salida del frame anterior de ese pase (T43); antes de existir, la entrada
                        if (rs.index >= 0 && (size_t)rs.index < m_feedbackResources.size() && m_feedbackResources[rs.index].view != VK_NULL_HANDLE)
                            view = m_feedbackResources[rs.index].view;
                        else view = original.view;
                    }
                    else if (rs.semantic == TextureSemantic::User) {
                        view = rs.lutView; // rs.index es el índice en preset.textures (aliasMap), no en m_lutIds
                    }

                    if (view == VK_NULL_HANDLE) {
                        static int warnedMissing = 0;
                        if (warnedMissing++ < 20)
                            LOGE("Pase %zu: sampler '%s' (binding %u) sin textura; el descriptor queda sin escribir", i, rs.name.c_str(), rs.binding);
                        continue;
                    }
                    const VkSampler sampler = rs.sampler != VK_NULL_HANDLE ? rs.sampler : m_samplers[0][0];
                    m_dsInfos.push_back({sampler, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL});
                    m_dsWrites.push_back({VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, ds, rs.binding, 0, 1,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &m_dsInfos.back(), nullptr, nullptr});
                }
                if (!m_dsWrites.empty())
                    vkUpdateDescriptorSets(m_device, (uint32_t)m_dsWrites.size(), m_dsWrites.data(), 0, nullptr);

                uint32_t outW, outH;
                if (toSwapchain) { outW = m_width; outH = m_height; }
                else { outW = m_passResources[i].width; outH = m_passResources[i].height; }

                writePassSemantics(pass, i, m_currentFrame, outW, outH,
                                    source.width, source.height, original.width, original.height, fc, toSwapchain);
                // R13: el shader ve el tamaño lógico (OutputSize); el swapchain pre-rotado mide lo del panel y la MVP gira el cuadro
                const uint32_t fbW = toSwapchain ? m_swapW : outW, fbH = toSwapchain ? m_swapH : outH;

                VkRenderPassBeginInfo rpbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
                // El render pass es el con el que se compiló el pase (formato incluido: #pragma format, float/srgb, Glass)
                rpbi.renderPass = toSwapchain ? m_renderPass : m_passRenderPasses[i];
                rpbi.framebuffer = toSwapchain ? m_swapchainImages[idx].framebuffer : m_passResources[i].framebuffer;
                rpbi.renderArea = {{0, 0}, {fbW, fbH}};

                VkClearValue cv = {};
                cv.color = {{0.0f, 0.0f, 0.0f, glass ? 0.0f : 1.0f}};
                rpbi.clearValueCount = 1;
                rpbi.pClearValues = &cv;

                vkCmdBeginRenderPass(ca, &rpbi, VK_SUBPASS_CONTENTS_INLINE);

                pass->bind(ca);

                VkViewport vp = {0.0f, 0.0f, (float)fbW, (float)fbH, 0.0f, 1.0f};
                vkCmdSetViewport(ca, 0, 1, &vp);
                VkRect2D sc = {{0, 0}, {fbW, fbH}};
                vkCmdSetScissor(ca, 0, 1, &sc);

                if (pass->hasPushConstant()) {
                    auto range = pass->getPushConstantRange();
                    vkCmdPushConstants(ca, pass->getPipelineLayout(), range.stageFlags, 0, range.size,
                                        pass->getPushConstantScratch());
                }
                vkCmdBindDescriptorSets(ca, VK_PIPELINE_BIND_POINT_GRAPHICS, pass->getPipelineLayout(),
                                         0, 1, &ds, 0, nullptr);

                VkDeviceSize vertOffsets[] = {0};
                vkCmdBindVertexBuffers(ca, 0, 1, &m_vertexBuffer, vertOffsets);

                vkCmdDraw(ca, 4, 1, 0, 0);

                vkCmdEndRenderPass(ca);

                if (!toSwapchain) {
                    if (m_passResources[i].levels > 1) {
                        // El pase siguiente pide mipmap_input: generar la cadena (nivel 0 ya está en SHADER_READ_ONLY)
                        recordGenerateMips(ca, m_passResources[i].image, outW, outH, m_passResources[i].levels,
                                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                           VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                    } else {
                        // Barrera explícita con el pase siguiente (como RetroArch): la dependencia implícita del
                        // render pass no garantiza que la escritura sea visible para el muestreo del siguiente.
                        VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT};
                        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                             0, 1, &mb, 0, nullptr, 0, nullptr);
                    }
                    // T33: con GLASS_GAIN en Auto y una medida pendiente, copiar este pase (último del preset, RGBA8) a un buffer de la CPU
                    if (glassMid && m_glassCalibPending && paramValueUnlocked("GLASS_GAIN", 0.0f) <= 0.0f &&
                        m_passResources[i].image != VK_NULL_HANDLE && ensureCalibBuffer(m_passResources[i].width, m_passResources[i].height)) {
                        VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                        VkImageMemoryBarrier toSrc = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_passResources[i].image, rng};
                        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toSrc);
                        VkBufferImageCopy region = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {m_passResources[i].width, m_passResources[i].height, 1}};
                        vkCmdCopyImageToBuffer(ca, m_passResources[i].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_calibBuf, 1, &region);
                        VkImageMemoryBarrier back = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_passResources[i].image, rng};
                        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &back);
                        calibRecorded = true;
                    }
                    source.image  = m_passResources[i].image;
                    source.view   = m_passResources[i].view;
                    source.width  = m_passResources[i].width;
                    source.height = m_passResources[i].height;
                }
            }

            // T44: el último pase escribió un framebuffer de otro tamaño que el viewport: se escala al swapchain. R13: con el swapchain
            // girado (y para un último pase con gl_FragCoord, que escribe ahí aunque mida lo mismo) se copia girado con un cuadro.
            bool rotatedFinal = false;
            if (m_finalOffscreen && m_preRotated && !m_passResources.empty() && m_passResources.back().image != VK_NULL_HANDLE) {
                const float full[4] = {0.0f, 0.0f, 1.0f, 1.0f};
                rotatedFinal = recordRotatedCopy(ca, idx, m_passResources.back().view, full,
                                                 m_pendingPreset.passes[m_pipelines.size() - 1].filterLinear);
            }
            if (m_finalOffscreen && !rotatedFinal && !m_passResources.empty() && m_passResources.back().image != VK_NULL_HANDLE) {
                const PooledFramebuffer& last = m_passResources.back();
                VkImage dst = m_swapchainImages[idx].image;
                VkImageSubresourceRange r1 = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                VkImageMemoryBarrier pre[2] = {
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, last.image, r1},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, dst, r1}};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
                VkImageBlit bl = {};
                bl.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                bl.srcOffsets[1] = {(int32_t)last.width, (int32_t)last.height, 1};
                bl.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                bl.dstOffsets[1] = {(int32_t)m_swapW, (int32_t)m_swapH, 1}; // sin girar (si la copia girada falló, al menos se ve algo)
                const bool lin = m_pendingPreset.passes[m_pipelines.size() - 1].filterLinear;
                vkCmdBlitImage(ca, last.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bl,
                               lin ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
                VkImageMemoryBarrier back = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, last.image, r1};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &back);
            }

            // --- Guardar el Original en el anillo de historial (el más antiguo pasa a ser el más reciente) ---
            if (m_historyCount > 0 && !m_historyChain.empty() && originalImage != VK_NULL_HANDLE) {
                PooledFramebuffer target = m_historyChain.back();
                VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                VkImageMemoryBarrier pre[2] = {
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                     VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, originalImage, rng},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                     VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, target.image, rng}};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
                // R20: mismo tamaño y mismo formato (RGBA8, el del historial): copia directa, sin el filtrado del blit. Un Original con
                // otro formato (el buffer de la captura puede llegar en otro) sigue con blit, que convierte.
                if (originalFormat == VK_FORMAT_R8G8B8A8_UNORM && target.width == original.width && target.height == original.height) {
                    VkImageCopy hc = {};
                    hc.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    hc.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    hc.extent = {original.width, original.height, 1};
                    vkCmdCopyImage(ca, originalImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &hc);
                } else {
                    VkImageBlit hb = {};
                    blitRect(&hb.srcOffsets[0], &hb.srcOffsets[1], original.width, original.height);
                    hb.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    blitRect(&hb.dstOffsets[0], &hb.dstOffsets[1], target.width, target.height);
                    hb.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    vkCmdBlitImage(ca, originalImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &hb, VK_FILTER_NEAREST);
                }
                VkImageMemoryBarrier post[2] = {
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, originalImage, rng},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, target.image, rng}};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 2, post);
                std::rotate(m_historyChain.begin(), m_historyChain.end() - 1, m_historyChain.end());
            }
            // PassFeedbackN (T43): la salida de este frame pasa a ser el "anterior" del siguiente
            for (size_t j = 0; j < m_feedbackResources.size() && j < m_passResources.size(); j++)
                if (m_feedbackResources[j].image != VK_NULL_HANDLE && m_passResources[j].image != VK_NULL_HANDLE)
                    std::swap(m_passResources[j], m_feedbackResources[j]);
            m_frameCount++;
            // Con intermedio, el swapchain queda en TRANSFER_DST tras la copia; la copia girada (R13) es un render pass: PRESENT_SRC
            swapchainInPresentLayout = !m_finalOffscreen || rotatedFinal;
        } else if (glass) {
            // Glass sin set válido (compilando, pase roto o entrada distinta): capa transparente; nunca la captura ni el magenta
            VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_swapchainImages[idx].image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
            vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
            VkClearColorValue clear = {{0.0f, 0.0f, 0.0f, 0.0f}};
            VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdClearColorImage(ca, m_swapchainImages[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
        } else if (m_preRotated) {
            // R13: captura sin procesar (compilando o set no válido) con el swapchain girado: el blit no gira, se copia con el cuadro
            // girado, a la resolución virtual (como abajo) o, si no hay framebuffer, directamente de la zona de la captura
            uint32_t targetW, targetH;
            getVirtualNativeDimensions(targetW, targetH);
            PooledFramebuffer rawBuf = m_fbPool->acquire(targetW, targetH, VK_FORMAT_R8G8B8A8_UNORM, m_renderPass);
            const VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            bool copied = false;
            if (rawBuf.image != VK_NULL_HANDLE) {
                VkImageMemoryBarrier pre[2] = {
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_READ_BIT, m_rawLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, rng},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, rawBuf.image, rng}};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);
                m_rawLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                VkImageBlit blitDown = {};
                regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &blitDown.srcOffsets[0], &blitDown.srcOffsets[1]);
                blitDown.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                blitRect(&blitDown.dstOffsets[0], &blitDown.dstOffsets[1], targetW, targetH);
                blitDown.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                vkCmdBlitImage(ca, m_rawImportTex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rawBuf.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               1, &blitDown, VK_FILTER_LINEAR);
                VkImageMemoryBarrier post = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, rawBuf.image, rng};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &post);
                const float full[4] = {0.0f, 0.0f, 1.0f, 1.0f};
                copied = recordRotatedCopy(ca, idx, rawBuf.view, full, false); // nearest, como el blit de abajo
                m_fbPool->release(rawBuf); // como abajo: el frame siguiente empieza con una barrera completa
            } else {
                VkImageMemoryBarrier toRead = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_SHADER_READ_BIT, m_rawLayout,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, rng};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toRead);
                m_rawLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                VkOffset3D s, e;
                regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &s, &e);
                const float uv[4] = {(float)s.x / m_rawImportTex.width, (float)s.y / m_rawImportTex.height,
                                     (float)e.x / m_rawImportTex.width, (float)e.y / m_rawImportTex.height};
                copied = recordRotatedCopy(ca, idx, m_rawImportTex.view, uv, true);
            }
            if (copied) {
                swapchainInPresentLayout = true;
            } else { // sin copia girada: negro (el final del frame espera la imagen en TRANSFER_DST)
                VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_swapchainImages[idx].image, rng};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
                VkClearColorValue black = {{0.0f, 0.0f, 0.0f, 1.0f}};
                vkCmdClearColorImage(ca, m_swapchainImages[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &rng);
            }
        } else {
            uint32_t targetW, targetH;
            getVirtualNativeDimensions(targetW, targetH);

            PooledFramebuffer retroBuffer = m_fbPool->acquire(targetW, targetH, VK_FORMAT_R8G8B8A8_UNORM, m_renderPass);

            if (retroBuffer.image != VK_NULL_HANDLE) {
                VkImageMemoryBarrier startBars[3] = {
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_READ_BIT, m_rawLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, retroBuffer.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}},
                    {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_swapchainImages[idx].image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}}
                };
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 3, startBars);
                m_rawLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

                VkImageBlit blitDown = {};
                regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &blitDown.srcOffsets[0], &blitDown.srcOffsets[1]);
                blitDown.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                blitRect(&blitDown.dstOffsets[0], &blitDown.dstOffsets[1], targetW, targetH);
                blitDown.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                vkCmdBlitImage(ca, m_rawImportTex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, retroBuffer.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blitDown, VK_FILTER_LINEAR);

                VkImageMemoryBarrier midBar = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, retroBuffer.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
                vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &midBar);

                VkImageBlit blitUp = {};
                blitRect(&blitUp.srcOffsets[0], &blitUp.srcOffsets[1], targetW, targetH);
                blitUp.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                blitRect(&blitUp.dstOffsets[0], &blitUp.dstOffsets[1], m_swapW, m_swapH);
                blitUp.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                vkCmdBlitImage(ca, retroBuffer.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_swapchainImages[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blitUp, VK_FILTER_NEAREST);

                m_fbPool->release(retroBuffer);
            } else {
                VkImageBlit blit = {};
                regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &blit.srcOffsets[0], &blit.srcOffsets[1]);
                blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                blitRect(&blit.dstOffsets[0], &blit.dstOffsets[1], m_swapW, m_swapH);
                blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                vkCmdBlitImage(ca, m_rawImportTex.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m_swapchainImages[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
            }
        }
    } else {
        VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_swapchainImages[idx].image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
        VkClearColorValue magenta = {{1.0f, 0.0f, 1.0f, 1.0f}};
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdClearColorImage(ca, m_swapchainImages[idx].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &magenta, 1, &range);
    }

    // Muestra para la detección de bandas negras (T07): al final, cuando ya nadie más toca la captura en este frame
    if (!glass && m_autoDetect.load() && m_rawImportTex.image != VK_NULL_HANDLE) detectRecorded = recordDetectSample(ca);

    // La copia propia de la captura vuelve a GENERAL: el siguiente frame (un redibujado, por ejemplo) la leerá sin captura nueva
    if (m_rawIsPrivate && !glass && m_rawImportTex.image != VK_NULL_HANDLE && m_rawLayout != VK_IMAGE_LAYOUT_GENERAL &&
        m_rawLayout != VK_IMAGE_LAYOUT_UNDEFINED) {
        VkImageMemoryBarrier rb = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, m_rawLayout, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED,
            VK_QUEUE_FAMILY_IGNORED, m_rawImportTex.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &rb);
        m_rawLayout = VK_IMAGE_LAYOUT_GENERAL;
    }
    // Solo en los caminos de blit/clear la imagen está en TRANSFER_DST. Tras el render pass final ya está en PRESENT_SRC:
    // declarar TRANSFER_DST como layout antiguo sería inválido (en Adreno/UBWC puede corromper la imagen de forma intermitente).
    if (!swapchainInPresentLayout) {
        VkImageMemoryBarrier eb = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, m_swapchainImages[idx].image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(ca, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &eb);
    }
    vkEndCommandBuffer(ca);

    // El semáforo de la imagen del swapchain también bloquea los blits (etapa de transferencia), no solo el render pass
    VkPipelineStageFlags ws = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 1, &sA, &ws, 1, &ca, 1, &sR};
    VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1, &sR, 1, &m_swapchain, &idx, nullptr};
    VkResult presRes;
    {
        std::lock_guard<std::mutex> ql(m_queueMutex); // la cola se comparte con el hilo de compilación (subida de LUTs)
        vkResetFences(m_device, 1, &m_frameFences[m_currentFrame]);
        const VkResult subRes = vkQueueSubmit(m_queue, 1, &si, m_frameFences[m_currentFrame]);
        m_frameInFlight[m_currentFrame] = (subRes == VK_SUCCESS);
        if (subRes != VK_SUCCESS) LOGE("renderFrame: vkQueueSubmit error: %d", (int)subRes);
        presRes = vkQueuePresentKHR(m_queue, &pi);
    }
    if (presRes == VK_ERROR_OUT_OF_DATE_KHR) {
        LOGI("renderFrame: vkQueuePresentKHR devolvió VK_ERROR_OUT_OF_DATE_KHR (%d). Recreando swapchain...", presRes);
        initSwapchain();
    } else if (presRes == VK_SUBOPTIMAL_KHR) {
        // En GPUs Adreno y ventanas flotantes de Android, VK_SUBOPTIMAL_KHR es común durante presentación normal.
        // No recreamos la swapchain para evitar bucles de recompilación a 60fps.
        LOGD("renderFrame: vkQueuePresentKHR devolvió VK_SUBOPTIMAL_KHR (superficie apta, presentación normal)");
        suboptimal = true;
    } else if (presRes != VK_SUCCESS) {
        LOGE("renderFrame: vkQueuePresentKHR error: %d", presRes);
    }
    // R13: Android da SUBOPTIMAL cuando la rotación del swapchain no es la que pide la superficie. Solo se recrea si de verdad cambió
    // (y se mira como mucho 4 veces por segundo: con IDENTITY en horizontal llegaba en cada frame).
    if (kPreRotate && suboptimal && presRes != VK_ERROR_OUT_OF_DATE_KHR) {
        static thread_local std::chrono::steady_clock::time_point lastCheck;
        const auto now = std::chrono::steady_clock::now();
        if (now - lastCheck > std::chrono::milliseconds(250)) {
            lastCheck = now;
            if (transformChanged()) {
                LOGI("renderFrame: SUBOPTIMAL por otra rotación de la superficie: se recrea el swapchain (R13)");
                initSwapchain();
            }
        }
    }

    // Solo se espera a la GPU cuando la CPU va a leer algo de este frame en el acto (calibración de Glass) o la captura no se pudo copiar;
    // si no, el frame sigue en vuelo y se espera a su fence al reutilizar el índice. R11: la muestra de la detección de bandas se analiza
    // entonces (waitFrame), no aquí: antes el render se paraba a esperar a la GPU cada 400 ms con la detección encendida.
    if (detectRecorded) m_detPendingSlot = (int)m_currentFrame;
    const bool needSync = syncEnd || calibRecorded;
    if (needSync) waitFrame(m_currentFrame);
    // R06: lo enviado antes de este frame pudo leer el buffer anterior, que Kotlin devuelve al volver: tiene que haber terminado. El
    // frame actual sigue en vuelo (lee el buffer nuevo, que Kotlin retiene). En la práctica suele estar ya terminado: no se espera.
    if (newInput) retirePreviousInput(m_currentFrame);

    if (calibRecorded) analyzeGlassCalibration(); // la cola está en reposo: el pase ya está en el buffer

    // El framebuffer temporal sigue en uso por la GPU si el frame sigue en vuelo: vuelve al pool cuando acabe
    if (usedRetroBuffer) {
        if (needSync) m_fbPool->release(retroBuffer);
        else m_pendingRelease[m_currentFrame].push_back(retroBuffer);
    }
    m_currentFrame = (m_currentFrame + 1) % FRAMES_IN_FLIGHT;

    return true;
}

void VulkanRenderer::cleanupSwapchainResources() {
    if (m_device == VK_NULL_HANDLE) return;
    deviceWaitIdle();
    for (auto& si : m_swapchainImages) {
        if (si.framebuffer) vkDestroyFramebuffer(m_device, si.framebuffer, nullptr);
        if (si.view) vkDestroyImageView(m_device, si.view, nullptr);
    }
    m_swapchainImages.clear();
    if (m_swapchain) { vkDestroySwapchainKHR(m_device, m_swapchain, nullptr); m_swapchain = VK_NULL_HANDLE; }
    // Los render passes, el buffer de vértices y el UBO sobreviven a la swapchain: así los pipelines compilados
    // siguen siendo válidos al pausar/reanudar el overlay y no hay que recompilar el preset (decenas de segundos).
    // Se destruyen en destroySystemResources() al destruir el renderer.
}

void VulkanRenderer::destroySystemResources() {
    if (m_device == VK_NULL_HANDLE) return;
    if (m_renderPass) { vkDestroyRenderPass(m_device, m_renderPass, nullptr); m_renderPass = VK_NULL_HANDLE; }
    if (m_offscreenRenderPass) { vkDestroyRenderPass(m_device, m_offscreenRenderPass, nullptr); m_offscreenRenderPass = VK_NULL_HANDLE; }
    if (m_offscreenRenderPassFloat) { vkDestroyRenderPass(m_device, m_offscreenRenderPassFloat, nullptr); m_offscreenRenderPassFloat = VK_NULL_HANDLE; }
    if (m_offscreenRenderPassSrgb) { vkDestroyRenderPass(m_device, m_offscreenRenderPassSrgb, nullptr); m_offscreenRenderPassSrgb = VK_NULL_HANDLE; }
    { std::lock_guard<std::mutex> lock(m_extraRpMutex); for (auto& kv : m_extraRenderPasses) vkDestroyRenderPass(m_device, kv.second, nullptr); m_extraRenderPasses.clear(); }
    if (m_vertexBuffer) { vkDestroyBuffer(m_device, m_vertexBuffer, nullptr); m_vertexBuffer = VK_NULL_HANDLE; }
    if (m_vertexMemory) { vkFreeMemory(m_device, m_vertexMemory, nullptr); m_vertexMemory = VK_NULL_HANDLE; }
    if (m_uboBuffer) { vkDestroyBuffer(m_device, m_uboBuffer, nullptr); m_uboBuffer = VK_NULL_HANDLE; }
    if (m_uboMemory) { vkFreeMemory(m_device, m_uboMemory, nullptr); m_uboMemory = VK_NULL_HANDLE; }
}

void VulkanRenderer::deviceWaitIdle() {
    std::lock_guard<std::mutex> ql(m_queueMutex);
    vkDeviceWaitIdle(m_device);
}

void VulkanRenderer::cleanupSwapchain() {
    cleanupSwapchainResources();
    if (m_surface) { vkDestroySurfaceKHR(m_instance, m_surface, nullptr); m_surface = VK_NULL_HANDLE; }
}

void VulkanRenderer::setPreset(const ShaderPreset& preset) {
    std::lock_guard<std::mutex> lock(m_renderMutex);
    m_pendingPreset = preset;
    applyGlassPass(m_pendingPreset); // en Glass añade el pase interno y sus parámetros GLASS_*
    rebuildParamCache(); // R02
    m_presetOverrides = preset.parameterOverrides; // valores del propio preset (y su cadena #reference): base de los perfiles
    // Los pipelines activos pertenecen al preset anterior (índices de pase, LUTs...): fuera. Mientras compila el
    // nuevo se muestra la captura sin procesar.
    cleanupPipelines();
    cleanupLUTs();
    m_pipelinesValid = false;
    invalidateCompile();
    LOGI("Preset Slang recibido: %zu pases. Parámetros: %zu",
         m_pendingPreset.passes.size(), m_pendingPreset.parameters.size());
    // La compilación solo arranca dentro de renderFrame. Si la app capturada está quieta (sin frames nuevos) el preset
    // se quedaba sin compilar indefinidamente: se fuerza un renderFrame (en otro hilo; aquí se tiene m_renderMutex).
    requestRedraw();
}

// --- Modo Glass (T30; PROYECTO.md §10) ---

static const char* const kGlassPassPath = "shaders/internal/glass_resolve";

// Lee una vez los #pragma parameter GLASS_* del pase interno (están en assets, no hay SAF de por medio)
void VulkanRenderer::ensureGlassParams() {
    if (m_glassParamsLoaded) return;
    std::string src = SlangCompiler::fullPreprocess(std::string(kGlassPassPath) + ".slang", m_assetManager);
    if (src.empty()) {
        LOGE("Glass: no se encuentra %s.slang en los assets; no habrá pase interno", kGlassPassPath);
        return;
    }
    SlangCompiler::scanParameters(src, m_glassParams);
    m_glassParamsLoaded = true;
    LOGI("Glass: %zu parámetros GLASS_* leídos del pase interno", m_glassParams.size());
}

// Quita el pase interno y los parámetros GLASS_* (si los hay) y, en Glass con un preset no vacío, los vuelve a poner al final.
void VulkanRenderer::applyGlassPass(ShaderPreset& preset) {
    preset.passes.erase(std::remove_if(preset.passes.begin(), preset.passes.end(),
                                       [](const ShaderPass& p) { return p.internalGlass; }), preset.passes.end());
    preset.parameters.erase(std::remove_if(preset.parameters.begin(), preset.parameters.end(),
                                           [](const ShaderParameter& p) { return p.name.rfind("GLASS_", 0) == 0; }),
                            preset.parameters.end());
    if (m_outputMode != 1 || preset.passes.empty()) return;
    ensureGlassParams();
    if (!m_glassParamsLoaded) return;

    ShaderPass gp;
    gp.index = (int)preset.passes.size();
    gp.filename = kGlassPassPath;
    gp.extension = "slang";
    gp.type = "frag";
    gp.filterLinear = false;                                   // nearest
    gp.scaleType = gp.scaleTypeX = gp.scaleTypeY = SCALE_VIEWPORT;
    gp.scaleX = gp.scaleY = 1.0f;
    gp.wrapModeRepeat = false;                                 // clamp
    gp.internalGlass = true;
    preset.passes.push_back(gp);
    for (const auto& p : m_glassParams) preset.parameters.push_back(p);
}

void VulkanRenderer::releaseGlassInput() {
    if (m_glassInput.image != VK_NULL_HANDLE && m_fbPool) m_fbPool->release(m_glassInput);
    m_glassInput = {};
}

// Cambia de modo en caliente: el swapchain se recrea (alfa de composición), el pase interno entra o sale y todo se recompila.
void VulkanRenderer::applyOutputMode(int mode) {
    LOGI("Modo de salida: %s -> %s", m_outputMode == 1 ? "Glass" : "Captura", mode == 1 ? "Glass" : "Captura");
    m_outputMode = mode;
    deviceWaitIdle();
    // R06: los buffers de captura de antes del cambio no se vuelven a leer (en Glass no hay captura; una sesión de Captura nueva trae
    // los suyos) y Kotlin los devuelve al cerrar su lector
    drainFrames();
    m_rawImportTex = {};
    m_rawLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    m_rawIsPrivate = false;
    flushImportCache();
    m_glassCalibPending = true;
    applyGlassPass(m_pendingPreset);
    rebuildParamCache(); // R02: el pase interno añade o quita los parámetros GLASS_*
    cleanupPipelines();
    cleanupLUTs();
    if (mode != 1) releaseGlassInput();
    m_pipelinesValid = false;
    invalidateCompile();
    if (m_surface && m_window) initSwapchain();
    requestRedraw(); // la compilación del set nuevo arranca en el siguiente renderFrame
}

void VulkanRenderer::discoverParameters(ShaderPreset& preset, AAssetManager* assetManager) {
    preset.parameters.clear();
    int passIdx = 0;
    for (auto& pass : preset.passes) {
        passIdx++;
        {
            std::string shortName = pass.alias.empty() ? pass.filename : pass.alias;
            size_t sl = shortName.find_last_of('/');
            if (sl != std::string::npos) shortName = shortName.substr(sl + 1);
            LoadProgress::setLabel(LoadProgress::code("analyze", {std::to_string(passIdx), std::to_string(preset.passes.size()), shortName}));
        }
        std::string slangPath = pass.filename + ".slang";
        std::string source = SlangCompiler::fullPreprocess(slangPath, assetManager);

        const std::string& passDir = pass.dir.empty() ? preset.basePath : pass.dir; // T45: cada pase de un preset combinado trae su carpeta
        if (source.empty() && !passDir.empty()) {
            source = SlangCompiler::fullPreprocess(passDir + slangPath, assetManager);
        }

        if (!source.empty()) {
            SlangCompiler::scanParameters(source, preset.parameters);
            scanShaderPragmas(source, pass); // #pragma name / #pragma format (T41)
        }
        LoadProgress::done++;
    }
    // Como RetroArch, una clave numérica del preset solo es un override si algún shader declara ese parámetro.
    // Excepción: GLASS_* los declara el pase interno del Modo Glass (que se añade después), así que se conservan.
    for (auto it = preset.parameterOverrides.begin(); it != preset.parameterOverrides.end();) {
        bool declared = it->first.rfind("GLASS_", 0) == 0 ||
                        std::any_of(preset.parameters.begin(), preset.parameters.end(),
                                    [&](const ShaderParameter& p) { return p.name == it->first; });
        it = declared ? std::next(it) : preset.parameterOverrides.erase(it);
    }
}
float VulkanRenderer::resolveParamValue(const std::string& name) {
    return paramValueUnlocked(name, 0.0f);
}

// --- Caché de parámetros (R02) ---

// La lista de parámetros cambió (preset nuevo o el pase interno de Glass entra o sale): índice por nombre y valores actuales.
void VulkanRenderer::rebuildParamCache() {
    m_paramIndex.clear();
    m_paramIndex.reserve(m_pendingPreset.parameters.size());
    for (size_t i = 0; i < m_pendingPreset.parameters.size(); i++)
        m_paramIndex.emplace(m_pendingPreset.parameters[i].name, (int)i); // como antes, vale el primero con ese nombre
    refreshParamValues();
}

// Valores actuales de todos los parámetros declarados: el tocado por el usuario (override) o el del #pragma parameter
void VulkanRenderer::refreshParamValues() {
    const auto& params = m_pendingPreset.parameters;
    const auto& ov = m_pendingPreset.parameterOverrides;
    m_paramValues.resize(params.size());
    for (size_t i = 0; i < params.size(); i++) {
        auto it = ov.find(params[i].name);
        m_paramValues[i] = it != ov.end() ? it->second : params[i].defaultValue;
    }
    refreshGlassCache();
}

void VulkanRenderer::refreshGlassCache() {
    m_glassAnimFpsCached.store(m_outputMode == 1 ? std::max(0.0f, paramValueUnlocked("GLASS_ANIM_FPS", 0.0f)) : 0.0f,
                               std::memory_order_relaxed);
    m_glassBlurCached.store(paramValueUnlocked("GLASS_BLUR", NAN), std::memory_order_relaxed);
}

// Al adoptar un set (hilo de render, con m_renderMutex): deja resuelto en cada binding lo que antes se buscaba por nombre en cada
// frame (índice del parámetro, tamaño de un pase o de una LUT) y en cada sampler su VkSampler y la vista de su LUT. El set solo
// vive mientras no cambien el preset ni el modo, y los dos lo sustituyen por otro, así que lo resuelto vale toda su vida.
void VulkanRenderer::resolveSetBindings() {
    const auto& passes = m_pendingPreset.passes;
    const auto& textures = m_pendingPreset.textures;
    static const std::string kSize = "Size";
    static const std::string kFeedback = "Feedback";

    for (size_t i = 0; i < m_pipelines.size(); i++) {
        VulkanPipeline* pass = m_pipelines[i].get();
        for (auto& f : pass->semantics()) {
            f.ref = BindingRef::None;
            f.refIndex = -1;
            f.refW = f.refH = 0;
            f.glassGain = false;
            if (f.semantic == SlangSemantic::FloatParameter) {
                auto it = m_paramIndex.find(f.name);
                if (it != m_paramIndex.end()) { f.ref = BindingRef::Param; f.refIndex = it->second; }
                else f.ref = BindingRef::ByName;
                f.glassGain = (f.name == "GLASS_GAIN");
            } else if (f.semantic == SlangSemantic::LUTSize) {
                // Mismo orden que antes: "<alias>Size" / "<alias>FeedbackSize" de un pase o "<id de LUT>Size" (p. ej. ORIG_LINEARIZEDSize,
                // mask_grille_texture_smallSize); si no, un #pragma parameter con "Size" en el nombre (es un float normal); si no, Original
                bool resolved = false, aliasMatched = false;
                if (f.name.size() > kSize.size() && f.name.compare(f.name.size() - kSize.size(), kSize.size(), kSize) == 0) {
                    std::string base = f.name.substr(0, f.name.size() - kSize.size());
                    if (base.size() > kFeedback.size() && base.compare(base.size() - kFeedback.size(), kFeedback.size(), kFeedback) == 0)
                        base = base.substr(0, base.size() - kFeedback.size());
                    for (size_t k = 0; k < passes.size(); k++) {
                        if (passes[k].alias.empty() || passes[k].alias != base) continue;
                        aliasMatched = true;
                        if (k < m_passResources.size()) { f.ref = BindingRef::PassSize; f.refIndex = (int)k; resolved = true; }
                        break;
                    }
                    if (!aliasMatched) {
                        auto lit = m_lutTextures.find(base);
                        if (lit != m_lutTextures.end() && lit->second.width && lit->second.height) {
                            f.ref = BindingRef::LutSize; f.refW = lit->second.width; f.refH = lit->second.height; resolved = true;
                        }
                    }
                }
                if (!resolved) {
                    auto it = m_paramIndex.find(f.name);
                    if (it != m_paramIndex.end()) {
                        f.ref = BindingRef::Param; f.refIndex = it->second;
                    } else {
                        // No debe quedar a 0: 1/size sería inf/NaN y pintaría todo negro
                        f.ref = BindingRef::Original;
                        LOGE("writePassSemantics: no se resolvió el tamaño de '%s' (pase %zu), usando Original", f.name.c_str(), i);
                    }
                }
            }
        }

        // Filtro y wrap por origen (T42, shader_vulkan.c:1647-1740): Original y OriginalHistoryN usan los del pase 0; la salida de un
        // pase (Source y PassOutputN) se lee con los del pase SIGUIENTE a quien la produjo; PassFeedbackN, con los de su propio pase.
        // Las LUTs usan su propio filtro/wrap (<id>_linear, <id>_wrap_mode).
        for (auto& rs : pass->resolvedSamplers()) {
            size_t refPass = i;
            if (rs.semantic == TextureSemantic::Original || rs.semantic == TextureSemantic::OriginalHistory) refPass = 0;
            else if (rs.semantic == TextureSemantic::PassOutput) refPass = (rs.index >= 0 && rs.index < (int)i) ? (size_t)rs.index + 1 : 0;
            else if (rs.semantic == TextureSemantic::PassFeedback) refPass = (rs.index >= 0) ? (size_t)rs.index : 0;
            if (refPass >= passes.size()) refPass = i;
            bool filterLinear = refPass < passes.size() && passes[refPass].filterLinear;
            bool wrapRepeat = refPass < passes.size() && passes[refPass].wrapModeRepeat;
            rs.lutView = VK_NULL_HANDLE;
            if (rs.semantic == TextureSemantic::User && rs.index >= 0 && rs.index < (int)textures.size()) {
                filterLinear = textures[rs.index].linear;
                wrapRepeat = textures[rs.index].wrapModeRepeat;
                auto lit = m_lutTextures.find(textures[rs.index].id);
                if (lit != m_lutTextures.end()) rs.lutView = lit->second.view;
            }
            rs.sampler = m_samplers[filterLinear ? 1 : 0][wrapRepeat ? 1 : 0];
        }
    }
}

// Rotación del contenido que ven los shaders (semántica Rotation de RetroArch): 0 = sin rotar. m_rotation (orientación del
// dispositivo) ya no se usa aquí: la rotación de pantalla la absorbe el MVP/pre-transform y la captura llega ya girada.
static constexpr uint32_t kContentRotation = 0;

// Lo que comparten todos los pases de un frame (R02): antes se recalculaba (y se tomaba el mutex de los sensores) en cada binding.
void VulkanRenderer::computeFrameConsts(FrameConsts& fc) {
    static const float kIdentity[16] = {1, 0, 0, 0,   0, 1, 0, 0,   0, 0, 1, 0,   0, 0, 0, 1};
    static const float kR90[16]      = {0, 1, 0, 0,  -1, 0, 0, 0,   0, 0, 1, 0,   0, 0, 0, 1};
    static const float kR180[16]     = {-1, 0, 0, 0,  0,-1, 0, 0,   0, 0, 1, 0,   0, 0, 0, 1};
    static const float kR270[16]     = {0, -1, 0, 0,  1, 0, 0, 0,   0, 0, 1, 0,   0, 0, 0, 1};
    const float* m = kIdentity;
    if (m_preTransform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR) m = kR90;
    else if (m_preTransform == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR) m = kR180;
    else if (m_preTransform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR) m = kR270;
    memcpy(fc.mvp, m, sizeof(fc.mvp));
    memcpy(fc.mvpIdentity, kIdentity, sizeof(fc.mvpIdentity));
    // Glass estático: FrameCount 0 y FrameTimeDelta fijo, así cada redibujado da la misma imagen; con GLASS_ANIM_FPS > 0 (T34) avanzan
    fc.glassStill = m_outputMode == 1 && paramValueUnlocked("GLASS_ANIM_FPS", 0.0f) <= 0.0f;
    // Solo si algún pase usa los sensores (m_sensorsNeeded sale de esos mismos bindings al adoptar el set)
    if (m_sensorsNeeded) {
        std::lock_guard<std::mutex> il(m_inputMutex);
        memcpy(fc.gyro, m_gyro, sizeof(fc.gyro));
        memcpy(fc.accel, m_accel, sizeof(fc.accel));
        memcpy(fc.accelRest, m_accelRest, sizeof(fc.accelRest));
    }
}

void VulkanRenderer::writePassSemantics(VulkanPipeline* pass, size_t passIndex, uint32_t frameIndex,
                                         uint32_t outW, uint32_t outH,
                                         uint32_t srcW, uint32_t srcH,
                                         uint32_t origW, uint32_t origH, const FrameConsts& fc, bool toSwapchain) {
    uint8_t* uboPtr = pass->hasUbo() ? pass->getUboWritePtr(frameIndex) : nullptr;
    uint8_t* pushPtr = pass->hasPushConstant() ? pass->getPushConstantScratch() : nullptr;

    auto writeField = [&](const SemanticBinding& f, const void* data, size_t size) {
        uint8_t* dst = f.isPushConstant ? pushPtr : uboPtr;
        if (!dst) return;
        memcpy(dst + f.offset, data, std::min(size, f.size));
    };
    auto writeVec4 = [&](const SemanticBinding& f, float x, float y, float z, float w) {
        float v[4] = {x, y, z, w};
        writeField(f, v, sizeof(v));
    };

    // Tamaño real de la salida del pase k (el último pase escribe al swapchain y no tiene recurso propio)
    auto passSizeByIndex = [&](int k, uint32_t& w, uint32_t& h) {
        if (k < 0 || k >= (int)m_passResources.size()) return;
        const auto& r = m_passResources[k];
        if (r.image != VK_NULL_HANDLE) { w = r.width; h = r.height; }
        else { w = m_width; h = m_height; }
    };
    // Valor de un parámetro ya resuelto (R02); si no se pudo resolver al adoptar el set, por nombre como antes
    auto paramValue = [&](const SemanticBinding& f) -> float {
        if (f.ref == BindingRef::Param && f.refIndex >= 0 && (size_t)f.refIndex < m_paramValues.size()) return m_paramValues[f.refIndex];
        return resolveParamValue(f.name);
    };
    // frame_count_modN: el pase ve FrameCount % N (0 = sin módulo)
    const uint32_t frameMod = passIndex < m_pendingPreset.passes.size() ? m_pendingPreset.passes[passIndex].frameCountMod : 0;

    for (const auto& f : pass->getSemantics()) {
        switch (f.semantic) {
            case SlangSemantic::MVP:
                // R13: girada solo en el pase que escribe en el swapchain; los framebuffers intermedios van siempre derechos
                writeField(f, toSwapchain ? fc.mvp : fc.mvpIdentity, sizeof(fc.mvp));
                break;
            case SlangSemantic::SourceSize:
                writeVec4(f, (float)srcW, (float)srcH, 1.0f/srcW, 1.0f/srcH);
                break;
            case SlangSemantic::OriginalSize:
                writeVec4(f, (float)origW, (float)origH, 1.0f/origW, 1.0f/origH);
                break;
            case SlangSemantic::OutputSize:
                writeVec4(f, (float)outW, (float)outH, 1.0f/outW, 1.0f/outH);
                break;
            case SlangSemantic::FinalViewportSize:
                writeVec4(f, (float)m_width, (float)m_height, 1.0f/m_width, 1.0f/m_height);
                break;
            case SlangSemantic::FrameCount: {
                // Glass estático: siempre 0 (cada redibujado da la misma imagen); con GLASS_ANIM_FPS > 0 (T34) avanza como en Captura
                uint32_t v = fc.glassStill ? 0 : (frameMod ? m_frameCount % frameMod : m_frameCount);
                writeField(f, &v, sizeof(uint32_t));
                break;
            }
            case SlangSemantic::FrameDirection: {
                int32_t v = 1; writeField(f, &v, sizeof(v)); break;
            }
            case SlangSemantic::FrameTimeDelta: {
                // Glass estático: 16667 µs fijos, así cada redibujado da la misma imagen; animado, el tiempo real
                uint32_t d = fc.glassStill ? 16667u : m_frameTimeDelta;
                writeField(f, &d, sizeof(uint32_t));
                break;
            }
            case SlangSemantic::TotalSubFrames:
            case SlangSemantic::CurrentSubFrame: {
                // Sin subframes (shader_subframes = 1), como RetroArch (shader_vulkan.c:3280): total 1, actual 1
                uint32_t one = 1;
                writeField(f, &one, sizeof(uint32_t));
                break;
            }
            case SlangSemantic::Rotation: {
                // En RetroArch es la rotación del CONTENIDO (núcleo + usuario), no la orientación física del dispositivo.
                // Aquí la captura ya llega girada como la pantalla, así que el contenido nunca está rotado: 0.
                // (Pasar la rotación del display hacía que koko-aio tratara el juego como TATE y aplastara la imagen.)
                uint32_t rot = kContentRotation;
                writeField(f, &rot, sizeof(uint32_t));
                break;
            }
            case SlangSemantic::Gyroscope:
                writeField(f, fc.gyro, sizeof(fc.gyro));
                break;
            case SlangSemantic::Accelerometer:
                writeField(f, fc.accel, sizeof(fc.accel));
                break;
            case SlangSemantic::AccelerometerRest:
                writeField(f, fc.accelRest, sizeof(fc.accelRest));
                break;
            case SlangSemantic::FloatParameter: {
                float v = paramValue(f);
                // GLASS_GAIN <= 0 = Auto (T33): la ganancia calibrada (1 hasta tener la primera medida)
                if (f.glassGain && m_outputMode == 1 && v <= 0.0f) v = m_glassGain;
                writeField(f, &v, sizeof(v));
                break;
            }
            case SlangSemantic::PassOutputSize: {
                // PassOutputNSize
                uint32_t w = origW, h = origH;
                passSizeByIndex(f.index, w, h);
                writeVec4(f, (float)w, (float)h, 1.0f/w, 1.0f/h);
                break;
            }
            case SlangSemantic::OriginalHistorySize:
                // Sin historial de frames todavía: OriginalHistoryN equivale a Original
                writeVec4(f, (float)origW, (float)origH, 1.0f/origW, 1.0f/origH);
                break;
            case SlangSemantic::LUTSize: {
                // Resuelto al adoptar el set (resolveSetBindings): tamaño de un pase o de una LUT, un parámetro o, si no, Original
                switch (f.ref) {
                    case BindingRef::PassSize: {
                        uint32_t w = origW, h = origH;
                        passSizeByIndex(f.refIndex, w, h);
                        writeVec4(f, (float)w, (float)h, 1.0f/w, 1.0f/h);
                        break;
                    }
                    case BindingRef::LutSize:
                        writeVec4(f, (float)f.refW, (float)f.refH, 1.0f/f.refW, 1.0f/f.refH);
                        break;
                    case BindingRef::Param: {
                        float v = paramValue(f);
                        writeField(f, &v, sizeof(v));
                        break;
                    }
                    default:
                        writeVec4(f, (float)origW, (float)origH, 1.0f/origW, 1.0f/origH);
                        break;
                }
                break;
            }
            case SlangSemantic::OriginalAspect:
            case SlangSemantic::OriginalAspectRotated: {
                float aspect = m_height ? (float)m_width / (float)m_height : 1.0f;
                // Como vulkan.c:7385: OriginalAspectRotated es 1/aspect solo con el contenido rotado 90/270
                if (f.semantic == SlangSemantic::OriginalAspectRotated && aspect != 0.0f &&
                    (kContentRotation == 1 || kContentRotation == 3)) aspect = 1.0f / aspect;
                writeField(f, &aspect, sizeof(float));
                break;
            }
            case SlangSemantic::OriginalFPS: {
                float fps = 60.0f;
                writeField(f, &fps, sizeof(float));
                break;
            }
            default:
                break;
        }
    }
}

void VulkanRenderer::getPassOutputSize(const ShaderPass& pass, uint32_t origW, uint32_t origH,
                        uint32_t srcW, uint32_t srcH, uint32_t viewportW, uint32_t viewportH,
                        uint32_t& outW, uint32_t& outH) {
    // El tipo de escala es independiente por eje (crt-royale: x absolute / y viewport)
    // Igual que slang_pass_get_output_size de RetroArch: float y roundf (no truncar: 431.99998 -> 432)
    auto axis = [](ScaleType t, float scale, uint32_t source, uint32_t viewport) -> uint32_t {
        float v;
        switch (t) {
            case SCALE_VIEWPORT: v = (float)viewport * scale; break;
            case SCALE_ABSOLUTE: v = scale; break;
            default:             v = (float)source * scale; break;
        }
        return (uint32_t)roundf(v);
    };
    outW = axis(pass.scaleTypeX, pass.scaleX, srcW, viewportW);
    outH = axis(pass.scaleTypeY, pass.scaleY, srcH, viewportH);
    if (outW == 0) outW = 1;
    if (outH == 0) outH = 1;
}

std::map<std::string, TextureSemanticAlias> VulkanRenderer::buildAliasMap(const ShaderPreset& preset) {
    std::map<std::string, TextureSemanticAlias> map;
    // Alias de pases
    for (size_t i = 0; i < preset.passes.size(); i++) {
        if (!preset.passes[i].alias.empty()) {
            map[preset.passes[i].alias] = {TextureSemantic::PassOutput, (int)i};
            map[preset.passes[i].alias + "Feedback"] = {TextureSemantic::PassFeedback, (int)i};
        }
    }
    // IDs de texturas (LUTs)
    for (size_t i = 0; i < preset.textures.size(); i++) {
        map[preset.textures[i].id] = {TextureSemantic::User, (int)i};
    }
    return map;
}

static constexpr float kGlassAutoPixel = 4.0f; // Glass, resolución "Auto": píxeles de pantalla por píxel virtual

// Tamaño de "Original" (la entrada del primer pase): la resolución virtual elegida, o con "Auto" el tamaño real de la zona
// del shader (la captura recortada a la zona). Con zona completa, la captura entera, como siempre.
void VulkanRenderer::getVirtualNativeDimensions(uint32_t& outW, uint32_t& outH) {
    // Tamaño en píxeles de la zona dentro de la captura. Sin captura todavía, la ventana del overlay (m_width x m_height)
    // YA mide lo mismo que la zona, así que no se vuelve a aplicar la fracción.
    uint32_t zoneW, zoneH;
    if (m_outputMode == 1) {
        // Glass: no hay captura (m_rawImportTex puede guardar una de una sesión anterior). La ventana mide lo mismo que la zona.
        zoneW = m_width ? m_width : 1920;
        zoneH = m_height ? m_height : 1080;
    } else if (m_rawImportTex.width > 0 && m_rawImportTex.height > 0) {
        VkOffset3D s, e;
        regionSourceRect(m_rawImportTex.width, m_rawImportTex.height, &s, &e);
        zoneW = (uint32_t)(e.x - s.x);
        zoneH = (uint32_t)(e.y - s.y);
    } else {
        zoneW = m_width ? m_width : 1920;
        zoneH = m_height ? m_height : 1080;
    }

    if (m_virtualNativeWidth > 0.0f && m_virtualNativeHeight > 0.0f) {
        uint32_t vMin = (uint32_t)roundf(std::min(m_virtualNativeWidth, m_virtualNativeHeight));
        uint32_t vMax = (uint32_t)roundf(std::max(m_virtualNativeWidth, m_virtualNativeHeight));

        // La orientación de la rejilla sigue a la de la ZONA (una zona 4:3 en un móvil en vertical es apaisada)
        if (zoneW >= zoneH) {
            outW = vMax;
            outH = vMin;
        } else {
            outW = vMin;
            outH = vMax;
        }
    } else if (m_outputMode == 1) {
        // "Auto" en Glass: una línea virtual cada kGlassAutoPixel píxeles de pantalla (entero, sin moiré)
        outW = std::max(1u, (uint32_t)lroundf((float)zoneW / kGlassAutoPixel));
        outH = std::max(1u, (uint32_t)lroundf((float)zoneH / kGlassAutoPixel));
    } else {
        outW = zoneW;
        outH = zoneH;
    }
}

// Vuelve a los valores con los que cargó el preset (los suyos y los de sus #reference; el resto, el valor por defecto del
// #pragma). Es la base sobre la que se aplica un perfil: así un perfil que no guarda un parámetro no hereda el de otro.
void VulkanRenderer::resetParametersToPreset() {
    std::lock_guard<std::mutex> lock(m_renderMutex);
    m_glassCalibPending = true;
    m_pendingPreset.parameterOverrides = m_presetOverrides;
    refreshParamValues(); // R02
}

void VulkanRenderer::updateParameter(const std::string& name, float value) {
    std::lock_guard<std::mutex> lock(m_renderMutex);
    // Como RetroArch: el valor siempre dentro de [min, max] del parámetro; NaN/inf se ignoran. Un valor guardado fuera de
    // rango (perfil corrupto) podía producir NaN en el shader y dejar la salida en negro.
    if (!std::isfinite(value)) {
        LOGW("SVDIAG_PARAMS: valor no finito ignorado para '%s'", name.c_str());
        return;
    }
    for (const auto& p : m_pendingPreset.parameters) {
        if (p.name == name) {
            float lo = std::min(p.min, p.max), hi = std::max(p.min, p.max);
            float clamped = std::min(std::max(value, lo), hi);
            if (clamped != value)
                LOGW("SVDIAG_PARAMS: '%s'=%g fuera de rango [%g,%g] -> %g", name.c_str(), value, lo, hi, clamped);
            value = clamped;
            break;
        }
    }
    // Todo parámetro salvo los de la propia capa Glass puede cambiar el patrón del preset: hay que volver a medir (T33)
    if (name != "GLASS_STRENGTH" && name != "GLASS_COLOR" && name != "GLASS_GAIN" && name != "GLASS_ANIM_FPS" && name != "GLASS_BLUR")
        m_glassCalibPending = true;
    m_pendingPreset.parameterOverrides[name] = value;
    auto it = m_paramIndex.find(name); // R02: el valor que lee el render de cada frame
    if (it != m_paramIndex.end() && (size_t)it->second < m_paramValues.size()) m_paramValues[it->second] = value;
    if (name == "GLASS_ANIM_FPS" || name == "GLASS_BLUR") refreshGlassCache(); // R22
}

// Se llama al inicio de cada renderFrame (hilo de render, con m_renderMutex): adopta el set que haya terminado de
// compilarse en segundo plano y lanza una compilación nueva si hace falta. NUNCA compila aquí: compilar los 12 pases de
// crt-royale tarda ~20 s y bloquearía el render, el menú del overlay y cualquier llamada JNI que espere el mutex.
void VulkanRenderer::createPassResources() {
    // La orientación de la captura (y con ella el tamaño virtual) puede cambiar después de haber lanzado la compilación:
    // la ventana del overlay y el VirtualDisplay se redimensionan por separado y el primer frame girado llega tarde.
    // Se comprueba ANTES de adoptar un set listo para que uno obsoleto se descarte.
    if (!m_hasPendingPreset && !m_pendingPreset.passes.empty() && m_compiledInputW != 0 &&
        (m_outputMode == 1 || m_rawImportTex.image != VK_NULL_HANDLE)) {
        uint32_t curW, curH;
        getVirtualNativeDimensions(curW, curH);
        if (curW != m_compiledInputW || curH != m_compiledInputH) {
            LOGI("createPassResources: la entrada pasó de %ux%u a %ux%u -> se recompila el preset",
                 m_compiledInputW, m_compiledInputH, curW, curH);
            invalidateCompile();
        }
    }

    std::unique_ptr<CompiledPipelineSet> ready;
    { std::lock_guard<std::mutex> l(m_swapMutex); ready = std::move(m_readyPipelineSet); }
    if (ready) {
        if (ready->generation == m_compileGeneration.load() && m_renderPass != VK_NULL_HANDLE) {
            adoptCompiledSet(*ready);
        } else {
            LOGI("createPassResources: set compilado obsoleto (gen %llu, actual %llu): se descarta",
                 (unsigned long long)ready->generation, (unsigned long long)m_compileGeneration.load());
            disposeCompiledSet(*ready);
        }
    }

    if (m_hasPendingPreset && !m_compileInProgress.load() && m_renderPass != VK_NULL_HANDLE && m_width > 0 && m_height > 0) {
        startBackgroundCompile();
    }
}

void VulkanRenderer::startBackgroundCompile() {
    if (m_compileThread.joinable()) m_compileThread.join(); // el anterior ya terminó (m_compileInProgress == false)

    if (m_pendingPreset.passes.empty()) {
        cleanupPipelines();
        updateSensorUsage(false);
        m_pipelinesValid = false;
        m_hasPendingPreset = false;
        LoadProgress::finish();
        return;
    }

    uint32_t iw, ih;
    getVirtualNativeDimensions(iw, ih);
    m_compiledInputW = iw;
    m_compiledInputH = ih;

    CompileEnv env;
    env.viewportW = m_width;
    env.viewportH = m_height;
    env.rpFinal = m_renderPass;
    env.rpUnorm = m_offscreenRenderPass;
    env.rpFloat = m_offscreenRenderPassFloat;
    env.rpSrgb  = m_offscreenRenderPassSrgb;
    env.generation = m_compileGeneration.load();
    env.glass = (m_outputMode == 1);
    env.preRotated = m_preRotated;
    m_compiledViewportW = m_width;
    m_compiledViewportH = m_height;
    m_compiledPreRotated = m_preRotated;

    ShaderPreset preset = m_pendingPreset;
    m_hasPendingPreset = false;
    // Progreso: si la carga empezó en loadPreset (análisis de los .slang) los pasos de compilación ya están contados;
    // en una recompilación suelta (rotación, cambio de resolución...) se abre una barra nueva.
    // Con varias salidas (T15) la barra es una sola: cada compilación se suma a la que ya esté en curso.
    LoadProgress::compileBegin((int)(preset.passes.size() + preset.textures.size()));
    m_compileInProgress = true;
    m_abortCompilation = false;
    LOGI("Compilación en segundo plano iniciada: %zu pases, entrada %ux%u, viewport %ux%u (gen %llu)",
         preset.passes.size(), iw, ih, env.viewportW, env.viewportH, (unsigned long long)env.generation);

    m_compileThread = std::thread([this, preset = std::move(preset), iw, ih, env]() {
        // R05: compilar (glslang, reflexión y pipelines) ocupa un núcleo entero durante segundos. Con prioridad baja (nice 10) cede la
        // CPU al emulador y al render cuando compiten por ella, y aprovecha la que sobra. Mientras, se ve la captura sin procesar.
        setpriority(PRIO_PROCESS, gettid(), 10);
        auto t0 = std::chrono::steady_clock::now();
        auto set = std::make_unique<CompiledPipelineSet>(compilePipelineSet(preset, iw, ih, env));
        set->generation = env.generation;
        set->inputW = iw;
        set->inputH = ih;
        // Terminó la carga si esta compilación sigue vigente; si quedó obsoleta, llegará otra y seguirá la barra
        const bool current = env.generation == m_compileGeneration.load();
        if (current) reportFailedPasses(preset, *set);
        // R07: lo que el driver compiló se guarda en disco para la próxima vez (solo si la caché creció; aquí, con prioridad baja)
        if (current && !m_abortCompilation.load()) savePipelineCacheToDisk();
        LoadProgress::compileEnd(current);
        long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        LOGI("Compilación en segundo plano terminada en %lld ms (%zu pipelines, gen %llu)",
             ms, set->pipelines.size(), (unsigned long long)env.generation);
        {
            std::unique_ptr<CompiledPipelineSet> old;
            std::lock_guard<std::mutex> l(m_swapMutex);
            old = std::move(m_readyPipelineSet); // normalmente null
            m_readyPipelineSet = std::move(set);
            if (old) disposeCompiledSet(*old);
        }
        m_compileInProgress = false;
        if (m_onCompileReady) m_onCompileReady(); // forzar un redibujado: puede que no lleguen frames nuevos de captura
    });
}

// Definida en native-lib.cpp: llama a VulkanEngineBridge.onSensorsNeeded(Z) en Kotlin
extern void notifySensorsNeeded(bool needed);

void VulkanRenderer::updateSensorUsage(bool needed) {
    if (needed == m_sensorsNeeded) return;
    m_sensorsNeeded = needed;
    LOGI("Sensores %s (el preset %s Gyroscope/Accelerometer/AccelerometerRest)", needed ? "necesarios" : "no necesarios",
         needed ? "usa" : "no usa");
    if (needed) startAccelRestCapture(); // AccelerometerRest: nueva captura de reposo al encenderlos
    notifySensorsNeeded(needed);
}

// Deja en LoadProgress el motivo si algún pase no tiene pipeline (la UI lo muestra al terminar la carga).
// Si el set quedó incompleto por haberse abortado, no se avisa: llegará otra compilación.
void VulkanRenderer::reportFailedPasses(const ShaderPreset& preset, const CompiledPipelineSet& set) {
    if (set.pipelines.size() < preset.passes.size()) return;
    int failed = 0;
    size_t firstIdx = 0;
    std::string firstName;
    for (size_t i = 0; i < set.pipelines.size(); i++) {
        if (set.pipelines[i]->isValid()) continue;
        if (failed++ == 0) {
            std::string name = preset.passes[i].alias.empty() ? preset.passes[i].filename : preset.passes[i].alias;
            size_t sl = name.find_last_of('/');
            if (sl != std::string::npos) name = name.substr(sl + 1);
            firstIdx = i + 1;
            firstName = name;
        }
    }
    if (failed == 0) return;
    LOGE("SHADER_ERROR: pase %zu (%s) y %d más no compilaron; se muestra la captura sin procesar", firstIdx, firstName.c_str(), failed - 1);
    LoadProgress::addStatus(LoadProgress::code("passfail", {std::to_string(firstIdx), firstName, std::to_string(failed - 1)}));
}

void VulkanRenderer::disposeCompiledSet(CompiledPipelineSet& set) {
    for (auto& fb : set.passResources) if (fb.image != VK_NULL_HANDLE) m_fbPool->release(fb);
    set.passResources.clear();
    for (auto& fb : set.feedbackResources) if (fb.image != VK_NULL_HANDLE) m_fbPool->release(fb);
    set.feedbackResources.clear();
    set.pipelines.clear();
    for (auto& pair : set.lutTextures) {
        if (pair.second.view) vkDestroyImageView(m_device, pair.second.view, nullptr);
        if (pair.second.image) vkDestroyImage(m_device, pair.second.image, nullptr);
        if (pair.second.memory) vkFreeMemory(m_device, pair.second.memory, nullptr);
    }
    set.lutTextures.clear();
    set.lutIds.clear();
}

void VulkanRenderer::adoptCompiledSet(CompiledPipelineSet& newSet) {
    cleanupPipelines();
    cleanupLUTs();
    // R10: lo que el set anterior devolvió al pool (sus framebuffers, el historial, sets obsoletos) se destruye ya: el pool nunca
    // soltaba nada hasta cerrar, y tras girar o cambiar de resolución guardaba los framebuffers de los dos tamaños (en presets como
    // crt-royale, cientos de MB de memoria de GPU que también necesita el emulador). cleanupPipelines ya esperó a la GPU.
    m_fbPool->trim();
    m_pipelines = std::move(newSet.pipelines);
    m_passResources = std::move(newSet.passResources);
    m_passRenderPasses = std::move(newSet.renderPasses);
    m_feedbackResources = std::move(newSet.feedbackResources);
    m_finalOffscreen = newSet.finalOffscreen;
    m_feedbackNeedsInit = true;
    m_lutTextures = std::move(newSet.lutTextures);
    m_lutIds = std::move(newSet.lutIds);
    m_activeInputW = newSet.inputW;
    m_activeInputH = newSet.inputH;
    m_glassCalibPending = true; // set nuevo (preset, resolución virtual o viewport distintos): hay que volver a medir (T33)
    resolveSetBindings(); // R02: parámetros, tamaños, samplers y LUTs resueltos una vez, no en cada frame

    // ¿Compilaron todos los pases? Con un pipeline nulo el draw es inválido y la pantalla sale negra.
    m_pipelinesValid = !m_pipelines.empty();
    for (size_t i = 0; i < m_pipelines.size(); i++) {
        if (!m_pipelines[i]->isValid()) {
            m_pipelinesValid = false;
            LOGE("createPassResources: el pase %zu (%s) NO tiene pipeline válido; se mostrará la captura sin procesar",
                 i, m_pendingPreset.passes[i].filename.c_str());
        }
    }

    // Frames de historial que necesita el preset: mayor N de OriginalHistoryN (muestreador o uniforme OriginalHistoryNSize)
    uint32_t hist = 0;
    for (auto& p : m_pipelines) {
        for (auto& rs : p->getResolvedSamplers())
            if (rs.semantic == TextureSemantic::OriginalHistory && rs.index > 0) hist = std::max(hist, (uint32_t)rs.index);
        for (auto& f : p->getSemantics())
            if (f.semantic == SlangSemantic::OriginalHistorySize && f.index > 0) hist = std::max(hist, (uint32_t)f.index);
    }
    m_historyCount = std::min(hist, (uint32_t)MAX_HISTORY_STEPS);
    m_historyNeedsInit = true;

    // ¿Algún pase usa los sensores? (shader_vulkan.c:3520: shader_uses_sensors). Solo entonces se encienden.
    bool usesSensors = false;
    for (auto& p : m_pipelines)
        for (auto& f : p->getSemantics())
            if (f.semantic == SlangSemantic::Gyroscope || f.semantic == SlangSemantic::Accelerometer ||
                f.semantic == SlangSemantic::AccelerometerRest) usesSensors = true;
    updateSensorUsage(usesSensors);
    LOGI("createPassResources: set adoptado: %zu pases, válidos=%d, historial=%u frames", m_pipelines.size(), m_pipelinesValid ? 1 : 0, m_historyCount);
    // Diagnóstico: parámetros aplicados que difieren del valor por defecto (un perfil guardado raro puede dejar la salida negra)
    for (const auto& kv : m_pendingPreset.parameterOverrides)
        for (const auto& p : m_pendingPreset.parameters)
            if (p.name == kv.first && std::fabs(p.defaultValue - kv.second) > 1e-6f)
                LOGI("SVDIAG_PARAMS: %s = %g (defecto %g, rango %g..%g)", p.name.c_str(), kv.second, p.defaultValue, p.min, p.max);
}

// Se ejecuta en el HILO DE COMPILACIÓN. No debe tocar miembros mutables del renderer (render passes, tamaño, LUTs
// activas...): todo lo necesario llega en 'env' y el resultado se devuelve en el set.
CompiledPipelineSet VulkanRenderer::compilePipelineSet(const ShaderPreset& preset, uint32_t iw, uint32_t ih, const CompileEnv& env) {
    CompiledPipelineSet set;
    auto aliasMap = buildAliasMap(preset);
    uint32_t srcW = iw, srcH = ih;
    uint32_t viewportW = env.viewportW, viewportH = env.viewportH;

    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(m_physDev, &props);

    // Cargar LUTs definidos en el preset (al set, no a los miembros activos)
    for (const auto& tex : preset.textures) {
        auto t0 = std::chrono::steady_clock::now();
        LoadProgress::setLabel(LoadProgress::code("texture", {tex.id}));
        if (!loadLUT(tex, preset.basePath, set.lutTextures, set.lutIds)) {
            LOGE("Error cargando LUT: %s", tex.id.c_str());
        }
        LoadProgress::done++;
        (void)t0;
    }

    for (size_t i = 0; i < preset.passes.size(); i++) {
        // Compilación obsoleta (preset nuevo, otro viewport...) o renderer en destrucción: abandonar cuanto antes
        if (m_abortCompilation.load() || env.generation != m_compileGeneration.load()) {
            LOGI("compilePipelineSet: abortada en el pase %zu (obsoleta)", i);
            return set;
        }
        const auto& p = preset.passes[i];
        bool isFinal = (i + 1 == preset.passes.size());
        // Glass: el último pase del preset (el penúltimo del set) es el "final" de Captura: tamaño de viewport, UNORM, sin
        // mirar float/srgb_framebuffer ni su scale_type, pero escribe un intermedio en vez del swapchain (que es del pase interno)
        const bool glassMid = env.glass && (i + 2 == preset.passes.size());

        // Tamaño de la salida (antes que el pipeline: T44 decide con él si el último pase pasa por un intermedio)
        uint32_t outW, outH;
        getPassOutputSize(p, iw, ih, srcW, srcH, viewportW, viewportH, outW, outH);
        if (glassMid) { outW = std::max(1u, viewportW); outH = std::max(1u, viewportH); }
        // T44: un último pase con scale_type que no da el tamaño del viewport (RetroArch añade un pase de copia): escribe un
        // framebuffer propio y luego se copia al swapchain. En Glass se mantiene el camino directo.
        // R13: con el swapchain girado, un último pase que use gl_FragCoord tampoco puede escribir en él (sus coordenadas serían las del
        // panel, no las de la imagen: las máscaras de guest-advanced saldrían giradas): escribe derecho, al tamaño del viewport si no
        // trae escala, y se copia girado.
        const bool fragCoordFinal = isFinal && !env.glass && env.preRotated && p.usesFragCoord;
        if (fragCoordFinal && !p.scaleSpecified) { outW = std::max(1u, viewportW); outH = std::max(1u, viewportH); }
        const bool finalOffscreen = isFinal && !env.glass &&
                                    (fragCoordFinal || (p.scaleSpecified && (outW != viewportW || outH != viewportH)));
        const bool toSwapchain = isFinal && !finalOffscreen;
        const VkFormat outFmt = passFormat(p, glassMid);

        VkRenderPass rp = toSwapchain ? env.rpFinal : offscreenRenderPassFor(outFmt);
        set.renderPasses.push_back(rp);
        if (finalOffscreen) set.finalOffscreen = true;

        auto tPass = std::chrono::steady_clock::now();
        {
            std::string shortName = p.alias.empty() ? p.filename : p.alias;
            size_t sl = shortName.find_last_of('/');
            if (sl != std::string::npos) shortName = shortName.substr(sl + 1);
            LoadProgress::setLabel(LoadProgress::code("compile", {std::to_string(i + 1), std::to_string(preset.passes.size()), shortName}));
        }
        auto pipeline = std::make_unique<VulkanPipeline>(m_device, m_assetManager, props);
        // El pase interno vive en los assets: sin basePath (evita probar rutas SAF que no existen)
        if (!pipeline->init(rp, p.filename, p.filename, p.internalGlass ? std::string() : (p.dir.empty() ? preset.basePath : p.dir), aliasMap, FRAMES_IN_FLIGHT, p.passBase,
                            sharedPipelineCache())) {
            __android_log_print(ANDROID_LOG_ERROR, "VulkanRenderer", "compilePipelineSet: fallo en pase %zu (%s)", i, p.filename.c_str());
        }
        LOGI("compilePipelineSet: pase %zu compilado en %lld ms (válido=%d)", i,
             (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - tPass).count(),
             pipeline->isValid() ? 1 : 0);

        LOGI("compilePipelineSet: Pass %zu (%s) -> Output Size: %ux%u (scaleType=%d/%d, viewport=%ux%u, isFinal=%d, intermedio=%d, formato=%d)",
             i, p.filename.c_str(), outW, outH, p.scaleTypeX, p.scaleTypeY, viewportW, viewportH, isFinal ? 1 : 0, finalOffscreen ? 1 : 0, (int)outFmt);

        if (!toSwapchain) {
            const VkFormat fmt = outFmt;
            // RetroArch (shader_vulkan.c:4630): la salida de un pase tiene mipmaps si el pase SIGUIENTE pide mipmap_input
            // (el pool limita al máximo posible para el tamaño).
            uint32_t levels = (i + 1 < preset.passes.size() && preset.passes[i + 1].mipmapInput) ? 32u : 1u;
            set.passResources.push_back(m_fbPool->acquire(outW, outH, fmt, rp, levels));
        } else {
            set.passResources.push_back({});
        }

        for (auto& param : pipeline->getParameters()) set.mergedParameters.push_back(param);
        set.pipelines.push_back(std::move(pipeline));
        LoadProgress::done++;
        srcW = outW; srcH = outH;
    }
    // PassFeedbackN (T43): los pases cuya salida del frame anterior pide algún shader llevan un segundo framebuffer igual al suyo.
    // Como en RetroArch, solo los que tienen framebuffer propio (el último pase directo al swapchain no lo tiene).
    set.feedbackResources.assign(set.passResources.size(), PooledFramebuffer{});
    std::vector<bool> needFeedback(set.passResources.size(), false);
    for (auto& pl : set.pipelines)
        for (auto& rs : pl->getResolvedSamplers())
            if (rs.semantic == TextureSemantic::PassFeedback && rs.index >= 0 && (size_t)rs.index < needFeedback.size())
                needFeedback[rs.index] = true;
    for (size_t j = 0; j < needFeedback.size(); j++) {
        const PooledFramebuffer& cur = set.passResources[j];
        if (!needFeedback[j] || cur.image == VK_NULL_HANDLE) continue;
        set.feedbackResources[j] = m_fbPool->acquire(cur.width, cur.height, cur.format, cur.renderPass, cur.levels);
        LOGI("compilePipelineSet: feedback del pase %zu (%ux%u)", j, cur.width, cur.height);
    }
    return set;
}

bool VulkanRenderer::loadLUT
(const ShaderTexture& tex, const std::string& basePath,
                                std::map<std::string, VulkanTexture>& outMap,
                                std::vector<std::string>& outIds) {
    extern jobject fetchBitmapFromJava(const std::string& path);
    extern JavaVM* g_vm;

    std::string fullPath = tex.path;
    if (!basePath.empty() && fullPath.find("://") == std::string::npos) {
        fullPath = basePath + fullPath;
    }
    // Rutas de LUTs que suben por "../" (override de un preset de #reference en otra carpeta)
    if (fullPath.find("..") != std::string::npos) fullPath = SlangCompiler::resolvePath("", fullPath);

    // Si la LUT no se puede cargar se registra igualmente una textura 1x1 negra: así su sampler siempre queda enlazado
    // (antes se quedaba sin escribir) y <id>Size vale 1x1 en vez de no resolverse. El aviso sale una sola vez por carga.
    static const uint8_t kBlackPixel[4] = {0, 0, 0, 255};
    const uint8_t* srcPixels = nullptr;
    uint32_t width = 1, height = 1, stride = 4;
    bool locked = false;

    JNIEnv* env = nullptr;
    bool attached = false;
    jobject jbitmap = fetchBitmapFromJava(fullPath);
    if (jbitmap) {
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            g_vm->AttachCurrentThread(&env, nullptr); attached = true;
        }
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, jbitmap, &info) >= 0 && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            void* px = nullptr;
            if (AndroidBitmap_lockPixels(env, jbitmap, &px) >= 0 && px) {
                srcPixels = (const uint8_t*)px;
                width = info.width; height = info.height; stride = info.stride;
                locked = true;
            }
        }
    }
    const bool fallback = (srcPixels == nullptr);
    if (fallback) {
        srcPixels = kBlackPixel;
        LOGE("loadLUT: no se pudo cargar la LUT '%s' (%s): se usa una textura 1x1 negra", tex.id.c_str(), fullPath.c_str());
        LoadProgress::addStatus(LoadProgress::code("lutfail", {tex.id}));
    }

    // Crear recursos Vulkan
    VulkanTexture vtex;
    vtex.width = width;
    vtex.height = height;
    vtex.format = VK_FORMAT_R8G8B8A8_UNORM;

    // <id>_mipmap = true: cadena de mipmaps completa (se genera con blits tras subir el nivel 0)
    const uint32_t lutLevels = (tex.mipmap && !fallback) ? maxMipLevelsFor(width, height) : 1;
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0, VK_IMAGE_TYPE_2D, vtex.format, {width, height, 1}, lutLevels, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | (lutLevels > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0u),
        VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};
    vkCreateImage(m_device, &ii, nullptr, &vtex.image);

    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(m_device, vtex.image, &mr);

    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
    uint32_t mt = 0xFFFFFFFF;
    for(uint32_t i=0; i<mp.memoryTypeCount; i++) if(mr.memoryTypeBits&(1<<i)) { mt=i; break; }

    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
    vkAllocateMemory(m_device, &mai, nullptr, &vtex.memory);
    vkBindImageMemory(m_device, vtex.image, vtex.memory, 0);

    // Upload via staging buffer
    VkBuffer staging; VkDeviceMemory stagingMem;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, (VkDeviceSize)width*height*4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_SHARING_MODE_EXCLUSIVE};
    vkCreateBuffer(m_device, &bi, nullptr, &staging);
    VkMemoryRequirements smr; vkGetBufferMemoryRequirements(m_device, staging, &smr);
    uint32_t smt = 0xFFFFFFFF;
    for(uint32_t i=0; i<mp.memoryTypeCount; i++) if((smr.memoryTypeBits&(1<<i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) { smt=i; break; }
    VkMemoryAllocateInfo smai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, smr.size, smt};
    vkAllocateMemory(m_device, &smai, nullptr, &stagingMem);
    vkBindBufferMemory(m_device, staging, stagingMem, 0);

    void* data;
    vkMapMemory(m_device, stagingMem, 0, (VkDeviceSize)width*height*4, 0, &data);
    // Fila a fila: el bitmap puede tener un stride mayor que width*4 (alineación de filas)
    for (uint32_t y = 0; y < height; y++)
        memcpy((uint8_t*)data + (size_t)y * width * 4, srcPixels + (size_t)y * stride, (size_t)width * 4);
    vkUnmapMemory(m_device, stagingMem);

    if (jbitmap) {
        if (locked) AndroidBitmap_unlockPixels(env, jbitmap);
        env->DeleteGlobalRef(jbitmap); // jbitmap era un global ref devuelto por fetchBitmapFromJava
    }
    if (attached) g_vm->DetachCurrentThread();

    // Comandos de transferencia
    // Pool de comandos propio del hilo de compilación (un VkCommandPool no admite uso concurrente desde dos hilos)
    VkCommandBufferAllocateInfo cal = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, m_loadCmdPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
    VkCommandBuffer cb; vkAllocateCommandBuffers(m_device, &cal, &cb);
    VkCommandBufferBeginInfo cbi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vkBeginCommandBuffer(cb, &cbi);

    VkImageMemoryBarrier b1 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, vtex.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, lutLevels, 0, 1}};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b1);

    VkBufferImageCopy region = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {width, height, 1}};
    vkCmdCopyBufferToImage(cb, staging, vtex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    if (lutLevels > 1) {
        // Deja todos los niveles en SHADER_READ_ONLY_OPTIMAL
        recordGenerateMips(cb, vtex.image, width, height, lutLevels,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    } else {
        VkImageMemoryBarrier b2 = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, vtex.image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b2);
    }

    vkEndCommandBuffer(cb);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cb, 0, nullptr};
    // R21: se espera a una fence propia FUERA del mutex de la cola. Antes era vkQueueWaitIdle con el mutex cogido: el hilo de render se
    // quedaba parado en su vkQueueSubmit hasta que la GPU acababa todo lo pendiente, un tirón por cada LUT de los presets que las cargan.
    VkFence uploadFence = VK_NULL_HANDLE;
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateFence(m_device, &fci, nullptr, &uploadFence);
    VkResult upRes;
    {
        std::lock_guard<std::mutex> ql(m_queueMutex); // la cola se comparte con el hilo de render
        upRes = vkQueueSubmit(m_queue, 1, &si, uploadFence);
    }
    if (upRes != VK_SUCCESS || vkWaitForFences(m_device, 1, &uploadFence, VK_TRUE, 5000000000ull) != VK_SUCCESS) {
        LOGE("loadLUT: la subida de '%s' no terminó (%d); se espera a toda la GPU", tex.id.c_str(), (int)upRes);
        deviceWaitIdle();
    }
    vkDestroyFence(m_device, uploadFence, nullptr);
    vkFreeCommandBuffers(m_device, m_loadCmdPool, 1, &cb);
    vkDestroyBuffer(m_device, staging, nullptr);
    vkFreeMemory(m_device, stagingMem, nullptr);

    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, vtex.image, VK_IMAGE_VIEW_TYPE_2D, vtex.format, {VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY}, {VK_IMAGE_ASPECT_COLOR_BIT, 0, lutLevels, 0, 1}};
    vkCreateImageView(m_device, &vi, nullptr, &vtex.view);
    LOGI("loadLUT: '%s' %ux%u mips=%u", tex.id.c_str(), width, height, lutLevels);

    outMap[tex.id] = vtex;
    outIds.push_back(tex.id);
    return true;
}

void VulkanRenderer::cleanupLUTs() {
    deviceWaitIdle();
    for (auto& pair : m_lutTextures) {
        if (pair.second.view) vkDestroyImageView(m_device, pair.second.view, nullptr);
        if (pair.second.image) vkDestroyImage(m_device, pair.second.image, nullptr);
        if (pair.second.memory) vkFreeMemory(m_device, pair.second.memory, nullptr);
    }
    m_lutTextures.clear();
    m_lutIds.clear();
}

void VulkanRenderer::cleanupPipelines() {
    drainFrames(); // T51: ningún frame en vuelo puede estar usando los pipelines que se van a destruir
    for (auto& fb : m_passResources) {
        if (fb.image != VK_NULL_HANDLE) {
            m_fbPool->release(fb);
        }
    }
    for (auto& fb : m_feedbackResources) if (fb.image != VK_NULL_HANDLE) m_fbPool->release(fb);
    m_feedbackResources.clear();
    m_passRenderPasses.clear();
    m_finalOffscreen = false;
    m_passResources.clear();
    for (auto& h : m_historyChain) m_fbPool->release(h);
    m_historyChain.clear();
    m_historyNeedsInit = true;
    m_pipelines.clear();
}
bool VulkanRenderer::ensureImportPipeline(const VkAndroidHardwareBufferFormatPropertiesANDROID& fp) { return false; }
void VulkanRenderer::recordImportPass(VkCommandBuffer cmd, uint32_t w, uint32_t h) {}
