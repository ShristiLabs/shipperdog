package com.shipperdog.demo.banking

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** The demo build's whole UI: proof of life once installed. */
class DemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        label.text = "Banking demo build installed by the ShipperDog companion"
        label.textSize = 18f
        label.gravity = Gravity.CENTER
        setContentView(label)
    }
}
