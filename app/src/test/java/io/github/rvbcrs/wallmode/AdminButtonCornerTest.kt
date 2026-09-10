package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Test

class AdminButtonCornerTest {
    @Test
    fun invalidStoredValueFallsBackToTopRight() {
        assertEquals(AdminButtonCorner.TOP_RIGHT, AdminButtonCorner.fromStoredValue("CENTER"))
    }
}
