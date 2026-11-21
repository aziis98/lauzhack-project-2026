package com.lauzhack.server.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.PageContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer

/** Decides what to do about a page. */
fun interface PageAgent {
    suspend fun decide(page: PageContext): AgentOutput
}

/**
 * [PageAgent] backed by a text model through Koog, with structured (JSON) output:
 * the answer is an `AgentOutput` whose `actions` list is empty when nothing is needed.
 */
class KoogPageAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val json: kotlinx.serialization.json.Json,
) : PageAgent {

    override suspend fun decide(page: PageContext): AgentOutput {
        val request = prompt("page-agent") {
            system(SYSTEM_PROMPT)
            user { text("Current page state:\n" + json.encodeToString(page)) }
        }
        val out = executor
            .executeStructured(request, model, serializer<AgentOutput>(), examples = EXAMPLES)
            .getOrThrow()
            .data

        // Defensive: the agent only knows chunk ids from the page it was given.
        val known = page.chunks.map { it.id }.toSet()
        return AgentOutput(
            actions = out.actions.mapNotNull { action ->
                when (action) {
                    is AgentAction.HintAction -> if (action.triggerChunkId in known) action else null
                    is AgentAction.PlotAction -> action
                }
            }
        )
    }

    private companion object {
        /** Few-shot examples covering the whole contract, including the empty list. */
        val EXAMPLES = listOf(
            // the full page: a help request next to the function it is about, and a plot
            AgentOutput(
                listOf(
                    AgentAction.HintAction(
                        triggerChunkId = "chunk-7",
                        title = "Product rule",
                        message = "2x·√(1−x²) is a product: which rule derives f·g?",
                        steps = listOf("What are f(x) and g(x)?"),
                        relatedChunkIds = listOf("chunk-3"),
                    ),
                    AgentAction.PlotAction(
                        functionName = "f",
                        variable = "x",
                        expression = "2*x*sqrt(1-x^2)",
                        domain = listOf(com.lauzhack.shared.plot.Interval(-1.0, 1.0)),
                    ),
                )
            ),
            AgentOutput(emptyList()),
            AgentOutput(
                listOf(
                    AgentAction.PlotAction(
                        functionName = "g",
                        variable = "x",
                        expression = "x^3-3*x",
                        domain = listOf(com.lauzhack.shared.plot.Interval(-3.0, 3.0)),
                    )
                )
            ),
        )

        val SYSTEM_PROMPT = """
            You are the assistant of a handwriting math app. You receive ONE page of a student's
            work as JSON: blocks of handwriting in reading order, each with its position on the
            page (y grows downwards), its kind and its transcription. Hints you already gave are
            listed too. You must decide what to DO about the page.

            Answer with one JSON object {"actions": [...]}. The list is EMPTY when there is
            nothing to do. Otherwise add one object per action, discriminated by "kind":

            1. {"kind": "hint", ...} — ONLY when a block has kind "HINT_REQUEST": the student
               drew a circled "?" to ask for help. Give ONE hint that unblocks the NEXT step of
               the work closest to the circled "?" (mostly above or left of it), never the
               solution. Be Socratic: point to the idea, rule or theorem, or ask a guiding
               question. Keep it short: title ≤ 6 words, message ≤ 2 sentences, at most 3 steps.
               Write in the language the student writes in (default: Italian); math in plain
               Unicode (x², √, π, f'(x)), never LaTeX. triggerChunkId is the HINT_REQUEST block's
               id; relatedChunkIds are the blocks the hint refers to. If hints were already
               given for the same request, be a bit more concrete than before, still without
               solving.

            2. {"kind": "plot", ...} — when the page contains a function definition like
               name(var) = expression. Plot the right-hand side only, rewritten in the engine's
               safe DSL and nothing else:
               * numbers, the variable, binary + - * / ^ and parentheses;
               * one-argument functions: sqrt abs exp ln log sin cos tan asin acos atan;
               * constants: pi, e;
               * NO implicit multiplication: write 2*x not 2x, x*(x+1) not x(x+1);
               * LaTeX like \sqrt{1-x^2} becomes sqrt(1-x^2), \frac{a}{b} becomes (a)/(b),
                 e^{...} becomes exp(...), |x| becomes abs(x);
               * keep the formula EXACTLY as the student wrote it: never simplify, never fix
                 mistakes, never complete an empty right-hand side.
               domain: the range that shows the definition well, prefer the natural domain
               (sqrt(1-x^2) → [-1, 1]); about [-5, 5] when nothing special. One plot action
               per distinct function definition.

            3. [] — no HINT_REQUEST block and no function definition: the student is just
               working. Do not invent actions, do not correct work that was not asked about,
               do not plot expressions that are not defined as functions.

            The engine validates your JSON: unknown chunk ids and expressions outside the safe
            DSL are dropped, so an action that cannot be executed is worse than no action.
        """.trimIndent()
    }
}
