# Hints: how a student asks for help

A hint is a short, Socratic nudge towards the next step of the exercise — never
the solution. It appears as a yellow card on the page.

## Asking for a hint

| How | What happens |
|---|---|
| Draw a **`?` inside a circle** on the page | The OCR classifies that chunk as `HINT_REQUEST`; a card appears next to it. Keep the circled `?` a little apart from other writing, otherwise it is merged into the same chunk and not recognized. |
| Press the **`?` button** in the toolbar | Same agent, no trigger on the page: the hint targets the most recent work (the lowest blocks); the card is placed next to it. |

Erasing a circled `?` removes its card. Each circled `?` is answered once.

## The card

- **Always on top** of the strokes, so it stays readable.
- **Draggable** like a tldraw shape: grab it anywhere except its buttons.
- **Red ✕** closes it (the same circled `?` will not be answered again).
- **"I don't get it — another hint"** replaces the card's text with a more
  concrete hint; the previous text moves to the card's `history`, and the agent
  is told what was already said.

## What is stored where

`Hint` (`shared/.../hint/Hint.kt`) lives in the server's memory (`HintService`);
it is lost on server restart.

| Field | Meaning | Sent to the AI? |
|---|---|---|
| `card` | current title, message, steps | yes, as a previous hint for later requests |
| `history` | earlier cards of this box | yes (the next hint must go further) |
| `anchor` | bounds of the circled `?` (or of the lowest block) | no — the agent sees chunk positions, not cards |
| `position` | where the student dragged the card | **no**, UI state only |

What the agent receives (`HintContext`): the exercise statement, every chunk
with its position and transcription, which chunk is the circled `?` (or that the
Help button was used), and the hints already given.

## API

```
GET    /api/documents/{id}/hints                    list (polled by the canvas every 1.5 s)
POST   /api/documents/{id}/hints                    ask with the Help button
POST   /api/documents/{id}/hints/{hintId}/more      "another hint"
PATCH  /api/documents/{id}/hints/{hintId}/position  { "x": …, "y": … } page coordinates
DELETE /api/documents/{id}/hints/{hintId}           close
```

## Code map

- `server/.../hint/HintService.kt` — triggers, lifecycle, "another hint", position
- `server/.../hint/HintAgent.kt` — prompt and structured output (Koog, DeepSeek V4.1 Flash on Together)
- `web/.../ui/HintLayer.kt` — cards: polling, drag, close, another hint
- `web/.../ui/Toolbar.kt` — the `?` button
