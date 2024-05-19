package org.rivchain.cuplink.util

import android.app.Activity
import android.content.Intent
import org.rivchain.cuplink.CallActivity
import org.rivchain.cuplink.model.Contact
import org.tdf.rlp.RLPCodec

internal object RlpUtils {

    private val URL_REGEX = """(cpl://)localhost/#c/([a-zA-Z0-9]+$)""".toRegex()

    private val BASE_URL = """cpl://localhost/#c/"""

    fun handlePotentialCupLinkContactUrl(activity: Activity, potentialUrl: String) {
        val contact = parseLink(potentialUrl)

        if (contact != null) {
            activity.startActivity(Intent(
                activity,
                CallActivity::class.java
            ).setAction("ANSWER_INCOMING_CALL").putExtra("EXTRA_CONTACT", contact))
        }
    }

    fun String.toBytes(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }

        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun parseLink(url: String): Contact? {
        val match: MatchResult = URL_REGEX.find(url) ?: return null
        val path: String = match.groups[2]?.value ?: return null

        val contact = RLPCodec.decode(path.toBytes(), Contact::class.java)

        if (contact.name.isEmpty() ||
            contact.publicKey.isEmpty() ||
            contact.addresses.isEmpty()){
                return null
        }
        return contact
    }

    @JvmStatic
    fun generateLink(contact: Contact):String? {
        if (contact.name.isEmpty() ||
            contact.publicKey.isEmpty() ||
            contact.addresses.isEmpty()){
            return null
        }
        return BASE_URL + RLPCodec.encode(contact).toHexString()
    }

}