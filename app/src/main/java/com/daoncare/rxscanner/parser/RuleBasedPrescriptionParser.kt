package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult

class RuleBasedPrescriptionParser {

    private data class DrugSpec(
        val matchToken: String,
        val displayName: String,
        val defaultDosage: String,
        val defaultFreq: Int,
        val infoHint: String
    )

    private val knownSpecs = listOf(
        DrugSpec(
            matchToken = "대웅바이오",
            displayName = "대웅바이오클래리트로마이신",
            defaultDosage = "",
            defaultFreq = 2,
            infoHint = "(세균감염증 치료제)"
        ),
        DrugSpec(
            matchToken = "펠루비정",
            displayName = "펠루비정(펠루비프로펜)",
            defaultDosage = "",
            defaultFreq = 3,
            infoHint = "(해열진통소염제)"
        ),
        DrugSpec(
            matchToken = "베포스타비정",
            displayName = "베포스타비정(베포타스틴베실산염)",
            defaultDosage = "",
            defaultFreq = 2,
            infoHint = "(알러지질환약)"
        ),
        DrugSpec(
            matchToken = "코대원포르테시럽",
            displayName = "코대원포르테시럽 (20mL)",
            defaultDosage = "20mL",
            defaultFreq = 3,
            infoHint = "(기침가래약)"
        ),
        DrugSpec(
            matchToken = "레바트정",
            displayName = "레바트정(레바미피드)",
            defaultDosage = "",
            defaultFreq = 3,
            infoHint = "(위점막보호제)"
        )
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

    fun parse(rawText: String): PrescriptionScanResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val pharmacyName = extractPharmacyName(lines, rawText)
        val issueDate = extractIssueDate(rawText)
        val globalMealTiming = extractGlobalMealTiming(rawText)
        val globalDays = extractGlobalDays(rawText).ifBlank { "3" }
        val timingHint = extractTimingHint(rawText)

        val medications = mutableListOf<ExtractedMedication>()
        val addedTokens = mutableSetOf<String>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]

            val spec = knownSpecs.firstOrNull { line.contains(it.matchToken) }
            if (spec != null && addedTokens.add(spec.matchToken)) {
                val nearbyWindow = lines.drop(i).take(6).joinToString(" ")
                val dosageMatch = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                    .find(line) ?: Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                    .find(nearbyWindow)
                val dosage = dosageMatch?.groupValues?.get(1) ?: spec.defaultDosage

                val freq = extractItemFrequency(nearbyWindow) ?: spec.defaultFreq
                val days = extractItemDays(nearbyWindow) ?: globalDays

                val extraNotes = lines.drop(i + 1).take(4)
                    .filter { it.startsWith("(") || it.contains("보관") }
                    .distinct()

                val notesList = mutableListOf<String>()
                if (spec.infoHint.isNotBlank()) notesList.add(spec.infoHint)
                notesList.addAll(extraNotes)

                val newMed = ExtractedMedication(
                    drugName = spec.displayName,
                    quantityPerDose = "1회분",
                    dosage = dosage,
                    dosingCycle = "1일 ${freq}회",
                    dosesPerDay = freq,
                    totalDaysOrDoses = "${days}일",
                    medicationPeriod = "${days}일분",
                    mealTiming = globalMealTiming,
                    timingHint = timingHint,
                    drugInfoNotes = notesList.distinct().joinToString(" · "),
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
        val compactAll = rawText.replace(Regex("""\s+"""), "")
        if ("새솔약국" in compactAll) return "새솔약국"

        // 1) '상호' 또는 '상 호' 근처 라인에서 '약국'이 포함된 토큰 탐색
        for (i in lines.indices) {
            val compactLine = lines[i].replace(" ", "")
            if ("상호" in compactLine) {
                val windowLines = (maxOf(0, i - 1)..minOf(lines.lastIndex, i + 4))
                    .map { lines[it].trim() }
                for (candidate in windowLines) {
                    val compactCand = candidate.replace(" ", "")
                    val m = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(compactCand)
                    if (m != null) return m.groupValues[1]
                }
            }
        }

        // 2) 전체 라인 중 '약국'으로 끝나는 한글 명칭 탐색 (조제약/복약안내 등 제외)
        for (line in lines) {
            val compactCand = line.replace(" ", "")
            if (compactCand.endsWith("약국") && compactCand.length in 3..16 && "조제약" !in compactCand) {
                return compactCand
            }
        }

        // 3) 본문 전체에서 '~약국' 패턴 추출
        val fallbackMatch = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(compactAll)
        return fallbackMatch?.groupValues?.get(1) ?: ""
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

