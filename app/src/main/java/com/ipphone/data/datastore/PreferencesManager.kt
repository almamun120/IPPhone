package com.ipphone.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wifi_calling_prefs")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore
    
    companion object {
        private val KEY_DEVICE_UUID = stringPreferencesKey("device_uuid")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_PROFILE_IMAGE = stringPreferencesKey("profile_image")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_HEARTBEAT_INTERVAL = intPreferencesKey("heartbeat_interval")
        private val KEY_PRESENCE_TIMEOUT = intPreferencesKey("presence_timeout")
        private val KEY_AUTO_ACCEPT_FILES = booleanPreferencesKey("auto_accept_files")
        private val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")
        private val KEY_NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        private val KEY_NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate")
    }
    
    // Device Identity
    suspend fun getDeviceUuid(): String {
        val uuid = dataStore.data.first()[KEY_DEVICE_UUID]
        return uuid ?: run {
            val newUuid = UUID.randomUUID().toString()
            setDeviceUuid(newUuid)
            newUuid
        }
    }
    
    suspend fun setDeviceUuid(uuid: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_UUID] = uuid
        }
    }
    
    val deviceUuidFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_UUID]
    }
    
    // Device Name
    suspend fun getDeviceName(): String {
        return dataStore.data.first()[KEY_DEVICE_NAME] ?: android.os.Build.MODEL
    }
    
    suspend fun setDeviceName(name: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_NAME] = name
        }
    }
    
    val deviceNameFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: android.os.Build.MODEL
    }
    
    // Profile Image
    suspend fun getProfileImage(): String? {
        return dataStore.data.first()[KEY_PROFILE_IMAGE]
    }
    
    suspend fun setProfileImage(imagePath: String?) {
        dataStore.edit { prefs ->
            if (imagePath != null) {
                prefs[KEY_PROFILE_IMAGE] = imagePath
            } else {
                prefs.remove(KEY_PROFILE_IMAGE)
            }
        }
    }
    
    val profileImageFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_PROFILE_IMAGE]
    }
    
    // Onboarding
    suspend fun isOnboardingCompleted(): Boolean {
        return dataStore.data.first()[KEY_ONBOARDING_COMPLETED] ?: false
    }
    
    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }
    
    // Network Settings
    suspend fun getHeartbeatInterval(): Int {
        return dataStore.data.first()[KEY_HEARTBEAT_INTERVAL] ?: 5000
    }
    
    suspend fun setHeartbeatInterval(intervalMs: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_HEARTBEAT_INTERVAL] = intervalMs
        }
    }
    
    suspend fun getPresenceTimeout(): Int {
        return dataStore.data.first()[KEY_PRESENCE_TIMEOUT] ?: 15000
    }
    
    suspend fun setPresenceTimeout(timeoutMs: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_PRESENCE_TIMEOUT] = timeoutMs
        }
    }
    
    // File Transfer Settings
    val downloadPathFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_PATH]
    }

    suspend fun isAutoAcceptFiles(): Boolean {
        return dataStore.data.first()[KEY_AUTO_ACCEPT_FILES] ?: false
    }
    
    suspend fun setAutoAcceptFiles(autoAccept: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_ACCEPT_FILES] = autoAccept
        }
    }
    
    suspend fun getDownloadPath(): String? {
        return dataStore.data.first()[KEY_DOWNLOAD_PATH]
    }
    
    suspend fun setDownloadPath(path: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DOWNLOAD_PATH] = path
        }
    }
    
    // Notification Settings
    val notificationSoundFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_SOUND] ?: true
    }
    
    suspend fun setNotificationSound(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATION_SOUND] = enabled
        }
    }
    
    val notificationVibrateFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_VIBRATE] ?: true
    }
    
    suspend fun setNotificationVibrate(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATION_VIBRATE] = enabled
        }
    }
}
