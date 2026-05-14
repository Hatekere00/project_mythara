package com.mythara.analytics

/**
 * Tells a real, named person apart from a promotional / automated
 * sender. Personality analysis, the "recommended people" ranking, and
 * the relationship graph all use this to ignore short-code senders
 * ("86041"), bare phone numbers, and brand/no-reply accounts — those
 * are noise, not relationships.
 *
 * Deliberately conservative: it only rejects a contact when there's a
 * clear promotional signal. A normal human name with letters always
 * passes.
 */
object ContactClassifier {

    /** Substrings that strongly mark an automated / promotional sender. */
    private val PROMO_MARKERS = listOf(
        "noreply", "no-reply", "donotreply", "do-not-reply",
        "notification", "notifications", "alert", "alerts", "info@",
        "offer", "offers", "sale", "deal", "deals", "promo", "discount",
        "verify", "otp", "one-time", "update", "updates", "newsletter",
        "support", "service", "team ", "-team", "billing", "invoice",
        "rewards", "cashback", "delivery", "order", "shipment", "txn",
        "bank", "loan", "credit card", "recharge", "winner", "congratulations",
    )

    /** A bare phone number or a numeric short-code (e.g. "86041", "+91 97892 17359"). */
    private fun isNumericSender(name: String): Boolean {
        val stripped = name.replace(Regex("[\\s\\-()+]"), "")
        return stripped.isNotEmpty() && stripped.all { it.isDigit() }
    }

    /**
     * True when [name] (optionally with [phone]) reads as a real,
     * named person worth including in personality analysis. False for
     * numeric short-codes, bare numbers, and promotional/automated
     * sender names.
     */
    fun isPersonal(name: String?, phone: String? = null): Boolean {
        val n = name?.trim().orEmpty()
        if (n.length < 2) return false
        if (isNumericSender(n)) return false
        // Name that's literally just the phone number → not a saved contact.
        if (phone != null && phone.isNotBlank() &&
            n.replace(Regex("[\\s\\-()+]"), "") == phone.replace(Regex("[\\s\\-()+]"), "")
        ) {
            return false
        }
        val lower = n.lowercase()
        if (PROMO_MARKERS.any { lower.contains(it) }) return false
        // Must contain at least one letter to be a person's name.
        if (n.none { it.isLetter() }) return false
        // ALL-CAPS multi-word names with no lowercase are usually brands
        // ("AMAZON PAY", "HDFC BANK"); a normal name has mixed case or is short.
        val words = n.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 2 && n == n.uppercase() && n.length > 10) return false
        return true
    }
}
