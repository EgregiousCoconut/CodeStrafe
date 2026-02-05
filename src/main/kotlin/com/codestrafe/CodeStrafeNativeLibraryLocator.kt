package com.codestrafe

import com.github.kwhat.jnativehook.NativeLibraryLocator
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * CodeStrafeNativeLibraryLocator
 *
 * JNativeHook sometimes fails to locate/extract its native library when running inside
 * IDE/plugin classloaders. JNativeHook supports overriding library loading by providing
 * a custom NativeLibraryLocator.  [oai_citation:1‡GitHub](https://github.com/kwhat/jnativehook/issues/394?utm_source=chatgpt.com)
 *
 * This locator extracts the platform-specific native library from the JNativeHook jar
 * resources into a temp directory and returns that file for loading.
 */
class CodeStrafeNativeLibraryLocator : NativeLibraryLocator {

    override fun getLibraries(): Iterator<File> {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        val arch = System.getProperty("os.arch").lowercase(Locale.getDefault())

        val (osDir, libName) = when {
            os.contains("mac") || os.contains("darwin") -> "macos" to "libJNativeHook.dylib"
            os.contains("win") -> "windows" to "JNativeHook.dll"
            else -> "linux" to "libJNativeHook.so"
        }

        val archDir = when {
            // macOS common
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            // linux 32-bit-ish fallback
            arch.contains("86") -> "x86"
            else -> "x86_64"
        }

        // JNativeHook 2.2.x jars typically store libs under:
        // /com/github/kwhat/jnativehook/lib/<os>/<arch>/<lib>
        val resourcePath = "/com/github/kwhat/jnativehook/lib/$osDir/$archDir/$libName"

        val input = this::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException(
                "Could not find JNativeHook native library resource: $resourcePath " +
                        "(os=$os, arch=$arch)"
            )

        val outDir = File(System.getProperty("java.io.tmpdir"), "codestrafe-jnativehook").apply { mkdirs() }
        val outFile = File(outDir, libName)

        input.use { ins ->
            FileOutputStream(outFile).use { fos ->
                ins.copyTo(fos)
            }
        }

        // Return the extracted lib file so JNativeHook can load it.
        return listOf(outFile).iterator()
    }
}