package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun `does not launch twice when WallMode is the default home`() {
        assertFalse(
            BootReceiver.shouldStartOnBoot(
                autoStartOnBoot = true,
                resolvedHomePackage = "io.github.rvbcrs.wallmode.debug",
                ownPackage = "io.github.rvbcrs.wallmode.debug"
            )
        )
    }

    @Test
    fun `launches at boot when enabled and another launcher owns home`() {
        assertTrue(
            BootReceiver.shouldStartOnBoot(
                autoStartOnBoot = true,
                resolvedHomePackage = "com.hihonor.android.launcher",
                ownPackage = "io.github.rvbcrs.wallmode.debug"
            )
        )
    }

    @Test
    fun `does not launch when boot start is disabled`() {
        assertFalse(
            BootReceiver.shouldStartOnBoot(
                autoStartOnBoot = false,
                resolvedHomePackage = "com.hihonor.android.launcher",
                ownPackage = "io.github.rvbcrs.wallmode.debug"
            )
        )
    }
}
