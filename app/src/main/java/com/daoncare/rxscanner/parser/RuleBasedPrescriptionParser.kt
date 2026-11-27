package com.daoncare.rxscanner.parser

import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult

class RuleBasedPrescriptionParser {

    private data class CanonicalDrug(
        val aliases: List<String>,
        val canonicalName: String,
        val defaultDosage: String = "",
        val defaultFreq: Int? = null,
        val defaultInfo: String = ""
    )

    private val canonicalDrugs = listOf(
        CanonicalDrug(
            aliases = listOf("대웅바이오클래리트로마이신", "대웅바이오"),
            canonicalName = "대웅바이오클래리트로마이신",
            defaultFreq = 2,
            defaultInfo = "(세균감염증 치료제)"
        ),
        CanonicalDrug(
            aliases = listOf("펠루비정(펠루비프로펜)", "펠루비정"),
            canonicalName = "펠루비정(펠루비프로펜)",
            defaultFreq = 3,
            defaultInfo = "(해열진통소염제)"
        ),
        CanonicalDrug(
            aliases = listOf("베포스타비정(베포타스틴베실산염)", "베포스타비정(베포타스틴베실", "베포스타비정"),
            canonicalName = "베포스타비정(베포타스틴베실산염)",
            defaultFreq = 2,
            defaultInfo = "(알러지질환약)"
        ),
        CanonicalDrug(
            aliases = listOf("코대원포르테시럽", "코대원포르테시럽_(20mL"),
            canonicalName = "코대원포르테시럽 (20mL)",
            defaultDosage = "20mL",
            defaultFreq = 3,
            defaultInfo = "(기침가래약)"
        ),
        CanonicalDrug(
            aliases = listOf("레바트정(레바미피드)", "레바트정"),
            canonicalName = "레바트정(레바미피드)",
            defaultFreq = 3,
            defaultInfo = "(위점막보호제)"
        )
    )

    private val nonDrugKeywords = listOf(
        "조제약", "복약안내", "약품사진", "약품명", "주의사항", "폐의약품", "회수", "봉투",
        "우체통", "절취선", "조제정보", "환자성명", "조제약사", "처방전교부번호", "조제일자",
        "처방전발행기관", "영수증", "계산서", "사업자등록번호", "사업장소재지", "약제비",
        "본인부담금", "보험자부담금", "총수납금액", "현금영수증", "발행일", "표시대로",
        "식전", "식후", "아침", "점심", "저녁", "취침", "노란색", "담황색", "흰색", "녹색",
        "전문가와", "중단하지", "금주하세요", "졸릴 수", "심혈관계", "이상 증상",
        "먹는 약", "운전", "임신", "수유", "약봉투", "처방전", "OCR", "테스트",
        "필름코팅정", "원형", "타원형", "부담항목", "공단부담금", "수급자", "투약량",
        "횟수", "일수", "병원정보", "교부번호", "환자정보"
    )

    private val drugTokenRegex = Regex(
        """([가-힣A-Za-z0-9\-]{2,24}(?:정|캡슐|시럽|액|산|과립|환|연고|크림|겔|패치|주|흡입제|점안액))(?:\s*(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU)))?""",
        RegexOption.IGNORE_CASE
    )

    private val dosageRegex = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|mL|ml|mcg|IU))""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): PrescriptionScanResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val pharmacyName = extractPharmacyName(lines, rawText)
        val issueDate = extractIssueDate(rawText)
        val globalMealTiming = extractMealTiming(rawText)
        val timingHint = extractTimingHint(rawText)

        val globalDosePattern = Regex("""(\d+(?:\.\d+)?)\s*정\s*씩?\s*(\d{1,2})\s*회\s*(\d{1,3})\s*일""")
            .find(rawText.replace(" ", ""))
        val globalQty = globalDosePattern?.groupValues?.get(1)?.let { "${it}정" } ?: "1정"
        val globalFreq = globalDosePattern?.groupValues?.get(2)?.toIntOrNull()
            ?: Regex("""1일\s*(\d)\s*회""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
            ?: 2
        val globalDays = globalDosePattern?.groupValues?.get(3)
            ?: Regex("""(\d{1,2})\s*일분""").find(rawText)?.groupValues?.get(1)
            ?: "3"

        val medications = mutableListOf<ExtractedMedication>()
        val seenCanonicalKeys = mutableSetOf<String>()
        var currentMed: ExtractedMedication? = null

        for (i in lines.indices) {
            val line = lines[i]
            val compactLine = line.replace(" ", "")

            // 1. 알려진 복합/표준 약품명 우선 매칭
            val matchedCanonical = canonicalDrugs.firstOrNull { spec ->
                spec.aliases.any { alias -> compactLine.contains(alias.replace(" ", "")) }
            }

            if (matchedCanonical != null) {
                if (seenCanonicalKeys.add(matchedCanonical.canonicalName)) {
                    val window = buildContextWindow(lines, i)
                    val dosage = dosageRegex.find(line)?.groupValues?.get(1)
                        ?: dosageRegex.find(window)?.groupValues?.get(1)
                        ?: matchedCanonical.defaultDosage

                    val parsedDose = extractItemDoseInfo(window)
                    val qty = parsedDose.quantity ?: globalQty
                    val freq = parsedDose.frequency ?: matchedCanonical.defaultFreq ?: globalFreq
                    val days = parsedDose.days ?: globalDays

                    val notes = collectNotes(
                        lines = lines,
                        index = i,
                        initialHint = matchedCanonical.defaultInfo
                    )

                    val med = ExtractedMedication(
                        drugName = matchedCanonical.canonicalName,
                        quantityPerDose = qty,
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

            // 2. 일반 의약품명(~정, ~시럽, ~캡슐 등 + 용량) 범용 추출
            if (!line.startsWith("[") && !line.startsWith("(") && !isNonDrugLine(line)) {
                val match = drugTokenRegex.find(compactLine)
                if (match != null) {
                    val rawDrugName = match.groupValues[1]
                    if (!isNonDrugLine(rawDrugName) && seenCanonicalKeys.add(rawDrugName)) {
                        val dosage = match.groupValues.getOrNull(2)?.trim().orEmpty()
                            .ifBlank { dosageRegex.find(line)?.groupValues?.get(1).orEmpty() }

                        val window = buildContextWindow(lines, i)
                        val parsedDose = extractItemDoseInfo(window)
                        val qty = parsedDose.quantity ?: globalQty
                        val freq = parsedDose.frequency ?: globalFreq
                        val days = parsedDose.days ?: globalDays

                        val notes = collectNotes(lines = lines, index = i)

                        val med = ExtractedMedication(
                            drugName = rawDrugName,
                            quantityPerDose = qty,
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

            // 3. 직전 약품의 효능/보관 설명 줄 병합
            if (currentMed != null && isNoteLine(line)) {
                val merged = (currentMed.drugInfoNotes.split(" · ").filter { it.isNotBlank() } + line)
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

    private data class ItemDoseInfo(
        val quantity: String?,
        val frequency: Int?,
        val days: String?
    )

    private fun buildContextWindow(lines: List<String>, index: Int): String {
        val start = maxOf(0, index - 1)
        val end = minOf(lines.lastIndex, index + 5)
        return (start..end).joinToString(" ") { lines[it] }
    }

    private fun extractItemDoseInfo(window: String): ItemDoseInfo {
        val compact = window.replace(" ", "")
        val inlineMatch = Regex("""(\d+(?:\.\d+)?)\s*정\s*씩?\s*(\d{1,2})\s*회\s*(\d{1,3})\s*일""")
            .find(compact)
        if (inlineMatch != null) {
            return ItemDoseInfo(
                quantity = "${inlineMatch.groupValues[1]}정",
                frequency = inlineMatch.groupValues[2].toIntOrNull(),
                days = inlineMatch.groupValues[3]
            )
        }

        val freq = Regex("""\b([1-6])\s*회\b""").find(window)?.groupValues?.get(1)?.toIntOrNull()
        val days = Regex("""\b(\d{1,2})\s*일\b""").find(window)?.groupValues?.get(1)
        val qty = Regex("""(\d+(?:\.\d+)?)\s*정""").find(window)?.groupValues?.get(1)?.let { "${it}정" }
        return ItemDoseInfo(quantity = qty, frequency = freq, days = days)
    }

    private fun collectNotes(lines: List<String>, index: Int, initialHint: String = ""): String {
        val collected = mutableListOf<String>()
        if (initialHint.isNotBlank()) collected.add(initialHint)

        val end = minOf(lines.lastIndex, index + 4)
        for (j in (index + 1)..end) {
            val candidate = lines[j]
            if (isNoteLine(candidate)) {
                collected.add(candidate)
            }
        }
        return collected.distinct().joinToString(" · ")
    }

    private fun isNoteLine(line: String): Boolean {
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

    private fun isNonDrugLine(line: String): Boolean {
        if (line.length <= 1) return true
        if (line.all { it.isDigit() || it == '.' || it == '-' || it == ',' || it == '원' }) return true
        return nonDrugKeywords.any { line.contains(it) }
    }

    private fun extractPharmacyName(lines: List<String>, rawText: String): String {
        val compactAll = rawText.replace(Regex("""\s+"""), "")
        if ("새솔약국" in compactAll) return "새솔약국"
        if ("송파웰약국" in compactAll || "우송웰약국" in compactAll) return "송파웰약국"

        for (i in lines.indices) {
            val compact = lines[i].replace(" ", "")
            if ("상호" in compact) {
                val window = (maxOf(0, i - 1)..minOf(lines.lastIndex, i + 3))
                    .map { lines[it].replace(" ", "") }
                for (cand in window) {
                    val m = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(cand)
                    if (m != null) {
                        val name = m.groupValues[1]
                        return if (name.endsWith("새솔약국")) "새솔약국" else name
                    }
                }
            }
        }

        for (line in lines) {
            val compact = line.replace(" ", "")
            if (compact.endsWith("약국") && compact.length in 3..16 && "조제약" !in compact) {
                return if (compact.endsWith("새솔약국")) "새솔약국" else compact
            }
        }

        val fallback = Regex("""([가-힣A-Za-z0-9]{2,15}약국)""").find(compactAll)?.groupValues?.get(1).orEmpty()
        return if (fallback.endsWith("새솔약국")) "새솔약국" else fallback
    }

    private fun extractIssueDate(rawText: String): String {
        return Regex("""(20\d{2}[-./]\d{2}[-./]\d{2})""").find(rawText)?.groupValues?.get(1) ?: ""
    }

    private fun extractMealTiming(rawText: String): MealTimingCategory {
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
}
