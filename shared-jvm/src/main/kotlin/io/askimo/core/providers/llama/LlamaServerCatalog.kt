/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.config.AppConfig

/**
 * Archive format of a [LlamaServerPlatform] download.
 */
enum class ArchiveFormat {
    ZIP,
    TAR_GZ,
}

/** Identifies one supported OS/arch combination — see [LlamaServerCatalog]. */
enum class LlamaPlatformId {
    MACOS,
    LINUX_X86_64,
    LINUX_AARCH64,
    WINDOWS_X86_64,
    WINDOWS_AARCH64,
}

/**
 * Describes the `llama-server` release asset layout for one OS/arch combination. The actual
 * download URL and release tag are **not** stored here — they come from
 * [io.askimo.core.config.AppConfig.localLlm] (see [LlamaServerCatalog.downloadUrl]), so an
 * advanced user can repoint them at a mirror via `askimo.yml` without touching source.
 *
 * All default URLs point at **CPU-only** builds (no CUDA/Vulkan/ROCm) — see the "Further
 * Considerations" discussion that led to this: CPU-only keeps the download size reasonable and
 * avoids having to match a GPU driver/CUDA version per user machine. GPU acceleration can be
 * added later as an opt-in variant without changing this catalog's shape.
 */
data class LlamaServerPlatform(
    val id: LlamaPlatformId,
    /** Directory name this platform's files are extracted to under [io.askimo.core.util.AskimoHome.llamaBinDir]. */
    val resourceDir: String,
    val archiveFormat: ArchiveFormat,
) {
    /**
     * Path of the `llama-server` executable *within* the extracted archive for [releaseTag].
     * Newer `.tar.gz` releases (macOS/Linux) wrap everything in a top-level `llama-{tag}/`
     * directory; the `.zip` (Windows) releases have no such wrapper.
     */
    fun executableName(releaseTag: String): String = when (id) {
        LlamaPlatformId.WINDOWS_X86_64, LlamaPlatformId.WINDOWS_AARCH64 -> "llama-server.exe"
        else -> "llama-$releaseTag/llama-server"
    }
}

/**
 * Resolves the current JVM's OS/arch to the matching [LlamaServerPlatform], or `null` if
 * unsupported (e.g. 32-bit JVMs, exotic architectures), and resolves each platform's download URL
 * and release tag from [io.askimo.core.config.AppConfig.localLlm].
 */
object LlamaServerCatalog {

    private val MACOS = LlamaServerPlatform(LlamaPlatformId.MACOS, "macos", ArchiveFormat.TAR_GZ)
    private val LINUX_X86_64 = LlamaServerPlatform(LlamaPlatformId.LINUX_X86_64, "linux-x86_64", ArchiveFormat.TAR_GZ)
    private val LINUX_AARCH64 = LlamaServerPlatform(LlamaPlatformId.LINUX_AARCH64, "linux-aarch64", ArchiveFormat.TAR_GZ)
    private val WINDOWS_X86_64 = LlamaServerPlatform(LlamaPlatformId.WINDOWS_X86_64, "windows-x86_64", ArchiveFormat.ZIP)
    private val WINDOWS_AARCH64 = LlamaServerPlatform(LlamaPlatformId.WINDOWS_AARCH64, "windows-aarch64", ArchiveFormat.ZIP)

    /** Detects the current JVM's OS/arch and returns the matching platform, or `null` if unsupported. */
    fun detectCurrentPlatform(): LlamaServerPlatform? {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        // Match explicit 64-bit arch names only — falling back to "x86_64" for anything
        // non-ARM previously advertised support (and downloaded an unusable binary) on 32-bit
        // x86, ARM32, and other exotic architectures.
        val isArm64 = archName == "aarch64" || archName == "arm64"
        val isX86Arch = archName == "x86_64" || archName == "amd64"

        return when {
            // Only Apple Silicon (arm64) is supported — AppConfig.localLlm.macosDownloadUrl
            // points at an arm64-only asset, so Intel Macs (x86_64) are intentionally
            // unsupported here rather than downloading a binary that can't run.
            osName.contains("mac") || osName.contains("darwin") -> if (isArm64) MACOS else null

            osName.contains("win") -> when {
                isArm64 -> WINDOWS_AARCH64
                isX86Arch -> WINDOWS_X86_64
                else -> null
            }

            osName.contains("nux") || osName.contains("nix") -> when {
                isArm64 -> LINUX_AARCH64
                isX86Arch -> LINUX_X86_64
                else -> null
            }

            else -> null
        }
    }

    /** Download URL for [platform]'s release asset — read from [AppConfig.localLlm], hand-editable in `askimo.yml`. */
    fun downloadUrl(platform: LlamaServerPlatform): String = when (platform.id) {
        LlamaPlatformId.MACOS -> AppConfig.localLlm.macosDownloadUrl
        LlamaPlatformId.LINUX_X86_64 -> AppConfig.localLlm.linuxX86DownloadUrl
        LlamaPlatformId.LINUX_AARCH64 -> AppConfig.localLlm.linuxArmDownloadUrl
        LlamaPlatformId.WINDOWS_X86_64 -> AppConfig.localLlm.windowsX86DownloadUrl
        LlamaPlatformId.WINDOWS_AARCH64 -> AppConfig.localLlm.windowsArmDownloadUrl
    }

    /** Release tag currently configured (see [AppConfig.localLlm]'s `releaseTag`) — used to resolve [LlamaServerPlatform.executableName]. */
    fun releaseTag(): String = AppConfig.localLlm.releaseTag

    /**
     * Pinned SHA-256 of [platform]'s release archive — read from [AppConfig.localLlm], used by
     * [LlamaServerDownloader.download] to verify archive integrity before extraction. A
     * `Content-Length` match alone only detects truncation, not a mirror/CDN serving substitute
     * bytes of the exact expected length.
     */
    fun sha256(platform: LlamaServerPlatform): String = when (platform.id) {
        LlamaPlatformId.MACOS -> AppConfig.localLlm.macosSha256
        LlamaPlatformId.LINUX_X86_64 -> AppConfig.localLlm.linuxX86Sha256
        LlamaPlatformId.LINUX_AARCH64 -> AppConfig.localLlm.linuxArmSha256
        LlamaPlatformId.WINDOWS_X86_64 -> AppConfig.localLlm.windowsX86Sha256
        LlamaPlatformId.WINDOWS_AARCH64 -> AppConfig.localLlm.windowsArmSha256
    }
}
