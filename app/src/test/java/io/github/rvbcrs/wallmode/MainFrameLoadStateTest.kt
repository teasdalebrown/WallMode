package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameLoadStateTest {
    @Test
    fun staleCallbacksCannotReplaceTheCurrentLoadState() {
        val state = MainFrameLoadState()
        val first = state.begin("https://old.example/")
        val second = state.begin("https://new.example/")

        assertFalse(state.markFailed(first))
        assertFalse(state.markFailed("https://old.example/"))
        assertFalse(state.markVisible("https://old.example/"))
        assertTrue(state.markVisible("https://new.example/"))
        assertFalse(state.markFailed(second))
        assertFalse(state.hasProblem)

        state.begin("https://next.example/")
        assertFalse(state.isCurrentUrl("https://stale.example/"))
        assertTrue(state.markFailed("https://next.example/"))
        assertFalse(state.markVisible())
        assertTrue(state.hasProblem)
    }

    @Test
    fun staleNavigationAndRendererFailureAreHandled() {
        assertFalse(mainFrameCallbackMatches("https://old.example/", "https://new.example/"))
        assertTrue(mainFrameCallbackMatches("https://new.example/#top", "https://new.example/#other"))

        val state = MainFrameLoadState()
        state.begin()
        assertTrue(state.markVisible())
        assertTrue(state.forceFailed())
        assertTrue(state.hasProblem)
        assertFalse(state.forceFailed())
    }
}
