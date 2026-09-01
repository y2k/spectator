DROP TABLE tasks;

CREATE TABLE tasks (
  id INTEGER PRIMARY KEY,
  telegram_user_id TEXT NOT NULL,
  text TEXT NOT NULL,
  cursor INTEGER NOT NULL,
  UNIQUE (telegram_user_id, text)
);

CREATE INDEX tasks_by_telegram_user_id ON tasks (telegram_user_id, id);
