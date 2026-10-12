#pragma once
#include <vulkan/vulkan.h>
#include <vector>
#include <string>
#include <map>
#include <android/asset_manager.h>
#include "SlangParser.hpp"
#include "SlangCompiler.hpp"
#include "SlangSemantics.hpp"

// R02: qué representa un binding ya resuelto (lo rellena VulkanRenderer::resolveSetBindings al adoptar el set)
enum class BindingRef : uint8_t {
    None,      // sin resolver o no hace falta
    Param,     // valor del parámetro 'ref' (índice en los parámetros del preset)
    ByName,    // nombre que no es un parámetro declarado: se busca por nombre (caso raro, como antes)
    PassSize,  // tamaño de la salida del pase 'ref' ("<alias>Size")
    LutSize,   // tamaño de una LUT ("<id>Size"): refW x refH
    Original   // no se pudo resolver: tamaño de Original (nunca 0, que daría inf/NaN)
};

struct SemanticBinding {
    SlangSemantic semantic;
    size_t offset;
    size_t size;
    int index;
    bool isPushConstant; // NUEVO: distingue destino UBO vs push constant buffer
    std::string name;
    // R02: resuelto al adoptar el set, para no buscar por nombre en cada frame (vale mientras viva el set)
    BindingRef ref = BindingRef::None;
    int refIndex = -1;
    uint32_t refW = 0, refH = 0;
    bool glassGain = false; // es GLASS_GAIN (T33: con valor <= 0 se escribe la ganancia calibrada)
};

struct ResolvedSampler {
    uint32_t binding;
    TextureSemantic semantic;
    int index;
    std::string name;
    // R02: resuelto al adoptar el set
    VkSampler sampler = VK_NULL_HANDLE;   // filtro y wrap según el origen de la textura (T42) o los propios de la LUT
    VkImageView lutView = VK_NULL_HANDLE; // User: la vista de la LUT
};

class VulkanPipeline {
public:
    VulkanPipeline(VkDevice device, AAssetManager* assetManager, VkPhysicalDeviceMemoryProperties memProps);
    ~VulkanPipeline() { release(); }

    uint8_t* getUboWritePtr(uint32_t frameIndex) {
        return m_uboMapped ? m_uboMapped + (frameIndex * m_uboStride) : nullptr;
    }
    uint8_t* getPushConstantScratch() { return m_pushScratch.empty() ? nullptr : m_pushScratch.data(); }

    // 'aliasMap' viene de VulkanRenderer (construido una vez por preset, ver punto 5).
    // 'framesInFlight' = VulkanRenderer::FRAMES_IN_FLIGHT (2).
    // 'pipelineCache' (R07): la caché de pipelines del driver compartida por todo el motor (VK_NULL_HANDLE = sin caché).
    bool init(VkRenderPass renderPass,
              const std::string& vertPath, const std::string& fragPath,
              const std::string& basePath,
              const std::map<std::string, TextureSemanticAlias>& aliasMap,
              uint32_t framesInFlight, int passBase = 0, VkPipelineCache pipelineCache = VK_NULL_HANDLE);

    void bind(VkCommandBuffer cmd);
    bool isValid() const { return m_pipeline != VK_NULL_HANDLE; }
    void release();

    const std::vector<ShaderParameter>& getParameters() const { return m_parameters; }
    const std::vector<SemanticBinding>& getVertSemantics() const { return m_vertSemantics; }
    const std::vector<SemanticBinding>& getFragSemantics() const { return m_fragSemantics; }
    // R02: las de los dos stages juntas (vertex y luego fragment, el orden de escritura de siempre). Se escriben en cada frame.
    const std::vector<SemanticBinding>& getSemantics() const { return m_semantics; }
    std::vector<SemanticBinding>& semantics() { return m_semantics; }
    const std::vector<ResolvedSampler>& getResolvedSamplers() const { return m_resolvedSamplers; }
    std::vector<ResolvedSampler>& resolvedSamplers() { return m_resolvedSamplers; }

    VkPipelineLayout getPipelineLayout() const { return m_pipelineLayout; }
    VkDescriptorSet getDescriptorSet(uint32_t frameIndex) const { return m_descriptorSets[frameIndex]; }
    bool hasUbo() const { return m_vertUbos.size() || m_fragUbos.size(); }
    uint32_t getUboBinding() const { return m_uboBinding; }
    size_t getUboSize() const { return m_uboSize; }
    bool hasPushConstant() const { return m_pushRange.size > 0; }
    const VkPushConstantRange& getPushConstantRange() const { return m_pushRange; }

private:
    VkShaderModule createShaderModule(const std::vector<uint32_t>& code);
    std::string loadAsset(const std::string& path);
    void cacheSemantics(const ReflectedBlock& block, std::vector<SemanticBinding>& outCache, bool isPush);
    bool buildDescriptorAndPipelineLayout(const std::map<std::string, TextureSemanticAlias>& aliasMap,
                                           uint32_t framesInFlight);

    VkDevice m_device;
    AAssetManager* m_assetManager;
    VkPipeline m_pipeline = VK_NULL_HANDLE;
    std::vector<ShaderParameter> m_parameters;

    std::map<uint32_t, ReflectedBlock> m_vertUbos, m_fragUbos;
    ReflectedBlock m_vertPush, m_fragPush;
    std::vector<SemanticBinding> m_vertSemantics, m_fragSemantics;
    std::vector<SemanticBinding> m_semantics; // R02: m_vertSemantics + m_fragSemantics
    std::vector<ReflectedSampler> m_fragSamplers;
    std::vector<ResolvedSampler> m_resolvedSamplers;
    int m_passBase = 0; // T45: desplazamiento de los PassOutputN escritos con número (pase de un preset combinado)

    // Propios de este pase (antes vivían en VulkanRenderer, ahora por-pase como en RetroArch).
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> m_descriptorSets; // uno por frame-in-flight
    uint32_t m_uboBinding = 0;
    size_t m_uboSize = 0;
    size_t m_uboStride = 0; // m_uboSize redondeado a la alineación de offsets de UBO (separación entre frames-in-flight)
    VkPushConstantRange m_pushRange = {0, 0, 0};

    VkPhysicalDeviceMemoryProperties m_memProps;
    VkBuffer m_uboBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_uboMemory = VK_NULL_HANDLE;
    uint8_t* m_uboMapped = nullptr;
    std::vector<uint8_t> m_pushScratch; // staging CPU para push constants
};
