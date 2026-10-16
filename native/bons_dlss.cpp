// Bons DLSS Diet native runtime (Windows x64): NVIDIA DLSS through NGX on Vulkan for an OpenGL renderer (Oculus).
//
// Called from the Minecraft render thread with the GL context current. One Vulkan device on the same GPU as the GL
// context (device + driver UUID match), four Vulkan images whose memory is imported into GL (EXT_memory_object), two
// exported binary semaphores (EXT_semaphore) for GPU-side synchronisation in both directions. Per frame:
//   GL  : ONE compute pass copies colour + depth + motion from the shader pack's textures into the shared images,
//         flipping them upright for DLSS (motion y negated); signal glDone
//   VK  : acquire (queue family EXTERNAL -> ours), NGX DLSS evaluate, release; wait glDone, signal vkDone
//   GL  : wait vkDone; ONE compute pass flips DLSS' output straight into the pack's colortex11
// No CPU waits in the frame path (the command-buffer ring only waits on a fence three frames old).
// Nothing here is derived from Super Resolution's code; the conventions match its shader-pack interface v3.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <jni.h>
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_WIN32_KHR
#include <vulkan/vulkan.h>
#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#define BONS_ABI 2
static const char *PROJECT_ID = "411e618c-c1f8-4e5d-a50e-e3a2ac4c7015";
static const char *ENGINE_VERSION = "1.1.0";

// ------------------------------------------------------------------------------------------------ log + errors

static std::mutex g_logMutex;
static std::deque<std::string> g_log;
static std::string g_error;

static void logLine(const std::string &s) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_log.size() >= 512) g_log.pop_front();
    g_log.push_back(s);
}

static void logf(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    logLine(buf);
}

static int fail(int code, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    {
        std::lock_guard<std::mutex> lock(g_logMutex);
        g_error = buf;
    }
    logLine(std::string("ERROR ") + buf);
    return code;
}

static void NVSDK_CONV ngxLog(const char *message, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature feature) {
    if (!message) return;
    std::string s(message);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    logLine("NGX[" + std::to_string((int) feature) + "] " + s);
}

// ------------------------------------------------------------------------------------------------ OpenGL

typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef int GLint;
typedef int GLsizei;
typedef unsigned char GLboolean;
typedef unsigned char GLubyte;
typedef char GLchar;
typedef unsigned int GLbitfield;
typedef uint64_t GLuint64;

enum : GLenum {
    GL_NO_ERROR_ = 0, GL_TEXTURE_2D_ = 0x0DE1,
    GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS_ = 0x8B4D, GL_MAX_IMAGE_UNITS_ = 0x8F38,
    GL_READ_ONLY_ = 0x88B8, GL_WRITE_ONLY_ = 0x88B9, GL_READ_WRITE_ = 0x88BA,
    GL_RGBA8_ = 0x8058, GL_R32F_ = 0x822E, GL_RG16F_ = 0x822F,
    GL_COMPUTE_SHADER_ = 0x91B9, GL_COMPILE_STATUS_ = 0x8B81, GL_LINK_STATUS_ = 0x8B82, GL_INFO_LOG_LENGTH_ = 0x8B84,
    GL_TEXTURE_MIN_FILTER_ = 0x2801, GL_TEXTURE_MAG_FILTER_ = 0x2800, GL_NEAREST_ = 0x2600,
    GL_TEXTURE_WIDTH_ = 0x1000, GL_TEXTURE_HEIGHT_ = 0x1001, GL_TEXTURE_INTERNAL_FORMAT_ = 0x1003,
    GL_VENDOR_ = 0x1F00, GL_RENDERER_ = 0x1F01, GL_VERSION_ = 0x1F02, GL_EXTENSIONS_ = 0x1F03,
    GL_NUM_EXTENSIONS_ = 0x821D, GL_MAJOR_VERSION_ = 0x821B, GL_MINOR_VERSION_ = 0x821C,
    GL_TIMESTAMP_ = 0x8E28, GL_QUERY_RESULT_ = 0x8866, GL_QUERY_RESULT_AVAILABLE_ = 0x8867,
    GL_DEBUG_SOURCE_APPLICATION_ = 0x824A,
    // EXT_memory_object(_win32), EXT_semaphore
    GL_TEXTURE_TILING_EXT_ = 0x9580, GL_DEDICATED_MEMORY_OBJECT_EXT_ = 0x9581, GL_OPTIMAL_TILING_EXT_ = 0x9584,
    GL_HANDLE_TYPE_OPAQUE_WIN32_EXT_ = 0x9587, GL_NUM_DEVICE_UUIDS_EXT_ = 0x9596, GL_DEVICE_UUID_EXT_ = 0x9597,
    GL_DRIVER_UUID_EXT_ = 0x9598, GL_LAYOUT_GENERAL_EXT_ = 0x958D, GL_LAYOUT_SHADER_READ_ONLY_EXT_ = 0x9591,
};
static const GLbitfield GL_ALL_BARRIER_BITS_ = 0xFFFFFFFFu;
static const GLbitfield GL_TEXTURE_FETCH_BARRIER_BIT_ = 0x8, GL_SHADER_IMAGE_ACCESS_BARRIER_BIT_ = 0x20,
                        GL_FRAMEBUFFER_BARRIER_BIT_ = 0x400, GL_TEXTURE_UPDATE_BARRIER_BIT_ = 0x100;

#define GL_REQUIRED(X) \
    X(void, glGetIntegerv, (GLenum, GLint *)) \
    X(GLenum, glGetError, (void)) \
    X(void, glFlush, (void)) \
    X(void, glFinish, (void)) \
    X(const GLubyte *, glGetString, (GLenum)) \
    X(const GLubyte *, glGetStringi, (GLenum, GLuint)) \
    X(void, glDeleteTextures, (GLsizei, const GLuint *)) \
    X(void, glBindTextureUnit, (GLuint, GLuint)) \
    X(void, glBindImageTexture, (GLuint, GLuint, GLint, GLboolean, GLint, GLenum, GLenum)) \
    X(void, glDispatchCompute, (GLuint, GLuint, GLuint)) \
    X(void, glMemoryBarrier, (GLbitfield)) \
    X(GLuint, glCreateShader, (GLenum)) \
    X(void, glShaderSource, (GLuint, GLsizei, const GLchar *const *, const GLint *)) \
    X(void, glCompileShader, (GLuint)) \
    X(void, glGetShaderiv, (GLuint, GLenum, GLint *)) \
    X(void, glGetShaderInfoLog, (GLuint, GLsizei, GLsizei *, GLchar *)) \
    X(void, glDeleteShader, (GLuint)) \
    X(GLuint, glCreateProgram, (void)) \
    X(void, glAttachShader, (GLuint, GLuint)) \
    X(void, glLinkProgram, (GLuint)) \
    X(void, glGetProgramiv, (GLuint, GLenum, GLint *)) \
    X(void, glGetProgramInfoLog, (GLuint, GLsizei, GLsizei *, GLchar *)) \
    X(void, glDeleteProgram, (GLuint)) \
    X(void, glUseProgram, (GLuint)) \
    X(GLint, glGetUniformLocation, (GLuint, const GLchar *)) \
    X(void, glProgramUniform2i, (GLuint, GLint, GLint, GLint)) \
    X(void, glProgramUniform1i, (GLuint, GLint, GLint)) \
    X(void, glProgramUniform2f, (GLuint, GLint, float, float)) \
    X(void, glProgramUniform4f, (GLuint, GLint, float, float, float, float)) \
    X(void, glCreateTextures, (GLenum, GLsizei, GLuint *)) \
    X(void, glTextureParameteri, (GLuint, GLenum, GLint)) \
    X(void, glGetTextureLevelParameteriv, (GLuint, GLint, GLenum, GLint *)) \
    X(void, glGetUnsignedBytevEXT, (GLenum, GLubyte *)) \
    X(void, glGetUnsignedBytei_vEXT, (GLenum, GLuint, GLubyte *)) \
    X(void, glCreateMemoryObjectsEXT, (GLsizei, GLuint *)) \
    X(void, glDeleteMemoryObjectsEXT, (GLsizei, const GLuint *)) \
    X(void, glMemoryObjectParameterivEXT, (GLuint, GLenum, const GLint *)) \
    X(void, glImportMemoryWin32HandleEXT, (GLuint, GLuint64, GLenum, void *)) \
    X(void, glTextureStorageMem2DEXT, (GLuint, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64)) \
    X(void, glGenSemaphoresEXT, (GLsizei, GLuint *)) \
    X(void, glDeleteSemaphoresEXT, (GLsizei, const GLuint *)) \
    X(void, glImportSemaphoreWin32HandleEXT, (GLuint, GLenum, void *)) \
    X(void, glSignalSemaphoreEXT, (GLuint, GLuint, const GLuint *, GLuint, const GLuint *, const GLenum *)) \
    X(void, glWaitSemaphoreEXT, (GLuint, GLuint, const GLuint *, GLuint, const GLuint *, const GLenum *)) \
    X(void, glGenQueries, (GLsizei, GLuint *)) \
    X(void, glDeleteQueries, (GLsizei, const GLuint *)) \
    X(void, glQueryCounter, (GLuint, GLenum)) \
    X(void, glGetQueryObjectiv, (GLuint, GLenum, GLint *)) \
    X(void, glGetQueryObjectui64v, (GLuint, GLenum, GLuint64 *))
#define GL_OPTIONAL(X) \
    X(void, glPushDebugGroup, (GLenum, GLuint, GLsizei, const GLchar *)) \
    X(void, glPopDebugGroup, (void))

struct Gl {
#define X(ret, name, args) ret(WINAPI *name) args = nullptr;
    GL_REQUIRED(X)
    GL_OPTIONAL(X)
#undef X
};
static Gl gl;

static void *glProc(const char *name) {
    PROC p = wglGetProcAddress(name);
    intptr_t v = reinterpret_cast<intptr_t>(p);
    if (!p || v == 1 || v == 2 || v == 3 || v == -1) {
        HMODULE lib = GetModuleHandleW(L"opengl32.dll");
        p = lib ? GetProcAddress(lib, name) : nullptr;
    }
    void *out;
    std::memcpy(&out, &p, sizeof(out));
    return out;
}

// Errors queued by other code (Minecraft, Oculus, mods) must not be blamed on our checks. Bounded: a lost context can
// report errors forever.
static void clearGlErrors() {
    for (int i = 0; i < 32 && gl.glGetError() != GL_NO_ERROR_; ++i) {
    }
}

static int loadGl() {
    if (!wglGetCurrentContext()) return fail(10, "no current OpenGL context on this thread");
    std::string missing;
#define X(ret, name, args) \
    { void *p = glProc(#name); std::memcpy(&gl.name, &p, sizeof(p)); if (!gl.name) missing += std::string(" ") + #name; }
    GL_REQUIRED(X)
#undef X
#define X(ret, name, args) { void *p = glProc(#name); std::memcpy(&gl.name, &p, sizeof(p)); }
    GL_OPTIONAL(X)
#undef X
    if (!missing.empty()) return fail(11, "OpenGL functions missing:%s", missing.c_str());
    GLint major = 0, minor = 0;
    gl.glGetIntegerv(GL_MAJOR_VERSION_, &major);
    gl.glGetIntegerv(GL_MINOR_VERSION_, &minor);
    if (major * 10 + minor < 45) return fail(12, "OpenGL 4.5 needed, context is %d.%d", major, minor);
    const char *need[] = {"GL_EXT_memory_object", "GL_EXT_memory_object_win32", "GL_EXT_semaphore", "GL_EXT_semaphore_win32"};
    bool have[4] = {};
    GLint n = 0;
    gl.glGetIntegerv(GL_NUM_EXTENSIONS_, &n);
    for (GLint i = 0; i < n; ++i) {
        const char *e = reinterpret_cast<const char *>(gl.glGetStringi(GL_EXTENSIONS_, (GLuint) i));
        if (!e) continue;
        for (int k = 0; k < 4; ++k)
            if (!std::strcmp(e, need[k])) have[k] = true;
    }
    for (int k = 0; k < 4; ++k)
        if (!have[k]) return fail(13, "OpenGL extension %s missing", need[k]);
    return 0;
}

// ------------------------------------------------------------------------------------------------ Vulkan loader

struct Vk {
    HMODULE lib = nullptr;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr = nullptr;
    PFN_vkCreateInstance CreateInstance = nullptr;
    PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties = nullptr;
    PFN_vkEnumerateInstanceVersion EnumerateInstanceVersion = nullptr;
    // instance level
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceProperties2 GetPhysicalDeviceProperties2 = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice CreateDevice = nullptr;
    // device level
    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkGetDeviceQueue GetDeviceQueue = nullptr;
    PFN_vkCreateImage CreateImage = nullptr;
    PFN_vkDestroyImage DestroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory AllocateMemory = nullptr;
    PFN_vkFreeMemory FreeMemory = nullptr;
    PFN_vkBindImageMemory BindImageMemory = nullptr;
    PFN_vkCreateImageView CreateImageView = nullptr;
    PFN_vkDestroyImageView DestroyImageView = nullptr;
    PFN_vkGetMemoryWin32HandleKHR GetMemoryWin32HandleKHR = nullptr;
    PFN_vkCreateSemaphore CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore DestroySemaphore = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR GetSemaphoreWin32HandleKHR = nullptr;
    PFN_vkCreateCommandPool CreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool DestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer EndCommandBuffer = nullptr;
    PFN_vkResetCommandBuffer ResetCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier = nullptr;
    PFN_vkQueueSubmit QueueSubmit = nullptr;
    PFN_vkQueueWaitIdle QueueWaitIdle = nullptr;
    PFN_vkDeviceWaitIdle DeviceWaitIdle = nullptr;
    PFN_vkCreateFence CreateFence = nullptr;
    PFN_vkDestroyFence DestroyFence = nullptr;
    PFN_vkWaitForFences WaitForFences = nullptr;
    PFN_vkResetFences ResetFences = nullptr;
    PFN_vkCreateQueryPool CreateQueryPool = nullptr;
    PFN_vkDestroyQueryPool DestroyQueryPool = nullptr;
    PFN_vkCmdResetQueryPool CmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp CmdWriteTimestamp = nullptr;
    PFN_vkGetQueryPoolResults GetQueryPoolResults = nullptr;
};
static Vk vk;

#define VK_LOAD_I(inst, name) vk.name = (PFN_vk##name) vk.GetInstanceProcAddr(inst, "vk" #name)
#define VK_LOAD_D(dev, name) vk.name = (PFN_vk##name) vk.GetDeviceProcAddr(dev, "vk" #name)

// ------------------------------------------------------------------------------------------------ state

static const int RING = 3;      // frames in flight for command buffers / queries
static const uint64_t TIMING_EVERY = 16;   // with the timing log on: GPU timestamps on one frame in 16
static const int IMAGE_COUNT = 4;
enum { IMG_COLOR = 0, IMG_DEPTH = 1, IMG_MOTION = 2, IMG_OUTPUT = 3 };

struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    GLenum glFormat = 0;
    uint32_t width = 0, height = 0;
    GLuint glMemory = 0;
    GLuint glTexture = 0;
};

struct Runtime {
    bool ready = false;          // init succeeded
    HGLRC glContext = nullptr;
    DWORD renderThread = 0;
    std::wstring dataDir, dlssDir;
    int quality = NVSDK_NGX_PerfQuality_Value_MaxQuality;
    int preset = NVSDK_NGX_DLSS_Hint_Render_Preset_K;
    // timing: GL + Vulkan timestamp queries (only while the mod logs timings; each GL timestamp waits for the GPU to idle)
    // lean: hand-off like Super Resolution's: the shared images stay in GENERAL, no per-frame Vulkan barriers, GL releases
    //       only the inputs and acquires only the output. strict (default): queue-family ownership transfers every frame.
    bool timing = true;
    bool lean = false;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkQueue queue = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memProps = {};
    float timestampPeriod = 1.0f;
    bool timestamps = false;
    char gpuName[256] = {};
    uint32_t driverVersion = 0;

    bool ngxReady = false;
    NVSDK_NGX_Parameter *params = nullptr;
    NVSDK_NGX_Handle *feature = nullptr;

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd[RING] = {};
    VkFence fence[RING] = {};
    bool submitted[RING] = {};
    VkQueryPool vkQueries = VK_NULL_HANDLE;

    VkSemaphore semGlDone = VK_NULL_HANDLE, semVkDone = VK_NULL_HANDLE;
    GLuint glSemGlDone = 0, glSemVkDone = 0;

    SharedImage img[IMAGE_COUNT];
    uint32_t renderW = 0, renderH = 0, outW = 0, outH = 0;

    GLuint progIn = 0, progOut = 0;
    GLint progInSize = -1, progOutSize = -1, progInUseDh = -1, progInProj = -1, progInUnified = -1;
    GLuint texBase = 0, imgBase = 0;   // first of our TEX_UNITS texture units / IMG_UNITS image units (the top ones)
    // last values set on our programs' uniforms (set again only when they change)
    GLint inSize[2] = {-1, -1}, outSize[2] = {-1, -1}, inUseDh = -1;
    float inProj[4] = {}, inUnified[2] = {};
    GLuint glQueries[RING * 4] = {};
    bool glQueriesIssued[RING] = {};
    bool vkTimed[RING] = {};

    uint64_t frame = 0;
    // timing accumulators (ns), drained by nStats; gap = GL timeline from the end of the input pass to the end of the
    // wait for DLSS (signal + both hand-offs + the Vulkan submission, barriers included)
    double accIn = 0, accDlss = 0, accOut = 0, accGap = 0;
    uint64_t accFrames = 0, accDlssFrames = 0;
    // render-thread (CPU) time of dispatchFrame, ns: whole call, and its Vulkan record + submit part
    double accCpu = 0, accCpuVk = 0, maxCpu = 0;
    uint64_t accCpuFrames = 0;
};

static double qpcNs() {
    static double toNs = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return 1e9 / (double) f.QuadPart;
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return (double) c.QuadPart * toNs;
}
static Runtime rt;

// ------------------------------------------------------------------------------------------------ helpers

static bool runBigStack(const std::function<void()> &fn) {
    // NGX initialisation is stack hungry; the Java render thread's stack is small. Run it on a 16 MB stack.
    struct Box { const std::function<void()> *fn; };
    Box box{&fn};
    HANDLE t = CreateThread(nullptr, 16u << 20, [](LPVOID p) -> DWORD {
        (*static_cast<Box *>(p)->fn)();
        return 0;
    }, &box, STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
    if (!t) return false;
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
    return true;
}

static std::string vkResultName(VkResult r) {
    char buf[32];
    snprintf(buf, sizeof(buf), "VkResult %d", (int) r);
    return buf;
}

static bool hasExt(const std::vector<VkExtensionProperties> &list, const char *name) {
    for (auto &e : list)
        if (!std::strcmp(e.extensionName, name)) return true;
    return false;
}

static void addUnique(std::vector<const char *> &list, const char *name) {
    for (auto *n : list)
        if (!std::strcmp(n, name)) return;
    list.push_back(name);
}

static NVSDK_NGX_FeatureCommonInfo featureInfo() {
    static const wchar_t *paths[1];
    paths[0] = rt.dlssDir.c_str();
    NVSDK_NGX_FeatureCommonInfo info = {};
    info.PathListInfo.Path = paths;
    info.PathListInfo.Length = 1;
    info.LoggingInfo.LoggingCallback = ngxLog;
    info.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_ON;
    info.LoggingInfo.DisableOtherLoggingSinks = false;
    return info;
}

static NVSDK_NGX_FeatureDiscoveryInfo discoveryInfo(const NVSDK_NGX_FeatureCommonInfo *common) {
    NVSDK_NGX_FeatureDiscoveryInfo d = {};
    d.SDKVersion = NVSDK_NGX_Version_API;
    d.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    d.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    d.Identifier.v.ProjectDesc.ProjectId = PROJECT_ID;
    d.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
    d.Identifier.v.ProjectDesc.EngineVersion = ENGINE_VERSION;
    d.ApplicationDataPath = rt.dataDir.c_str();
    d.FeatureInfo = common;
    return d;
}

// ------------------------------------------------------------------------------------------------ GL programs

static const char *SHADER_IN = R"GLSL(#version 430 core
// Bons DLSS Diet: shader pack -> DLSS inputs in one pass. GL keeps row 0 at the bottom, DLSS wants it at the top.
layout(local_size_x = 16, local_size_y = 16) in;
// texture / image units: set once from the host (the top units, see buildPrograms), never units the game uses
uniform sampler2D uColor;   // colortex3, render size, LDR
uniform sampler2D uDepth;   // depthtex0, screen size: the scene fills its lower-left render-size corner
uniform sampler2D uMotion;  // colortex12, render size, previous uv - current uv
uniform sampler2D uDhDepth; // Distant Horizons' depth (screen size, lower-left corner), if any
layout(rgba8) uniform writeonly image2D oColor;
layout(r32f) uniform writeonly image2D oDepth;
layout(rg16f) uniform writeonly image2D oMotion;
uniform ivec2 uSize;
uniform int uUseDh;      // 1: give DLSS one depth for the world AND the LODs (otherwise LODs look like sky to it)
uniform vec4 uProj;      // main projection m22, m32; DH projection m22, m32 (OpenGL, column-major names)
uniform vec2 uUnified;   // near (main) and far (DH) of the combined perspective depth
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uSize.x || p.y >= uSize.y) return;
    ivec2 s = ivec2(p.x, uSize.y - 1 - p.y);
    imageStore(oColor, p, texelFetch(uColor, s, 0));
    float d = texelFetch(uDepth, s, 0).r;
    if (uUseDh != 0) {
        // view distance from whichever depth holds geometry, then one perspective depth over near .. DH far:
        // for nearby blocks practically the main depth, and LOD terrain keeps its order instead of reading as sky
        float dist = -1.0;
        if (d < 1.0) {
            dist = uProj.y / (d * 2.0 - 1.0 + uProj.x);
        } else {
            float dh = texelFetch(uDhDepth, s, 0).r;
            if (dh < 1.0) dist = uProj.w / (dh * 2.0 - 1.0 + uProj.z);
        }
        d = dist > 0.0 ? clamp(uUnified.y / (uUnified.y - uUnified.x) * (1.0 - uUnified.x / dist), 0.0, 1.0) : 1.0;
    }
    imageStore(oDepth, p, vec4(d, 0.0, 0.0, 0.0));
    vec2 mv = texelFetch(uMotion, s, 0).rg;
    imageStore(oMotion, p, vec4(mv.x, -mv.y, 0.0, 0.0));
}
)GLSL";

static const char *SHADER_OUT = R"GLSL(#version 430 core
// Bons DLSS Diet: DLSS output (row 0 at the top) -> the pack's colortex11 (row 0 at the bottom), one pass.
layout(local_size_x = 16, local_size_y = 16) in;
uniform sampler2D uUpscaled;
layout(rgba8) uniform writeonly image2D oTarget;
uniform ivec2 uSize;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uSize.x || p.y >= uSize.y) return;
    imageStore(oTarget, p, texelFetch(uUpscaled, ivec2(p.x, uSize.y - 1 - p.y), 0));
}
)GLSL";

static GLuint buildProgram(const char *source, const char *what) {
    GLuint sh = gl.glCreateShader(GL_COMPUTE_SHADER_);
    gl.glShaderSource(sh, 1, &source, nullptr);
    gl.glCompileShader(sh);
    GLint ok = 0;
    gl.glGetShaderiv(sh, GL_COMPILE_STATUS_, &ok);
    if (!ok) {
        char log[2048] = {};
        gl.glGetShaderInfoLog(sh, sizeof(log) - 1, nullptr, log);
        gl.glDeleteShader(sh);
        fail(45, "%s compute shader failed to compile: %s", what, log);
        return 0;
    }
    GLuint prog = gl.glCreateProgram();
    gl.glAttachShader(prog, sh);
    gl.glLinkProgram(prog);
    gl.glDeleteShader(sh);
    gl.glGetProgramiv(prog, GL_LINK_STATUS_, &ok);
    if (!ok) {
        char log[2048] = {};
        gl.glGetProgramInfoLog(prog, sizeof(log) - 1, nullptr, log);
        gl.glDeleteProgram(prog);
        fail(46, "%s compute program failed to link: %s", what, log);
        return 0;
    }
    return prog;
}

// GL state, without reading it back (no glGet* per frame: under NVIDIA's threaded OpenGL driver every query makes the
// render thread wait for the driver thread). Our passes bind textures to the TOP texture units and images to the top
// image units: Minecraft uses units 0-11 and Oculus assigns sampler units upward from there (it skips a bind when
// GlStateManager's cache already holds the texture, so we must never touch a unit it uses; it never reaches the top),
// and every Oculus program re-binds its images on use. Texture binds are DSA (glBindTextureUnit), so the active texture
// unit is never changed. The program we leave bound is replaced at once: our dispatch runs inside Oculus' renderAll,
// right before composite6's Program.use().
static const int TEX_UNITS = 4, IMG_UNITS = 3;

static void bindTex(int i, GLuint tex) { gl.glBindTextureUnit(rt.texBase + (GLuint) i, tex); }

static void bindImage(int i, GLuint tex, GLenum format) {
    gl.glBindImageTexture(rt.imgBase + (GLuint) i, tex, 0, 0, 0, GL_WRITE_ONLY_, format);
}

// ------------------------------------------------------------------------------------------------ Vulkan setup

static int createVulkan(const GLubyte glDeviceUuid[16][VK_UUID_SIZE], int glUuidCount, const GLubyte glDriverUuid[VK_UUID_SIZE]) {
    vk.lib = LoadLibraryW(L"vulkan-1.dll");
    if (!vk.lib) return fail(20, "vulkan-1.dll not found (no Vulkan driver)");
    vk.GetInstanceProcAddr = (PFN_vkGetInstanceProcAddr) GetProcAddress(vk.lib, "vkGetInstanceProcAddr");
    if (!vk.GetInstanceProcAddr) return fail(21, "vkGetInstanceProcAddr missing");
    VK_LOAD_I(VK_NULL_HANDLE, CreateInstance);
    VK_LOAD_I(VK_NULL_HANDLE, EnumerateInstanceExtensionProperties);
    VK_LOAD_I(VK_NULL_HANDLE, EnumerateInstanceVersion);
    if (!vk.CreateInstance || !vk.EnumerateInstanceExtensionProperties) return fail(21, "Vulkan loader incomplete");
    uint32_t apiVersion = VK_API_VERSION_1_0;
    if (vk.EnumerateInstanceVersion) vk.EnumerateInstanceVersion(&apiVersion);
    if (apiVersion < VK_API_VERSION_1_2) return fail(22, "Vulkan 1.2 needed, loader offers %u.%u",
            VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion));

    // instance extensions: what NGX asks for, plus external memory / semaphore capabilities (core in 1.1)
    uint32_t n = 0;
    vk.EnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> available(n);
    vk.EnumerateInstanceExtensionProperties(nullptr, &n, available.data());
    std::vector<const char *> instExts;
    NVSDK_NGX_FeatureCommonInfo common = featureInfo();
    NVSDK_NGX_FeatureDiscoveryInfo disc = discoveryInfo(&common);
    uint32_t ngxCount = 0;
    VkExtensionProperties *ngxExts = nullptr;
    NVSDK_NGX_Result nr = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&disc, &ngxCount, &ngxExts);
    if (NVSDK_NGX_FAILED(nr)) return fail(23, "NGX instance extension query failed (0x%08x)", (unsigned) nr);
    std::vector<std::string> keep;   // NGX owns its array; keep our own copies of the names
    keep.reserve(ngxCount + 4);
    for (uint32_t i = 0; i < ngxCount; ++i) {
        if (!hasExt(available, ngxExts[i].extensionName))
            return fail(24, "Vulkan instance extension %s (needed by DLSS) missing", ngxExts[i].extensionName);
        keep.emplace_back(ngxExts[i].extensionName);
    }
    for (auto &k : keep) addUnique(instExts, k.c_str());

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Bons DLSS Diet";
    app.applicationVersion = VK_MAKE_VERSION(1, 1, 0);
    app.pEngineName = "Minecraft";
    app.engineVersion = VK_MAKE_VERSION(1, 20, 1);
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = (uint32_t) instExts.size();
    ici.ppEnabledExtensionNames = instExts.data();
    VkResult r = vk.CreateInstance(&ici, nullptr, &rt.instance);
    if (r != VK_SUCCESS) return fail(25, "vkCreateInstance failed (%s)", vkResultName(r).c_str());
    VK_LOAD_I(rt.instance, DestroyInstance);
    VK_LOAD_I(rt.instance, EnumeratePhysicalDevices);
    VK_LOAD_I(rt.instance, GetPhysicalDeviceProperties);
    VK_LOAD_I(rt.instance, GetPhysicalDeviceProperties2);
    VK_LOAD_I(rt.instance, GetPhysicalDeviceFeatures2);
    VK_LOAD_I(rt.instance, GetPhysicalDeviceQueueFamilyProperties);
    VK_LOAD_I(rt.instance, GetPhysicalDeviceMemoryProperties);
    VK_LOAD_I(rt.instance, EnumerateDeviceExtensionProperties);
    VK_LOAD_I(rt.instance, CreateDevice);
    VK_LOAD_I(rt.instance, GetDeviceProcAddr);
    if (!vk.EnumeratePhysicalDevices || !vk.GetPhysicalDeviceProperties2 || !vk.CreateDevice || !vk.GetDeviceProcAddr)
        return fail(26, "Vulkan instance functions missing");

    // the physical device OpenGL renders on: same device UUID and same driver UUID (EXT_external_objects rule)
    uint32_t count = 0;
    vk.EnumeratePhysicalDevices(rt.instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vk.EnumeratePhysicalDevices(rt.instance, &count, devices.data());
    for (VkPhysicalDevice pd : devices) {
        VkPhysicalDeviceIDProperties ids = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 props = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props.pNext = &ids;
        vk.GetPhysicalDeviceProperties2(pd, &props);
        bool deviceMatch = false;
        for (int i = 0; i < glUuidCount; ++i)
            if (!std::memcmp(ids.deviceUUID, glDeviceUuid[i], VK_UUID_SIZE)) deviceMatch = true;
        bool driverMatch = !std::memcmp(ids.driverUUID, glDriverUuid, VK_UUID_SIZE);
        logf("Vulkan device \"%s\" vendor 0x%04x: device UUID %s OpenGL's, driver UUID %s", props.properties.deviceName,
                props.properties.vendorID, deviceMatch ? "matches" : "differs from", driverMatch ? "matches" : "differs");
        if (deviceMatch && driverMatch && !rt.physical) {
            rt.physical = pd;
            std::snprintf(rt.gpuName, sizeof(rt.gpuName), "%s", props.properties.deviceName);
            rt.driverVersion = props.properties.driverVersion;
            rt.timestampPeriod = props.properties.limits.timestampPeriod;
            rt.timestamps = props.properties.limits.timestampComputeAndGraphics == VK_TRUE;
        }
    }
    if (!rt.physical) return fail(27, "no Vulkan device shares memory with the OpenGL context (UUID mismatch)");

    NVSDK_NGX_FeatureRequirement req = {};
    nr = NVSDK_NGX_VULKAN_GetFeatureRequirements(rt.instance, rt.physical, &disc, &req);
    if (NVSDK_NGX_FAILED(nr)) return fail(28, "NGX feature requirement query failed (0x%08x)", (unsigned) nr);
    if (req.FeatureSupported != NVSDK_NGX_FeatureSupportResult_Supported)
        return fail(29, "DLSS not supported on %s (support mask %d, minimum GPU architecture 0x%x, minimum OS %s)",
                rt.gpuName, (int) req.FeatureSupported, req.MinHWArchitecture, req.MinOSVersion);

    // queue family with graphics + compute (NGX records compute work; graphics family is the safe superset)
    vk.GetPhysicalDeviceQueueFamilyProperties(rt.physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vk.GetPhysicalDeviceQueueFamilyProperties(rt.physical, &count, families.data());
    bool found = false;
    for (uint32_t i = 0; i < count && !found; ++i)
        if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            rt.family = i;
            found = true;
            if (families[i].timestampValidBits == 0) rt.timestamps = false;
        }
    if (!found) return fail(30, "no graphics+compute queue family");

    // device extensions: NGX's list + Win32 external memory and semaphores
    vk.EnumerateDeviceExtensionProperties(rt.physical, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> devAvailable(n);
    vk.EnumerateDeviceExtensionProperties(rt.physical, nullptr, &n, devAvailable.data());
    ngxCount = 0;
    ngxExts = nullptr;
    nr = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(rt.instance, rt.physical, &disc, &ngxCount, &ngxExts);
    if (NVSDK_NGX_FAILED(nr)) return fail(31, "NGX device extension query failed (0x%08x)", (unsigned) nr);
    std::vector<std::string> devKeep;
    devKeep.reserve(ngxCount + 4);
    for (uint32_t i = 0; i < ngxCount; ++i) {
        if (!hasExt(devAvailable, ngxExts[i].extensionName))
            return fail(32, "Vulkan device extension %s (needed by DLSS) missing", ngxExts[i].extensionName);
        devKeep.emplace_back(ngxExts[i].extensionName);
    }
    for (const char *e : {VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME, VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME}) {
        if (!hasExt(devAvailable, e)) return fail(33, "Vulkan device extension %s missing", e);
        devKeep.emplace_back(e);
    }
    std::vector<const char *> devExts;
    std::string extList;
    for (auto &k : devKeep) {
        addUnique(devExts, k.c_str());
        extList += " " + k;
    }
    logf("Vulkan device extensions:%s", extList.c_str());

    // features NGX's DLSS may rely on, enabled when the device has them
    VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &f12;
    vk.GetPhysicalDeviceFeatures2(rt.physical, &f2);
    VkPhysicalDeviceVulkan12Features e12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    e12.bufferDeviceAddress = f12.bufferDeviceAddress;
    e12.timelineSemaphore = f12.timelineSemaphore;
    e12.descriptorIndexing = f12.descriptorIndexing;
    e12.shaderFloat16 = f12.shaderFloat16;
    VkPhysicalDeviceFeatures2 e2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    e2.pNext = &e12;
    e2.features.shaderInt16 = f2.features.shaderInt16;
    e2.features.shaderInt64 = f2.features.shaderInt64;
    e2.features.shaderStorageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat;
    e2.features.shaderStorageImageReadWithoutFormat = f2.features.shaderStorageImageReadWithoutFormat;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = rt.family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &e2;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t) devExts.size();
    dci.ppEnabledExtensionNames = devExts.data();
    r = vk.CreateDevice(rt.physical, &dci, nullptr, &rt.device);
    if (r != VK_SUCCESS) return fail(34, "vkCreateDevice failed (%s)", vkResultName(r).c_str());

    VK_LOAD_D(rt.device, DestroyDevice);
    VK_LOAD_D(rt.device, GetDeviceQueue);
    VK_LOAD_D(rt.device, CreateImage);
    VK_LOAD_D(rt.device, DestroyImage);
    VK_LOAD_D(rt.device, GetImageMemoryRequirements);
    VK_LOAD_D(rt.device, AllocateMemory);
    VK_LOAD_D(rt.device, FreeMemory);
    VK_LOAD_D(rt.device, BindImageMemory);
    VK_LOAD_D(rt.device, CreateImageView);
    VK_LOAD_D(rt.device, DestroyImageView);
    VK_LOAD_D(rt.device, GetMemoryWin32HandleKHR);
    VK_LOAD_D(rt.device, CreateSemaphore);
    VK_LOAD_D(rt.device, DestroySemaphore);
    VK_LOAD_D(rt.device, GetSemaphoreWin32HandleKHR);
    VK_LOAD_D(rt.device, CreateCommandPool);
    VK_LOAD_D(rt.device, DestroyCommandPool);
    VK_LOAD_D(rt.device, AllocateCommandBuffers);
    VK_LOAD_D(rt.device, BeginCommandBuffer);
    VK_LOAD_D(rt.device, EndCommandBuffer);
    VK_LOAD_D(rt.device, ResetCommandBuffer);
    VK_LOAD_D(rt.device, CmdPipelineBarrier);
    VK_LOAD_D(rt.device, QueueSubmit);
    VK_LOAD_D(rt.device, QueueWaitIdle);
    VK_LOAD_D(rt.device, DeviceWaitIdle);
    VK_LOAD_D(rt.device, CreateFence);
    VK_LOAD_D(rt.device, DestroyFence);
    VK_LOAD_D(rt.device, WaitForFences);
    VK_LOAD_D(rt.device, ResetFences);
    VK_LOAD_D(rt.device, CreateQueryPool);
    VK_LOAD_D(rt.device, DestroyQueryPool);
    VK_LOAD_D(rt.device, CmdResetQueryPool);
    VK_LOAD_D(rt.device, CmdWriteTimestamp);
    VK_LOAD_D(rt.device, GetQueryPoolResults);
    if (!vk.GetMemoryWin32HandleKHR || !vk.GetSemaphoreWin32HandleKHR || !vk.QueueSubmit || !vk.CmdPipelineBarrier)
        return fail(35, "Vulkan device functions missing");
    vk.GetDeviceQueue(rt.device, rt.family, 0, &rt.queue);
    vk.GetPhysicalDeviceMemoryProperties(rt.physical, &rt.memProps);

    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = rt.family;
    r = vk.CreateCommandPool(rt.device, &pci, nullptr, &rt.pool);
    if (r != VK_SUCCESS) return fail(36, "vkCreateCommandPool failed (%s)", vkResultName(r).c_str());
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = rt.pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = RING;
    r = vk.AllocateCommandBuffers(rt.device, &cai, rt.cmd);
    if (r != VK_SUCCESS) return fail(37, "vkAllocateCommandBuffers failed (%s)", vkResultName(r).c_str());
    for (int i = 0; i < RING; ++i) {
        VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        r = vk.CreateFence(rt.device, &fci, nullptr, &rt.fence[i]);
        if (r != VK_SUCCESS) return fail(38, "vkCreateFence failed (%s)", vkResultName(r).c_str());
    }
    if (rt.timestamps) {
        VkQueryPoolCreateInfo qpi = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qpi.queryCount = RING * 2;
        if (vk.CreateQueryPool(rt.device, &qpi, nullptr, &rt.vkQueries) != VK_SUCCESS) rt.timestamps = false;
    }
    return 0;
}

static int createSemaphore(VkSemaphore &vkSem, GLuint &glSem) {
    VkExportSemaphoreCreateInfo export_ = {VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
    export_.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    sci.pNext = &export_;
    VkResult r = vk.CreateSemaphore(rt.device, &sci, nullptr, &vkSem);
    if (r != VK_SUCCESS) return fail(40, "vkCreateSemaphore failed (%s)", vkResultName(r).c_str());
    VkSemaphoreGetWin32HandleInfoKHR hi = {VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
    hi.semaphore = vkSem;
    hi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    HANDLE handle = nullptr;
    r = vk.GetSemaphoreWin32HandleKHR(rt.device, &hi, &handle);
    if (r != VK_SUCCESS || !handle) return fail(41, "vkGetSemaphoreWin32HandleKHR failed (%s)", vkResultName(r).c_str());
    clearGlErrors();
    gl.glGenSemaphoresEXT(1, &glSem);
    gl.glImportSemaphoreWin32HandleEXT(glSem, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT_, handle);
    CloseHandle(handle);   // importing an NT handle does not take ownership of it
    GLenum e = gl.glGetError();
    if (e != GL_NO_ERROR_) return fail(42, "OpenGL semaphore import failed (GL error 0x%04x)", e);
    return 0;
}

static uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < rt.memProps.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (rt.memProps.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

static void destroyImage(SharedImage &s) {
    if (s.glTexture) { gl.glDeleteTextures(1, &s.glTexture); s.glTexture = 0; }
    if (s.glMemory) { gl.glDeleteMemoryObjectsEXT(1, &s.glMemory); s.glMemory = 0; }
    if (s.view) { vk.DestroyImageView(rt.device, s.view, nullptr); s.view = VK_NULL_HANDLE; }
    if (s.image) { vk.DestroyImage(rt.device, s.image, nullptr); s.image = VK_NULL_HANDLE; }
    if (s.memory) { vk.FreeMemory(rt.device, s.memory, nullptr); s.memory = VK_NULL_HANDLE; }
    s.width = s.height = 0;
}

static int createImage(SharedImage &s, uint32_t w, uint32_t h, VkFormat format, GLenum glFormat, const char *what) {
    s.format = format;
    s.glFormat = glFormat;
    s.width = w;
    s.height = h;
    VkExternalMemoryImageCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.pNext = &ext;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent = {w, h, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult r = vk.CreateImage(rt.device, &ici, nullptr, &s.image);
    if (r != VK_SUCCESS) return fail(50, "vkCreateImage %s %ux%u failed (%s)", what, w, h, vkResultName(r).c_str());
    VkMemoryRequirements req = {};
    vk.GetImageMemoryRequirements(rt.device, s.image, &req);
    uint32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return fail(51, "no device-local memory type for %s", what);
    VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = s.image;
    VkExportMemoryAllocateInfo exportInfo = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
    exportInfo.pNext = &dedicated;
    exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &exportInfo;
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = type;
    r = vk.AllocateMemory(rt.device, &mai, nullptr, &s.memory);
    if (r != VK_SUCCESS) return fail(52, "vkAllocateMemory %s (%llu bytes) failed (%s)", what,
            (unsigned long long) req.size, vkResultName(r).c_str());
    r = vk.BindImageMemory(rt.device, s.image, s.memory, 0);
    if (r != VK_SUCCESS) return fail(53, "vkBindImageMemory %s failed (%s)", what, vkResultName(r).c_str());
    VkImageViewCreateInfo vci = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = s.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    r = vk.CreateImageView(rt.device, &vci, nullptr, &s.view);
    if (r != VK_SUCCESS) return fail(54, "vkCreateImageView %s failed (%s)", what, vkResultName(r).c_str());

    VkMemoryGetWin32HandleInfoKHR hi = {VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
    hi.memory = s.memory;
    hi.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    HANDLE handle = nullptr;
    r = vk.GetMemoryWin32HandleKHR(rt.device, &hi, &handle);
    if (r != VK_SUCCESS || !handle) return fail(55, "vkGetMemoryWin32HandleKHR %s failed (%s)", what, vkResultName(r).c_str());
    clearGlErrors();
    gl.glCreateMemoryObjectsEXT(1, &s.glMemory);
    GLint yes = 1;
    gl.glMemoryObjectParameterivEXT(s.glMemory, GL_DEDICATED_MEMORY_OBJECT_EXT_, &yes);
    gl.glImportMemoryWin32HandleEXT(s.glMemory, req.size, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT_, handle);
    CloseHandle(handle);
    gl.glCreateTextures(GL_TEXTURE_2D_, 1, &s.glTexture);
    gl.glTextureParameteri(s.glTexture, GL_TEXTURE_TILING_EXT_, (GLint) GL_OPTIMAL_TILING_EXT_);
    gl.glTextureStorageMem2DEXT(s.glTexture, 1, glFormat, (GLsizei) w, (GLsizei) h, s.glMemory, 0);
    gl.glTextureParameteri(s.glTexture, GL_TEXTURE_MIN_FILTER_, (GLint) GL_NEAREST_);
    gl.glTextureParameteri(s.glTexture, GL_TEXTURE_MAG_FILTER_, (GLint) GL_NEAREST_);
    GLenum e = gl.glGetError();
    if (e != GL_NO_ERROR_) return fail(56, "OpenGL import of %s failed (GL error 0x%04x)", what, e);
    return 0;
}

static void destroyFeature() {
    if (rt.feature) {
        NVSDK_NGX_VULKAN_ReleaseFeature(rt.feature);
        rt.feature = nullptr;
    }
}

static void destroySized() {
    // GL may still be reading the imported images (last frame's output pass): let it finish before Vulkan frees their
    // memory. Only on resize / pack switch / shutdown, never per frame.
    if (rt.renderW && gl.glFinish && wglGetCurrentContext() == rt.glContext) gl.glFinish();
    if (rt.device) vk.DeviceWaitIdle(rt.device);
    destroyFeature();
    for (auto &s : rt.img) destroyImage(s);
    rt.renderW = rt.renderH = rt.outW = rt.outH = 0;
    for (bool &b : rt.submitted) b = false;
    for (bool &b : rt.glQueriesIssued) b = false;
    for (bool &b : rt.vkTimed) b = false;
}

static int submitAndWait(const std::function<void(VkCommandBuffer)> &record) {
    VkCommandBuffer c = rt.cmd[0];
    vk.WaitForFences(rt.device, 1, &rt.fence[0], VK_TRUE, UINT64_MAX);
    vk.ResetFences(rt.device, 1, &rt.fence[0]);
    vk.ResetCommandBuffer(c, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.BeginCommandBuffer(c, &bi);
    record(c);
    vk.EndCommandBuffer(c);
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &c;
    VkResult r = vk.QueueSubmit(rt.queue, 1, &si, rt.fence[0]);
    if (r != VK_SUCCESS) return fail(60, "vkQueueSubmit failed (%s)", vkResultName(r).c_str());
    vk.WaitForFences(rt.device, 1, &rt.fence[0], VK_TRUE, UINT64_MAX);
    return 0;
}

static int createSized(uint32_t rw, uint32_t rh, uint32_t ow, uint32_t oh) {
    destroySized();
    int e;
    if ((e = createImage(rt.img[IMG_COLOR], rw, rh, VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8_, "colour"))) return e;
    if ((e = createImage(rt.img[IMG_DEPTH], rw, rh, VK_FORMAT_R32_SFLOAT, GL_R32F_, "depth"))) return e;
    if ((e = createImage(rt.img[IMG_MOTION], rw, rh, VK_FORMAT_R16G16_SFLOAT, GL_RG16F_, "motion"))) return e;
    if ((e = createImage(rt.img[IMG_OUTPUT], ow, oh, VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8_, "output"))) return e;

    // DLSS feature: render size -> output size, quality mode + preset hint from the mod's config
    unsigned int optW = 0, optH = 0, maxW = 0, maxH = 0, minW = 0, minH = 0;
    float sharp = 0;
    NGX_DLSS_GET_OPTIMAL_SETTINGS(rt.params, ow, oh, (NVSDK_NGX_PerfQuality_Value) rt.quality, &optW, &optH,
            &maxW, &maxH, &minW, &minH, &sharp);
    logf("DLSS optimal render size for %ux%u (quality value %d): %ux%u (allowed %ux%u .. %ux%u); using %ux%u", ow, oh,
            rt.quality, optW, optH, minW, minH, maxW, maxH, rw, rh);
    if (minW && (rw < minW || rh < minH || rw > maxW || rh > maxH))
        logf("WARNING render size %ux%u is outside DLSS' dynamic range for this mode", rw, rh);
    const char *presetKeys[] = {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance,
            NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality};
    for (const char *k : presetKeys) NVSDK_NGX_Parameter_SetUI(rt.params, k, (unsigned int) rt.preset);
    NVSDK_NGX_DLSS_Create_Params cp = {};
    cp.Feature.InWidth = rw;
    cp.Feature.InHeight = rh;
    cp.Feature.InTargetWidth = ow;
    cp.Feature.InTargetHeight = oh;
    cp.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value) rt.quality;
    cp.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;   // LDR colour, UV motion at render size, plain depth
    cp.InEnableOutputSubrects = false;
    NVSDK_NGX_Result nr = NVSDK_NGX_Result_Fail;
    int se = 0;
    runBigStack([&] {
        se = submitAndWait([&](VkCommandBuffer c) {
            if (rt.lean) {
                // lean hand-off: every shared image lives in GENERAL from here on (valid for sampling and storage)
                VkImageMemoryBarrier b[IMAGE_COUNT];
                for (int i = 0; i < IMAGE_COUNT; ++i) {
                    b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                    b[i].srcAccessMask = 0;
                    b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                    b[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                    b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                    b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    b[i].image = rt.img[i].image;
                    b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                }
                vk.CmdPipelineBarrier(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr,
                        0, nullptr, IMAGE_COUNT, b);
            }
            nr = NGX_VULKAN_CREATE_DLSS_EXT(c, 1, 1, &rt.feature, rt.params, &cp);
        });
    });
    if (se) return se;
    if (NVSDK_NGX_FAILED(nr) || !rt.feature) return fail(61, "DLSS feature creation failed (0x%08x)", (unsigned) nr);
    rt.renderW = rw;
    rt.renderH = rh;
    rt.outW = ow;
    rt.outH = oh;
    logf("DLSS ready: %ux%u -> %ux%u, preset %d, quality value %d, %s hand-off", rw, rh, ow, oh, rt.preset, rt.quality,
            rt.lean ? "lean" : "strict");
    return 0;
}

// ------------------------------------------------------------------------------------------------ init / shutdown

static void shutdownAll() {
    if (rt.device) vk.DeviceWaitIdle(rt.device);
    destroySized();
    if (wglGetCurrentContext() && gl.glDeleteProgram) {
        if (rt.progIn) gl.glDeleteProgram(rt.progIn);
        if (rt.progOut) gl.glDeleteProgram(rt.progOut);
        if (rt.glQueries[0]) gl.glDeleteQueries(RING * 4, rt.glQueries);
        if (rt.glSemGlDone) gl.glDeleteSemaphoresEXT(1, &rt.glSemGlDone);
        if (rt.glSemVkDone) gl.glDeleteSemaphoresEXT(1, &rt.glSemVkDone);
    }
    rt.progIn = rt.progOut = 0;
    std::memset(rt.glQueries, 0, sizeof(rt.glQueries));
    rt.glSemGlDone = rt.glSemVkDone = 0;
    if (rt.params) { NVSDK_NGX_VULKAN_DestroyParameters(rt.params); rt.params = nullptr; }
    if (rt.ngxReady) { NVSDK_NGX_VULKAN_Shutdown1(rt.device); rt.ngxReady = false; }
    if (rt.device) {
        if (rt.semGlDone) vk.DestroySemaphore(rt.device, rt.semGlDone, nullptr);
        if (rt.semVkDone) vk.DestroySemaphore(rt.device, rt.semVkDone, nullptr);
        if (rt.vkQueries) vk.DestroyQueryPool(rt.device, rt.vkQueries, nullptr);
        for (auto &f : rt.fence) if (f) vk.DestroyFence(rt.device, f, nullptr);
        if (rt.pool) vk.DestroyCommandPool(rt.device, rt.pool, nullptr);
        vk.DestroyDevice(rt.device, nullptr);
    }
    if (rt.instance && vk.DestroyInstance) vk.DestroyInstance(rt.instance, nullptr);
    std::wstring keepData = rt.dataDir, keepDlss = rt.dlssDir;
    rt = Runtime();
    rt.dataDir = keepData;
    rt.dlssDir = keepDlss;
}

static int initAll() {
    int e;
    if ((e = loadGl())) return e;
    rt.glContext = wglGetCurrentContext();
    rt.renderThread = GetCurrentThreadId();
    const char *glRenderer = reinterpret_cast<const char *>(gl.glGetString(GL_RENDERER_));
    const char *glVersion = reinterpret_cast<const char *>(gl.glGetString(GL_VERSION_));
    logf("OpenGL: %s, %s", glRenderer ? glRenderer : "?", glVersion ? glVersion : "?");
    clearGlErrors();
    GLint uuids = 0;
    gl.glGetIntegerv(GL_NUM_DEVICE_UUIDS_EXT_, &uuids);
    if (uuids < 1) uuids = 1;
    if (uuids > 16) uuids = 16;
    GLubyte devUuid[16][VK_UUID_SIZE] = {};
    GLubyte drvUuid[VK_UUID_SIZE] = {};
    for (GLint i = 0; i < uuids; ++i) gl.glGetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT_, (GLuint) i, devUuid[i]);
    gl.glGetUnsignedBytevEXT(GL_DRIVER_UUID_EXT_, drvUuid);
    if (gl.glGetError() != GL_NO_ERROR_) return fail(14, "OpenGL device UUID query failed");

    int ve = 0;
    if (!runBigStack([&] { ve = createVulkan(devUuid, uuids, drvUuid); })) return fail(15, "could not start the init thread");
    if (ve) return ve;

    NVSDK_NGX_Result nr = NVSDK_NGX_Result_Fail;
    NVSDK_NGX_FeatureCommonInfo common = featureInfo();
    runBigStack([&] {
        nr = NVSDK_NGX_VULKAN_Init_with_ProjectID(PROJECT_ID, NVSDK_NGX_ENGINE_TYPE_CUSTOM, ENGINE_VERSION,
                rt.dataDir.c_str(), rt.instance, rt.physical, rt.device, vk.GetInstanceProcAddr, vk.GetDeviceProcAddr,
                &common, NVSDK_NGX_Version_API);
    });
    if (NVSDK_NGX_FAILED(nr)) return fail(70, "NGX initialisation failed (0x%08x)", (unsigned) nr);
    rt.ngxReady = true;
    nr = NVSDK_NGX_VULKAN_GetCapabilityParameters(&rt.params);
    if (NVSDK_NGX_FAILED(nr) || !rt.params) return fail(71, "NGX capability parameters unavailable (0x%08x)", (unsigned) nr);
    int available = 0, needsDriver = 0;
    unsigned int minMajor = 0, minMinor = 0;
    NVSDK_NGX_Parameter_GetI(rt.params, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    NVSDK_NGX_Parameter_GetI(rt.params, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
    NVSDK_NGX_Parameter_GetUI(rt.params, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minMajor);
    NVSDK_NGX_Parameter_GetUI(rt.params, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMinor, &minMinor);
    if (needsDriver) return fail(72, "DLSS needs a newer NVIDIA driver (at least %u.%u)", minMajor, minMinor);
    if (!available) return fail(73, "DLSS is not available on this GPU/driver (is nvngx_dlss.dll in %ls?)", rt.dlssDir.c_str());

    if ((e = createSemaphore(rt.semGlDone, rt.glSemGlDone))) return e;
    if ((e = createSemaphore(rt.semVkDone, rt.glSemVkDone))) return e;
    rt.progIn = buildProgram(SHADER_IN, "input");
    if (!rt.progIn) return 45;
    rt.progOut = buildProgram(SHADER_OUT, "output");
    if (!rt.progOut) return 46;
    rt.progInSize = gl.glGetUniformLocation(rt.progIn, "uSize");
    rt.progInUseDh = gl.glGetUniformLocation(rt.progIn, "uUseDh");
    rt.progInProj = gl.glGetUniformLocation(rt.progIn, "uProj");
    rt.progInUnified = gl.glGetUniformLocation(rt.progIn, "uUnified");
    rt.progOutSize = gl.glGetUniformLocation(rt.progOut, "uSize");
    // our units: the top texture units (GL 4.5 guarantees at least 80 combined) and the top image units (at least 8)
    GLint maxTex = 0, maxImg = 0;
    gl.glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS_, &maxTex);
    gl.glGetIntegerv(GL_MAX_IMAGE_UNITS_, &maxImg);
    if (maxTex < 64 || maxImg < 8) return fail(47, "too few texture/image units (%d / %d)", maxTex, maxImg);
    rt.texBase = (GLuint) (maxTex - TEX_UNITS);
    rt.imgBase = (GLuint) (maxImg - IMG_UNITS);
    const char *samplers[TEX_UNITS] = {"uColor", "uDepth", "uMotion", "uDhDepth"};
    const char *images[IMG_UNITS] = {"oColor", "oDepth", "oMotion"};
    clearGlErrors();
    for (int i = 0; i < TEX_UNITS; ++i)
        gl.glProgramUniform1i(rt.progIn, gl.glGetUniformLocation(rt.progIn, samplers[i]), (GLint) rt.texBase + i);
    for (int i = 0; i < IMG_UNITS; ++i)
        gl.glProgramUniform1i(rt.progIn, gl.glGetUniformLocation(rt.progIn, images[i]), (GLint) rt.imgBase + i);
    gl.glProgramUniform1i(rt.progOut, gl.glGetUniformLocation(rt.progOut, "uUpscaled"), (GLint) rt.texBase);
    gl.glProgramUniform1i(rt.progOut, gl.glGetUniformLocation(rt.progOut, "oTarget"), (GLint) rt.imgBase);
    if (gl.glGetError() != GL_NO_ERROR_) return fail(48, "could not set our programs' texture/image units");
    logf("our passes use texture units %u-%u and image units %u-%u", rt.texBase, rt.texBase + TEX_UNITS - 1, rt.imgBase,
            rt.imgBase + IMG_UNITS - 1);
    gl.glGenQueries(RING * 4, rt.glQueries);
    rt.ready = true;
    logf("DLSS available on %s (driver %u.%u)", rt.gpuName, rt.driverVersion >> 22, (rt.driverVersion >> 14) & 0xff);
    return 0;
}

// ------------------------------------------------------------------------------------------------ frame

static void collectTimings(int slot) {
    // Results of the frame that used this slot RING frames ago; never stalls (availability checked first).
    if (rt.glQueriesIssued[slot]) {
        GLint avail = 0;
        gl.glGetQueryObjectiv(rt.glQueries[slot * 4 + 3], GL_QUERY_RESULT_AVAILABLE_, &avail);
        if (avail) {
            GLuint64 t[4] = {};
            for (int i = 0; i < 4; ++i) gl.glGetQueryObjectui64v(rt.glQueries[slot * 4 + i], GL_QUERY_RESULT_, &t[i]);
            rt.accIn += (double) (t[1] - t[0]);
            rt.accGap += (double) (t[2] - t[1]);
            rt.accOut += (double) (t[3] - t[2]);
            rt.accFrames++;
        }
        rt.glQueriesIssued[slot] = false;
    }
    if (rt.vkTimed[slot] && rt.submitted[slot]) {
        uint64_t ts[2] = {};
        if (vk.GetQueryPoolResults(rt.device, rt.vkQueries, (uint32_t) slot * 2, 2, sizeof(ts), ts, sizeof(uint64_t),
                VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            rt.accDlss += (double) (ts[1] - ts[0]) * rt.timestampPeriod;
            rt.accDlssFrames++;
        }
    }
}

static VkImageMemoryBarrier ownership(const SharedImage &s, bool acquire, VkImageLayout layout, VkAccessFlags access) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = acquire ? 0 : access;
    b.dstAccessMask = acquire ? access : 0;
    b.oldLayout = layout;
    b.newLayout = layout;
    b.srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_EXTERNAL : rt.family;
    b.dstQueueFamilyIndex = acquire ? rt.family : VK_QUEUE_FAMILY_EXTERNAL;
    b.image = s.image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}

// dh: Distant Horizons' depth texture (0 = none / not wanted); proj = main m22, m32, DH m22, m32; unified = near, far
static int dispatchFrame(GLuint colorTex, GLuint depthTex, GLuint motionTex, GLuint targetTex, uint32_t rw, uint32_t rh,
                         uint32_t ow, uint32_t oh, float jx, float jy, bool reset, float frameMs, GLuint dhDepthTex,
                         const float *proj, const float *unified) {
    if (!rt.ready) return fail(80, "runtime not initialised");
    if (wglGetCurrentContext() != rt.glContext || GetCurrentThreadId() != rt.renderThread)
        return fail(81, "dispatch from another thread or OpenGL context");
    if (!colorTex || !depthTex || !motionTex || !targetTex || rw < 32 || rh < 32 || ow < rw || oh < rh || ow > 16384 || oh > 16384)
        return fail(82, "bad frame arguments (%u %u %u %u, %ux%u -> %ux%u)", colorTex, depthTex, motionTex, targetTex, rw, rh, ow, oh);
    if (rw != rt.renderW || rh != rt.renderH || ow != rt.outW || oh != rt.outH) {
        int e = createSized(rw, rh, ow, oh);
        if (e) return e;
        reset = true;
    }
    double cpu0 = qpcNs();
    int slot = (int) (rt.frame % RING);
    // the command buffer of RING frames ago must be done before it is re-recorded (practically never waits)
    vk.WaitForFences(rt.device, 1, &rt.fence[slot], VK_TRUE, UINT64_MAX);
    collectTimings(slot);
    vk.ResetFences(rt.device, 1, &rt.fence[slot]);

    if (gl.glPushDebugGroup) gl.glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION_, 0x6c55, -1, "Bons DLSS Diet");
    // timing: one frame in TIMING_EVERY carries the timestamp queries (each GL timestamp waits for the GPU pipeline to
    // drain; every frame cost ~0.3 ms in the harness's back-to-back burst, 2026-10-16)
    bool sample = rt.timing && rt.frame % TIMING_EVERY == 0;

    // 1) GL: shader pack textures -> shared DLSS inputs (one pass)
    if (sample) gl.glQueryCounter(rt.glQueries[slot * 4 + 0], GL_TIMESTAMP_);
    gl.glUseProgram(rt.progIn);
    if (rt.inSize[0] != (GLint) rw || rt.inSize[1] != (GLint) rh) {
        rt.inSize[0] = (GLint) rw;
        rt.inSize[1] = (GLint) rh;
        gl.glProgramUniform2i(rt.progIn, rt.progInSize, (GLint) rw, (GLint) rh);
    }
    bool useDh = dhDepthTex != 0 && proj && unified && unified[1] > unified[0] && unified[0] > 0.0f;
    if (rt.inUseDh != (useDh ? 1 : 0)) {
        rt.inUseDh = useDh ? 1 : 0;
        gl.glProgramUniform1i(rt.progIn, rt.progInUseDh, rt.inUseDh);
    }
    if (useDh && (std::memcmp(rt.inProj, proj, sizeof(rt.inProj)) || std::memcmp(rt.inUnified, unified, sizeof(rt.inUnified)))) {
        std::memcpy(rt.inProj, proj, sizeof(rt.inProj));
        std::memcpy(rt.inUnified, unified, sizeof(rt.inUnified));
        gl.glProgramUniform4f(rt.progIn, rt.progInProj, proj[0], proj[1], proj[2], proj[3]);
        gl.glProgramUniform2f(rt.progIn, rt.progInUnified, unified[0], unified[1]);
    }
    bindTex(0, colorTex);
    bindTex(1, depthTex);
    bindTex(2, motionTex);
    bindTex(3, useDh ? dhDepthTex : depthTex);   // never leave the sampler without a texture
    bindImage(0, rt.img[IMG_COLOR].glTexture, GL_RGBA8_);
    bindImage(1, rt.img[IMG_DEPTH].glTexture, GL_R32F_);
    bindImage(2, rt.img[IMG_MOTION].glTexture, GL_RG16F_);
    gl.glDispatchCompute((rw + 15) / 16, (rh + 15) / 16, 1);
    gl.glMemoryBarrier(GL_ALL_BARRIER_BITS_);
    if (sample) gl.glQueryCounter(rt.glQueries[slot * 4 + 1], GL_TIMESTAMP_);

    GLuint texs[IMAGE_COUNT] = {rt.img[IMG_COLOR].glTexture, rt.img[IMG_DEPTH].glTexture, rt.img[IMG_MOTION].glTexture,
                                rt.img[IMG_OUTPUT].glTexture};
    GLenum layouts[IMAGE_COUNT] = {GL_LAYOUT_SHADER_READ_ONLY_EXT_, GL_LAYOUT_SHADER_READ_ONLY_EXT_,
                                   GL_LAYOUT_SHADER_READ_ONLY_EXT_, GL_LAYOUT_GENERAL_EXT_};
    static const GLenum general[IMAGE_COUNT] = {GL_LAYOUT_GENERAL_EXT_, GL_LAYOUT_GENERAL_EXT_, GL_LAYOUT_GENERAL_EXT_,
                                                GL_LAYOUT_GENERAL_EXT_};
    if (rt.lean) gl.glSignalSemaphoreEXT(rt.glSemGlDone, 0, nullptr, IMG_OUTPUT, texs, general);   // the three inputs
    else gl.glSignalSemaphoreEXT(rt.glSemGlDone, 0, nullptr, IMAGE_COUNT, texs, layouts);
    gl.glFlush();   // the signal must reach the GPU before Vulkan waits on it

    // 2) Vulkan: DLSS
    double cpuVk0 = qpcNs();
    VkCommandBuffer c = rt.cmd[slot];
    vk.ResetCommandBuffer(c, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.BeginCommandBuffer(c, &bi);
    VkImageMemoryBarrier acq[IMAGE_COUNT] = {
            ownership(rt.img[IMG_COLOR], true, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT),
            ownership(rt.img[IMG_DEPTH], true, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT),
            ownership(rt.img[IMG_MOTION], true, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_SHADER_READ_BIT),
            ownership(rt.img[IMG_OUTPUT], true, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)};
    if (!rt.lean)
        vk.CmdPipelineBarrier(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0,
                nullptr, IMAGE_COUNT, acq);
    bool vkTime = rt.timestamps && sample;
    rt.vkTimed[slot] = vkTime;
    if (vkTime) {
        vk.CmdResetQueryPool(c, rt.vkQueries, (uint32_t) slot * 2, 2);
        vk.CmdWriteTimestamp(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, rt.vkQueries, (uint32_t) slot * 2);
    }
    NVSDK_NGX_Resource_VK color = NVSDK_NGX_Create_ImageView_Resource_VK(rt.img[IMG_COLOR].view, rt.img[IMG_COLOR].image,
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, rt.img[IMG_COLOR].format, rw, rh, false);
    NVSDK_NGX_Resource_VK depth = NVSDK_NGX_Create_ImageView_Resource_VK(rt.img[IMG_DEPTH].view, rt.img[IMG_DEPTH].image,
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, rt.img[IMG_DEPTH].format, rw, rh, false);
    NVSDK_NGX_Resource_VK motion = NVSDK_NGX_Create_ImageView_Resource_VK(rt.img[IMG_MOTION].view, rt.img[IMG_MOTION].image,
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, rt.img[IMG_MOTION].format, rw, rh, false);
    NVSDK_NGX_Resource_VK output = NVSDK_NGX_Create_ImageView_Resource_VK(rt.img[IMG_OUTPUT].view, rt.img[IMG_OUTPUT].image,
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, rt.img[IMG_OUTPUT].format, ow, oh, true);
    NVSDK_NGX_VK_DLSS_Eval_Params ep = {};
    ep.Feature.pInColor = &color;
    ep.Feature.pInOutput = &output;
    ep.Feature.InSharpness = 0.0f;
    ep.pInDepth = &depth;
    ep.pInMotionVectors = &motion;
    ep.InJitterOffsetX = jx;
    ep.InJitterOffsetY = jy;
    ep.InRenderSubrectDimensions.Width = rw;
    ep.InRenderSubrectDimensions.Height = rh;
    ep.InReset = reset ? 1 : 0;
    ep.InMVScaleX = (float) rw;   // the pack's motion is in UV units
    ep.InMVScaleY = (float) rh;
    ep.InPreExposure = 1.0f;
    ep.InExposureScale = 1.0f;
    ep.InFrameTimeDeltaInMsec = frameMs;
    NVSDK_NGX_Result nr = NGX_VULKAN_EVALUATE_DLSS_EXT(c, rt.feature, rt.params, &ep);
    if (vkTime) vk.CmdWriteTimestamp(c, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, rt.vkQueries, (uint32_t) slot * 2 + 1);
    VkImageMemoryBarrier rel[IMAGE_COUNT] = {
            ownership(rt.img[IMG_COLOR], false, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0),
            ownership(rt.img[IMG_DEPTH], false, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0),
            ownership(rt.img[IMG_MOTION], false, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0),
            ownership(rt.img[IMG_OUTPUT], false, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_WRITE_BIT)};
    if (!rt.lean)
        vk.CmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0,
                nullptr, IMAGE_COUNT, rel);
    vk.EndCommandBuffer(c);
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &rt.semGlDone;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &c;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &rt.semVkDone;
    VkResult vr = vk.QueueSubmit(rt.queue, 1, &si, rt.fence[slot]);
    rt.submitted[slot] = vr == VK_SUCCESS;
    double cpuVk = qpcNs() - cpuVk0;

    // 3) GL: wait for DLSS on the GPU, then its output -> colortex11 (one pass)
    int result = 0;
    if (vr == VK_SUCCESS) {
        GLenum src[IMAGE_COUNT] = {GL_LAYOUT_SHADER_READ_ONLY_EXT_, GL_LAYOUT_SHADER_READ_ONLY_EXT_,
                                   GL_LAYOUT_SHADER_READ_ONLY_EXT_, GL_LAYOUT_GENERAL_EXT_};
        if (rt.lean) gl.glWaitSemaphoreEXT(rt.glSemVkDone, 0, nullptr, 1, &texs[IMG_OUTPUT], &general[IMG_OUTPUT]);
        else gl.glWaitSemaphoreEXT(rt.glSemVkDone, 0, nullptr, IMAGE_COUNT, texs, src);
        if (sample) gl.glQueryCounter(rt.glQueries[slot * 4 + 2], GL_TIMESTAMP_);
        gl.glUseProgram(rt.progOut);
        if (rt.outSize[0] != (GLint) ow || rt.outSize[1] != (GLint) oh) {
            rt.outSize[0] = (GLint) ow;
            rt.outSize[1] = (GLint) oh;
            gl.glProgramUniform2i(rt.progOut, rt.progOutSize, (GLint) ow, (GLint) oh);
        }
        bindTex(0, rt.img[IMG_OUTPUT].glTexture);
        bindImage(0, targetTex, GL_RGBA8_);
        gl.glDispatchCompute((ow + 15) / 16, (oh + 15) / 16, 1);
        gl.glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT_ | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT_ | GL_FRAMEBUFFER_BARRIER_BIT_ |
                           GL_TEXTURE_UPDATE_BARRIER_BIT_);
        if (sample) gl.glQueryCounter(rt.glQueries[slot * 4 + 3], GL_TIMESTAMP_);
        rt.glQueriesIssued[slot] = sample;
    } else {
        // nothing was submitted that would consume glDone: drain it on the GPU so the binary semaphore is unsignalled
        VkPipelineStageFlags ws = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo drain = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
        drain.waitSemaphoreCount = 1;
        drain.pWaitSemaphores = &rt.semGlDone;
        drain.pWaitDstStageMask = &ws;
        vk.QueueSubmit(rt.queue, 1, &drain, VK_NULL_HANDLE);
        result = fail(90, "vkQueueSubmit failed (%s)", vkResultName(vr).c_str());
    }
    if (gl.glPopDebugGroup) gl.glPopDebugGroup();
    if (!result && NVSDK_NGX_FAILED(nr)) result = fail(91, "DLSS evaluation failed (0x%08x)", (unsigned) nr);
    rt.frame++;
    double cpu = qpcNs() - cpu0;
    rt.accCpu += cpu;
    rt.accCpuVk += cpuVk;
    if (cpu > rt.maxCpu) rt.maxCpu = cpu;
    rt.accCpuFrames++;
    return result;
}

// ------------------------------------------------------------------------------------------------ C API (tests)

// The same entry points without JNI, for the native test harness (native/harness.cpp). Render thread, GL current.
extern "C" {

__declspec(dllexport) int bons_c_init(const wchar_t *dataDir, const wchar_t *dlssDir, int quality, int preset) {
    if (rt.ready) return 0;
    shutdownAll();
    rt.dataDir = dataDir ? dataDir : L"";
    rt.dlssDir = dlssDir ? dlssDir : L"";
    rt.quality = quality;
    rt.preset = preset;
    int e = initAll();
    if (e) shutdownAll();
    return e;
}

__declspec(dllexport) int bons_c_dispatch(unsigned colorTex, unsigned depthTex, unsigned motionTex, unsigned targetTex,
                                          int renderW, int renderH, int outW, int outH, float jitterX, float jitterY,
                                          int reset, float frameMs, unsigned dhDepthTex, const float *proj,
                                          const float *unified) {
    return dispatchFrame(colorTex, depthTex, motionTex, targetTex, (uint32_t) renderW, (uint32_t) renderH, (uint32_t) outW,
            (uint32_t) outH, jitterX, jitterY, reset != 0, frameMs, dhDepthTex, proj, unified);
}

__declspec(dllexport) void bons_c_shutdown() { shutdownAll(); }

__declspec(dllexport) int bons_c_last_error(char *buf, int size) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    return snprintf(buf, (size_t) size, "%s", g_error.c_str());
}

__declspec(dllexport) int bons_c_poll_log(char *buf, int size) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_log.empty()) return -1;
    int n = snprintf(buf, (size_t) size, "%s", g_log.front().c_str());
    g_log.pop_front();
    return n;
}

// out[STATS]: frames with GL timings, input pass ns, output pass ns, frames with DLSS timing, DLSS ns, frames with CPU
// timing, CPU ns of the whole dispatch, CPU ns of its Vulkan record + submit, slowest dispatch ns, GL gap ns (input pass
// end -> DLSS wait end, same frames as the GL timings) (sums since last call)
static const int STATS = 10;
static void takeStats(double *out) {
    out[0] = (double) rt.accFrames;
    out[1] = rt.accIn;
    out[2] = rt.accOut;
    out[3] = (double) rt.accDlssFrames;
    out[4] = rt.accDlss;
    out[5] = (double) rt.accCpuFrames;
    out[6] = rt.accCpu;
    out[7] = rt.accCpuVk;
    out[8] = rt.maxCpu;
    out[9] = rt.accGap;
    rt.accFrames = rt.accDlssFrames = rt.accCpuFrames = 0;
    rt.accIn = rt.accOut = rt.accDlss = rt.accCpu = rt.accCpuVk = rt.maxCpu = rt.accGap = 0;
}

// timing: issue the timestamp queries (off = no GPU queries at all); lean: see Runtime::lean. Render thread.
static void setOptions(bool timing, bool lean) {
    rt.timing = timing;
    if (lean != rt.lean) {
        rt.lean = lean;
        if (rt.ready) destroySized();   // rebuilt at the next frame, in the layouts the new hand-off expects
    }
}

__declspec(dllexport) void bons_c_take_stats(double *out) { takeStats(out); }

__declspec(dllexport) void bons_c_set_options(int timing, int lean) { setOptions(timing != 0, lean != 0); }

}  // extern "C"

// ------------------------------------------------------------------------------------------------ JNI

static std::wstring jwstring(JNIEnv *env, jstring s) {
    if (!s) return std::wstring();
    const jchar *chars = env->GetStringChars(s, nullptr);
    jsize len = env->GetStringLength(s);
    std::wstring out(reinterpret_cast<const wchar_t *>(chars), (size_t) len);
    env->ReleaseStringChars(s, chars);
    return out;
}

extern "C" {

JNIEXPORT jint JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nAbi(JNIEnv *, jclass) { return BONS_ABI; }

JNIEXPORT jint JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nInit(JNIEnv *env, jclass, jstring dataDir, jstring dlssDir,
                                                                   jint quality, jint preset) {
    if (rt.ready) return 0;
    shutdownAll();
    rt.dataDir = jwstring(env, dataDir);
    rt.dlssDir = jwstring(env, dlssDir);
    rt.quality = quality;
    rt.preset = preset;
    int e = initAll();
    if (e) shutdownAll();
    return e;
}

JNIEXPORT jint JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nSetMode(JNIEnv *, jclass, jint quality, jint preset) {
    if (quality == rt.quality && preset == rt.preset) return 0;
    rt.quality = quality;
    rt.preset = preset;
    if (rt.ready) destroySized();   // the feature is rebuilt at the next frame
    return 0;
}

JNIEXPORT jint JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nDispatch(JNIEnv *, jclass, jint colorTex, jint depthTex,
        jint motionTex, jint targetTex, jint renderW, jint renderH, jint outW, jint outH, jfloat jitterX, jfloat jitterY,
        jboolean reset, jfloat frameMs, jint dhDepthTex, jfloat mainM22, jfloat mainM32, jfloat dhM22, jfloat dhM32,
        jfloat nearPlane, jfloat farPlane) {
    float proj[4] = {mainM22, mainM32, dhM22, dhM32};
    float unified[2] = {nearPlane, farPlane};
    return dispatchFrame((GLuint) colorTex, (GLuint) depthTex, (GLuint) motionTex, (GLuint) targetTex, (uint32_t) renderW,
            (uint32_t) renderH, (uint32_t) outW, (uint32_t) outH, jitterX, jitterY, reset == JNI_TRUE, frameMs,
            (GLuint) dhDepthTex, proj, unified);
}

JNIEXPORT void JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nSetOptions(JNIEnv *, jclass, jboolean timing, jboolean lean) {
    setOptions(timing == JNI_TRUE, lean == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nShutdown(JNIEnv *, jclass) { shutdownAll(); }

// Frees the size-dependent images and the DLSS feature (another shader pack was selected); the device stays.
JNIEXPORT void JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nReleaseFrameResources(JNIEnv *, jclass) {
    if (rt.ready) destroySized();
}

JNIEXPORT jstring JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nLastError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    return env->NewStringUTF(g_error.c_str());
}

JNIEXPORT jstring JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nPollLog(JNIEnv *env, jclass) {
    std::string s;
    {
        std::lock_guard<std::mutex> lock(g_logMutex);
        if (g_log.empty()) return nullptr;
        s = g_log.front();
        g_log.pop_front();
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nDevice(JNIEnv *env, jclass) {
    return env->NewStringUTF(rt.gpuName);
}

// See takeStats for the layout.
JNIEXPORT jdoubleArray JNICALL Java_bons_dlssdiet_runtime_NativeDlss_nTakeStats(JNIEnv *env, jclass) {
    jdouble v[STATS];
    takeStats(v);
    jdoubleArray a = env->NewDoubleArray(STATS);
    if (a) env->SetDoubleArrayRegion(a, 0, STATS, v);
    return a;
}

}  // extern "C"
