package org.rivchain.cuplink

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.widget.Toolbar

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        title = getString(R.string.menu_about)

        val toolbar = findViewById<Toolbar>(R.id.about_toolbar)
        toolbar.apply {
            setNavigationOnClickListener {
                finish()
            }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }

        findViewById<TextView>(R.id.licenseTV).setOnClickListener {
            val intent = Intent(this, LicenseActivity::class.java)
            startActivity(intent)
        }
        findViewById<TextView>(R.id.splashText).text = "CupLink v${BuildConfig.VERSION_NAME}"
    }
}