module.exports = {
  apps: [
    {
      name: "nkriz-config-api",
      script: "server.js",
      cwd: __dirname,
      env: {
        PORT: "8081",
        CONFIG_API_TOKEN: "7c1a7d0b8861f78538584047acb33442cb05283e",
        CONFIG_ENC_KEY_B64: "wAq6f83NaIsdfsKh2nOgIgJdVTQBESze2GMOcsB7fJQ=",
        CONFIG_PAYLOAD_PATH: "configs.txt"
      }
    }
  ]
};
