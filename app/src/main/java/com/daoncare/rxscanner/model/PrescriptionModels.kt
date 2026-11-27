package com.daoncare.rxscanner.model

enum class MealTimingCategory(val label: String) {
    BEFORE_MEAL("식전"),
    AFTER_MEAL("식후"),
    EMPTY_STOMACH("공복"),
    BEDTIME("취침 전"),
    PRN("필요 시(PRN)"),
    UNSPECIFIED("미지정")
}

data class ExtractedMedication(
    val drugName: String,
    val quantityPerDose: String = "",
    val dosage: String = "",
    val dosingCycle: String = "",
    val dosesPerDay: Int? = null,
    val totalDaysOrDoses: String = "",
    val medicationPeriod: String = "",
    val mealTiming: MealTimingCategory = MealTimingCategory.UNSPECIFIED,
    val timingHint: String = "",
    val drugInfoNotes: String = "",
    val isLowConfidence: Boolean = false
)

data class PrescriptionScanResult(
    val documentType: String = "약봉투/처방전",
    val pharmacyName: String = "",
    val hospitalOrPharmacy: String = "",
    val issueDateText: String = "",
    val issueCount: Int? = null,
    val medications: List<ExtractedMedication> = emptyList(),
    val rawOcrText: String = "",
    val usedGeminiNano: Boolean = false
)

fun parseMealTiming(raw: String): MealTimingCategory {
    val normalized = raw.trim()
    return when {
        "식전" in normalized -> MealTimingCategory.BEFORE_MEAL
        "식후" in normalized -> MealTimingCategory.AFTER_MEAL
        "공복" in normalized -> MealTimingCategory.EMPTY_STOMACH
        "취침" in normalized -> MealTimingCategory.BEDTIME
        "필요" in normalized || "PRN" in normalized.uppercase() -> MealTimingCategory.PRN
        else -> MealTimingCategory.UNSPECIFIED
    }
}
