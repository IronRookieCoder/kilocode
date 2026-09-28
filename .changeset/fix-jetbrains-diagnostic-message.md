---
"kilo-jetbrains": patch
---

Carry the real (redacted) message text in diagnostic parent facts: `diagnostic.reported.message` now holds the original log or error message instead of a fixed placeholder, so log consumers can read it directly. Oversize messages are clipped in the parent and the full text overflows into message chunks.
