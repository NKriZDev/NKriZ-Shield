const express = require("express");
const crypto = require("crypto");
const fs = require("fs/promises");
const path = require("path");

const app = express();

const port = parseInt(process.env.PORT || "8081", 10);
const token = process.env.CONFIG_API_TOKEN || "";
const keyB64 = process.env.CONFIG_ENC_KEY_B64 || "";
const payloadPath = process.env.CONFIG_PAYLOAD_PATH || "configs.txt";

// Cache for encrypted payload
let cachedEncrypted = null;
let cachedFileMtime = null;

const resolvePayloadPath = () => {
  if (path.isAbsolute(payloadPath)) {
    return payloadPath;
  }
  return path.join(__dirname, payloadPath);
};

const encryptPayload = (plaintext) => {
  const key = Buffer.from(keyB64, "base64");
  if (key.length !== 32) {
    throw new Error("CONFIG_ENC_KEY_B64 must be 32 bytes (base64)");
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, ciphertext, tag]).toString("base64");
};

const requireAuth = (req, res, next) => {
  if (!token) {
    res.status(500).json({ error: "CONFIG_API_TOKEN not set" });
    return;
  }
  const auth = req.get("authorization") || "";
  if (auth !== `Bearer ${token}`) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  next();
};

app.get("/health", (req, res) => {
  res.json({ ok: true });
});

app.get("/configs", requireAuth, async (req, res) => {
  try {
    const filePath = resolvePayloadPath();
    const stats = await fs.stat(filePath);
    
    // Check if file has changed
    if (cachedEncrypted && cachedFileMtime && stats.mtimeMs === cachedFileMtime) {
      // Return cached version
      res.json({ enc: cachedEncrypted, v: 1 });
      return;
    }
    
    // Read and encrypt
    const text = await fs.readFile(filePath, "utf8");
    const enc = encryptPayload(text);
    
    // Cache the result
    cachedEncrypted = enc;
    cachedFileMtime = stats.mtimeMs;
    
    res.json({ enc, v: 1 });
  } catch (err) {
    console.error("Failed to serve configs", err);
    res.status(500).json({ error: "failed" });
  }
});

app.listen(port, "0.0.0.0", () => {
  console.log(`config-api listening on ${port}`);
});
