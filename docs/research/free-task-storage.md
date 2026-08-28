# Free Storage for Telegram Tasks

Research for [#2](https://github.com/y2k/spectator/issues/2). Sources were checked on 2026-08-28 and are Cloudflare documentation only.

## Requirement

Persist text tasks associated with a Telegram user ID from this Worker. The bot needs to add and query those records by user ID.

## Relevant Options

| Option | Free availability and limits | Fit |
| --- | --- | --- |
| D1 | Available on Workers Free: 5 million rows read/day, 100,000 rows written/day, and 5 GB total storage. Free accounts can have 10 databases, each up to 500 MB. | Best fit. A `tasks` table can store `telegram_user_id` and task text, while SQL directly expresses listing a user's tasks. |
| Workers KV | Included on Workers Free: 100,000 reads/day, 1,000 writes to different keys/day, 1 write/second to the same key, and 1 GB storage. | Possible only as one serialized task list per user-ID key. KV is eventually consistent: a change can take 60 seconds or more to appear in other locations. Its write cap and whole-list rewrites make it a poorer task store. |

## Recommendation

Use one **D1** database bound to the existing Worker. Store each task as a row keyed/indexed by `telegram_user_id`; this keeps task operations direct and remains within the free tier's stated limits for the intended small bot. Do not add KV for task persistence.

## Sources

- [Cloudflare Workers pricing: D1 and Workers KV free-plan allocations](https://developers.cloudflare.com/workers/platform/pricing/)
- [Cloudflare D1 limits](https://developers.cloudflare.com/d1/platform/limits/)
- [Cloudflare storage-option guidance](https://developers.cloudflare.com/workers/platform/storage-options/)
- [Cloudflare Workers KV limits](https://developers.cloudflare.com/kv/platform/limits/)
- [Cloudflare KV consistency model](https://developers.cloudflare.com/kv/concepts/how-kv-works/)
