package com.musicplayer.app.data

import android.content.Context

/**
 * Remembers that the user dismissed the playback notification with the X
 * button — so the notification must NOT come back by itself.
 *
 * v1.0.16 (zombie notification, reported 2026-11-28 18:38): after the
 * 18:34 alarm crash the service restarts (Media3 returns START_STICKY),
 * and the notification kept reappearing on its own — even after X — with
 * dead buttons (no live player behind it). Root causes, all verified
 * against the Media3 1.5.1 source:
 *  - MediaSessionService.onStartCommand returns START_STICKY, so Android
 *    restarts the service after a crash; the pre-crash notification sits
 *    in the shade (notifications survive process death) and the manager's
 *    onConnected(false) does NOT cancel it when the restarted service has
 *    an empty timeline.
 *  - closeFromNotification() only cleared the timeline + stopSelf(): on a
 *    dead service the X PendingIntent starts a fresh service whose
 *    timeline is already empty, so clearMediaItems() fires no event, the
 *    manager never re-evaluates, and the stale notification is never
 *    cancelled — X looks dead.
 *  - nudgeNotification() re-posts the notification directly
 *    (manager.notify), bypassing the manager's shouldShowNotification
 *    gate entirely.
 *  - The app had no onTaskRemoved override, so a service left alive
 *    after the app was swiped away could keep/re-post the notification.
 *
 * The flag is set ONLY by the X action (closeFromNotification) and
 * cleared ONLY when playback genuinely starts again (PlayerConnection
 * play entry points + the service's onIsPlayingChanged backstop + the
 * alarm). A paused queue restore does NOT clear it: after X the
 * notification stays gone until the user actually presses play.
 */
object NotificationDismissStore {

    private const val PREFS = "zeno_notification_dismiss"
    private const val KEY_DISMISSED = "dismissed"

    fun isDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISMISSED, false)

    fun setDismissed(context: Context, dismissed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISMISSED, dismissed).apply()
    }
}
