package com.hadi.smsbot

enum class Source { CONTACT, SMS, CSV }
data class Candidate(val phone: String, val name: String?, val source: Source)
data class Recipient(val phone: String, val name: String, val contacts: Boolean, val sms: Boolean, val csv: Boolean)

object RecipientMerge {
    fun merge(candidates: List<Candidate>, normalize: (String) -> String?): List<Recipient> {
        val merged = linkedMapOf<String, Recipient>()
        for (item in candidates) {
            val phone = normalize(item.phone) ?: continue
            val current = merged[phone]
            val contact = item.source == Source.CONTACT
            val name = item.name?.trim().orEmpty()
            val preferredName = when {
                contact && name.isNotEmpty() -> name
                current != null && current.name != phone -> current.name
                name.isNotEmpty() -> name
                else -> phone
            }
            merged[phone] = Recipient(
                phone, preferredName,
                (current?.contacts == true) || contact,
                (current?.sms == true) || item.source == Source.SMS,
                (current?.csv == true) || item.source == Source.CSV
            )
        }
        return merged.values.sortedWith(compareByDescending<Recipient> { it.contacts }.thenBy { it.name.lowercase() })
    }
}
