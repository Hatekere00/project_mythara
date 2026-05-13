package com.mythara.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Wrapper around the system contact-picker intent so callers get a
 * `(displayName, phone)` pair instead of a raw URI they'd otherwise
 * have to query.
 *
 * The system picker handles permission implicitly — the user picks
 * a single contact and the OS grants us one-shot read access via a
 * content URI without us ever needing the READ_CONTACTS permission.
 * That's a deliberate Android privacy pattern; we lean into it so
 * adding favorites doesn't require granting Mythara a permission
 * that's broader than the one-pick use case.
 *
 * If the picked contact has multiple phone numbers we take the first
 * one returned. The user can edit the phone after the picker
 * resolves if they want a different number.
 *
 * Returns null when the user cancelled the picker, when the contact
 * had no phone numbers, or when the URI couldn't be resolved.
 */
data class PickedContact(val displayName: String, val phone: String)

/**
 * Compose-friendly launcher. Caller invokes `launcher.launch(Unit)`
 * and is delivered a [PickedContact] (or null) via the `onResult`
 * lambda. Mirrors the shape of the built-in ActivityResultContracts
 * so callers stay readable.
 */
@Composable
fun rememberContactPicker(onResult: (PickedContact?) -> Unit): ActivityResultLauncher<Unit> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val contract = remember { ContactPickContract() }
    return rememberLauncherForActivityResult(contract) { uri ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        val picked = runCatching { resolveContact(ctx, uri) }.getOrNull()
        onResult(picked)
    }
}

private class ContactPickContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        // CommonDataKinds.Phone.CONTENT_URI specifically asks for the
        // phone-numbers data set, which means the URI we get back
        // resolves directly to one phone row (with name + number)
        // instead of a contact-level URI we'd have to fan out from.
        return Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

private fun resolveContact(ctx: Context, uri: Uri): PickedContact? {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    return ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val phoneIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val name = if (nameIdx >= 0) c.getString(nameIdx) else null
        val phone = if (phoneIdx >= 0) c.getString(phoneIdx) else null
        if (name.isNullOrBlank() || phone.isNullOrBlank()) {
            Log.w("Mythara/ContactPicker", "picked contact had null fields name=$name phone=$phone")
            null
        } else {
            PickedContact(displayName = name.trim(), phone = phone.trim())
        }
    }
}
