#include "pyrowave_vulkan_decoder.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "pyrowave_api.h"
#include "pyrowave_hdr_metadata.h"
#include "pyrowave_dynamic_hdr.hpp"
#include "moonlight-common-c/src/DynamicHdr.h"

#include "pyrowave_yuv_to_rgba.comp.spv.h"
#include "pyrowave_yuv_to_rgb10a2.comp.spv.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <utility>
#include <vector>
#include <sys/system_properties.h>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

// ==== 临时诊断（2026-10-11）====================================================
// 目的：定位 Turnip 在 PyroWave 路径上的确定性崩溃（kgsl_syncobj_merge 空指针，
// fault addr 0x1b0，首帧成功、第二轮必崩）究竟发生在哪一次 Vulkan 调用上。
// 在每步调用前打标记，崩溃时 logcat 里最后一条 PWVTRACE 就是凶手指认。
// 用 ERROR 级别 + 独立 tag 便于过滤；打满 128 行后自动静默，不影响长时间运行。
static int g_pwv_trace_lines = 0;
#define PWV_TRACE(...)                                                                 \
  do {                                                                                 \
    if (g_pwv_trace_lines < 128) {                                                      \
      ++g_pwv_trace_lines;                                                              \
      __android_log_print(ANDROID_LOG_ERROR, "PWVTRACE", __VA_ARGS__);                  \
    }                                                                                   \
  } while (0)

namespace FramegenPipeline {
namespace {

constexpr std::size_t kSsHdrMetadataSize = 26;
constexpr std::uint32_t kMaxWidth = 8192;
constexpr std::uint32_t kMaxHeight = 8192;
constexpr std::uint64_t kMaxPixels = 8192ull * 8192ull;
// A frame-level wait must be recoverable if a vendor driver loses the queue or
// PyroWave fails to signal the hand-off semaphore. Teardown still performs the
// device-level wait needed to destroy Vulkan resources safely.
constexpr std::uint64_t kGpuWaitTimeoutNs = 5'000'000'000ULL;

bool valid_dimensions(int width, int height) {
    return width > 0 && height > 0 && (width & 1) == 0 && (height & 1) == 0 &&
           static_cast<std::uint32_t>(width) <= kMaxWidth &&
           static_cast<std::uint32_t>(height) <= kMaxHeight &&
           static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) <= kMaxPixels;
}

template<typename T>
void destroy_handle(VkDevice device, T &handle, void (*destroy)(VkDevice, T, const VkAllocationCallbacks*)) {
    if (device != VK_NULL_HANDLE && handle != VK_NULL_HANDLE) {
        destroy(device, handle, nullptr);
        handle = VK_NULL_HANDLE;
    }
}

struct api_t {
    void *library{nullptr};
    using get_version_fn = void (*)(std::uint32_t *, std::uint32_t *, std::uint32_t *);
    using create_device_fn = pyrowave_result (*)(const pyrowave_device_create_info *, pyrowave_device *);
    using create_default_device_fn = pyrowave_result (*)(pyrowave_device *);
    using destroy_device_fn = void (*)(pyrowave_device);
    using prefers_fragment_path_fn = bool (*)(pyrowave_device);
    using set_queue_type_fn = pyrowave_result (*)(pyrowave_device, VkQueueFlagBits);
    using create_decoder_fn = pyrowave_result (*)(const pyrowave_decoder_create_info *, pyrowave_decoder *);
    using clear_decoder_fn = void (*)(pyrowave_decoder);
    using destroy_decoder_fn = void (*)(pyrowave_decoder);
    using push_fn = pyrowave_result (*)(pyrowave_decoder, const void *, size_t);
    using ready_fn = bool (*)(pyrowave_decoder, bool);
    using decode_gpu_fn = pyrowave_result (*)(pyrowave_decoder,
                                              const pyrowave_gpu_sync_operation *,
                                              const pyrowave_gpu_sync_operation *,
                                              const pyrowave_gpu_buffers *);
    using get_color_metadata_fn = bool (*)(pyrowave_decoder, pyrowave_color_metadata *);

    get_version_fn get_version{nullptr};
    create_device_fn create_device{nullptr};
    create_default_device_fn create_default_device{nullptr};
    destroy_device_fn destroy_device{nullptr};
    prefers_fragment_path_fn prefers_fragment_path{nullptr};
    set_queue_type_fn set_queue_type{nullptr};
    create_decoder_fn create_decoder{nullptr};
    clear_decoder_fn clear_decoder{nullptr};
    destroy_decoder_fn destroy_decoder{nullptr};
    push_fn push{nullptr};
    ready_fn ready{nullptr};
    decode_gpu_fn decode_gpu{nullptr};
    get_color_metadata_fn get_color_metadata{nullptr};

    template<typename T>
    T symbol(const char *name) const {
        return reinterpret_cast<T>(dlsym(library, name));
    }

    bool load() {
        if (library != nullptr) return true;
        library = dlopen("libpyrowave-shared.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) return false;
        get_version = symbol<get_version_fn>("pyrowave_get_api_version");
        create_device = symbol<create_device_fn>("pyrowave_create_device");
        create_default_device = symbol<create_default_device_fn>("pyrowave_create_default_device");
        destroy_device = symbol<destroy_device_fn>("pyrowave_device_destroy");
        prefers_fragment_path = symbol<prefers_fragment_path_fn>("pyrowave_decoder_device_prefers_fragment_path");
        set_queue_type = symbol<set_queue_type_fn>("pyrowave_device_set_queue_type");
        create_decoder = symbol<create_decoder_fn>("pyrowave_decoder_create");
        clear_decoder = symbol<clear_decoder_fn>("pyrowave_decoder_clear");
        destroy_decoder = symbol<destroy_decoder_fn>("pyrowave_decoder_destroy");
        push = symbol<push_fn>("pyrowave_decoder_push_packet");
        ready = symbol<ready_fn>("pyrowave_decoder_decode_is_ready");
        decode_gpu = symbol<decode_gpu_fn>("pyrowave_decoder_decode_gpu_buffer");
        get_color_metadata = symbol<get_color_metadata_fn>("pyrowave_decoder_get_color_metadata");

        std::uint32_t major = 0, minor = 0, patch = 0;
        if (get_version != nullptr) get_version(&major, &minor, &patch);
        const bool valid = get_version != nullptr && major == PYROWAVE_API_VERSION_MAJOR &&
                           minor == PYROWAVE_API_VERSION_MINOR && patch == PYROWAVE_API_VERSION_PATCH &&
                           create_device != nullptr && create_default_device != nullptr && destroy_device != nullptr &&
                           prefers_fragment_path != nullptr && set_queue_type != nullptr &&
                           create_decoder != nullptr && clear_decoder != nullptr && destroy_decoder != nullptr &&
                           push != nullptr && ready != nullptr && decode_gpu != nullptr &&
                           get_color_metadata != nullptr;
        if (!valid) {
            LOGW("PyroWave Vulkan decoder ABI is unavailable (version %u.%u.%u)", major, minor, patch);
            dlclose(library);
            library = nullptr;
            return false;
        }
        return true;
    }

    void unload() noexcept {
        if (library != nullptr) dlclose(library);
        library = nullptr;
        get_version = nullptr;
        create_device = nullptr;
        create_default_device = nullptr;
        destroy_device = nullptr;
        prefers_fragment_path = nullptr;
        set_queue_type = nullptr;
        create_decoder = nullptr;
        clear_decoder = nullptr;
        destroy_decoder = nullptr;
        push = nullptr;
        ready = nullptr;
        decode_gpu = nullptr;
        get_color_metadata = nullptr;
    }

    ~api_t() { unload(); }
};

bool has_extension(VkPhysicalDevice gpu, const char *name) {
    std::uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto &ext) {
        return std::strcmp(ext.extensionName, name) == 0;
    });
}

bool has_instance_extension(const char *name) {
    std::uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data()) != VK_SUCCESS) return false;
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto &ext) {
        return std::strcmp(ext.extensionName, name) == 0;
    });
}

std::uint32_t find_memory_type(const VkPhysicalDeviceMemoryProperties &props,
                               std::uint32_t bits,
                               VkMemoryPropertyFlags preferred) {
    std::uint32_t fallback = std::numeric_limits<std::uint32_t>::max();
    for (std::uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) == 0) continue;
        if ((props.memoryTypes[i].propertyFlags & preferred) == preferred) return i;
        if (fallback == std::numeric_limits<std::uint32_t>::max()) fallback = i;
    }
    return fallback;
}

struct image_t {
    VkImage image{VK_NULL_HANDLE};
    VkDeviceMemory memory{VK_NULL_HANDLE};
    VkImageView view{VK_NULL_HANDLE};
};

void destroy_image(VkDevice device, image_t &image) {
    destroy_handle(device, image.view, vkDestroyImageView);
    destroy_handle(device, image.image, vkDestroyImage);
    if (device != VK_NULL_HANDLE && image.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device, image.memory, nullptr);
        image.memory = VK_NULL_HANDLE;
    }
}

struct shader_constants {
    float scale_x{1.0F};
    float scale_y{1.0F};
    float full_range{1.0F};
    float reserved{0.0F};
    float hdr_mode{0.0F};
    float source_peak_nits{1000.0F};
    float target_peak_nits{1000.0F};
    float padding{0.0F};
};

bool log_vulkan_failure(const char *stage, VkResult result) {
    LOGW("PyroWave Vulkan setup failed at %s (VkResult=%d)", stage, static_cast<int>(result));
    return false;
}

} // namespace

struct PyrowaveVulkanDecoder::impl {
    std::mutex mutex;
    api_t api;
    int width{0};
    int height{0};
    int hdr_mode{0};
    bool full_range{true};
    bool fragment_path{false};
    ANativeWindow *window{nullptr};

    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physical_device{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    PFN_vkSetHdrMetadataEXT set_hdr_metadata{nullptr};
    VkQueue queue{VK_NULL_HANDLE};
    std::uint32_t queue_family{VK_QUEUE_FAMILY_IGNORED};
    VkPhysicalDeviceMemoryProperties memory_properties{};
    VkSurfaceKHR surface{VK_NULL_HANDLE};
    VkSwapchainKHR swapchain{VK_NULL_HANDLE};
    VkFormat swapchain_format{VK_FORMAT_UNDEFINED};
    VkColorSpaceKHR swapchain_color_space{VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    VkExtent2D swapchain_extent{};
    std::vector<VkImage> swapchain_images;
    std::vector<VkImageView> swapchain_views;
    // Per-frame-in-flight resources. The original code shared a single command
    // buffer, fence and descriptor set across every frame, so the CPU had to
    // wait for the GPU before presenting, and the GPU then sat idle through the
    // whole CPU round-trip before the next decode could be submitted. With a
    // small ring of slots the dependency becomes a semaphore: the GPU can start
    // the next decode as soon as the previous conversion signals it.
    //
    // All frames share one set of YUV planes, so a decode must never start
    // before the previous conversion has finished reading them. That is
    // expressed by passing the previous slot's convert_done as the decoder's
    // acquire semaphore (pyrowave_decoder_decode_gpu's first sync argument).
    static constexpr std::uint32_t kMaxFrameDepth = 3;
    struct frame_slot {
        VkCommandBuffer command_buffer{VK_NULL_HANDLE};
        VkCommandBuffer timestamp_cb{VK_NULL_HANDLE};
        VkFence fence{VK_NULL_HANDLE};
        VkSemaphore acquire{VK_NULL_HANDLE};
        VkSemaphore decode_done{VK_NULL_HANDLE};
        VkSemaphore convert_done{VK_NULL_HANDLE};
        VkDescriptorSet descriptor_set{VK_NULL_HANDLE};
        bool fence_pending{false};
        // True while convert_done carries an unconsumed signal. Only then may
        // the next frame's decode wait on it; otherwise it would deadlock.
        bool convert_signalled{false};
    };
    std::array<frame_slot, kMaxFrameDepth> slots{};
    std::uint32_t frame_depth{1};
    std::uint32_t slot_index{0};

    VkCommandPool command_pool{VK_NULL_HANDLE};
    VkFence drain_fence{VK_NULL_HANDLE};
    // GPU-side measurement of the YUV -> RGBA conversion pass. Together with the
    // CPU-side fence wait this separates "the GPU is busy inside convert" from
    // "the GPU is still busy inside the PyroWave decode submission".
    VkQueryPool timestamp_pool{VK_NULL_HANDLE};
    float timestamp_period_ns{1.0F};
    bool timestamps_available{false};

    VkShaderModule conversion_shader{VK_NULL_HANDLE};
    VkDescriptorSetLayout descriptor_layout{VK_NULL_HANDLE};
    VkPipelineLayout pipeline_layout{VK_NULL_HANDLE};
    VkPipeline conversion_pipeline{VK_NULL_HANDLE};
    VkDescriptorPool descriptor_pool{VK_NULL_HANDLE};
    VkSampler sampler{VK_NULL_HANDLE};
    VkBuffer mapping_buffer{VK_NULL_HANDLE};
    VkDeviceMemory mapping_memory{VK_NULL_HANDLE};
    void* mapping_data{nullptr};
    bool mapping_coherent{false};
    int dynamic_hdr_format{DYNAMIC_HDR_FORMAT_NONE};
    float target_peak_nits{1000.0F};
    moonlight::pyrowave::dynamic_hdr_scene dynamic_scene{};
    bool dynamic_frame_applied{false};
    bool dynamic_hdr_logged{false};
    std::array<image_t, 3> planes{};
    std::array<pyrowave_image_view, 3> plane_views{};

    // These objects are deliberately kept alive for the borrowed-device API.
    VkApplicationInfo app_info{};
    VkInstanceCreateInfo instance_create_info{};
    std::vector<const char *> instance_extensions;
    VkDeviceQueueCreateInfo queue_create_info{};
    float queue_priority{1.0F};
    VkPhysicalDeviceFeatures2 enabled_features{};
    VkPhysicalDeviceVulkan12Features enabled_vulkan12_features{};
    VkPhysicalDeviceVulkan13Features enabled_vulkan13_features{};
    VkDeviceCreateInfo device_create_info{};
    std::vector<const char *> device_extensions;
    pyrowave_device_create_queue_info queue_info{};
    pyrowave_device pyrowave_device_handle{nullptr};
    pyrowave_decoder decoder{nullptr};
    moonlight::pyrowave::hdr_static_metadata hdr_metadata{};
    bool hdr_metadata_enabled{false};
    bool hdr_metadata_valid{false};
    bool hdr_metadata_missing_logged{false};
    bool hdr_metadata_extension_missing_logged{false};
    std::uint64_t submitted_frames{0};
    std::uint64_t last_decode_time_us{0};
    std::uint64_t last_present_time_us{0};
    const char *last_submit_failure{nullptr};
    bool submit_failed{false};
    bool decode_signal_pending{false};

    int submit_failure(const char *stage, int code = 0, std::size_t size = 0) {
        submit_failed = true;
        if (last_submit_failure != stage) {
            LOGW("PyroWave Vulkan submit failed at %s (frame=%llu, code=%d, size=%zu)",
                 stage,
                 static_cast<unsigned long long>(submitted_frames),
                 code,
                 size);
            last_submit_failure = stage;
        }
        return -1;
    }

    bool consume_pending_decode_signal(frame_slot &slot) {
        if (!decode_signal_pending) return true;
        if (device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE ||
            slot.decode_done == VK_NULL_HANDLE || drain_fence == VK_NULL_HANDLE) {
            LOGW("PyroWave Vulkan could not drain decode completion semaphore: sync objects unavailable");
            return false;
        }

        const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo submit{
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .pNext = nullptr,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &slot.decode_done,
            .pWaitDstStageMask = &wait_stage,
            .commandBufferCount = 0,
            .pCommandBuffers = nullptr,
            .signalSemaphoreCount = 0,
            .pSignalSemaphores = nullptr,
        };
        PWV_TRACE("DRAIN: -> vkQueueSubmit wait-only (wait={dec=%p} cmdCount=0)",
                  reinterpret_cast<const void *>(slot.decode_done));
        const auto submit_result = vkQueueSubmit(queue, 1, &submit, drain_fence);
        PWV_TRACE("DRAIN: <- vkQueueSubmit wait-only res=%d", static_cast<int>(submit_result));
        if (submit_result != VK_SUCCESS) {
            LOGW("PyroWave Vulkan could not drain decode completion semaphore at queue submit (VkResult=%d)",
                 static_cast<int>(submit_result));
            return false;
        }
        const auto wait_result = vkWaitForFences(device, 1, &drain_fence, VK_TRUE, kGpuWaitTimeoutNs);
        if (wait_result != VK_SUCCESS) {
            LOGW("PyroWave Vulkan could not drain decode completion semaphore at fence wait (VkResult=%d)",
                 static_cast<int>(wait_result));
            return false;
        }
        const auto reset_result = vkResetFences(device, 1, &drain_fence);
        if (reset_result != VK_SUCCESS) {
            LOGW("PyroWave Vulkan could not reset decode drain fence (VkResult=%d)",
                 static_cast<int>(reset_result));
            return false;
        }
        decode_signal_pending = false;
        return true;
    }

    int submit_failure_after_decode(const char *stage, int code = 0, std::size_t size = 0) {
        if (!consume_pending_decode_signal(slots[slot_index])) {
            LOGW("PyroWave Vulkan decode completion remains pending after %s", stage);
        }
        return submit_failure(stage, code, size);
    }

    bool init_instance() {
        const auto volk_result = volkInitialize();
        if (volk_result != VK_SUCCESS) return log_vulkan_failure("volkInitialize", volk_result);
        app_info = {
            .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
            .pNext = nullptr,
            .pApplicationName = "moonlight-pyrowave",
            .applicationVersion = 1,
            .pEngineName = "moonlight",
            .engineVersion = 1,
            .apiVersion = VK_API_VERSION_1_3,
        };
        instance_extensions = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
        if (hdr_mode != 0) {
            if (!has_instance_extension(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME)) {
                LOGW("PyroWave Vulkan setup failed at instance extension: VK_EXT_swapchain_colorspace unavailable");
                return false;
            }
            instance_extensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
        }
        instance_create_info = {
            .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .pApplicationInfo = &app_info,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = static_cast<std::uint32_t>(instance_extensions.size()),
            .ppEnabledExtensionNames = instance_extensions.data(),
        };
        const auto result = vkCreateInstance(&instance_create_info, nullptr, &instance);
        if (result != VK_SUCCESS) return log_vulkan_failure("vkCreateInstance", result);
        volkLoadInstance(instance);
        return true;
    }

    bool init_surface() {
        if (window == nullptr || instance == VK_NULL_HANDLE) return false;
        VkAndroidSurfaceCreateInfoKHR surface_info{
            .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
            .pNext = nullptr,
            .flags = 0,
            .window = window,
        };
        const auto result = vkCreateAndroidSurfaceKHR(instance, &surface_info, nullptr, &surface);
        return result == VK_SUCCESS ? true : log_vulkan_failure("vkCreateAndroidSurfaceKHR", result);
    }

    bool choose_device() {
        std::uint32_t count = 0;
        const auto enumerate_result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (enumerate_result != VK_SUCCESS) return log_vulkan_failure("enumerate-physical-devices", enumerate_result);
        if (count == 0) {
            LOGW("PyroWave Vulkan setup failed: no physical devices");
            return false;
        }
        std::vector<VkPhysicalDevice> devices(count);
        const auto devices_result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
        if (devices_result != VK_SUCCESS) return log_vulkan_failure("enumerate-physical-devices-data", devices_result);

        for (auto candidate : devices) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(candidate, &props);
            if (VK_VERSION_MAJOR(props.apiVersion) < 1 || VK_VERSION_MINOR(props.apiVersion) < 3) continue;
            if (!has_extension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
            const bool hdr_metadata_supported = has_extension(candidate, VK_EXT_HDR_METADATA_EXTENSION_NAME);
            // HDR10/PQ needs VK_EXT_hdr_metadata so supplied static metadata
            // can be applied before presentation. HLG may still present with
            // its HLG colorspace when the host has no mastering metadata; a
            // supplied HLG snapshot is applied only when the extension exists.
            if (hdr_mode == 1 && !hdr_metadata_supported) {
                LOGW("PyroWave HDR10 decoder disabled: VK_EXT_hdr_metadata is unavailable");
                continue;
            }

            std::uint32_t family_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, nullptr);
            std::vector<VkQueueFamilyProperties> families(family_count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &family_count, families.data());
            for (std::uint32_t i = 0; i < family_count; ++i) {
                if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) !=
                    (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) continue;
                VkBool32 present = VK_FALSE;
                if (vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, &present) != VK_SUCCESS || !present) continue;
                physical_device = candidate;
                queue_family = i;
                timestamp_period_ns = props.limits.timestampPeriod;
                timestamps_available = props.limits.timestampComputeAndGraphics != 0;
                break;
            }
            if (physical_device != VK_NULL_HANDLE) break;
        }
        if (physical_device == VK_NULL_HANDLE) return false;

        vkGetPhysicalDeviceMemoryProperties(physical_device, &memory_properties);
        VkPhysicalDeviceFeatures2 supported{};
        supported.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        VkPhysicalDeviceVulkan13Features supported13{};
        VkPhysicalDeviceVulkan12Features supported12{};
        supported12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        supported13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        supported.pNext = &supported12;
        supported12.pNext = &supported13;
        vkGetPhysicalDeviceFeatures2(physical_device, &supported);
        if (!supported13.subgroupSizeControl) {
            LOGW("PyroWave GPU decoder disabled: subgroup size control is unavailable");
            return false;
        }

        enabled_features = {};
        enabled_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        enabled_features.features.shaderInt16 = supported.features.shaderInt16;
        enabled_features.features.shaderStorageImageWriteWithoutFormat = supported.features.shaderStorageImageWriteWithoutFormat;
        enabled_vulkan12_features = {};
        enabled_vulkan12_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        enabled_vulkan12_features.storageBuffer8BitAccess = supported12.storageBuffer8BitAccess;
        enabled_vulkan12_features.shaderFloat16 = supported12.shaderFloat16;
        enabled_vulkan12_features.shaderInt8 = supported12.shaderInt8;
        enabled_vulkan12_features.timelineSemaphore = supported12.timelineSemaphore;
        enabled_vulkan13_features = {};
        enabled_vulkan13_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        enabled_vulkan13_features.subgroupSizeControl = VK_TRUE;
        enabled_vulkan13_features.computeFullSubgroups = supported13.computeFullSubgroups;
        enabled_vulkan13_features.synchronization2 = supported13.synchronization2;
        enabled_features.pNext = &enabled_vulkan12_features;
        enabled_vulkan12_features.pNext = &enabled_vulkan13_features;

        queue_create_info = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .queueFamilyIndex = queue_family,
            .queueCount = 1,
            .pQueuePriorities = &queue_priority,
        };
        device_extensions = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
        if (has_extension(physical_device, VK_EXT_HDR_METADATA_EXTENSION_NAME)) {
            device_extensions.push_back(VK_EXT_HDR_METADATA_EXTENSION_NAME);
        }
        device_create_info = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .pNext = &enabled_features,
            .flags = 0,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queue_create_info,
            .enabledLayerCount = 0,
            .ppEnabledLayerNames = nullptr,
            .enabledExtensionCount = static_cast<std::uint32_t>(device_extensions.size()),
            .ppEnabledExtensionNames = device_extensions.data(),
            .pEnabledFeatures = nullptr,
        };
        const auto create_device_result = vkCreateDevice(physical_device, &device_create_info, nullptr, &device);
        if (create_device_result != VK_SUCCESS) return log_vulkan_failure("vkCreateDevice", create_device_result);
        volkLoadDevice(device);
        if (has_extension(physical_device, VK_EXT_HDR_METADATA_EXTENSION_NAME)) {
            set_hdr_metadata = reinterpret_cast<PFN_vkSetHdrMetadataEXT>(
                vkGetDeviceProcAddr(device, "vkSetHdrMetadataEXT"));
        }
        if (hdr_mode == 1 && set_hdr_metadata == nullptr) {
            LOGW("PyroWave HDR10 decoder disabled: vkSetHdrMetadataEXT is unavailable");
            return false;
        }
        vkGetDeviceQueue(device, queue_family, 0, &queue);
        queue_info = { queue, queue_family, 0 };
        if (timestamps_available) {
            // 0 = conversion start, 1 = conversion end, 2 = pre-decode stamp.
            VkQueryPoolCreateInfo query_info{ .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
                                              .queryType = VK_QUERY_TYPE_TIMESTAMP, .queryCount = 3 };
            if (vkCreateQueryPool(device, &query_info, nullptr, &timestamp_pool) != VK_SUCCESS) {
                timestamp_pool = VK_NULL_HANDLE;
                timestamps_available = false;
                LOGW("PyroWave: GPU timestamp pool unavailable; using wall-clock estimates only");
            }
        }

        pyrowave_device_create_info pyro_info{
            .GetInstanceProcAddr = vkGetInstanceProcAddr,
            .instance = instance,
            .physical_device = physical_device,
            .device = device,
            .instance_create_info = &instance_create_info,
            .device_create_info = &device_create_info,
            .queue_info = &queue_info,
            .queue_info_count = 1,
            .queue_lock_callback = nullptr,
            .queue_unlock_callback = nullptr,
            .userdata = nullptr,
        };
        const auto pyro_result = api.create_device(&pyro_info, &pyrowave_device_handle);
        const auto queue_result = pyrowave_device_handle == nullptr
            ? PYROWAVE_ERROR_GENERIC
            : api.set_queue_type(pyrowave_device_handle, VK_QUEUE_GRAPHICS_BIT);
        if (pyro_result != PYROWAVE_SUCCESS || pyrowave_device_handle == nullptr ||
            queue_result != PYROWAVE_SUCCESS) {
            LOGW("PyroWave Vulkan setup failed at PyroWave device/queue (device=%d queue=%d null=%d)",
                 static_cast<int>(pyro_result), static_cast<int>(queue_result),
                 pyrowave_device_handle == nullptr ? 1 : 0);
            return false;
        }
        // These planes live on the same VkDevice; Linux DRM external-memory
        // support is not required for an Android Surface decoder.
        fragment_path = api.prefers_fragment_path(pyrowave_device_handle);
        return true;
    }

    bool create_command_objects() {
        VkCommandPoolCreateInfo pool_info{
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = queue_family,
        };
        const auto pool_result = vkCreateCommandPool(device, &pool_info, nullptr, &command_pool);
        if (pool_result != VK_SUCCESS) return log_vulkan_failure("vkCreateCommandPool", pool_result);

        // How many frames may be in flight. 1 reproduces the original
        // wait-for-the-GPU behaviour; larger values let the decoder start the
        // next frame while the previous conversion is still running.
        char prop_depth[PROP_VALUE_MAX] = {};
        __system_property_get("debug.pyrowave.depth", prop_depth);
        const long requested = prop_depth[0] != '\0' ? std::strtol(prop_depth, nullptr, 10) : 2;
        frame_depth = (requested >= 1 && requested <= static_cast<long>(kMaxFrameDepth))
                          ? static_cast<std::uint32_t>(requested)
                          : 2;
        slot_index = 0;

        VkCommandBufferAllocateInfo allocate_info{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .pNext = nullptr,
            .commandPool = command_pool,
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = 2,
        };
        VkFenceCreateInfo fence_info{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        VkSemaphoreCreateInfo semaphore_info{ .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        for (std::uint32_t i = 0; i < frame_depth; ++i) {
            auto &slot = slots[i];
            std::array<VkCommandBuffer, 2> buffers{};
            const auto command_result = vkAllocateCommandBuffers(device, &allocate_info, buffers.data());
            if (command_result != VK_SUCCESS) return log_vulkan_failure("vkAllocateCommandBuffers", command_result);
            slot.command_buffer = buffers[0];
            slot.timestamp_cb = buffers[1];
            if (vkCreateFence(device, &fence_info, nullptr, &slot.fence) != VK_SUCCESS)
                return log_vulkan_failure("vkCreateFence(submit)", VK_ERROR_INITIALIZATION_FAILED);
            if (vkCreateSemaphore(device, &semaphore_info, nullptr, &slot.acquire) != VK_SUCCESS)
                return log_vulkan_failure("vkCreateSemaphore(acquire)", VK_ERROR_INITIALIZATION_FAILED);
            if (vkCreateSemaphore(device, &semaphore_info, nullptr, &slot.decode_done) != VK_SUCCESS)
                return log_vulkan_failure("vkCreateSemaphore(decode-complete)", VK_ERROR_INITIALIZATION_FAILED);
            if (vkCreateSemaphore(device, &semaphore_info, nullptr, &slot.convert_done) != VK_SUCCESS)
                return log_vulkan_failure("vkCreateSemaphore(convert-complete)", VK_ERROR_INITIALIZATION_FAILED);
            slot.fence_pending = false;
            slot.convert_signalled = false;
        }
        if (vkCreateFence(device, &fence_info, nullptr, &drain_fence) != VK_SUCCESS)
            return log_vulkan_failure("vkCreateFence(drain)", VK_ERROR_INITIALIZATION_FAILED);
        LOGI("PyroWave frame pipeline: depth=%u", frame_depth);
        return true;
    }

    // Records the one-shot timestamp command buffer used to bracket the
    // decoder's GPU work from the outside (the library owns its own command
    // buffers, so we cannot write timestamps inside them).
    bool record_timestamp_buffers() {
        if (timestamp_pool == VK_NULL_HANDLE) return true;
        VkCommandBufferBeginInfo begin_info{ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        for (std::uint32_t i = 0; i < frame_depth; ++i) {
            auto &slot = slots[i];
            if (vkBeginCommandBuffer(slot.timestamp_cb, &begin_info) != VK_SUCCESS) return false;
            vkCmdResetQueryPool(slot.timestamp_cb, timestamp_pool, 2, 1);
            vkCmdWriteTimestamp(slot.timestamp_cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestamp_pool, 2);
            if (vkEndCommandBuffer(slot.timestamp_cb) != VK_SUCCESS) return false;
        }
        return true;
    }

    bool apply_hdr_metadata() {
        if (hdr_mode == 0 || !hdr_metadata_enabled || !hdr_metadata_valid) return true;
        if (swapchain == VK_NULL_HANDLE) return false;
        if (set_hdr_metadata == nullptr) {
            // HLG can still be presented through its HLG swapchain colorspace
            // when the optional static-metadata extension is unavailable.
            // HDR10/PQ is rejected during device preflight instead.
            if (hdr_mode == 2) {
                if (!hdr_metadata_extension_missing_logged) {
                    LOGW("PyroWave HLG presentation is continuing without VK_EXT_hdr_metadata");
                    hdr_metadata_extension_missing_logged = true;
                }
                return true;
            }
            return false;
        }

        VkHdrMetadataEXT metadata{
            .sType = VK_STRUCTURE_TYPE_HDR_METADATA_EXT,
            .pNext = nullptr,
            .displayPrimaryRed = { hdr_metadata.display_primaries[0].x,
                                   hdr_metadata.display_primaries[0].y },
            .displayPrimaryGreen = { hdr_metadata.display_primaries[1].x,
                                     hdr_metadata.display_primaries[1].y },
            .displayPrimaryBlue = { hdr_metadata.display_primaries[2].x,
                                    hdr_metadata.display_primaries[2].y },
            .whitePoint = { hdr_metadata.white_point.x, hdr_metadata.white_point.y },
            .maxLuminance = hdr_metadata.max_display_luminance,
            .minLuminance = hdr_metadata.min_display_luminance,
            .maxContentLightLevel = hdr_metadata.max_content_light_level,
            .maxFrameAverageLightLevel = hdr_metadata.max_frame_average_light_level,
        };
        if (dynamic_frame_applied) {
            // These are output-signal values after application mapping. The
            // source SS_HDR_METADATA snapshot itself remains unchanged.
            metadata.maxLuminance = target_peak_nits;
            metadata.minLuminance = std::min(metadata.minLuminance, target_peak_nits);
            metadata.maxContentLightLevel = std::min(metadata.maxContentLightLevel, target_peak_nits);
            metadata.maxFrameAverageLightLevel = std::min(metadata.maxFrameAverageLightLevel, target_peak_nits);
        }
        // VkHdrMetadataEXT has no independent maxFullFrameLuminance member.
        // Keep that Sunshine display value in the validated snapshot (so it
        // survives rebind/recovery) but do not mislabel it as MaxFALL.
        // VK_EXT_hdr_metadata exposes a void entry point; extension/function
        // availability is the failure boundary, while the driver applies the
        // metadata asynchronously to the swapchain.
        set_hdr_metadata(device, 1, &swapchain, &metadata);
        return true;
    }

    bool set_hdr_metadata_snapshot(bool enabled, const std::uint8_t *bytes, std::size_t length) {
        const auto previous_metadata = hdr_metadata;
        const bool previous_metadata_valid = hdr_metadata_valid;
        hdr_metadata_enabled = enabled;
        hdr_metadata_valid = false;
        hdr_metadata = {};
        bool all_zero = bytes != nullptr && length >= kSsHdrMetadataSize;
        if (all_zero) {
            for (std::size_t i = 0; i < kSsHdrMetadataSize; ++i) {
                if (bytes[i] != 0) {
                    all_zero = false;
                    break;
                }
            }
        }
        if (!enabled || bytes == nullptr || length == 0 || all_zero) {
            // Vulkan defines HDR metadata as optional. Keep the negotiated
            // HDR10/HLG colorspace when Sunshine has not supplied a static
            // snapshot yet; do not invent mastering or content-light values.
            if (hdr_mode != 0 && !hdr_metadata_missing_logged) {
                LOGW("PyroWave HDR mode %d running without Sunshine static HDR metadata; "
                     "presenting degraded HDR colorspace", hdr_mode);
                hdr_metadata_missing_logged = true;
            }
            return true;
        }
        hdr_metadata_missing_logged = false;
        hdr_metadata_valid = moonlight::pyrowave::parse_ss_hdr_metadata(
            bytes, length, hdr_metadata);
        if (!hdr_metadata_valid) {
            LOGW("PyroWave HDR metadata rejected: invalid Sunshine SS_HDR_METADATA (mode=%d size=%zu)",
                 hdr_mode, length);
            // Do not apply malformed values and do not invent replacements.
            // Keep the last known-good snapshot if one was already applied;
            // otherwise the negotiated PQ/HLG colorspace remains usable as
            // degraded HDR.
            hdr_metadata = previous_metadata;
            hdr_metadata_valid = previous_metadata_valid;
            if (!previous_metadata_valid) hdr_metadata_missing_logged = true;
            return true;
        }
        if (swapchain == VK_NULL_HANDLE) return true;
        const auto applied = apply_hdr_metadata();
        if (!applied) {
            LOGW("PyroWave HDR metadata could not be applied to the current swapchain (mode=%d)", hdr_mode);
        }
        return applied;
    }

    bool create_swapchain() {
        std::uint32_t format_count = 0;
        const auto format_result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device, surface, &format_count, nullptr);
        if (format_result != VK_SUCCESS) return log_vulkan_failure("surface-formats", format_result);
        if (format_count == 0) {
            LOGW("PyroWave Vulkan setup failed: Surface exposes no formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        const auto formats_result = vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device, surface, &format_count, formats.data());
        if (formats_result != VK_SUCCESS) return log_vulkan_failure("surface-formats-data", formats_result);
        const bool hdr = hdr_mode != 0;
        const VkFormat preferred_hdr = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        const VkColorSpaceKHR hdr_space = hdr_mode == 2
            ? VK_COLOR_SPACE_HDR10_HLG_EXT
            : VK_COLOR_SPACE_HDR10_ST2084_EXT;
        VkSurfaceFormatKHR selected{};
        if (hdr) {
            auto it = std::find_if(formats.begin(), formats.end(), [&](const auto &f) {
                return f.format == preferred_hdr && f.colorSpace == hdr_space;
            });
            if (it == formats.end()) {
                LOGW("PyroWave Vulkan setup failed: HDR Surface format/colorspace unavailable");
                return false;
            }
            selected = *it;
        } else {
            auto it = std::find_if(formats.begin(), formats.end(), [](const auto &f) {
                return f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            });
            // The conversion shader declares an rgba8 storage image. Do not
            // silently accept BGRA8 (or an arbitrary first format), because
            // that either fails image-view creation or swaps red/blue.
            if (it == formats.end()) {
                LOGW("PyroWave Vulkan setup failed: RGBA8 sRGB Surface format unavailable");
                return false;
            }
            selected = *it;
        }
        VkFormatProperties format_properties{};
        vkGetPhysicalDeviceFormatProperties(physical_device, selected.format, &format_properties);
        if ((format_properties.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
            LOGW("PyroWave GPU decoder disabled: selected Surface format lacks storage-image support");
            return false;
        }
        VkSurfaceCapabilitiesKHR caps{};
        const auto caps_result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device, surface, &caps);
        if (caps_result != VK_SUCCESS) return log_vulkan_failure("surface-capabilities", caps_result);
        if ((caps.supportedUsageFlags & VK_IMAGE_USAGE_STORAGE_BIT) == 0) {
            LOGW("PyroWave GPU decoder disabled: Surface swapchain has no storage-image usage");
            return false;
        }
        VkExtent2D extent = caps.currentExtent;
        if (extent.width == std::numeric_limits<std::uint32_t>::max()) {
            extent.width = std::clamp<std::uint32_t>(static_cast<std::uint32_t>(width), caps.minImageExtent.width, caps.maxImageExtent.width);
            extent.height = std::clamp<std::uint32_t>(static_cast<std::uint32_t>(height), caps.minImageExtent.height, caps.maxImageExtent.height);
        }
        std::uint32_t image_count = std::max(2u, caps.minImageCount);
        if (caps.maxImageCount != 0) image_count = std::min(image_count, caps.maxImageCount);

        // Presentation latency control. The original code hard-coded FIFO with a
        // minimal image count: on a 120 Hz panel that queues the frame behind the
        // already-pending one and blocks until an image is released, which shows
        // up as a roughly constant ~15 ms "present" cost that barely changes with
        // resolution. MAILBOX lets the newest frame replace the pending one, which
        // is the standard low-latency choice for streaming.
        //
        // Selection is overridable at runtime (no rebuild needed) for A/B testing:
        //   setprop debug.pyrowave.present mailbox | immediate | fifo | auto
        //   setprop debug.pyrowave.images  3
        // Kill and restart the app for it to take effect.
        char prop_present[PROP_VALUE_MAX] = {};
        __system_property_get("debug.pyrowave.present", prop_present);
        char prop_images[PROP_VALUE_MAX] = {};
        __system_property_get("debug.pyrowave.images", prop_images);

        std::uint32_t present_mode_count = 0;
        std::vector<VkPresentModeKHR> modes;
        if (vkGetPhysicalDeviceSurfacePresentModesKHR(physical_device, surface, &present_mode_count, nullptr) == VK_SUCCESS &&
            present_mode_count > 0) {
            modes.resize(present_mode_count);
            if (vkGetPhysicalDeviceSurfacePresentModesKHR(physical_device, surface, &present_mode_count, modes.data()) != VK_SUCCESS)
                modes.clear();
        }
        const auto have = [&modes](VkPresentModeKHR m) {
            for (auto x : modes) if (x == m) return true;
            return false;
        };
        const bool want_mailbox = std::strcmp(prop_present, "mailbox") == 0 ||
                                  prop_present[0] == '\0' || std::strcmp(prop_present, "auto") == 0;
        const bool want_immediate = std::strcmp(prop_present, "immediate") == 0;

        VkPresentModeKHR selected_mode = VK_PRESENT_MODE_FIFO_KHR;
        if (want_immediate && have(VK_PRESENT_MODE_IMMEDIATE_KHR)) {
            selected_mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
        } else if (want_mailbox && have(VK_PRESENT_MODE_MAILBOX_KHR)) {
            selected_mode = VK_PRESENT_MODE_MAILBOX_KHR;
        } else if (want_mailbox && have(VK_PRESENT_MODE_IMMEDIATE_KHR) && prop_present[0] != '\0') {
            selected_mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        if (selected_mode != VK_PRESENT_MODE_FIFO_KHR) {
            // Queueing requires a spare image, otherwise it degenerates to FIFO.
            image_count = std::max(image_count, 3u);
            if (caps.maxImageCount != 0) image_count = std::min(image_count, caps.maxImageCount);
        }
        if (prop_images[0] != '\0') {
            const long requested = std::strtol(prop_images, nullptr, 10);
            if (requested >= 2) {
                image_count = static_cast<std::uint32_t>(requested);
                if (caps.maxImageCount != 0) image_count = std::min(image_count, caps.maxImageCount);
                image_count = std::max(image_count, caps.minImageCount);
            }
        }
        LOGI("PyroWave swapchain: extent=%ux%u presentMode=%u imageCount=%u min/max=%u/%u (requested='%s' images='%s')",
             extent.width, extent.height, static_cast<unsigned>(selected_mode), image_count,
             caps.minImageCount, caps.maxImageCount, prop_present, prop_images);
        VkSurfaceTransformFlagBitsKHR pre_transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        if ((caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) == 0) {
            // The conversion shader writes upright frames and does not apply
            // app-side pre-rotation. Prefer letting the compositor rotate the
            // window; only follow currentTransform when the driver rejects
            // identity preTransform entirely.
            pre_transform = caps.currentTransform;
            if (pre_transform != VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
                LOGW("PyroWave Vulkan: identity preTransform unavailable (transform=%u); presentation may rotate",
                     static_cast<unsigned>(pre_transform));
            }
        }
        const VkCompositeAlphaFlagBitsKHR composite_alpha =
            (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                : (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                    ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
                    : VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        VkSwapchainCreateInfoKHR swap_info{
            .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
            .pNext = nullptr,
            .flags = 0,
            .surface = surface,
            .minImageCount = image_count,
            .imageFormat = selected.format,
            .imageColorSpace = selected.colorSpace,
            .imageExtent = extent,
            .imageArrayLayers = 1,
            .imageUsage = VK_IMAGE_USAGE_STORAGE_BIT,
            .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = nullptr,
            .preTransform = pre_transform,
            .compositeAlpha = composite_alpha,
            .presentMode = selected_mode,
            .clipped = VK_TRUE,
            .oldSwapchain = VK_NULL_HANDLE,
        };
        const auto swapchain_result = vkCreateSwapchainKHR(device, &swap_info, nullptr, &swapchain);
        if (swapchain_result != VK_SUCCESS) return log_vulkan_failure("vkCreateSwapchainKHR", swapchain_result);
        swapchain_format = selected.format;
        swapchain_color_space = selected.colorSpace;
        swapchain_extent = extent;
        std::uint32_t count = 0;
        const auto image_count_result = vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr);
        if (image_count_result != VK_SUCCESS) return log_vulkan_failure("swapchain-images", image_count_result);
        if (count == 0) {
            LOGW("PyroWave Vulkan setup failed: swapchain has no images");
            return false;
        }
        swapchain_images.resize(count);
        const auto images_result = vkGetSwapchainImagesKHR(device, swapchain, &count, swapchain_images.data());
        if (images_result != VK_SUCCESS) return log_vulkan_failure("swapchain-images-data", images_result);
        swapchain_views.resize(count);
        for (std::uint32_t i = 0; i < count; ++i) {
            VkImageViewCreateInfo view_info{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = swapchain_images[i],
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = swapchain_format,
                .components = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                                VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY },
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
            };
            const auto view_result = vkCreateImageView(device, &view_info, nullptr, &swapchain_views[i]);
            if (view_result != VK_SUCCESS) return log_vulkan_failure("vkCreateImageView(swapchain)", view_result);
        }
        return apply_hdr_metadata();
    }

    bool create_planes() {
        const bool high_precision = hdr_mode != 0;
        const VkFormat format = high_precision ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM;
        VkFormatProperties properties{};
        vkGetPhysicalDeviceFormatProperties(physical_device, format, &properties);
        constexpr VkFormatFeatureFlags required_features = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
            VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
        if ((properties.optimalTilingFeatures & required_features) != required_features) {
            LOGW("PyroWave Vulkan plane format %d lacks required features (available=0x%x, required=0x%x)",
                 static_cast<int>(format), properties.optimalTilingFeatures, required_features);
            return false;
        }
        for (std::size_t i = 0; i < planes.size(); ++i) {
            const std::uint32_t w = static_cast<std::uint32_t>(i == 0 ? width : width / 2);
            const std::uint32_t h = static_cast<std::uint32_t>(i == 0 ? height : height / 2);
            VkImageCreateInfo image_info{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = format,
                .extent = { w, h, 1 },
                .mipLevels = 1,
                .arrayLayers = 1,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            };
            const auto image_result = vkCreateImage(device, &image_info, nullptr, &planes[i].image);
            if (image_result != VK_SUCCESS) return log_vulkan_failure("vkCreateImage(plane)", image_result);
            VkMemoryRequirements requirements{};
            vkGetImageMemoryRequirements(device, planes[i].image, &requirements);
            const std::uint32_t memory_type = find_memory_type(memory_properties, requirements.memoryTypeBits,
                                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memory_type == std::numeric_limits<std::uint32_t>::max()) {
                LOGW("PyroWave Vulkan setup failed: no memory type for plane %zu", i);
                return false;
            }
            VkMemoryAllocateInfo allocation{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = requirements.size,
                .memoryTypeIndex = memory_type,
            };
            const auto memory_result = vkAllocateMemory(device, &allocation, nullptr, &planes[i].memory);
            if (memory_result != VK_SUCCESS) return log_vulkan_failure("vkAllocateMemory(plane)", memory_result);
            const auto bind_result = vkBindImageMemory(device, planes[i].image, planes[i].memory, 0);
            if (bind_result != VK_SUCCESS) return log_vulkan_failure("vkBindImageMemory(plane)", bind_result);
            VkImageViewCreateInfo view_info{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = planes[i].image,
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = format,
                .components = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                                VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY },
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
            };
            const auto view_result = vkCreateImageView(device, &view_info, nullptr, &planes[i].view);
            if (view_result != VK_SUCCESS) return log_vulkan_failure("vkCreateImageView(plane)", view_result);
            plane_views[i] = {
                .image = planes[i].image,
                .width = w,
                .height = h,
                .image_format = format,
                .view_format = format,
                .mip_level = 0,
                .layer = 0,
                .aspect = VK_IMAGE_ASPECT_COLOR_BIT,
                .swizzle = VK_COMPONENT_SWIZZLE_IDENTITY,
                .layout = VK_IMAGE_LAYOUT_GENERAL,
            };
        }

        VkCommandBufferBeginInfo begin_info{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .pNext = nullptr,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
            .pInheritanceInfo = nullptr,
        };
        const auto reset_command_result = vkResetCommandBuffer(slots[0].command_buffer, 0);
        if (reset_command_result != VK_SUCCESS) {
            return log_vulkan_failure("vkResetCommandBuffer(planes)", reset_command_result);
        }
        const auto begin_result = vkBeginCommandBuffer(slots[0].command_buffer, &begin_info);
        if (begin_result != VK_SUCCESS) return log_vulkan_failure("vkBeginCommandBuffer(planes)", begin_result);
        const VkAccessFlags plane_write_access = fragment_path
            ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            : VK_ACCESS_SHADER_WRITE_BIT;
        const VkPipelineStageFlags plane_write_stage = fragment_path
            ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        std::array<VkImageMemoryBarrier, 3> barriers{};
        for (std::size_t i = 0; i < planes.size(); ++i) {
            barriers[i] = {
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = plane_write_access,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = planes[i].image,
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
            };
        }
        vkCmdPipelineBarrier(slots[0].command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             plane_write_stage, 0, 0, nullptr, 0, nullptr,
                             static_cast<std::uint32_t>(barriers.size()), barriers.data());
        const auto end_result = vkEndCommandBuffer(slots[0].command_buffer);
        if (end_result != VK_SUCCESS) return log_vulkan_failure("vkEndCommandBuffer(planes)", end_result);
        VkSubmitInfo submit{ .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1,
                             .pCommandBuffers = &slots[0].command_buffer };
        const auto queue_result = vkQueueSubmit(queue, 1, &submit, slots[0].fence);
        if (queue_result != VK_SUCCESS) return log_vulkan_failure("vkQueueSubmit(planes)", queue_result);
        const auto fence_result = vkWaitForFences(device, 1, &slots[0].fence, VK_TRUE, kGpuWaitTimeoutNs);
        if (fence_result != VK_SUCCESS) return log_vulkan_failure("vkWaitForFences(planes)", fence_result);
        const auto reset_result = vkResetFences(device, 1, &slots[0].fence);
        if (reset_result != VK_SUCCESS) return log_vulkan_failure("vkResetFences(planes)", reset_result);
        return true;
    }

    bool create_mapping_buffer() {
        VkBufferCreateInfo info{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = sizeof(moonlight::pyrowave::dynamic_hdr_lut),
            .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
        auto result = vkCreateBuffer(device, &info, nullptr, &mapping_buffer);
        if (result != VK_SUCCESS) return log_vulkan_failure("vkCreateBuffer(HDR mapping)", result);
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device, mapping_buffer, &requirements);
        std::uint32_t type = UINT32_MAX;
        for (std::uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
            const auto flags = memory_properties.memoryTypes[i].propertyFlags;
            if ((requirements.memoryTypeBits & (1U << i)) == 0 ||
                (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
            type = i;
            if ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) break;
        }
        if (type == UINT32_MAX) return false;
        mapping_coherent = (memory_properties.memoryTypes[type].propertyFlags &
                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        VkMemoryAllocateInfo allocation{.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = requirements.size, .memoryTypeIndex = type};
        result = vkAllocateMemory(device, &allocation, nullptr, &mapping_memory);
        if (result != VK_SUCCESS) return log_vulkan_failure("vkAllocateMemory(HDR mapping)", result);
        result = vkBindBufferMemory(device, mapping_buffer, mapping_memory, 0);
        if (result != VK_SUCCESS) return log_vulkan_failure("vkBindBufferMemory(HDR mapping)", result);
        result = vkMapMemory(device, mapping_memory, 0, VK_WHOLE_SIZE, 0, &mapping_data);
        return result == VK_SUCCESS ? true : log_vulkan_failure("vkMapMemory(HDR mapping)", result);
    }

    bool update_dynamic_mapping(const std::uint8_t* metadata, std::size_t length) {
        if (dynamic_hdr_format == DYNAMIC_HDR_FORMAT_NONE) return true;
        moonlight::pyrowave::dynamic_hdr_scene scene;
        if (!moonlight::pyrowave::parse_dynamic_hdr_frame(metadata, length, dynamic_hdr_format, width, height, scene)) {
            LOGW("PyroWave dynamic HDR frame rejected: format=%d metadata_bytes=%zu", dynamic_hdr_format, length);
            return false;
        }
        // Validate every frame, but unrelated runtime TLVs must not force LUT
        // allocation/rebuild. Missing metadata never authorizes stale reuse.
        if (!dynamic_frame_applied || scene.minimum_nits != dynamic_scene.minimum_nits ||
            scene.maximum_nits != dynamic_scene.maximum_nits || scene.average_nits != dynamic_scene.average_nits ||
            scene.median_nits != dynamic_scene.median_nits || scene.variance_pq != dynamic_scene.variance_pq) {
            moonlight::pyrowave::dynamic_hdr_lut lut;
            if (mapping_data == nullptr || !moonlight::pyrowave::build_dynamic_hdr_lut(scene, target_peak_nits, lut)) return false;
            std::memcpy(mapping_data, lut.data(), sizeof(lut));
            if (!mapping_coherent) {
                VkMappedMemoryRange range{.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                    .memory = mapping_memory, .offset = 0, .size = VK_WHOLE_SIZE};
                const auto result = vkFlushMappedMemoryRanges(device, 1, &range);
                if (result != VK_SUCCESS) return log_vulkan_failure("vkFlushMappedMemoryRanges(HDR mapping)", result);
            }
        }
        dynamic_scene = scene;
        dynamic_frame_applied = true;
        return true;
    }

    bool create_conversion_pipeline() {
        if (hdr_mode != 0 && !create_mapping_buffer()) return false;
        const auto *code = hdr_mode != 0 ? k_pyrowave_yuv_to_rgb10a2_spv : k_pyrowave_yuv_to_rgba_spv;
        const auto size = hdr_mode != 0 ? k_pyrowave_yuv_to_rgb10a2_spv_size : k_pyrowave_yuv_to_rgba_spv_size;
        VkShaderModuleCreateInfo shader_info{ .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                              .codeSize = size, .pCode = code };
        const auto shader_result = vkCreateShaderModule(device, &shader_info, nullptr, &conversion_shader);
        if (shader_result != VK_SUCCESS) return log_vulkan_failure("vkCreateShaderModule", shader_result);
        VkDescriptorSetLayoutBinding bindings[5]{};
        for (std::uint32_t i = 0; i < 3; ++i) {
            bindings[i] = { i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        }
        bindings[3] = { 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        bindings[4] = { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr };
        VkDescriptorSetLayoutCreateInfo layout_info{ .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                                     .bindingCount = hdr_mode != 0 ? 5U : 4U, .pBindings = bindings };
        const auto layout_result = vkCreateDescriptorSetLayout(device, &layout_info, nullptr, &descriptor_layout);
        if (layout_result != VK_SUCCESS) return log_vulkan_failure("vkCreateDescriptorSetLayout", layout_result);
        VkPushConstantRange push_range{ VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(shader_constants) };
        VkPipelineLayoutCreateInfo pipeline_layout_info{ .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                                                         .setLayoutCount = 1, .pSetLayouts = &descriptor_layout,
                                                         .pushConstantRangeCount = 1, .pPushConstantRanges = &push_range };
        const auto pipeline_layout_result = vkCreatePipelineLayout(device, &pipeline_layout_info, nullptr, &pipeline_layout);
        if (pipeline_layout_result != VK_SUCCESS) return log_vulkan_failure("vkCreatePipelineLayout", pipeline_layout_result);
        VkComputePipelineCreateInfo pipeline_info{ .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .stage = { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                       .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = conversion_shader, .pName = "main" },
            .layout = pipeline_layout };
        // REAL_PASS enables the 5-tap sharpen in the conversion shader. It costs
        // four extra sampler reads per presented pixel, so make it switchable and
        // keep it on by default to preserve the existing picture (the YCbCr
        // sampler still does the 4:2:0 -> RGB chroma reconstruction either way).
        //   setprop debug.pyrowave.sharpen 0   # off, 1 sample/px
        {
            char prop_sharpen[PROP_VALUE_MAX] = {};
            __system_property_get("debug.pyrowave.sharpen", prop_sharpen);
            const int real_pass = prop_sharpen[0] == '0' ? 0 : 1;
            const VkSpecializationMapEntry entry{ 1, 0, sizeof(int) };
            VkSpecializationInfo spec{ .mapEntryCount = 1, .pMapEntries = &entry,
                                       .dataSize = sizeof(int), .pData = &real_pass };
            pipeline_info.stage.pSpecializationInfo = &spec;
            LOGI("PyroWave conversion pipeline: sharpen=%d", real_pass);
        }
        const auto pipeline_result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &conversion_pipeline);
        if (pipeline_result != VK_SUCCESS) return log_vulkan_failure("vkCreateComputePipelines", pipeline_result);
        // Sized per in-flight frame: the counts are totals for the whole pool,
        // not per set.
        VkDescriptorPoolSize sizes[3] = { { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3 * frame_depth },
                                          { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1 * frame_depth },
                                          { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1 * frame_depth } };
        VkDescriptorPoolCreateInfo pool_info{ .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                              .maxSets = frame_depth, .poolSizeCount = hdr_mode != 0 ? 3U : 2U, .pPoolSizes = sizes };
        const auto pool_result = vkCreateDescriptorPool(device, &pool_info, nullptr, &descriptor_pool);
        if (pool_result != VK_SUCCESS) return log_vulkan_failure("vkCreateDescriptorPool", pool_result);
        // One descriptor set per in-flight frame: each set pins the swapchain
        // image view it renders into, so it must not be rewritten while the GPU
        // is still reading it for an earlier frame.
        std::vector<VkDescriptorSetLayout> layouts(frame_depth, descriptor_layout);
        std::vector<VkDescriptorSet> sets(frame_depth, VK_NULL_HANDLE);
        VkDescriptorSetAllocateInfo allocation{ .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                                .descriptorPool = descriptor_pool,
                                                .descriptorSetCount = frame_depth,
                                                .pSetLayouts = layouts.data() };
        const auto descriptor_result = vkAllocateDescriptorSets(device, &allocation, sets.data());
        if (descriptor_result != VK_SUCCESS) return log_vulkan_failure("vkAllocateDescriptorSets", descriptor_result);
        for (std::uint32_t i = 0; i < frame_depth; ++i) slots[i].descriptor_set = sets[i];
        VkSamplerCreateInfo sampler_info{ .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
            .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .maxLod = 0.0f, .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK };
        const auto sampler_result = vkCreateSampler(device, &sampler_info, nullptr, &sampler);
        return sampler_result == VK_SUCCESS ? true : log_vulkan_failure("vkCreateSampler", sampler_result);
    }

    bool ready_for_submit() const {
        return decoder != nullptr && swapchain != VK_NULL_HANDLE && conversion_pipeline != VK_NULL_HANDLE;
    }

    bool initialize_locked() {
        if (!api.load()) {
            LOGW("PyroWave Vulkan decoder initialization failed: runtime library unavailable");
            release_locked();
            return false;
        }
        if (!init_instance()) {
            LOGW("PyroWave Vulkan decoder initialization failed: Vulkan instance setup");
            release_locked();
            return false;
        }
        if (window == nullptr || !init_surface()) {
            LOGW("PyroWave Vulkan decoder initialization failed: Android Surface setup");
            release_locked();
            return false;
        }
        if (!choose_device()) {
            LOGW("PyroWave Vulkan decoder initialization failed: no compatible device");
            release_locked();
            return false;
        }
        if (!create_command_objects()) {
            LOGW("PyroWave Vulkan decoder initialization failed: command/sync objects");
            release_locked();
            return false;
        }
        if (!create_swapchain()) {
            LOGW("PyroWave Vulkan decoder initialization failed: swapchain");
            release_locked();
            return false;
        }
        if (!create_planes()) {
            LOGW("PyroWave Vulkan decoder initialization failed: YUV planes");
            release_locked();
            return false;
        }
        if (!create_conversion_pipeline()) {
            LOGW("PyroWave Vulkan decoder initialization failed: conversion pipeline");
            release_locked();
            return false;
        }
        pyrowave_decoder_create_info info{
            .device = pyrowave_device_handle,
            .width = width,
            .height = height,
            .chroma = PYROWAVE_CHROMA_SUBSAMPLING_420,
            .fragment_path = fragment_path,
        };
        if (api.create_decoder(&info, &decoder) != PYROWAVE_SUCCESS || decoder == nullptr) {
            LOGW("PyroWave Vulkan decoder initialization failed: decoder creation");
            release_locked();
            return false;
        }
        if (!record_timestamp_buffers()) {
            LOGW("PyroWave Vulkan decoder initialization failed: timestamp buffers");
            release_locked();
            return false;
        }
        return true;
    }

    int submit_locked(const std::uint8_t *data, std::size_t length,
                      const std::uint8_t* frame_metadata, std::size_t metadata_length) {
        // A failed submission may still own GPU resources (including the LUT).
        // Only teardown/reinitialization may make this handle reusable.
        if (submit_failed) return -1;
        const auto frame_number = ++submitted_frames;
        PWV_TRACE("f%llu: >>> submit_locked enter (len=%zu)", static_cast<unsigned long long>(frame_number), length);
        const auto decode_start = std::chrono::steady_clock::now();
        if (!ready_for_submit()) return submit_failure("decoder-not-ready");
        if (data == nullptr || length == 0) return submit_failure("invalid-packet");

        // Claim the next slot in the ring. With depth > 1 the fence we wait on
        // belongs to a frame submitted `depth` frames ago, so the GPU stays fed
        // while this CPU-side code runs.
        auto &slot = slots[slot_index];
        const auto slot_wait_start = std::chrono::steady_clock::now();
        if (slot.fence_pending) {
            if (vkWaitForFences(device, 1, &slot.fence, VK_TRUE, kGpuWaitTimeoutNs) != VK_SUCCESS)
                return submit_failure("wait-slot-fence");
            if (vkResetFences(device, 1, &slot.fence) != VK_SUCCESS) return submit_failure("reset-slot-fence");
            slot.fence_pending = false;
            // Everything this slot owned has completed, so any convert_done
            // signal it carried has been consumed by the following frame.
            slot.convert_signalled = false;
        }
        const std::uint64_t slot_wait_us = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - slot_wait_start).count());
        PWV_TRACE("f%llu: -> api.push", static_cast<unsigned long long>(frame_number));
        if (api.push(decoder, data, length) != PYROWAVE_SUCCESS) {
            return submit_failure("push-packet", static_cast<int>(PYROWAVE_ERROR_GENERIC), length);
        }
        PWV_TRACE("f%llu: -> api.ready", static_cast<unsigned long long>(frame_number));
        if (!api.ready(decoder, false)) {
            api.clear_decoder(decoder);
            return PYROWAVE_SUBMIT_FRAME_DROPPED;
        }
        pyrowave_color_metadata metadata {};
        PWV_TRACE("f%llu: -> api.get_color_metadata", static_cast<unsigned long long>(frame_number));
        if (!api.get_color_metadata(decoder, &metadata)) return submit_failure("color-metadata", 0, length);
        const bool expected_hdr = hdr_mode != 0;
        const auto expected_transfer = hdr_mode == 2 ? PYROWAVE_TRANSFER_HLG : PYROWAVE_TRANSFER_PQ;
        if ((expected_hdr && (metadata.primaries != PYROWAVE_COLOR_PRIMARIES_BT2020 ||
                              metadata.transfer != expected_transfer ||
                              metadata.transform != PYROWAVE_YCBCR_BT2020)) ||
            (!expected_hdr && (metadata.primaries != PYROWAVE_COLOR_PRIMARIES_BT709 ||
                               metadata.transfer != PYROWAVE_TRANSFER_BT709 ||
                               metadata.transform != PYROWAVE_YCBCR_BT709)) ||
            metadata.range != (full_range ? PYROWAVE_YCBCR_FULL : PYROWAVE_YCBCR_LIMITED)) {
            LOGW("PyroWave color metadata mismatch (frame=%llu, hdr=%d, range=%d, primaries=%d, transfer=%d, transform=%d)",
                 static_cast<unsigned long long>(frame_number), hdr_mode, metadata.range,
                 metadata.primaries, metadata.transfer, metadata.transform);
            return submit_failure("color-metadata-mismatch", 0, length);
        }
        if (!update_dynamic_mapping(frame_metadata, metadata_length)) {
            return submit_failure("dynamic-hdr-metadata", 0, metadata_length);
        }
        pyrowave_gpu_buffers buffers{};
        for (std::size_t i = 0; i < 3; ++i) buffers.planes[i] = plane_views[i];
        pyrowave_gpu_sync_operation release_sync{
            .images = nullptr,
            .num_images = 0,
            .sync = { slot.decode_done, 0 },
        };
        // Every frame decodes into the same YUV planes, so the decoder must not
        // start writing them until the previous frame's conversion has finished
        // reading them. With depth == 1 the slot fence wait above already
        // guarantees that and no semaphore is needed.
        pyrowave_gpu_sync_operation acquire_sync{
            .images = nullptr,
            .num_images = 0,
            .sync = { VK_NULL_HANDLE, 0 },
        };
        if (frame_depth > 1) {
            auto &previous = slots[(slot_index + frame_depth - 1) % frame_depth];
            if (previous.convert_signalled) {
                acquire_sync.sync = { previous.convert_done, 0 };
                previous.convert_signalled = false;
            }
        }
        if (timestamp_pool != VK_NULL_HANDLE) {
            VkSubmitInfo stamp_submit{ .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1,
                                       .pCommandBuffers = &slot.timestamp_cb };
            vkQueueSubmit(queue, 1, &stamp_submit, VK_NULL_HANDLE);
        }
        PWV_TRACE("f%llu: -> api.decode_gpu (release_sem=%p acquire_sem=%p)", static_cast<unsigned long long>(frame_number),
                  reinterpret_cast<const void *>(slot.decode_done),
                  reinterpret_cast<const void *>(acquire_sync.sync.semaphore));
        const auto decode_result = api.decode_gpu(decoder, &acquire_sync, &release_sync, &buffers);
        if (decode_result != PYROWAVE_SUCCESS) {
            return submit_failure("decode-gpu", static_cast<int>(decode_result), length);
        }
        PWV_TRACE("f%llu: <- api.decode_gpu OK", static_cast<unsigned long long>(frame_number));
        decode_signal_pending = true;
        // The GPU completion is handed to the conversion submission through
        // slot.decode_done. Do not wait here: decode_end is the host
        // submission boundary, while the following interval includes the
        // semaphore wait, YUV conversion, and presentation submission.
        const auto decode_end = std::chrono::steady_clock::now();

        const auto present_start = decode_end;
        const auto t0 = present_start;
        std::uint32_t image_index = 0;
        PWV_TRACE("f%llu: -> vkAcquireNextImageKHR (acquire_sem=%p)", static_cast<unsigned long long>(frame_number),
                  reinterpret_cast<const void *>(slot.acquire));
        VkResult acquire = vkAcquireNextImageKHR(device, swapchain, kGpuWaitTimeoutNs, slot.acquire, VK_NULL_HANDLE, &image_index);
        PWV_TRACE("f%llu: <- vkAcquireNextImageKHR res=%d idx=%u", static_cast<unsigned long long>(frame_number),
                  static_cast<int>(acquire), image_index);
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
            return submit_failure_after_decode("acquire-image", acquire, length);
        }
        if (image_index >= swapchain_images.size()) {
            return submit_failure_after_decode("acquire-image-index", 0, length);
        }
        const auto t1 = std::chrono::steady_clock::now();

        VkDescriptorImageInfo source_infos[3]{};
        for (std::size_t i = 0; i < 3; ++i) source_infos[i] = { sampler, planes[i].view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
        VkDescriptorImageInfo destination{ VK_NULL_HANDLE, swapchain_views[image_index], VK_IMAGE_LAYOUT_GENERAL };
        VkWriteDescriptorSet writes[5]{};
        for (std::uint32_t i = 0; i < 3; ++i) {
            writes[i] = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = slot.descriptor_set,
                          .dstBinding = i, .descriptorCount = 1,
                          .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &source_infos[i] };
        }
        writes[3] = { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = slot.descriptor_set,
                      .dstBinding = 3, .descriptorCount = 1,
                      .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &destination };
        VkDescriptorBufferInfo mapping_info{mapping_buffer, 0, sizeof(moonlight::pyrowave::dynamic_hdr_lut)};
        writes[4] = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = slot.descriptor_set,
            .dstBinding = 4, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            .pBufferInfo = &mapping_info};
        vkUpdateDescriptorSets(device, hdr_mode != 0 ? 5U : 4U, writes, 0, nullptr);

        VkCommandBufferBeginInfo begin_info{ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                             .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
        const auto reset_command_result = vkResetCommandBuffer(slot.command_buffer, 0);
        if (reset_command_result != VK_SUCCESS) {
            return submit_failure_after_decode("reset-command-buffer", reset_command_result, length);
        }
        const auto begin_result = vkBeginCommandBuffer(slot.command_buffer, &begin_info);
        if (begin_result != VK_SUCCESS) {
            return submit_failure_after_decode("begin-command-buffer", begin_result, length);
        }
        if (timestamp_pool != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(slot.command_buffer, timestamp_pool, 0, 2);
            vkCmdWriteTimestamp(slot.command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, timestamp_pool, 0);
        }
        if (dynamic_hdr_format != DYNAMIC_HDR_FORMAT_NONE) {
            VkMemoryBarrier metadata_visibility{.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT, .dstAccessMask = VK_ACCESS_SHADER_READ_BIT};
            vkCmdPipelineBarrier(slot.command_buffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0, 1, &metadata_visibility, 0, nullptr, 0, nullptr);
        }
        std::array<VkImageMemoryBarrier, 4> barriers{};
        const VkAccessFlags plane_write_access = fragment_path
            ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            : VK_ACCESS_SHADER_WRITE_BIT;
        const VkPipelineStageFlags plane_write_stage = fragment_path
            ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        for (std::size_t i = 0; i < 3; ++i) {
            barriers[i] = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .srcAccessMask = plane_write_access,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT, .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = planes[i].image,
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        }
        barriers[3] = { .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = swapchain_images[image_index],
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        vkCmdPipelineBarrier(slot.command_buffer, plane_write_stage,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                             static_cast<std::uint32_t>(barriers.size()), barriers.data());
        vkCmdBindPipeline(slot.command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, conversion_pipeline);
        vkCmdBindDescriptorSets(slot.command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &slot.descriptor_set, 0, nullptr);
        const shader_constants constants{
            .scale_x = 1.0F,
            .scale_y = 1.0F,
            .full_range = full_range ? 1.0F : 0.0F,
            .reserved = dynamic_hdr_format != DYNAMIC_HDR_FORMAT_NONE ? 1.0F : 0.0F,
            .hdr_mode = static_cast<float>(hdr_mode),
            .source_peak_nits = hdr_mode == 2 ? dynamic_scene.hlg_nominal_peak_nits : 10000.0F,
            .target_peak_nits = target_peak_nits,
            .padding = 0.0F,
        };
        vkCmdPushConstants(slot.command_buffer, pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(constants), &constants);
        vkCmdDispatch(slot.command_buffer, (swapchain_extent.width + 7) / 8, (swapchain_extent.height + 7) / 8, 1);
        VkImageMemoryBarrier output_barrier{ .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT, .dstAccessMask = 0,
            .oldLayout = VK_IMAGE_LAYOUT_GENERAL, .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = swapchain_images[image_index], .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        vkCmdPipelineBarrier(slot.command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &output_barrier);
        for (std::size_t i = 0; i < 3; ++i) {
            barriers[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barriers[i].dstAccessMask = plane_write_access;
            barriers[i].oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        }
        vkCmdPipelineBarrier(slot.command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             plane_write_stage, 0, 0, nullptr, 0, nullptr, 3, barriers.data());
        if (timestamp_pool != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(slot.command_buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, timestamp_pool, 1);
        }
        const auto end_result = vkEndCommandBuffer(slot.command_buffer);
        if (end_result != VK_SUCCESS) {
            return submit_failure_after_decode("end-command-buffer", end_result, length);
        }
        const auto t2 = std::chrono::steady_clock::now();
        std::array<VkSemaphore, 2> wait_semaphores{ slot.decode_done, slot.acquire };
        std::array<VkPipelineStageFlags, 2> wait_stages{
            plane_write_stage | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            plane_write_stage | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        };
        // Signal convert_done so the next frame's decode knows the planes are
        // free again, and fence this slot so its command buffer can be recycled
        // `depth` frames later.
        VkSubmitInfo submit{ .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = static_cast<std::uint32_t>(wait_semaphores.size()),
            .pWaitSemaphores = wait_semaphores.data(), .pWaitDstStageMask = wait_stages.data(),
            .commandBufferCount = 1, .pCommandBuffers = &slot.command_buffer,
            .signalSemaphoreCount = 1, .pSignalSemaphores = &slot.convert_done };
        PWV_TRACE("f%llu: -> vkQueueSubmit conv (queue=%p waits={dec=%p,acq=%p} cmdCount=%u fence=%p)",
                  static_cast<unsigned long long>(frame_number),
                  reinterpret_cast<const void *>(queue),
                  reinterpret_cast<const void *>(slot.decode_done),
                  reinterpret_cast<const void *>(slot.acquire),
                  submit.commandBufferCount,
                  reinterpret_cast<const void *>(slot.fence));
        const auto queue_result = vkQueueSubmit(queue, 1, &submit, slot.fence);
        if (queue_result != VK_SUCCESS) {
            return submit_failure_after_decode("queue-submit", queue_result, length);
        }
        slot.fence_pending = true;
        slot.convert_signalled = true;
        // Advance the ring only after the submission succeeded, so a failed
        // frame does not leave a hole in the dependency chain.
        slot_index = (slot_index + 1) % frame_depth;
        const auto t3 = std::chrono::steady_clock::now();
        PWV_TRACE("f%llu: <- vkQueueSubmit conv OK (fence=%p)", static_cast<unsigned long long>(frame_number),
                  reinterpret_cast<const void *>(slot.fence));
        // The conversion submission now owns the wait on the decode semaphore.
        decode_signal_pending = false;
        // Deliberately no vkWaitForFences here: presentation is already ordered
        // after the conversion on the same queue, and the ring keeps the CPU
        // from getting more than `depth` frames ahead of the GPU.
        const auto t4 = std::chrono::steady_clock::now();

        if (dynamic_hdr_format != DYNAMIC_HDR_FORMAT_NONE && !apply_hdr_metadata()) {
            return submit_failure("dynamic-hdr-output-metadata", 0, metadata_length);
        }
        VkPresentInfoKHR present{ .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .swapchainCount = 1,
                                  .pSwapchains = &swapchain, .pImageIndices = &image_index };
        PWV_TRACE("f%llu: -> vkQueuePresentKHR (idx=%u)", static_cast<unsigned long long>(frame_number), image_index);
        const VkResult result = vkQueuePresentKHR(queue, &present);
        const auto t5 = std::chrono::steady_clock::now();
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            return submit_failure("present", result, length);
        }
        // Latency breakdown. Averaged over 60 frames and emitted once per window
        // so the per-call cost stays negligible.
        //   acquire  = waiting for a free swapchain image
        //   record   = descriptor update + command buffer recording
        //   submit   = vkQueueSubmit
        //   present  = vkQueuePresentKHR hand-off to the compositor
        //   cpu      = whole submit_locked wall time (the frame period when
        //              the decoder is fed as fast as packets arrive)
        //   slotWait = time this frame blocked waiting to recycle a ring slot,
        //              i.e. how far the CPU had run ahead of the GPU
        //   decodeGPU / convertGPU = pure GPU execution, from timestamps
        {
            static std::uint64_t perf_frames = 0;
            static double perf_acquire = 0, perf_record = 0, perf_submit = 0, perf_present = 0;
            static double perf_cpu = 0, perf_slot = 0;
            static double perf_convert_gpu = 0, perf_decode_gpu = 0;
            double convert_gpu_ms = 0.0, decode_gpu_ms = 0.0;
            if (timestamp_pool != VK_NULL_HANDLE) {
                std::uint64_t stamps[3]{};
                if (vkGetQueryPoolResults(device, timestamp_pool, 0, 3, sizeof(stamps), stamps,
                                          sizeof(stamps[0]), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS &&
                    stamps[1] > stamps[0]) {
                    convert_gpu_ms = static_cast<double>(stamps[1] - stamps[0]) * static_cast<double>(timestamp_period_ns) / 1.0e6;
                    // stamps[2] is written just before the decoder is invoked and
                    // stamps[0] is the first thing the conversion command buffer
                    // executes, so the gap is the decoder's GPU duration.
                    if (stamps[0] > stamps[2])
                        decode_gpu_ms = static_cast<double>(stamps[0] - stamps[2]) * static_cast<double>(timestamp_period_ns) / 1.0e6;
                }
            }
            const auto us = [](auto a, auto b) {
                return static_cast<double>(std::chrono::duration_cast<std::chrono::microseconds>(b - a).count());
            };
            perf_acquire += us(t0, t1);
            perf_record += us(t1, t2);
            perf_submit += us(t2, t3);
            perf_present += us(t4, t5);
            perf_cpu += us(decode_start, t5);
            perf_slot += static_cast<double>(slot_wait_us);
            perf_convert_gpu += convert_gpu_ms;
            perf_decode_gpu += decode_gpu_ms;
            ++perf_frames;
            if (perf_frames >= 60) {
                const auto n = static_cast<double>(perf_frames);
                LOGI("PWVPERF frame ms: cpu=%.2f slotWait=%.2f acquire=%.2f record=%.2f submit=%.2f present=%.2f | GPU decode=%.2f convert=%.2f",
                     perf_cpu / n / 1000.0, perf_slot / n / 1000.0,
                     perf_acquire / n / 1000.0, perf_record / n / 1000.0,
                     perf_submit / n / 1000.0, perf_present / n / 1000.0,
                     perf_decode_gpu / n, perf_convert_gpu / n);
                perf_frames = 0;
                perf_acquire = perf_record = perf_submit = perf_present = 0;
                perf_cpu = perf_slot = perf_convert_gpu = perf_decode_gpu = 0;
            }
        }

        PWV_TRACE("f%llu: <<< frame done OK (res=%d)", static_cast<unsigned long long>(frame_number), static_cast<int>(result));
        if (dynamic_hdr_format != DYNAMIC_HDR_FORMAT_NONE && !dynamic_hdr_logged) {
            LOGI("PyroWave dynamic HDR applied: format=%d presentation=application-mapped output=%s target_peak=%.1f",
                 dynamic_hdr_format, hdr_mode == 2 ? "HLG" : "PQ", target_peak_nits);
            dynamic_hdr_logged = true;
        }
        const auto present_end = std::chrono::steady_clock::now();
        last_decode_time_us = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(decode_end - decode_start).count());
        last_present_time_us = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(present_end - present_start).count());
        last_submit_failure = nullptr;
        return 0;
    }

    void release_locked() noexcept {
        if (device != VK_NULL_HANDLE) vkDeviceWaitIdle(device);
        if (api.destroy_decoder != nullptr && decoder != nullptr) {
            try {
                api.destroy_decoder(decoder);
            } catch (...) {
                LOGW("PyroWave decoder destruction raised an exception");
            }
        }
        decoder = nullptr;
        if (api.destroy_device != nullptr && pyrowave_device_handle != nullptr) {
            try {
                api.destroy_device(pyrowave_device_handle);
            } catch (...) {
                LOGW("PyroWave device destruction raised an exception");
            }
        }
        pyrowave_device_handle = nullptr;
        for (auto &plane : planes) destroy_image(device, plane);
        destroy_handle(device, sampler, vkDestroySampler);
        destroy_handle(device, descriptor_pool, vkDestroyDescriptorPool);
        destroy_handle(device, conversion_pipeline, vkDestroyPipeline);
        destroy_handle(device, pipeline_layout, vkDestroyPipelineLayout);
        destroy_handle(device, descriptor_layout, vkDestroyDescriptorSetLayout);
        destroy_handle(device, conversion_shader, vkDestroyShaderModule);
        if (device != VK_NULL_HANDLE && mapping_data != nullptr) vkUnmapMemory(device, mapping_memory);
        mapping_data = nullptr;
        destroy_handle(device, mapping_buffer, vkDestroyBuffer);
        if (device != VK_NULL_HANDLE && mapping_memory != VK_NULL_HANDLE) vkFreeMemory(device, mapping_memory, nullptr);
        mapping_memory = VK_NULL_HANDLE;
        dynamic_scene = {};
        dynamic_frame_applied = false;
        dynamic_hdr_logged = false;
        destroy_handle(device, drain_fence, vkDestroyFence);
        destroy_handle(device, timestamp_pool, vkDestroyQueryPool);
        timestamp_pool = VK_NULL_HANDLE;
        for (std::uint32_t i = 0; i < kMaxFrameDepth; ++i) {
            auto &slot = slots[i];
            destroy_handle(device, slot.acquire, vkDestroySemaphore);
            destroy_handle(device, slot.decode_done, vkDestroySemaphore);
            destroy_handle(device, slot.convert_done, vkDestroySemaphore);
            destroy_handle(device, slot.fence, vkDestroyFence);
            slot.fence_pending = false;
            slot.convert_signalled = false;
            slot.descriptor_set = VK_NULL_HANDLE;
            if (device != VK_NULL_HANDLE && command_pool != VK_NULL_HANDLE &&
                (slot.command_buffer != VK_NULL_HANDLE || slot.timestamp_cb != VK_NULL_HANDLE)) {
                std::array<VkCommandBuffer, 2> buffers{ slot.command_buffer, slot.timestamp_cb };
                vkFreeCommandBuffers(device, command_pool, 2, buffers.data());
            }
            slot.command_buffer = VK_NULL_HANDLE;
            slot.timestamp_cb = VK_NULL_HANDLE;
        }
        slot_index = 0;
        destroy_handle(device, command_pool, vkDestroyCommandPool);
        for (auto &view : swapchain_views) destroy_handle(device, view, vkDestroyImageView);
        swapchain_views.clear();
        if (device != VK_NULL_HANDLE && swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, nullptr);
        swapchain = VK_NULL_HANDLE;
        swapchain_images.clear();
        if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
        set_hdr_metadata = nullptr;
        if (instance != VK_NULL_HANDLE && surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
        if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
        physical_device = VK_NULL_HANDLE;
        queue = VK_NULL_HANDLE;
        queue_family = VK_QUEUE_FAMILY_IGNORED;
        last_submit_failure = nullptr;
        submit_failed = false;
        decode_signal_pending = false;
        submitted_frames = 0;
        last_decode_time_us = 0;
        last_present_time_us = 0;
        hdr_metadata_missing_logged = false;
        hdr_metadata_extension_missing_logged = false;
        if (window != nullptr) ANativeWindow_release(window);
        window = nullptr;
        api.unload();
    }
};

PyrowaveVulkanDecoder::PyrowaveVulkanDecoder(): impl_(std::make_unique<impl>()) {}
PyrowaveVulkanDecoder::~PyrowaveVulkanDecoder() { destroy(); }

bool PyrowaveVulkanDecoder::available(int width, int height, int hdrMode) {
    if (!valid_dimensions(width, height) || (hdrMode < 0 || hdrMode > 2)) {
        LOGW("PyroWave Vulkan preflight rejected dimensions or HDR mode: %dx%d hdr=%d", width, height, hdrMode);
        return false;
    }
    api_t api;
    if (!api.load()) {
        LOGW("PyroWave Vulkan preflight failed: runtime library unavailable");
        return false;
    }
    pyrowave_device device = nullptr;
    pyrowave_decoder decoder = nullptr;
    bool available = false;
    try {
        const auto device_result = api.create_default_device(&device);
        if (device_result == PYROWAVE_SUCCESS && device != nullptr) {
            const pyrowave_decoder_create_info info {
                .device = device,
                .width = width,
                .height = height,
                .chroma = PYROWAVE_CHROMA_SUBSAMPLING_420,
                .fragment_path = api.prefers_fragment_path(device),
            };
            available = api.create_decoder(&info, &decoder) == PYROWAVE_SUCCESS && decoder != nullptr;
            if (!available) {
                LOGW("PyroWave Vulkan preflight failed: decoder creation for %dx%d hdr=%d", width, height, hdrMode);
            }
        } else {
            LOGW("PyroWave Vulkan preflight failed: default device result=%d null=%d",
                 static_cast<int>(device_result), device == nullptr ? 1 : 0);
        }
    } catch (...) {
        LOGW("PyroWave Vulkan preflight failed: native exception");
        available = false;
    }
    if (decoder != nullptr) {
        try {
            api.destroy_decoder(decoder);
        } catch (...) {
            available = false;
        }
    }
    if (device != nullptr) {
        try {
            api.destroy_device(device);
        } catch (...) {
            available = false;
        }
    }
    return available;
}

bool PyrowaveVulkanDecoder::create(int width, int height, int hdrMode, bool fullRange) {
    if (!valid_dimensions(width, height) || (hdrMode < 0 || hdrMode > 2)) {
        LOGW("PyroWave Vulkan decoder rejected create request: %dx%d hdr=%d full_range=%d",
             width, height, hdrMode, fullRange ? 1 : 0);
        return false;
    }
    std::lock_guard<std::mutex> lock(impl_->mutex);
    ANativeWindow *previous_window = impl_->window;
    if (previous_window != nullptr) ANativeWindow_acquire(previous_window);
    impl_->release_locked();
    impl_->width = width;
    impl_->height = height;
    impl_->hdr_mode = hdrMode;
    impl_->full_range = fullRange;
    impl_->window = previous_window;
    if (!impl_->api.load()) {
        LOGW("PyroWave Vulkan decoder create failed: runtime library unavailable");
        impl_->release_locked();
        return false;
    }
    // Keep the negotiated dimensions until Java supplies the output Surface.
    if (impl_->window == nullptr) return true;
    const auto initialized = impl_->initialize_locked();
    if (!initialized) {
        LOGW("PyroWave Vulkan decoder create failed: initialization did not complete");
    }
    return initialized;
}

bool PyrowaveVulkanDecoder::setSurface(ANativeWindow* next) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const bool same_window = next == impl_->window;
    const bool same_geometry = same_window && next != nullptr &&
        impl_->swapchain_extent.width == static_cast<std::uint32_t>(std::max(0, ANativeWindow_getWidth(next))) &&
        impl_->swapchain_extent.height == static_cast<std::uint32_t>(std::max(0, ANativeWindow_getHeight(next)));
    if (same_window && same_geometry) return true;
    if (next != nullptr) ANativeWindow_acquire(next);
    impl_->release_locked();
    impl_->window = next;
    if (impl_->window == nullptr) return false;
    const auto initialized = impl_->initialize_locked();
    if (!initialized) {
        LOGW("PyroWave Vulkan decoder Surface bind failed: initialization did not complete");
    }
    return initialized;
}

bool PyrowaveVulkanDecoder::setHdrMetadata(bool enabled, const std::uint8_t* data, std::size_t length) {
    if (enabled && data == nullptr && length != 0) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    try {
        return impl_->set_hdr_metadata_snapshot(enabled, data, length);
    } catch (...) {
        LOGW("PyroWave Vulkan HDR metadata update raised an exception");
        return false;
    }
}

bool PyrowaveVulkanDecoder::setDynamicHdr(int format, float targetPeakNits) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    const bool pq = format == DYNAMIC_HDR_FORMAT_HDR10_PLUS || format == DYNAMIC_HDR_FORMAT_VIVID_PQ ||
                    format == DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_81;
    const bool hlg = format == DYNAMIC_HDR_FORMAT_VIVID_HLG || format == DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_84;
    if ((format != DYNAMIC_HDR_FORMAT_NONE && !pq && !hlg) ||
        (pq && impl_->hdr_mode != 1) || (hlg && impl_->hdr_mode != 2) ||
        !std::isfinite(targetPeakNits) || targetPeakNits < 1 || targetPeakNits > 10000) return false;
    impl_->dynamic_hdr_format = format;
    impl_->target_peak_nits = targetPeakNits;
    impl_->dynamic_scene = {};
    impl_->dynamic_frame_applied = false;
    impl_->dynamic_hdr_logged = false;
    return impl_->swapchain == VK_NULL_HANDLE || impl_->apply_hdr_metadata();
}

int PyrowaveVulkanDecoder::submit(const std::uint8_t* data, std::size_t length,
                                 const std::uint8_t* frameMetadata, std::size_t metadataLength) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    try { return impl_->submit_locked(data, length, frameMetadata, metadataLength); }
    catch (...) {
        return impl_->submit_failure("native-exception", 0, length);
    }
}

std::uint64_t PyrowaveVulkanDecoder::getLastTimingsPacked() {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return ((impl_->last_decode_time_us & 0xffffffffULL) << 32) |
           (impl_->last_present_time_us & 0xffffffffULL);
}

void PyrowaveVulkanDecoder::destroy() noexcept {
    if (!impl_) return;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->release_locked();
}

} // namespace FramegenPipeline
