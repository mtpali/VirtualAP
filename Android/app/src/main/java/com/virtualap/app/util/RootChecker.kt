package com.virtualap.app.util

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootStatus {
    Checking,
    Granted,
    Denied
}

object RootChecker {
    /**
     * Returns Granted only if the app holds root AND a live `id` command runs.
     * If root isn't granted yet, runs `id` once to trigger the SU manager prompt.
     */
    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            if (Shell.isAppGrantedRoot() == true) {
                if (ShellUtils.fastCmdResult("id")) RootStatus.Granted else RootStatus.Denied
            } else {
                // Not granted yet - this triggers the SU manager dialog.
                val result = Shell.cmd("id").exec()
                if (result.isSuccess && Shell.isAppGrantedRoot() == true) {
                    RootStatus.Granted
                } else {
                    RootStatus.Denied
                }
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }
}
