package com.liftosaur.wear.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Owns the watch's storage: the single source of truth the UI observes and every JS call reads.
 *
 * **Memory is authoritative, disk is write-through.** Storage is loaded from
 * `filesDir/storage.json` exactly once, at startup, and thereafter lives in [storage]. This
 * deliberately does not copy watchOS, which re-reads the file on every getter — at 19-135KB
 * against a polled UI that is pure cost (spec §2.4).
 *
 * **Every mutation goes through one funnel** ([mutate]): call JS on the engine thread →
 * *only* on success persist atomically and emit. A failed mutation leaves both memory and
 * disk untouched, which is why the persisted file can never contain the result of a call that
 * reported failure.
 *
 * **Cache coherence is structural, not remembered.** See [cacheDirty].
 */
class WatchStorageRepository(private val context: Context) {
    companion object {
        private const val TAG = "WatchStorage"
        private const val FILE_NAME = "storage.json"
        private const val PREFS = "wear-storage"
        private const val KEY_DEVICE_ID = "deviceId"
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    private val _storage = MutableStateFlow<ByteArray?>(null)

    /** The current storage, or null before [load] or when the phone has never synced. */
    val storage: StateFlow<ByteArray?> = _storage.asStateFlow()

    private val _externalRevision = MutableStateFlow(0)

    /**
     * Bumped on every write that did **not** come from a UI-driven mutation — the phone's
     * inbound DataItem, a migration, a wipe.
     *
     * The UI can't just observe [storage]: mutations write it too, and those already end in a
     * refresh, so observing storage directly would re-derive the whole screen twice per tap
     * (~45ms of engine calls). This counter is the "something changed underneath you" signal,
     * and it is the only thing that makes a phone-side edit visible while the watch screen is
     * open.
     */
    val externalRevision: StateFlow<Int> = _externalRevision.asStateFlow()

    /**
     * True when the bundle's module-scope cache may not match [_storage].
     *
     * The bundle caches parsed storage at module scope and — the trap — **does not key it by
     * content**: `parseStorageSync` short-circuits before `JSON.parse`, so passing *different*
     * storage silently returns the *first* storage. Reads come back stale with no error.
     *
     * So writes are split by origin. A **JS-originated** write (storage that a bundle call
     * just returned) leaves the cache already correct. An **external** write — startup disk
     * load, an incoming phone DataItem, post-migration storage — invalidates it. This flag is
     * set by [setExternal] and checked-and-cleared inside [withFreshCache] before every call,
     * so there is no call site that can forget to do it (spec §2.4).
     */
    @Volatile
    private var cacheDirty: Boolean = false

    /**
     * This watch's identity in the `_versions` vector clock.
     *
     * **Deliberately unstable across reinstall.** A reinstall wipes local storage, so the
     * clock counters restart low; reusing the old id would present a lying clock and the merge
     * would silently drop genuinely-new changes from other devices. A fresh identity is the
     * honest one. Never derive this from `ANDROID_ID` or any hardware id — that would make it
     * stable and reintroduce exactly that bug.
     *
     * The `wear-` prefix makes collision with the phone and with watchOS's `watch-`
     * structurally impossible (spec §2.4).
     */
    val deviceId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val fresh = "wear-" + UUID.randomUUID().toString().take(8)
            prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
            Log.i(TAG, "minted deviceId $fresh")
            fresh
        }
    }

    /**
     * Loads the last-persisted storage from disk. Call once at startup.
     *
     * This is an external write by definition — the engine in a freshly started process has
     * evaluated the bundle but never seen this storage, and after a *process* restart (where
     * the engine may hold cached state from before) assuming otherwise would serve stale
     * reads. Returns false when nothing has been persisted yet, which is the un-paired
     * first-run state, not an error.
     */
    fun load(): Boolean {
        val f = file
        if (!f.exists()) {
            Log.i(TAG, "no persisted storage yet")
            return false
        }
        return try {
            val bytes = f.readBytes()
            _storage.value = bytes
            cacheDirty = true
            Log.i(TAG, "loaded ${bytes.size}B from disk")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not read persisted storage", e)
            false
        }
    }

    /**
     * Accepts storage from outside the bundle — the phone's DataItem, a migration result, a
     * test fixture — and persists it.
     *
     * Marks the bundle's cache dirty, because this storage did not come from the bundle and
     * therefore does not match what it has cached.
     */
    fun setExternal(bytes: ByteArray) {
        _storage.value = bytes
        cacheDirty = true
        persist(bytes)
        _externalRevision.value += 1
    }

    /**
     * Wipes local storage — memory, disk, and the bundle's cache.
     *
     * Called only on an `accountEpoch` change (spec §2.5): the incoming storage belongs to a
     * different account, and merging it with what is here would fuse two people's data into a
     * single `_versions` clock that no later sync could pull apart. Deleting first is what
     * makes the account switch a replacement rather than a merge.
     */
    fun clear() {
        _storage.value = null
        cacheDirty = true
        if (file.exists() && !file.delete()) {
            Log.e(TAG, "could not delete persisted storage")
        }
        _externalRevision.value += 1
    }

    /** In-memory only, for tests that want to prove what the cache invariant is protecting. */
    internal fun setExternalWithoutInvalidating(bytes: ByteArray) {
        _storage.value = bytes
    }

    /**
     * Runs a read against current storage on the engine thread, with the cache invariant
     * applied. Fails cleanly when there is no storage yet.
     */
    suspend fun <T> read(block: (ByteArray) -> WatchJs.CallResult<T>): WatchJs.CallResult<T> {
        val current = _storage.value
            ?: return WatchJs.CallResult.Failure("no storage yet")
        return withContext(EngineDispatcher.dispatcher) { withFreshCache { block(current) } }
    }

    /**
     * The single mutation funnel: call JS, and **only on success** persist and emit.
     *
     * On failure the previous storage stands in both memory and on disk — the bundle returns
     * an error envelope without new storage, so there is nothing to write even by accident.
     */
    suspend fun mutate(
        block: (storage: ByteArray, deviceId: String) -> WatchJs.MutationResult,
    ): WatchJs.MutationResult {
        val current = _storage.value
            ?: return WatchJs.MutationResult.Failure("no storage yet")
        val result = withContext(EngineDispatcher.dispatcher) { withFreshCache { block(current, deviceId) } }
        if (result is WatchJs.MutationResult.Success) {
            // JS-originated: the bundle's cache already holds exactly this storage, so this
            // must NOT set cacheDirty — doing so would throw away the cache that makes warm
            // calls 15ms instead of 200ms, on every single mutation.
            _storage.value = result.storage
            persist(result.storage)
        }
        return result
    }

    /**
     * Applies the cache invariant around a call. Must wrap every entry into the bundle.
     *
     * Check-and-clear rather than clear-always: `invalidateStorageCache` also drops the
     * evaluated-program cache, and re-evaluating Liftoscript costs ~150ms.
     */
    private inline fun <T> withFreshCache(block: () -> T): T {
        if (cacheDirty) {
            WatchJs.invalidateStorageCache()
            cacheDirty = false
        }
        return block()
    }

    /**
     * Writes to a temp file and renames over the target.
     *
     * The watch is a device that suspends aggressively and can be yanked off the charger
     * mid-write; a partial `storage.json` would be unparseable and would strand the user until
     * the next phone sync. Rename is atomic within a filesystem, so the file is either the old
     * storage or the new one.
     */
    private fun persist(bytes: ByteArray) {
        val target = file
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // renameTo won't overwrite on every filesystem; fall back explicitly.
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IllegalStateException("could not rename $tmp to $target")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not persist storage", e)
            tmp.delete()
        }
    }

    internal fun isCacheDirty(): Boolean = cacheDirty
}
