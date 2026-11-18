package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ColorOverrideMode
import com.example.model.DriverProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.printerPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "printer_device_overrides"
)

data class PrinterDeviceOverride(
    val protocolOverride: DriverProtocol = DriverProtocol.AUTO_DETECTED,
    val colorOverride: ColorOverrideMode = ColorOverrideMode.AUTO
)

class PrinterPreferencesStore(private val context: Context) {

    fun observeOverride(vidPidKey: String): Flow<PrinterDeviceOverride> {
        val protoKey = stringPreferencesKey("proto_$vidPidKey")
        val colorKey = stringPreferencesKey("color_$vidPidKey")
        return context.printerPrefsDataStore.data.map { prefs ->
            val protoName = prefs[protoKey]
            val colorName = prefs[colorKey]
            val proto = DriverProtocol.entries.firstOrNull { it.name == protoName }
                ?: DriverProtocol.AUTO_DETECTED
            val color = ColorOverrideMode.entries.firstOrNull { it.name == colorName }
                ?: ColorOverrideMode.AUTO
            PrinterDeviceOverride(protocolOverride = proto, colorOverride = color)
        }
    }

    suspend fun setProtocolOverride(vidPidKey: String, protocol: DriverProtocol) {
        val protoKey = stringPreferencesKey("proto_$vidPidKey")
        context.printerPrefsDataStore.edit { prefs ->
            if (protocol == DriverProtocol.AUTO_DETECTED) {
                prefs.remove(protoKey)
            } else {
                prefs[protoKey] = protocol.name
            }
        }
    }

    suspend fun setColorOverride(vidPidKey: String, colorMode: ColorOverrideMode) {
        val colorKey = stringPreferencesKey("color_$vidPidKey")
        context.printerPrefsDataStore.edit { prefs ->
            if (colorMode == ColorOverrideMode.AUTO) {
                prefs.remove(colorKey)
            } else {
                prefs[colorKey] = colorMode.name
            }
        }
    }
}
