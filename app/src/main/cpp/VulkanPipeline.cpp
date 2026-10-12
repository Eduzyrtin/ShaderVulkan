#include "DebugLog.hpp"
#include "VulkanPipeline.hpp"
#include "SlangCompiler.hpp"
#include <android/log.h>
#include <regex>
#include <sstream>
#include <algorithm>

#define LOG_TAG "VulkanPipeline"
#define LOGI(...) SV_DIAG(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) SV_DIAG(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

VulkanPipeline::VulkanPipeline(VkDevice device, AAssetManager* assetManager, VkPhysicalDeviceMemoryProperties memProps)
    : m_device(device), m_assetManager(assetManager), m_memProps(memProps) {}

bool VulkanPipeline::init(VkRenderPass renderPass,
                          const std::string& vertPath, const std::string& fragPath,
                          const std::string& basePath,
                          const std::map<std::string, TextureSemanticAlias>& aliasMap,
                          uint32_t framesInFlight, int passBase, VkPipelineCache pipelineCache) {
    m_passBase = passBase;
    VkShaderModule vertModule = VK_NULL_HANDLE;
    VkShaderModule fragModule = VK_NULL_HANDLE;
    std::string vSource, fSource;
    std::vector<std::string> textureIds; // vacío en fase 1

    LOGI("VulkanPipeline::init: %s", fragPath.c_str());

    std::string vSpvPath = vertPath + ".spv";
    std::string fSpvPath = fragPath + ".spv";
    std::string vSpv = loadAsset(vSpvPath);
    std::string fSpv = loadAsset(fSpvPath);

    if (vSpv.empty() || fSpv.empty()) {
        if (!basePath.empty()) {
            vSpvPath = basePath + vertPath + ".spv";
            fSpvPath = basePath + fragPath + ".spv";
            vSpv = loadAsset(vSpvPath);
            fSpv = loadAsset(fSpvPath);
        }
    }

    auto applyReflection = [&](const std::vector<uint32_t>& code, bool isVert) {
        SlangReflection refl = SlangCompiler::reflect(code);
        if (isVert) {
            m_vertUbos = refl.ubos;
            m_vertPush = refl.pushConstant;
            m_vertSemantics.clear();
            for(auto const& [b, ubo] : m_vertUbos) cacheSemantics(ubo, m_vertSemantics, false);
            if (m_vertPush.present) cacheSemantics(m_vertPush, m_vertSemantics, true);
        } else {
            m_fragUbos = refl.ubos;
            m_fragPush = refl.pushConstant;
            m_fragSamplers = refl.samplers;
            m_fragSemantics.clear();
            for(auto const& [b, ubo] : m_fragUbos) cacheSemantics(ubo, m_fragSemantics, false);
            if (m_fragPush.present) cacheSemantics(m_fragPush, m_fragSemantics, true);
        }
    };

    if (!vSpv.empty() && !fSpv.empty()) {
        std::vector<uint32_t> vCode(vSpv.size()/4); memcpy(vCode.data(), vSpv.data(), vSpv.size());
        std::vector<uint32_t> fCode(fSpv.size()/4); memcpy(fCode.data(), fSpv.data(), fSpv.size());
        applyReflection(vCode, true);
        applyReflection(fCode, false);
        vertModule = createShaderModule(vCode);
        fragModule = createShaderModule(fCode);
    } else {
        std::string usedVertPath = vertPath + ".slang";
        std::string usedFragPath = fragPath + ".slang";
        vSource = SlangCompiler::fullPreprocess(usedVertPath, m_assetManager);
        fSource = SlangCompiler::fullPreprocess(usedFragPath, m_assetManager);

        if ((vSource.empty() || fSource.empty()) && !basePath.empty()) {
            usedVertPath = basePath + vertPath + ".slang";
            usedFragPath = basePath + fragPath + ".slang";
            vSource = SlangCompiler::fullPreprocess(usedVertPath, m_assetManager);
            fSource = SlangCompiler::fullPreprocess(usedFragPath, m_assetManager);
        }

        if (!vSource.empty() && !fSource.empty()) {
            auto vCode = SlangCompiler::compileShader(vSource, usedVertPath, STAGE_VERTEX, textureIds, &m_parameters);
            auto fCode = SlangCompiler::compileShader(fSource, usedFragPath, STAGE_FRAGMENT, textureIds, &m_parameters);
            if (!vCode.empty() && !fCode.empty()) {
                applyReflection(vCode, true);
                applyReflection(fCode, false);
                vertModule = createShaderModule(vCode);
                fragModule = createShaderModule(fCode);
                LOGI("PIPELINE-PATH: %s -> REAL (compileShader OK)", fragPath.c_str());
            }
        }
    }

    // Sin fallback: un pase que no se encuentra o no compila deja el pipeline inválido (isValid() == false). El renderer
    // enseña entonces la captura sin procesar y la UI avisa de qué pase falló (T50).
    if (!vertModule || !fragModule) {
        if (vertModule) vkDestroyShaderModule(m_device, vertModule, nullptr);
        if (fragModule) vkDestroyShaderModule(m_device, fragModule, nullptr);
        LOGE("PIPELINE-PATH: %s -> FAILED (no se encontró o no compiló)", fragPath.c_str());
        return false;
    }

    // R02: una sola lista para el render de cada frame (antes se copiaban y juntaban las dos en cada pase y frame)
    m_semantics = m_vertSemantics;
    m_semantics.insert(m_semantics.end(), m_fragSemantics.begin(), m_fragSemantics.end());

    if (!buildDescriptorAndPipelineLayout(aliasMap, framesInFlight)) {
        LOGE("buildDescriptorAndPipelineLayout falló para %s", fragPath.c_str());
        vkDestroyShaderModule(m_device, vertModule, nullptr);
        vkDestroyShaderModule(m_device, fragModule, nullptr);
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, vertModule, "main", nullptr},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fragModule, "main", nullptr}
    };

    VkVertexInputBindingDescription vbd = {0, 16, VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[2] = {{0, 0, VK_FORMAT_R32G32_SFLOAT, 0}, {1, 0, VK_FORMAT_R32G32_SFLOAT, 8}};
    VkPipelineVertexInputStateCreateInfo vInput = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO, nullptr, 0, 1, &vbd, 2, attrs};
    VkPipelineInputAssemblyStateCreateInfo iAsm = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, nullptr, 0, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, VK_FALSE};
    VkPipelineViewportStateCreateInfo vpt = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, nullptr, 0, 1, nullptr, 1, nullptr};
    VkPipelineRasterizationStateCreateInfo rast = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, nullptr, 0, VK_FALSE, VK_FALSE, VK_POLYGON_MODE_FILL, VK_CULL_MODE_NONE, VK_FRONT_FACE_COUNTER_CLOCKWISE, VK_FALSE, 0, 0, 0, 1.0f};
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, nullptr, 0, VK_SAMPLE_COUNT_1_BIT, VK_FALSE, 0, nullptr, VK_FALSE, VK_FALSE};
    VkPipelineColorBlendAttachmentState att = {VK_FALSE, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, 0xf};
    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, nullptr, 0, VK_FALSE, VK_LOGIC_OP_COPY, 1, &att};
    VkDynamicState dyns[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyn = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, nullptr, 0, 2, dyns};

    VkGraphicsPipelineCreateInfo pInfo = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, nullptr, 0, 2, stages, &vInput, &iAsm, nullptr, &vpt, &rast, &ms, nullptr, &cb, &dyn, m_pipelineLayout, renderPass, 0};
    // R07: con la caché de pipelines del driver, un pase ya compilado en otra sesión no vuelve a pasar por el compilador de la GPU
    if (vkCreateGraphicsPipelines(m_device, pipelineCache, 1, &pInfo, nullptr, &m_pipeline) != VK_SUCCESS) {
        vkDestroyShaderModule(m_device, vertModule, nullptr);
        vkDestroyShaderModule(m_device, fragModule, nullptr);
        return false;
    }

    vkDestroyShaderModule(m_device, vertModule, nullptr);
    vkDestroyShaderModule(m_device, fragModule, nullptr);
    return true;
}

void VulkanPipeline::bind(VkCommandBuffer cmd) {
    if (m_pipeline) vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, m_pipeline);
}

void VulkanPipeline::release() {
    if (m_pipeline) { vkDestroyPipeline(m_device, m_pipeline, nullptr); m_pipeline = VK_NULL_HANDLE; }
    if (m_descriptorPool) { vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr); m_descriptorPool = VK_NULL_HANDLE; }
    if (m_pipelineLayout) { vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr); m_pipelineLayout = VK_NULL_HANDLE; }
    if (m_descriptorSetLayout) { vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr); m_descriptorSetLayout = VK_NULL_HANDLE; }
    if (m_uboMapped) { vkUnmapMemory(m_device, m_uboMemory); m_uboMapped = nullptr; }
    if (m_uboBuffer) { vkDestroyBuffer(m_device, m_uboBuffer, nullptr); m_uboBuffer = VK_NULL_HANDLE; }
    if (m_uboMemory) { vkFreeMemory(m_device, m_uboMemory, nullptr); m_uboMemory = VK_NULL_HANDLE; }
}

void VulkanPipeline::cacheSemantics(const ReflectedBlock& block, std::vector<SemanticBinding>& outCache, bool isPush) {
    for (auto const& [name, field] : block.fields) {
        if (field.semantic != SlangSemantic::Unknown) {
            outCache.push_back({field.semantic, field.offset, field.size, (field.semantic == SlangSemantic::PassOutputSize && field.index >= 0) ? field.index + m_passBase : field.index, isPush, name});
        }
    }
}

bool VulkanPipeline::buildDescriptorAndPipelineLayout(
    const std::map<std::string, TextureSemanticAlias>& aliasMap, uint32_t framesInFlight) {

    std::vector<VkDescriptorSetLayoutBinding> bindings;

    // --- UBO: en este motor solo aparece en frag o en ambos con el mismo binding (RetroArch exige esto). ---
    bool hasUbo = !m_vertUbos.empty() || !m_fragUbos.empty();
    if (hasUbo) {
        uint32_t binding = !m_fragUbos.empty() ? m_fragUbos.begin()->first : m_vertUbos.begin()->first;
        VkShaderStageFlags stages = 0;
        if (m_vertUbos.count(binding)) stages |= VK_SHADER_STAGE_VERTEX_BIT;
        if (m_fragUbos.count(binding)) stages |= VK_SHADER_STAGE_FRAGMENT_BIT;
        m_uboBinding = binding;
        m_uboSize = std::max(
            m_vertUbos.count(binding) ? m_vertUbos[binding].size : 0,
            m_fragUbos.count(binding) ? m_fragUbos[binding].size : 0);
        bindings.push_back({binding, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, stages, nullptr});
    }

    // --- Samplers: resolver semántica de cada uno (solo frag, como en la mayoría de shaders slang). ---
    m_resolvedSamplers.clear();
    for (auto& s : m_fragSamplers) {
        int idx = 0;
        TextureSemantic sem = resolveTextureSemantic(s.name, idx, aliasMap, m_passBase);
        if (sem == TextureSemantic::Invalid) {
            SV_DIAG(ANDROID_LOG_WARN, "VulkanPipeline",
                "Sampler '%s' no resuelve a ninguna semántica conocida, se ignora.", s.name.c_str());
            continue;
        }
        m_resolvedSamplers.push_back({s.binding, sem, idx, s.name});
        bindings.push_back({s.binding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr});
    }

    VkDescriptorSetLayoutCreateInfo dsl = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr, 0,
        (uint32_t)bindings.size(), bindings.data()};
    if (vkCreateDescriptorSetLayout(m_device, &dsl, nullptr, &m_descriptorSetLayout) != VK_SUCCESS) return false;

    // --- Push constant: máximo tamaño entre vert/frag, stages combinados. ---
    m_pushRange = {0, 0, 0};
    if (m_vertPush.present || m_fragPush.present) {
        VkShaderStageFlags stages = 0;
        if (m_vertPush.present) stages |= VK_SHADER_STAGE_VERTEX_BIT;
        if (m_fragPush.present) stages |= VK_SHADER_STAGE_FRAGMENT_BIT;
        size_t size = std::max(m_vertPush.size, m_fragPush.size);
        m_pushRange = {stages, 0, (uint32_t)size};
    }

    VkPipelineLayoutCreateInfo pli = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0,
        1, &m_descriptorSetLayout,
        m_pushRange.size ? 1u : 0u, m_pushRange.size ? &m_pushRange : nullptr};
    if (vkCreatePipelineLayout(m_device, &pli, nullptr, &m_pipelineLayout) != VK_SUCCESS) return false;

    // --- Pool + sets, uno por frame-in-flight (igual que pass->sets[num_sync_indices] en RetroArch). ---
    std::vector<VkDescriptorPoolSize> poolSizes;
    if (hasUbo) poolSizes.push_back({VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, framesInFlight});
    if (!m_resolvedSamplers.empty())
        poolSizes.push_back({VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, (uint32_t)(m_resolvedSamplers.size() * framesInFlight)});

    VkDescriptorPoolCreateInfo dpi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0,
        framesInFlight, (uint32_t)poolSizes.size(), poolSizes.data()};
    if (vkCreateDescriptorPool(m_device, &dpi, nullptr, &m_descriptorPool) != VK_SUCCESS) return false;

    m_descriptorSets.resize(framesInFlight);
    std::vector<VkDescriptorSetLayout> layouts(framesInFlight, m_descriptorSetLayout);
    VkDescriptorSetAllocateInfo dsai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr,
        m_descriptorPool, framesInFlight, layouts.data()};
    if (vkAllocateDescriptorSets(m_device, &dsai, m_descriptorSets.data()) != VK_SUCCESS) return false;

    if (hasUbo) {
        // El offset de cada descriptor debe ser múltiplo de minUniformBufferOffsetAlignment (la spec lo limita a ≤256).
        // Con un tamaño sin alinear, el slot del 2.º frame-in-flight se leía con un offset inválido (UBO basura -> pase negro).
        constexpr size_t kUboAlign = 256;
        m_uboStride = (m_uboSize + kUboAlign - 1) / kUboAlign * kUboAlign;
        VkDeviceSize totalSize = m_uboStride * framesInFlight;
        VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, totalSize,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr};
        if (vkCreateBuffer(m_device, &bi, nullptr, &m_uboBuffer) != VK_SUCCESS) return false;

        VkMemoryRequirements mr; vkGetBufferMemoryRequirements(m_device, m_uboBuffer, &mr);
        VkPhysicalDeviceMemoryProperties mp = m_memProps;
        uint32_t mt = 0xFFFFFFFF;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((mr.memoryTypeBits & (1 << i)) &&
                (mp.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                    == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { mt = i; break; }

        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, mt};
        if (vkAllocateMemory(m_device, &mai, nullptr, &m_uboMemory) != VK_SUCCESS) return false;
        vkBindBufferMemory(m_device, m_uboBuffer, m_uboMemory, 0);
        vkMapMemory(m_device, m_uboMemory, 0, totalSize, 0, (void**)&m_uboMapped);

        // Escribir el binding del UBO en cada descriptor set (estático: mismo buffer, offset fijo por frame).
        for (uint32_t f = 0; f < framesInFlight; f++) {
            VkDescriptorBufferInfo bufInfo = {m_uboBuffer, f * m_uboStride, m_uboSize};
            VkWriteDescriptorSet w = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, m_descriptorSets[f],
                m_uboBinding, 0, 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &bufInfo, nullptr};
            vkUpdateDescriptorSets(m_device, 1, &w, 0, nullptr);
        }
    }

    if (m_pushRange.size > 0) m_pushScratch.resize(m_pushRange.size, 0);

    return true;
}

std::string VulkanPipeline::loadAsset(const std::string& path) {
    extern std::string fetchFromJava(const std::string& path);
    if (path.find("content://") == 0) return fetchFromJava(path);
    if (m_assetManager == nullptr) return "";
    AAsset* a = AAssetManager_open(m_assetManager, path.c_str(), AASSET_MODE_BUFFER);
    if (!a) return "";
    off_t l = AAsset_getLength(a);
    if (l <= 0) { AAsset_close(a); return ""; }
    std::string c(l, '\0');
    AAsset_read(a, &c[0], l);
    AAsset_close(a);
    return c;
}

VkShaderModule VulkanPipeline::createShaderModule(const std::vector<uint32_t>& code) {
    VkShaderModuleCreateInfo i = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0, code.size()*4, code.data()};
    VkShaderModule m;
    if (vkCreateShaderModule(m_device, &i, nullptr, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}
