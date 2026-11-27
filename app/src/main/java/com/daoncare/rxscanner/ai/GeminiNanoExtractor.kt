package com.daoncare.rxscanner.ai

import com.daoncare.rxscanner.model.AiPrescriptionExtraction
import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.PrescriptionScanResult
import com.daoncare.rxscanner.model.buildPrescriptionPromptRequest
import com.daoncare.rxscanner.model.parseMealTiming
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.Generation

class GeminiNanoExtractor {

private val generativeModel by lazy {
    Generation.getClient()
}

suspend fun extractStructured(ocrText: String): PrescriptionScanResult? {
    if (ocrText.isBlank()) return null

    val status = generativeModel.checkStatus()
    if (status != FeatureStatus.AVAILABLE && status != FeatureStatus.DOWNLOADABLE) {
        return null
    }

    val request = buildPrescriptionPromptRequest(ocrText)
    val response = generativeModel.generateContent(
        request,
        AiPrescriptionExtraction::class.java
    )
    val structured = response.candidates.firstOrNull()?.getParsedContent() ?: return null

    val meds = structured.medications.map { item ->
        val parsedFreq = item.dosesPerDay.takeIf { it > 0 }
        val meal = parseMealTiming(item.mealTiming.ifBlank { item.timingHint })
        ExtractedMedication(
            drugName = item.drugName.trim(),
            quantityPerDose = item.quantityPerDose.trim(),
            dosage = item.dosage.trim(),
            dosingCycle = item.dosingCycle.trim(),
            dosesPerDay = parsedFreq,
            totalDaysOrDoses = item.totalDaysOrDoses.trim(),
            medicationPeriod = item.medicationPeriod.trim(),
            mealTiming = meal,
            timingHint = item.timingHint.trim(),
            drugInfoNotes = item.drugInfoNotes.trim(),
            isLowConfidence = item.needsReview ||
                item.drugName.isBlank() ||
                parsedFreq == null
        )
    }

    val pharmacy = structured.pharmacyName.trim()
    return PrescriptionScanResult(
        documentType = structured.documentType.trim().ifBlank { "약봉투/처방전" },
        pharmacyName = pharmacy,
        hospitalOrPharmacy = pharmacy,
        issueDateText = structured.issueDateText.trim(),
        issueCount = structured.issueCount.takeIf { it > 0 },
        medications = meds,
        rawOcrText = ocrText,
        usedGeminiNano = true
    )
}
}
