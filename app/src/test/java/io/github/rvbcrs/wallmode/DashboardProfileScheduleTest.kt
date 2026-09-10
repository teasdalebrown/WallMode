package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardProfileScheduleTest {
    @Test
    fun resolvesProfileAcrossMidnight() {
        assertEquals(DashboardProfile.NIGHT, DashboardProfileSchedule.activeAtHour(5, 6, 10, 21))
        assertEquals(DashboardProfile.HOME, DashboardProfileSchedule.activeAtHour(6, 6, 10, 21))
        assertEquals(DashboardProfile.WALL, DashboardProfileSchedule.activeAtHour(10, 6, 10, 21))
        assertEquals(DashboardProfile.NIGHT, DashboardProfileSchedule.activeAtHour(21, 6, 10, 21))
    }

    @Test
    fun doesNotDependOnSlotDeclarationOrder() {
        assertEquals(DashboardProfile.WALL, DashboardProfileSchedule.activeAtHour(9, 18, 8, 23))
        assertEquals(DashboardProfile.HOME, DashboardProfileSchedule.activeAtHour(20, 18, 8, 23))
        assertEquals(DashboardProfile.NIGHT, DashboardProfileSchedule.activeAtHour(2, 18, 8, 23))
    }

    @Test
    fun emptyProfilePathUsesMainDashboard() {
        assertEquals(
            "main",
            DashboardProfileSchedule.path(DashboardProfile.HOME, "main", "", "wall", "night")
        )
    }

    @Test
    fun importedScheduleRequiresUniqueHoursWithinOneDay() {
        assertTrue(DashboardProfileSchedule.hasValidStartHours(6, 10, 21))
        assertFalse(DashboardProfileSchedule.hasValidStartHours(6, 6, 21))
        assertFalse(DashboardProfileSchedule.hasValidStartHours(-1, 10, 21))
        assertFalse(DashboardProfileSchedule.hasValidStartHours(6, 10, 24))
    }

    @Test
    fun reloadKeepsManualProfileWhileSchedulingIsOff() {
        assertEquals(
            DashboardProfile.NIGHT,
            dashboardProfileForLoad(
                scheduleEnabled = false,
                activeProfile = DashboardProfile.NIGHT,
                scheduledProfile = DashboardProfile.HOME
            )
        )
        assertEquals(
            DashboardProfile.HOME,
            dashboardProfileForLoad(
                scheduleEnabled = true,
                activeProfile = DashboardProfile.NIGHT,
                scheduledProfile = DashboardProfile.HOME
            )
        )
    }
}
