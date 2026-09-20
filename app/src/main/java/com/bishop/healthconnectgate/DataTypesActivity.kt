package com.bishop.healthconnectgate

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.HealthConnectClient

/** Lets the user choose which categories of Health Connect data are synchronized. Every change is saved at once. */
class DataTypesActivity : ComponentActivity() {
    private val settings by lazy { Settings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        title = getString(R.string.types_title)
        val chosen = DataSelection.effectiveIds(settings.selectedCategoryIds).toMutableSet()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = getString(R.string.types_title); textSize = 20f })
        column.addView(TextView(this).apply { text = getString(R.string.types_intro); setPadding(0, dp(8), 0, dp(12)) })
        for (category in DataCategory.entries) {
            column.addView(CheckBox(this).apply {
                val name = getString(category.titleRes)
                text = if (category.sensitive) getString(R.string.sensitive_suffix, name) else name
                isChecked = category.id in chosen
                setOnCheckedChangeListener { _, checked ->
                    if (checked) chosen.add(category.id) else chosen.remove(category.id)
                    settings.selectedCategoryIds = chosen.toSet()
                }
            })
            column.addView(TextView(this).apply {
                text = getString(category.summaryRes); textSize = 12f; setPadding(dp(32), 0, 0, dp(10))
            })
        }
        column.addView(TextView(this).apply { text = getString(R.string.types_revoke_note); setPadding(0, dp(8), 0, dp(8)) })
        column.addView(Button(this).apply {
            text = getString(R.string.open_health_connect_settings)
            setOnClickListener {
                try { startActivity(HealthConnectClient.getHealthConnectManageDataIntent(this@DataTypesActivity)) } catch (_: ActivityNotFoundException) { }
            }
        })
        column.addView(Button(this).apply { text = getString(R.string.done); setOnClickListener { finish() } })
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        applyInsets(scroll, dp(16))
    }
}
