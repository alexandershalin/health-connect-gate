package com.bishop.healthconnectgate

import android.app.Activity
import android.os.Bundle
import android.text.util.Linkify
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.app_name) + " uses Health Connect to synchronize your health data with the server you configure.\n\n" +
                "Privacy policy: https://github.com/alexandershalin/health-connect-gate/blob/main/PRIVACY.md"
            Linkify.addLinks(this, Linkify.WEB_URLS)
            setPadding(32, 48, 32, 32)
        })
    }
}