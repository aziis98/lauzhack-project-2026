package com.lauzhack.web.markdown

/**
 * Renders Markdown with `$…$` (inline) and `$$…$$` (display) LaTeX math to HTML.
 * Math is cut out first so Markdown never mangles it (underscores, asterisks),
 * then rendered with KaTeX and put back.
 */
fun renderMathMarkdown(source: String): String {
    val formulas = mutableListOf<String>()
    fun stash(tex: String, display: Boolean): String {
        val options: dynamic = js("({})")
        options.displayMode = display
        options.throwOnError = false
        formulas += katex.renderToString(tex.trim(), options) as String
        return "@@MATH${formulas.size - 1}@@"
    }

    val withoutMath = source
        .replace(Regex("""\$\$([\s\S]+?)\$\$""")) { stash(it.groupValues[1], display = true) }
        .replace(Regex("""(?<!\\)\$([^\n$]+?)\$""")) { stash(it.groupValues[1], display = false) }

    val html = marked.parse(withoutMath) as String
    return Regex("@@MATH(\\d+)@@").replace(html) { formulas[it.groupValues[1].toInt()] }
}
