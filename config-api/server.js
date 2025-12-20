"use strict";

const crypto = require("crypto");
const express = require("express");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT || 8081);
const TOKEN = process.env.CONFIG_API_TOKEN || "";
const KEY_B64 = process.env.CONFIG_ENC_KEY_B64 || "";
const PAYLOAD_PATH =
  process.env.CONFIG_PAYLOAD_PATH || path.join(__dirname, "configs.txt");

function loadKey() {
  if (!KEY_B64) {
    throw new Error("CONFIG_ENC_KEY_B64 is required");
  }
  const key = Buffer.from(KEY_B64, "base64");
  if (key.length !== 32) {
    throw new Error("CONFIG_ENC_KEY_B64 must decode to 32 bytes");
  }
  return key;
}

let KEY;
try {
  if (!TOKEN) {
    throw new Error("CONFIG_API_TOKEN is required");
  }
  KEY = loadKey();
} catch (err) {
  console.error(err.message);
  process.exit(1);
}

function encryptPayload(payload) {
  const nonce = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", KEY, nonce);
  const ciphertext = Buffer.concat([cipher.update(payload), cipher.final()]);
  const tag = cipher.getAuthTag();
  const data = Buffer.concat([ciphertext, tag]);
  return { nonce, data };
}

const app = express();

app.get("/health", (_req, res) => {
  res.json({ status: "ok" });
});

app.get("/configs", (req, res) => {
  const auth = req.get("authorization") || "";
  if (!auth.startsWith("Bearer ")) {
    return res.sendStatus(401);
  }
  const token = auth.slice("Bearer ".length).trim();
  if (token !== TOKEN) {
    return res.sendStatus(403);
  }

  let payload;
  try {
    payload = fs.readFileSync(PAYLOAD_PATH);
  } catch (_err) {
    return res.status(500).json({ error: "payload_unavailable" });
  }

  const { nonce, data } = encryptPayload(payload);
  res.setHeader("Cache-Control", "no-store");
  return res.json({
    nonce: nonce.toString("base64"),
    data: data.toString("base64"),
  });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`config-api listening on ${PORT}`);
});
