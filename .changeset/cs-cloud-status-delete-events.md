---
"kilo-jetbrains": patch
---

Fix cs-cloud session status seeding, session deletion, and SSE session events: keep the session status map and conversation delete responses decodable, release the IDE capability before deleting a conversation (with auditable release logs), and map flat daemon session.created/updated events.
