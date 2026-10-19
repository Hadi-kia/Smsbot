package com.hadi.smsbot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

class NumberLoader(private val context: Context) {
    data class LoadResult(val candidates: List<Candidate>, val contactsOk: Boolean, val smsOk: Boolean, val info: String)
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun fromDevice(): LoadResult {
        val data = mutableListOf<Candidate>()
        val info = mutableListOf<String>()
        var contactsOk = false
        var smsOk = false
        if (granted(Manifest.permission.READ_CONTACTS)) {
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY),
                    null, null, null
                )?.use { cur ->
                    val n = cur.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val name = cur.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    while (cur.moveToNext()) cur.getString(n)?.let { data += Candidate(it, cur.getString(name), Source.CONTACT) }
                    contactsOk = true
                }
            } catch (e: Exception) { info += "خطای دفترچه: ${e.javaClass.simpleName}" }
        } else info += "READ_CONTACTS مجاز نیست"
        if (granted(Manifest.permission.READ_SMS)) {
            try {
                // Only addresses; SMS bodies are never retrieved.
                context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, null)?.use { cur ->
                    val address = cur.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    while (cur.moveToNext()) cur.getString(address)?.let { data += Candidate(it, null, Source.SMS) }
                    smsOk = true
                }
            } catch (e: Exception) { info += "خواندن SMS محدود شد: ${e.javaClass.simpleName}" }
        } else info += "READ_SMS مجاز نیست (CSV قابل استفاده است)"
        return LoadResult(data, contactsOk, smsOk, info.joinToString("؛ "))
    }

    fun normalize(raw: String): String? {
        val input = raw.trim()
        if (input.count { it.isDigit() } !in 7..15) return null
        val iso = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.simCountryIso?.takeIf { it.length == 2 } ?: Locale.getDefault().country
        val e164 = try { if (iso.length == 2) PhoneNumberUtils.formatNumberToE164(input, iso.uppercase(Locale.ROOT)) else null }
                   catch (_: Exception) { null }
        val n = e164 ?: PhoneNumberUtils.normalizeNumber(input)
        val cleaned = n.filterIndexed { index, ch -> ch.isDigit() || (index == 0 && ch == '+') }
        return cleaned.takeIf { it.count(Char::isDigit) in 7..15 }
    }

    fun fromCsv(uri: Uri): List<Candidate> {
        val output = mutableListOf<Candidate>()
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.lineSequence().take(20_000).forEach { line ->
                val columns = line.trim().removePrefix("\uFEFF").split(',', ';', '\t').map { it.trim().trim('"') }
                val number = columns.lastOrNull().orEmpty()
                if (normalize(number) != null) output += Candidate(number, columns.dropLast(1).firstOrNull(), Source.CSV)
            }
        }
        return output
    }
}
