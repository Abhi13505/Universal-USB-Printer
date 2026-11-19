package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val colorOverride: ColorOverrideMode = ColorOverrideMode.AUTO,
    val testPageVerified: Boolean = false
)

class PrinterPreferencesStore(private val context: Context) {

    fun observeOverride(vidPidKey: String): Flow<PrinterDeviceOverride> {
        val protoKey = stringPreferencesKey("proto_$vidPidKey")
        val colorKey = stringPreferencesKey("color_$vidPidKey")
        val testVerifiedKey = booleanPreferencesKey("test_verified_$vidPidKey")
        return context.printerPrefsDataStore.data.map { prefs ->
            val protoName = prefs[protoKey]
            val colorName = prefs[colorKey]
            val proto = DriverProtocol.entries.firstOrNull { it.name == protoName && it != DriverProtocol.UNSUPPORTED }
                ?: DriverProtocol.AUTO_DETECTED
            val color = ColorOverrideMode.entries.firstOrNull { it.name == colorName }
                ?: ColorOverrideMode.AUTO
            val verified = prefs[testVerifiedKey] ?: false
            PrinterDeviceOverride(
                protocolOverride = proto,
                colorOverride = color,
                testPageVerified = verified
            )
        }
    }

    suspend fun setProtocolOverride(vidPidKey: String, protocol: DriverProtocol) {
        val protoKey = stringPreferencesKey("proto_$vidPidKey")
        val testVerifiedKey = booleanPreferencesKey("test_verified_$vidPidKey")
        context.printerPrefsDataStore.edit { prefs ->
            if (protocol == DriverProtocol.AUTO_DETECTED || protocol == DriverProtocol.UNSUPPORTED) {
                prefs.remove(protoKey)
            } else {
                prefs[protoKey] = protocol.name
            }
            // Switching protocol requires re-verifying with a 1-inch test page on non-SCX-3200 devices
            prefs.remove(testVerifiedKey)
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

    suspend fun setTestPageVerified(vidPidKey: String, verified: Boolean) {
        val testVerifiedKey = booleanPreferencesKey("test_verified_$vidPidKey")
        context.printerPrefsDataStore.edit { prefs ->
            if (verified) {
                prefs[testVerifiedKey] = true
            } else {
                prefs.remove(testVerifiedKey)
            }
        }
    }
}
