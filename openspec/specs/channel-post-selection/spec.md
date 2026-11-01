# channel-post-selection Specification

## Purpose

Select Channel Posts for Task owners using Task-specific keyword rules, including media captions and poll questions, without allowing unavailable text to bypass a configured rule.

## Requirements

### Requirement: Tasks without a Selection Rule accept all posts
A Task without a Selection Rule SHALL accept every new Channel Post regardless of whether analysable text is available.

#### Scenario: Unfiltered media and polls
- **WHEN** a Task without a Selection Rule observes a photo without a caption or a poll
- **THEN** the system sends a Channel notification to the Task owner

### Requirement: Configured rules require available text
A Task with a Selection Rule SHALL reject a Channel Post when its analysable text is unavailable, empty, or whitespace-only. This SHALL apply to required-only, prohibited-only, and mixed keyword rules.

#### Scenario: Photo without a caption
- **WHEN** a Task with a Selection Rule observes a photo without a caption
- **THEN** no Channel notification is sent for that post

#### Scenario: Prohibited-only rule does not bypass missing text
- **WHEN** a Task with only prohibited keywords observes a post with no available text
- **THEN** no Channel notification is sent for that post

#### Scenario: Empty or unanalysable text
- **WHEN** a Task with a Selection Rule observes a post whose text is empty, whitespace-only, or cannot be decoded for analysis
- **THEN** no Channel notification is sent for that post

### Requirement: Poll selection uses only the question
The system SHALL apply a Task's Selection Rule to the available poll question. Poll answer options, voting statistics, reactions, and surrounding interface text SHALL NOT participate in keyword matching.

#### Scenario: Required keyword in the question
- **WHEN** a poll question contains a required keyword and no prohibited keyword
- **THEN** the poll matches the Selection Rule

#### Scenario: Required keyword only in an answer
- **WHEN** a required-only Selection Rule has no match in the question but has a match in an answer option
- **THEN** the poll does not match the Selection Rule

#### Scenario: Prohibited keyword only in an answer
- **WHEN** the question satisfies the Selection Rule and a prohibited keyword appears only in an answer option
- **THEN** the poll matches the Selection Rule

#### Scenario: Prohibited keyword in the question
- **WHEN** the question contains a prohibited keyword, including when it also contains a required keyword
- **THEN** the poll does not match the Selection Rule

### Requirement: Available text uses existing keyword semantics
The system SHALL match ordinary text, media captions, and poll questions case-insensitively. A post SHALL match when at least one required keyword is present (or none are configured) and no prohibited keyword is present. Keywords SHALL retain punctuation, with boundaries determined by adjacent Unicode letters and digits.

#### Scenario: Caption obeys the configured rule
- **WHEN** a photo caption contains a required keyword and no prohibited keyword
- **THEN** the photo matches the Selection Rule

#### Scenario: Caption with a prohibited keyword
- **WHEN** a photo caption contains both a required keyword and a prohibited keyword
- **THEN** the photo does not match the Selection Rule

#### Scenario: Case and punctuation are preserved
- **WHEN** the required keyword is `C++` and the poll question contains `c++?` with no prohibited keyword
- **THEN** the poll matches the Selection Rule

### Requirement: Rejected posts advance processing
The system SHALL advance a Task's cursor past rejected Channel Posts without sending Channel notifications for them. This change SHALL NOT reprocess posts already covered by the cursor.

#### Scenario: Rejected post followed by a matching post
- **WHEN** a Task observes a rejected photo followed by a newer matching poll
- **THEN** the cursor advances past the photo, processing continues to the poll, and only the poll produces a Channel notification

#### Scenario: Existing cursor after deployment
- **WHEN** the updated selection behavior is deployed for an existing Task
- **THEN** posts at or below its stored cursor are not reconsidered
