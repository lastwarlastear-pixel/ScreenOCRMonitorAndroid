package com.screenocr.monitor.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.utils.PreferencesManager

class FirebaseSyncManager private constructor(private val context: android.content.Context) {
    private val prefsManager = PreferencesManager(context)
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private var configListener: ValueEventListener? = null
    
    val userId: String? get() = auth.currentUser?.uid

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var instance: FirebaseSyncManager? = null

        fun getInstance(context: android.content.Context): FirebaseSyncManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var onConfigUpdated: ((AppConfig) -> Unit)? = null

    fun initialize(onReady: (String) -> Unit) {
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener { result ->
                val uid = result.user?.uid ?: ""
                Log.d("FirebaseSyncManager", "Signed in anonymously: $uid")
                setupFCM(uid)
                startSyncing(uid)
                onReady(uid)
            }.addOnFailureListener {
                Log.e("FirebaseSyncManager", "Auth failed", it)
            }
        } else {
            val uid = auth.currentUser!!.uid
            setupFCM(uid)
            startSyncing(uid)
            onReady(uid)
        }
    }

    private fun setupFCM(uid: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                database.child("devices").child(uid).child("fcmToken").setValue(token)
            }
        }
    }

    private fun startSyncing(uid: String) {
        val configRef = database.child("devices").child(uid).child("config")
        
        // Push initial local config to Firebase if it doesn't exist or just overwrite it as master for now
        // Or we could let the companion app be the master. 
        // Requirements say: "Sync the existing AppConfig to Firebase RTDB node... Listen for changes... update local prefs."
        
        configRef.setValue(prefsManager.loadConfig())

        configListener = configRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remoteConfig = snapshot.getValue(AppConfig::class.java)
                if (remoteConfig != null) {
                    Log.d("FirebaseSyncManager", "Remote config updated: $remoteConfig")
                    
                    // Merge remote config with local region to prevent "forgetting" the area
                    val localConfig = prefsManager.loadConfig()
                    remoteConfig.selectedRegion = localConfig.selectedRegion
                    remoteConfig.showBorder = localConfig.showBorder
                    
                    prefsManager.saveConfig(remoteConfig)
                    onConfigUpdated?.invoke(remoteConfig)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseSyncManager", "Config sync cancelled", error.toException())
            }
        })
    }

    fun pushConfig(config: AppConfig) {
        userId?.let { uid ->
            database.child("devices").child(uid).child("config").setValue(config)
        }
    }

    fun pushAlert(message: String) {
        userId?.let { uid ->
            val alertRef = database.child("devices").child(uid).child("alerts").push()
            val alertData = mapOf(
                "message" to message,
                "timestamp" to ServerValue.TIMESTAMP
            )
            alertRef.setValue(alertData)
        }
    }

    fun cleanup() {
        userId?.let { uid ->
            configListener?.let {
                database.child("devices").child(uid).child("config").removeEventListener(it)
            }
        }
    }
}
