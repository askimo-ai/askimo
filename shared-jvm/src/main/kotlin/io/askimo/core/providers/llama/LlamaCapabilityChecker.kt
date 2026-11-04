/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.AskimoHome
import java.lang.management.ManagementFactory

/** One hardware dimension evaluated by [LlamaCapabilityChecker]. */
enum class CapabilityCheck {
    RAM,
    CPU_CORES,
    DISK_SPACE,
}

/**
 * Result of a [LlamaCapabilityChecker.check] call. [isCapable] is `true` only when every
 * dimension meets its minimum; [failedChecks] lists which ones (if any) fell short, so the UI
 * can explain exactly why the local engine is unavailable.
 */
data class LlamaCapabilityResult(
    val isCapable: Boolean,
    val totalRamBytes: Long,
    val cpuCores: Int,
    val freeDiskBytes: Long,
    val failedChecks: List<CapabilityCheck>,
)

/**
 * Checks whether the current machine is powerful enough to run the built-in `llama-server`
 * local LLM engine. The engine is CPU-only today (no GPU-accelerated build exists yet), so RAM
 * is the dominant constraint, with CPU core count and free disk space (for the binary + at
 * least one quantized model) as secondary gates.
 *
 * This is a one-shot, cheap check — call it on demand (e.g. `remember { }` in a composable)
 * rather than observing it continuously.
 */
object LlamaCapabilityChecker {
    private val log = logger<LlamaCapabilityChecker>()

    /** Minimum total system RAM, in bytes, required to enable the local engine. */
    const val MIN_RAM_BYTES: Long = 16L * 1024 * 1024 * 1024 // 16 GB

    /** Minimum CPU core count required to enable the local engine. */
    const val MIN_CPU_CORES: Int = 4

    /** Minimum free disk space, in bytes, required to enable the local engine. */
    const val MIN_DISK_BYTES: Long = 10L * 1024 * 1024 * 1024 // 10 GB

    fun check(): LlamaCapabilityResult {
        val totalRamBytes = readTotalRamBytes()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val freeDiskBytes = readFreeDiskBytes()

        val failedChecks = buildList {
            if (totalRamBytes < MIN_RAM_BYTES) add(CapabilityCheck.RAM)
            if (cpuCores < MIN_CPU_CORES) add(CapabilityCheck.CPU_CORES)
            if (freeDiskBytes < MIN_DISK_BYTES) add(CapabilityCheck.DISK_SPACE)
        }

        return LlamaCapabilityResult(
            isCapable = failedChecks.isEmpty(),
            totalRamBytes = totalRamBytes,
            cpuCores = cpuCores,
            freeDiskBytes = freeDiskBytes,
            failedChecks = failedChecks,
        )
    }

    /**
     * Total physical system RAM, via [com.sun.management.OperatingSystemMXBean]. Falls back to
     * `0` (treated as "fails the check") if the MXBean isn't the expected implementation on
     * some JVM/packaging combo.
     */
    private fun readTotalRamBytes(): Long = try {
        val osBean = ManagementFactory.getOperatingSystemMXBean()
        if (osBean is com.sun.management.OperatingSystemMXBean) {
            osBean.totalMemorySize
        } else {
            log.warn("OperatingSystemMXBean is not com.sun.management variant; cannot read total RAM")
            0L
        }
    } catch (e: Exception) {
        log.warn("Failed to read total system RAM", e)
        0L
    }

    /** Free disk space at the Askimo home directory (where the binary/models are downloaded). */
    private fun readFreeDiskBytes(): Long = try {
        AskimoHome.base().toFile().usableSpace
    } catch (e: Exception) {
        log.warn("Failed to read free disk space", e)
        0L
    }
}
