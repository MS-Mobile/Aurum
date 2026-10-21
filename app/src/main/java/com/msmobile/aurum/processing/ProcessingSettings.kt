package com.msmobile.aurum.processing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What every photo is processed with: the params and whether `hdr` is scaled by the scene light. */
data class ProcessingSettings(
    val params: EnhanceParams = EnhanceParams.IPHONE,
    /** Applies [SceneAdaptiveHdr] to each photo. */
    val scaleHdrBySceneLight: Boolean = true,
)

/** The [ProcessingSettings] chosen in the processing tuner, kept across app restarts. */
@Singleton
class ProcessingSettingsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    private val _settings by lazy { MutableStateFlow(load()) }
    val settings: StateFlow<ProcessingSettings> get() = _settings

    fun save(settings: ProcessingSettings) {
        preferences.edit().apply {
            EnhanceParam.entries.forEach { putLong(it.key, it.get(settings.params).toRawBits()) }
            putBoolean(KEY_SCALE_HDR, settings.scaleHdrBySceneLight)
        }.apply()
        _settings.value = settings
    }

    private fun load(): ProcessingSettings {
        val params = EnhanceParam.entries.fold(EnhanceParams.IPHONE) { params, param ->
            if (preferences.contains(param.key)) {
                param.set(params, Double.fromBits(preferences.getLong(param.key, 0)))
            } else {
                params
            }
        }
        return ProcessingSettings(
            params = params,
            scaleHdrBySceneLight = preferences.getBoolean(KEY_SCALE_HDR, true),
        )
    }

    private companion object {
        const val PREFERENCES = "processing"
        const val KEY_SCALE_HDR = "scaleHdrBySceneLight"
    }
}
