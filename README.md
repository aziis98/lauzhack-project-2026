<p align="center">
  <img src="branding/logo-wordmark-light.png" alt="Hintsight" width="520">
</p>

<p align="center"><b>A digital worksheet with a tutor that only speaks when you ask.</b><br>
Built at LauzHack 2026.</p>

![Hintsight canvas](docs/hero.png)

---

## The idea

When you study maths, the hardest moment is being stuck alone in front of an
exercise. Looking up the solution kills the learning; asking a chatbot usually
gives you the whole answer, and you have to retype your work for it anyway.

**Hintsight** keeps you on the page. You solve the exercise by hand, on a tablet
or with a mouse, just like on paper. When you get stuck you **draw a question
mark inside a circle**, the way you would mark a doubt in your notebook, and a
small hint card appears right next to your work.

The tutor reads what you wrote, understands where you are in the exercise, and
gives you a **nudge towards the next step, never the solution**.

## What you can do

**✍️ Write naturally.** Every worksheet is a page you write on with a pen.
Your handwriting stays handwriting; behind the scenes Hintsight reads it so the
tutor can follow your reasoning.

**❓ Ask for a hint by drawing a circled "?".** The card appears next to the
question mark. Didn't get it? Press *"Another hint"* and the tutor goes one step
further, remembering what it already told you. You can also press the **?**
button in the toolbar, and the hint is about your most recent work.

**🎚️ Choose how much help you want.** A slider sets the level of detail:

| Level | What you get (example: derivative of a product) |
|---|---|
| Minimal | Just the idea: *"Use the rule for the derivative of a product."* |
| Medium | The rule itself and where it applies, still to be applied by you. |
| Guided | The rule applied to *your* expression, with the next steps laid out. |

**📖 Tap a word of the exercise to see what it means.** Don't remember what a
"critical point" is? Tap it in the exercise text and a short definition appears,
with an example at the most detailed level.

**🔴 See your mistakes.** If you make a clear calculation error, the wrong
symbol is highlighted in red. Tap it to read what is wrong and a suggestion, but the
fix is up to you.

**📈 Look at the graph of your function.** When you write a function on the
page, a plot button appears next to it. The graph shows the domain you wrote and
the points *you* computed (zeros, maxima, minima), so you can check your work
visually. Functions of two variables are shown as 3D surfaces.

**📄 Import exercises from a PDF.** Upload a PDF of exercises, select the parts
you want, and Hintsight turns them into clean text with properly typeset
formulas. Figures are kept as images. Each exercise becomes a new worksheet.

## The principles behind it

- **The student does the work.** The tutor hints, points and shows; it never
  writes the final answer.
- **Help only on request.** Hints appear when you ask. Mistakes are marked
  discreetly, without interrupting you.
- **Everything happens on the page.** No chat window to switch to and no work to
  retype: the tutor sees what you see.
- **Based on what you wrote.** Plots show your own results, and hints refer to
  your own expressions.

## Try it

You need Java 21 and an API key for [Together AI](https://www.together.ai),
the service that runs the AI models.

```bash
export TOGETHER_API_KEY=your-key
./gradlew :web:jsBrowserDistribution
./gradlew :server:run
```

Then open <http://localhost:8080>. Without an API key the app still works as a
plain drawing worksheet, just without the tutor.

## Learn more

- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit together, from a pen
  stroke to a hint card.
- [TECHNICAL_DETAILS.md](TECHNICAL_DETAILS.md): setup, configuration, API,
  data model, AI models and prompts.
- [docs/demo/demo-problem.md](docs/demo/demo-problem.md): the exercise we
  solve live in the demo.

Built with Kotlin (server, shared logic and web app), [tldraw](https://tldraw.dev)
for the canvas, and DeepSeek models on Together AI through JetBrains
[Koog](https://github.com/JetBrains/koog).
