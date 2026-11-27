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
    setPinned(formulas, true); setPinned(maths, true)
    open(formulas)
}
