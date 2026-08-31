# Observing Public Telegram Channel Posts

Research for [#6](https://github.com/y2k/spectator/issues/6). Sources and first-party web behavior were checked on 2026-08-31. This report compares mechanisms; it does not select one.

## Question

How can a Cloudflare Worker discover channel posts published after a known point in an arbitrary public Telegram channel?

Here, "arbitrary" means the channel owner has not agreed to add this service's bot or user account. The known point may be a channel message ID, a Bot API `update_id`, or an MTProto channel `pts`; these cursors are not interchangeable.

## Comparison

| Mechanism | Authentication | Channel relationship | Cursor and catch-up | Rate and reliability boundary | Cloudflare fit |
| --- | --- | --- | --- | --- | --- |
| Bot API `channel_post` updates via webhook | Bot token | Telegram says bots receive all messages from channels where they are members. The documented channel-add flow adds a bot as an administrator, so channel-owner cooperation is required in practice. No extra admin right is documented merely to receive `channel_post`. | Global bot `update_id`, not a per-channel post cursor. Webhooks retry failed non-2xx deliveries a "reasonable" number of times; consumers must deduplicate and order by `update_id`. Updates remain queued for at most 24 hours. | Webhook and `getUpdates` are mutually exclusive. Webhook `max_connections` is 1-100. Telegram publishes no separate incoming-update rate cap; notification sends are limited to about 30 messages/second globally by default and should stay below one message/second per destination chat. | Native HTTPS endpoint; direct fit for a Worker `fetch` handler. No scheduled channel polling is needed, but it cannot cover arbitrary channels. |
| Bot API `channel_post` updates via `getUpdates` | Bot token | Same membership/cooperation boundary as webhook. | Long polling returns up to 100 updates. Confirm through `offset = highest update_id + 1`; duplicates are possible until confirmation. Queue retention is at most 24 hours. It cannot query historical channel posts by channel message ID. | Telegram says positive-timeout long polling should be used in production and short polling only for testing. It shares one global update queue with the bot's command traffic and cannot run while a webhook is configured. | HTTPS works, but a minute-granularity Cron Trigger is not Telegram's documented long-polling model. A long-held HTTP invocation is possible while its caller remains connected, but scheduled invocations have a 15-minute duration limit. |
| MTProto `messages.getHistory` under a user account | Own `api_id`/`api_hash`, phone/QR/passkey login, optional 2FA, and a persisted MTProto auth key/session | `messages.getHistory` is user-only. A public username can be resolved with `contacts.resolveUsername`. The method documents `CHANNEL_PRIVATE` when the account lacks access; it does not promise that every nominally public channel remains readable, so access failures still require handling. No admin role is required. Joining is not stated as a prerequisite for public channels. | Channel message IDs are monotonically increasing and stable across accounts. `min_id` filters to IDs strictly greater than a known post ID; `offset_id` plus negative `add_offset` pages toward newer messages. Results are reverse chronological. IDs may have gaps and must not be treated as contiguous. The method is limited by Telegram's channel message-box retention for sufficiently old history. | Telegram gives no fixed request quota. Excess calls produce `FLOOD_WAIT_<seconds>`/420 and must wait for the server-specified interval. History polling discovers current history rather than receiving an event guarantee; deletions or lost access can make a former point unavailable. | MTProto supports HTTPS, secure WebSocket, and TCP transports, all available from Workers. Unlike Bot API JSON, the Worker must implement or bundle MTProto framing, TL serialization, encryption, acknowledgements, DC migration, session persistence, and auth flows. The Free plan's 10 ms CPU limit is material for protocol/crypto work. |
| MTProto `updates.getChannelDifference` under an authorized account | User auth as above, or MTProto bot auth using bot token plus `api_id`/`api_hash` | The method is callable by users and bots. Telegram explicitly documents short-polling public channels not joined by the account to enable passive updates, but says clients should short-poll at most 10 channels and stop when the user closes them. Passive updates are otherwise usually for joined channels. This client-UI contract is not an unrestricted background-monitoring guarantee. | Per-channel `pts`, not message ID. Start from a stored channel state and request differences; persist returned `pts` and repeat while `final` is absent. Telegram recommends 10-100 updates per page for ordinary users. A post ID alone cannot be converted to the earlier `pts`; history scanning is needed if only a post ID is known. Very old `pts` can return `channelDifferenceTooLong`. | The documented gap protocol deduplicates and recovers reordered/missed events. Telegram says to recover after 15 minutes without updates and respect returned `timeout`, but also limits active short-poll subscriptions to 10 channels. Dynamic `FLOOD_WAIT` still applies. | Possible over HTTPS or sockets, with the same MTProto implementation and secret-state burden. Worker isolates cannot share TCP sockets from global scope. Outbound WebSockets do not hibernate in Durable Objects, keep them active for up to 15 minutes, and deployments disconnect them; scheduled HTTPS difference calls avoid a permanent socket but still need durable auth/session/`pts` state. |
| First-party public web preview `https://t.me/s/<username>` | None in observed behavior | Works only when Telegram exposes a web preview for that public channel. No membership or admin role is involved. A public username does not itself guarantee that a usable preview or every post will be exposed. | Observed HTML contains post IDs in `data-post="<username>/<id>"` and canonical post links. On `@telegram`, the default page returned 20 posts; `?after=440` returned IDs 441-460, while `?before=441` returned the preceding window. A caller can therefore experimentally advance from a known ID using `after` and retain the maximum parsed ID. These parameters and markup are not documented as an API or stability contract. | Telegram publishes no web-preview polling quota, cursor guarantee, retention guarantee, retry contract, or change notice. A nonexistent username also returned HTTP 200 in the check, so status alone does not validate a channel. Markup, pagination size, anti-abuse behavior, geographic filtering, and post visibility may change without notice. | Straightforward Worker `fetch` plus HTML parsing. Each page and redirect consumes subrequests; Workers Free permits 50 subrequests/invocation, 10 ms CPU, 128 MB memory, and six simultaneous outgoing connections. Cron Triggers can run as often as once per minute. |

## Mechanism Details

### Bot API

The Bot API is the only option here that delivers JSON `channel_post` events directly to the existing bot. It is not a way to inspect arbitrary public channels: Telegram's Bot FAQ limits channel messages to channels where the bot is a member, and Telegram's documented channel deep-link flow opens a channel administrator picker and adds the bot as an administrator. This makes owner cooperation the decisive boundary, regardless of webhook versus `getUpdates`.

The cursor is the bot-wide `update_id`. It orders command, membership, and channel updates together; the channel's `message_id` is still needed to build `https://t.me/<username>/<message_id>`, but it cannot be passed to the Bot API to backfill channel history. A 24-hour outage can therefore create an unrecoverable Bot API update gap.

Webhook delivery maps directly onto the current Worker endpoint and can be authenticated with `X-Telegram-Bot-Api-Secret-Token`. `getUpdates` instead owns the bot's single polling cursor, so changing the existing webhook integration would also change command ingestion.

### MTProto

MTProto is Telegram's full client protocol, not another Bot API endpoint. `contacts.resolveUsername` supplies peer information for a public username. For a known channel post ID, `messages.getHistory` provides the clearest documented forward query: `min_id` means strictly newer than that ID, and Telegram's pagination guide documents using `offset_id` with a negative `add_offset` to load newer messages. Only user accounts may call this method.

For continuous state, each channel has an independent `pts` event sequence. `updates.getChannelDifference` returns new messages and a successor `pts`, supports pagination until `final`, and is Telegram's documented recovery path for missed updates. This is stronger gap semantics than repeatedly sampling latest history, but it requires a prior `pts`; a known message ID is not enough.

Telegram explicitly describes temporary short-poll subscriptions for public channels an account has not joined. The same documentation frames them as channels a user is currently viewing, limits them to 10, and says to stop when the view closes. That fact supports technical accessibility, not a claim that Telegram supports unlimited server-side surveillance. Bots may invoke `updates.getChannelDifference`, but `messages.getHistory` is user-only and normal bot message visibility remains constrained by bot membership rules.

MTProto credentials are materially more sensitive than a public-page cursor. The service would need to protect `api_hash`, auth keys, user session state, and any 2FA-related flow; Telegram warns that unofficial clients are monitored for abuse and that flooding, spam, and fake metrics can permanently ban the API identity/account. Parallel use of one authorization key/session also has protocol constraints and can invalidate it with `AUTH_KEY_DUPLICATED`.

### Public Web Preview

Telegram itself serves public channel preview HTML at `/s/<username>`. The observed response exposes stable-looking post IDs and links without login, and `after`/`before` currently provide useful windows. This was a live behavioral check, not an official specification: Telegram's documented `t.me` deep-link syntax covers individual message links, but no Telegram documentation found defines `/s/`, its HTML schema, or pagination parameters as a supported API.

The narrowest use would parse only post IDs greater than the stored point and emit the canonical Telegram links, without downloading media or retaining post bodies. That reduces data collection and Worker cost, but it does not turn the endpoint into a guaranteed API. A later decision relying on it must accept parser breakage, silent omissions, and possible throttling as operational failure modes.

## Cloudflare Constraints

- Bot API and public preview requests use the native Worker Fetch API.
- MTProto can technically use Telegram's documented HTTPS/WSS endpoints or Workers' outbound `connect()` TCP API. TCP sockets must be opened inside a handler and count toward the six simultaneous-connection limit.
- Workers Free allows 50 subrequests and 10 ms CPU per invocation; paid Workers allow 10,000 subrequests by default and longer CPU. Network wait does not count as CPU.
- Cron Trigger expressions have one-minute resolution and scheduled invocations have a 15-minute wall-time ceiling. This suits periodic scans, not an indefinitely connected MTProto client.
- Long-lived outbound MTProto WebSockets do not receive Durable Object WebSocket hibernation. They keep the object active for up to 15 minutes, and code deployments disconnect them.
- Every mechanism needs durable, atomic cursor advancement only after downstream handling succeeds. Bot API needs `update_id`; MTProto needs message ID and/or per-channel `pts`; web preview needs the maximum observed post ID. Cursor writes and notifications also consume Worker service calls/subrequests.

## Terms And Security Boundaries

- Telegram's Content Licensing Terms prohibit access outside ordinary intended platform use, with a limited exception for data strictly required to run a legitimate compliant client, bot, or Mini App. They also require compliance with rights-holder restrictions.
- Bot Developer Terms prohibit collecting, storing, aggregating, or processing beyond what is essential to the service, specifically prohibit public-channel scraping for large datasets/ML products, require a suitable privacy policy, require deletion when data is no longer needed, and require credentials not be public.
- Telegram API Terms require an application's own `api_id`, careful privacy/security, and support for official sponsored messages when an app exposes channel content. The latter may be relevant if the product evolves beyond link-only notifications.
- None of these sources gives blanket permission to scrape `/s/`. Conversely, the terms' limited service-operation exception means the endpoint should not be characterized as categorically forbidden from these texts alone. Compliance depends on keeping collection essential and on the eventual product behavior; this report is not legal advice.
- A user-account MTProto session grants broad account access. Isolating that account, encrypting session material at rest separately from its key, minimizing retained post data, and planning credential revocation are security requirements implied by Telegram's terms, not optional implementation polish.

## Bounded Implications For The Decision Ticket

- If arbitrary channels is non-negotiable, Bot API `channel_post` updates do not satisfy it because they require the channel to add the bot.
- If a supported Telegram protocol and recoverable cursors dominate, MTProto supplies documented history and gap APIs, but the decision inherits a user/client identity, dynamic flood control, sensitive session custody, and substantial protocol/runtime work.
- If minimum Worker complexity and no Telegram login dominate, the public preview is the smallest mechanism, but the decision must explicitly accept an undocumented HTML dependency with no delivery, quota, or retention guarantee.
- A known post ID directly fits MTProto history and observed web pagination. It does not initialize Bot API `update_id` or MTProto `pts`.
- Scale matters before implementation: Telegram documents at most 10 simultaneous MTProto short-polled channel views, while Workers Free caps a scheduled invocation at 50 subrequests. Neither number is a general supported-channel quota, but both bound particular polling designs.
- No source establishes a mechanism that is simultaneously official, unauthenticated, arbitrary-channel, unlimited-scale, and guaranteed. The later ticket must choose which constraint to relax; this report does not make that choice.

## Sources

### Telegram

- [Bot API: getting updates, `Update`, `getUpdates`, and `setWebhook`](https://core.telegram.org/bots/api#getting-updates)
- [Bot FAQ: messages visible to bots and send limits](https://core.telegram.org/bots/faq#what-messages-will-my-bot-get)
- [Deep links: public message links and channel bot-add flow](https://core.telegram.org/api/links#message-links)
- [Working with bots over MTProto](https://core.telegram.org/api/bots)
- [`contacts.resolveUsername`](https://core.telegram.org/method/contacts.resolveUsername)
- [`messages.getHistory`](https://core.telegram.org/method/messages.getHistory)
- [MTProto pagination](https://core.telegram.org/api/offsets)
- [Working with updates: `pts`, gap recovery, retention, and public-channel short polling](https://core.telegram.org/api/updates)
- [`updates.getChannelDifference`](https://core.telegram.org/method/updates.getChannelDifference)
- [`channels.getMessages`](https://core.telegram.org/method/channels.getMessages)
- [MTProto transport protocols](https://core.telegram.org/mtproto/transports)
- [User authorization](https://core.telegram.org/api/auth)
- [Creating an application and abuse warning](https://core.telegram.org/api/obtaining_api_id)
- [MTProto error handling and `FLOOD_WAIT`](https://core.telegram.org/api/errors)
- [Telegram API Terms of Service](https://core.telegram.org/api/terms)
- [Telegram Bot Platform Developer Terms](https://telegram.org/tos/bot-developers)
- [Terms of Service for Content Licensing](https://telegram.org/tos/content-licensing)
- [First-party public channel preview used for the behavioral check](https://t.me/s/telegram)

### Cloudflare

- [Workers Fetch API](https://developers.cloudflare.com/workers/runtime-apis/fetch/)
- [Workers TCP sockets](https://developers.cloudflare.com/workers/runtime-apis/tcp-sockets/)
- [Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- [Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)
- [Durable Objects WebSockets, including outbound hibernation limits](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
