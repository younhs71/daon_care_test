package com.daoncare.rxscanner.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.daoncare.rxscanner.model.ExtractedMedication
import com.daoncare.rxscanner.model.MealTimingCategory
import com.daoncare.rxscanner.model.PrescriptionScanResult
import com.daoncare.rxscanner.model.parseMealTiming
import com.daoncare.rxscanner.repository.PrescriptionScanRepository
import kotlinx.coroutines.launch
import java.io.File

private fun createTempImageUri(context: Context): Uri {
val dir = File(context.cacheDir, "rx_images").apply { mkdirs() }
val file = File.createTempFile("rx_${System.currentTimeMillis()}_", ".jpg", dir)
return FileProvider.getUriForFile(
    context,
    "${context.packageName}.fileprovider",
    file
)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionScannerScreen(
repository: PrescriptionScanRepository,
onConfirmSaved: (PrescriptionScanResult) -> Unit
) {
val context = LocalContext.current
val scope = rememberCoroutineScope()

var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
var isScanning by remember { mutableStateOf(false) }
var errorMessage by remember { mutableStateOf<String?>(null) }
var savedBanner by remember { mutableStateOf<String?>(null) }

var pharmacyName by remember { mutableStateOf("") }
var issueDateText by remember { mutableStateOf("") }
var issueCountText by remember { mutableStateOf("1") }
var medications by remember { mutableStateOf<List<ExtractedMedication>>(emptyList()) }
var rawOcrText by remember { mutableStateOf("") }
var engineBadge by remember { mutableStateOf("") }

fun runScan(uri: Uri) {
    scope.launch {
        isScanning = true
        errorMessage = null
        savedBanner = null
        try {
            val result = repository.scanImage(uri)
            pharmacyName = result.pharmacyName.ifBlank { result.hospitalOrPharmacy }
            issueDateText = result.issueDateText
            issueCountText = result.issueCount?.toString().orEmpty()
            medications = if (result.medications.isNotEmpty()) {
                result.medications
            } else {
                listOf(ExtractedMedication(drugName = "", isLowConfidence = true))
            }
            rawOcrText = result.rawOcrText
            engineBadge = if (result.usedGeminiNano) "ML Kit OCR + Gemini Nano" else "ML Kit OCR + 규칙 파서"
        } catch (e: Exception) {
            errorMessage = "인식 중 오류가 발생했습니다: ${e.localizedMessage ?: "다시 시도해 주세요."}"
        } finally {
            isScanning = false
        }
    }
}

val takePictureLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
) { success ->
    val uri = pendingCameraUri
    if (success && uri != null) {
        runScan(uri)
    }
}

val cameraPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        val uri = createTempImageUri(context)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    } else {
        errorMessage = "카메라 권한이 필요합니다. 갤러리 선택을 이용하거나 권한을 허용해 주세요."
    }
}

val galleryLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri: Uri? ->
    if (uri != null) {
        runScan(uri)
    }
}

Scaffold(
    topBar = {
        TopAppBar(title = { Text("약봉투·처방전 OCR 테스트") })
    }
) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f).height(60.dp)
                ) {
                    Text("사진 촬영")
                }
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f).height(60.dp)
                ) {
                    Text("갤러리 선택")
                }
            }
        }

        if (isScanning) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("약봉투·처방전 항목을 인식하고 있어요...")
                }
            }
        }

        errorMessage?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        savedBanner?.let { banner ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = banner,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (engineBadge.isNotBlank() || medications.isNotEmpty()) {
            item {
                Text(
                    text = "엔진: $engineBadge · 의사 상담용 참고 추출이며 저장 전 직접 확인하세요.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("문서 공통 정보", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = pharmacyName,
                            onValueChange = { pharmacyName = it },
                            label = { Text("약국이름") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = issueDateText,
                                onValueChange = { issueDateText = it },
                                label = { Text("발행일자") },
                                modifier = Modifier.weight(1.3f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = issueCountText,
                                onValueChange = { issueCountText = it.filter(Char::isDigit) },
                                label = { Text("발행횟수") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            itemsIndexed(medications) { index, med ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (med.isLowConfidence) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "약 #${index + 1}${if (med.isLowConfidence) " (확인 필요)" else ""}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = med.drugName,
                            onValueChange = { v ->
                                medications = medications.toMutableList().apply {
                                    this[index] = med.copy(drugName = v)
                                }
                            },
                            label = { Text("약이름") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = med.quantityPerDose,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(quantityPerDose = v)
                                    }
                                },
                                label = { Text("갯수(예: 1정)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = med.dosage,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(dosage = v)
                                    }
                                },
                                label = { Text("용량(예: 0.1mg)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = med.dosingCycle,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(dosingCycle = v)
                                    }
                                },
                                label = { Text("복용주기") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = med.totalDaysOrDoses,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(totalDaysOrDoses = v)
                                    }
                                },
                                label = { Text("투약일수") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = med.medicationPeriod,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(medicationPeriod = v)
                                    }
                                },
                                label = { Text("복용기간") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = if (med.mealTiming == MealTimingCategory.UNSPECIFIED) "" else med.mealTiming.label,
                                onValueChange = { v ->
                                    medications = medications.toMutableList().apply {
                                        this[index] = med.copy(mealTiming = parseMealTiming(v))
                                    }
                                },
                                label = { Text("식전/식후 구분") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = med.drugInfoNotes,
                            onValueChange = { v ->
                                medications = medications.toMutableList().apply {
                                    this[index] = med.copy(drugInfoNotes = v)
                                }
                            },
                            label = { Text("약정보") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val finalResult = PrescriptionScanResult(
                            documentType = "확정 처방/투약 정보",
                            pharmacyName = pharmacyName.trim(),
                            hospitalOrPharmacy = pharmacyName.trim(),
                            issueDateText = issueDateText.trim(),
                            issueCount = issueCountText.toIntOrNull(),
                            medications = medications.filter { it.drugName.isNotBlank() },
                            rawOcrText = rawOcrText,
                            usedGeminiNano = engineBadge.contains("Gemini Nano")
                        )
                        onConfirmSaved(finalResult)
                        savedBanner = "약 ${finalResult.medications.size}건 확인 완료"
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) {
                    Text("확인 후 저장")
                }
            }

            if (rawOcrText.isNotBlank()) {
                item {
                    Text(
                        text = "원본 OCR 텍스트:\n$rawOcrText",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
}
