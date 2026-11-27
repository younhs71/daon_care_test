package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult

class RuleBasedPrescriptionParser {

    private val excludeKeywords = listOf(
        "조제약", "복약안내", "약품사진", "약품명", "주의사항", "폐의약품", "회수", "봉투",
        "우체통", "절취선", "조제정보", "환자성명", "조제약사", "처방전교부번호", "조제일자",
        "처방전발행기관", "영수증", "계산서", "사업자등록번호", "사업장소재지", "약제비",
        "본인부담금", "보험자부담금", "총수납금액", "현금영수증", "발행일", "표시대로",
        "식전", "식후", "아침", "점심", "저녁", "취침", "노란색", "담황색", "흰색", "녹색",
        "전문가와", "중단하지", "금주하세요", "졸릴 수", "심혈관계", "이상 증상",
        "먹는 약", "운전", "임신", "수유", "약봉투", "처방전", "OCR", "테스트",
        "필름코팅정", "원형", "타원형", "위산분비억제제", "위산차단제", "소염진통제",
        "근이완제", "소화성궤양", "치료제", "보관", "부담항목", "공단부담금", "수급자"
    )

    private val drugNameRegex = Regex(
        """^([가-힣A-Za-z0-9\-]{2,25}(?:정|캡슐|시럽|액|산|과립|환|연고|크림|겔|패치|주|흡입제|점안액)(?:\s*\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))?)$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawText: String): PrescriptionScanResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val pharmacyName = extractPharmacyName(lines, rawText)
        val issueDate = extractIssueDate(rawText)
        val globalMealTiming = extractGlobalMealTiming(rawText)
        val timingHint = extractTimingHint(rawText)
        val dosePattern = Regex("""(\d+(?:\.\d+)?)\s*정\s*씩?\s*(\d{1,2})\s*회\s*(\d{1,3})\s*일""")
            .find(rawText.replace(" ", ""))

        val defaultQty = dosePattern?.groupValues?.get(1)?.let { "${it}정" } ?: "1정"
        val defaultFreq = dosePattern?.groupValues?.get(2)?.toIntOrNull()
            ?: extractGlobalFrequency(rawText)
            ?: 2
        val defaultDays = dosePattern?.groupValues?.get(3)
            ?: extractGlobalDays(rawText)
            ?: "7"

        val medications = mutableListOf<ExtractedMedication>()
        val seenNames = mutableSetOf<String>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]
            val compactLine = line.replace(" ", "")

            val match = drugNameRegex.find(compactLine)
            if (match != null && !isExcludedLine(line)) {
                val drugName = match.groupValues[1]
                if (seenNames.add(drugName)) {
                    val dosageMatch = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                        .find(drugName)
                    val dosage = dosageMatch?.groupValues?.get(1) ?: ""

                    val nearby = lines.drop(i + 1).take(4)
                    val notes = nearby.filter {
                        it.startsWith("[") || it.startsWith("(") || it.contains("치료제") ||
                            it.contains("소염제") || it.contains("근이완제") || it.contains("보관")
                    }.distinct().joinToString(" · ")

                    val med = ExtractedMedication(
                        drugName = drugName,
                        quantityPerDose = defaultQty,
                        dosage = dosage,
                        dosingCycle = "1일 ${defaultFreq}회",
                        dosesPerDay = defaultFreq,
                        totalDaysOrDoses = "${defaultDays}일",
                        medicationPeriod = "${defaultDays}일분",
                        mealTiming = globalMealTiming,
                        timingHint = timingHint,
                        drugInfoNotes = notes,
                        isLowConfidence = false
                    )
                    medications.add(med)
                    currentMed = med
                }
                continue
            }

            if (currentMed != null && (line.startsWith("[") || line.startsWith("(") || line.contains("보관"))) {
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

    private fun extractPharmacyName(lines: List<String>, rawText: String): String {
        for (line in lines) {
            val compact = line.replace(" ", "")
            if (compact.endsWith("약국") && compact.length in 3..16 && !compact.contains("조제약")) {
                return compact
            }
        }
        val compactAll = rawText.replace(Regex("""\s+"""), "")
        val match = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(compactAll)
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

    private fun extractGlobalDays(rawText: String): String? {
        return Regex("""(\d{1,2})\s*일분""").find(rawText)?.groupValues?.get(1)
    }
}
