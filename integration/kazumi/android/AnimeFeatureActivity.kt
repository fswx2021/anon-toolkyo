package com.shengjian.player

import android.content.Intent
import android.os.Bundle
import com.ryanheise.audioservice.AudioServicePlugin
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel
import java.util.WeakHashMap

/** Presents the anime feature selected by ANON; the host owns the top-level back stack. */
class AnimeFeatureActivity : AnimeMediaActivity() {
    private companion object {
        const val FEATURE_CHANNEL = "com.shengjian.player/anime_features"
        val readyEngines = WeakHashMap<FlutterEngine, Boolean>()
    }

    private var pending: Map<String, String>? = null
    private var featureChannel: MethodChannel? = null
    private var engine: FlutterEngine? = null
    private var restoring = false
    private var reattaching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        restoring = savedInstanceState != null
        reattaching = FlutterEngineCache.getInstance().get(AudioServicePlugin.getFlutterEngineId()) != null
        pending = featureRequest(intent)
        super.onCreate(savedInstanceState)
    }

    private fun featureRequest(request: Intent): Map<String, String> = buildMap {
        val feature = request.getStringExtra("feature") ?: "resume"
        put("feature", feature)
        put("value", request.getStringExtra("value").orEmpty())
        put("appearance", getSharedPreferences("app_settings", MODE_PRIVATE).getInt("appearance", 0).toString())
        if (feature == "subject") {
            request.getStringExtra("subjectPreview")?.takeIf { it.isNotBlank() }?.let { put("subjectPreview", it) }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        engine = flutterEngine
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FEATURE_CHANNEL)
        featureChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    readyEngines[flutterEngine] = true
                    result.success(pending)
                    pending = null
                }
                "dashboard" -> {
                    val summary = call.arguments as? Map<*, *>
                    if (summary == null) {
                        result.error("invalid_summary", "Expected a map of anime library counts", null)
                    } else {
                        val editor = getSharedPreferences("anime_feature_summary", MODE_PRIVATE).edit()
                        for (key in listOf("historyCount", "favoriteCount", "ruleCount")) {
                            val count = (summary[key] as? Number)?.toLong() ?: continue
                            if (count in 0..Int.MAX_VALUE.toLong()) editor.putInt(key, count.toInt())
                        }
                        for (key in listOf("latestTitle", "latestEpisode")) {
                            if (summary["historyCount"] == 0) editor.remove(key)
                            else (summary[key] as? String)?.let { editor.putString(key, it) }
                        }
                        editor.apply()
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
        if (readyEngines[flutterEngine] == true) {
            if (restoring) pending = null
            deliverPending()
        } else if (reattaching) {
            // A media browser may have already started the Dart isolate without an Activity.
            channel.invokeMethod("hostAttached", null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("feature")) {
            pending = featureRequest(intent)
            deliverPending()
        }
    }

    private fun deliverPending() {
        val current = engine ?: return
        val channel = featureChannel ?: return
        if (readyEngines[current] != true) return
        val request = pending ?: return
        pending = null
        channel.invokeMethod("open", request)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        featureChannel?.setMethodCallHandler(null)
        featureChannel = null
        engine = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
