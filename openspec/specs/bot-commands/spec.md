# bot-commands Specification

## Purpose

Let Telegram users discover supported slash-commands and their parameters while preserving existing Task management behavior.

## Requirements

### Requirement: Help retains the Telegram start entry point
The bot SHALL respond to exact `/start` with generated help listing `/start`, `/add`, `/tasks`, `/delete`, and `/filter`, their parameter notation, and Russian descriptions, without concrete invocation examples. Commands without arguments SHALL be shown without parameter placeholders.

#### Scenario: Telegram Start button
- **WHEN** Telegram sends `/start`
- **THEN** the bot shows all five supported slash-commands with descriptions and parameter notation for add, delete, and filter
- **AND** start and tasks have no parameter placeholders

### Requirement: Slash-command compatibility
The bot SHALL preserve existing slash-command syntax, argument checks, usage responses, Task ownership, and chat restrictions. Unknown commands, ordinary text, and updates without message text SHALL remain ignored. Adding command documentation SHALL NOT change which argument values are accepted.

#### Scenario: Existing Task operations
- **WHEN** a Task owner sends valid `/add`, `/tasks`, `/delete`, or `/filter` commands with their existing arguments in a permitted chat
- **THEN** the corresponding operation retains its existing behavior and ownership restrictions

#### Scenario: Invalid arguments
- **WHEN** a user sends `/delete invalid` or `/filter 2 clojure`
- **THEN** the bot returns the existing usage response without modifying Tasks

#### Scenario: Similar names and unsupported forms
- **WHEN** a user sends `/tasks extra`, `/start extra`, `/tasksx`, `/unknown`, or `(tasks)`
- **THEN** the bot does not execute a command or send a message

#### Scenario: Ordinary text or non-text update
- **WHEN** the bot receives ordinary conversation text or an update without message text
- **THEN** it does not execute a command

### Requirement: Help explains command parameters
Help SHALL identify the channel link for add and the list position for delete and filter. It SHALL describe repeated signed filter words and the alternative `off` form, explaining that `+` requires a word, `-` excludes a word, and `off` disables the rule. Parameter notation SHALL use the existing slash-command syntax without introducing string-literal quotes.

#### Scenario: Discover parameter syntax
- **WHEN** a user requests help
- **THEN** add shows `https://t.me/<канал>`, delete shows a list-position placeholder, and tasks explains where these positions come from

#### Scenario: Discover filter forms
- **WHEN** a user reads filter help
- **THEN** its parameter notation is `ID +слово | -слово... | номер off`
- **AND** the description explains the meaning of the signs and disabling the rule
