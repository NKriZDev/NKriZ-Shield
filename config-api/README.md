# Config API (PM2)

Minimal config server that returns AES-256-GCM encrypted subscriptions.

## Files
- `configs.txt` stores VLESS links (one per line).
- `server.js` serves `/health` and `/configs`.
- `ecosystem.config.js` is the PM2 app definition.

## Environment variables
- `PORT` (default `8081`)
- `CONFIG_API_TOKEN` (must match the app)
- `CONFIG_ENC_KEY_B64` (base64, 32 bytes; must match the app)
- `CONFIG_PAYLOAD_PATH` (default `configs.txt`)

## Run
```bash
npm install
pm2 start ecosystem.config.js
```

## Notes
- If you change the token, key, or URL, update the constants in
  `V2rayNG/app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt`.
- This runs on a dedicated port and does not touch your website ports.
- The payload is encrypted, but requests are still HTTP by default; use firewall
  rules or a private IP if you want extra protection.
