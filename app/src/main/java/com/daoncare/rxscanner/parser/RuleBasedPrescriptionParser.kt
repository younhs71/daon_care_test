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
        "식전", "식후", "아침", "점심", "저녁", "취침", "노란색", "담황색", "흰색",
        "차광실온", "실온 보관", "실온보관", "전문가와", "중단하지", "금주하세요",
        "졸릴 수", "심혈관계", "이상 증상", "먹는 약", "운전", "임신", "수유"
    )

    private val drugSuffixRegex = Regex(
        """([가-힣A-Za-z0-9\-]+(?:정|캡슐|시럽|액|산|과립|환|연고|크림|겔|패치|주|흡입제|점안액)(?:\([^)]+\))?(?:_\([^)]+\))?)"""
    )

    private val tripletRegex = Regex("""(\d+(?:\.\d+)?)\s+(\d{1,2})\s+(\d{1,3})""")

    fun parse(rawText: String): PrescriptionScanResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val pharmacyName = extractPharmacyName(lines, rawText)
        val issueDate = extractIssueDate(rawText)
        val globalMealTiming = extractGlobalMealTiming(rawText)
        val globalDays = extractGlobalDays(rawText)
        val globalFreq = extractGlobalFrequency(rawText)

        val medications = mutableListOf<ExtractedMedication>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]

            if (isExcludedLine(line)) {
                // 효능/보관/주의 문구는 직전 약품의 약정보에 보조 메모로 추가
                if (currentMed != null && isDrugInfoNote(line)) {
                    val mergedNote = listOf(currentMed.drugInfoNotes, line)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · ")
                    currentMed = currentMed.copy(drugInfoNotes = mergedNote)
                    medications[medications.lastIndex] = currentMed
                }
                continue
            }

            val drugMatch = drugSuffixRegex.find(line)
            if (drugMatch != null && !line.startsWith("(")) {
                val cleanName = drugMatch.groupValues[1]
                    .replace(Regex("""_\($"""), "")
                    .trim()

                // 직후 몇 줄 안에서 투약량/횟수/일수(예: 1.00 2 3) 탐색
                val nearbyWindow = lines.drop(i).take(5).joinToString(" ")
                val triplet = tripletRegex.find(nearbyWindow)

                val qty = triplet?.groupValues?.get(1)?.let { "${it}정(회)" } ?: "1회분"
                val freq = triplet?.groupValues?.get(2)?.toIntOrNull() ?: globalFreq
                val days = triplet?.groupValues?.get(3) ?: globalDays

                // 용량 추출 (예: 20mL, 500mg 등)
                val dosageMatch = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""").find(line)
                val dosage = dosageMatch?.groupValues?.get(1) ?: ""

                // 직후 줄의 괄호 효능 설명 (예: (세균감염증 치료제))
                val nextLine = lines.getOrNull(i + 1).orEmpty()
                val effectNote = if (nextLine.startsWith("(") && nextLine.endsWith(")")) nextLine else ""

                val newMed = ExtractedMedication(
                    drugName = cleanName,
                    quantityPerDose = qty,
                    dosage = dosage,
                    dosingCycle = if (freq != null) "1일 ${freq}회" else "1일 3회",
                    dosesPerDay = freq ?: 3,
                    totalDaysOrDoses = if (days.isNotBlank()) "${days}일" else "",
                    medicationPeriod = if (days.isNotBlank()) "${days}일분" else "",
                    mealTiming = globalMealTiming,
                    timingHint = extractTimingHint(rawText),
                    drugInfoNotes = effectNote,
                    isLowConfidence = false
                )
                medications.add(newMed)
                currentMed = newMed
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
        return (line.startsWith("(") && line.endsWith(")")) ||
            line.contains("치료제") ||
            line.contains("소염제") ||
            line.contains("질환약") ||
            line.contains("가래약") ||
            line.contains("보호제") ||
            line.contains("보관")
    }

    private fun extractPharmacyName(lines: List<String>, rawText: String): String {
        for (i in lines.indices) {
            if (lines[i].contains("상호") || lines[i] == "상 호") {
                val candidate = lines.getOrNull(i + 1).orEmpty()
                if (candidate.contains("약국")) return candidate
            }
        }
        val pharmacyRegex = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""")
        return pharmacyRegex.find(rawText)?.groupValues?.get(1) ?: ""
    }

    private fun extractIssueDate(rawText: String): String {
        val dateRegex = Regex("""(20\d{2}[-./]\d{2}[-./]\d{2})""")
        return dateRegex.find(rawText)?.groupValues?.get(1) ?: ""
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
        val match = Regex("""1일\s*(\d)\s*회""").find(rawText)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGlobalDays(rawText: String): String {
        val match = Regex("""(\d{1,2})\s*일분""").find(rawText)
        return match?.groupValues?.get(1) ?: ""
    }
}
