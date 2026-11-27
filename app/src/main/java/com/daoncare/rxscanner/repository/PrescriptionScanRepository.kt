package com.daoncare.rxscanner.repository

import android.content.Context
import android.net.Uri
import com.daoncare.rxscanner.ai.GeminiNanoExtractor
import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.PrescriptionScanResult
import com.daoncare.rxscanner.ocr.KoreanOcrEngine
import com.daoncare.rxscanner.parser.RuleBasedPrescriptionParser

class PrescriptionScanRepository(context: Context) {

private val ocrEngine = KoreanOcrEngine(context.applicationContext)
private val geminiNanoExtractor = GeminiNanoExtractor()

suspend fun scanImage(imageUri: Uri): PrescriptionScanResult {
    val rawText = ocrEngine.recognizeFromUri(imageUri)
    if (rawText.isBlank()) {
        return PrescriptionScanResult(
            medications = listOf(
                ExtractedMedication(drugName = "", isLowConfidence = true)
            ),
            rawOcrText = "",
            usedGeminiNano = false
        )
    }

    val fallback = RuleBasedPrescriptionParser.parse(rawText)
    val aiResult = runCatching { geminiNanoExtractor.extractStructured(rawText) }.getOrNull()

    return if (aiResult != null && aiResult.medications.isNotEmpty()) {
        val mergedMeds = aiResult.medications.mapIndexed { idx, med ->
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
        val mergedPharmacy = aiResult.pharmacyName.ifBlank { fallback.pharmacyName }
        aiResult.copy(
            pharmacyName = mergedPharmacy,
            hospitalOrPharmacy = mergedPharmacy,
            issueCount = aiResult.issueCount ?: fallback.issueCount,
            issueDateText = aiResult.issueDateText.ifBlank { fallback.issueDateText },
            medications = mergedMeds
        )
    } else {
        fallback
    }
}

fun release() {
    ocrEngine.close()
}
}
