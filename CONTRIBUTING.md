# Contributing

The project is currently in an architecture-first prototype phase.

Before changing skip heuristics:

1. Add or update a replay/unit case that demonstrates the behavior.
2. Prefer a miss over a false positive when evidence is ambiguous.
3. Do not add runtime networking or `android.permission.INTERNET`.
4. Do not persist arbitrary accessibility text in default diagnostics.
5. Keep Android framework types out of `:core`.
