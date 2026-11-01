import { spawn } from "node:child_process";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline";
import { stripVTControlCharacters } from "node:util";

let child;
let origin;
let lines;

export async function listen() {
  // Merge console.log/error into one stream so the completion log follows all errors.
  child = spawn("sh", ["-c", 'exec wrangler "$@" 2>&1', "wrangler",
    "dev", "--config", fileURLToPath(new URL("wrangler.toml", import.meta.url)),
    "--local", "--test-scheduled", "--log-level", "log",
    "--ip", "127.0.0.1", "--port", "0", "--inspector-port", "0",
  ], { stdio: ["ignore", "pipe", "pipe", "ipc"],
    env: { ...process.env, WRANGLER_SEND_METRICS: "false" } });
  let output = "";
  child.stdout.on("data", chunk => { output += chunk; });
  child.stderr.on("data", chunk => { output += chunk; });
  lines = createInterface({ input: child.stdout });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Wrangler startup timed out\n${output}`)), 30000);
    const finish = error => {
      clearTimeout(timeout);
      child.off("error", finish);
      child.off("exit", onExit);
      child.off("message", onMessage);
      if (error) reject(error);
      else resolve();
    };
    const onExit = code => finish(new Error(`Wrangler exited (${code})\n${output}`));
    const onMessage = message => {
      const ready = typeof message === "string" ? JSON.parse(message) : message;
      if (ready.event === "DEV_SERVER_READY") {
        origin = `http://${ready.ip}:${ready.port}`;
        finish();
      }
    };
    child.once("error", finish);
    child.once("exit", onExit);
    child.on("message", onMessage);
  });
}

export async function close() {
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const exited = once(child, "exit");
    child.kill();
    await exited;
  }
}

export function fetch(path, options) {
  return globalThis.fetch(new URL(path, origin), options);
}

export async function scheduled() {
  const errors = [];
  let started = false;
  let onLine;
  let timeout;
  const completed = new Promise((resolve, reject) => {
    timeout = setTimeout(() => reject(new Error("Missing scheduled completion log")), 10000);
    onLine = line => {
      line = stripVTControlCharacters(line).trim();
      if (started && line.includes("[ERROR]")) errors.push(line);
      if (!line.startsWith('{"event":')) return;
      const entry = JSON.parse(line);
      if (entry.event === "scheduled_started") started = true;
      if (entry.event === "scheduled_effects") resolve({ effects: entry.effects, errors });
    };
    lines.on("line", onLine);
  });
  try {
    const [response, result] = await Promise.all([
      fetch("/__scheduled?cron=*+*+*+*+*", { signal: AbortSignal.timeout(10000) }),
      completed,
    ]);
    if (!response.ok) throw new Error(`Scheduled event failed: ${response.status}`);
    return result;
  } finally {
    clearTimeout(timeout);
    lines.off("line", onLine);
  }
}
