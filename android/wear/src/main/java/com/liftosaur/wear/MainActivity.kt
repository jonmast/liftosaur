package com.liftosaur.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    const val VARIANTS = "variants"
    const val EXERCISES = "exercises"
    const val DETAIL = "detail"
    const val PROMPT = "prompt"
}

@Composable
fun WearApp() {
    MaterialTheme(colorScheme = LiftosaurColorScheme) {
        val navController: NavHostController = rememberSwipeDismissableNavController()
        var openEntryIndex by remember { mutableStateOf(0) }
        var promptAt by remember { mutableStateOf<GlobalSetIndex?>(null) }

        LaunchedEffect(PrototypeRemote.nonce) {
            if (PrototypeRemote.nonce == 0) return@LaunchedEffect
            PrototypeRemote.consumeEntryIndex()?.let { openEntryIndex = it }
            val target = when (PrototypeRemote.consumeRoute()?.lowercase()) {
                "home" -> Routes.HOME
                "variants" -> Routes.VARIANTS
                "exercises" -> Routes.EXERCISES
                "detail" -> Routes.DETAIL
                "prompt" -> {
                    promptAt = PrototypeStore.entry(openEntryIndex).nextUnfinished()?.at
                    if (promptAt == null) null else Routes.PROMPT
                }
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
                        onStart = { navController.navigate(Routes.EXERCISES) },
                        onOpenVariants = { navController.navigate(Routes.VARIANTS) },
                    )
                }
                composable(Routes.VARIANTS) {
                    VariantsScreen()
                }
                composable(Routes.EXERCISES) {
                    ExerciseListScreen(
                        onOpenExercise = { index ->
                            openEntryIndex = index
                            navController.navigate(Routes.DETAIL)
                        },
                    )
                }
                composable(Routes.DETAIL) {
                    ExerciseDetailScreen(
                        entryIndex = openEntryIndex,
                        onNeedsPrompt = { at ->
                            promptAt = at
                            navController.navigate(Routes.PROMPT)
                        },
                    )
                }
                composable(Routes.PROMPT) {
                    val at = promptAt
                    if (at == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        PromptScreen(
                            entryIndex = openEntryIndex,
                            at = at,
                            onDone = {
                                promptAt = null
                                navController.popBackStack()
                            },
                            onCancel = {
                                promptAt = null
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }
        }
    }
}
