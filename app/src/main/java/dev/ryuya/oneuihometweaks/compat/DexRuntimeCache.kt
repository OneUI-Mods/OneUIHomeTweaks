package dev.ryuya.oneuihometweaks.compat

import android.content.Context
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import org.luckypray.dexkit.DexKitBridge

/** Persistent cache for feature-specific call graphs; no bridge on a hit, including failures. */
object DexRuntimeCache {
    @Volatile private var hostContext: Context? = null
    private val systemUiProofs = ConcurrentHashMap<Pair<String, String>, Map<String, String?>>()

    fun exportProofs(): Array<Bundle> = systemUiProofs.map { (key, evidence) ->
        Bundle().apply {
            putString("apk", key.first)
            putString("rule", key.second)
            putByteArray("evidence", GenerationStore.encode(GenerationStore.Result("ready", evidence)))
        }
    }.toTypedArray()

    fun importProofs(snapshot: Any?) {
        for (entry in snapshot as? Array<*> ?: return) {
            val bundle = entry as? Bundle ?: continue
            val apk = bundle.getString("apk") ?: continue
            val rule = bundle.getString("rule") ?: continue
            val bytes = bundle.getByteArray("evidence") ?: continue
            val result = GenerationStore.decode(bytes)
            if (result.state == "ready") systemUiProofs[apk to rule] = result.bindings
        }
    }

    fun configure(context: Context) {
        hostContext = context
    }

    fun query(
        apk: String,
        rule: String,
        block: (DexKitBridge) -> Map<String, String?>,
    ): Map<String, String?> {
        val context = checkNotNull(hostContext) { "Discovery cache not configured" }
        val systemUi = context.packageName == "com.android.systemui"
        if (systemUi) systemUiProofs[apk to rule]?.let { return it }
        val uri = android.net.Uri.parse("content://${DexKitConfig.MODULE_ID}.dexkit/ready")
        val request =
            android.os.Bundle().apply {
                putString("apk", apk)
                putString("rule", rule)
            }
        val start =
            checkNotNull(
                context.contentResolver.call(uri, "feature_begin", context.packageName, request)
            )
        val token = start.getString("token")
        val result =
            if (token == null) {
                GenerationStore.decode(checkNotNull(start.getByteArray("snapshot")))
            } else {
                val explored =
                    try {
                        DexKitNative.load()
                        DexKitBridge.create(apk).use { bridge ->
                            bridge.setThreadNum(2)
                            GenerationStore.Result("ready", block(bridge))
                        }
                    } catch (error: Exception) {
                        discoveryFailure("failed", error)
                    } catch (error: LinkageError) {
                        discoveryFailure("native-unavailable", error)
                    }
                val bytes = GenerationStore.encode(explored)
                check(bytes.size <= 512 * 1024) { "Feature result too large" }
                request.putString("token", token)
                request.putByteArray("snapshot", bytes)
                val saved =
                    context.contentResolver.call(
                        uri,
                        "feature_finish",
                        context.packageName,
                        request,
                    )
                check(saved?.getBoolean("saved") == true) { "Discovery claim changed" }
                explored
            }
        check(result.state == "ready") {
            "Discovery unavailable for this version: ${result.state}" +
                (result.bindings["detail"]?.let { " ($it)" } ?: "")
        }
        if (systemUi) systemUiProofs[apk to rule] = result.bindings
        return result.bindings
    }

    private fun discoveryFailure(state: String, error: Throwable): GenerationStore.Result {
        val message = error.message?.take(280)?.let { ": $it" } ?: ""
        return GenerationStore.Result(state, mapOf("detail" to error.javaClass.simpleName + message))
    }

    fun list(values: Map<String, String?>, key: String): List<String> =
        (0 until (values["$key.count"]?.toInt() ?: 0)).map { checkNotNull(values["$key.$it"]) }

    fun putList(values: MutableMap<String, String?>, key: String, list: List<String>) {
        values["$key.count"] = list.size.toString()
        list.forEachIndexed { index, value -> values["$key.$index"] = value }
    }
}
