package com.shay.waypoint.capture

import org.junit.Assert.*
import org.junit.Test

class TxnParserTest {
    private fun sms(s: String) = TxnParser.parseSms(s)

    @Test fun hdfcUpiMultiline() {
        val p = sms("Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo ZOMATO\nOn 15/11/26\nRef 433012345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808")!!
        assertEquals(250.0, p.amount, 0.001)
        assertEquals("ZOMATO", p.merchant)
        assertEquals("1234", p.account4)
        assertEquals("433012345678", p.upiRef)
    }

    @Test fun hdfcVpa() {
        val p = sms("Rs.500.00 debited from a/c **1234 on 15-11-26 to VPA swiggy@icici(UPI Ref No 433098765432). Not you? Call on 18002586161 to report")!!
        assertEquals(500.0, p.amount, 0.001)
        assertEquals("swiggy@icici", p.merchant)
        assertEquals("1234", p.account4)
        assertEquals("433098765432", p.upiRef)
    }

    @Test fun sbiNoCurrencySymbol() {
        val p = sms("Dear UPI user A/C X5678 debited by 120.0 on date 15Nov26 trf to RAPIDO Refno 433011112222. If not u? call 1800111109. -SBI")!!
        assertEquals(120.0, p.amount, 0.001)
        assertEquals("RAPIDO", p.merchant)
        assertEquals("5678", p.account4)
        assertEquals("433011112222", p.upiRef)
    }

    @Test fun iciciCardSkipsLimit() {
        val p = sms("INR 1,299.00 spent using ICICI Bank Card XX9012 on 15-Nov-26 on AMAZON PAY IN. Avl Limit: INR 45,000.00. If not you, call 1800 2662/SMS BLOCK 9012 to 9215676766.")!!
        assertEquals(1299.0, p.amount, 0.001)
        assertEquals("AMAZON PAY IN", p.merchant)
        assertEquals("9012", p.account4)
    }

    @Test fun axisAtm() {
        val p = sms("INR 2,000.00 withdrawn at ATM AXIS BANK MG ROAD from A/c no. XX3456 on 15-11-2026 14:10. Avl Bal INR 10,500.00")!!
        assertEquals(2000.0, p.amount, 0.001)
        assertEquals("ATM AXIS BANK MG ROAD", p.merchant)
        assertEquals("3456", p.account4)
    }

    @Test fun kotakPaytmQrDropsLongDigits() {
        val p = sms("Sent Rs.80.00 from Kotak Bank AC X7890 to paytmqr2810050501@paytm on 15-11-26.UPI Ref 433022223333. Not you, https://kotak.com/KBANKT/Fraud")!!
        assertEquals(80.0, p.amount, 0.001)
        assertEquals("paytmqr@paytm", p.merchant)
        assertEquals("7890", p.account4)
        assertEquals("433022223333", p.upiRef)
    }

    @Test fun lakhCommas() {
        val p = sms("Rs 1,25,000.00 debited from A/c XX1111 to VPA landlord@okhdfc on 15-11-26. UPI Ref No 433000000099")!!
        assertEquals(125000.0, p.amount, 0.001)
    }

    @Test fun debitWithNeverShareOtpWarningIsKept() {
        val p = sms("Rs.99.00 spent on your SBI Credit Card ending 4321 at SPOTIFY on 15/11/26. Never share your OTP with anyone. Call 18001801290")
        assertNotNull(p)
        assertEquals("4321", p!!.account4)
        assertEquals("SPOTIFY", p.merchant)
    }

    @Test fun iciciUpiPayeeCredited() {
        val p = sms("ICICI Bank Acct XX123 debited for Rs 250.00 on 15-Nov-26; ZOMATO credited. UPI:433012345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.")!!
        assertEquals(250.0, p.amount, 0.001)
        assertEquals("ZOMATO", p.merchant)
        assertEquals("123", p.account4)
        assertEquals("433012345678", p.upiRef)
    }

    @Test fun rejectsCreditMentioningSender() {
        assertNull(sms("Rs 500.00 Credited to A/c XX1234 on 15-11-26. Sent by RAHUL via UPI Ref 433000000002"))
        assertNull(sms("You have received Rs.500.00 in A/c XX1234, sent by rahul@okaxis. UPI Ref 433000000003"))
    }

    @Test fun rejectsCredit() = assertNull(sms("Rs.1,000.00 credited to a/c XX1234 on 15-11-26 by a/c linked to VPA abc@okhdfc (UPI Ref No 433000000001)."))
    @Test fun rejectsOtp() = assertNull(sms("123456 is your OTP for txn of Rs.500.00 at AMAZON on HDFC Bank card XX1234. Do not share. -HDFC"))
    @Test fun rejectsOtp2() = assertNull(sms("OTP is 8812 to complete payment of Rs.500 paid using a/c XX12. Valid 5 mins"))
    @Test fun rejectsBalance() = assertNull(sms("Available balance in A/c XX1234 is Rs.25,000.00 as on 15-11-26"))
    @Test fun rejectsFailed() = assertNull(sms("Your UPI transaction of Rs.300.00 to ZOMATO from A/c XX1234 has failed. Amount debited, if any, will be refunded."))
    @Test fun rejectsPromo() = assertNull(sms("Get instant personal loan up to Rs.5,00,000. Apply now: hdfc.bank/pl"))

    @Test fun senders() {
        val l = TxnParser.defaultSenders
        assertTrue(TxnParser.isBankSender("VM-HDFCBK-S", l))
        assertTrue(TxnParser.isBankSender("AX-SBIUPI", l))
        assertTrue(TxnParser.isBankSender("JD-ICICIB", l))
        assertTrue(TxnParser.isBankSender("XX-KOTAKB", listOf("XX-KOTAKB")))
        assertFalse(TxnParser.isBankSender("AD-SWIGGY", l))
        assertFalse(TxnParser.isBankSender("+919876543210", l))
    }

    @Test fun notifications() {
        val a = TxnParser.parseNotification("Google Pay", "Paid ₹250 to Zomato")!!
        assertEquals(250.0, a.amount, 0.001); assertEquals("Zomato", a.merchant)
        val b = TxnParser.parseNotification("₹1,200 sent to Rahul Sharma", "")!!
        assertEquals(1200.0, b.amount, 0.001); assertEquals("Rahul Sharma", b.merchant)
        val c = TxnParser.parseNotification("Paid to Blinkit", "₹349")!!
        assertEquals(349.0, c.amount, 0.001); assertEquals("Blinkit", c.merchant)
        val d = TxnParser.parseNotification("PhonePe", "Payment of ₹80 to Rapido is successful")!!
        assertEquals("Rapido", d.merchant)
        assertNull(TxnParser.parseNotification("Google Pay", "You received ₹500 from Rahul"))
        assertNull(TxnParser.parseNotification("PhonePe", "Payment failed: ₹250 to Zomato"))
        assertNull(TxnParser.parseNotification("Paytm", "Rahul requested ₹200"))
    }
}
