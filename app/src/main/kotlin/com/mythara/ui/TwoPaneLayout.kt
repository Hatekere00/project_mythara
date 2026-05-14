package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.ui.about.AboutScreen
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.secret.SecretSettingsScreen
import com.mythara.ui.settings.SettingsScreen
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Two-pane layout for unfolded foldables / tablets / wide windows.
 *
 *   ┌──────────────────┬──────────────────┐
 *   │                  │  Settings /      │
 *   │   ChatScreen     │  People /        │
 *   │   (always)       │  About /         │
 *   │                  │  Secret /        │
 *   │                  │  (welcome)       │
 *   └──────────────────┴──────────────────┘
 *
 * The left pane is the chat surface, always present so the user
 * doesn't lose the conversation when opening secondary destinations.
 * Tapping the People / Settings pills in the chat header drives the
 * right pane's own [androidx.navigation.compose.NavController]
 * rather than the top-level one — so right-side navigation never
 * replaces the chat.
 *
 * Right-pane "back" pops within its own stack until empty, at which
 * point we revert to the welcome placeholder ("open settings, people,
 * or just keep chatting on the left").
 *
 * On compact widths the parent layout uses the original full-screen
 * NavHost; this composable is never invoked.
 */
@Composable
fun TwoPaneLayout(
    onSecretUnlockRequest: () -> Unit,
) {
    val rightNav = rememberNavController()

    Row(modifier = Modifier.fillMaxSize()) {
        // LEFT PANE — chat, always present. Header buttons drive the
        // right-side nav rather than replacing the chat.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MytharaColors.Bg),
        ) {
            ChatScreen(
                onOpenSettings = {
                    rightNav.navigate(RightPaneRoutes.Settings) {
                        popUpTo(RightPaneRoutes.Welcome) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenPeople = {
                    rightNav.navigate(RightPaneRoutes.People) {
                        popUpTo(RightPaneRoutes.Welcome) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenAppDrawer = {
                    rightNav.navigate(RightPaneRoutes.AppDrawer) {
                        popUpTo(RightPaneRoutes.Welcome) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenTimeline = {
                    rightNav.navigate(RightPaneRoutes.Timeline) {
                        popUpTo(RightPaneRoutes.Welcome) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenTasks = {
                    rightNav.navigate(RightPaneRoutes.Tasks) {
                        popUpTo(RightPaneRoutes.Welcome) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }

        // Visual divider between the panes — single dp wide so the
        // two surfaces feel related, not awkwardly siloed.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MytharaColors.SurfaceHigh),
        )

        // RIGHT PANE — secondary destinations. Own NavController so
        // back-stack stays scoped to this side.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MytharaColors.Bg),
        ) {
            NavHost(
                navController = rightNav,
                startDestination = RightPaneRoutes.Welcome,
            ) {
                composable(RightPaneRoutes.Welcome) {
                    RightPaneWelcome()
                }
                composable(RightPaneRoutes.Settings) {
                    SettingsScreen(
                        onBack = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                        onOpenAbout = { rightNav.navigate(RightPaneRoutes.About) },
                        onOpenPeople = { rightNav.navigate(RightPaneRoutes.People) },
                    )
                }
                composable(RightPaneRoutes.People) {
                    com.mythara.ui.analytics.PeopleScreen(
                        onBack = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                    )
                }
                composable(RightPaneRoutes.About) {
                    AboutScreen(
                        onBack = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                        onSecretRequest = onSecretUnlockRequest,
                    )
                }
                composable(RightPaneRoutes.SecretSettings) {
                    SecretSettingsScreen(
                        onBack = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                    )
                }
                composable(RightPaneRoutes.AppDrawer) {
                    com.mythara.ui.launcher.AppDrawerPane(
                        onClose = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                    )
                }
                composable(RightPaneRoutes.Timeline) {
                    com.mythara.ui.lifeline.TimelineGridPane(
                        onClose = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                    )
                }
                composable(RightPaneRoutes.Tasks) {
                    com.mythara.ui.tasks.TasksScreenPane(
                        onClose = {
                            if (!rightNav.popBackStack()) {
                                rightNav.navigate(RightPaneRoutes.Welcome) { launchSingleTop = true }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RightPaneWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${Glyph.DiamondOutline} MYTHARA",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "tap people or settings on the left to fill this pane",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Right-pane-only routes. Kept separate from [Routes] so the two
 * NavControllers never collide on shared route names — even though
 * the route strings happen to match today, future divergence
 * (e.g. right-pane-only Welcome) stays clean.
 */
object RightPaneRoutes {
    const val Welcome = "rp_welcome"
    const val Settings = "rp_settings"
    const val People = "rp_people"
    const val About = "rp_about"
    const val SecretSettings = "rp_secret"
    const val AppDrawer = "rp_app_drawer"
    const val Timeline = "rp_timeline"
    const val Tasks = "rp_tasks"
    /** Tablet-only landing destination — the DashboardHome tile grid. */
    const val Dashboard = "rp_dashboard"
}
