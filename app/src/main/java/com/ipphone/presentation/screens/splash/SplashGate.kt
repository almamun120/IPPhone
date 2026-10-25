package com.ipphone.presentation.screens.splash

/**
 * The branded splash is only meant for a real cold start. If this process already showed it
 * (the activity was merely re-created while the background service kept the process alive),
 * the splash passes through instantly instead of replaying the 2-second animation.
 */
object SplashGate {
    @Volatile
    var shownThisProcess: Boolean = false
}
