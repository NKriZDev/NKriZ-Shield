package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.util.Log
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.HY2
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.net.URI
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AngConfigManager {


    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = V2rayConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                val config = MmkvManager.decodeServerConfig(guid)
                if (config?.configType == EConfigType.HYSTERIA2) {
                    val socksPort = Utils.findFreePort(listOf(100 + SettingsManager.getSocksPort(), 0))
                    val hy2Config = Hysteria2Fmt.toNativeConfig(config, socksPort)
                    Utils.setClipboard(context, JsonUtil.toJsonPretty(hy2Config) + "\n" + result.content)
                    return 0
                }
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.CUSTOM -> ""
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.HTTP -> ""
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid)
        }

        var countSub = parseBatchSubscription(server)
        if (countSub <= 0) {
            countSub = parseBatchSubscription(Utils.decode(server))
        }
        if (countSub > 0) {
            updateConfigViaSubAll()
        }

        return count to countSub
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            val removedSelectedServer =
                if (!TextUtils.isEmpty(subid) && !append) {
                    MmkvManager.decodeServerConfig(
                        MmkvManager.getSelectServer().orEmpty()
                    )?.let {
                        if (it.subscriptionId == subid) {
                            return@let it
                        }
                        return@let null
                    }
                } else {
                    null
                }
            if (!append) {
                MmkvManager.removeServerViaSubid(subid)
            }

            val subItem = MmkvManager.decodeSubscription(subid)
            var count = 0
            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    val resId = parseConfig(it, subid, subItem, removedSelectedServer)
                    if (resId == 0) {
                        count++
                    }
                }
            return count
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java)

                if (serverList.isNotEmpty()) {
                    var count = 0
                    for (srv in serverList.reversed()) {
                        val config = CustomFmt.parse(JsonUtil.toJson(srv)) ?: continue
                        config.subscriptionId = subid
                        val key = MmkvManager.encodeServerConfig("", config)
                        MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(srv) ?: "")
                        count += 1
                    }
                    return count
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server) ?: return 0
                config.subscriptionId = subid
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server) ?: return R.string.toast_incorrect_protocol
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @param removedSelectedServer The removed selected server.
     * @return The result code.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?,
        removedSelectedServer: ProfileItem?
    ): Int {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return R.string.toast_none_data
            }

            val config = if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
                VmessFmt.parse(str)
            } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
                ShadowsocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
                SocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
                TrojanFmt.parse(str)
            } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
                VlessFmt.parse(str)
            } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
                WireguardFmt.parse(str)
            } else if (str.startsWith(EConfigType.HYSTERIA2.protocolScheme) || str.startsWith(HY2)) {
                Hysteria2Fmt.parse(str)
            } else {
                null
            }

            if (config == null) {
                return R.string.toast_incorrect_protocol
            }
            //filter
            if (subItem?.filter != null && subItem.filter?.isNotEmpty() == true && config.remarks.isNotEmpty()) {
                val matched = Regex(pattern = subItem.filter ?: "")
                    .containsMatchIn(input = config.remarks)
                if (!matched) return -1
            }

            config.subscriptionId = subid
            val guid = MmkvManager.encodeServerConfig("", config)
            if (removedSelectedServer != null &&
                config.server == removedSelectedServer.server && config.serverPort == removedSelectedServer.serverPort
            ) {
                MmkvManager.setSelectServer(guid)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse config", e)
            return -1
        }
        return 0
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return The number of configurations updated.
     */
    fun updateConfigViaSubAll(): Int {
        var count = 0
        try {
            MmkvManager.decodeSubscriptions().forEach {
                count += updateConfigViaSub(it)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            return 0
        }
        return count
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return The number of configurations updated.
     */
    fun updateConfigViaSub(it: Pair<String, SubscriptionItem>): Int {
        try {
            // allow update even if remarks/url are empty; only skip if disabled
            if (!it.second.enabled) {
                return 0
            }
            val subId = SERVER_SUB_ID
            ensureHardcodedSubscription()
            Log.i(AppConfig.TAG, "Using hardcoded subscription: $SERVER_SUB_URL")
            val userAgent = it.second.userAgent

            // Use direct connection (port 0) - no need to route through local proxy for config fetching
            val configText = fetchSubscriptionContent(SERVER_SUB_URL, userAgent, 0)
            if (configText.isEmpty()) {
                return 0
            }
            val decrypted = tryDecryptEncryptedPayload(configText)
            if (decrypted != null) {
                return parseConfigViaSub(decrypted, subId, false)
            }

            val decoded = tryDecodeSignedPayload(configText)
            if (decoded != null) {
                return parseConfigViaSub(decoded, subId, false)
            }

            val extracted = tryExtractPayloadFromJson(configText)
            if (extracted != null) {
                val count = parseConfigViaSub(extracted, subId, false)
                if (count > 0) {
                    return count
                }
            }

            return parseConfigViaSub(configText, subId, false)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return 0
        }
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid)
        }
        return count
    }

    /**
     * Parses already-decoded subscription content (no base64 decode).
     */
    private fun parseConfigViaSubDecoded(server: String?, subid: String, append: Boolean): Int {
        var count = parseBatchConfig(server, subid, append)
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid)
        }
        return count
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.second.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    /**
     * Creates an intelligent selection configuration based on multiple server configurations.
     *
     * @param context The application context used for configuration generation.
     * @param guidList The list of server GUIDs to be included in the intelligent selection.
     *                 Each GUID represents a server configuration that will be combined.
     * @param subid The subscription ID to associate with the generated configuration.
     *              This helps organize the configuration under a specific subscription.
     * @return The GUID key of the newly created intelligent selection configuration,
     *         or null if the operation fails (e.g., empty guidList or configuration parsing error).
     */
    fun createIntelligentSelection(
        context: Context,
        guidList: List<String>,
        subid: String
    ): String? {
        if (guidList.isEmpty()) {
            return null
        }
        val result = V2rayConfigManager.genV2rayConfig(context, guidList) ?: return null
        val config = CustomFmt.parse(JsonUtil.toJson(result)) ?: return null
        config.subscriptionId = subid
        val key = MmkvManager.encodeServerConfig("", config)
        MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(result) ?: "")
        return key
    }

    //region signed subscription helpers

    private const val SERVER_TOKEN = "7c1a7d0b8861f78538584047acb33442cb05283e"
    private const val SERVER_SUB_URL = "http://api.nkriz.ir:8081/configs"
    private const val SERVER_ENC_KEY_B64 = "wAq6f83NaIsdfsKh2nOgIgJdVTQBESze2GMOcsB7fJQ="
    private const val SERVER_PUBKEY_B64 = ""
    const val SERVER_SUB_ID = "NK_SUB"
    private const val SERVER_SUB_REMARK = "NKriZ.ir"

    private fun fetchSubscriptionContent(url: String, userAgent: String?, httpPort: Int): String {
        try {
            val conn = HttpUtil.createProxyConnection(url, httpPort, 5000, 5000, false) ?: return ""
            val finalUserAgent = if (userAgent.isNullOrBlank()) {
                "v2rayNG/${BuildConfig.VERSION_NAME}"
            } else {
                userAgent
            }
            conn.setRequestProperty("User-agent", finalUserAgent)
            conn.setRequestProperty("Authorization", "Bearer $SERVER_TOKEN")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return ""
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            return text
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to fetch subscription content", e)
        }
        return ""
    }

    /**
     * Attempts to decrypt an AES-256-GCM payload.
     * Expects JSON with base64 field: enc (nonce + ciphertext + tag).
     */
    private fun tryDecryptEncryptedPayload(jsonText: String): String? {
        val trimmed = jsonText.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            val obj = JsonUtil.parseString(trimmed) ?: return null
            val keyBytes = decodeBase64Bytes(SERVER_ENC_KEY_B64) ?: return null
            if (keyBytes.size != 32) {
                Log.w(AppConfig.TAG, "Invalid encryption key length")
                return null
            }

            val encB64 = obj.get("enc")?.asString
            if (!encB64.isNullOrBlank()) {
                val encBytes = decodeBase64Bytes(encB64) ?: return null
                if (encBytes.size < 28) {
                    return null
                }
                val gcmIvSizes = listOf(12, 16)
                for (ivSize in gcmIvSizes) {
                    if (encBytes.size <= ivSize + 16) {
                        continue
                    }
                    val iv = encBytes.copyOfRange(0, ivSize)
                    val cipherAndTag = encBytes.copyOfRange(ivSize, encBytes.size)
                    val gcm = decryptAesGcm(cipherAndTag, iv, keyBytes)
                    if (gcm != null) {
                        return gcm
                    }
                }

                if (encBytes.size > 16) {
                    val iv = encBytes.copyOfRange(0, 16)
                    val cipherBytes = encBytes.copyOfRange(16, encBytes.size)
                    val cbc = decryptAesCbc(cipherBytes, iv, keyBytes)
                    if (cbc != null) {
                        return cbc
                    }
                }
            }

            val ivB64 = obj.get("iv")?.asString ?: obj.get("nonce")?.asString
            val dataB64 = obj.get("ciphertext")?.asString ?: obj.get("data")?.asString
            val tagB64 = obj.get("tag")?.asString
            if (!ivB64.isNullOrBlank() && !dataB64.isNullOrBlank()) {
                val iv = decodeBase64Bytes(ivB64) ?: return null
                val cipherBytes = decodeBase64Bytes(dataB64) ?: return null
                val cipherAndTag = if (!tagB64.isNullOrBlank()) {
                    val tagBytes = decodeBase64Bytes(tagB64) ?: return null
                    cipherBytes + tagBytes
                } else {
                    cipherBytes
                }
                val gcm = decryptAesGcm(cipherAndTag, iv, keyBytes)
                if (gcm != null) {
                    return gcm
                }
                if (tagB64.isNullOrBlank() && iv.size == 16) {
                    val cbc = decryptAesCbc(cipherBytes, iv, keyBytes)
                    if (cbc != null) {
                        return cbc
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to decrypt encrypted payload", e)
            null
        }
    }

    private fun decryptAesGcm(cipherAndTag: ByteArray, iv: ByteArray, keyBytes: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val plainBytes = cipher.doFinal(cipherAndTag)
            plainBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "AES-GCM decrypt failed", e)
            null
        }
    }

    private fun decryptAesCbc(cipherBytes: ByteArray, iv: ByteArray, keyBytes: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val spec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            plainBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "AES-CBC decrypt failed", e)
            null
        }
    }

    private fun decodeBase64Bytes(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        return try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (e: Exception) {
            try {
                Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Attempts to extract a plain payload from a JSON response.
     */
    private fun tryExtractPayloadFromJson(jsonText: String): String? {
        val trimmed = jsonText.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            val obj = JsonUtil.parseString(trimmed) ?: return null
            val keys = listOf("payload", "configs", "data", "text", "content", "enc")
            for (key in keys) {
                val value = obj.get(key)?.asString
                if (!value.isNullOrBlank()) {
                    return value
                }
            }
            null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to extract payload from JSON", e)
            null
        }
    }

    /**
     * Attempts to decode a signed subscription payload.
     * Expects JSON with base64 fields: payload, signature (Ed25519), and optional pubkey.
     * Uses the hardcoded server pubkey for verification.
     *
     * @return decoded payload text if verification succeeds, otherwise null.
     */
    private fun tryDecodeSignedPayload(jsonText: String): String? {
        val trimmed = jsonText.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            val obj = JsonUtil.parseString(trimmed) ?: return null
            val payloadB64 = obj.get("payload")?.asString ?: return null
            val signatureB64 = obj.get("signature")?.asString ?: return null

            val payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT)
            val signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            val pubKeyBytes = Base64.decode(SERVER_PUBKEY_B64, Base64.DEFAULT)

            val verified = verifyEd25519(payloadBytes, signatureBytes, pubKeyBytes)
            if (!verified) {
                Log.w(AppConfig.TAG, "Signed payload verification failed")
                return null
            }
            payloadBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to decode signed payload", e)
            null
        }
    }

    private fun verifyEd25519(payload: ByteArray, signature: ByteArray, pubKeyBytes: ByteArray): Boolean {
        return try {
            val spec = EdDSAPublicKeySpec(pubKeyBytes, EdDSANamedCurveTable.getByName("Ed25519"))
            val pubKey = EdDSAPublicKey(spec)
            val verifier = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
            verifier.initVerify(pubKey)
            verifier.update(payload)
            verifier.verify(signature)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Ed25519 verification failed", e)
            false
        }
    }

    fun ensureHardcodedSubscription() {
        val existing = MmkvManager.decodeSubscription(SERVER_SUB_ID)
        if (existing?.url == SERVER_SUB_URL) {
            return
        }
        val subItem = SubscriptionItem().apply {
            remarks = SERVER_SUB_REMARK
            url = SERVER_SUB_URL
            enabled = true
            allowInsecureUrl = false
        }
        MmkvManager.encodeSubscription(SERVER_SUB_ID, subItem)
    }

    //endregion
}
