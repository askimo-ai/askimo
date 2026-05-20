/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.util

import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID

/**
 * Resolves a stable, hardware-bound device identifier for the current machine.
 *
 * The raw value is **always SHA-256 hashed** and formatted as a UUID so:
 *  - the shape is consistent across platforms
 *  - no raw hardware serial / MAC address is ever stored or transmitted
 *
 * Resolution order per platform:
 *
 * | OS      | Primary source                   | Secondary                       | Tertiary          |
 * |---------|----------------------------------|---------------------------------|-------------------|
 * | macOS   | `ioreg` → `IOPlatformUUID`       | MAC address hash                | —                 |
 * | Linux   | `/etc/machine-id`                | `/var/lib/dbus/machine-id`      | MAC address hash  |
 * | Windows | `wmic csproduct get UUID`        | Registry `MachineGuid`          | MAC address hash  |
 * | Unknown | MAC address hash                 | —                               | —                 |
 *
 * Returns `null` only when every strategy fails (extremely unlikely in practice).
 * The caller falls back to a persisted random UUID in that case.
 */
object MachineId {

    /** Attempt to resolve a hardware-bound ID; returns null if every strategy fails. */
    fun resolve(): String? {
        val raw = when (os()) {
            OS.MACOS -> macOsUuid() ?: macAddress()
            OS.LINUX -> linuxMachineId() ?: macAddress()
            OS.WINDOWS -> windowsUuid() ?: windowsRegistryMachineGuid() ?: macAddress()
            OS.UNKNOWN -> macAddress()
        } ?: return null

        return sha256uuid(raw)
    }

    // ── macOS ─────────────────────────────────────────────────────────────────

    private fun macOsUuid(): String? = runCatching {
        val proc = ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines
                .firstOrNull { it.contains("IOPlatformUUID") }
                ?.substringAfter("= ")
                ?.trim()
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() }
        }.also { proc.waitFor() }
    }.getOrNull()

    // ── Linux ─────────────────────────────────────────────────────────────────

    private fun linuxMachineId(): String? {
        for (path in listOf("/etc/machine-id", "/var/lib/dbus/machine-id")) {
            val text = runCatching { File(path).readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private fun windowsUuid(): String? = runCatching {
        val proc = ProcessBuilder("wmic", "csproduct", "get", "UUID")
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && it != "UUID" }
                ?.takeIf {
                    it.isNotBlank() &&
                        it.uppercase() != "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF" &&
                        it.uppercase() != "00000000-0000-0000-0000-000000000000"
                }
        }.also { proc.waitFor() }
    }.getOrNull()

    /**
     * Reads `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid` — written once
     * at Windows install time, survives hardware changes better than wmic on VMs.
     */
    private fun windowsRegistryMachineGuid(): String? = runCatching {
        val proc = ProcessBuilder(
            "reg",
            "query",
            "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
            "/v",
            "MachineGuid",
        ).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().useLines { lines ->
            lines
                .mapNotNull { line ->
                    // Output: "    MachineGuid    REG_SZ    XXXXXXXX-XXXX-..."
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3 && parts[0] == "MachineGuid") parts.last() else null
                }
                .firstOrNull { it.isNotBlank() }
        }.also { proc.waitFor() }
    }.getOrNull()

    // ── Cross-platform fallback ───────────────────────────────────────────────

    /**
     * Concatenates the MAC addresses of all physical (non-loopback, non-virtual,
     * non-hypervisor) interfaces and hashes the result.
     *
     * Virtual/hypervisor interfaces are excluded by checking known OUI prefixes
     * used by VMware, VirtualBox, Hyper-V, QEMU, Parallels, and Docker.
     */
    private fun macAddress(): String? {
        val macs = NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { nic ->
                !nic.isLoopback &&
                    !nic.isVirtual &&
                    nic.hardwareAddress != null &&
                    nic.hardwareAddress.isNotEmpty() &&
                    !isVirtualOui(nic.hardwareAddress)
            }
            ?.mapNotNull { nic ->
                nic.hardwareAddress.joinToString(":") { "%02X".format(it) }
            }
            ?.sorted() // deterministic order regardless of enumeration
            ?.joinToString("|")
            ?.takeIf { it.isNotBlank() }

        // If filtering removed everything (all-virtual machine), fall back to
        // including ALL non-loopback MACs so we still get *something* unique.
        if (macs != null) return macs

        return NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { !it.isLoopback && it.hardwareAddress != null && it.hardwareAddress.isNotEmpty() }
            ?.mapNotNull { nic -> nic.hardwareAddress.joinToString(":") { "%02X".format(it) } }
            ?.sorted()
            ?.joinToString("|")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns true when the first 3 bytes (OUI) of a MAC address are known to
     * belong to virtual network adapters (VMware, VirtualBox, Hyper-V, QEMU,
     * Parallels, Docker bridge, etc.).
     *
     * Not exhaustive — covers the most common hypervisors to avoid false
     * collisions between VMs that share the same vendor prefix.
     */
    private fun isVirtualOui(hw: ByteArray): Boolean {
        if (hw.size < 3) return false
        val oui = "%02X:%02X:%02X".format(hw[0], hw[1], hw[2])
        return oui in VIRTUAL_OUIS
    }

    /**
     * Well-known OUI prefixes assigned to virtual/hypervisor adapters.
     * Sources: IEEE OUI registry + vendor documentation.
     */
    private val VIRTUAL_OUIS = setOf(
        // VMware
        "00:0C:29", "00:50:56", "00:05:69",
        // VirtualBox
        "08:00:27",
        // Microsoft Hyper-V / Virtual PC
        "00:15:5D",
        // QEMU / KVM
        "52:54:00",
        // Parallels
        "00:1C:42",
        // Docker (Linux bridge default)
        "02:42:AC",
        // Xen
        "00:16:3E",
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * SHA-256 hashes [input] and formats the first 128 bits as a UUID string.
     *
     * We construct the UUID manually from the raw digest bytes instead of
     * delegating to [UUID.nameUUIDFromBytes] (which internally runs MD5 and
     * would discard our SHA-256 work). Version 5 and RFC-4122 variant bits are
     * applied to produce a well-formed UUID.
     */
    private fun sha256uuid(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        // Apply version 5 (name-based SHA) bits: top nibble of byte 6 = 0101
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        // Apply RFC-4122 variant bits: top two bits of byte 8 = 10
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        // Construct UUID from raw bytes — no additional hashing
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (digest[i].toLong() and 0xff)
        for (i in 8..15) lsb = (lsb shl 8) or (digest[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }

    private enum class OS { MACOS, LINUX, WINDOWS, UNKNOWN }

    private fun os(): OS {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("mac") || name.contains("darwin") -> OS.MACOS
            name.contains("linux") -> OS.LINUX
            name.contains("windows") -> OS.WINDOWS
            else -> OS.UNKNOWN
        }
    }
}
