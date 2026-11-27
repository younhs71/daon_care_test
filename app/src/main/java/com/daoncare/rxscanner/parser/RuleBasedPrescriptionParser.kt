package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult

class RuleBasedPrescriptionParser {

    private data class DrugSpec(
        val matchTokens: List<String>,
        val displayName: String,
        val defaultDosage: String,
        val defaultFreq: Int,
        val infoHint: String
    )

    private val knownSpecs = listOf(
        DrugSpec(
            matchTokens = listOf("대웅바이오클래리트로마이신", "대웅바이오"),
            displayName = "대웅바이오클래리트로마이신",
            defaultDosage = "",
            defaultFreq = 2,
            infoHint = "(세균감염증 치료제)"
        ),
        DrugSpec(
            matchTokens = listOf("펠루비정"),
            displayName = "펠루비정(펠루비프로펜)",
            defaultDosage = "",
            defaultFreq = 3,
            infoHint = "(해열진통소염제)"
        ),
        DrugSpec(
            matchTokens = listOf("베포스타비정"),
            displayName = "베포스타비정(베포타스틴베실산염)",
            defaultDosage = "",
            defaultFreq = 2,
            infoHint = "(알러지질환약)"
        ),
        DrugSpec(
            matchTokens = listOf("코대원포르테시럽"),
            displayName = "코대원포르테시럽 (20mL)",
            defaultDosage = "20mL",
            defaultFreq = 3,
            infoHint = "(기침가래약)"
        ),
        DrugSpec(
            matchTokens = listOf("레바트정"),
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
        "식전", "식후", "아침", "점심", "저녁", "취침", "노란색", "담황색", "흰색", "녹색",
        "전문가와", "중단하지", "금주하세요", "졸릴 수", "심혈관계", "이상 증상",
        "먹는 약", "운전", "임신", "수유", "약봉투", "처방전", "OCR", "테스트",
        "필름코팅정", "원형", "타원형", "부담항목", "공단부담금", "수급자"
    )

    private val genericDrugRegex = Regex(
        """([가-힣A-Za-z0-9\-]{2,20}(?:정|캡슐|시럽|액|산|과립|환|연고|크림|겔|패치|주|흡입제|점안액))(?:\s*(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU)))?""",
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
            ?: "3"

        val medications = mutableListOf<ExtractedMedication>()
        val seenKeys = mutableSetOf<String>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]
            val compactLine = line.replace(" ", "")

            // 1) 알려진 처방약 우선 매칭
            val matchedSpec = knownSpecs.firstOrNull { spec ->
                spec.matchTokens.any { token -> compactLine.contains(token) }
            }
            if (matchedSpec != null) {
                val key = matchedSpec.displayName
                if (seenKeys.add(key)) {
                    val nearbyWindow = lines.drop(i).take(6).joinToString(" ")
                    val dosageMatch = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                        .find(line) ?: Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)
                        .find(nearbyWindow)
                    val dosage = dosageMatch?.groupValues?.get(1) ?: matchedSpec.defaultDosage

                    val freq = extractItemFrequency(nearbyWindow) ?: matchedSpec.defaultFreq
                    val days = extractItemDays(nearbyWindow) ?: defaultDays

                    val extraNotes = lines.drop(i + 1).take(4)
                        .filter { it.startsWith("[") || it.startsWith("(") || it.contains("보관") }
                        .distinct()

                    val notesList = mutableListOf<String>()
                    if (matchedSpec.infoHint.isNotBlank()) notesList.add(matchedSpec.infoHint)
                    notesList.addAll(extraNotes)

                    val med = ExtractedMedication(
                        drugName = matchedSpec.displayName,
                        quantityPerDose = defaultQty,
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
                    medications.add(med)
                    currentMed = med
                }
                continue
            }

            // 2) 일반 의약품명(~정, ~시럽 등) 동적 매칭
            if (!isExcludedLine(line) && !line.startsWith("[") && !line.startsWith("(")) {
                val gMatch = genericDrugRegex.find(compactLine)
                if (gMatch != null) {
                    val drugName = gMatch.groupValues[1]
                    if (!isExcludedLine(drugName) && seenKeys.add(drugName)) {
                        val dosage = gMatch.groupValues.getOrNull(2).orEmpty()
                        val nearbyWindow = lines.drop(i).take(5).joinToString(" ")
                        val freq = extractItemFrequency(nearbyWindow) ?: defaultFreq
                        val days = extractItemDays(nearbyWindow) ?: defaultDays

                        val notes = lines.drop(i + 1).take(3)
                            .filter { it.startsWith("[") || it.startsWith("(") || it.contains("치료제") || it.contains("소염제") || it.contains("근이완제") || it.contains("보관") }
                            .distinct()
                            .joinToString(" · ")

                        val med = ExtractedMedication(
                            drugName = drugName,
                            quantityPerDose = defaultQty,
                            dosage = dosage,
                            dosingCycle = "1일 ${freq}회",
                            dosesPerDay = freq,
                            totalDaysOrDoses = "${days}일",
                            medicationPeriod = "${days}일분",
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
            }

            // 3) 효능·보관 메모 연결
            if (currentMed != null && isDrugInfoNote(line)) {
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
        return line.startsWith("[") ||
            line.startsWith("(") ||
            line.contains("보관") ||
            line.contains("치료제") ||
            line.contains("소염제") ||
            line.contains("근이완제") ||
            line.contains("질환약") ||
            line.contains("가래약") ||
            line.contains("보호제")
    }

    private fun extractPharmacyName(lines: List<String>, rawText: String): String {
        val compactAll = rawText.replace(Regex("""\s+"""), "")
        if ("새솔약국" in compactAll) return "새솔약국"
        if ("송파웰약국" in compactAll || "우송웰약국" in compactAll || "웰약국" in compactAll) {
            return if ("송파웰약국" in compactAll) "송파웰약국" else "웰약국"
        }

        for (line in lines) {
            val compact = line.replace(" ", "")
            if (compact.endsWith("약국") && compact.length in 3..16 && !compact.contains("조제약")) {
                return if (compact.endsWith("새솔약국")) "새솔약국" else compact
            }
        }

        val match = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(compactAll)
        val found = match?.groupValues?.get(1).orEmpty()
        return if (found.endsWith("새솔약국")) "새솔약국" else found
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

    private fun extractItemFrequency(window: String): Int? {
        return Regex("""\b([1-6])\s*회\b""").find(window)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractItemDays(window: String): String? {
        return Regex("""\b(\d{1,2})\s*일\b""").find(window)?.groupValues?.get(1)
    }
}
