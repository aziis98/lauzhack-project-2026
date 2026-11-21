@file:JsModule("pdfjs-dist")

package com.lauzhack.web.pdf

/** pdf.js entry points we use; documents, pages and viewports are read as `dynamic`. */
external val GlobalWorkerOptions: dynamic

external fun getDocument(src: dynamic): dynamic
