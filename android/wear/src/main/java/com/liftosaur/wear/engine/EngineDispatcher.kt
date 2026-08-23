package com.liftosaur.wear.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single thread that owns the QuickJS runtime and context for the process lifetime.
 *
 * This exists so the engine needs no locking: [LiftosaurEngine]'s native state is confined to
 * this one thread by construction. Every engine call must be dispatched here.
 *
 * The thread is deliberately non-daemon and never shut down — the runtime lives as long as
 * the process, and tearing it down on a background thread while a call is in flight is a
 * crash rather than a cleanup.
 */
object EngineDispatcher {
    private const val THREAD_NAME = "liftosaur-engine"

    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = false }
    }.asCoroutineDispatcher()

    /** True when the calling thread is the engine thread. For assertions in debug paths. */
    fun isEngineThread(): Boolean = Thread.currentThread().name == THREAD_NAME
}
