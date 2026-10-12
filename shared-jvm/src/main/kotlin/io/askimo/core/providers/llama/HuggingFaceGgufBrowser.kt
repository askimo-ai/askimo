/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.encodeUrlPathSegments
import io.askimo.core.util.httpGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Searches Hugging Face for downloadable `.gguf` models, so Settings > Local LLM can offer far
 * more choices than the small curated [LlamaModelCatalog] without Askimo needing to ship/update
 * a hardcoded list for every new model release.
 *
 * Results are converted to ordinary [LlamaModelEntry] values — [LlamaServerProcessManager] and
 * [LlamaModelDownloader] don't need to know a model came from a live search vs. the static
 * catalog; both resolve purely by file name on disk (see [LlamaModelEntry]'s class docs).
 */
object HuggingFaceGgufBrowser {

    private val log = logger<HuggingFaceGgufBrowser>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Quantization substrings preferred when a repo has many `.gguf` variants — first match wins. */
    private val PREFERRED_QUANT_ORDER = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q4_0", "Q8_0", "Q6_K")

    /**
     * Matches Hugging Face's sharded-GGUF naming convention, e.g. `model-00001-of-00004.gguf`.
     * A shard is not a loadable model on its own — `llama-server --model` needs every shard
     * present alongside it — and this browser only ever downloads the single file the user
     * picked, so surfacing one shard as a standalone result would silently produce a file
     * `llama-server` can't load. Excluded entirely rather than grouped/multi-file-downloaded,
     * since the curated [LlamaModelCatalog] is the place for models that need special handling.
     */
    private val SHARDED_GGUF_PATTERN = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

    /** Max `.gguf` files kept per repo, to avoid one repo with 20 quantizations flooding the list. */
    private const val MAX_FILES_PER_REPO = 3

    class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Searches Hugging Face for GGUF model repos matching [query] (blank = most-downloaded GGUF
     * repos overall), returning up to [limit] repos' worth of `.gguf` file entries
     * (capped at [MAX_FILES_PER_REPO] files per repo).
     *
     * @throws SearchException on network failure or an unparsable response.
     */
    suspend fun search(query: String, limit: Int = 20): List<LlamaModelEntry> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://huggingface.co/api/models" +
            "?search=$encodedQuery&filter=gguf&full=true&sort=downloads&direction=-1&limit=$limit"

        val (status, body) = try {
            httpGet(url, connectTimeoutMs = 8_000, readTimeoutMs = 15_000)
        } catch (e: Exception) {
            throw SearchException("Could not reach Hugging Face: ${e.message}", e)
        }
        if (status !in 200..299) {
            throw SearchException("Hugging Face search failed (HTTP $status).")
        }

        val repos = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw SearchException("Unexpected response from Hugging Face.", e)
        }

        repos.flatMap { repo ->
            val repoId = repo.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val siblings = repo.jsonObject["siblings"]?.jsonArray.orEmpty()
            val ggufFiles = siblings
                .asSequence()
                .mapNotNull { it.jsonObject["rfilename"]?.jsonPrimitive?.contentOrNull }
                .filter { it.endsWith(".gguf", ignoreCase = true) }
                // Vision-projector shards — not standalone chat/embedding models, can't be
                // loaded directly with `--model`.
                .filterNot { it.contains("mmproj", ignoreCase = true) }
                // Multi-part GGUF shards (e.g. "model-00001-of-00004.gguf") — see
                // SHARDED_GGUF_PATTERN's doc for why one shard can't be downloaded/loaded alone.
                .filterNot { SHARDED_GGUF_PATTERN.containsMatchIn(it) }
                .sortedBy { fileName ->
                    val idx = PREFERRED_QUANT_ORDER.indexOfFirst { fileName.contains(it, ignoreCase = true) }
                    if (idx >= 0) idx else PREFERRED_QUANT_ORDER.size
                }
                .take(MAX_FILES_PER_REPO)
                .toList()

            ggufFiles.map { rfilename ->
                val leafName = rfilename.substringAfterLast('/')
                // Namespace the on-disk file name by repo to avoid collisions between repos that
                // happen to use the same generic quant file name (e.g. "model-Q4_K_M.gguf").
                val uniqueFileName = "${repoId.replace("/", "__")}__$leafName"
                LlamaModelEntry(
                    fileName = uniqueFileName,
                    labelKey = "",
                    label = "$repoId — $leafName",
                    approxSizeBytes = 0L, // unknown until download; HF search doesn't reliably expose file size
                    // rfilename can contain spaces/reserved chars (e.g. "#") — splicing it in raw
                    // would break URI parsing or silently change the target. See
                    // encodeUrlPathSegments's doc.
                    downloadUrl = "https://huggingface.co/$repoId/resolve/main/${encodeUrlPathSegments(rfilename)}",
                    sourceRepoId = repoId,
                )
            }
        }.also { log.info("Hugging Face GGUF search for \"{}\" returned {} file(s)", query, it.size) }
    }
}
