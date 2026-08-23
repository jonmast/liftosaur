package com.liftosaur.wear.engine

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * The QuickJS-NG engine hosting the Liftosaur watch bundle.
 *
 * Threading: every native entry point must be called from the single engine thread owned by
 * [EngineDispatcher]. The runtime and context are process-lifetime singletons with no
 * locking, because there is nothing legitimate to lock against — a second thread entering
 * here is a caller bug, not a case to defend against.
 *
 * Marshalling: storage crosses as [ByteArray], never String. ART's jstring path corrupts
 * embedded NUL in both directions; marshalling is 0.3% of call cost either way, so this is a
 * correctness choice rather than a performance one (ticket 07).
 */
object LiftosaurEngine {
    private const val TAG = "LiftosaurEngine"

    /**
     * Bounds JS-side allocation (`malloc_size`), which needs ~3MB in practice. It does NOT
     * bound RSS — a 32MB limit never tripped while anon RSS hit 44.6MB, because the gap is
     * libc holding freed arenas, invisible to QuickJS. Its value is turning a runaway into a
     * clean caught error instead of an OOM kill (ticket 12).
     */
    const val MEMORY_LIMIT_BYTES: Long = 32L * 1024 * 1024

    init {
        System.loadLibrary("liftosaur_engine")
    }

    @Volatile
    private var initialized = false

    val isInitialized: Boolean
        get() = initialized

    /**
     * Evaluates the prelude and the baked bundle. Idempotent.
     *
     * @throws RuntimeException with the JS exception text if either fails to evaluate.
     */
    fun initialize(context: Context, memoryLimitBytes: Long = MEMORY_LIMIT_BYTES) {
        if (initialized) return

        val prelude = context.assets.open("prelude.js").use { it.readBytes() }
        val bundle = readBakedBundle(context)

        Log.i(TAG, "initializing: prelude=${prelude.size}B bundle=${bundle.size}B")
        val ok = nativeInit(prelude, bundle, memoryLimitBytes)
        if (!ok) throw IllegalStateException("engine init returned false without throwing")
        initialized = true
    }

    /**
     * Reads the bundle by mmap'ing it straight out of the APK.
     *
     * This works only because the asset is stored uncompressed (`noCompress 'js'`); a
     * deflated asset would have no usable file descriptor and would force a 442KB inflate
     * into the Java heap on every cold start.
     */
    private fun readBakedBundle(context: Context): ByteArray {
        context.assets.openFd("watch-bundle.js").use { afd ->
            FileInputStream(afd.fileDescriptor).use { stream ->
                val mapped = stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
                return ByteArray(mapped.remaining()).also { mapped.get(it) }
            }
        }
    }

    /**
     * Calls `Liftosaur.<method>(storage, ...extraArgs)`.
     *
     * @param storage the opaque storage JSON. Never parsed on the Kotlin side.
     * @param extraArgsJson a JSON array of primitives spread into argv[1..], or null.
     * @return the raw UTF-8 result bytes — an envelope the caller is responsible for parsing.
     */
    fun call(method: String, storage: ByteArray, extraArgsJson: String? = null): ByteArray {
        check(initialized) { "engine not initialized" }
        return nativeCall(method, storage, extraArgsJson)
    }

    /**
     * JS-side allocated bytes. Constant across calls with a constant payload; a rising value
     * is a real leak. RSS movement is not — that is libc arena behavior and is not
     * reclaimable by GC (ticket 12).
     */
    fun mallocSize(): Long = nativeMallocSize()

    /**
     * Adjusts the JS allocation ceiling on the live runtime. Intended for the self-test's
     * deliberate trip; normal operation uses [MEMORY_LIMIT_BYTES] set at init.
     */
    fun setMemoryLimit(bytes: Long) {
        check(initialized) { "engine not initialized" }
        nativeSetMemoryLimit(bytes)
    }

    @JvmStatic
    private external fun nativeInit(prelude: ByteArray, bundle: ByteArray, memoryLimitBytes: Long): Boolean

    @JvmStatic
    private external fun nativeCall(method: String, storage: ByteArray, extraArgsJson: String?): ByteArray

    @JvmStatic
    private external fun nativeMallocSize(): Long

    @JvmStatic
    private external fun nativeSetMemoryLimit(bytes: Long)
}
