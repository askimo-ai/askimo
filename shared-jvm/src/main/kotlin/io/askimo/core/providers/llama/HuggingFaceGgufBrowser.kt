/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.providers.llama

import io.askimo.core.logging.logger
import io.askimo.core.util.encodeUrlPathSegments
import io.askimo.core.util.httpGet
import io.askimo.core.util.httpHeadContentLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Searches Hugging Face for downloadable `.gguf` models, so Settings > Local LLM can offer chat
 * models without Askimo needing to ship/maintain a hardcoded, inevitably-stale list (see
 * [LlamaModelCatalog]'s class doc — only embedding models stay curated there). [popularModels]
 * is what's shown by default (most-downloaded GGUF repos overall); [search] additionally powers
 * the explicit search box.
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
     * Hugging Face `pipeline_tag` values that correspond to chat/instruct-capable models.
     * Repos tagged with anything else (e.g. `"object-detection"`, `"feature-extraction"`,
     * `"image-text-to-text"`) aren't usable as a langchain4j chat model even though they ship a
     * `.gguf` file and pass the `filter=gguf` search — see [search]'s `restrictToChatModels` param.
     */
    private val CHAT_PIPELINE_TAGS = setOf("text-generation", "text2text-generation", "conversational")

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
     * @param restrictToChatModels When `true` (the default), only repos whose `pipeline_tag` is
     * one of [CHAT_PIPELINE_TAGS] are returned — filters out non-chat GGUF repos (vision/grounding/
     * detection/embedding models, etc.) that otherwise look identical to a chat model from the
     * file listing alone. Repos with a missing/unrecognized `pipeline_tag` are excluded too, since
     * there's no way to tell they're safe. Pass `false` to see every GGUF repo Hugging Face
     * returns, unfiltered — for models HF hasn't tagged correctly, or tags Askimo doesn't
     * recognize yet.
     * @throws SearchException on network failure or an unparsable response.
     */
    suspend fun search(
        query: String,
        limit: Int = 20,
        restrictToChatModels: Boolean = true,
    ): List<LlamaModelEntry> = withContext(Dispatchers.IO) {
        val repos = fetchRepos(query, limit)
        parseEntries(repos) { tag ->
            // See restrictToChatModels's doc — unknown/missing tags are excluded along with
            // known-non-chat ones, since there's no safe way to tell those apart here.
            !restrictToChatModels || tag in CHAT_PIPELINE_TAGS
        }.also { log.info("Hugging Face GGUF search for \"{}\" returned {} file(s)", query, it.size) }
    }

    /** Fetches [limit] raw repos matching [query] from Hugging Face's models API, newest first by downloads. */
    private fun fetchRepos(query: String, limit: Int): List<JsonElement> {
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

        return try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw SearchException("Unexpected response from Hugging Face.", e)
        }
    }

    /**
     * Converts raw Hugging Face repo JSON into [LlamaModelEntry] values, keeping only repos
     * whose `pipeline_tag` satisfies [isAllowedTag] — see [search]'s `restrictToChatModels` doc
     * (the default predicate) and [bestEntryForFamily] (a looser predicate for [starterPicks]).
     */
    private fun parseEntries(
        repos: List<JsonElement>,
        isAllowedTag: (String?) -> Boolean,
    ): List<LlamaModelEntry> = repos.flatMap { repo ->
        val repoId = repo.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
        val pipelineTag = repo.jsonObject["pipeline_tag"]?.jsonPrimitive?.contentOrNull
        if (!isAllowedTag(pipelineTag)) return@flatMap emptyList()
        val downloads = repo.jsonObject["downloads"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val likes = repo.jsonObject["likes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val lastModified = repo.jsonObject["lastModified"]?.jsonPrimitive?.contentOrNull
        val createdAt = repo.jsonObject["createdAt"]?.jsonPrimitive?.contentOrNull
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
                downloads = downloads,
                likes = likes,
                lastModified = lastModified,
                createdAt = createdAt,
                pipelineTag = pipelineTag,
            )
        }
    }

    /**
     * Hugging Face's most-downloaded GGUF repos overall — what Settings > Local LLM shows by
     * default (see [LlamaModelCatalog]'s class doc for why chat models aren't hardcoded). Just
     * [search] with a blank query; named separately so call sites read as "browse popular" rather
     * than "the user searched for nothing".
     *
     * @throws SearchException on network failure or an unparsable response (same as [search]).
     */
    suspend fun popularModels(
        limit: Int = 20,
        restrictToChatModels: Boolean = true,
    ): List<LlamaModelEntry> = search(query = "", limit = limit, restrictToChatModels = restrictToChatModels)

    /** Preferred chat-model families used to pick [starterPicks]'s quick-setup recommendations. */
    private val STARTER_FAMILIES = listOf("gemma", "llama", "qwen")

    /**
     * Sweet-spot file-size range (decimal GB) for [starterPicks]: downloadable quickly, fits the
     * 16 GB-RAM minimum [LlamaCapabilityChecker] requires, and avoids toy 1-2B quants.
     */
    private val STARTER_SIZE_RANGE = 3_000_000_000L..5_000_000_000L

    /**
     * How many raw repos to fetch per family before filtering/ranking — deliberately generous:
     * [search]'s [CHAT_PIPELINE_TAGS] filter excludes current-generation multimodal-capable chat
     * families entirely (verified live: every currently-trending Gemma GGUF repo is tagged
     * `image-text-to-text`, not `text-generation`/`conversational`, even though the actual `.gguf`
     * weights downloaded here are plain text-only language-model weights — see [search]'s
     * `mmproj` exclusion), so [bestEntryForFamily] uses [STARTER_DENYLISTED_TAGS] instead and
     * needs a wider raw pool to find a same-size-ballpark match.
     */
    private const val STARTER_RAW_LIMIT = 25

    /**
     * `pipeline_tag` values that are *never* a usable chat model regardless of family name match
     * (audio/vision/image-generation tasks) — used instead of [CHAT_PIPELINE_TAGS] for
     * [bestEntryForFamily], since by that point the family name itself (Gemma/Llama/Qwen) is
     * already the trust signal, not the tag.
     */
    private val STARTER_DENYLISTED_TAGS = setOf(
        "text-to-speech",
        "automatic-speech-recognition",
        "audio-classification",
        "object-detection",
        "image-classification",
        "image-segmentation",
        "text-to-image",
        "feature-extraction",
        "depth-estimation",
    )

    /**
     * Matches a leading parameter-count hint in a repo/file name, e.g. `8B` in
     * `Meta-Llama-3.1-8B-Instruct-GGUF` or `9b` in `gemma-2-9b-it`. Used to prefer repos in the
     * ~6-10B range likely to quantize into [STARTER_SIZE_RANGE] — a generic size heuristic
     * rather than a specific hardcoded model name/version, so it keeps working as families
     * release new versions.
     */
    private val PARAM_COUNT_PATTERN = Regex("""(\d+(?:\.\d+)?)[Bb](?![a-zA-Z0-9])""")

    /** Sweet-spot parameter-count range (billions) likely to quantize into [STARTER_SIZE_RANGE] at Q4. */
    private val STARTER_PARAM_RANGE = 5.5..10.0

    /** How many size-ranked candidates to resolve via `HEAD` per family, bounding network cost. */
    private const val STARTER_SIZE_CHECK_CANDIDATES = 6

    /**
     * Repo-namespace prefixes (lowercased, trailing `/`) preferred for [starterPicks]'s
     * recommendations — official model publishers plus well-known, reputable GGUF quantizers.
     * Without this, the family-name + param-count ranking alone can surface an arbitrary
     * uncensored/abliterated community fine-tune ahead of the mainline instruct model simply
     * because it happens to be the right size and wasn't denylisted by pipeline tag — not a good
     * first impression for a first-run quick-setup recommendation.
     */
    private val STARTER_TRUSTED_PUBLISHERS = listOf(
        "google/",
        "meta-llama/",
        "qwen/",
        "mistralai/",
        "microsoft/",
        "unsloth/",
        "bartowski/",
        "lmstudio-community/",
        "ggml-org/",
    )

    /**
     * Picks one well-known chat model per entry in [families] (default: Gemma, Llama, Qwen — see
     * `LocalLlmQuickSetupDialog`'s quick-setup recommendations). Unlike [popularModels] (sorted
     * purely by raw download count, which — verified live — often surfaces non-chat/esoteric
     * repos before reaching a recognizable family name), this searches each family name directly,
     * ranks candidates by a parameter-count heuristic ([PARAM_COUNT_PATTERN]/[STARTER_PARAM_RANGE])
     * to prefer the right-sized variant, then resolves actual file size via
     * [httpHeadContentLength] (Hugging Face's search API doesn't expose it — see
     * `approxSizeBytes = 0L` above) to confirm/pick a quant inside [STARTER_SIZE_RANGE]. Falls
     * back to the best-ranked match regardless of confirmed size rather than omitting a family
     * entirely, since an imperfect recommendation beats none.
     *
     * Network calls (one search + a few `HEAD` requests per family) run in parallel across
     * families; a family that fails entirely (network error, no matches) is simply omitted, so
     * one bad family doesn't block the others — callers should expect fewer than
     * `families.size` results back on a bad connection.
     */
    suspend fun starterPicks(families: List<String> = STARTER_FAMILIES): List<LlamaModelEntry> = coroutineScope {
        families.map { family -> async { runCatching { bestEntryForFamily(family) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Finds [family]'s best quick-setup candidate: searches for it directly (looser tag filter —
     * see [STARTER_DENYLISTED_TAGS]), ranks by parameter-count fit, then resolves the top few
     * candidates' real file size in parallel to prefer one landing in [STARTER_SIZE_RANGE].
     * Returns `null` only if the search itself fails or returns nothing.
     */
    private suspend fun bestEntryForFamily(family: String): LlamaModelEntry? = coroutineScope {
        val matches = withContext(Dispatchers.IO) {
            parseEntries(fetchRepos(family, STARTER_RAW_LIMIT)) { tag -> tag !in STARTER_DENYLISTED_TAGS }
        }
        if (matches.isEmpty()) return@coroutineScope null

        val ranked = matches.sortedWith(
            compareBy(
                { entry ->
                    val repoId = entry.sourceRepoId?.lowercase() ?: ""
                    if (STARTER_TRUSTED_PUBLISHERS.any { repoId.startsWith(it) }) 0 else 1
                },
                { entry ->
                    val params = PARAM_COUNT_PATTERN.find(entry.label ?: entry.fileName)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (params != null && params in STARTER_PARAM_RANGE) 0 else 1
                },
            ),
        )

        val sized = ranked.take(STARTER_SIZE_CHECK_CANDIDATES).map { entry ->
            async(Dispatchers.IO) { entry to httpHeadContentLength(entry.downloadUrl) }
        }.awaitAll()

        // Only entries with a *resolved* (non-null) size are trustworthy — null covers both
        // network failures and non-2xx responses (notably gated repos like official
        // `google/gemma-*`, which 401 behind an HF login + license click-through that an
        // unattended quick-setup flow can't satisfy; see httpHeadContentLength's doc). Preferring
        // any resolved size over an unresolved one avoids recommending a repo the user can't
        // actually download.
        val resolved = sized.filter { (_, size) -> size != null }
        val rangeMid = (STARTER_SIZE_RANGE.first + STARTER_SIZE_RANGE.last) / 2
        val best = resolved.firstOrNull { (_, size) -> size!! in STARTER_SIZE_RANGE }
            ?: resolved.minByOrNull { (_, size) -> kotlin.math.abs(size!! - rangeMid) }
            ?: (ranked.first() to null)
        val (entry, size) = best
        if (size != null) entry.copy(approxSizeBytes = size) else entry
    }
}
