package app.styli.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTest {
    private fun v(text: String, x: Double = 0.0): Double = Formula.parse(text)!!.at(x)

    @Test
    fun arithmeticFollowsTheUsualOrder() {
        assertEquals(14.0, v("2+3*4"), 1e-12)
        assertEquals(20.0, v("(2+3)*4"), 1e-12)
        assertEquals(1.0, v("10-4-5"), 1e-12) // left to right
        assertEquals(2.0, v("16/4/2"), 1e-12)
        assertEquals(512.0, v("2^3^2"), 1e-9) // powers go to the right
        assertEquals(-4.0, v("-2^2"), 1e-12) // the minus is applied last
        assertEquals(0.5, v("2^-1"), 1e-12)
        assertEquals(3.0, v("--3"), 1e-12)
        assertEquals(7.5, v(" 1.5 * ( 2 + 3 ) "), 1e-12)
    }

    @Test
    fun xAndConstantsAndFunctions() {
        assertEquals(9.0, v("x^2", 3.0), 1e-12)
        assertEquals(1.0, v("sin(pi/2)"), 1e-12)
        assertEquals(4.0, v("sqrt(16)"), 1e-12)
        assertEquals(1.0, v("ln(e)"), 1e-12)
        assertEquals(3.0, v("log(1000)"), 1e-9)
        assertEquals(3.0, v("log2(8)"), 1e-9)
        assertEquals(3.0, v("ABS(-3)"), 1e-12) // case does not matter
        assertEquals(2.0, v("floor(2.9)"), 1e-12)
        assertEquals(-1.0, v("sign(x)", -5.0), 1e-12)
        assertEquals(2.0, v("cos(0)+1"), 1e-12)
        assertEquals(1e-3 * 5, v("1e-3*x", 5.0), 1e-15)
    }

    @Test
    fun aMissingTimesSignIsUnderstood() {
        assertEquals(6.0, v("2x", 3.0), 1e-12)
        assertEquals(8.0, v("2(x+1)", 3.0), 1e-12)
        assertEquals(12.0, v("x(x-1)", 4.0), 1e-12)
        assertEquals(15.0, v("(x+1)(x-1)", 4.0), 1e-12)
        assertEquals(2 * Math.PI, v("2pi"), 1e-12)
        assertEquals(3.0 * Math.sin(1.0), v("3sin(x)", 1.0), 1e-12)
        assertEquals(2 * Math.E, v("2e"), 1e-12)
        assertEquals(10.0, v("x x", Math.sqrt(10.0)), 1e-9)
        assertEquals(2 * Math.PI, v("xpi*2", 1.0), 1e-9) // x times pi times 2
    }

    @Test
    fun noValueWhereTheFormulaHasNone() {
        assertTrue(v("1/x", 0.0).isInfinite())
        assertTrue(v("sqrt(x)", -1.0).isNaN())
        assertTrue(v("ln(x)", 0.0).isInfinite())
        assertTrue(v("tan(pi/2)").let { Math.abs(it) > 1e10 })
    }

    @Test
    fun notAFormulaIsNull() {
        for (bad in listOf("", "   ", "2+", "(1", "1)", "foo(1)", "sin x", "sin(", "2**3", "x y z!", "3 +* 4", "@", "pi(", "1..2")) assertNull(bad, Formula.parse(bad))
        assertNotNull(Formula.parse("x"))
    }
}
