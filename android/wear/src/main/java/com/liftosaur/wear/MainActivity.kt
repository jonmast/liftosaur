package com.liftosaur.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent { WearApp() }
    }
}

private object Routes {
    const val HOME = "home"
    const val EXERCISES = "exercises"
    const val DETAIL = "detail"
    const val PROMPT = "prompt"
    const val ENGINE = "engine"
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val controller = remember { AppContainer.controller(context) }
    val state by controller.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { controller.start() }

    MaterialTheme(colorScheme = LiftosaurColorScheme) {
        val navController: NavHostController = rememberSwipeDismissableNavController()
        var openEntryIndex by remember { mutableIntStateOf(0) }

        // The prompt is routed from *storage*, not from the tap that caused it. `completeSet`
        // decides whether a set needs one, and it can also be raised by a phone-side change
        // arriving while the screen is open — so the modal's presence is the trigger, and its
        // absence is what dismisses the route. Navigating from the tap handler instead would
        // show the prompt for sets that did not need it and miss the ones that did.
        LaunchedEffect(state.prompt != null) {
            val onPrompt = navController.currentDestination?.route == Routes.PROMPT
            if (state.prompt != null && !onPrompt) {
                navController.navigate(Routes.PROMPT)
            } else if (state.prompt == null && onPrompt) {
                navController.popBackStack()
            }
        }

        // Finishing or discarding — on the watch or on the phone — makes the workout screens
        // meaningless, so they are popped rather than left showing a workout that is over.
        LaunchedEffect(state.isWorkoutActive) {
            if (!state.isWorkoutActive && !state.loading) {
                navController.popBackStack(Routes.HOME, inclusive = false)
            }
        }

        // The phone finished or discarded the workout. This is the header-derived edge, which
        // arrives before (and independently of) the merge that clears `progress` — so the wrist
        // leaves the workout even when that merge fails.
        LaunchedEffect(state.endedNonce) {
            if (state.endedNonce == 0) return@LaunchedEffect
            navController.popBackStack(Routes.HOME, inclusive = false)
        }

        // The mirror image of the phone's `forceUpdateEntryIndex` (`src/ducks/thunks.ts`):
        // `currentEntryIndex` version-merges across devices, so an exercise advanced on the
        // phone lands here as a storage change, and the open detail screen must follow it.
        LaunchedEffect(state.progress?.currentEntryIndex) {
            state.progress?.currentEntryIndex?.let { openEntryIndex = it }
        }

        // Starting a workout on the watch walks the user in, rather than leaving them on Home
        // to tap "Continue" on the thing they just started.
        LaunchedEffect(state.startedNonce) {
            if (state.startedNonce == 0) return@LaunchedEffect
            navController.navigate(Routes.EXERCISES)
        }

        LaunchedEffect(DevRemote.nonce) {
            if (DevRemote.nonce == 0) return@LaunchedEffect
            if (DevRemote.consumeReseed()) controller.reseedFixture()
            DevRemote.consumeEntryIndex()?.let { openEntryIndex = it }
            val target = when (DevRemote.consumeRoute()?.lowercase()) {
                "home" -> Routes.HOME
                "exercises" -> Routes.EXERCISES
                "detail" -> Routes.DETAIL
                "engine" -> Routes.ENGINE
                else -> null
            } ?: return@LaunchedEffect
            navController.navigate(target) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = target != Routes.DETAIL
            }
        }

        AppScaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(LiftosaurColor.background),
        ) {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        state = state,
                        onStart = { controller.startWorkout() },
                        onOpenWorkout = {
                            state.workout?.let { openEntryIndex = it.nextUnfinishedEntryIndex() }
                            navController.navigate(Routes.EXERCISES)
                        },
                        onOpenEngine = { navController.navigate(Routes.ENGINE) },
                    )
                }
                composable(Routes.ENGINE) {
                    EngineScreen()
                }
                composable(Routes.EXERCISES) {
                    ExerciseListScreen(
                        state = state,
                        onOpenExercise = { index ->
                            openEntryIndex = index
                            navController.navigate(Routes.DETAIL)
                        },
                        onFinish = { controller.finishWorkout() },
                        onDiscard = { controller.discardWorkout() },
                    )
                }
                composable(Routes.DETAIL) {
                    ExerciseDetailScreen(
                        state = state,
                        entryIndex = openEntryIndex,
                        onLog = { entryIndex, at -> controller.logSet(entryIndex, at) },
                        onShown = { controller.setCurrentEntryIndex(it) },
                    )
                }
                composable(Routes.PROMPT) {
                    // Swipe-right dismisses a pushed route on Wear, and it does so without
                    // telling us — so leaving this route with the modal still pending means
                    // the user swiped out. That must be a cancel: a modal left pending in
                    // storage is cleared by the *next* completeSet, which would silently
                    // discard this set while reporting success (spec §2.3).
                    DisposableEffect(Unit) {
                        onDispose {
                            if (controller.state.value.prompt != null) controller.cancelPrompt()
                        }
                    }
                    val modal = state.prompt
                    if (modal != null) {
                        PromptScreen(
                            modal = modal,
                            busy = state.busy,
                            error = state.error,
                            onSubmit = { controller.submitPrompt(it) },
                            onCancel = { controller.cancelPrompt() },
                        )
                    } else {
                        LoadingScreen()
                    }
                }
            }
        }
    }
}
