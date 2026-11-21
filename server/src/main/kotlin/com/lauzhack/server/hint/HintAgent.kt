package com.lauzhack.server.hint

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.math.roundToInt

/** Everything the hint agent knows when the student asks for help. */
data class HintContext(
    /** All chunks of the page in reading order, with their transcriptions. */
    val page: List<TranscribedChunk>,
    /** The chunk holding the circled "?". */
    val trigger: TranscribedChunk,
    /** Hints already given on this page, oldest first (to make the next one more explicit). */
    val previousHints: List<HintCard>,
    /** Exercise statement (Markdown + LaTeX); empty when the page has none. */
    val exercise: String = "",
)

/** Produces a [HintCard] for a help request. */
fun interface HintGenerator {
    suspend fun generate(context: HintContext): HintCard
}

/** [HintGenerator] backed by an LLM through Koog, with structured (JSON) output. */
class KoogHintAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
) : HintGenerator {

    @Serializable
    private data class HintOutput(
        val title: String,
        val message: String,
        val steps: List<String> = emptyList(),
        val relatedChunkIds: List<String> = emptyList(),
    )

    override suspend fun generate(context: HintContext): HintCard {
        val request = prompt("hint") {
            system(SYSTEM_PROMPT)
            user(describe(context))
        }
        val out = executor.executeStructured(request, model, serializer<HintOutput>(), examples = EXAMPLES)
            .getOrThrow().data
        val known = context.page.map { it.chunk.id }.toSet()
        return HintCard(
            title = out.title.trim(),
            message = out.message.trim(),
            steps = out.steps.map { it.trim() }.filter { it.isNotEmpty() },
            relatedChunkIds = out.relatedChunkIds.filter { it in known },
        )
    }

    companion object {
        /**
         * The page as the model sees it: one line per chunk with its position
         * (page units, y grows downwards) and content; the trigger is marked.
         */
        fun describe(context: HintContext): String = buildString {
            if (context.exercise.isNotBlank()) {
                appendLine("Exercise statement:")
                appendLine(context.exercise.trim())
                appendLine()
            }
            appendLine("The student's page, one block per line, top to bottom:")
            for (tc in context.page) {
                val b = tc.chunk.bounds
                append("[").append(tc.chunk.id).append("] ")
                append("x=").append(b.minX.roundToInt()).append("..").append(b.maxX.roundToInt())
                append(" y=").append(b.minY.roundToInt()).append("..").append(b.maxY.roundToInt()).append("  ")
                val t = tc.transcription
                when {
                    tc.chunk.id == context.trigger.chunk.id -> append("<< CIRCLED ? — the student asks for help HERE >>")
                    t == null -> append("(not transcribed yet)")
                    t.kind == ChunkKind.HINT_REQUEST -> append("(older circled ?)")
                    t.content.isBlank() -> append(t.kind.name)
                    else -> append(t.kind.name).append(": ").append(t.content)
                }
                appendLine()
            }
            if (context.previousHints.isNotEmpty()) {
                appendLine()
                appendLine("Hints you already gave on this page (oldest first):")
                context.previousHints.forEachIndexed { i, h -> appendLine("${i + 1}. ${h.title}: ${h.message}") }
                appendLine("The student is still asking: be a bit more concrete than before, without solving it for them.")
            }
            appendLine()
            append("Give ONE hint for the step the student is stuck on, i.e. the work closest to (mostly above or left of) the circled ?.")
        }

        private val EXAMPLES = listOf(
            HintOutput(
                title = "Derivative of a product",
                message = "A(x) = 2x·√(1 − x²) is a product of two functions of x. Which rule do you use to differentiate f·g?",
                steps = listOf("What are f(x) and g(x)?", "What is the derivative of √(1 − x²)?"),
                relatedChunkIds = listOf("chunk-3"),
            ),
        )

        private val SYSTEM_PROMPT = """
            You are a patient math tutor inside a handwriting app. A student is solving an exercise
            on a page (the exercise statement, when given, is printed at the top); their handwriting has been split into blocks and transcribed (LaTeX), each with
            its position on the page. The student drew a circled question mark to ask for help.

            Your job: give a HINT that unblocks the NEXT step, not the solution.
            - Figure out the exercise and where the student is, from the blocks near the circled ?
              (usually the last lines above it or on its left).
            - Be Socratic: point to the idea, rule, formula or theorem to use, or ask a guiding question.
            - Never give the final result or do the step for them. If they made a mistake just before,
              say where to look again, without correcting it for them.
            - Keep it short: `title` ≤ 6 words, `message` ≤ 2 sentences, at most 3 `steps`
              (optional sub-questions).
            - Write in the language of the exercise statement and of the student's notes (default: English).
            - Write math in plain Unicode (x², √, ≤, π, f'(x)), never LaTeX.
            - `relatedChunkIds`: ids of the blocks your hint refers to.
            - If the transcription is unclear, say so and ask the student to rewrite the unclear part.
        """.trimIndent()
    }
}
