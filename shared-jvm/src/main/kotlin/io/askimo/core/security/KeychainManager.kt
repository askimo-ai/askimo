/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.security

import io.askimo.core.logging.logger
import io.askimo.core.security.KeychainManager.OperatingSystem.LINUX
import io.askimo.core.security.KeychainManager.OperatingSystem.MACOS
import io.askimo.core.security.KeychainManager.OperatingSystem.UNKNOWN
import io.askimo.core.security.KeychainManager.OperatingSystem.WINDOWS
import io.askimo.core.util.ProcessBuilderExt
import java.io.IOException

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

    private fun storeWindowsCredentialManager(
        keyIdentifier: String,
        secretKey: String,
    ): Boolean {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"

        try {
            val process =
                ProcessBuilderExt(
                    "cmdkey",
                    "/generic:$target",
                    "/user:askimo",
                    "/pass:$secretKey",
                ).start()

            val exitCode = process.waitFor()
            return exitCode == 0
        } catch (e: Exception) {
            log.error("Failed to store API key with cmdkey: ${e.message}", e)
            return false
        }
    }

    private fun retrieveWindowsCredentialManager(keyIdentifier: String): String? {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"

        // Try PowerShell approach using CredentialManager API
        try {
            // Build PowerShell script with proper escaping
            val csCode = """using System;
using System.Runtime.InteropServices;
using System.Text;

public class CredentialManager {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct CREDENTIAL {
        public uint Flags;
        public uint Type;
        public string TargetName;
        public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    [DllImport(\""advapi32.dll\"", EntryPoint = \""CredReadW\"", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool CredRead(string target, uint type, int reservedFlag, out IntPtr credentialPtr);

    [DllImport(\""advapi32.dll\"", SetLastError = true)]
    public static extern bool CredFree(IntPtr cred);

    public static string GetPassword(string target) {
        IntPtr credPtr;
        if (CredRead(target, 1, 0, out credPtr)) {
            try {
                CREDENTIAL cred = (CREDENTIAL)Marshal.PtrToStructure(credPtr, typeof(CREDENTIAL));
                if (cred.CredentialBlobSize > 0) {
                    byte[] passwordBytes = new byte[cred.CredentialBlobSize];
                    Marshal.Copy(cred.CredentialBlob, passwordBytes, 0, (int)cred.CredentialBlobSize);
                    return Encoding.Unicode.GetString(passwordBytes);
                }
                return \""\"";
            } finally {
                CredFree(credPtr);
            }
        }
        return null;
    }
}"""

            val powershellScript = "Add-Type -TypeDefinition '$csCode'; " +
                "\$password = [CredentialManager]::GetPassword('$target'); " +
                "if (\$password -ne \$null) { Write-Output \"FOUND:\$password\"; exit 0 } " +
                "else { [Console]::Error.WriteLine('Credential not found'); exit 1 }"

            val process =
                ProcessBuilderExt(
                    "powershell.exe",
                    "-WindowStyle",
                    "Hidden",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    powershellScript,
                ).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.startsWith("FOUND:")) {
                return output.removePrefix("FOUND:")
            } else if (errorOutput.isNotBlank()) {
                log.error("PowerShell credential retrieval failed with exit code $exitCode: $errorOutput")
            } else {
                log.error("PowerShell credential retrieval failed with exit code $exitCode (no error message)")
            }
        } catch (e: Exception) {
            log.error("PowerShell credential retrieval failed: ${e.message}", e)
        }

        // PowerShell failed, check if credential exists via cmdkey
        try {
            val checkProcess = ProcessBuilderExt("cmdkey", "/list:$target").start()
            val checkOutput = checkProcess.inputStream.bufferedReader().readText()
            val checkExitCode = checkProcess.waitFor()

            if (checkExitCode == 0 && checkOutput.contains(target)) {
                log.warn("Credential exists in Windows Credential Manager but cannot be retrieved")
            }
        } catch (e: Exception) {
            log.error("Failed to check credential existence: ${e.message}", e)
        }

        return null
    }

    private fun removeWindowsCredentialManager(keyIdentifier: String): Boolean {
        val target = "$SERVICE_NAME:askimo-$keyIdentifier"

        try {
            val process =
                ProcessBuilderExt(
                    "cmdkey",
                    "/delete:$target",
                ).start()

            val exitCode = process.waitFor()
            return exitCode == 0
        } catch (e: Exception) {
            log.error("Failed to remove API key with cmdkey: ${e.message}", e)
            return false
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
