package app.styli.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A formula in x, such as "sin(x) / x" or "2x^2 - 3", read and evaluated by hand (nothing is ever run as code).
 * It knows + - * / ^ and brackets, a missing times sign ("2x", "2(x+1)", "x(x-1)"), the constants pi and e, and
 * sin cos tan asin acos atan sinh cosh tanh sqrt abs ln log (base 10) log2 exp floor ceil round sign. Case does not matter.
 */
class Formula private constructor(private val node: (Double) -> Double) {
    /** The value at [x]; NaN or infinite where the formula has none (a division by zero, the square root of a negative number). */
    fun at(x: Double): Double = node(x)

    companion object {
        private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
            "sin" to ::sin, "cos" to ::cos, "tan" to ::tan, "asin" to ::asin, "acos" to ::acos, "atan" to ::atan,
            "sinh" to ::sinh, "cosh" to ::cosh, "tanh" to ::tanh, "sqrt" to ::sqrt, "abs" to ::abs, "ln" to ::ln,
            "log" to ::log10, "log2" to { v -> ln(v) / ln(2.0) }, "exp" to ::exp, "floor" to ::floor, "ceil" to ::ceil,
            "round" to { v -> Math.rint(v) }, "sign" to ::sign,
        )

        /** The formula in [text], or null if it is empty or not a formula (a bracket left open, an unknown word, a missing number). */
        fun parse(text: String): Formula? {
            val p = Parser(text.lowercase().filter { !it.isWhitespace() })
            return try {
                val node = p.expression()
                if (p.atEnd()) Formula(node) else null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private class Parser(val s: String) {
            var i = 0
            fun atEnd() = i >= s.length
            private fun peek() = if (i < s.length) s[i] else '\u0000'
            private fun fail(): Nothing = throw IllegalArgumentException("bad formula at $i")

            fun expression(): (Double) -> Double {
                var left = term()
                while (peek() == '+' || peek() == '-') {
                    val plus = s[i++] == '+'
                    val l = left; val r = term()
                    left = if (plus) { x -> l(x) + r(x) } else { x -> l(x) - r(x) }
                }
                return left
            }

            private fun startsFactor() = peek().isDigit() || peek() == '.' || peek().isLetter() || peek() == '('

            private fun term(): (Double) -> Double {
                var left = unary()
                while (true) {
                    val c = peek()
                    val l = left
                    if (c == '*') { i++; val r = unary(); left = { x -> l(x) * r(x) } }
                    else if (c == '/') { i++; val r = unary(); left = { x -> l(x) / r(x) } }
                    else if (startsFactor()) { val r = power(); left = { x -> l(x) * r(x) } } // "2x", "2(x+1)", "x(x-1)"
                    else return left
                }
            }

            private fun unary(): (Double) -> Double {
                if (peek() == '-') { i++; val u = unary(); return { x -> -u(x) } }
                if (peek() == '+') { i++; return unary() }
                return power()
            }

            // ^ binds tighter than a minus in front, and to the right: -2^2 is -4 and 2^3^2 is 2^9
            private fun power(): (Double) -> Double {
                val base = atom()
                if (peek() == '^') { i++; val e = unary(); return { x -> base(x).pow(e(x)) } }
                return base
            }

            private fun atom(): (Double) -> Double {
                val c = peek()
                if (c == '(') {
                    i++
                    val e = expression()
                    if (peek() != ')') fail()
                    i++
                    return e
                }
                if (c.isDigit() || c == '.') return number()
                if (c.isLetter()) {
                    val start = i
                    while (peek().isLetter()) i++
                    var word = s.substring(start, i)
                    if (peek().isDigit()) { // log2
                        var j = i; while (j < s.length && s[j].isDigit()) j++
                        if (FUNCTIONS.containsKey(word + s.substring(i, j))) { word += s.substring(i, j); i = j }
                    }
                    if (word == "x") return { x -> x }
                    if (word == "pi") return { _ -> PI }
                    if (word == "e") return { _ -> Math.E }
                    val f = FUNCTIONS[word]
                    if (f == null) { // "xpi", "2xe": letters run together are separate factors
                        i = start
                        return single()
                    }
                    if (peek() != '(') fail()
                    i++
                    val arg = expression()
                    if (peek() != ')') fail()
                    i++
                    return { x -> f(arg(x)) }
                }
                fail()
            }

            /** One letter that is x, or pi / e at the front of a run of letters. */
            private fun single(): (Double) -> Double {
                if (s.startsWith("pi", i)) { i += 2; return { _ -> PI } }
                val c = s[i]
                if (c == 'x') { i++; return { x -> x } }
                if (c == 'e') { i++; return { _ -> Math.E } }
                fail()
            }

            private fun number(): (Double) -> Double {
                val start = i
                while (peek().isDigit() || peek() == '.') i++
                // an exponent such as 1e-3, but not the constant e ("2e" is 2 times e)
                if (peek() == 'e' && (s.getOrNull(i + 1)?.isDigit() == true || ((s.getOrNull(i + 1) == '-' || s.getOrNull(i + 1) == '+') && s.getOrNull(i + 2)?.isDigit() == true))) {
                    i += 2
                    while (peek().isDigit()) i++
                }
                val v = s.substring(start, i).toDoubleOrNull() ?: fail()
                return { _ -> v }
            }
        }
    }
}
