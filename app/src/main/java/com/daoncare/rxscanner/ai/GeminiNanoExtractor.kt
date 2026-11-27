package com.daoncare.rxscanner.ai

import com.daoncare.rxscanner.model.PrescriptionScanResult

class GeminiNanoExtractor {
    suspend fun extractStructured(ocrText: String): PrescriptionScanResult? {
        if (ocrText.isBlank()) return null
        return null
    }
}
