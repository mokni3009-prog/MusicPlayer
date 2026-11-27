package com.musicplayer.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.musicplayer.app.report.ErrorReporter

/** Receives the PackageInstaller result for an in-app update. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "تم تثبيت التحديث بنجاح", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                ErrorReporter.log(
                    ErrorReporter.TYPE_UPDATE,
                    "Update install failed: status=$status message=$message"
                )
                Toast.makeText(
                    context,
                    "فشل تثبيت التحديث${if (!message.isNullOrBlank()) ": $message" else ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User confirmation needed (should not happen for same-signature updates).
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
    }
}
