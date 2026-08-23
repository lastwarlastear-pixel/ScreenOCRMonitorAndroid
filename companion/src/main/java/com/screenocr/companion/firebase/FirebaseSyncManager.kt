package com.screenocr.companion.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.screenocr.companion.model.AppConfig

class FirebaseSyncManager private constructor() {
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    private var configListener: ValueEventListener? = null
    private var alertsListener: ChildEventListener? = null
    
    var targetDeviceId: String? = null
        private set

    companion object {
        private var instance: FirebaseSyncManager? = null

        fun getInstance(): FirebaseSyncManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseSyncManager().also { instance = it }
            }
        }
    }

    fun initialize(onReady: () -> Unit) {
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener {
                onReady()
            }.addOnFailureListener {
                Log.e("FirebaseSyncManager", "Auth failed", it)
            }
        } else {
            onReady()
        }
    }

    fun connectToDevice(deviceId: String, onConfigUpdate: (AppConfig) -> Unit, onAlert: (String) -> Unit) {
        cleanup()

        targetDeviceId = deviceId
        val configRef = database.child("devices").child(deviceId).child("config")
        val alertsRef = database.child("devices").child(deviceId).child("alerts")
        
        configListener = configRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val config = snapshot.getValue(AppConfig::class.java)
                if (config != null) onConfigUpdate(config)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseSyncManager", "Config sync cancelled", error.toException())
            }
        })

        // Listen for new alerts added since 'now'
        alertsListener = alertsRef.orderByChild("timestamp").startAt(System.currentTimeMillis().toDouble())
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val msg = snapshot.child("message").getValue(String::class.java)
                    if (msg != null) onAlert(msg)
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    fun updateRemoteConfig(config: AppConfig) {
        targetDeviceId?.let { deviceId ->
            database.child("devices").child(deviceId).child("config").setValue(config)
        }
    }

    fun cleanup() {
        targetDeviceId?.let { oldId ->
            configListener?.let { database.child("devices").child(oldId).child("config").removeEventListener(it) }
            alertsListener?.let { database.child("devices").child(oldId).child("alerts").removeEventListener(it) }
        }
        configListener = null
        alertsListener = null
        targetDeviceId = null
    }
}
