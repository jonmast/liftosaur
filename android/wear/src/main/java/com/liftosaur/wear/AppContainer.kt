package com.liftosaur.wear

import android.content.Context
import com.liftosaur.wear.engine.WatchStorageRepository
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

    fun repository(context: Context): WatchStorageRepository = synchronized(this) {
        repository ?: WatchStorageRepository(context.applicationContext).also { repository = it }
    }

    fun controller(context: Context): WorkoutController = synchronized(this) {
        controller ?: WorkoutController(
            context.applicationContext,
            repository(context),
            scope,
        ).also { controller = it }
    }
}
