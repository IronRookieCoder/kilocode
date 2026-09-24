"kilo-code": patch
---

Send the first prompt in a JetBrains chat session immediately instead of waiting up to 10s for the IDE capability bind, which now retries in the background. Withdraw the terminal error card when an in-turn API error retries successfully and streaming resumes.
