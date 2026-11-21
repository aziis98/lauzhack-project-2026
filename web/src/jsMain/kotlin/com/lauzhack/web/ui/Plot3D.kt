package com.lauzhack.web.ui

import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.PlotInterval
import com.lauzhack.web.three.AmbientLight
import com.lauzhack.web.three.BufferAttribute
import com.lauzhack.web.three.BufferGeometry
import com.lauzhack.web.three.DirectionalLight
import com.lauzhack.web.three.GridHelper
import com.lauzhack.web.three.Mesh
import com.lauzhack.web.three.MeshBasicMaterial
import com.lauzhack.web.three.MeshLambertMaterial
import com.lauzhack.web.three.OrbitControls
import com.lauzhack.web.three.PerspectiveCamera
import com.lauzhack.web.three.Scene
import com.lauzhack.web.three.SphereGeometry
import com.lauzhack.web.three.Vector3
import com.lauzhack.web.three.WebGLRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import react.FC
import react.Props
import react.dom.html.ReactHTML
import react.useRef
import react.useEffectOnce
import web.cssom.ClassName

/*
 * The 3D half of the plot renderer: two-variable functions become an orbitable
 * three.js surface inside the same neobrutalist panel. Kotlin samples the JS
 * expression over the (x, y) grid and builds the geometry; three.js draws it;
 * OrbitControls (with damping) gives rotate and zoom. The surface uses the
 * app palette: pastel mint fill with an ink wireframe — squared paper in 3D.
 */

private const val SURFACE_SAMPLES = 72

internal const val PLOT3D_CANVAS_WIDTH = 432.0
internal const val PLOT3D_CANVAS_HEIGHT = 336.0

private const val COLOR_CREAM = 0xfff8ec
private const val COLOR_MINT = 0xbfe3cf
private const val COLOR_INK = 0x1a1a1a
private const val COLOR_RED = 0xc62828

internal external interface Plot3DProps : Props {
    var plot: AgentAction.PlotAction
}

internal val Plot3D = FC<Plot3DProps> { props ->
    val container = useRef<web.html.HTMLDivElement>()

    useEffectOnce {
        val host = container.current?.unsafeCast<HTMLElement>() ?: return@useEffectOnce
        val plot = props.plot
        val varX = plot.variable
        val varY = plot.variableY ?: "y"
        val f = compileExpression2(plot.expression, varX, varY)

        val renderer = WebGLRenderer(js("({ antialias: true })"))
        renderer.setSize(PLOT3D_CANVAS_WIDTH, PLOT3D_CANVAS_HEIGHT)
        renderer.setPixelRatio(js("window.devicePixelRatio || 1"))
        renderer.domElement.className = "plot-3d-canvas"
        host.appendChild(renderer.domElement)

        val scene = Scene()
        scene.background = com.lauzhack.web.three.Color(COLOR_CREAM)
        scene.add(AmbientLight(0xffffff, 0.75))
        val sun = DirectionalLight(0xffffff, 1.05)
        sun.position.set(3.0, -4.0, 6.0)
        scene.add(sun)

        val camera = PerspectiveCamera(40.0, PLOT3D_CANVAS_WIDTH / PLOT3D_CANVAS_HEIGHT, 0.1, 1000.0)
        val controls = OrbitControls(camera, renderer.domElement)
        controls.enableDamping = true
        controls.dampingFactor = 0.08
        controls.target.set(0.0, 0.0, 0.0)

        val disposables = ArrayList<dynamic>()

        try {
            if (f != null) {
                val ix = plot.domain.firstOrNull { it.to > it.from } ?: PlotInterval(-2.0, 2.0)
                val iy = plot.domainY?.takeIf { it.to > it.from } ?: PlotInterval(ix.from, ix.to)
                val surface = buildSurfaceGeometry(f, ix, iy)
                val geometry = surface.geometry
                val span = maxOf(ix.to - ix.from, iy.to - iy.from)

                val fill = MeshLambertMaterial(js("({ color: 0xbfe3cf })"))
                scene.add(Mesh(geometry, fill))
                val wire = MeshBasicMaterial(js("({ color: 0x1a1a1a, wireframe: true, transparent: true, opacity: 0.28 })"))
                scene.add(Mesh(geometry, wire))
                disposables.add(geometry); disposables.add(fill); disposables.add(wire)

                // ground grid at the bottom of the surface, paper-style
                val grid = GridHelper(span, 10, 0x9c968c, 0xd8d2c6)
                grid.position.set(0.0, 0.0, -surface.height / 2)
                scene.add(grid)
                disposables.add(grid)

                // the points the STUDENT computed
                val sphereGeo = SphereGeometry(span * 0.02, 16, 16)
                val redMaterial = MeshBasicMaterial(js("({ color: 0xc62828 })"))
                disposables.add(sphereGeo); disposables.add(redMaterial)
                for (p in plot.points) {
                    val py = p.y ?: continue
                    if (p.x < ix.from || p.x > ix.to || py < iy.from || py > iy.to) continue
                    val z = p.z ?: f(p.x, py) ?: continue
                    if (z < surface.zMin || z > surface.zMax) continue
                    val sx = p.x - (ix.from + ix.to) / 2
                    val sy = py - (iy.from + iy.to) / 2
                    val sz = z.coerceIn(surface.zMin, surface.zMax) - surface.zCenter
                    val dot = Mesh(sphereGeo, redMaterial)
                    dot.position.set(sx, sy, sz)
                    scene.add(dot)
                }

                val distance = span * 1.7
                camera.position.set(span * 0.7, -span * 0.95, distance)
                camera.lookAt(0.0, 0.0, 0.0)
                controls.minDistance = span * 0.35
                controls.maxDistance = span * 5.0
            }

            // render loop: runs while the panel is open, stops (and disposes) on unmount
            while (currentCoroutineContext().isActive) {
                controls.update()
                renderer.render(scene, camera)
                delay(16)
            }
        } finally {
            runCatching { controls.dispose() }
            runCatching { renderer.dispose() }
            runCatching { host.removeChild(renderer.domElement) }
        }
    }

    ReactHTML.div {
        className = ClassName("plot-3d")
        ref = container
        val css: dynamic = js("({})")
        css.width = "${PLOT3D_CANVAS_WIDTH}px"
        css.height = "${PLOT3D_CANVAS_HEIGHT}px"
        asDynamic().style = css
    }
}

// ---------------------------------------------------------------
// Surface sampling (pure Kotlin builds the three.js buffers)
// ---------------------------------------------------------------

internal class SurfaceData(
    val geometry: BufferGeometry,
    val zMin: Double,
    val zMax: Double,
    val zCenter: Double,
    val height: Double,
)

private fun jsNewFloat32Array(length: Int): dynamic = js("new Float32Array(length)")

/** Samples the function over the grid, centring the result at the origin. */
internal fun buildSurfaceGeometry(
    f: (Double, Double) -> Double?,
    ix: PlotInterval,
    iy: PlotInterval,
    samples: Int = SURFACE_SAMPLES,
): SurfaceData {
    val nx = samples
    val ny = samples
    val cx = (ix.from + ix.to) / 2
    val cy = (iy.from + iy.to) / 2

    // sample z, remember which vertices are finite, clamp outliers like the 2D plot
    val zs = Array((nx + 1) * (ny + 1)) { 0.0 }
    val finite = BooleanArray((nx + 1) * (ny + 1))
    val collected = ArrayList<Double>((nx + 1) * (ny + 1))
    for (j in 0..ny) {
        val y = iy.from + (iy.to - iy.from) * j / ny
        for (i in 0..nx) {
            val x = ix.from + (ix.to - ix.from) * i / nx
            val z = f(x, y)
            val k = j * (nx + 1) + i
            if (z != null && z.isFinite()) {
                zs[k] = z
                finite[k] = true
                collected.add(z)
            }
        }
    }
    var zMin = -1.0
    var zMax = 1.0
    if (collected.isNotEmpty()) {
        val sorted = collected.sorted()
        zMin = sorted[(sorted.size * 0.01).toInt().coerceIn(sorted.indices)]
        zMax = sorted[((sorted.size * 0.99).toInt()).coerceIn(sorted.indices)]
        if (zMax - zMin < 1e-9) { zMin -= 1.0; zMax += 1.0 }
        val pad = (zMax - zMin) * 0.06
        zMin -= pad
        zMax += pad
    }

    val pos = jsNewFloat32Array((nx + 1) * (ny + 1) * 3)
    for (j in 0..ny) {
        val y = iy.from + (iy.to - iy.from) * j / ny - cy
        for (i in 0..nx) {
            val x = ix.from + (ix.to - ix.from) * i / nx - cx
            val k = j * (nx + 1) + i
            val z = (if (finite[k]) zs[k] else 0.0).coerceIn(zMin, zMax)
            pos[3 * k] = x
            pos[3 * k + 1] = y
            pos[3 * k + 2] = z - (zMin + zMax) / 2
        }
    }

    val indices = ArrayList<Int>()
    for (j in 0 until ny) {
        for (i in 0 until nx) {
            val a = j * (nx + 1) + i
            val b = a + 1
            val c = a + (nx + 1)
            val d = c + 1
            if (finite[a] && finite[b] && finite[c] && finite[d]) {
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }
    }

    val geometry = BufferGeometry()
    geometry.setAttribute("position", BufferAttribute(pos, 3))
    val jsIndices: Array<Int> = indices.toTypedArray()
    geometry.setIndex(jsIndices)
    geometry.computeVertexNormals()

    return SurfaceData(geometry, zMin, zMax, (zMin + zMax) / 2, zMax - zMin)
}

// ---------------------------------------------------------------
// Evaluation of a two-variable JS expression
// ---------------------------------------------------------------

/** Compiles the JS expression to a function of two variables; null when unsafe. */
internal fun compileExpression2(expression: String, variableX: String, variableY: String): ((Double, Double) -> Double?)? {
    if (!expressionIsSafe(expression, variableX, variableY)) return null
    val f: dynamic = makeJsFunction2(variableX, variableY, expression)
    return { x, y -> (f(x, y) as? Number)?.toDouble() }
}

private fun makeJsFunction2(vx: String, vy: String, expr: String): dynamic =
    js("new Function(vx, vy, 'return (' + expr + ');')")
