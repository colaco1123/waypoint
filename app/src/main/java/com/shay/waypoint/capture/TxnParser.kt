package com.shay.waypoint.capture

/**
 * Pure-Kotlin parsers for Indian bank / UPI debit messages. No Android imports so they can be unit tested.
 * Only the extracted fields leave this file; the raw text is never stored.
 */
data class ParsedTxn(
    val amount: Double,
    val merchant: String?,
    val account4: String?,
    val upiRef: String?,
)

object TxnParser {
    val defaultSenders = listOf(
        "HDFCBK", "SBIUPI", "SBIINB", "CBSSBI", "ICICIB", "ICICIT", "AXISBK", "KOTAKB",
        "PNBSMS", "BOIIND", "IDFCFB", "YESBNK", "INDUSB", "CANBNK", "UNIONB", "FEDBNK", "PAYTMB", "AIRBNK",
    )

    /** "VM-HDFCBK-S", "AX-HDFCBK", "HDFCBK" all match "HDFCBK". Phone numbers never match. */
    fun isBankSender(sender: String, list: List<String>): Boolean {
        val s = sender.uppercase().replace(" ", "")
        if (s.isEmpty() || s.trimStart('+').all { it.isDigit() }) return false
        val parts = s.split('-')
        return list.map { it.trim().uppercase().substringAfterLast('-').ifEmpty { null } }.filterNotNull()
            .any { id -> parts.any { it == id } || s.endsWith(id) || s.contains("-$id") }
    }

    private val debitWords = Regex("""\b(debited|spent|paid|sent|withdrawn|withdrawal)\b""", RegexOption.IGNORE_CASE)
    private val creditWords = Regex("""\b(credited|received|deposited|refund(ed)?|cashback)\b""", RegexOption.IGNORE_CASE)
    // OTP messages carry a code next to the word; plain "never share your OTP" warnings inside debit alerts are fine.
    private val otpWords = Regex(
        """(\b\d{4,8}\b\s+is\s+(?:your|the)\b.{0,30}\b(otp|one[- ]time password|verification code|passcode)\b)|""" +
            """(\b(otp|one[- ]time password|verification code|passcode)\s*(?:is|:)\s*\d{4,8}\b)|(\botp\s+for\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val failedWords = Regex("""\b(failed|declined|unsuccessful|reversed|could not be|not been processed|insufficient|will be refunded)\b""", RegexOption.IGNORE_CASE)
    private val promoWords = Regex("""\b(offer|apply now|pre-?approved|loan (?:of|up ?to)|win |reward points|click here to|limited period|get instant)\b""", RegexOption.IGNORE_CASE)
    private val accountHint = Regex("""\b(a/?c|acct|account|card|upi|vpa|imps|neft|atm)\b""", RegexOption.IGNORE_CASE)

    private val amountRx = Regex("""(?:\b(?:rs\.?|inr)|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val debitedByRx = Regex("""debited\s+(?:by|for|with)\s+(?:rs\.?|inr|₹)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val accountRx = Regex("""(?:a/?c|acct|account|card)(?:\s*no\.?)?(?:\s*ending(?:\s*with)?)?\s*[:.]?\s*(?:[x*]+\s*)?(\d{3,6})\b""", RegexOption.IGNORE_CASE)
    private val refRx = Regex("""(?:upi\s*ref(?:erence)?(?:\s*no\.?)?|ref\s*no\.?|refno|ref|rrn|txn\s*id|upi)\s*[:.#-]?\s*(\d{6,18})""", RegexOption.IGNORE_CASE)
    private val vpaRx = Regex("""\b([a-z0-9][a-z0-9._-]{1,60}@[a-z]{2,20})\b""", RegexOption.IGNORE_CASE)

    private val stop = """(?=\s+on\b|\s+ref|\s+upi|\s+refno|\s+avl|\s+avail|\s+via|\s+using|\s+from|\s*\(|\.\s|\.$|,|;|\n|\s+-|$)"""
    private val merchantRxs = listOf(
        Regex("""\btrf\s+to\s+(.{2,40}?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|\n|\s)to\s*:?\s+(?:vpa\s+)?(.{2,40}?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:spent|paid|purchase|txn)\b.{0,60}?\b(?:at|on)\s+([A-Za-z][A-Za-z0-9&'._ -]{1,40}?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\bwithdrawn\s+at\s+(.{2,40}?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""\binfo:?\s*([A-Za-z0-9*/._ -]{2,40}?)$stop""", RegexOption.IGNORE_CASE),
        Regex("""[;.]\s*([A-Za-z][A-Za-z0-9&'._@ -]{1,40}?)\s+credited\b""", RegexOption.IGNORE_CASE),
    )

    fun parseAmount(s: String): Double? = s.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 && it < 10_000_000 }

    /** Returns null for anything that is not a clear, successful debit. */
    fun parseSms(body: String): ParsedTxn? {
        val text = body.replace('\r', '\n')
        if (otpWords.containsMatchIn(text)) return null
        if (failedWords.containsMatchIn(text)) return null
        if (!debitWords.containsMatchIn(text)) return null
        // A credit word before any debit word = incoming money ("Rs 500 credited ... sent by X").
        // A debit SMS may mention the payee being credited afterwards ("debited ...; ZOMATO credited"), which is fine.
        val credit = creditWords.find(text)
        val debit = debitWords.find(text)
        if (credit != null && (debit == null || debit.range.first > credit.range.first)) return null
        if (promoWords.containsMatchIn(text) && !accountRx.containsMatchIn(text)) return null
        if (!accountHint.containsMatchIn(text)) return null

        val amount = pickAmount(text) ?: return null
        val acct = accountRx.find(text)?.groupValues?.get(1)?.takeLast(4)
        val ref = refRx.find(text)?.groupValues?.get(1)
        return ParsedTxn(amount, extractMerchant(text), acct, ref)
    }

    private fun pickAmount(text: String): Double? {
        // The debit amount comes first; skip balance / limit figures.
        for (m in amountRx.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 18), m.range.first).lowercase()
            if ("bal" in before || "limit" in before || "balance" in before) continue
            parseAmount(m.groupValues[1])?.let { return it }
        }
        return debitedByRx.find(text)?.groupValues?.get(1)?.let(::parseAmount)
    }

    fun extractMerchant(text: String): String? {
        for (rx in merchantRxs) {
            val raw = rx.find(text)?.groupValues?.get(1) ?: continue
            clean(raw)?.let { return it }
        }
        return vpaRx.find(text)?.groupValues?.get(1)?.let(::clean)
    }

    /** Tidy a merchant string; drops long digit runs so account / phone numbers never get stored. */
    fun clean(raw: String): String? {
        var s = raw.replace(Regex("""\d{5,}"""), "").replace(Regex("""\s+"""), " ").trim().trim('.', ',', ':', '-', '/', '(', ')', ' ')
        s = s.removePrefix("VPA ").removePrefix("vpa ").trim()
        if (s.length < 2) return null
        if (Regex("""^(a/?c|account|your|the|you|ur)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        if (s.all { !it.isLetter() }) return null
        return s.take(40)
    }

    // ---------------- payment-app notifications ----------------

    val paymentApps = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "com.samsung.android.spay" to "Samsung Wallet",
        "com.samsung.android.samsungpay.gear" to "Samsung Wallet",
    )

    private val notifRxs = listOf(
        Regex("""(?:you\s+)?(?:paid|sent)\s*(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:successfully\s+)?to\s+(.{2,40}?)(?=\s+on\b|\s+using|\s+via|\.|,|\n|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:paid|sent)\s*(?:successfully\s+)?to\s+(.{2,40}?)(?=\s+on\b|\s+using|\s+via|\.|,|\n|$)""", RegexOption.IGNORE_CASE),
        Regex("""payment\s+of\s*(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*to\s+(.{2,40}?)\s+(?:is\s+)?(?:successful|completed|done)""", RegexOption.IGNORE_CASE),
    )
    private val notifReject = Regex("""\b(received|requested|request|failed|declined|pending|cashback|reward|refund|collect|reminder|due|offer|bill is)\b""", RegexOption.IGNORE_CASE)

    fun parseNotification(title: String, text: String): ParsedTxn? {
        val all = "$title\n$text"
        if (notifReject.containsMatchIn(all)) return null
        for (rx in notifRxs) {
            val m = rx.find(all) ?: continue
            val amt = parseAmount(m.groupValues[1]) ?: continue
            return ParsedTxn(amt, clean(m.groupValues[2]), null, refRx.find(all)?.groupValues?.get(1))
        }
        // reversed order, e.g. title "Paid to Zomato", text "₹250"
        Regex("""(?:paid|sent)\s+to\s+(.{2,40}?)\s*[\n.,:·-]+\s*(?:\b(?:rs\.?|inr)|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
            .find(all)?.let { m -> parseAmount(m.groupValues[2])?.let { return ParsedTxn(it, clean(m.groupValues[1]), null, null) } }
        return null
    }
}
