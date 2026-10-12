#include "DebugLog.hpp"
#include "FramebufferPool.hpp"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "FramebufferPool"
#define LOGD(...) SV_DIAG(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

FramebufferPool::FramebufferPool(VkDevice device, VkPhysicalDevice physDev)
    : m_device(device), m_physDev(physDev) {}

FramebufferPool::~FramebufferPool() {
    cleanup();
}

static uint32_t maxMipLevels(uint32_t w, uint32_t h) {
    uint32_t m = std::max(w, h), n = 1;
    while (m > 1) { m >>= 1; n++; }
    return n;
}

PooledFramebuffer FramebufferPool::acquire(uint32_t width, uint32_t height, VkFormat format, VkRenderPass renderPass, uint32_t levels) {
    // DEBUG DIAGNOSTICO - SEGURO DE VIDA - QUITAR DESPUÉS
    if (width > 8192 || height > 8192) {
        LOGE("FramebufferPool: Intento de crear textura GIGANTE (%ux%u). Limitando a 1x1 para evitar CRASH.", width, height);
        width = 1; height = 1;
    }
    if (width == 0) width = 1;
    if (height == 0) height = 1;
    levels = std::max(1u, std::min(levels, maxMipLevels(width, height)));

    std::lock_guard<std::mutex> lock(m_mutex);
    PoolKey key = {width, height, format, renderPass, levels};

    if (!m_available[key].empty()) {
        PooledFramebuffer fb = m_available[key].back();
        m_available[key].pop_back();
        LOGD("FramebufferPool: Reutilizando recurso %ux%u (f=%d, mips=%u)", width, height, (int)format, levels);
        return fb;
    }

    // Create new
    PooledFramebuffer fb = {};
    fb.width = width;
    fb.height = height;
    fb.levels = levels;
    fb.format = format;
    fb.renderPass = renderPass;

    // TRANSFER_SRC/DST: generación de mipmaps (blit), copia al historial y sondeo de píxeles de diagnóstico
    VkImageCreateInfo ii = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0, VK_IMAGE_TYPE_2D,
        format, {width, height, 1}, levels, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};
    vkCreateImage(m_device, &ii, nullptr, &fb.image);

    VkMemoryRequirements mr; vkGetImageMemoryRequirements(m_device, fb.image, &mr);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(m_physDev, &mp);
    uint32_t mt = 0xFFFFFFFF;
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((mr.memoryTypeBits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { mt = i; break; }

    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
    vkAllocateMemory(m_device, &mai, nullptr, &fb.memory);
    vkBindImageMemory(m_device, fb.image, fb.memory, 0);

    VkComponentMapping ident = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
    // Vista de muestreo: todos los niveles
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, fb.image, VK_IMAGE_VIEW_TYPE_2D, format,
        ident, {VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1}};
    vkCreateImageView(m_device, &vi, nullptr, &fb.view);

    if (levels > 1) {
        // El framebuffer exige una vista de un solo nivel (nivel 0)
        VkImageViewCreateInfo av = vi;
        av.subresourceRange.levelCount = 1;
        vkCreateImageView(m_device, &av, nullptr, &fb.attachView);
    } else {
        fb.attachView = fb.view;
    }

    VkFramebufferCreateInfo fi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, nullptr, 0, renderPass, 1, &fb.attachView, width, height, 1};
    vkCreateFramebuffer(m_device, &fi, nullptr, &fb.framebuffer);

    LOGI("FramebufferPool: Creado nuevo recurso %ux%u (mips=%u)", width, height, levels);
    return fb;
}

void FramebufferPool::release(PooledFramebuffer fb) {
    if (fb.image == VK_NULL_HANDLE) return;
    std::lock_guard<std::mutex> lock(m_mutex);
    LOGD("FramebufferPool: Liberando recurso %ux%u al pool", fb.width, fb.height);
    PoolKey key = {fb.width, fb.height, fb.format, fb.renderPass, fb.levels};
    m_available[key].push_back(fb);
}

void FramebufferPool::cleanup() {
    std::lock_guard<std::mutex> lock(m_mutex);
    LOGI("FramebufferPool: Limpiando %zu grupos de recursos", m_available.size());
    for (auto& pair : m_available) {
        for (auto& fb : pair.second) {
            vkDestroyFramebuffer(m_device, fb.framebuffer, nullptr);
            if (fb.attachView && fb.attachView != fb.view) vkDestroyImageView(m_device, fb.attachView, nullptr);
            vkDestroyImageView(m_device, fb.view, nullptr);
            vkDestroyImage(m_device, fb.image, nullptr);
            vkFreeMemory(m_device, fb.memory, nullptr);
        }
    }
    m_available.clear();
}
