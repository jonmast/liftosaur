package com.liftosaur.www.twa.wear

/**
 * The phone→watch Data Layer contract (spec §2.5): two DataItems, zero messages.
 *
 * **Deliberately duplicated** in the `:wear` module (`com.liftosaur.wear.sync.WearProtocol`)
 * rather than hoisted into a shared Kotlin module — the fork keeps shared-file surface at
 * zero, and a constant mismatch fails loudly at integration time on the first put
 * (spec §2.1). If you change a value here, change it there in the same commit.
 */
object WearProtocol {
    /** Phone → watch: full filtered storage. Replace-in-place, latest wins. */
    const val PATH_STORAGE = "/storage"

    /** Watch → phone: full watch storage (ticket 06). */
    const val PATH_WATCH_STORAGE = "/watch/storage"

    /**
     * Monotonic per-sender counter. **Correctness, not ordering sugar**: re-putting
     * byte-identical content fires no `onDataChanged` on the peer, so without this a
     * reinstalled watch whose phone storage never changed would sit empty forever.
     */
    const val KEY_SEQ = "seq"

    /** Gzipped storage JSON. Always filtered (phone→watch), always gzipped. */
    const val KEY_Z = "z"

    /**
     * Hash of `tempUserId`. The watch wipes local storage before applying when this
     * *differs* from what it last applied — compare for inequality, never ordering.
     * Never derive from `originalId`: that is a creation timestamp reduced with
     * `Math.max` on merge, wrong in both directions (ticket 08).
     */
    const val KEY_ACCOUNT_EPOCH = "accountEpoch"

    /**
     * Plaintext, outside the gzip blob, so either side can react to workout start/end
     * with zero JS — no QuickJS call, no JSON.parse, no merge. Absent ⇒ no active workout.
     */
    const val KEY_ACTIVE_WORKOUT_START_TIME = "activeWorkoutStartTime"

    /** Watch → phone only: the watch's `wear-` vector-clock identity (ticket 06). */
    const val KEY_DEVICE_ID = "deviceId"

    /** The capability the watch app declares; the phone queries it for install/reach. */
    const val CAPABILITY_WEAR_APP = "liftosaur_wear_app"
}
