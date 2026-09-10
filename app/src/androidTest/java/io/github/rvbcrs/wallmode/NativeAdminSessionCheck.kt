package io.github.rvbcrs.wallmode

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID

/** Uses isolated fixture stores; never reads or changes the installed app's credentials. */
internal fun checkNativeAdminSession(context: Context) {
    val names = List(2) { "wallmode_admin_test_${UUID.randomUUID()}" }
    fun isolated(index: Int) = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            super.getSharedPreferences(names[index], Context.MODE_PRIVATE)
    }
    try {
        val firstContext = isolated(0)
        val first = KioskPreferences(firstContext)
        val second = KioskPreferences(isolated(1))
        first.setAdminPassword("fixture-first")
        check(!first.isAdminSessionStillValid()) { "New password inherited an unlock" }
        check(first.verifyAdminPasswordForUnlock("wrong") == null) { "Wrong password verified" }
        val credential = checkNotNull(first.verifyAdminPasswordForUnlock("fixture-first"))
        check(first.markAdminUnlockedNow(credential) && first.isAdminSessionStillValid())
        check(KioskPreferences(firstContext).isAdminSessionStillValid()) { "Activity recreation lost valid unlock" }

        // Even identical credentials in another preference store must not share a local session.
        context.getSharedPreferences(names[1], Context.MODE_PRIVATE).edit()
            .putString("admin_password_hash", credential).apply()
        check(!second.isAdminSessionStillValid()) { "Local unlock leaked to a different preference store" }
        check(second.markAdminUnlockedNow(credential) && second.isAdminSessionStillValid())
        check(first.isAdminSessionStillValid()) { "Another preference store displaced the original session" }

        first.setAdminPassword("fixture-replacement")
        check(!first.isAdminSessionStillValid()) { "Password change kept old unlock" }
        check(!first.markAdminUnlockedNow(credential)) { "Stale password verification created a new unlock" }
        check(second.isAdminSessionStillValid()) { "Password change invalidated an unrelated store" }
        first.clearAdminPassword()
        check(!first.hasAdminPassword() && first.isAdminSessionStillValid())
    } finally {
        names.forEach(context::deleteSharedPreferences)
    }
}
