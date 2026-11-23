package io.github.letdown2491.noscat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingTest {
    // Valid bech32 npub from the NIP-19 examples.
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test
    fun roundTrip() {
        assertEquals(npub, Pairing.parse(Pairing.encode(npub)))
    }

    @Test
    fun bareNpubAccepted() {
        assertEquals(npub, Pairing.parse(npub))
    }

    @Test
    fun unknownParamsIgnored() {
        assertEquals(npub, Pairing.parse("noscat:$npub?relay=wss%3A%2F%2Fexample.com"))
    }

    @Test
    fun junkRejected() {
        assertNull(Pairing.parse("hello world"))
        assertNull(Pairing.parse("noscat:nsec1abcdef"))
        assertNull(Pairing.parse("noscat:npub1b1b1b1")) // 'b'/'1' not in bech32 data charset
        assertNull(Pairing.parse(""))
    }

    @Test
    fun uriIsTinyEnoughForAnyQr() {
        assert(Pairing.encode(npub).length < 128)
    }
}
