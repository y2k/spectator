# Spectator

Spectator is a Worker that acknowledges HTTP requests and reports each invocation to Telegram.

## Language

**Invocation notification**:
A Telegram message emitted for each HTTP request handled by the Worker.
_Avoid_: Worker startup notification, Telegram alert

**Bot API snapshot**:
A checked-in local copy of the official Telegram Bot API reference used by the project's Telegram integration skill.
_Avoid_: SDK documentation, framework guide
