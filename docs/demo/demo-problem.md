# Hackathon demo problem — "The best rectangle in a semicircle"

The live-coding/live-drawing centerpiece of the demo: a problem the jury understands
in one sentence, drawn and solved **on the Lauzhack Draw canvas itself**.

---

## Statement

> Among all rectangles inscribed in a semicircle of radius 1, with the base on the
> diameter, which one has the largest area — and how much is that area?

**Why this problem:**

- Understood in 5 seconds; the jury can guess along (most will say "square" — they
  are wrong, and that is the hook).
- One simple function, one clean derivative, and an exact, memorable punchline:
  **the maximum area is exactly 1 = r²**.
- It has two independent solution paths (a derivative computation and a pure
  trigonometry one-liner) — same underlying problem, two "tools". That duality is
  also the product story of this repo: the drawing data is tool-agnostic, the
  editor is just one client.

---

## Live-drawing script (in Lauzhack Draw)

Open a new document from the home list, then:

1. **Figure** — draw the semicircle (ellipse tool) and the diameter (line tool).
   Sketch a rectangle inside, symmetric about the vertical axis; label the
   half-base `x`, the height `h`, and mark `r = 1` in a second color.
2. **Setup** — with the pen: `h = √(1 − x²)` (Pythagoras on the radius to a top
   corner), so `A(x) = 2x·√(1 − x²)` for `x ∈ (0, 1)`.
3. **Function study** — compute and write the derivative, find its zero, compare
   with the endpoints, conclude. Use the arrow tool to point from `x = 1/√2` back
   to the winning rectangle in the figure.
4. **Punchline** — write the answer big: *max area = 1, reached at x = 1/√2 — the
   rectangle is 2:1, literally two squares side by side.*
5. **The "Osservazione"** — in a contrasting color, add the trigonometry path
   (below): no derivative at all.
6. **Product flex mid-demo** — reload the page: everything is still there
   (autosave → backend → SQLite, visible persistence). Keyboard shortcuts
   (V select, D draw, R rectangle, A arrow, T text) keep the flow fast; the
   canvas is a vertical scrolling document, so scroll down to keep the solution
   organized like a real exercise sheet.

---

## Solution (function-study path)

The rectangle is determined by the half-base `x ∈ (0, 1)` (endpoints give the
degenerate rectangles). By Pythagoras, the top corner `(x, y)` satisfies
`x² + y² = 1`, so the height is `h = √(1 − x²)`:

```
A(x) = base · height = 2x · √(1 − x²)
```

Differentiate:

```
A'(x) = 2·√(1 − x²) + 2x · (−x / √(1 − x²))
      = 2(1 − 2x²) / √(1 − x²)
```

`A'(x) = 0` at `x² = 1/2`, i.e. `x = 1/√2 ≈ 0.707`. Compare with the endpoints:

```
A(0) = 0,    A(1) = 0,    A(1/√2) = 2 · (1/√2) · (1/√2) = 1
```

**Conclusion:** the maximum area is **exactly 1**, reached at `x = 1/√2`:

- base `2x = √2 ≈ 1.41`
- height `√(1 − 1/2) = √2/2 ≈ 0.71`
- ratio base : height = **2 : 1** — the optimal rectangle is **two squares side
  by side** (each of side `√2/2`), and its area equals the square of the radius.

---

## Osservazione — the no-calculus path (different parametrization)

Parametrize instead by the angle `θ` of the top corner (from the center):

```
base = 2·cos θ,    height = sin θ
A = 2·sin θ·cos θ = sin 2θ  ≤  1
```

Done — the double-angle identity *is* the whole solution. Equality holds at
`2θ = 90°`, i.e. `θ = 45°`, giving the same 2:1 rectangle and the same maximum
area 1. Two parametrizations, one answer: the geometry was never about the
calculus.

*(Nice tie-in for the demo: this is exactly the "same data, different tool"
philosophy of the app — the strokes on the canvas are the problem; the pen and
the shapes are just clients.)*

---

## Backup problems (if the jury asks for more, or timing allows)

| Problem | Punchline | Effort |
|---|---|---|
| Cut a wire of length L into a square + a circle; minimize the total area | the critical point is the **minimum**; the maximum is "don't cut — all circle" | lowest algebra, most surprising |
| Minimize the metal in a soda can (fixed volume) | the optimal can has `h = 2r`; real cans are ~3.5× taller — why? (stacking, branding, foam) | relatable, great discussion starter |
| Largest rectangle inside a right triangle (legs on the axes) | its area is **half** the triangle's, and it touches the hypotenuse midpoint (Thales) | cleanest algebra of all |
| Among all trapezoids inscribed in a unit semicircle, the one with maximum perimeter | `p(x) = 4 − 2x + 2√(2x)`, max **p = 5** at `x = 1/2` (the escalation of this problem — reuse the same figure) | the "encore" |

---

## Where the AI tutor slots in (roadmap tie-in)

This same exercise is the scripted scenario for the AI features described in the
README: after the human solves it, the **AI tutor** could re-play the flow —
detect the drawn `?`, suggest the derivative step, catch a sign error in red,
and plot `A(x)` with the critical point `x = 1/√2` marked. A natural closing
line for the demo: *"everything you just watched me draw by hand is exactly what
the tutor will do with the student — on the same canvas, saved in the same
document."*
