package com.screenocr.monitor.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MLKitOcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            continuation.resume(visionText.text.trim())
                        }
                    }
                    .addOnFailureListener { e ->
                        if (continuation.isActive) {
                            continuation.resume("")
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
