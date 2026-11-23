package io.github.letdown2491.noscat

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Pairing payload exchange, per docs/nip-noscat.md "Pairing QR codes" in the
 * noscat repo: the QR encodes `noscat:<npub>` and nothing else. Relays,
 * tailnet, and endpoints come from the scanner's own config plus the peer's
 * published advert, never from the QR.
 */
object Pairing {
    const val SCHEME = "noscat:"

    // bech32 data charset (NIP-19); note '1' and 'b' are not in it.
    private const val BECH32_DATA = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /** The payload to put in a QR code for this device's npub. */
    fun encode(npub: String): String = SCHEME + npub.lowercase()

    /**
     * Extract the peer npub from anything a scanner or user hands over: a
     * full `noscat:` URI, a bare npub, or one with unknown `?params` (which
     * are reserved and ignored). Returns null for non-npub junk.
     */
    fun parse(raw: String): String? {
        val s = raw.trim()
        val body = if (s.startsWith(SCHEME, ignoreCase = true)) {
            s.substring(SCHEME.length)
        } else {
            s // bare npub is accepted
        }
        val npub = body.substringBefore('?')
        if (!npub.startsWith("npub1", ignoreCase = true)) return null
        // Real npubs are 63 chars (npub1 + 58), be generous for future sizes.
        if (npub.length !in 60..90) return null
        if (npub.drop(5).any { it.lowercaseChar() !in BECH32_DATA }) return null
        return npub.lowercase()
    }

    /** Render any payload (the URI, or the npub as copyable text) as a QR bitmap. */
    fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
