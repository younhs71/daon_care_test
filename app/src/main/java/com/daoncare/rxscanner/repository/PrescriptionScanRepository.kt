package com.daoncare.rxscanner.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.daoncare.rxscanner.ai.GeminiNanoExtractor
import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.PrescriptionScanResult
import com.daoncare.rxscanner.parser.RuleBasedPrescriptionParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class PrescriptionScanRepository(
    private val context: Context,
    private val parser: RuleBasedPrescriptionParser = RuleBasedPrescriptionParser(),
    private val geminiNanoExtractor: GeminiNanoExtractor = GeminiNanoExtractor()
) {
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    suspend fun scanImage(imageUri: Uri): PrescriptionScanResult {
        val rawText = runCatching {
            val image = InputImage.fromFilePath(context, imageUri)
            val visionText = recognizer.process(image).await()
            visionText.text
        }.onFailure { e ->
            Log.e("DaonCareRxTest", "ML Kit OCR 실패: ${e.message}", e)
        }.getOrDefault("")

        Log.d("DaonCareRxTest", "OCR 추출 길이=${rawText.length}\n$rawText")

        val fallback = parser.parse(rawText)
        val aiResult = runCatching {
            geminiNanoExtractor.extractStructured(rawText)
        }.getOrNull()

        if (aiResult != null && aiResult.medications.isNotEmpty()) {
            val mergedMeds: List<ExtractedMedication> = aiResult.medications.mapIndexed { idx, med ->
                val fb = fallback.medications.getOrNull(idx)
                med.copy(
                    quantityPerDose = med.quantityPerDose.ifBlank { fb?.quantityPerDose.orEmpty() },
                    dosage = med.dosage.ifBlank { fb?.dosage.orEmpty() },
                    dosingCycle = med.dosingCycle.ifBlank { fb?.dosingCycle.orEmpty() },
                    dosesPerDay = med.dosesPerDay ?: fb?.dosesPerDay,
                    totalDaysOrDoses = med.totalDaysOrDoses.ifBlank { fb?.totalDaysOrDoses.orEmpty() },
                    medicationPeriod = med.medicationPeriod.ifBlank { fb?.medicationPeriod.orEmpty() },
                    timingHint = med.timingHint.ifBlank { fb?.timingHint.orEmpty() },
                    drugInfoNotes = med.drugInfoNotes.ifBlank { fb?.drugInfoNotes.orEmpty() }
                )
            }
            return aiResult.copy(
                pharmacyName = aiResult.pharmacyName.ifBlank { fallback.pharmacyName },
                hospitalOrPharmacy = aiResult.hospitalOrPharmacy.ifBlank { fallback.hospitalOrPharmacy },
                issueDateText = aiResult.issueDateText.ifBlank { fallback.issueDateText },
                medications = mergedMeds,
                rawOcrText = rawText
            )
        }

        return fallback.copy(
            rawOcrText = rawText,
            usedGeminiNano = false
        )
    }

    fun release() {
        runCatching { recognizer.close() }
    }
}
