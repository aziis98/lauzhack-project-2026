# Hintsight: technical details

Everything you need to build, run, configure and extend Hintsight. For the big
picture (components and data flow) read [ARCHITECTURE.md](ARCHITECTURE.md) first.

## Contents

- [Stack](#stack)
- [Repository layout](#repository-layout)
- [Running](#running)
- [Configuration](#configuration)
- [AI models](#ai-models)
- [The pipeline in detail](#the-pipeline-in-detail)
- [Hints](#hints)
- [Plots](#plots)
- [Error marks](#error-marks)
- [Exercise import from PDF](#exercise-import-from-pdf)
- [Data model](#data-model)
- [Storage](#storage)
- [REST API](#rest-api)
- [Debug mode](#debug-mode)
- [Tests](#tests)
- [Known limitations](#known-limitations)

---

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin everywhere (JVM, Multiplatform, Kotlin/JS) |
| Server | Ktor 3, Exposed + SQLite, kotlinx.serialization |
| Shared logic | Kotlin Multiplatform module: domain model, DTOs, REST client, chunking |
| Web client | Kotlin/JS + React (kotlin-wrappers), [tldraw](https://tldraw.dev) 5 canvas |
| Math rendering | KaTeX + marked (Markdown), SVG plots, three.js for 3D surfaces |
| PDF | pdf.js (in the browser) |
| AI | [Koog](https://github.com/JetBrains/koog) 1.3 → Together AI (OpenAI-compatible API), DeepSeek V4.1 Flash models |
| Image processing | Java2D (stroke rasterization), BoofCV (symbol segmentation) |
| Fonts and icons | Open Sans, JetBrains Mono, Phosphor icons |
| Build | Gradle (wrapper included), JVM toolchain 21 |

## Repository layout

```
├── shared/     Kotlin Multiplatform: model, DTOs, DrawingApi client, chunking, agent schema
├── server/     Ktor application: REST API, SQLite, OCR, page agent, hints, PDF import
├── web/        Kotlin/JS + React + tldraw: the only module that knows tldraw
├── branding/   Logo and wordmark (SVG + PNG)
└── docs/       Hero image, demo problem, hint notes
```

Main packages:

| Path | Contents |
|---|---|
| `shared/.../domain` | `Document`, `Element`, `Style`, `ElementData` |
| `shared/.../chunking` | `StrokeClusterer`, `ChunkTracker`, stroke geometry |
| `shared/.../ocr` | `ChunkKind`, `ChunkTranscription`, `SymbolFragment` |
| `shared/.../agent` | Agent messages (`PageSnapshot`, `PageDelta`, `HelpRequest`) and actions |
| `shared/.../hint` | `Hint`, `HintCard`, `HintLevel`, `HintStatus` |
| `shared/.../api` | `DrawingApi`, the Ktor client used by the web app |
| `server/.../chunking` | `ChunkService`: re-chunks a page after every write |
| `server/.../ocr` | `ChunkRasterizer`, `SymbolAnnotator`, `ChunkRecognizer`, `OcrService` |
| `server/.../agent` | `PageAgent` (prompt + model call), `AgentService` (conversation loop) |
| `server/.../hint` | `HintService`: hint card lifecycle |
| `server/.../exercise` | `RegionConverter`, `ExerciseImportService`: PDF region → Markdown |
| `server/.../db` | Tables, DAOs, migrations, seed data, assets |
| `server/.../routes` | `ApiRoutes`, `AgentRoutes`, `ExerciseRoutes` |
| `web/.../ui` | App shell, canvas, toolbar, hint/plot/error/exercise layers, PDF import, debug drawer |
| `web/.../tldraw` | tldraw externals and the tldraw ↔ `Element` mapper |
| `web/.../markdown`, `web/.../pdf`, `web/.../three` | KaTeX/marked, pdf.js, three.js bindings |

## Running

Prerequisites: a JDK 21 (Gradle provisions it through the toolchain if missing)
and, for the AI features, a Together AI API key.

**Single process (what we use for demos).** The server serves the built web bundle on port 8080:

```bash
export TOGETHER_API_KEY=...
./gradlew :web:jsBrowserDistribution
./gradlew :server:run
```

Open <http://localhost:8080>. Rebuild the web bundle after changing web or shared code.

**Development with hot reload:**

```bash
./gradlew :server:run                                 # API on :8080
./gradlew :web:jsBrowserDevelopmentRun --continuous   # web on :8081, proxies /api to :8080
```

Open <http://localhost:8081>.

The SQLite database is `data.db` in the server's working directory. Example
worksheets are seeded at startup when missing.

The build is memory-hungry (Gradle daemon, Kotlin daemon and webpack together).
On machines with 8 GB of RAM, close other heavy apps or lower `org.gradle.jvmargs`
in `gradle.properties` if builds get killed.

## Configuration

`server/src/main/resources/application.yaml`, every value overridable by an environment variable:

| Variable | Default | Meaning |
|---|---|---|
| `TOGETHER_API_KEY` | none | Together AI key. Without it the app runs with no OCR, hints, plots or error marks. |
| `OCR_MODEL` | `deepseek-ai/DeepSeek-V4.1-Flash-1017` | Vision model for handwriting OCR and PDF import |
| `AGENT_MODEL` | `deepseek-ai/DeepSeek-V4.1-Flash` | Model of the page agent (hints, plots, errors) |
| `OCR_REASONING_ENABLED` | `false` | Let the OCR model think (slower, rarely useful) |
| `AGENT_REASONING_EFFORT` | `high` | Reasoning effort of the page agent |

Never commit API keys; pass them through the environment.

## AI models

All model calls go through one Koog `MultiLLMPromptExecutor`, whose OpenAI client
is pointed at `https://api.together.xyz` (`server/.../ai/AiModels.kt`).

- **Structured output.** Models are declared without the JSON-schema capability,
  so Koog's `executeStructured` puts the schema and a few examples in the prompt
  and parses the JSON answer. This works with any Together model.
- **Thinking control.** DeepSeek V4.1 Flash thinks by default. For OCR we send the
  Together-specific `reasoning: {"enabled": false}`. `reasoning_effort` is not used
  for OCR because DeepSeek V4 only accepts `high`/`max` and Together maps lower
  values to `high`. The page agent keeps reasoning on at effort `high`.
- **Prompts** are single constants, also shown in debug mode:
  `OCR_SYSTEM_PROMPT` (`ocr/ChunkRecognizer.kt`) and `PAGE_AGENT_SYSTEM_PROMPT`
  (`agent/PageAgent.kt`).

## The pipeline in detail

### 1. Autosave

The web client converts the tldraw page to shared `Element`s and saves them with
`PUT /api/documents/{id}/elements` about every 2.5 s, only when something
changed. tldraw details never leave `web/.../tldraw/Mapper.kt`.

### 2. Chunking (`shared/.../chunking`, `server/.../chunking/ChunkService.kt`)

After every write the page's strokes are grouped into **chunks** (macro-blocks of
handwriting) by spatial proximity only:

- Union-find over strokes whose bounding boxes are closer than a fixed gap, in
  page units, so the result does not depend on zoom.
- `ChunkTracker` keeps chunk **ids stable** across edits by matching each new
  chunk to the old one it shares the most strokes with.
- Each chunk has a `contentHash` (FNV-1a over its strokes). Only chunks whose
  hash changed are reported as `modified`.
- `ChunkService.events` emits `{added, modified, removed}` plus the strokes of
  the changed chunks.

The state is in memory. After a restart, the first write reports all chunks as added.

### 3. OCR (`server/.../ocr`)

For every added or modified chunk, `OcrService`:

1. waits a **1 s debounce**; a newer version of the chunk cancels the pending job;
2. **rasterizes** the strokes to a PNG, black on white, cropped to the chunk (`ChunkRasterizer`);
3. **segments** the image into symbol fragments with BoofCV (`SymbolAnnotator`):
   ink mask, dilation, connected components, tight boxes, noise filter, sorted left to right;
4. shades a light-gray patch behind each fragment and sends the image and the
   fragment coordinate list to the vision model;
5. gets back `{kind, content}`:
   - `kind` is one of `MATH`, `TEXT`, `HINT_REQUEST` (a circled "?"), `QUESTION_MARK`, `DRAWING`, `UNREADABLE`;
   - `content` is LaTeX/Markdown where every symbol is wrapped in `\bbox[minx,miny,maxx,maxy]{…}`,
     with coordinates copied from the fragment list. This lets the agent point at exact symbols later.

Results are cached by content hash, so the same content is never transcribed
twice. A result for an outdated chunk version is cached but not published. At
most 4 OCR calls run in parallel.

### 4. Page agent (`server/.../agent`)

`AgentService` keeps **one conversation per document** with the agent model:

- The first user message is a `PageSnapshot`: all chunks with position, kind and
  transcription, the exercise text, pending help requests and the hint level.
- Every later message is a `PageDelta` with only the chunks added, modified or removed.
- The model's previous answers stay in the conversation, so it remembers what it
  already said and drew.
- A short **rules recap** is appended before each delta, because models drift on
  long conversations.
- Changes are buffered until the page **settles** (all OCR done, at most 6 s),
  then sent as one message. A failed call keeps the buffer for the next attempt.
- The conversation is stored in the `agent_state` table and trimmed when too long.

The model always answers with `AgentOutput { actions: [...] }`; an empty list
means nothing to do. The actions are:

| Action | Meaning |
|---|---|
| `hint` | A hint card at three detail levels (`minimal`, `medium`, `guided`). It answers a circled "?" (`triggerChunkId`) or an explicit request (`requestId`). |
| `plot` | A function to plot: a JavaScript expression, domain, the domain the student wrote, the points the student computed. `variableY` makes it a 3D surface. `remove: true` withdraws it. |
| `error` | A clear mistake: chunk, message, suggestion, and the `\bbox` of the wrong symbol. `remove: true` withdraws it. |

## Hints

**Triggers:**

| How | Request kind |
|---|---|
| Circled "?" drawn on the page | OCR kind `HINT_REQUEST`; the agent sees it in the next delta |
| **?** button in the toolbar | `HelpRequest(kind = "button")`, anchored to the lowest block of work |
| "Another hint" on a card | `HelpRequest(kind = "more")` with the previous texts; the agent goes one step further |
| Tap a word or formula of the exercise statement | `HelpRequest(kind = "define")` with the term and its sentence |

**How a request is answered.** For explicit requests `HintService` first reserves
a card in state `THINKING`, so the user sees it immediately. The request then
rides along with the next agent message, and the model must answer with a
`hint` action carrying the same `requestId`. A request left unanswered, or a
failed call, turns the card into `FAILED`.

**Hint levels.** The level is chosen with a slider and stored per document
(`documents.hint_level`):

| Level | Hint | Definition |
|---|---|---|
| `MINIMAL` | Only what to use, at most 3-word title | One short sentence |
| `MEDIUM` | The general rule or formula and where it applies, not yet applied | 2–3 sentences |
| `GUIDED` | The rule applied to the student's expression, plus the next 2–3 steps | Medium + one worked example |

The agent always writes all three levels, so moving the slider changes the card
without a new model call. The final result is never given.

**Card behaviour.** A circled "?" is answered once per content. Erasing it
removes its card. Cards can be dragged (`PATCH …/position`) and closed
(`DELETE`). Hint cards live in server memory and are lost on restart.

## Plots

`PlotLayer` shows a button next to the chunk that defines the function. It opens
a draggable panel where the client samples the expression over the domain and
draws an SVG curve, with the student's domain and the student's points marked.
Two-variable functions open a three.js surface (`Plot3D`). The model never
draws: it only supplies the expression and the points, and the client computes.

## Error marks

The agent gives the wrong symbol's bbox in chunk-image pixels. `AgentService`
maps it back to page coordinates using the rasterizer's scale and padding, and
`ErrorLayer` draws a red box with a popup (message + suggestion). Without a bbox
the whole chunk is marked.

## Exercise import from PDF

1. **Upload PDF** on the home page. The PDF is rendered in the browser with
   pdf.js and is never uploaded.
2. The user draws rectangles on the pages, marking each as **text** or **figure**.
3. **Text regions**: the region's PNG and the text pdf.js finds inside it go to
   `POST /api/exercises/convert`. The vision model returns Markdown with `$…$` /
   `$$…$$` LaTeX and the boxes of any figures. Figures are cropped on the server,
   stored as assets and embedded as images. If KaTeX cannot render a formula,
   the client retries once, sending the KaTeX errors.
4. **Figure regions** are stored as images as they are (`POST /api/assets`).
5. The results go into an editable draft (Markdown source + live preview), in
   selection order. **Create worksheet** saves it as the document's `exercise`.
   The cropped region image itself is not kept.

## Data model

Defined in `shared/.../domain/Model.kt`, serialized as JSON.

```kotlin
Document(id, name, updatedAt, exercise /* Markdown + LaTeX */, hintLevel)

Element(id, type, x, y, rotation, zIndex, parentId?, style?, data)
Style(color?, fill?, dash?, size?)
ElementData (discriminator "kind"):
  stroke   → Stroke(segments: [{points: [x1,y1,x2,y2,…], type}], complete)
  geometry → Geometry(w, h)
  text     → Text(text, w, h)
  arrow    → Arrow(bend, start, end)
```

Element `type` values include `stroke`, `rectangle`, `ellipse`, `triangle`,
`diamond`, `cloud`, `text`, `note`, `arrow`. New kinds can be added without a
schema migration, because `style` and `data` are stored as JSON.

Example element:

```json
{
  "id": "shape:8f9c…", "type": "stroke", "x": 120, "y": 40, "rotation": 0, "zIndex": "a1",
  "style": { "color": "black", "size": "m" },
  "data": { "kind": "stroke", "segments": [{ "points": [0, 0, 12, 30], "type": "free" }], "complete": true }
}
```

## Storage

| Store | What | Survives restart |
|---|---|---|
| SQLite `documents` | name, exercise, hint level, timestamps | yes |
| SQLite `elements` | canvas elements (style/data as JSON) | yes |
| SQLite `agent_state` | agent conversation per document | yes (plots/errors are rebuilt from it) |
| SQLite `assets` | images from PDF import | yes |
| Memory | chunks, OCR cache, hint cards | no |

Schema changes are applied at startup with simple `ALTER TABLE` migrations (`db/Database.kt`).

## REST API

Document ids can be given in full or in their 16-hex short form.

**Documents and elements:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/documents` | List documents |
| `POST` | `/api/documents` | Create `{ name, exercise? }` |
| `GET` | `/api/documents/{id}` | Document metadata |
| `DELETE` | `/api/documents/{id}` | Delete document and its state |
| `PATCH` | `/api/documents/{id}/hint-level` | `{ level: MINIMAL \| MEDIUM \| GUIDED }` |
| `GET` | `/api/documents/{id}/elements` | Elements, ordered by z-index |
| `PUT` | `/api/documents/{id}/elements` | Replace all elements (autosave) |
| `POST` | `/api/documents/{id}/elements` | Create one element |
| `PATCH` | `/api/documents/{id}/elements/{eid}` | Replace one element |
| `DELETE` | `/api/documents/{id}/elements/{eid}` | Delete one element |

**Tutor:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/documents/{id}/hints` | Hint cards (polled every 1.5 s) |
| `POST` | `/api/documents/{id}/hints` | Help button |
| `POST` | `/api/documents/{id}/hints/define` | `{ term, context, anchor }`: definition of a word |
| `POST` | `/api/documents/{id}/hints/{hid}/more` | "Another hint" |
| `PATCH` | `/api/documents/{id}/hints/{hid}/position` | `{ x, y }` page coordinates |
| `DELETE` | `/api/documents/{id}/hints/{hid}` | Close a card |
| `GET` | `/api/documents/{id}/plots` | Active plots with anchors (polled) |
| `GET` | `/api/documents/{id}/errors` | Active error marks in page coordinates (polled) |
| `GET` | `/api/documents/{id}/actions` | Latest agent output |
| `GET` | `/api/documents/{id}/conversation` | The agent conversation |

**Exercise import:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/exercises/convert` | `{ imagePngBase64, pdfText, previousMarkdown?, katexErrors? }` → `{ markdown, figureCount }` |
| `POST` | `/api/assets` | `{ imagePngBase64 }` → `{ id, url }` |
| `GET` | `/api/assets/{id}` | A stored image |

**Debug and inspection:**

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/documents/{id}/chunks` | Chunks with bounds and hashes |
| `GET` | `/api/documents/{id}/transcription` | Chunks with their OCR result |
| `GET` | `/api/documents/{id}/chunks/{cid}/image.png` | Rasterized chunk |
| `GET` | `/api/documents/{id}/chunks/{cid}/symbols` | Detected symbol fragments |
| `GET` | `/api/documents/{id}/chunks/{cid}/annotated.png` | Segmentation drawn over the ink |
| `GET` | `/api/documents/{id}/chunks/{cid}/washed.png` | Exact image sent to the OCR model |
| `GET` | `/api/documents/{id}/debug` | Agent conversation, OCR prompts, chunks, hints |

## Debug mode

Open the app with `?debug` (e.g. `http://localhost:8080/?debug`) to get:

- chunk outlines on the canvas;
- a drawer showing each chunk's images and transcription;
- the OCR prompts;
- the full agent conversation, including all three levels of every hint.

## Tests

```bash
./gradlew :shared:jsNodeTest :server:test
./gradlew :web:jsBrowserTest     # needs Chrome/Chromium (set CHROME_BIN if not found)
```

Covered: chunking (clustering, stable ids, hashes), symbol segmentation, OCR
service (debounce, cache, stale results), PDF region import, hint lifecycle, and
the agent's help-request flow with a fake model.

## Known limitations

- The client **polls** hints, plots and errors every 1.5 s; there is no push channel.
- Chunks, the OCR cache and hint cards are in memory and are lost on restart.
- A circled "?" written too close to other writing is merged into the same chunk
  and may not be recognized as a help request.
- The canvas is a single tall page (960 × 4800) rather than multiple pages.
- Some tldraw features are simplified when saved: bound arrows become free
  arrows, and groups and embedded assets are not persisted.
- The subject sidebar on the home page uses mock data.
- CORS is fully open for development.
