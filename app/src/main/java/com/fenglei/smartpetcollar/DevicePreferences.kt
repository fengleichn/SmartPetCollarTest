package com.fenglei.smartpetcollar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_prefs")

class DevicePreferences private constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val SELECTED_MAC_KEY = stringPreferencesKey("selected_mac")

        @Volatile private var INSTANCE: DevicePreferences? = null

        @JvmStatic
        fun getInstance(context: Context): DevicePreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DevicePreferences(context.applicationContext.deviceDataStore)
                    .also { INSTANCE = it }
            }
    }

    fun getSelectedMac(): LiveData<String> = dataStore.data
        .map { prefs -> prefs[SELECTED_MAC_KEY] ?: MqttManager.DEFAULT_DEVICE_MAC }
        .asLiveData()

    fun saveSelectedMac(mac: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs -> prefs[SELECTED_MAC_KEY] = mac }
        }
    }
}
