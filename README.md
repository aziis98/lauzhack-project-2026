# Lauzhack Draw (AI-assisted math & exercise practice canvas)

![Lauzhack Draw canvas](docs/hero.png)

A Kotlin-based, cross-platform drawing workspace for students doing **exercise-based subjects** (with a first focus on **mathematics**, e.g. analyzing the graph of a function). Students work on an infinite whiteboard/canvas; when they need help, an **AI tutor** can step in interactively (hints, feedback, visual aids) directly inside the same workspace.

This repository currently contains the **canvas app + backend** (already implemented). The hackathon goal is to **add AI-assisted interaction** on top of this canvas for live demos.

---

## Table of contents

- [Vision](#vision)
- [Hackathon demo (concrete scenario)](#hackathon-demo-concrete-scenario)
- [What exists today (current app)](#what-exists-today-current-app)
- [Planned AI-assisted features](#planned-ai-assisted-features)
- [How the system works (architecture)](#how-the-system-works-architecture)
- [Data model (shared, tool-agnostic)](#data-model-shared-tool-agnostic)
- [REST API (backend)](#rest-api-backend)
- [Running in development](#running-in-development)
- [Production build](#production-build)
- [Notes for AI model collaborators](#notes-for-ai-model-collaborators)
- [Project structure](#project-structure)
- [Current limitations / MVP notes](#current-limitations--mvp-notes)
- [Tooling](#tooling)

---

## Vision

We want a **digital whiteboard for exercises** where:

- Students solve problems by drawing/writing naturally (steps, sketches, formulas, graphs).
- The system is **subject-aware in intent** (math first; chemistry and other worked-example subjects later) and can:
  - react when the student asks for help,
  - point out mistakes,
  - generate helpful visualizations (e.g. plot a function and highlight key points).
- The AI does **not** replace the student: it provides **interactive guidance** (hints, corrections, annotations, plots) inside the same canvas context.

The product direction: **Kotlin everywhere** (shared logic + server + web client now; mobile clients planned) with a persistent canvas stored on a backend.

---

## Hackathon demo (concrete scenario)

### Primary demo: “study the graph of a function” (mathematics)

A student is working through a typical exercise, for example analyzing `f(x)` (domain, derivative, critical points, monotonicity, asymptotes, sketch/graph interpretation). They use the canvas to write steps, draw rough sketches, compute derivatives, etc.

We demonstrate AI help in three complementary ways:

1. **Help on request (interactive hint) via a drawn signal**
   - If the student is stuck, they can **draw a question mark** (`?`) on the canvas (freehand/stroke) as a request for help.
   - The AI detects/receives this intent (see [Notes for AI model collaborators](#notes-for-ai-model-collaborators)) and responds **inside the interface**, for example:
     - showing a hint near the relevant work (text/annotation),
     - suggesting the next step (e.g. “try using the product rule: `(fg)' = f'g + f g'`”),
     - or guiding step-by-step rather than giving the full solution immediately (pedagogical behavior to be defined by the team).

2. **Automatic feedback on mistakes (visual correction)**
   - If the student makes a **calculation/error** in their work, the AI can mark it **in red** (e.g. highlight the wrong expression/region or draw a red underline/annotation) to draw attention and prompt correction.
   - The exact trigger can be: explicit “check my work”, periodic analysis, or (future) continuous assistance—decided during implementation.

3. **Function plotting + key points**
   - The AI can **plot a function** the student is studying (based on what the student wrote/selected or what the exercise states) directly in the canvas context.
   - It can also **mark special points** that were calculated (e.g. roots, critical points, extrema, inflection points if relevant) with labels/markers to connect algebraic work to the graph.

### Other subjects (direction, not the main demo)

The same interaction patterns apply to **other exercised-based subjects** such as **chemistry** (e.g. stepwise problem solving, checking balances/calculations, highlighting errors, adding guided hints). The hackathon demo focuses on the **graph-of-a-function** math flow above.

---

## What exists today (current app)

The repo is a working **drawing app** (Lauzhack Draw) that serves as the base canvas:

- **Infinite whiteboard UI** powered by **tldraw** (web), running as a Kotlin/JS + React app.
- **Documents**: users can create/open drawings (documents) from a simple home list.
- **Persistent storage**: drawings are saved to a **Kotlin/JVM backend** (Ktor) using **SQLite** (via Exposed).
- **Autosave**: the web client periodically batch-saves the current canvas state to the server (silent background sync).
- **Tool-agnostic persistence**: the server stores a **generic element model** (see below); the web app is the only layer that knows tldraw details (conversion happens in the web adapter).

### Canvas/editor behavior (current)

- The canvas is effectively **chrome-free** in the main drawing view (floating back button). tldraw’s default UI panels are hidden in this build; interaction follows the app’s current configuration.
- Drawings are treated as a **single page** in the current integration.
- Supported content today (via the shared model + tldraw mapping) includes shapes such as **strokes/drawings, basic geometries (rectangle/ellipse/triangle/diamond/cloud), text/note, arrows** (arrows are persisted in a simplified/free form in current MVP behavior). Unknown/future element kinds can be extended in the shared model.

> This canvas is the “workspace” where students will do exercises and where the AI will add hints, highlights, plots, and annotations during the hackathon demo.

---

## Planned AI-assisted features

These are the features we are building toward for the demo (and beyond). They describe **intended behavior** so AI model/ML colleagues know what the product needs.

### 1) Hint/request trigger: student draws a **question mark**

- **User action**: the student draws a `?` (stroke) on the canvas when stuck.
- **System intent**: treat this as a **help request** tied to the nearby context (current strokes/text/selected area or recent work).
- **AI response (examples)**:
  - Insert a hint annotation near the problem area (text callout / note).
  - Suggest a method/formula relevant to the step (e.g. derivative rules for the function being studied).
  - Offer a Socratic next step (“What is the derivative of the product?”) instead of the full answer (policy TBD).
- **Open design choices (to be decided)**:
  - Detection: recognize a question-mark glyph from strokes (vision/classifier) vs. an explicit UI button shortcut vs. both.
  - Scope: what canvas region/context is sent to the model (bounding box around `?`, whole page, selected elements, or a structured export).

### 2) Mistake detection → **mark in red**

- **User action**: student writes/calculates on the canvas (and optionally requests a check, or the system checks on demand).
- **AI response**:
  - Visually mark incorrect parts **in red** (highlight, red stroke, or an overlay annotation) so the student sees what to revisit.
  - Optionally attach a short explanation/hint (separately or on hover/click, depending on UI).
- **Examples (math, graph-of-function exercise)**:
  - Wrong derivative expression.
  - Sign/error in solving `f'(x)=0`.
  - Incorrect classification of a critical point (if inferred).
- **Open design choices**:
  - How “marks” are represented on canvas (new styled elements in the shared model vs. temporary overlays). Today the model supports styles/colors on elements; the team should align representation with persistence needs.

### 3) **Plot the function** being studied (+ mark special points)

- **User action**: student indicates the function (written text like `f(x)=...`, or selection) or the AI infers it from context.
- **AI/system response**:
  - Render a **plot** of the function on the canvas (or in a dedicated canvas region) consistent with the exercise.
  - **Mark special points** computed/relevant to the exercise: e.g. intercepts, critical points, local maxima/minima, points of interest the student found—labeled clearly.
- **Goal**: connect symbolic work (derivatives, equations) to a visual graph to support learning.

### 4) General interactive tutoring loop (beyond the three bullets)

The hackathon demo centers on the items above, but the intended product behavior is an **interactive assistant** that can:

- answer follow-up questions in context,
- give progressive hints,
- annotate the canvas (drawings/text/markers/plots),
- stay aligned with what the student wrote (context-aware reasoning).

---

## How the system works (architecture)

High-level components (monorepo):

```
lauzhack-project-2026/
├── shared/        Kotlin Multiplatform library (domain model + DTOs + REST client)
├── server/        Ktor 3 (JVM) REST API + SQLite persistence, serves built web in prod
├── web/           Kotlin/JS + React app with tldraw canvas (only layer that knows tldraw)
├── docs/          Static assets (e.g. hero image)
└── ...            Gradle wrapper, build files
```

### Key principles (from current design)

- **`web/` is the only module that imports/knows tldraw.** Conversion between tldraw records and the shared domain happens in the web adapter layer.
- **Backend stores generic drawing data.** The API and database do not depend on tldraw concepts.
- **`shared/` is reused** by web today and is intended for future clients (e.g. Android/iOS) using the same API/model.

### Runtime flow (current)

1. User opens the web app (home page lists documents).
2. User creates or opens a document → the canvas loads elements from `GET /api/documents/{id}/elements`.
3. User draws/edits in tldraw → the web client converts the current page shapes to shared `Element` objects.
4. **Autosave** (every ~2.5 seconds, when content changes): `PUT /api/documents/{id}/elements` replaces the stored element set for that document.
5. Backend persists documents and elements in SQLite; in production the server can also serve the built web bundle.

### Where AI fits (integration points, conceptual)

When AI features are added, typical integration points will be:

- **Client-side (web)**: detect user intents (e.g. a new stroke that looks like `?`, or an explicit “Ask AI” action), gather context (nearby elements, selected region, full document snapshot), call an AI service/orchestrator.
- **AI/orchestration layer** (to be implemented): may run as part of the server, as a separate service, or via external APIs—responsibility is to interpret student context and produce actions.
- **Applying AI output to the canvas**: represent results as new/updated elements through the same shared model (e.g. add text/annotation shapes, add plotted geometry/strokes, restyle elements to red for error marks) so they persist like normal drawing data (subject to product rules: ephemeral hints vs. saved annotations).

> **For AI colleagues**: the most important contract is the **shared element representation** and the REST API below. Decide early whether AI-generated content is persisted (saved into the document) or shown transiently; both are possible, but persistence uses the same `Element`/API path.

---

## Data model (shared, tool-agnostic)

Defined in `shared` (Kotlin Multiplatform), serialized as JSON for the API.

### `Document`

- `id: String` (e.g. `doc:...`)
- `name: String`
- `updatedAt: Long` (epoch millis)

### `Element` (one canvas object)

- `id: String` (conventionally `shape:<uuid>` in the web editor)
- `type: String` — e.g. `stroke`, `rectangle`, `ellipse`, `triangle`, `diamond`, `cloud`, `text`, `note`, `arrow` (and future widget types)
- `x: Double`, `y: Double`
- `rotation: Double`
- `zIndex: String` (fractional index string for ordering)
- `parentId: String?` (reserved)
- `style: Style?`
- `data: ElementData` (polymorphic, discriminator **`kind`**)

### `Style`

- `color: String?` (named palette, e.g. `black`, `red`, `light-blue`, …)
- `fill: String?` (`none` | `semi` | `solid`)
- `dash: String?` (`draw` | `solid` | `dotted` | `dashed`)
- `size: String?` (`s` | `m` | `l` | `xl`)

### `ElementData` (by `kind`)

- `kind = "stroke"` → `Stroke(segments: List<Segment>, complete: Boolean)`; each `Segment` has `points: List<Double>` flattened as `[x1,y1,x2,y2,...]`, `type` (e.g. `free`/`straight`/`edge` depending on source).
- `kind = "geometry"` → `Geometry(w, h)`
- `kind = "text"` → `Text(text, w, h)`
- `kind = "arrow"` → `Arrow(bend, start: Point, end: Point)`

### Extensibility

New element/widget kinds can be added in `shared` (new `ElementData` subclasses) without requiring a database schema migration (payloads are stored as JSON). This is the intended extension path for **AI-generated widgets** (plots, annotated markers, hint cards) if you model them as elements.

---

## REST API (backend)

Base URL in development: `http://localhost:8080` (web dev server proxies `/api` from `http://localhost:8081`).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/documents` | List documents |
| `POST` | `/api/documents` | Create document (`{ "name": "..." }`) |
| `GET` | `/api/documents/{id}` | Get document metadata |
| `DELETE` | `/api/documents/{id}` | Delete document |
| `GET` | `/api/documents/{id}/elements` | List elements (ordered by z-index) |
| `PUT` | `/api/documents/{id}/elements` | **Batch replace** all elements for the document (used by web autosave) |
| `POST` | `/api/documents/{id}/elements` | Create a single element |
| `PATCH` | `/api/documents/{id}/elements/{eid}` | Replace/update a single element (full element body) |
| `DELETE` | `/api/documents/{id}/elements/{eid}` | Delete a single element |

### Example: element JSON shape

```json
{
  "id": "shape:8f9c...",
  "type": "stroke",
  "x": 120,
  "y": 40,
  "rotation": 0,
  "zIndex": "a1",
  "style": { "color": "red", "size": "m" },
  "data": {
    "kind": "stroke",
    "segments": [{ "points": [0, 0, 12, 30], "type": "free" }],
    "complete": true
  }
}
```

Notes:
- The `kind` field is required on `data` (polymorphism).
- The server uses permissive JSON decoding in practice (`ignoreUnknownKeys`), but clients should send well-formed `Element` objects.

---

## Running in development

Prerequisites: a JDK compatible with the Gradle toolchain used by the project (the build targets JVM 21 in server configuration; use a recent JDK that can run Gradle with this repo).

```sh
# Terminal 1 — backend on :8080 (SQLite file data.db in the working directory of the server process)
./gradlew :server:run

# Terminal 2 — web dev server (proxies /api to :8080)
./gradlew :web:jsBrowserDevelopmentRun --continuous
```

Open: http://localhost:8081

---

## Production build

```sh
# Build web bundle
./gradlew :web:jsBrowserDistribution

# Run server (can serve the built frontend + API on :8080 depending on setup)
./gradlew :server:run
```

(See also the existing build output paths referenced in build files; the server is configured to serve a built frontend bundle when present.)

---

## Notes for AI model collaborators

This section is for teammates building **AI models/services** who need to understand the product context and integration expectations.

### What to optimize for (demo)

- **Math first**: “study the graph of a function” exercise.
- **Interactive, not passive**: the AI should feel like a tutor inside the canvas (hints, corrections, plots), not a separate chat-only answer box (though chat can be complementary later).
- **Low-friction triggers**:
  - Student draws a **question mark** (`?`) → request for help/hint.
  - Student work contains **calculation errors** → mark mistakes (e.g. in **red**).
  - Student is studying a **function** → offer/produce a **plot** and **mark special points** (roots, critical points, extrema, etc., as relevant).

### Context the AI may need

Depending on implementation, useful inputs include:

- The **document’s elements** (full canvas state) from `GET /api/documents/{id}/elements` (or a client-side snapshot): strokes, text, positions.
- **Spatial context** around a trigger (e.g. bounding region near a drawn `?`, or the region the student is working in).
- **Parsed intent**: “I need a hint about the next step”, “check my solution”, “plot f(x)=…” (may come from gestures, nearby text, or explicit UI actions).
- Subject metadata (future): math vs chemistry, topic tags—today not modeled explicitly in the API.

### Possible output modalities (align with UI team)

- **Annotations on the canvas**: new `Element`s such as text/note hints, highlighted marks (often using `style.color = "red"` for errors), arrows/markers.
- **Plots/graphics**: represented as strokes/geometry elements (or a future dedicated element kind). For the demo, a pragmatic approach is to generate drawable geometry (e.g. polylines as strokes) from computed points.
- **Structured feedback** (optional): short explanations returned alongside canvas actions (could be shown in a side panel later); the hackathon demo emphasizes **in-canvas** effects.

### Representation guidance

- Prefer reusing the **shared model** so AI output can be persisted and round-tripped: create `Element` objects with appropriate `type`/`data`/`style`.
- For **error marking in red**, a simple v1 is to add red strokes/highlights or restyle relevant elements to `style.color = "red"` (if the product allows modifying student strokes, define UX carefully; alternatively add overlay elements).
- For **plots**, consider generating `stroke` elements from sampled `(x,y)` points (and optional separate markers for special points as small geometries or additional strokes/text labels).

### Timing / interaction patterns (suggested)

- **On-demand first**: trigger AI when student draws `?` or clicks an “Ask AI / Check work” control (easier to control in a demo).
- **Then optional proactive checks**: only if reliable enough to avoid distracting/incorrect interruptions.

### Privacy & correctness (be explicit in demo)

- State clearly when the AI is guessing vs. computing (e.g. plotting from an explicitly parsed formula is more reliable than guessing from messy handwriting).
- For math, prefer **symbolic/numeric pipelines** you trust for the demo scope (specific functions chosen in advance if helpful) over fully open-ended parsing.

---

## Project structure

- `shared/` — Kotlin Multiplatform: `com.lauzhack.shared.domain`, `com.lauzhack.shared.dto`, `com.lauzhack.shared.api` (`DrawingApi`)
- `server/` — Ktor app (`com.lauzhack.server`), routes in `routes/`, persistence in `db/` (Exposed + SQLite)
- `web/` — Kotlin/JS React app (`com.lauzhack.web`), UI in `ui/`, tldraw integration in `tldraw/` (mapping + externals)
- `docs/` — images/assets for documentation

---

## Current limitations / MVP notes

These are known constraints of the current codebase (useful context for scoping the AI demo):

- Autosave is **polling-based** (periodic batch replace) in the web client.
- tldraw integration is focused on a **single page** experience in this build.
- Some tldraw features are not persisted in the same way as a full tldraw document (see comments in code/docs): e.g. bound arrows may be treated as free arrows in persistence, and some complex shapes/assets/groups may not be fully persisted yet.
- The backend stores generic JSON blobs for `style`/`data`, which is good for extension but means **validation of new AI-produced element kinds** should be handled carefully by clients/services.

---

## Tooling

- Kotlin **2.4.21**
- Ktor **3.6.1** (server + client)
- Exposed **1.6.1**, SQLite (`sqlite-jdbc`)
- Kotlin/JS + React (kotlin-wrappers), **tldraw 5.6.2** (pinned)
- Build: Gradle (wrapper included)

For day-to-day commands, see [Running in development](#running-in-development) and [Production build](#production-build).
