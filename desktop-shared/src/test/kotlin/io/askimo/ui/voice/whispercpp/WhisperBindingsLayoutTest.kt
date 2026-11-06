/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.ui.voice.whispercpp

import java.lang.foreign.MemoryLayout.PathElement.groupElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for a Windows-only `ClassCastException` (surfacing as
 * `ExceptionInInitializerError` from [io.askimo.ui.voice.impl.whispercpp.WhisperCppFfmSpeechToTextService.ensureContextLoaded])
 * caused by jextract deriving `whisper_h$shared.C_LONG` from the generation host's canonical
 * `"long"` layout — 8 bytes on LP64 macOS/Linux, but only 4 bytes on LLP64 Windows. Every struct
 * field that actually uses `C_LONG` (`n_heads`, `n_grammar_rules`, `i_start_rule`,
 * `dtw_mem_size`) is a C `size_t`, which is 8 bytes on every 64-bit target we ship for — so
 * `C_LONG` must be a fixed, portable 8-byte layout, not derived from the host's `"long"` mapping.
 *
 * These tests deliberately never call [io.askimo.ui.voice.impl.whispercpp.NativeLibraryLoader] or
 * load the whisper native library: merely touching a generated struct's `layout()` forces
 * `whisper_h$shared`'s static initializer to run (JLS 12.4.1 — accessing a static field only
 * initializes its *declaring* class, and `C_LONG`/`C_INT`/etc. are declared there even though
 * `whisper_h` and the struct classes just reference them), which is exactly where the original
 * crash occurred. That lets this run on every CI OS/arch with no native binary or model file
 * required, as a fast first line of defense — see also
 * `WhisperCppFfmSpeechToTextServiceIntegrationTest` for an end-to-end check that drives the real
 * native code path.
 */
class WhisperBindingsLayoutTest {

    @Test
    fun `whisper struct layouts initialize without throwing`() {
        whisper_context_params.layout()
        whisper_full_params.layout()
        whisper_aheads.layout()
        whisper_vad_params.layout()
    }

    @Test
    fun `size_t-backed fields use a portable 8-byte layout`() {
        // size_t is 8 bytes on every 64-bit target we support (macOS, Linux, Windows x64/arm64).
        // Locks C_LONG's semantics so a future revert to deriving it from
        // Linker.nativeLinker().canonicalLayouts().get("long") (4 bytes on Windows) fails fast
        // in CI on every OS instead of only crashing for Windows users.
        assertEquals(8L, whisper_h.C_LONG.byteSize())
    }

    @Test
    fun `size_t struct fields are 8-byte aligned regardless of host OS`() {
        val contextParamsLayout = whisper_context_params.layout()
        assertEquals(0L, contextParamsLayout.byteOffset(groupElement("dtw_mem_size")) % 8)

        val aheadsLayout = whisper_aheads.layout()
        assertEquals(0L, aheadsLayout.byteOffset(groupElement("n_heads")) % 8)

        val fullParamsLayout = whisper_full_params.layout()
        assertEquals(0L, fullParamsLayout.byteOffset(groupElement("n_grammar_rules")) % 8)
        assertEquals(0L, fullParamsLayout.byteOffset(groupElement("i_start_rule")) % 8)
    }
}
