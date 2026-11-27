package app.styli.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Throwaway: a few clips for trying the clipboard grid by hand (a star, a tick, a wavy underline, a labelled box and a small table).
 * Added once, the first time the app runs with an empty shelf. Remove with the `clipSamples` setting when the grid is tested.
 */
fun sampleClips(): List<Clip> {
    val random = java.security.SecureRandom()
    fun id() = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) } // the format wants 16 random bytes as hex
    fun stroke(vararg pts: Pair<Float, Float>, color: Int = 0xFF000000.toInt(), width: Int = 40) =
        Item(id(), app.styli.ink.proto.Tool.TOOL_PEN, color, width, pts.mapIndexed { i, (x, y) -> Pt(x, y, i * 8L, 1f) })
    /** A smooth line through a formula, dense like real pen input. */
    fun curve(count: Int, color: Int = 0xFF000000.toInt(), at: (Float) -> Pair<Float, Float>) =
        Item(id(), app.styli.ink.proto.Tool.TOOL_PEN, color, 40, (0..count).map { i -> at(i / count.toFloat()).let { (x, y) -> Pt(x, y, i * 8L, 1f) } })
    fun clip(name: String, w: Float, h: Float, items: List<Item> = emptyList(), texts: List<TextBox> = emptyList(), tables: List<Table> = emptyList(), shapes: List<Shape> = emptyList()) =
        Clip(name, w, h, items, texts, tables, shapes, emptyList(), emptyList(), (items.map { it.id } + texts.map { it.id } + tables.map { it.id } + shapes.map { it.id }))

    val star = curve(100, 0xFFC0392B.toInt()) { t ->
        val k = (t * 5).toInt().coerceAtMost(4); val f = t * 5 - k
        fun pt(i: Int) = (i * 2 % 5).let { j -> (1000f + 900f * cos(-PI / 2 + j * 2 * PI / 5).toFloat()) to (1000f + 900f * sin(-PI / 2 + j * 2 * PI / 5).toFloat()) }
        val a = pt(k); val b = pt(k + 1)
        (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
    }
    val tick = curve(60, 0xFF2E7D32.toInt()) { t -> if (t < 0.3f) (200f + t / 0.3f * 300f) to (700f + t / 0.3f * 400f) else (500f + (t - 0.3f) / 0.7f * 1100f) to (1100f - (t - 0.3f) / 0.7f * 1100f) }
    val wave = curve(120, 0xFF1B3A8A.toInt()) { t -> (t * 2400f) to (200f + 120f * sin(t * 6 * PI).toFloat()) }
    val boxed = clip(
        "Key idea", 2400f, 900f,
        items = listOf(stroke(0f to 0f, 2400f to 0f, 2400f to 900f, 0f to 900f, 0f to 0f, color = 0xFFD9772B.toInt(), width = 60)),
        texts = listOf(TextBox(id(), 150f, 250f, 2100f, 330f, "Remember this", 0xFF000000.toInt())),
    )
    val table = Table(id(), 0f, 0f, listOf(800f, 800f, 800f), listOf(400f, 400f, 400f), 40f, cellText = listOf("x", "f(x)", "f'(x)", "1", "2", "2", "2", "4", "4"), headerRow = true)
    return listOf(
        clip("Star", 2000f, 2000f, listOf(star)),
        clip("Tick", 1600f, 1100f, listOf(tick)),
        clip("Wavy underline", 2400f, 400f, listOf(wave)),
        boxed,
        clip("Small table", 2400f, 1200f, tables = listOf(table)),
        clip("Circle", 1600f, 1600f, shapes = listOf(ShapePreset.CIRCLE.make(id(), 0f, 0f, 1600f, 1600f, color = 0xFF1B3A8A.toInt()))),
        clip("Arrow", 2000f, 900f, shapes = listOf(ShapePreset.ARROW.make(id(), 0f, 0f, 2000f, 900f, color = 0xFFC0392B.toInt()))),
    )
}
