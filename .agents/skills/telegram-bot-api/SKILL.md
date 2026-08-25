---
name: telegram-bot-api
description: Build or review direct Telegram Bot API integrations: HTTPS methods, JSON types, updates, file transfers, polling, and webhooks. Use the local Bot API snapshot for any Telegram bot API question.
---

# Telegram Bot API

Use this skill for the HTTP-based Telegram Bot API only. The local snapshot is the API contract; it is not an SDK or framework guide.

## Reference

- `references/overview.md`: authorization and request protocol.
- `references/methods.md`: API methods, including polling and webhooks.
- `references/types.md`: JSON objects and field definitions.

Load only the relevant file and section. If a required method or type is absent, refresh the snapshot before implementing:

```sh
python3 .agents/skills/telegram-bot-api/scripts/update_reference.py
```

## Workflow

1. Identify the required API method, input fields, result type, and relevant `Update` variant from the snapshot.
2. Use the project's existing HTTP client to call `https://api.telegram.org/bot<TOKEN>/METHOD_NAME` over HTTPS.
3. Keep the bot token in the environment or secret store. Do not put it in source, logs, URLs, or error messages.
4. For webhooks, validate `X-Telegram-Bot-Api-Secret-Token`; for polling, advance `offset` after each successfully processed update. The two delivery modes are mutually exclusive.
5. Handle the documented response shape and error fields. Do not guess unknown fields or methods.

## Refreshing

`scripts/update_reference.py` fetches the official documentation, rewrites the three references, and records the Bot API version and snapshot date. It fails if the expected API sections or core entries cannot be extracted.
