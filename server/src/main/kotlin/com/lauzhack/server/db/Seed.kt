package com.lauzhack.server.db

/** Example exercises created on startup when missing (fixed ids, so it runs once per database). */
private data class ExampleExercise(val id: String, val name: String, val resource: String)

private val examples = listOf(
    ExampleExercise("doc:example-semicircle-rectangle", "Example · Rectangle in a semicircle", "exercises/semicircle-rectangle.md"),
    ExampleExercise("doc:example-function-study", "Example · Study of x³ − 3x", "exercises/function-study.md"),
)

fun seedExampleExercises() {
    for (ex in examples) {
        if (getDocument(ex.id) != null) continue
        val text = object {}.javaClass.classLoader.getResource(ex.resource)?.readText() ?: continue
        createDocument(ex.name, text.trim(), ex.id)
    }
}
