package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.shared.exercise.ConvertRegionRequest
import com.lauzhack.web.markdown.mathErrors
import com.lauzhack.web.markdown.renderMathMarkdown
import com.lauzhack.web.pdf.GlobalWorkerOptions
import com.lauzhack.web.pdf.getDocument
import com.lauzhack.web.pdf.pdfWorkerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLCanvasElement
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val importApi = DrawingApi()
private val importScope = CoroutineScope(Dispatchers.Default)

/** Largest display width of a PDF page in the viewer (CSS px); narrower screens fit the column. */
private const val MAX_PAGE_CSS_WIDTH = 720.0

/** Crops are rendered at up to this many pixels on their longest side (and at most 3× the PDF size). */
private const val CROP_MAX_SIDE = 2000.0

/** What a rectangle selection turns into. */
private enum class SelectMode { TEXT, FIGURE }

/** A rectangle on a page, as fractions (0..1) of the page width/height. */
private data class PageRect(val page: Int, val x: Double, val y: Double, val w: Double, val h: Double)

private data class Selection(val n: Int, val rect: PageRect, val mode: SelectMode, val status: String)

private fun obj(): dynamic = js("({})")

private fun scaleOptions(scale: Double): dynamic {
    val o = obj()
    o.scale = scale
    return o
}

@Suppress("UNCHECKED_CAST")
private suspend fun <T> await(promise: dynamic): T = (promise as Promise<T>).await()

external interface PdfImportProps : Props {
    /** The PDF the user picked (a browser `File`). */
    var file: dynamic
    var onClose: () -> Unit
    var onCreated: (documentId: String) -> Unit
}

/**
 * Exercise import from a PDF. The PDF is rendered in the browser (pdf.js); the
 * user drags rectangles over it:
 *  - **Text** → the region (image + the PDF's own text) is converted on the
 *    server into Markdown + LaTeX, figures inside it become images;
 *  - **Figure** → the region is stored as an image as-is.
 * Each result is appended to an editable draft with a live KaTeX preview; the
 * draft becomes the exercise statement of a new worksheet.
 */
val PdfImport = FC<PdfImportProps> { props ->
    var pdf by useState<dynamic>(null)
    var pageCount by useState(0)
    var loadError by useState<String?>(null)
    var mode by useState(SelectMode.TEXT)
    // updated from coroutines too: always through functional setters, never from a captured value
    val (draft, setDraft) = useState("")
    val (selections, setSelections) = useState<List<Selection>>(emptyList())
    var name by useState(titleFromFileName(props.file.name as String))
    var creating by useState(false)
    // page display width: fits the pages column (measured once on open)
    val pagesRef = useRef<web.html.HTMLDivElement>(null)
    var pageWidth by useState<Double?>(null)

    useEffectOnce {
        ensureReadableStreamAsyncIterator()
        val column = (pagesRef.current?.asDynamic()?.clientWidth as? Number)?.toDouble() ?: MAX_PAGE_CSS_WIDTH
        pageWidth = min(MAX_PAGE_CSS_WIDTH, column - 32)
        GlobalWorkerOptions.workerSrc = pdfWorkerUrl
        try {
            val buffer = await<dynamic>(props.file.arrayBuffer())
            val bytes: dynamic = js("new Uint8Array(buffer)")
            val src = obj(); src.data = bytes
            val doc = await<dynamic>(getDocument(src).promise)
            pdf = doc
            pageCount = doc.numPages as Int
        } catch (e: Throwable) {
            loadError = "Could not open this PDF: ${e.message}"
        }
    }

    fun updateSelection(n: Int, status: String) {
        setSelections { list -> list.map { if (it.n == n) it.copy(status = status) else it } }
    }

    fun onSelect(rect: PageRect) {
        val doc = pdf ?: return
        val n = (selections.maxOfOrNull { it.n } ?: 0) + 1
        val selectionMode = mode
        setSelections { it + Selection(n, rect, selectionMode, "working") }
        // reserve the block's place now, so results keep the selection order
        // even when a later selection finishes first (an invisible HTML comment)
        val marker = "<!--selection-$n-->"
        setDraft { appendBlock(it, marker) }
        importScope.launch {
            try {
                val region = cropRegion(doc, rect)
                val markdown = when (selectionMode) {
                    SelectMode.FIGURE -> "![Figure](${importApi.uploadAsset(region.pngBase64).url})"
                    SelectMode.TEXT -> convertWithRetry(region)
                }
                setDraft { d -> if (marker in d) d.replace(marker, markdown.trim()) else appendBlock(d, markdown) }
                updateSelection(n, "done")
            } catch (e: Throwable) {
                setDraft { it.replace(marker, "").replace(Regex("\n{3,}"), "\n\n").trim() }
                updateSelection(n, "failed: ${e.message}")
            }
        }
    }

    ReactHTML.div {
        className = ClassName("pdf-import")

        // ---- header ----
        ReactHTML.div {
            className = ClassName("pdf-import-head")
            ReactHTML.span { className = ClassName("ph ph-file-pdf") }
            ReactHTML.span { className = ClassName("pdf-import-title"); +(props.file.name as String) }

            ReactHTML.div {
                className = ClassName("pdf-mode")
                for ((m, label, icon) in listOf(
                    Triple(SelectMode.TEXT, "Text → LaTeX", "ph-text-t"),
                    Triple(SelectMode.FIGURE, "Figure", "ph-image"),
                )) {
                    ReactHTML.button {
                        key = Key(m.name)
                        className = ClassName(if (mode == m) "pdf-mode-btn active" else "pdf-mode-btn")
                        onClick = { mode = m }
                        ReactHTML.span { className = ClassName("ph $icon") }
                        +label
                    }
                }
            }

            ReactHTML.button {
                className = ClassName("pdf-close")
                title = "Close"
                onClick = { props.onClose() }
                // "x" has no font ligature: use the CSS class form
                ReactHTML.span { className = ClassName("ph ph-x") }
            }
        }

        ReactHTML.div {
            className = ClassName("pdf-import-body")

            // ---- pages ----
            ReactHTML.div {
                className = ClassName("pdf-pages")
                ref = pagesRef
                ReactHTML.p {
                    className = ClassName("pdf-hint")
                    +"Drag a rectangle over the exercise. "
                    +(if (mode == SelectMode.TEXT) "Text and formulas become LaTeX; figures inside it become images."
                    else "The selected area is added as an image.")
                }
                loadError?.let { ReactHTML.p { className = ClassName("pdf-error"); +it } }
                val doc = pdf
                val width = pageWidth
                if (doc != null && width != null) {
                    for (i in 1..pageCount) {
                        PdfPageView {
                            key = Key("page-$i")
                            this.pdf = doc
                            this.width = width
                            pageNumber = i
                            this.selections = selections.filter { it.rect.page == i }
                            onSelect = { rect -> onSelect(rect) }
                        }
                    }
                } else if (loadError == null) {
                    ReactHTML.p { className = ClassName("pdf-hint"); +"Opening PDF…" }
                }
            }

            // ---- draft ----
            ReactHTML.div {
                className = ClassName("pdf-draft")

                ReactHTML.label {
                    className = ClassName("pdf-field")
                    +"Worksheet name"
                    ReactHTML.input {
                        value = name
                        onChange = { name = it.target.value }
                    }
                }

                val working = selections.count { it.status == "working" }
                if (working > 0) {
                    ReactHTML.div { className = ClassName("pdf-working"); +"Converting $working selection${if (working > 1) "s" else ""}…" }
                }
                for (s in selections.filter { it.status.startsWith("failed") }) {
                    ReactHTML.div {
                        key = Key("err-${s.n}")
                        className = ClassName("pdf-error")
                        +"Selection ${s.n} ${s.status}"
                    }
                }

                ReactHTML.div { className = ClassName("pdf-field-label"); +"Preview" }
                ReactHTML.div {
                    className = ClassName("pdf-preview exercise-body")
                    if (draft.isBlank()) {
                        ReactHTML.span { className = ClassName("muted"); +"Nothing selected yet." }
                    } else {
                        val html: dynamic = obj()
                        html.__html = renderMathMarkdown(draft)
                        asDynamic().dangerouslySetInnerHTML = html
                    }
                }

                val errors = mathErrors(draft)
                if (errors.isNotEmpty()) {
                    ReactHTML.div {
                        className = ClassName("pdf-error")
                        +"Formulas to fix in the source:"
                        ReactHTML.ul { for ((i, e) in errors.withIndex()) ReactHTML.li { key = Key("e$i"); +e } }
                    }
                }

                ReactHTML.div { className = ClassName("pdf-field-label"); +"Source (Markdown + LaTeX)" }
                ReactHTML.textarea {
                    className = ClassName("pdf-source mono")
                    value = draft
                    onChange = { setDraft(it.target.value) }
                }

                ReactHTML.button {
                    className = ClassName("btn-primary pdf-create")
                    disabled = draft.isBlank() || creating || working > 0
                    onClick = {
                        creating = true
                        importScope.launch {
                            try {
                                val doc = importApi.createDocument(name.ifBlank { "Imported exercise" }, draft.trim())
                                props.onCreated(doc.id)
                            } catch (e: Throwable) {
                                creating = false
                                loadError = "Could not create the worksheet: ${e.message}"
                            }
                        }
                    }
                    ReactHTML.span { className = ClassName("ph ph-check") }
                    +(if (creating) "Creating…" else "Create worksheet")
                }
            }
        }
    }
}

private fun appendBlock(draft: String, block: String) =
    if (draft.isBlank()) block.trim() else draft.trimEnd() + "\n\n" + block.trim()

private fun titleFromFileName(fileName: String) = fileName
    .substringBeforeLast(".")
    .replace(Regex("[-_]+"), " ")
    .trim()
    .replaceFirstChar { it.uppercase() }
    .ifBlank { "Imported exercise" }

/** Converts a region; if KaTeX cannot render the result, asks once more with the errors. */
private suspend fun convertWithRetry(region: Region): String {
    val first = importApi.convertExerciseRegion(ConvertRegionRequest(region.pngBase64, region.text)).markdown
    val errors = mathErrors(first)
    if (errors.isEmpty()) return first
    val second = runCatching {
        importApi.convertExerciseRegion(
            ConvertRegionRequest(region.pngBase64, region.text, previousMarkdown = first, katexErrors = errors),
        ).markdown
    }.getOrNull() ?: return first
    return if (mathErrors(second).size <= errors.size) second else first
}

private class Region(val pngBase64: String, val text: String)

/** Safari/iPadOS lacks ReadableStream[Symbol.asyncIterator], which pdf.js getTextContent() uses via `for await`. */
private fun ensureReadableStreamAsyncIterator() {
    js(
        """
        if (typeof ReadableStream !== 'undefined' && !ReadableStream.prototype[Symbol.asyncIterator]) {
            ReadableStream.prototype[Symbol.asyncIterator] = function () {
                var reader = this.getReader();
                return {
                    next: function () {
                        return reader.read().then(function (r) {
                            if (r.done) reader.releaseLock();
                            return r;
                        });
                    },
                    return: function () {
                        reader.releaseLock();
                        return Promise.resolve({ done: true, value: undefined });
                    }
                };
            };
        }
        """
    )
}

/** Renders the selected area at high resolution and collects the PDF text inside it. */
private suspend fun cropRegion(pdf: dynamic, rect: PageRect): Region {
    val page = await<dynamic>(pdf.getPage(rect.page))
    val base = page.getViewport(scaleOptions(1.0))
    val baseW = base.width as Double
    val baseH = base.height as Double

    // ---- image ----
    val longest = max(rect.w * baseW, rect.h * baseH)
    val scale = min(3.0, CROP_MAX_SIDE / longest).coerceAtLeast(1.0)
    val viewport = page.getViewport(scaleOptions(scale))
    val full: dynamic = js("document.createElement('canvas')")
    full.width = ceil(viewport.width as Double).toInt()
    full.height = ceil(viewport.height as Double).toInt()
    val params = obj()
    params.canvas = full
    params.canvasContext = full.getContext("2d")
    params.viewport = viewport
    await<dynamic>(page.render(params).promise)

    val sx = (rect.x * (full.width as Int)).roundToInt()
    val sy = (rect.y * (full.height as Int)).roundToInt()
    val cw = max(1, (rect.w * (full.width as Int)).roundToInt())
    val ch = max(1, (rect.h * (full.height as Int)).roundToInt())
    val crop: dynamic = js("document.createElement('canvas')")
    crop.width = cw
    crop.height = ch
    val ctx = crop.getContext("2d")
    ctx.fillStyle = "#ffffff"
    ctx.fillRect(0, 0, cw, ch)
    ctx.drawImage(full, sx, sy, cw, ch, 0, 0, cw, ch)
    val png = (crop.toDataURL("image/png") as String).substringAfter("base64,")

    // ---- text layer ----
    val content = await<dynamic>(page.getTextContent())
    val items = content.items as Array<dynamic>
    val left = rect.x * baseW
    val top = rect.y * baseH
    val right = left + rect.w * baseW
    val bottom = top + rect.h * baseH
    val text = StringBuilder()
    for (item in items) {
        val str = item.str as? String ?: continue
        val t = item.transform
        val p = base.convertToViewportPoint(t[4], t[5]) // baseline start, top-left origin
        val cx = (p[0] as Double) + (item.width as Double) / 2
        val cy = (p[1] as Double) - abs(item.height as Double) / 2
        if (cx in left..right && cy in top..bottom) {
            text.append(str)
            if (item.hasEOL == true) text.append('\n')
        }
    }
    return Region(png, text.toString().trim())
}

// ------------------------------------------------------------------
// One page: canvas + rectangle selection overlay
// ------------------------------------------------------------------

private external interface PdfPageViewProps : Props {
    var pdf: dynamic
    var pageNumber: Int
    /** Display width in CSS px. */
    var width: Double
    var selections: List<Selection>
    var onSelect: (PageRect) -> Unit
}

/** Drag rectangle in CSS px relative to the page box. */
private data class Drag(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

private val PdfPageView = FC<PdfPageViewProps> { props ->
    val canvasRef = useRef<HTMLCanvasElement>(null)
    val cssWidth = props.width
    var cssHeight by useState(cssWidth * 1.414)
    var drag by useState<Drag?>(null)
    var renderError by useState<String?>(null)

    useEffectOnce {
        try {
            val page = await<dynamic>(props.pdf.getPage(props.pageNumber))
            val base = page.getViewport(scaleOptions(1.0))
            val cssScale = cssWidth / (base.width as Double)
            cssHeight = (base.height as Double) * cssScale
            val dpr = (js("window.devicePixelRatio") as? Double) ?: 1.0
            val viewport = page.getViewport(scaleOptions(cssScale * dpr))
            val canvas: dynamic = canvasRef.current ?: return@useEffectOnce
            canvas.width = ceil(viewport.width as Double).toInt()
            canvas.height = ceil(viewport.height as Double).toInt()
            val params = obj()
            params.canvas = canvas
            params.canvasContext = canvas.getContext("2d")
            params.viewport = viewport
            await<dynamic>(page.render(params).promise)
        } catch (e: Throwable) {
            renderError = "Could not render page ${props.pageNumber}: ${e.message}"
        }
    }

    fun local(event: dynamic): Pair<Double, Double> {
        val box = event.currentTarget.getBoundingClientRect()
        val x = ((event.clientX as Double) - (box.left as Double)).coerceIn(0.0, cssWidth)
        val y = ((event.clientY as Double) - (box.top as Double)).coerceIn(0.0, cssHeight)
        return x to y
    }

    ReactHTML.div {
        className = ClassName("pdf-page")
        val css: dynamic = obj()
        css.width = "${cssWidth}px"
        css.height = "${cssHeight}px"
        asDynamic().style = css

        ReactHTML.canvas {
            ref = canvasRef
            val c: dynamic = obj()
            c.width = "${cssWidth}px"
            c.height = "${cssHeight}px"
            asDynamic().style = c
        }
        renderError?.let { ReactHTML.p { className = ClassName("pdf-error"); +it } }

        // selection overlay (mouse, finger and pen)
        ReactHTML.div {
            className = ClassName("pdf-select-layer")
            onPointerDown = { e ->
                val ev: dynamic = e
                ev.currentTarget.setPointerCapture(ev.pointerId)
                val (x, y) = local(ev)
                drag = Drag(x, y, x, y)
            }
            onPointerMove = { e ->
                val d = drag
                if (d != null) {
                    val (x, y) = local(e.asDynamic())
                    drag = d.copy(x1 = x, y1 = y)
                }
            }
            onPointerUp = {
                val d = drag
                drag = null
                if (d != null && abs(d.x1 - d.x0) > 8 && abs(d.y1 - d.y0) > 8) {
                    props.onSelect(
                        PageRect(
                            page = props.pageNumber,
                            x = min(d.x0, d.x1) / cssWidth,
                            y = min(d.y0, d.y1) / cssHeight,
                            w = abs(d.x1 - d.x0) / cssWidth,
                            h = abs(d.y1 - d.y0) / cssHeight,
                        ),
                    )
                }
            }

            // past selections, numbered
            for (s in props.selections) {
                ReactHTML.div {
                    key = Key("sel-${s.n}")
                    className = ClassName("pdf-sel pdf-sel-${s.mode.name.lowercase()} pdf-sel-${s.status.substringBefore(':')}")
                    val r: dynamic = obj()
                    r.left = "${s.rect.x * cssWidth}px"
                    r.top = "${s.rect.y * cssHeight}px"
                    r.width = "${s.rect.w * cssWidth}px"
                    r.height = "${s.rect.h * cssHeight}px"
                    asDynamic().style = r
                    ReactHTML.span { className = ClassName("pdf-sel-n"); +s.n.toString() }
                }
            }

            drag?.let { d ->
                ReactHTML.div {
                    className = ClassName("pdf-sel pdf-sel-drag")
                    val r: dynamic = obj()
                    r.left = "${min(d.x0, d.x1)}px"
                    r.top = "${min(d.y0, d.y1)}px"
                    r.width = "${abs(d.x1 - d.x0)}px"
                    r.height = "${abs(d.y1 - d.y0)}px"
                    asDynamic().style = r
                }
            }
        }
    }
}
