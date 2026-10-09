#!/usr/bin/env bash
#
# generate-whisper-bindings.sh
#
# Downloads jextract (cached per-user, not committed to the repo) and
# regenerates the Java FFM bindings for whisper.cpp from the headers under
# native-headers/whisper/.
#
# This is a DEV-TIME tool, not part of the normal Gradle build: the generated
# sources under src/main/java/<package> ARE committed to git. Re-run this
# script only when the pinned whisper.cpp build changes (currently b5454) and
# you need to regenerate bindings to match a new whisper.h/ABI.
#
# Usage:
#   ./scripts/generate-whisper-bindings.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# --- Config -------------------------------------------------------------
# jextract build to use. The FFM (java.lang.foreign) API has been stable
# since JDK 22 (JEP 454), so any jextract build >= 22 works regardless of
# the JDK you actually compile/run askimo with. We use 25 here since a
# prebuilt jextract-for-JDK-25 binary is available.
JEXTRACT_VERSION="25"
# Early-access build number of the jextract release for JEXTRACT_VERSION, and
# the trailing filename suffix Oracle appends after it (e.g. "25/2/...+2-4_...").
# Verify/update both against https://jdk.java.net/jextract/ if the download
# below returns 404 (Oracle bumps these across jextract point releases).
JEXTRACT_BUILD="2"
JEXTRACT_FILENAME_SUFFIX="4"

JEXTRACT_CACHE_ROOT="${HOME}/.cache/askimo/jextract"
JEXTRACT_CACHE_DIR="${JEXTRACT_CACHE_ROOT}/${JEXTRACT_VERSION}-${JEXTRACT_BUILD}"

HEADERS_DIR="${MODULE_DIR}/native-headers/whisper"
OUTPUT_DIR="${MODULE_DIR}/src/main/java"
TARGET_PACKAGE="io.askimo.ui.voice.whispercpp"

# --- 1. Resolve jextract binary: honor an explicit override, else auto-download ---------
# If the caller already points JEXTRACT_BIN at a working jextract (e.g. a manual install on
# an OS/arch we don't auto-download for — currently anything but macOS/Linux x64/aarch64),
# skip platform detection and the download step entirely; that path is used as-is below.
if [[ -n "${JEXTRACT_BIN:-}" ]]; then
  if [[ ! -x "${JEXTRACT_BIN}" ]]; then
    echo "error: JEXTRACT_BIN='${JEXTRACT_BIN}' is not an executable file." >&2
    exit 1
  fi
  echo "Using explicitly provided jextract at ${JEXTRACT_BIN}"
else
  case "$(uname -s)" in
    Darwin) OS="macos" ;;
    Linux)  OS="linux" ;;
    *)
      echo "error: no jextract auto-download available for OS $(uname -s)." >&2
      echo "Download jextract manually from https://jdk.java.net/jextract/ and" >&2
      echo "re-run with JEXTRACT_BIN=/path/to/jextract/bin/jextract set." >&2
      exit 1
      ;;
  esac

  case "$(uname -m)" in
    arm64|aarch64) ARCH="aarch64" ;;
    x86_64)        ARCH="x64" ;;
    *)
      echo "error: no jextract auto-download available for arch $(uname -m)." >&2
      echo "Download jextract manually from https://jdk.java.net/jextract/ and" >&2
      echo "re-run with JEXTRACT_BIN=/path/to/jextract/bin/jextract set." >&2
      exit 1
      ;;
  esac

  JEXTRACT_URL="https://download.java.net/java/early_access/jextract/${JEXTRACT_VERSION}/${JEXTRACT_BUILD}/openjdk-${JEXTRACT_VERSION}-jextract+${JEXTRACT_BUILD}-${JEXTRACT_FILENAME_SUFFIX}_${OS}-${ARCH}_bin.tar.gz"
  JEXTRACT_BIN="${JEXTRACT_CACHE_DIR}/bin/jextract"
fi

# --- 2. Download + cache jextract (skip if already cached or explicitly provided) -------
if [[ ! -x "${JEXTRACT_BIN}" ]]; then
  echo "jextract ${JEXTRACT_VERSION} not found in cache, downloading..."
  echo "  URL: ${JEXTRACT_URL}"
  mkdir -p "${JEXTRACT_CACHE_DIR}"
  TMP_TAR="$(mktemp)"
  if ! curl -fL "${JEXTRACT_URL}" -o "${TMP_TAR}"; then
    echo "" >&2
    echo "error: download failed. Check https://jdk.java.net/jextract/ for the" >&2
    echo "current JEXTRACT_BUILD number for version ${JEXTRACT_VERSION} and update" >&2
    echo "this script, or set JEXTRACT_BIN to a manually installed jextract." >&2
    rm -f "${TMP_TAR}"
    exit 1
  fi
  tar -xzf "${TMP_TAR}" -C "${JEXTRACT_CACHE_DIR}" --strip-components=1
  rm -f "${TMP_TAR}"
  chmod +x "${JEXTRACT_BIN}"

  # macOS quarantines downloaded executables not installed via Homebrew/App
  # Store; strip the quarantine attribute so it runs without a Gatekeeper
  # prompt. No-op (and harmless) on Linux.
  if [[ "${OS:-}" == "macos" ]]; then
    xattr -dr com.apple.quarantine "${JEXTRACT_CACHE_DIR}" 2>/dev/null || true
  fi

  echo "jextract installed at ${JEXTRACT_CACHE_DIR}"
else
  echo "Using cached jextract at ${JEXTRACT_BIN}"
fi

# --- 3. Sanity-check inputs ----------------------------------------------
if [[ ! -f "${HEADERS_DIR}/whisper.h" ]]; then
  echo "error: ${HEADERS_DIR}/whisper.h not found." >&2
  echo "Copy whisper.h and its ggml-*.h dependencies from the xcframework/" >&2
  echo "release archive's Headers/ dir into ${HEADERS_DIR} first." >&2
  exit 1
fi

# --- 4. Run jextract -------------------------------------------------------
# We only bind the small handful of whisper.cpp functions askimo actually
# calls (plus the struct/constant types they transitively require), rather
# than every symbol reachable from whisper.h. Two reasons:
#
#  1. Size/noise: whisper.h pulls in most of ggml's internal API surface
#     (backends, schedulers, tensors, ...) that we never touch.
#
#  2. Correctness: ggml's headers define both a FUNCTION and a STRUCT TAG
#     named `ggml_backend_graph_copy` (legal in C, which has separate
#     function/struct-tag namespaces). jextract emits one top-level
#     `ggml_backend_graph_copy.java` for the struct, but ALSO generates a
#     same-named nested function-holder class *inside* whichever header
#     file declares the function. Since Java has a single namespace, that
#     nested class shadows the top-level struct class for any unqualified
#     `ggml_backend_graph_copy.layout()` reference in that header file,
#     causing `cannot find symbol: method layout()` javac errors. This
#     symbol isn't reachable from the functions we actually call below, so
#     allowlisting avoids generating it entirely.
#
# If you need to call additional whisper.cpp functions, add more
# --include-function (and any --include-struct/--include-constant jextract
# reports as missing when you re-run this script) below.
rm -rf "${OUTPUT_DIR}/$(echo "${TARGET_PACKAGE}" | tr '.' '/')"
mkdir -p "${OUTPUT_DIR}"
echo "Generating FFM bindings into ${OUTPUT_DIR} (package ${TARGET_PACKAGE})..."

"${JEXTRACT_BIN}" \
  --output "${OUTPUT_DIR}" \
  -t "${TARGET_PACKAGE}" \
  -I "${HEADERS_DIR}" \
  -l whisper \
  --include-function whisper_init_from_file_with_params \
  --include-function whisper_full \
  --include-function whisper_full_n_segments \
  --include-function whisper_full_get_segment_text \
  --include-function whisper_free \
  --include-function whisper_context_default_params \
  --include-function whisper_full_default_params \
  --include-function whisper_print_system_info \
  --include-struct whisper_context_params \
  --include-struct whisper_full_params \
  --include-struct whisper_aheads \
  --include-struct whisper_vad_params \
  --include-constant WHISPER_SAMPLING_GREEDY \
  "${HEADERS_DIR}/whisper.h"

# --- 5. Patch the broken SYMBOL_LOOKUP fallback chain ----------------------
# jextract generates (in the package's "<header>_1.java" file):
#   static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.libraryLookup(System.mapLibraryName("whisper"), LIBRARY_ARENA)
#           .or(SymbolLookup.loaderLookup())
#           .or(Linker.nativeLinker().defaultLookup());
#
# SymbolLookup.libraryLookup(simpleName, arena) THROWS IllegalArgumentException
# immediately if the library isn't found via the platform's default simple-name
# search path (confirmed empirically: it does NOT consult java.library.path,
# and does NOT check already-`System.load()`-ed libraries by a different path).
# Since that call is eager and throws *before* `.or(...)` ever runs, this
# poisons the class forever the first time ANY whisper_h function is called
# (ExceptionInInitializerError -> NoClassDefFoundError on all later calls) —
# fatal for our case, where the native lib is extracted to a per-user cache
# directory (never a "default" search path) and loaded via System.load() by
# NativeLibraryLoader.ensureLoaded() *before* any whisper_h call.
#
# Fix: drop the failing libraryLookup(...) call, relying solely on
# loaderLookup() — which DOES find symbols from a library already loaded via
# System.load(), regardless of the path it was loaded from (also confirmed
# empirically). NativeLibraryLoader.ensureLoaded() must run before any
# whisper_h.* call for this to work.
#
# Which generated file declares SYMBOL_LOOKUP depends on how many headers
# jextract pulls in (it splits across "<header>_1.java", "<header>_2.java",
# etc. once a single file would get too large) — so we grep for it rather
# than hardcoding a filename.
PKG_DIR="${OUTPUT_DIR}/$(echo "${TARGET_PACKAGE}" | tr '.' '/')"
SYMBOL_LOOKUP_FILE="$(grep -rl 'static final SymbolLookup SYMBOL_LOOKUP' "${PKG_DIR}" | head -n1)"
if [[ -n "${SYMBOL_LOOKUP_FILE}" && -f "${SYMBOL_LOOKUP_FILE}" ]]; then
  echo "Patching broken SYMBOL_LOOKUP fallback chain in ${SYMBOL_LOOKUP_FILE}..."
  python3 - "${SYMBOL_LOOKUP_FILE}" << 'PYEOF'
import re
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    content = f.read()

pattern = re.compile(
    r'static final SymbolLookup SYMBOL_LOOKUP = '
    r'SymbolLookup\.libraryLookup\(System\.mapLibraryName\("whisper"\), LIBRARY_ARENA\)\s*'
    r'\.or\(SymbolLookup\.loaderLookup\(\)\)',
)
replacement = (
    'static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.loaderLookup()'
)
new_content, count = pattern.subn(replacement, content, count=1)
if count != 1:
    print(f"error: expected exactly 1 match to patch in {path}, found {count}", file=sys.stderr)
    print("jextract's generated code may have changed shape — update the patch regex in generate-whisper-bindings.sh", file=sys.stderr)
    sys.exit(1)

with open(path, "w", encoding="utf-8") as f:
    f.write(new_content)
print("Patched successfully.")
PYEOF
else
  echo "error: expected to patch ${SYMBOL_LOOKUP_FILE} but it does not exist." >&2
  echo "jextract's output file naming may have changed — update SYMBOL_LOOKUP_FILE above." >&2
  exit 1
fi

echo ""
echo "Done. Generated sources are under:"
echo "  ${OUTPUT_DIR}/$(echo "${TARGET_PACKAGE}" | tr '.' '/')"

