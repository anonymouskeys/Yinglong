#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "third_party/sstpClient")

def patch(path, old, new, label):
    p = root / path
    s = p.read_text(encoding="utf-8")
    if new in s:
        return
    if old not in s:
        raise SystemExit(f"SSTP patch anchor missing: {label}: {p}")
    p.write_text(s.replace(old, new, 1), encoding="utf-8")

patch(
    "build.gradle",
    "plugins {\n    id 'com.android.library'\n}",
    "plugins {\n    id 'com.android.library'\n    id 'org.jetbrains.kotlin.android'\n}",
    "kotlin android plugin",
)

patch(
    "src/main/java/kittoku/osc/service/SstpVpnService.kt",
    "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        return when",
    "override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        beForegrounded()\n        return when",
    "foreground service restart",
)

patch(
    "src/main/java/kittoku/osc/service/SstpVpnService.kt",
    "                mWasConnected = false\n\n                beForegrounded()",
    "                mWasConnected = false\n                setStringPrefValue(\"CONNECTING\", OscPrefKey.HOME_STATUS, prefs)",
    "connecting status",
)

patch(
    "src/main/java/kittoku/osc/service/SstpVpnService.kt",
    "                if (connectedIp != \"\") {\n                    mWasConnected = true",
    "                if (connectedIp != \"\") {\n                    setStringPrefValue(\"CONNECTED\", OscPrefKey.HOME_STATUS, prefs)\n                    mWasConnected = true",
    "connected status",
)

patch(
    "src/main/java/kittoku/osc/service/SstpVpnService.kt",
    "    internal fun notifyError(message: String) {\n        if (mWasConnected) {",
    "    internal fun notifyError(message: String) {\n        setStringPrefValue(message, OscPrefKey.HOME_STATUS, prefs)\n        if (mWasConnected) {",
    "error status",
)

old_verify = (
    "        if (getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY, bridge.prefs)) {\n"
    "            HttpsURLConnection.getDefaultHostnameVerifier().also {\n"
    "                if (!it.verify(sslHostname, engine.session)) {\n"
    "                    bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_VERIFICATION_FAILED))\n"
    "                    return false\n"
    "                }\n"
    "            }\n"
    "        }\n"
)
new_verify = (
    "        if (getBooleanPrefValue(OscPrefKey.SSL_DO_VERIFY, bridge.prefs)) {\n"
    "            val verifyHostname =\n"
    "                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&\n"
    "                    getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, bridge.prefs)) {\n"
    "                    getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, bridge.prefs)\n"
    "                } else {\n"
    "                    sslHostname\n"
    "                }\n"
    "            HttpsURLConnection.getDefaultHostnameVerifier().also {\n"
    "                if (!it.verify(verifyHostname, engine.session)) {\n"
    "                    bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.ERR_VERIFICATION_FAILED))\n"
    "                    return false\n"
    "                }\n"
    "            }\n"
    "        }\n"
)
patch(
    "src/main/java/kittoku/osc/terminal/SSLTerminal.kt",
    old_verify,
    new_verify,
    "TLS hostname verification",
)

# Send the same logical host in the HTTP Host header when custom SNI is used.
old_http = (
    "    private suspend fun establishHttp(): Boolean {\n"
    "        val buffer = ByteBuffer.allocate(getApplicationBufferSize())\n\n"
    "        val request = arrayOf(\n"
    "            \"SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\",\n"
    "            \"Content-Length: 18446744073709551615\",\n"
    "            \"Host: $sslHostname\",\n"
)
new_http = (
    "    private suspend fun establishHttp(): Boolean {\n"
    "        val buffer = ByteBuffer.allocate(getApplicationBufferSize())\n"
    "        val httpHostname =\n"
    "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&\n"
    "                getBooleanPrefValue(OscPrefKey.SSL_DO_USE_CUSTOM_SNI, bridge.prefs)) {\n"
    "                getStringPrefValue(OscPrefKey.SSL_CUSTOM_SNI, bridge.prefs)\n"
    "            } else {\n"
    "                sslHostname\n"
    "            }\n\n"
    "        val request = arrayOf(\n"
    "            \"SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\",\n"
    "            \"Content-Length: 18446744073709551615\",\n"
    "            \"Host: $httpHostname\",\n"
)
patch(
    "src/main/java/kittoku/osc/terminal/SSLTerminal.kt",
    old_http,
    new_http,
    "SSTP HTTP host",
)

print("SSTP module patched")
