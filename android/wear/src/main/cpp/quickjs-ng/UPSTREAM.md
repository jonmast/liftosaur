# Vendored quickjs-ng

**Upstream:** https://github.com/quickjs-ng/quickjs
**Tag:** `v0.16.1`
**Commit:** `954dc53628e36891f93c359aa60895c2ae3dac6b`

Vendored in full, including upstream's own `CMakeLists.txt`, which
`../CMakeLists.txt` pulls in via `add_subdirectory()`. Nothing in this
directory is patched — build options are forced from the parent instead, so
`git diff` against the upstream tag stays empty and updates stay cheap.
Refresh with `./update_quickjs.sh <tag>`.

## Why this engine

This is a **deliberate divergence from iOS**, which vendors Bellard's QuickJS.
On the physical Pixel Watch 2, ng evaluates the production watch bundle **2x
faster** than the Bellard copy this repo already carries, with non-overlapping
distributions across 5 trials each, and it carries 32-bit fixes Bellard lacks —
which matters because the watch is `armeabi-v7a` only (tickets 01, 04).

Worth knowing: **no other Android project embeds quickjs-ng at all** (surveyed
in ticket 06), and Android×armv7 has no upstream CI coverage. Our on-device
measurements are the only validation that exists, so re-measure after any bump
rather than assuming a green upstream release is green here.

## Local deletions

Removed to keep the tree small; none participate in the `qjs` static library:

- `docs/`, `examples/`, `tests/`, `test262/`, `fuzz/`, `.github/`, `.git/`

`gen/` is **kept** — upstream's `qjs_exe`/`qjsc` targets reference
`gen/repl.c` and `gen/standalone.c` at configure time, so removing it breaks
CMake configuration even though we never build those targets.
