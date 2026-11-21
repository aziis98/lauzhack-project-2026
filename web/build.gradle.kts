plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()

        // ES2015 modules (the default ES5 output uses UMD, which forbids bare
        // @JsModule externals like our tldraw declarations)
        compilerOptions {
            target = "es2015"
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation("org.jetbrains.kotlin-wrappers:kotlin-react:2026.11.2-19.3.1")
            implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom:2026.11.2-19.3.1")
            implementation(npm("react", "19.3.1"))
            implementation(npm("react-dom", "19.3.1"))
            implementation(npm("tldraw", "5.6.2"))
            // exercise statements: Markdown + LaTeX math
            implementation(npm("marked", "15.0.12"))
            implementation(npm("katex", "0.16.22"))
            // exercise import: render PDFs and read their text layer in the browser
            implementation(npm("pdfjs-dist", "6.4.299"))
            // typography + icon fonts
            implementation(npm("@fontsource/open-sans", "5.3.0"))
            implementation(npm("@fontsource/jetbrains-mono", "5.3.0"))
            implementation(npm("@phosphor-icons/web", "2.1.2"))
            // loaders used by the webpack css pipeline
            implementation(devNpm("css-loader", "7.1.5"))
            implementation(devNpm("style-loader", "4.0.0"))
        }
    }
}
