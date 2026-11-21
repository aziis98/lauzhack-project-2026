@file:JsModule("pdfjs-dist/build/pdf.worker.min.mjs")

package com.lauzhack.web.pdf

/**
 * URL of pdf.js' worker script. webpack emits the file as an asset (see
 * webpack.config.d/pdfjs.js), so importing it yields its URL.
 */
@JsName("default")
external val pdfWorkerUrl: String
