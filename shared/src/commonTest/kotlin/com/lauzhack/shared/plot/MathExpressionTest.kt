package com.lauzhack.shared.plot

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MathExpressionTest {

    @Test
    fun parsesAndEvaluatesArithmetic() {
        assertEquals(7.0, MathExpression.parse("2*x+1").evaluate(3.0))
        assertEquals(6.0, MathExpression.parse("1+2*3-1").evaluate(0.0))
    }

    @Test
    fun parsesTheSemicircleFunction() {
        // f(x) = 2x·√(1−x²): max area 1 at x = 1/√2, 0 at the endpoints — the demo problem
        val f = MathExpression.parse("2*x*sqrt(1-x^2)")
        assertEquals(1.0, f.evaluate(1.0 / sqrt(2.0)), 1e-9)
        assertEquals(0.0, f.evaluate(0.0), 1e-12)
        assertEquals(0.0, f.evaluate(1.0), 1e-12)
        assertEquals(0.0, f.evaluate(-1.0), 1e-12)
    }

    @Test
    fun honorsPrecedenceAndRightAssociativity() {
        assertEquals(-8.0, MathExpression.parse("-2^3").evaluate(0.0)) // -(2^3)
        assertEquals(512.0, MathExpression.parse("2^3^2").evaluate(0.0)) // 2^(3^2)
        assertEquals(16.0, MathExpression.parse("(1+3)^2").evaluate(0.0))
    }

    @Test
    fun supportsFunctionsAndConstants() {
        assertEquals(0.0, MathExpression.parse("sin(x)").evaluate(0.0), 1e-12)
        assertEquals(sqrt(2.0), MathExpression.parse("sqrt(2)").evaluate(9.0), 1e-12)
        assertEquals(PI, MathExpression.parse("pi").evaluate(0.0), 1e-12)
        assertEquals(1.0, MathExpression.parse("exp(0)").evaluate(0.0), 1e-12)
    }

    @Test
    fun evaluatesWithAnyVariableName() {
        assertEquals(0.5, MathExpression.parse("t/2", variable = "t").evaluate(1.0))
    }

    @Test
    fun rejectsUnsafeOrSloppyInput() {
        // implicit multiplication is NOT the DSL: the extractor must emit 2*x
        assertFailsWith<MathExpression.SyntaxError> { MathExpression.parse("2x") }
        assertFailsWith<MathExpression.SyntaxError> { MathExpression.parse("x(x+1)") }
        // unknown names are parse errors — this is what makes the DSL safe
        assertFailsWith<MathExpression.SyntaxError> { MathExpression.parse("y+1") }
        assertFailsWith<MathExpression.SyntaxError> { MathExpression.parse("foo(x)") }
        assertFailsWith<MathExpression.SyntaxError> { MathExpression.parse("1 +") }
    }
}
