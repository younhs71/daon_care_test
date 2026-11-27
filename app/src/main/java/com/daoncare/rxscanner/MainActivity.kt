package com.daoncare.rxscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import android.util.Log
import com.daoncare.rxscanner.repository.PrescriptionScanRepository
import com.daoncare.rxscanner.ui.PrescriptionScannerScreen

class MainActivity : ComponentActivity() {

private lateinit var scanRepository: PrescriptionScanRepository

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    scanRepository = PrescriptionScanRepository(this)

    setContent {
        MaterialTheme {
            PrescriptionScannerScreen(
                repository = scanRepository,
                onConfirmSaved = { confirmed ->
                    Log.d(
                        "DaonCareRxTest",
                        "약국=${confirmed.pharmacyName}, 발행일=${confirmed.issueDateText}, " +
                            "발행횟수=${confirmed.issueCount}, 약수=${confirmed.medications.size}"
                    )
                    confirmed.medications.forEach { med ->
                        Log.d(
                            "DaonCareRxTest",
                            "약=${med.drugName}, 갯수=${med.quantityPerDose}, 용량=${med.dosage}, " +
                                "주기=${med.dosingCycle}, 기간=${med.medicationPeriod}, " +
                                "투약일수=${med.totalDaysOrDoses}, 식전후=${med.mealTiming.label}, " +
                                "약정보=${med.drugInfoNotes}"
                        )
                    }
                }
            )
        }
    }
}

override fun onDestroy() {
    scanRepository.release()
    super.onDestroy()
}
}
