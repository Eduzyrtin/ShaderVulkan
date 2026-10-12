#pragma once
#include <vulkan/vulkan.h>
#include <vector>
#include <map>
#include <mutex>

struct PooledFramebuffer {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;       // vista para muestrear (todos los niveles de mip)
    VkImageView attachView = VK_NULL_HANDLE; // vista del nivel 0 para el framebuffer (== view si levels == 1)
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t levels = 1;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkRenderPass renderPass = VK_NULL_HANDLE; // Añadido
};

class FramebufferPool {
public:
    FramebufferPool(VkDevice device, VkPhysicalDevice physDev);
    ~FramebufferPool();

    // levels > 1 reserva cadena de mipmaps (se limita al máximo posible para el tamaño)
    PooledFramebuffer acquire(uint32_t width, uint32_t height, VkFormat format, VkRenderPass renderPass, uint32_t levels = 1);
    void release(PooledFramebuffer fb);
    void cleanup();
    // R10: destruye los recursos libres (no los que están en uso). Quien llama garantiza que la GPU ya no usa ninguno de los libres.
    void trim() { cleanup(); }

private:
    VkDevice m_device;
    VkPhysicalDevice m_physDev;
    std::mutex m_mutex;

    struct PoolKey {
        uint32_t w, h;
        VkFormat f;
        VkRenderPass rp;
        uint32_t levels;
        bool operator<(const PoolKey& o) const {
            if (w != o.w) return w < o.w;
            if (h != o.h) return h < o.h;
            if (f != o.f) return f < o.f;
            if (levels != o.levels) return levels < o.levels;
            return rp < o.rp;
        }
    };

    std::map<PoolKey, std::vector<PooledFramebuffer>> m_available;
};
