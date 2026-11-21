// pdf.js worker: emit it as a separate file; importing it yields its URL
// (used for GlobalWorkerOptions.workerSrc in PdfImport.kt).
config.module.rules.push({
	test: /pdf\.worker(\.min)?\.mjs$/,
	type: "asset/resource",
});
