package com.daoncare.rxscanner.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class KoreanOcrEngine(private val context: Context) {

private val recognizer by lazy {
    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
}

suspend fun recognizeFromUri(imageUri: Uri): String {
    val inputImage = InputImage.fromFilePath(context, imageUri)
    val visionText = recognizer.process(inputImage).await()
    return visionText.text.trim()
}

fun close() {
    recognizer.close()
}
}
