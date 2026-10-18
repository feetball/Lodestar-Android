package net.kuecker.lodestar.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnlineCometDataTest {
    // Three lines of the Minor Planet Center's CometEls.txt (2026-10-18).
    private val vaisala = "    CJ42E00A  2028 09 13.9764  1.277537  0.934878  335.5631  172.3201   37.8727  20261018  13.5  4.0  C/1942 EA (Vaisala)                                      MPEC 2026-K60"
    private val southern = "    CJ47X01b  1947 10 23.2966  0.124377  0.999439  196.9518  338.0712  140.8343  20261018   9.0  4.0  C/1947 X1-B (Southern comet)                              38,  189"
    private val hale = "    CJ95O010  1997 03 29.0282  0.926027  0.994895  130.7316  281.8099   89.7334  20261018  -2.0  4.0  C/1995 O1 (Hale-Bopp)                                    MPC194091"

    private val smith = "    CK19K070  2020 06 15.9745  4.469221  0.999373   27.2761  308.0529  103.4587  20261018   6.0  4.0  C/2019 K7 (Smith)                                        MPC194161"

    private fun bytes(vararg lines: String) = lines.joinToString("\n", postfix = "\n").toByteArray()

    @Test
    fun theCometsOfTheFileAreRead() {
        val parsed = OnlineCometData.parse(bytes(vaisala, southern, hale))
        assertEquals(3, parsed.value.size)
    }

    @Test
    fun aFewRejectedLinesAreTolerated() {
        val parsed = OnlineCometData.parse(bytes(vaisala, southern, hale, smith, "a stray line that is not a comet"))
        assertEquals(4, parsed.value.size)
    }

    @Test
    fun aFileWithoutCometsIsRejected() {
        assertFailsWith<OnlineDataException> { OnlineCometData.parse(ByteArray(0)) }
        assertFailsWith<OnlineDataException> { OnlineCometData.parse("<html>Service unavailable</html>".toByteArray()) }
    }

    @Test
    fun aFileMostlyOfRejectedLinesIsRejected() {
        val junk = Array(30) { "this is not a comet line number $it" }
        assertFailsWith<OnlineDataException> { OnlineCometData.parse(bytes(vaisala, *junk)) }
    }
}
