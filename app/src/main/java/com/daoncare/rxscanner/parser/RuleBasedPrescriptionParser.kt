package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult
import com.daoncare.rxscanner.model.parseMealTiming

object RuleBasedPrescriptionParser {

private val dosageRegex = Regex(
    """(\d+(?:\.\d+)?\s*(?:mg|밀리그램|g|그램|mcg|ug|mL|ml|IU|정|캡슐|포))""",
    RegexOption.IGNORE_CASE
)
private val quantityPerDoseRegex = Regex(
    """(?:1회(?:\s*복용량)?|회당)\s*[:\-]?\s*(\d+(?:\.\d+)?\s*(?:정|캡슐|포|mL|ml|방울))"""
)
private val dosesPerDayRegex = Regex(
    """((?:1일|하루|매일)\s*\d+\s*(?:회|번))"""
)
private val daysCountRegex = Regex(
    """((?:남은\s*)?투약\s*일수\s*[:\-]?\s*\d+\s*일|\d+\s*(?:일분|일치|회분|일))"""
)
private val dateRangeRegex = Regex(
    """(20\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2}\s*(?:~|-|부터)\s*(?:20\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2})?)"""
)
private val singleDateRegex = Regex(
    """(20\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2})"""
)
private val issueCountRegex = Regex(
    """발행\s*(?:횟수)?\s*[:\-]?\s*(\d+)\s*(?:회|건)"""
)
private val pharmacyRegex = Regex(
    """([가-힣A-Za-z0-9]+(?:약국|병원|의원|의료원))"""
)

fun parse(rawText: String): PrescriptionScanResult {
    val lines = rawText.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val pharmacyName = lines.firstNotNullOfOrNull { line ->
        pharmacyRegex.find(line)?.groupValues?.getOrNull(1)
    }.orEmpty()

    val issueDate = singleDateRegex.find(rawText)?.groupValues?.getOrNull(1).orEmpty()
    val periodText = dateRangeRegex.find(rawText)?.groupValues?.getOrNull(1).orEmpty()
    val issueCount = issueCountRegex.find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val globalMealTiming = when {
        "식전" in rawText -> MealTimingCategory.BEFORE_MEAL
        "식후" in rawText -> MealTimingCategory.AFTER_MEAL
        "공복" in rawText -> MealTimingCategory.EMPTY_STOMACH
        "취침" in rawText -> MealTimingCategory.BEDTIME
        "필요시" in rawText || "PRN" in rawText.uppercase() -> MealTimingCategory.PRN
        else -> MealTimingCategory.UNSPECIFIED
    }

    val globalCycle = dosesPerDayRegex.find(rawText)?.groupValues?.getOrNull(1).orEmpty()
    val globalDays = daysCountRegex.find(rawText)?.groupValues?.getOrNull(1).orEmpty()
    val globalQty = quantityPerDoseRegex.find(rawText)?.groupValues?.getOrNull(1).orEmpty()

    val meds = mutableListOf<ExtractedMedication>()
    for (line in lines) {
        val hasMedSignal = line.contains("정") ||
            line.contains("캡슐") ||
            line.contains("시럽") ||
            line.contains("산") ||
            line.contains("액") ||
            dosageRegex.containsMatchIn(line)

        if (hasMedSignal && !line.startsWith("조제일") && !line.startsWith("투약일수")) {
            val dosage = dosageRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
            val qty = quantityPerDoseRegex.find(line)?.groupValues?.getOrNull(1)
                ?: Regex("""(\d+(?:\.\d+)?\s*(?:정|캡슐|포))""").find(line)?.groupValues?.getOrNull(1)
                ?: globalQty

            val cycle = dosesPerDayRegex.find(line)?.groupValues?.getOrNull(1) ?: globalCycle
            val dosesPerDay = Regex("""(\d+)\s*(?:회|번)""").find(cycle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val totalDays = daysCountRegex.find(line)?.groupValues?.getOrNull(1) ?: globalDays

            val meal = parseMealTiming(line).let {
                if (it == MealTimingCategory.UNSPECIFIED) globalMealTiming else it
            }

            val drugName = line
                .replace(dosageRegex, "")
                .replace(dosesPerDayRegex, "")
                .replace(daysCountRegex, "")
                .replace(Regex("""[()\[\]·•/|-]+"""), " ")
                .trim()

            val infoNotes = buildList {
                if (meal != MealTimingCategory.UNSPECIFIED) add(meal.label)
                if (cycle.isNotBlank()) add(cycle)
                if (totalDays.isNotBlank()) add(totalDays)
            }.joinToString(" · ")

            meds += ExtractedMedication(
                drugName = drugName,
                quantityPerDose = qty,
                dosage = dosage,
                dosingCycle = cycle,
                dosesPerDay = dosesPerDay,
                totalDaysOrDoses = totalDays,
                medicationPeriod = periodText,
                mealTiming = meal,
                timingHint = infoNotes,
                drugInfoNotes = infoNotes,
                isLowConfidence = drugName.isBlank() || (dosage.isBlank() && qty.isBlank())
            )
        }
    }

    return PrescriptionScanResult(
        documentType = if ("처방전" in rawText) "처방전" else "약봉투",
        pharmacyName = pharmacyName,
        hospitalOrPharmacy = pharmacyName,
        issueDateText = issueDate,
        issueCount = issueCount,
        medications = meds,
        rawOcrText = rawText,
        usedGeminiNano = false
    )
}
}
