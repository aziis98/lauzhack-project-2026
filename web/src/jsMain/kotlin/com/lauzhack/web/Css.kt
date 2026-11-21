@file:JsModule("tldraw/tldraw.css")

package com.lauzhack.web

/**
 * Side-effect import: makes webpack bundle tldraw's stylesheet.
 * Named `default` so the compiler emits `import { default } from 'tldraw/tldraw.css'`,
 * matching the css module's only export.
 */
external val default: dynamic
