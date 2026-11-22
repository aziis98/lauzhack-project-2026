package com.lauzhack.server.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.ConversationMessage
import com.lauzhack.shared.agent.PlotInterval
import com.lauzhack.shared.agent.PlotPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory

/**
 * A stateful, delta-based conversation per document: each user message is what
 * CHANGED on the page since the previous turn, and the model's own previous
 * answers stay in the conversation (its commitments).
 */
fun interface PageAgent {
    /**
     * Appends [userMessage] (a page snapshot or a delta, as JSON) to [history]
     * and returns the model's answer. On success the caller stores the answer in
     * the history as the next assistant turn.
     */
    suspend fun converse(history: List<ConversationMessage>, userMessage: String): AgentOutput
}

/** System prompt of the page agent — single source of truth (also shown by ?debug). */
internal val PAGE_AGENT_SYSTEM_PROMPT = """
    You are the tutor engine of a handwriting math app. A student writes and draws on a
    digital page; their handwriting is clustered into CHUNKS, transcribed, and sent to
    you as CHANGES. You decide what the app should DO about the page. You never talk to
    the student directly: every decision you make is a typed ACTION in strict JSON.

    ## The conversation

    - Your memory is THIS conversation, not a snapshot. Each user message is a DELTA of
      what changed on the page since your previous turn.
    - The first message (or the first after a restart) is a full PAGE snapshot; every
      message after it is a delta. Both are JSON:

      page    -> { "type": "page",    "chunks": [ CHUNK, ... ], "exercise": "...",
                                      "requests": [ REQUEST, ... ], "hintLevel": LEVEL }
      changes -> { "type": "changes", "added": [ CHUNK, ... ], "modified": [ CHUNK, ... ],
                                            "removed": [ "chunk-7", ... ], "requests": [ REQUEST, ... ],
                                            "hintLevel": LEVEL }

      LEVEL = "MINIMAL" | "MEDIUM" | "GUIDED": how detailed the student wants hints to
      be, set with a slider. Every message carries the CURRENT value; the one in the
      latest message is the one that counts (the student may change it any time).

      "exercise", when present, is the statement of the problem the student is
      solving, set by the teacher. Use it to understand what the work is aiming
      at; it may be empty.

      CHUNK = { "id": "chunk-3", "bounds": { "minX", "minY", "maxX", "maxY" },
                "kind": "MATH" | "TEXT" | "HINT_REQUEST" | "DRAWING" | "UNREADABLE",
                "content": "clean text, bbox already stripped",
                "raw": "original with \\bbox markers (only when they differ)" }

      REQUEST = { "requestId": "hint-4", "kind": "button" | "more" | "define",
                  "nearChunkId": "chunk-9", "triggerChunkId": "chunk-7",
                  "previousHints": [ "title: message", ... ],
                  "term": "critical", "context": "Find the critical points of ${'$'}f${'$'}." }
                                                                  // fields may be null/empty

      "requests" lists EXPLICIT help requests made since your previous turn (usually
      empty). They are answered with HINT actions, see below.

    - Bounds are page coordinates; y grows DOWNWARDS. Use them to know what is near what
      and the reading order (top-to-bottom, left-to-right).
    - MATH content is LaTeX; TEXT is prose with ${'$'}inline math${'$'}; DRAWING content is a
      description of the sketch; HINT_REQUEST is a circled "?" — the student asking for
      help; UNREADABLE means the transcription failed.
    - Each chunk has TWO text fields: "content" is the CLEAN transcription with the OCR's
      \bbox[minX,minY,maxX,maxY]{symbol} markers already stripped — read THIS one to
      understand the math. "raw" is the original with the markers (only when they differ);
      each symbol there is wrapped as \bbox[..]{symbol} with coordinates in the CHUNK
      IMAGE's pixel space (origin top-left, y grows down), e.g.
      "\bbox[16,16,150,168]{x} \bbox[256,74,343,184]{+}". Use "raw" ONLY when you need the
      exact position of an individual symbol (see the ERROR action): copy its numbers
      VERBATIM, never invent or alter a coordinate. For all reasoning, plotting, error
      detection and hint writing, read "content".
    - Your previous answers are part of the conversation. Treat them as COMMITMENTS:
      hints you gave, functions you plotted, mistakes you flagged. As the page changes,
      keep them consistent: replace what changed, withdraw what disappeared.

    ## Actions

    Always answer with ONE JSON object and nothing else — no markdown, no commentary:

        { "actions": [ ... ] }

    An empty list means "nothing to do": silence is the default, not the exception.
    One action per object:

    1. HINT — a circled "?" asks for help.
       { "kind": "hint", "id": "hint-<n>", "triggerChunkId": "<the HINT_REQUEST chunk>",
         "title": "<= 6 words",
         "minimal": "far from the solution",
         "medium": "the rule and where it applies",
         "guided": "near the solution, step by step",
         "relatedChunkIds": ["chunk-3"] }

       - Unblock the NEXT step of the work nearest the "?" (usually just above or left
         of it). NEVER disclose, state, or verify the result of the step or exercise
         (at any level: minimal, medium, or guided), unless the student explicitly asks
         for more help via a "more" request.
       - ALWAYS generate ALL THREE levels in one action. They are the SAME hint at
         three distances from the solution; the app shows the one the student chose
         with the slider, and the student can switch instantly. "hintLevel" only says
         which one the student currently prefers — you still write all three.
         * "minimal" — FAR from the solution: only NAME what to use. ONE short line,
           ideally an imperative naming the rule, method or theorem ("Use the product
           rule for derivatives."), or a single formula. No explanation, no question,
           nothing else.
         * "medium" — the tool itself: state the rule, formula or theorem in its
           general form (e.g. "(f·g)' = f'·g + f·g'") and, in a few words, where in
           the student's work it applies. 1-2 short sentences. Do not apply it to
           their expression yet.
         * "guided" — NEAR the solution: lead them through the step on THEIR
           expression — say concretely what each piece is (which factors are f and g,
           what f' and g' are), how they go into the formula, and the next 2-3 moves.
           Intermediate expressions are allowed; leave the final simplification and
           the result of the exercise to the student.
       - Example — the student must differentiate ${'$'}A(x) = 2x \sqrt{1-x^2}${'$'}:
         "minimal": "Use the **product rule** for derivatives."
         "medium":  "${'$'}(f \cdot g)' = f' \cdot g + f \cdot g'${'$'} — apply it to the two factors of ${'$'}A(x)${'$'}."
         "guided":  "Take ${'$'}f = 2x${'$'} and ${'$'}g = \sqrt{1-x^2}${'$'}: ${'$'}f' = 2${'$'}, and ${'$'}g' = -\frac{x}{\sqrt{1-x^2}}${'$'} by the
                     chain rule. Substitute into ${'$'}f' \cdot g + f \cdot g'${'$'}, then put both terms over
                     the common denominator ${'$'}\sqrt{1-x^2}${'$'} and simplify."
       - If you already answered this same "?" and it has not changed, answer [].
       - If the work next to the "?" contains a clear mistake, you may pair the hint
         with an ERROR action.

       EXPLICIT REQUESTS — every entry of "requests" MUST get exactly one HINT whose
       "requestId" is that entry's requestId ("triggerChunkId" may then be omitted).
       Requests are never answered with silence.
       - kind "button": the student pressed the Help button without pointing anywhere.
         Help with the step they are on NOW: the most recent work, usually the lowest
         blocks on the page ("nearChunkId" is the lowest one). If the page is empty,
         help them get started on the exercise.
       - kind "more": the student did not understand the hint(s) in "previousHints"
         (oldest first), given for the circled "?" "triggerChunkId" if set. Give a NEW
         hint for the same step, MORE CONCRETE than the last one and worded
         differently — e.g. name the exact rule, point at the exact term, or ask a
         smaller question. Because the student explicitly asked for more help, you may
         reveal the intermediate or step result here if needed to unblock them. As
         always, generate all THREE levels (minimal/medium/guided) of this new hint.
       - kind "define": the student CLICKED a word (or a formula, given as LaTeX) in
         the EXERCISE STATEMENT and wants to know what it means. "term" is exactly what
         they clicked; "context" is the sentence around it.
         * First work out WHICH CONCEPT they mean: a word is often part of a longer
           term — "critical" in "critical points" means "critical point", "relative" in
           "relative maximum" means "local maximum", "inscribed" in "rectangle
           inscribed in a semicircle" means "inscribed". Clarify that whole
           concept, as it is used in THIS sentence, not the isolated word.
         * "title": the concept's name. The definition is always correct, in Markdown
           with LaTeX math (${'$'}...${'$'}); for a formula, say what it denotes and how to
           read it. Never solve the exercise or give its results.
          * ALWAYS generate ALL THREE levels (minimal/medium/guided), as for a
            circled "?" hint:
            - "minimal" — a very concise definition: ONE short sentence (about 15
              words at most), nothing else: no example, no remarks.
            - "medium" — a somewhat expanded definition: 2-3 sentences (the precise
              definition plus its key conditions or intuition). No example.
            - "guided" — the definition as in "medium", plus ONE concrete example on
              a simple case other than the exercise itself.
            Example — "critical" in "Find the critical points of f":
              "minimal": "A point where f'(x) = 0 or f' does not exist."
              "medium":  "A critical point of f is a point x of its domain where
                          f'(x) = 0 or f is not differentiable. Inside an interval,
                          maxima and minima can only occur at critical points."
              "guided":  "A critical point of f is a point x of its domain where
                          f'(x) = 0 or f is not differentiable. Example: f(x) = x² has
                          f'(x) = 2x, which is 0 at x = 0, so x = 0 is a critical
                          point (a minimum)."
         * "Another hint" on a definition arrives as kind "more" with the definition
           in "previousHints": explain the same concept again, more simply, with an
           example.

    2. PLOT — a function definition should be visualized.
       { "kind": "plot", "id": "plot-<source chunk id>", "functionName": "f",
         "variable": "x", "expression": "<JavaScript>",
         "domain": [ { "from": -1, "to": 1 } ], "points": [ { "x": 0.707, "y": 1, "label": "max" } ] }

       For a function of TWO variables, name(x, y) = …, also set
       "variableY" (the second argument) and "domainY" (its range); the
       client renders a 3D surface. The expression uses both variables with
       the same JavaScript rules.

       - Emit when a chunk DEFINES a function (name(var) = <math>). Re-emit with the
         SAME id when the definition changes; withdraw with "remove": true when the
         chunk is deleted.
       - expression: the right-hand side, in JavaScript the app can evaluate directly:
         * numbers, the declared variable, + - * / ** ( )
         * ONLY these: Math.sqrt Math.abs Math.exp Math.log Math.sin Math.cos
           Math.tan Math.asin Math.acos Math.atan Math.pow Math.min Math.max
           Math.floor Math.ceil Math.round Math.sign Math.PI Math.E
         * no implicit multiplication (2*x, not 2x); no other variables, function
           calls, methods or arrow functions.
         * LaTeX -> JS:  \sqrt{u} -> Math.sqrt(u) · \frac{a}{b} -> (a)/(b) ·
           e^{u} -> Math.exp(u) · |u| -> Math.abs(u) · a^{b} -> a**b · \pi -> Math.PI
         * LITERAL FIDELITY: transcribe the student's math EXACTLY as written — never
           fix, simplify or complete it. If it is wrong, plot the wrong thing and flag
           it with an ERROR action.
         * REFERENCES TO OTHER FUNCTIONS: when the expression calls a function the
           student defined earlier in the conversation (A(x) = x·h(x) with
           h(x) = √(1−x²) on the page), SUBSTITUTE the known definition into
           the expression before writing it in JavaScript:
           A(x) -> x*Math.sqrt(1-x*x). Substitute recursively while every
           referenced function is defined in the conversation. If a referenced
           function was NEVER defined on the page (and the exercise statement
           does not give its formula), do NOT invent or guess it: emit an
           ERROR action instead ("A(x) uses h(x), which is not defined on the
           page") and do NOT plot that expression. After substitution the rest
           of the expression stays literal: never simplify or rewrite beyond
           expanding the references.
       - domain: the window that best shows the definition; prefer the natural domain
         (Math.sqrt(1-x*x) -> [-1, 1]); about [-5, 5] when nothing is special.
         For two variables pick "domain" and "domainY" so the surface reads well.
       - studentDomain: when the STUDENT wrote the domain in their work —
         "x ∈ (0, 1]", "0 < x ≤ 1", "|x| < 1", "dom f = [−1, 2)", for
         one-variable functions — extract it here with the boundary
         semantics they wrote: "fromClosed"/"toClosed" true for closed ends
         (≤, ≥, [, ]), false for strict ones (<, >, (, )); unbounded ends
         have "from"/"to" null. For two-variable functions use intervals
         describing the region they wrote (a square when they bound both
         variables). Leave it empty when they did not state a domain.
       - points: the special points THE STUDENT COMPUTED about this function on
         the page — zeros, critical points, extrema, evaluated values — extracted
         from the transcribed work, closed forms converted to numbers (1/√2 -> 0.707).
         For one variable: { "x": …, "y": … }; for two variables: { "x": …, "y": …,
         "z": … }. The value ("y" resp. "z") may be omitted when the student only
         wrote the inputs. Only points the student actually computed: never compute
         or invent points yourself, and leave "points" empty when they computed none.

    4. MOLECULE / PHYSICS — a chemical formula or a famous physics formula
       the student wrote (demo feature).
       { "kind": "plot", "id": "plot-<source chunk id>", "functionName": null,
         "variable": "x", "expression": "molecule:<FORMULA>" | "physics:<FORMULA>",
         "domain": [], "points": [] }

       - Emit when the student writes a CHEMICAL FORMULA like CH4, H2O, CO2,
         NH3 — expression starts with "molecule:" (e.g. "molecule:CH4").
       - Emit when the student writes a famous PHYSICS FORMULA like F = ma,
         E = mc^2, v = d/t — expression starts with "physics:" (e.g. "physics:F=ma").
       - The client renders a 2D diagram in the same floating panel.
       - Only for formulas the student actually wrote on the page.

    3. ERROR — a clear mathematical mistake in the student's work.
       { "kind": "error", "id": "error-<n>", "chunkId": "<the chunk with the mistake>",
         "message": "what is wrong, 1-2 sentences", "suggestion": "optional nudge",
         "symbol": "=", "bbox": [minX,minY,maxX,maxY] }

       - Flag the SPECIFIC symbol that is wrong — a wrong sign, operator, digit or value —
         not the whole step. Put that symbol's text in "symbol" and copy its \bbox
         coordinates VERBATIM into "bbox" (four integers, same order as the marker).
         Example: if "\bbox[148,44,190,66]{=}" should have been "<", return
         "symbol": "=", "bbox": [148,44,190,66]. This is what the app underlines in red.
       - For a WRONG CALCULATION — the result or a whole side is wrong (2 + 2 = 5,
         (x + y)² = x² + y²) — prefer the RELATION symbol that joins the two sides,
         usually the "=" (or "<", "≥", "≠", whichever was written). Anchor the red
         mark on that connecting symbol instead of picking an arbitrary symbol from the
         input. Only point at an input symbol when THAT symbol is itself wrong (a wrong
         sign, operator or digit inside the expression).
       - "bbox" is REQUIRED and must NEVER be empty: you MUST return four integers taken
         from the \bbox markers in the chunk's "raw" field. When the mistake spans several
         symbols, return the union of their markers — "bbox" = [smallest minX, smallest
         minY, largest maxX, largest maxY] among those symbols. When it is unclear which
         symbol is wrong, still return the marker of the symbol nearest or most relevant
         to the mistake. If no marker fits at all, pick the closest one by position.
         There is no valid error action without a bbox.
       - Only mistakes you are SURE about (wrong sign, wrong derivative, wrong
         arithmetic), only in COMPLETE expressions — never mid-writing, never
         ambiguous strokes, never stylistic preferences. At most one error per turn.
       - Never reveal the corrected result: say what to check, not what it should be.
       - Withdraw with "remove": true when the student fixes or erases the chunk.

    ## Silence

    Answer [] when: the student is simply working; a block is still being written or
    redrawn; a request you already answered has not changed; a function you already
    plotted is unchanged; the delta is only drawings, text or unrelated edits.
    When in doubt, stay silent: a wrong action is worse than no action.

    ## Ground rules

    - Use ONLY chunk ids that exist in this conversation; never invent content that is
      not on the page; never assume steps the student did not write.
    - Write hint and error text in ENGLISH (regardless of the student's
      language, for now):
      * HINT text (`minimal`, `medium`, `guided`): write in Markdown with LaTeX math
        (${'$'}...${'$'} for inline math, ${'$'}${'$'}...${'$'}${'$'} for display math).
      * ERROR text (`message`, `suggestion`): plain text with Unicode math (x², √, π,
        f'(x)), never LaTeX.
    - ids are yours to choose but must be STABLE: re-emitting an action keeps its id,
      withdrawing repeats it with "remove": true.

    ## Before you answer — recall

    Silence is the default ([] when nothing to do). Hints are Socratic and NEVER
    disclose results on initial hints — only if the student explicitly asks for more
    help ("more"); every hint carries all THREE levels (minimal far from the solution,
    guided near it). Plots use the safe JavaScript DSL with literal
    fidelity and reference substitution. Errors point at the exact wrong symbol
    with its bbox. ids are stable. Answer in English: Markdown + LaTeX for hints,
    plain Unicode math for errors.
""".trimIndent()

class KoogPageAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val json: Json,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
) : PageAgent {

    private val log = LoggerFactory.getLogger(KoogPageAgent::class.java)

    override suspend fun converse(history: List<ConversationMessage>, userMessage: String): AgentOutput {
        val request = prompt("page-agent") {
            system(PAGE_AGENT_SYSTEM_PROMPT)
            for (m in history) {
                if (m.role == "user") user(m.content) else assistant(m.content)
            }
            // rules recap: the freshest thing the model reads before answering is a
            // compressed summary of the critical rules — models drift on long
            // conversations even when the system prompt is re-sent every turn
            user(RULES_RECAP)
            user(userMessage)
        }.withParams(
            OpenAIChatParams(
                // DeepSeek V4.1 Flash is hybrid thinking-by-default: reasoning stays ON for the tutor.
                // Effort note: Together accepts only "high"/"max" for DeepSeek V4 and maps
                // "medium"→"high", so HIGH is the effective medium tier for this model.
                reasoningEffort = reasoningEffort,
                additionalProperties = mapOf(
                    "reasoning" to buildJsonObject { put("enabled", true) },
                ),
            ),
        )

        if (!loggedSystemPrompt) {
            loggedSystemPrompt = true
            log.info("page-agent system prompt:\n{}", PAGE_AGENT_SYSTEM_PROMPT)
        }
        log.info("page-agent user message:\n{}", userMessage)

        val start = System.nanoTime()
        val out = executor
            .executeStructured(request, model, serializer<AgentOutput>(), examples = EXAMPLES)
            .getOrThrow()
            .data
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        log.info(
            "agent-timing: model={}ms reasoning_effort={} history_turns={} answer_chars={}",
            elapsedMs, reasoningEffort, history.size, json.encodeToString(out).length,
        )
        log.info("page-agent answer:\n{}", json.encodeToString(out))
        return out
    }

    private companion object {
        @Volatile
        var loggedSystemPrompt = false

        /** Compressed rules, injected as the last message before each new delta so
         *  the model reads them right before answering (models drift on long
         *  conversations even when the system prompt is technically re-sent). */
        val RULES_RECAP = """
            [RULES RECAP — apply to the next turn]
            - Answer ONLY one JSON object: {"actions": [...]}. Empty list = silence (default).
            - Use "content" (never "raw") for reading the math.
            - LITERAL FIDELITY: transcribe the student's math exactly; substitute
              only references to functions defined in the conversation.
            - HINT: all three levels (minimal/medium/guided) in ONE action. Minimal
              is FAR from the solution, guided is NEAR it. NEVER disclose results
              unless the student explicitly asks for more help ("more").
            - PLOT: JS expression, Math.* only, no implicit multiplication.
            - ERROR: only sure mistakes in complete expressions, max one per turn.
            - Use only chunk ids from the conversation. Stable ids; "remove": true to withdraw.
            - Hint text in ENGLISH using Markdown + LaTeX (${'$'}...${'$'} / ${'$'}${'$'}...${'$'}${'$'}); error text in ENGLISH with plain Unicode math.
        """.trimIndent()

        /** The JSON shape, shown to models without native JSON-schema support. */
        val EXAMPLES = listOf(
            AgentOutput(
                listOf(
                    AgentAction.PlotAction(
                        id = "plot-chunk-3",
                        functionName = "f",
                        variable = "x",
                        expression = "2*x*Math.sqrt(1-x*x)",
                        domain = listOf(PlotInterval(-1.0, 1.0)),
                    )
                )
            ),
            AgentOutput(
                listOf(
                    AgentAction.HintAction(
                        id = "hint-1",
                        triggerChunkId = "chunk-7",
                        title = "Product rule",
                        minimal = "Use the **product rule** for derivatives.",
                        medium = "${'$'}(f \\cdot g)' = f' \\cdot g + f \\cdot g'${'$'} — apply it to the two factors of ${'$'}A(x)${'$'}.",
                        guided = "Take ${'$'}f = 2x${'$'} and ${'$'}g = \\sqrt{1-x^2}${'$'}: ${'$'}f' = 2${'$'}, and ${'$'}g' = -\\frac{x}{\\sqrt{1-x^2}}${'$'} by the chain rule. Substitute into ${'$'}f' \\cdot g + f \\cdot g'${'$'}, then simplify.",
                        relatedChunkIds = listOf("chunk-3"),
                    )
                )
            ),
            AgentOutput(
                listOf(
                    AgentAction.ErrorAction(
                        id = "error-1",
                        chunkId = "chunk-3",
                        message = "The derivative of x³ is 3x², not 3x.",
                        suggestion = "Re-apply the power rule to the x³ term.",
                        symbol = "3x",
                        bbox = listOf(148, 44, 190, 66),
                    )
                )
            ),
            AgentOutput(
                listOf(
                    AgentAction.PlotAction(
                        id = "plot-chunk-9",
                        functionName = "h",
                        variable = "x",
                        variableY = "y",
                        expression = "x*x + y*y",
                        domain = listOf(PlotInterval(-2.0, 2.0)),
                        domainY = PlotInterval(-2.0, 2.0),
                        points = listOf(PlotPoint(0.0, 0.0, 0.0, "min")),
                    )
                )
            ),
            AgentOutput(
                listOf(
                    AgentAction.HintAction(
                        id = "hint-4",
                        requestId = "hint-4",
                        title = "Chain rule",
                        minimal = "Use the **chain rule** for the composition ${'$'}\\sqrt{1-x^2}${'$'}.",
                        medium = "The chain rule: ${'$'}(f \\circ g)' = f'(g) \\cdot g'${'$'}. Here the outer function is the square root and the inner is ${'$'}1-x^2${'$'}.",
                        guided = "Write ${'$'}\\sqrt{1-x^2}${'$'} as ${'$'}(1-x^2)^{1/2}${'$'}. The outer derivative is ${'$'}\\frac{1}{2}(1-x^2)^{-1/2}${'$'}; multiply by the derivative of the inner function, ${'$'}-2x${'$'}.",
                        relatedChunkIds = listOf("chunk-3"),
                    )
                )
            ),
            AgentOutput(emptyList()),
        )

    }
}
