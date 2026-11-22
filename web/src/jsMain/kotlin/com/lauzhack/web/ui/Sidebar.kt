package com.lauzhack.web.ui

import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useState
import web.cssom.ClassName

external interface SidebarProps : Props {
    /** "all" or a subject name */
    var active: String
    var onSelect: ((subject: String) -> Unit)?
}

private data class MockSubject(val name: String, val children: List<String>)

/** Mock subject tree for the redesign; will be driven by real data later. */
private val mockSubjects = listOf(
    MockSubject("Algebra", listOf("Quadratic equations", "Polynomial division", "Inequalities")),
    MockSubject("Geometry", listOf("Circle theorems", "Triangle areas")),
    MockSubject("Calculus", listOf("Limits & continuity", "Derivatives")),
    MockSubject("Physics", listOf("Kinematics", "Momentum")),
)

val Sidebar = FC<SidebarProps> { props ->
    var expanded by useState<Set<String>>(emptySet())

    ReactHTML.nav {
        className = ClassName("subjects")

        ReactHTML.div {
            className = ClassName("subject-label")
            +"Subjects"
        }

        ReactHTML.button {
            className = ClassName(if (props.active == "all") "subject active" else "subject")
            onClick = { _ -> props.onSelect?.invoke("all") }
            ReactHTML.span { className = ClassName("ph ph-stack") }
            +"All"
        }

        for (subject in mockSubjects) {
            val isOpen = subject.name in expanded

            ReactHTML.button {
                className = ClassName(if (props.active == subject.name) "subject active" else "subject")
                key = Key(subject.name)
                onClick = { _ -> props.onSelect?.invoke(subject.name) }

                ReactHTML.span {
                    className = ClassName(if (isOpen) "ph ph-caret-right caret open" else "ph ph-caret-right caret")
                    onClick = { event ->
                        event.stopPropagation()
                        expanded = if (isOpen) expanded - subject.name else expanded + subject.name
                    }
                }

                +subject.name

                ReactHTML.span {
                    className = ClassName("count")
                    +subject.children.size.toString()
                }
            }

            if (isOpen) {
                ReactHTML.div {
                    className = ClassName("subject-children")
                    key = Key(subject.name + ":children")
                    for (child in subject.children) {
                        ReactHTML.button {
                            className = ClassName("subject-child")
                            key = Key(child)
                            title = child
                            onClick = { _ -> props.onSelect?.invoke(subject.name) }
                            +child
                        }
                    }
                }
            }
        }
    }
}
