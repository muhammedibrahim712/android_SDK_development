package com.cardscanai.insurancecardscan

import android.content.Context
import android.content.Intent
import android.util.Log
import org.opencv.android.OpenCVLoader

class CardScanView(private val context: Context) {

    fun launch() {
        Log.d("library", "launching")
        context.startActivity(Intent(context, CardScanViewUI::class.java))
    }

}