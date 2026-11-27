package app.styli.ink

import app.styli.ink.proto.Template

/**
 * Throwaway: fills a new library with made-up folders and notes so the Tabularium can be tried by hand.
 * Nothing is saved to disk yet, so this runs at every start. Remove it when saving exists.
 */
fun Library.addSamples() {
    val lined = NewFileLook(template = Template.TEMPLATE_LINED)
    val grid = NewFileLook(template = Template.TEMPLATE_GRID)
    val blank = NewFileLook(template = Template.TEMPLATE_BLANK)
    val physics = newFolder("Physics", 0xFF4A78C2.toInt())
    val labs = newFolder("Labs", 0xFF3A9AA0.toInt(), physics)
    val maths = newFolder("Maths", 0xFFC8902E.toInt())
    val ideas = newFolder("Ideas", 0xFF8A64B8.toInt())
    newFolder("Recipes", 0xFFD9772B.toInt())
    newFolder("Archive")
    newFile(physics, "Lecture 1", lined); newFile(physics, "Lecture 2", lined)
    val formulas = newFile(physics, "Formulas", grid)
    newFile(labs, "Pendulum lab", grid); newFile(labs, "Circuits lab", grid)
    newFile(maths, "Calculus notes", lined); newFile(maths, "Proofs", blank)
    newFile(ideas, "App ideas", blank)
    newFile(root, "Shopping list", lined)
    newFile(root, "Sketchbook", NewFileLook(infinite = true, template = Template.TEMPLATE_BLANK))
    newFile(root, "Reading list", lined)
    // Two files already in the trash, to try the Trash tab with.
    delete(physics, newFile(physics, "Scrap notes", lined)); delete(root.folders.first { it.name == "Archive" }, newFile(root.folders.first { it.name == "Archive" }, "Old draft", blank))
    delete(ideas, newFile(ideas, "Rejected idea", blank)); delete(maths, newFile(maths, "Practice sheet", grid))
    setPinned(formulas, true); setPinned(maths, true)
    // A few notes get some handwriting, so their covers show a real page.
    for ((i, f) in listOf(formulas, labs.files[0], maths.files[0], physics.files[0], root.files[0]).withIndex()) scribble(f, i)
    open(formulas)
}

/** Throwaway: a few wavy pen lines on a file's first page, standing in for handwriting. */
private fun scribble(file: NoteFile, seed: Int) {
    val page = file.notebook!!.pages.first()
    val w = page.page.width.toFloat()
    for (line in 0 until 5 + seed % 3) {
        val y0 = 3500f + line * 2800f
        val n = 400 // as dense as a real pen's points (a point every half millimetre or so)
        val pts = (0..n).map { k ->
            val x = 2200f + k * (w - 6000f) / n * (if (line % 3 == 2) 0.6f else 1f)
            Pt(x, y0 + 260f * kotlin.math.sin(k * 0.07f + seed + line), k * 4L, 0.8f)
        }
        page.add(app.styli.ink.proto.Tool.TOOL_PEN, if (line == 0) 0xFF1B3A8A.toInt() else 0xFF222222.toInt(), 40, pts)
    }
}
