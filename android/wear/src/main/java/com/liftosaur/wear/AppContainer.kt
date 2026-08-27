package com.liftosaur.wear

import android.content.Context
import com.liftosaur.wear.engine.WatchStorageRepository
import com.liftosaur.wear.sync.WatchStorageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped owner of the repository and the controller.
 *
 * Not activity-scoped, and deliberately so: the inbound phone `/storage` DataItem (ticket 05)
 * is delivered to a `WearableListenerService` that runs with the activity dead, and it must
 * write into the *same* repository the UI observes. Hanging these off the activity would mean
 * two storages that disagree.
 */
object AppContainer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var repository: WatchStorageRepository? = null

    @Volatile
    private var controller: WorkoutController? = null

    /**
     * The repository, with the watch→phone put wired into its mutation funnel.
     *
     * Wiring here rather than at each call site is what makes "every mutation is announced to
     * the phone" true by construction: the service, the UI and the tests all get the same
     * repository, already connected, and there is no code path that can log a set without the
     * phone hearing about it.
     */
    fun repository(context: Context): WatchStorageRepository = synchronized(this) {
        repository ?: WatchStorageRepository(context.applicationContext).also { repo ->
            val sender = WatchStorageSender.forContext(context, repo.deviceId)
            repo.onMutationCommitted = { storage -> sender.submit(storage) }
            repository = repo
        }
    }

    fun controller(context: Context): WorkoutController = synchronized(this) {
        controller ?: WorkoutController(
            context.applicationContext,
            repository(context),
            scope,
        ).also { controller = it }
    }
}
