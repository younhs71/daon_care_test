package com.daoncare.rxscanner.contract

import com.daoncare.rxscanner.model.PrescriptionScanResult

interface RxScannerMergeSink {
suspend fun onPrescriptionConfirmed(result: PrescriptionScanResult)
}
