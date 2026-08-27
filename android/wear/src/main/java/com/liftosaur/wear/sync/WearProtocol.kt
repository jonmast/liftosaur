package com.liftosaur.wear.sync

/**
 * The phone↔watch Data Layer contract (spec §2.5).
 *
 * **Deliberately duplicated** from the phone module
 * (`com.liftosaur.www.twa.wear.WearProtocol`) rather than hoisted into a shared Kotlin module:
 * the fork keeps its shared-file surface at zero, and the cost of the duplication is bounded —
 * a constant that disagrees produces "the DataItem never arrives" on the first integration
 * run, loudly and immediately. If you change a value here, change it there in the same commit.
 */
object WearProtocol {
    /** Phone → watch: full filtered storage. Replace-in-place at a fixed path, latest wins. */
    const val PATH_STORAGE = "/storage"

    /** Watch → phone: full watch storage (ticket 06). */
    const val PATH_WATCH_STORAGE = "/watch/storage"

    /** Monotonic per-sender counter; see the phone-side copy for why it is a correctness need. */
    const val KEY_SEQ = "seq"

    /** Gzipped storage JSON. */
    const val KEY_Z = "z"

    /** Hash of `tempUserId`. Compare for inequality, never ordering. */
    const val KEY_ACCOUNT_EPOCH = "accountEpoch"

    /** Plaintext, outside the blob. Absent ⇒ no active workout. */
    const val KEY_ACTIVE_WORKOUT_START_TIME = "activeWorkoutStartTime"

    /** Watch → phone only: the watch's `wear-` vector-clock identity. */
    const val KEY_DEVICE_ID = "deviceId"

    /** Declared in `res/values/wear.xml`; the phone queries it for install/reachability. */
    const val CAPABILITY_WEAR_APP = "liftosaur_wear_app"
}
