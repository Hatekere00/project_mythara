package com.mythara.glasses

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stack of screens currently visible on the glasses. [current] is
 * what's rendered; [push] / [pop] navigate. The bottom of the stack
 * is always [GlassesScreen.Root].
 *
 * Subscribed by [GlassesConnectionService] which rerenders the
 * glasses display on every change via [GlassesDatFacade.render].
 */
@Singleton
class GlassesScreenStore @Inject constructor() {

    private val stack = ArrayDeque<GlassesScreen>().apply { addLast(GlassesScreen.Root) }

    private val _current = MutableStateFlow<GlassesScreen>(GlassesScreen.Root)
    val current: StateFlow<GlassesScreen> = _current.asStateFlow()

    @Synchronized
    fun push(screen: GlassesScreen) {
        stack.addLast(screen)
        _current.value = screen
    }

    /** Replace the top screen — used for live updates (e.g. PTT
     *  partial transcript changes word-by-word, but it's still one
     *  conceptual screen). */
    @Synchronized
    fun replaceTop(screen: GlassesScreen) {
        if (stack.isNotEmpty()) stack.removeLast()
        stack.addLast(screen)
        _current.value = screen
    }

    @Synchronized
    fun pop() {
        // Never pop the Root — that's the default home screen.
        if (stack.size > 1) stack.removeLast()
        _current.value = stack.last()
    }

    /** Pop everything back to Root. */
    @Synchronized
    fun popToRoot() {
        while (stack.size > 1) stack.removeLast()
        _current.value = stack.last()
    }
}
