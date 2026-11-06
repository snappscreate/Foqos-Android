# Foqos AI activity test relay

Optional backend for the Foqos **Settings → AI活動認識テスト** page.
The offline pose mode works with no API setup.

For a **private, personal development test**, the screen also offers
「直接API（テスト専用）」: enter a Gemini API key at runtime. The app does not
write that key to disk or ship it in the APK, and discards it when leaving the
screen. This direct mode calls `gemini-3.5-flash` and estimates yen using
the published paid standard tariff and 160 JPY/USD; actual billed cost may
differ. **Do not distribute this test mode as a publicly deployed app.**

For regular use, select 「HTTPS中継」 and deploy the relay below. Android APKs
cannot securely hide an embedded API key.

## Deploy (Cloudflare Workers)

1. Install Node.js, create a Cloudflare account and authenticate:
   `npx wrangler login`
2. From `tools/activity-relay` run `npx wrangler deploy`.
3. Add secrets using the Wrangler prompts:
   `npx wrangler secret put GEMINI_API_KEY`
   `npx wrangler secret put LAB_TOKEN` (use a long random value).
4. Open Foqos → 設定 → AI活動認識テスト. Enter your Worker's
   `https://...workers.dev` URL and the `LAB_TOKEN`. They stay in memory
   only while the test screen is open.
5. Switch to 「HTTPS中継」, choose Gemini・静止画 or Gemini・動画 and run the capture.

Model can be changed with `GEMINI_MODEL` in `wrangler.jsonc`; use a Gemini
model that supports image/video inputs and structured JSON output. To display
**estimated** yen cost, set `INPUT_USD_PER_M`, `OUTPUT_USD_PER_M`,
and `USD_JPY` as Wrangler vars matching your model's current API pricing.
The token counts are returned from Gemini's usage metadata, not guessed.

**Privacy/security:** The Worker code does not persist media and requires a bearer
token, but media is sent to Cloudflare and Google. Keep the secret and URL private,
prefer a dedicated Google API key with a budget/quota, and disable/delete the
Worker when testing is over. A personal token is not a replacement for
per-user authentication on a publicly distributed app.

The Worker accepts POST JSON with `activity`, `mode` (`photos` or `video`),
plus either `frames` (timestamped Base64 JPEG) or `video` (Base64 MP4). It
returns `status` (`一致`/`不一致`/`判断不能`), `reason`, `evidence`,
`inputTokens`, `outputTokens`, and optional `estimatedYen`.

Media is stored temporarily in Android's cache and deleted on screen exit
or before a new run. This feature is **diagnostic only** and has no connection
to the blocking/unblocking policy.
