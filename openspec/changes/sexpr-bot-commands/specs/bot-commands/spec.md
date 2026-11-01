# Spec Delta

## ADDED Requirements

### Requirement: One non-evaluating command per message
The bot SHALL accept exactly one parenthesized command containing a command name followed by literal arguments. It SHALL allow whitespace around the form and between tokens. It SHALL NOT evaluate expressions, execute nested forms, or execute any part of a message containing multiple forms or trailing non-whitespace content.

#### Scenario: Whitespace around a command
- **WHEN** a user sends `  (tasks)  `
- **THEN** the bot lists that user's Tasks

#### Scenario: Nested expression
- **WHEN** a user sends `(delete (+ 1 1))`
- **THEN** no Task is deleted and the bot replies with a format error

#### Scenario: Multiple commands or trailing text
- **WHEN** a user sends `(delete 1) (delete 2)` or `(delete 1) extra`
- **THEN** neither command is executed and the bot replies with a format error

### Requirement: Command arguments have explicit types and arity
The bot SHALL accept `start` and `tasks` with no arguments, `add` with one double-quoted string, `delete` with one positive integer, and `filter` with a positive integer followed by one or more double-quoted strings. It SHALL reject unknown commands, malformed forms, incorrect argument types, and incorrect argument counts without Task mutations.

#### Scenario: Invalid argument type
- **WHEN** a user sends `(delete "2")` or `(add https://t.me/channel)`
- **THEN** the bot replies with an error and does not mutate Tasks

#### Scenario: Invalid command or arity
- **WHEN** a user sends `(unknown)`, `(tasks 1)`, `(delete 0)`, or `(add)` in a private chat
- **THEN** the bot replies with an error or usage guidance and does not mutate Tasks

#### Scenario: Unterminated form
- **WHEN** a user sends `(add "https://t.me/channel"`
- **THEN** no Task is added and the bot replies with a format error

### Requirement: Task commands preserve ownership and behavior
The bot SHALL add public Telegram channel Tasks only in private chats, list only the sender's Tasks in their existing order, and delete only the sender's Task at the supplied one-based list position. Existing channel validation, duplicate detection, and initial cursor behavior SHALL remain unchanged.

#### Scenario: Add a channel
- **WHEN** a user sends `(add "https://t.me/channel")` in a private chat for a readable channel they have not added
- **THEN** the bot adds their Task with its cursor at the latest observed post and confirms the addition

#### Scenario: Add outside a private chat
- **WHEN** a user sends `(add "https://t.me/channel")` outside a private chat
- **THEN** no Task is added

#### Scenario: List and delete owned Tasks
- **WHEN** a user sends `(tasks)` and then `(delete 2)` with at least two Tasks
- **THEN** the list contains only their Tasks and only their second Task is deleted

#### Scenario: Missing Task
- **WHEN** a user sends `(delete 2)` without a second Task
- **THEN** the bot reports that the Task was not found and other users' Tasks remain unchanged

### Requirement: Filter commands preserve Selection Rule semantics
The bot SHALL apply `(filter N "+required" "-prohibited")` to the sender's Nth Task using existing Selection Rule semantics. Each keyword argument SHALL have a nonempty body after `+` or `-` and SHALL NOT contain whitespace. The sole string argument `"off"` SHALL remove the rule. Unsupported arguments SHALL NOT change the rule.

#### Scenario: Configure a rule
- **WHEN** a user sends `(filter 2 "+clojure" "-ads")`
- **THEN** their second Task requires the keyword `clojure` and prohibits `ads` using existing matching semantics

#### Scenario: Remove a rule
- **WHEN** a user sends `(filter 2 "off")`
- **THEN** the Selection Rule is removed from their second Task

#### Scenario: Invalid keywords
- **WHEN** a user sends `(filter 2 "clojure")`, `(filter 2 "+two words")`, or `(filter 2 "off" "+clojure")`
- **THEN** the bot returns usage guidance and leaves the Selection Rule unchanged

## MODIFIED Requirements

### Requirement: Help retains the Telegram start entry point
The bot SHALL respond to both `(start)` and exact `/start` with equivalent generated help listing all five commands with Russian descriptions and s-expression invocation examples. Commands without arguments SHALL be shown without parameter placeholders.

#### Scenario: Telegram Start button
- **WHEN** Telegram sends `/start`
- **THEN** the bot shows the same help as `(start)`, including s-expression examples for add, tasks, delete, and filter
- **AND** start and tasks have no parameter placeholders

### Requirement: Slash-command compatibility
The bot SHALL retain exact `/start` as the only supported external slash-command. Task operations SHALL use the s-expression syntax while preserving ownership and chat restrictions. Former slash-commands, ordinary text, and updates without message text SHALL be ignored. Invalid s-expressions SHALL receive a format error or s-expression usage guidance without Task mutations.

#### Scenario: Existing Task operations
- **WHEN** a Task owner sends valid `(add "https://t.me/channel")`, `(tasks)`, `(delete 2)`, or `(filter 2 "off")` in a permitted chat
- **THEN** the corresponding operation retains its existing behavior and ownership restrictions

#### Scenario: Invalid arguments
- **WHEN** a user sends `(delete "invalid")` or `(filter 2 "clojure")`
- **THEN** the bot returns s-expression usage guidance without modifying Tasks

#### Scenario: Similar names and unsupported forms
- **WHEN** a user sends `/tasks extra`, `/start extra`, `/tasksx`, or `/unknown`
- **THEN** the bot does not execute a command or send a message

#### Scenario: Retired slash-commands
- **WHEN** a user sends `/add https://t.me/channel`, `/tasks`, `/delete 2`, or `/filter 2 off`
- **THEN** the bot does not execute the corresponding command

#### Scenario: Ordinary text or non-text update
- **WHEN** the bot receives ordinary conversation text or an update without message text
- **THEN** it does not execute a command

### Requirement: Help explains command parameters
Help SHALL identify the quoted channel link for add and the one-based Task list position for delete and filter. It SHALL show repeated quoted signed keywords and the alternative `(filter 2 "off")`, explaining that `+` requires a word, `-` excludes a word, and `off` disables the rule. Help and usage guidance SHALL use s-expression syntax.

#### Scenario: Discover parameter syntax
- **WHEN** a user requests help
- **THEN** add shows `(add "https://t.me/<канал>")`, delete shows `(delete 2)`, and tasks explains where these positions come from

#### Scenario: Discover filter forms
- **WHEN** a user reads filter help
- **THEN** it shows `(filter 2 "+слово" "-слово")` and `(filter 2 "off")`
- **AND** the description explains the meaning of the signs and disabling the rule

#### Scenario: Empty channel argument
- **WHEN** a user sends `(add "")` or `(add "   ")` in a private chat
- **THEN** the bot returns usage guidance with a quoted channel link in s-expression syntax and does not add a Task
