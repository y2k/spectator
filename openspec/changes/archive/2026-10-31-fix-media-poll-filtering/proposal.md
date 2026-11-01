# Proposal

## Why

Публикации без извлечённого текста сейчас обходят Selection Rule: это подтверждено фотографией https://t.me/serbia/107367 и опросом https://t.me/Serbia/107388. У фотографии нет подписи, а доступный в Telegram Preview вопрос опроса парсер не извлекает.

## What Changes

- **BREAKING**: при настроенном Selection Rule публикации без доступного непустого текста больше не вызывают Channel notification; это намеренно заменяет прежнее разрешение из `CONTEXT.md`.
- Вопрос опроса становится текстом для существующей проверки ключевых слов. Варианты ответа не участвуют в проверке.
- Текст и подписи обычных публикаций продолжают проверяться существующим правилом; без Selection Rule отправляются все публикации.
- Пропуск публикации продвигает курсор; старые публикации не пересматриваются.

## Capabilities

### New Capabilities

- `channel-post-selection`: отбор Channel Posts по Selection Rule, включая доступный текст, подписи, вопросы опросов и отсутствие анализируемого текста. Capability впервые описывается в OpenSpec; существующих specs нет.

### Modified Capabilities

Нет.

## Impact

- `src/main.clj`: поведение `matches-selection-rule` при отсутствии текста.
- `src/telegram.clj`: извлечение вопроса опроса из Telegram Preview.
- `test/effect_test_entrypoint.clj`, `test/main_test.clj`: HTML-фикстуры и проверки отправок/продвижения курсора.
- `CONTEXT.md`: актуализация определения Selection Rule; `docs/filtering-implementation-plan.md`: пометка о замене прежней политики.
- Изменение не требует миграции БД, новых зависимостей, OCR или нового Telegram API.
