package com.mythara.voice

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * VoiceInteractionSessionService used by [MytharaVoiceInteractionService].
 *
 * Android invokes one of two entrypoints for an assistant gesture:
 *  - `onLaunchVoiceAssistFromKeyguard` on the VoiceInteractionService
 *    when the device is locked (we handle that there).
 *  - `onNewSession` here when the device is unlocked (home
 *    long-press, corner-swipe assist, Pixel Buds touch-and-hold via
 *    the system route).
 *
 * For the unlocked case, the system binds this session and calls
 * `onShow` on it. We don't draw an overlay sheet — Mythara's UX is
 * a full chat activity — so onShow starts MainActivity with
 * ACTION_ASSIST and then immediately finishes the session. The
 * activity's existing `handleVoiceIntent` path then fires
 * VoiceActionStore → ChatScreen one-shot STT → agent.
 *
 * (Earlier revision finish()ed in `onCreate`. That was wrong: the
 * system binds the session before showing it, and our early finish
 * left the gesture path dead. onShow is the right place.)
 */
class MytharaVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "onNewSession")
        return MytharaSession(this)
    }

    private class MytharaSession(svc: VoiceInteractionSessionService) :
        VoiceInteractionSession(svc) {

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            Log.d(TAG, "onShow flags=$showFlags — launching MainActivity")
            launchAssistActivity()
            // Finish the session right after launching the activity.
            // We don't paint any overlay; the activity owns the UX
            // from here on.
            finish()
        }

        private fun launchAssistActivity() {
            val ctx = context
            val intent = Intent(Intent.ACTION_ASSIST).apply {
                component = ComponentName(ctx, "com.mythara.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { ctx.startActivity(intent) }
                .onFailure { Log.w(TAG, "startActivity failed", it) }
        }
    }

    companion object {
        private const val TAG = "Mythara/VISSession"
    }
}
