package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult

class RuleBasedPrescriptionParser {

    private val knownDrugPatterns = listOf(
        "대웅바이오클래리트로마이신",
        "펠루비정",
        "베포스타비정",
        "코대원포르테시럽",
        "레바트정"
    )

    private val excludeKeywords = listOf(
        "조제약", "복약안내", "약품사진", "약품명", "주의사항", "폐의약품", "회수", "봉투",
        "우체통", "절취선", "조제정보", "환자성명", "조제약사", "처방전교부번호", "조제일자",
        "처방전발행기관", "영수증", "계산서", "사업자등록번호", "사업장소재지", "약제비",
        "본인부담금", "보험자부담금", "총수납금액", "현금영수증", "발행일", "표시대로",
        "식전", "식후", "아침", "점심", "저녁", "취침", "노란색", "담황색", "흰색",
        "전문가와", "중단하지", "금주하세요", "졸릴 수", "심혈관계", "이상 증상",
        "먹는 약", "운전", "임신", "수유", "약봉투", "처방전", "OCR", "테스트"
    )

    private val drugSuffixRegex = Regex(
        """([가-힣A-Za-z0-9\-]+(?:정|캡슐|시럽|액|산|과립|환|연고|크림|겔|패치|주|흡입제|점안액)(?:\([^)]+\))?)"""
    )

    fun parse(rawText: String): PrescriptionScanResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val pharmacyName = extractPharmacyName(lines, rawText)
        val issueDate = extractIssueDate(rawText)
        val globalMealTiming = extractGlobalMealTiming(rawText)
        val globalDays = extractGlobalDays(rawText)
        val globalFreq = extractGlobalFrequency(rawText)
        val timingHint = extractTimingHint(rawText)

        val medications = mutableListOf<ExtractedMedication>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]

            val matchedKnown = knownDrugPatterns.firstOrNull { line.contains(it) }
            val suffixMatch = if (matchedKnown == null && !line.startsWith("(") && !isExcludedLine(line)) {
                drugSuffixRegex.find(line)?.groupValues?.get(1)
            } else {
                null
            }

            val candidateName = matchedKnown ?: suffixMatch
            if (candidateName != null) {
                if (medications.any { it.drugName.contains(candidateName) || candidateName.contains(it.drugName) }) {
                    continue
                }

                val nearbyWindow = lines.drop(i).take(6).joinToString(" ")
                val dosageMatch = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                    .find(line) ?: Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                    .find(nearbyWindow)
                val dosage = dosageMatch?.groupValues?.get(1) ?: ""

                val freq = extractItemFrequency(nearbyWindow) ?: globalFreq ?: 3
                val days = extractItemDays(nearbyWindow) ?: globalDays

                val infoNotes = lines.drop(i + 1).take(4)
                    .filter { it.startsWith("(") || it.contains("보관") || it.contains("치료제") || it.contains("소염제") || it.contains("질환약") || it.contains("가래약") || it.contains("보호제") }
                    .distinct()
                    .joinToString(" · ")

                val newMed = ExtractedMedication(
                    drugName = candidateName,
                    quantityPerDose = "1회분",
                    dosage = dosage,
                    dosingCycle = "1일 ${freq}회",
                    dosesPerDay = freq,
                    totalDaysOrDoses = if (days.isNotBlank()) "${days}일" else "",
                    medicationPeriod = if (days.isNotBlank()) "${days}일분" else "",
                    mealTiming = globalMealTiming,
                    timingHint = timingHint,
                    drugInfoNotes = infoNotes,
                    isLowConfidence = false
                )
                medications.add(newMed)
                currentMed = newMed
                continue
            }

            if (currentMed != null && !isExcludedLine(line) && isDrugInfoNote(line)) {
                val merged = listOf(currentMed.drugInfoNotes, line)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
                currentMed = currentMed.copy(drugInfoNotes = merged)
                medications[medications.lastIndex] = currentMed
            }
        }

        return PrescriptionScanResult(
            documentType = "조제약 복약안내",
            pharmacyName = pharmacyName,
            hospitalOrPharmacy = pharmacyName,
            issueDateText = issueDate,
            medications = medications,
            rawOcrText = rawText,
            usedGeminiNano = false
        )
    }

    private fun isExcludedLine(line: String): Boolean {
        if (line.length <= 1) return true
        if (line.all { it.isDigit() || it == '.' || it == '-' || it == ',' }) return true
        return excludeKeywords.any { line.contains(it) }
    }

    private fun isDrugInfoNote(line: String): Boolean {
        return line.startsWith("(") ||
            line.contains("보관") ||
            line.contains("치료제") ||
            line.contains("소염제") ||
            line.contains("질환약") ||
            line.contains("가래약") ||
            line.contains("보호제")
    }

    private fun extractPharmacyName(lines: List<String>, rawText: String): String {
        for (i in lines.indices) {
            if (lines[i] == "상호" || lines[i] == "상 호" || lines[i].contains("상호")) {
                val next = lines.getOrNull(i + 1).orEmpty()
                if (next.contains("약국")) return next
            }
        }
        val match = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(rawText)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun extractIssueDate(rawText: String): String {
        return Regex("""(20\d{2}[-./]\d{2}[-./]\d{2})""").find(rawText)?.groupValues?.get(1) ?: ""
    }

    private fun extractGlobalMealTiming(rawText: String): MealTimingCategory {
        return when {
            rawText.contains("식후") -> MealTimingCategory.AFTER_MEAL
            rawText.contains("식전") -> MealTimingCategory.BEFORE_MEAL
            rawText.contains("공복") -> MealTimingCategory.EMPTY_STOMACH
            rawText.contains("취침") -> MealTimingCategory.BEDTIME
            else -> MealTimingCategory.UNSPECIFIED
        }
    }

    private fun extractTimingHint(rawText: String): String {
        val hints = mutableListOf<String>()
        if (rawText.contains("아침")) hints.add("아침")
        if (rawText.contains("점심")) hints.add("점심")
        if (rawText.contains("저녁")) hints.add("저녁")
        return hints.joinToString("·")
    }

    private fun extractGlobalFrequency(rawText: String): Int? {
        return Regex("""1일\s*(\d)\s*회""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGlobalDays(rawText: String): String {
        return Regex("""(\d{1,2})\s*일분""").find(rawText)?.groupValues?.get(1) ?: ""
    }

    private fun extractItemFrequency(window: String): Int? {
        return Regex("""\b([1-6])\s*회\b""").find(window)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractItemDays(window: String): String? {
        return Regex("""\b(\d{1,2})\s*일\b""").find(window)?.groupValues?.get(1)
    }
}
