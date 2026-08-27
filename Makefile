WRANGLER_DIR := .wrangler
BIN_DIR := $(WRANGLER_DIR)/bin

.PHONY: test
test: build
	@ cd $(WRANGLER_DIR) && node --test bin/test/main_test.js

.PHONY: build
build:
	@ mkdir -p $(BIN_DIR)
	@ ly2k --target eval < build.clj > $(BIN_DIR)/Makefile
	@ $(MAKE) -f $(BIN_DIR)/Makefile > /dev/null
	@ cp $$LY2K_PACKAGES_DIR/prelude/1.0.0/js/language_runtime.js $(BIN_DIR)/src
	@ cp $$LY2K_PACKAGES_DIR/prelude/1.0.0/js/language_runtime.js $(BIN_DIR)/test

.PHONY: clean
clean:
	@ rm -rf $(BIN_DIR)

.PHONY: dev
dev:
	@ set -eu; \
	set -a; . $(WRANGLER_DIR)/.dev.vars; set +a; \
	: "$${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN must be set}"; \
	: "$${TELEGRAM_WEBHOOK_SECRET:?TELEGRAM_WEBHOOK_SECRET must be set}"; \
	ngrok http 8787 --log=stdout & ngrok_pid=$$!; \
	trap 'kill $$ngrok_pid 2>/dev/null || true' EXIT INT TERM; \
	for _ in $$(seq 1 50); do \
		tunnel_url=$$({ curl -fsS http://127.0.0.1:4040/api/tunnels 2>/dev/null || true; } | node -e 'let body=""; process.stdin.on("data", chunk => body += chunk).on("end", () => { if (!body) return; const tunnel = JSON.parse(body).tunnels.find(({ public_url }) => public_url.startsWith("https://")); if (tunnel) process.stdout.write(tunnel.public_url); })'); \
		test -n "$$tunnel_url" && break; \
		sleep 0.1; \
	done; \
	: "$${tunnel_url:?ngrok did not start}"; \
	TELEGRAM_WEBHOOK_URL="$$tunnel_url" node -e 'void (async () => { const response = await fetch(`https://api.telegram.org/bot$${process.env.TELEGRAM_BOT_TOKEN}/setWebhook`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ url: process.env.TELEGRAM_WEBHOOK_URL, secret_token: process.env.TELEGRAM_WEBHOOK_SECRET }) }); const result = await response.json(); if (!response.ok || !result.ok) throw new Error(result.description || "setWebhook failed"); })()'; \
	cd $(WRANGLER_DIR) && wrangler dev
