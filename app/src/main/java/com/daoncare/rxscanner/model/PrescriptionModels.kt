package com.daoncare.rxscanner.model

import com.google.mlkit.genai.prompt.GeneratePromptRequest
import com.google.mlkit.genai.prompt.Generable
import com.google.mlkit.genai.prompt.Guide

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

@Generable
data class AiMedicationItem(
@Guide("약이름. 불명확하면 빈 문자열")
val drugName: String,
@Guide("1회 복용 갯수(예: 1정, 1포, 2캡슐). 없으면 빈 문자열")
val quantityPerDose: String,
@Guide("용량 또는 함량(예: 0.1mg, 10밀리그램, 5mL). 없으면 빈 문자열")
val dosage: String,
@Guide("복용주기(예: 1일 3회, 하루 2번, 아침·저녁, 격일). 없으면 빈 문자열")
val dosingCycle: String,
@Guide("하루 투약 횟수 숫자. 모르면 -1")
val dosesPerDay: Int,
@Guide("투약일수 또는 총 투약량/횟수(예: 28일분, 남은투약일수 19일). 없으면 빈 문자열")
val totalDaysOrDoses: String,
@Guide("복용기간(예: 2026-08-29 ~, 7일간). 없으면 빈 문자열")
val medicationPeriod: String,
@Guide("식전/식후 구분: 식전, 식후, 공복, 취침 전, 필요 시(PRN), 미지정 중 하나")
val mealTiming: String,
@Guide("복용 시점 힌트(예: 식후 30분, 아침·점심·저녁). 없으면 빈 문자열")
val timingHint: String,
@Guide("문서에 적힌 약 관련 안내·주의사항 원문 요약. 없으면 빈 문자열")
val drugInfoNotes: String,
@Guide("오인식 가능성이 있거나 사용자 확인이 필요하면 true")
val needsReview: Boolean
)

@Generable
data class AiPrescriptionExtraction(
@Guide("문서 유형: 약봉투 또는 처방전")
val documentType: String,
@Guide("약국이름(조제 약국 또는 원내약국). 없으면 빈 문자열")
val pharmacyName: String,
@Guide("발행일자 또는 조제일자(예: 2026-10-16). 없으면 빈 문자열")
val issueDateText: String,
@Guide("처방전 발행 횟수. 알 수 없으면 -1")
val issueCount: Int,
@Guide("추출된 약품 목록")
val medications: List<AiMedicationItem>
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

fun buildPrescriptionPromptRequest(ocrText: String): GeneratePromptRequest {
val prompt = """
    다음은 한국 약봉투 또는 처방전에서 OCR로 읽은 텍스트입니다.
    문서에 실제로 보이는 내용만 바탕으로 아래 항목을 정확히 추출하세요.
    - 약국이름(pharmacyName), 발행일자(issueDateText), 발행횟수(issueCount)
    - 각 약품의 약이름(drugName), 갯수(quantityPerDose), 용량(dosage),
      복용주기(dosingCycle, dosesPerDay), 투약일수(totalDaysOrDoses),
      복용기간(medicationPeriod), 식전/식후 구분(mealTiming),
      복용 시점 힌트(timingHint), 약정보(drugInfoNotes)
    추측하여 만들어내지 말고, 불확실한 항목은 needsReview를 true로 설정하세요.

    [OCR 텍스트]
    $ocrText
""".trimIndent()
return GeneratePromptRequest.builder(prompt).build()
}
