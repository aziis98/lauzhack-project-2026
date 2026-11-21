package com.lauzhack.web.markdown

/**
 * KaTeX errors of every `$…$` / `$$…$$` formula in [markdown], e.g.
 * `"\frac{1}: Expected group after '\frac'"`; empty when everything renders.
 */
fun mathErrors(markdown: String): List<String> {
    val errors = mutableListOf<String>()
    fun check(tex: String, display: Boolean) {
        val options: dynamic = js("({})")
        options.displayMode = display
        options.throwOnError = true
        try {
            katex.renderToString(tex.trim(), options)
        } catch (e: Throwable) {
            val message = (e.asDynamic().message as? String) ?: e.toString()
            errors += "${tex.trim().take(60)}: ${message.removePrefix("KaTeX parse error: ")}"
        }
    }
    val withoutDisplay = Regex("""\$\$([\s\S]+?)\$\$""").replace(markdown) { check(it.groupValues[1], true); " " }
    Regex("""(?<!\\)\$([^\n$]+?)\$""").findAll(withoutDisplay).forEach { check(it.groupValues[1], false) }
    return errors
}
