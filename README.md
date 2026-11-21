# Lauzhack Draw

![Lauzhack Draw canvas](docs/hero.png)

A cross-platform drawing app backend + web frontend, built with Kotlin.

- **`server/`** — Ktor 3 + Exposed + SQLite REST backend (Kotlin/JVM)
- **`shared/`** — Kotlin Multiplatform module: tool-agnostic drawing domain model,
  DTOs and a Ktor REST client, reused by every current and future client
- **`web/`** — Kotlin/JS + React (kotlin-wrappers) frontend with a
  [tldraw](https://tldraw.dev) infinite canvas, wired to the backend through a
  thin adapter layer

The UI is chrome-free: the canvas fills the whole page, with only a floating
back button on top. tldraw's own UI (minimap, zoom controls, menus, default
toolbar, pages) is hidden — pick tools via keyboard shortcuts (V, D, E, R,
A, T) and colors from the contextual style panel. Autosave runs silently
in the background.

The database stores **generic drawing data** (strokes, geometry, text, arrows
with style/color info) — tldraw is just the web-facing editor. Android and iOS
apps planned later will consume the same API and the same `shared` module.

## Running in development

```sh
# terminal 1 — backend on :8080
./gradlew :server:run

# terminal 2 — web dev server on :8081 (proxies /api to :8080)
./gradlew :web:jsBrowserDevelopmentRun --continuous
```

Open http://localhost:8081.

## Production build

```sh
./gradlew :web:jsBrowserDistribution   # bundle → web/build/dist/js/productionExecutable
./gradlew :server:run                   # serves the bundle + API on :8080
```

## API

```
GET    /api/documents                       list documents
POST   /api/documents                       { "name": "..." } → create
GET    /api/documents/{id}                  document metadata
DELETE /api/documents/{id}

GET    /api/documents/{id}/elements         all elements (z-ordered)
PUT    /api/documents/{id}/elements          batch replace (web autosave)
POST   /api/documents/{id}/elements         create one
PATCH  /api/documents/{id}/elements/{eid}   replace one
DELETE /api/documents/{id}/elements/{eid}   delete one
```

### Handwriting chunks

Every element write re-chunks the page: strokes are grouped into macro-blocks
purely by spatial proximity (`shared/.../chunking`), and each block keeps a
stable id across edits. Only blocks whose content hash changed are reported
as modified, so downstream OCR/AI only re-processes what the user touched.

```
GET    /api/documents/{id}/chunks    [{ id, strokeIds, bounds, contentHash, version }]   (debug/inspection)
```

Inside the server, consumers subscribe to `ChunkService.events`, which pushes
`{ added, modified, removed }` (plus the strokes of changed chunks) after every write.

### OCR

Every added/modified chunk is rendered to a PNG on the server and transcribed by a
light vision model on [Together AI](https://www.together.ai)
(`deepseek-ai/DeepSeek-V4.1-Flash-1017` by default) through
[Koog](https://github.com/JetBrains/koog). The result is `{ kind, content }` with
`kind` one of `MATH | TEXT | QUESTION_MARK | DRAWING | UNREADABLE` and `content`
in LaTeX. Results are cached by chunk content hash and debounced (1 s) while a
block is being written; `OcrService.events` streams them to the reasoning agent.

```sh
export TOGETHER_API_KEY=...      # OCR (optional: without it chunks stay pending)
export ANTHROPIC_API_KEY=...     # reasoning model (not used yet)
./gradlew :server:run
```

```
GET    /api/documents/{id}/transcription               [{ chunk, transcription | null }]   (debug)
GET    /api/documents/{id}/chunks/{chunkId}/image.png  image sent to the OCR model        (debug)
```

Elements are polymorphic via the `kind` discriminator:

```json
{
  "id": "shape:8f9c...",
  "type": "stroke",
  "x": 120, "y": 40, "rotation": 0,
  "zIndex": "a1",
  "style": { "color": "red", "size": "m" },
  "data": { "kind": "stroke", "segments": [ { "points": [0, 0, 12, 30], "type": "free" } ], "complete": true }
}
```

## Architecture notes

- **Only `web/` knows tldraw exists.** `tldraw/Mapper.kt` converts tldraw store
  records to the shared `Element` model and back; everything behind the REST
  boundary is tool-agnostic.
- **New widget types need no DB migration**: `data`/`style` are JSON, and new
  `ElementData` subclasses can be added in `shared` only.
- MVP limitations (documented on purpose): bound arrows are saved as free
  arrows, image/asset shapes and groups are not persisted yet, single page.
- Node packages for the web app are installed by the Kotlin Gradle plugin's
  bundled Yarn inside the build (KGP does not support pnpm for this — it's an
  implementation detail of the build, not a project-level choice). Use pnpm for
  any direct JS tooling you add yourself. `tldraw` is pinned to an exact version
  on purpose — tldraw does not follow semver.

## Tooling

- Kotlin 2.4.21, Ktor 3.6.1, Exposed 1.6.1, SQLite via `sqlite-jdbc`
- kotlin-wrappers (React 19), tldraw 5.6.2
- Server scaffolded with the official `ktor` CLI, adapted to a multi-module build
