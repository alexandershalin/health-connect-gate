package com.bishop.healthconnectgate

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.app_name) + " uses Health Connect to synchronize your health data."; setPadding(32, 48, 32, 32) })
    }
}