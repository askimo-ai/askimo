/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import io.askimo.core.logging.logger
import io.askimo.core.security.KeychainManager.OperatingSystem.LINUX
import io.askimo.core.security.KeychainManager.OperatingSystem.MACOS
import io.askimo.core.security.KeychainManager.OperatingSystem.UNKNOWN
import io.askimo.core.security.KeychainManager.OperatingSystem.WINDOWS
import io.askimo.core.util.ProcessBuilderExt
import java.io.IOException

/**
 * Minimal native bindings for the subset of the Windows Credential Manager API (advapi32.dll)
 * needed to store/retrieve/delete a generic credential.
 *
 * jna-platform does not ship Wincred/CredRead/CredWrite bindings (verified against 5.19.1), so
 * we declare the native entry points ourselves. Loading with [W32APIOptions.UNICODE_OPTIONS]
 * makes JNA's function mapper resolve e.g. "CredRead" -> the real export "CredReadW".
 *
 * This still avoids spawning any process at all (unlike the previous hidden-PowerShell +
 * inline-C#-P/Invoke approach, which Windows Defender flagged as credential-stealing malware)
 * and keeps the secret out of command-line args (unlike `cmdkey /pass:...`).
 */
internal interface CredentialApi : StdCallLibrary {
    @Suppress("FunctionName")
    fun CredWrite(
        credential: CREDENTIAL,
        flags: Int,
    ): Boolean

    @Suppress("FunctionName")
    fun CredRead(
        targetName: WString,
        type: Int,
        flags: Int,
        pCredential: PointerByReference,
    ): Boolean

    @Suppress("FunctionName")
    fun CredFree(credential: Pointer)

    @Suppress("FunctionName")
    fun CredDelete(
        targetName: WString,
        type: Int,
        flags: Int,
    ): Boolean

    companion object {
        val INSTANCE: CredentialApi =
            Native.load("Advapi32", CredentialApi::class.java, W32APIOptions.UNICODE_OPTIONS)
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
    }
}

/** Mirrors the native `CREDENTIALW` struct from wincred.h. */
@Structure.FieldOrder(
    "flags",
    "type",
    "targetName",
    "comment",
    "lastWritten",
    "credentialBlobSize",
    "credentialBlob",
    "persist",
    "attributeCount",
    "attributes",
    "targetAlias",
    "userName",
)
internal open class CREDENTIAL : Structure {
    @Suppress("unused")
    @JvmField
    var flags: Int = 0

    @JvmField var type: Int = 0

    @JvmField var targetName: WString? = null

    @Suppress("unused")
    @JvmField
    var comment: WString? = null

    @Suppress("unused")
    @JvmField
    var lastWritten: WinBase.FILETIME = WinBase.FILETIME()

    @JvmField var credentialBlobSize: Int = 0

    @JvmField var credentialBlob: Pointer? = null

    @JvmField var persist: Int = 0

    @Suppress("unused")
    @JvmField
    var attributeCount: Int = 0

    @Suppress("unused")
    @JvmField
    var attributes: Pointer? = null

    @Suppress("unused")
    @JvmField
    var targetAlias: WString? = null

    @JvmField var userName: WString? = null

    constructor() : super()

    constructor(p: Pointer) : super(p) {
        read()
    }
}

/**
 * Manages secure storage of API keys using system keychain.
 * Falls back to encrypted storage if keychain is not available.
 */
object KeychainManager {
    private val log = logger<KeychainManager>()
    private const val SERVICE_NAME = "askimo-cli"

    /**
     * Stores an secret key securely in the system keychain.
     *
     * @param keyIdentifier The keyIdentifier name that is unique
     * @param secretKey The secret key to store
     * @return true if stored successfully in keychain, false if keychain failed
     */
    fun storeSecretKey(
        keyIdentifier: String,
        secretKey: String,
    ): Boolean = try {
        when (getOperatingSystem()) {
            MACOS -> storeMacOSKeychain(keyIdentifier, secretKey)

            LINUX -> storeLinuxKeyring(keyIdentifier, secretKey)

            WINDOWS -> storeWindowsCredentialManager(keyIdentifier, secretKey)

            UNKNOWN -> {
                log.warn("Unknown operating system, keychain storage not available")
                false
            }
        }
    } catch (e: Exception) {
        log.error("Failed to store API key in keychain: ${e.message}", e)
        false
    }

    /**
     * Retrieves an secret key from the system keychain.
     *
     * @param provider The provider name
     * @return The secret key if found, null if not found or keychain failed
     */
    fun retrieveSecretKey(provider: String): String? = try {
        when (getOperatingSystem()) {
            MACOS -> retrieveMacOSKeychain(provider)

            LINUX -> retrieveLinuxKeyring(provider)

            WINDOWS -> retrieveWindowsCredentialManager(provider)

            UNKNOWN -> {
                log.debug("Unknown operating system, keychain retrieval not available")
                null
            }
        }
    } catch (e: Exception) {
        log.error("Failed to retrieve API key from keychain: ${e.message}", e)
        null
    }

    /**
     * Removes an API key from the system keychain.
     *
     * @param keyIdentifier The unique name
     * @return true if removed successfully, false otherwise
     */
    fun removeSecretKey(keyIdentifier: String): Boolean = try {
        when (getOperatingSystem()) {
            MACOS -> removeMacOSKeychain(keyIdentifier)
            LINUX -> removeLinuxKeyring(keyIdentifier)
            WINDOWS -> removeWindowsCredentialManager(keyIdentifier)
            UNKNOWN -> false
        }
    } catch (e: Exception) {
        log.error("Failed to remove API key from keychain: ${e.message}", e)
        false
    }

    private fun storeMacOSKeychain(
        keyIdentifier: String,
        secretKey: String,
    ): Boolean {
        val account = "askimo-$keyIdentifier"
        val process =
            ProcessBuilderExt(
                "security",
                "add-generic-password",
                "-a",
                account,
                "-s",
                SERVICE_NAME,
                "-w",
                secretKey,
                "-U", // Update if exists
            ).start()

        val exitCode = process.waitFor()
        return exitCode == 0
    }

    private fun retrieveMacOSKeychain(keyIdentifier: String): String? {
        val account = "askimo-$keyIdentifier"
        val process =
            ProcessBuilderExt(
                "security",
                "find-generic-password",
                "-a",
                account,
                "-s",
                SERVICE_NAME,
                "-w", // Output password only
            ).start()

        val exitCode = process.waitFor()
        return if (exitCode == 0) {
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        } else {
            null
        }
    }

    private fun removeMacOSKeychain(keyIdentifier: String): Boolean {
        val account = "askimo-$keyIdentifier"
        val process =
            ProcessBuilderExt(
                "security",
                "delete-generic-password",
                "-a",
                account,
                "-s",
                SERVICE_NAME,
            ).start()

        val exitCode = process.waitFor()
        return exitCode == 0
    }

    private fun storeLinuxKeyring(
        keyIdentifier: String,
        secretKey: String,
    ): Boolean {
        // Try secret-tool first (libsecret)
        if (isCommandAvailable("secret-tool")) {
            try {
                val process =
                    ProcessBuilderExt(
                        "secret-tool",
                        "store",
                        "--label",
                        "Askimo API Key for $keyIdentifier",
                        "service",
                        SERVICE_NAME,
                        "account",
                        "askimo-$keyIdentifier",
                    ).start()

                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(secretKey)
                    writer.flush()
                }

                val exitCode = process.waitFor()
                return exitCode == 0
            } catch (e: Exception) {
                log.error("Failed to store API key with secret-tool: ${e.message}", e)
                return false
            }
        }

        // Fallback to gnome-keyring if available
        if (isCommandAvailable("gnome-keyring-daemon")) {
            // Implementation for gnome-keyring would go here
            // For now, return false to use encrypted fallback
            log.debug("gnome-keyring-daemon available but not implemented")
            return false
        }

        log.debug("No Linux keyring implementation available (secret-tool not found)")
        return false
    }

    private fun retrieveLinuxKeyring(keyIdentifier: String): String? {
        if (isCommandAvailable("secret-tool")) {
            try {
                val process =
                    ProcessBuilderExt(
                        "secret-tool",
                        "lookup",
                        "service",
                        SERVICE_NAME,
                        "account",
                        "askimo-$keyIdentifier",
                    ).start()

                val exitCode = process.waitFor()
                return if (exitCode == 0) {
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                        .takeIf { it.isNotBlank() }
                } else {
                    log.debug("secret-tool lookup failed with exit code $exitCode for provider $keyIdentifier")
                    null
                }
            } catch (e: Exception) {
                log.error("Failed to retrieve API key with secret-tool: ${e.message}", e)
                return null
            }
        }

        log.debug("secret-tool not available for retrieving API key")
        return null
    }

    private fun removeLinuxKeyring(keyIdentifier: String): Boolean {
        if (isCommandAvailable("secret-tool")) {
            try {
                val process =
                    ProcessBuilderExt(
                        "secret-tool",
                        "clear",
                        "service",
                        SERVICE_NAME,
                        "account",
                        "askimo-$keyIdentifier",
                    ).start()

                val exitCode = process.waitFor()
                return exitCode == 0
            } catch (e: Exception) {
                log.error("Failed to remove API key with secret-tool: ${e.message}", e)
                return false
            }
        }

        log.debug("secret-tool not available for removing API key")
        return false
    }

    /**
     * Stores a secret in Windows Credential Manager via direct native calls (JNA → advapi32.dll
     * CredWrite). We intentionally do NOT shell out to `cmdkey` here: `cmdkey /pass:...` exposes
     * the secret in plaintext on the process command line (visible to other processes / task
     * manager) for the life of the child process.
     */
    private fun storeWindowsCredentialManager(
        keyIdentifier: String,
        secretKey: String,
    ): Boolean {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"
        var blobMemory: Memory? = null
        return try {
            val blobBytes = secretKey.toByteArray(Charsets.UTF_16LE)
            blobMemory = Memory(blobBytes.size.toLong()).apply { write(0, blobBytes, 0, blobBytes.size) }

            val credential = CREDENTIAL()
            credential.type = CredentialApi.CRED_TYPE_GENERIC
            credential.targetName = WString(target)
            credential.credentialBlobSize = blobBytes.size
            credential.credentialBlob = blobMemory
            credential.persist = CredentialApi.CRED_PERSIST_LOCAL_MACHINE
            credential.userName = WString("askimo")

            val result = CredentialApi.INSTANCE.CredWrite(credential, 0)
            if (!result) {
                log.error("CredWrite failed for target $target (error ${Native.getLastError()})")
            }
            result
        } catch (e: Exception) {
            log.error("Failed to store API key via Credential Manager: ${e.message}", e)
            false
        } finally {
            blobMemory?.clear()
        }
    }

    /**
     * Retrieves a secret from Windows Credential Manager via direct native calls (JNA → advapi32.dll
     * CredRead). This replaces a previous implementation that spawned a hidden `powershell.exe`
     * process compiling inline C# via `Add-Type` to P/Invoke `CredRead` — that pattern (hidden
     * window + ExecutionPolicy Bypass + dynamically compiled P/Invoke into advapi32.dll for
     * credential access) is structurally identical to known credential-stealing malware and was
     * being flagged by Windows Defender/AV (e.g. Trojan:Win32/Commando.A!ml). Calling the Win32
     * API directly avoids spawning any process at all.
     */
    private fun retrieveWindowsCredentialManager(keyIdentifier: String): String? {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"
        val pCredential = PointerByReference()
        return try {
            val found = CredentialApi.INSTANCE.CredRead(WString(target), CredentialApi.CRED_TYPE_GENERIC, 0, pCredential)
            if (!found) {
                log.debug("No credential found for target $target (error ${Native.getLastError()})")
                return null
            }
            try {
                val credential = CREDENTIAL(pCredential.value)
                val blob = credential.credentialBlob?.getByteArray(0, credential.credentialBlobSize)
                blob?.let { String(it, Charsets.UTF_16LE) }
            } finally {
                CredentialApi.INSTANCE.CredFree(pCredential.value)
            }
        } catch (e: Exception) {
            log.error("Failed to retrieve API key via Credential Manager: ${e.message}", e)
            null
        }
    }

    /**
     * Removes a secret from Windows Credential Manager via direct native calls (JNA → advapi32.dll
     * CredDelete), for consistency with the store/retrieve paths above.
     */
    private fun removeWindowsCredentialManager(keyIdentifier: String): Boolean {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"
        return try {
            CredentialApi.INSTANCE.CredDelete(WString(target), CredentialApi.CRED_TYPE_GENERIC, 0)
        } catch (e: Exception) {
            log.error("Failed to remove API key via Credential Manager: ${e.message}", e)
            false
        }
    }

    private fun getOperatingSystem(): OperatingSystem {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") || osName.contains("darwin") -> MACOS
            osName.contains("linux") -> LINUX
            osName.contains("windows") -> WINDOWS
            else -> UNKNOWN
        }
    }

    private fun isCommandAvailable(command: String): Boolean = try {
        val process = ProcessBuilderExt("which", command).start()
        process.waitFor() == 0
    } catch (_: IOException) {
        false
    }

    enum class OperatingSystem {
        MACOS,
        LINUX,
        WINDOWS,
        UNKNOWN,
    }
}
