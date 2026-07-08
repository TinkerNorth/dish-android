// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import com.tinkernorth.dish.R
import com.tinkernorth.dish.databinding.ActivityLicensesBinding
import com.tinkernorth.dish.ui.common.BaseGamepadHostActivity
import com.tinkernorth.dish.ui.common.applyDishActivityTransitions
import com.tinkernorth.dish.ui.common.applyDishSystemBars
import com.tinkernorth.dish.ui.common.attachDonatePill
import com.tinkernorth.dish.ui.common.setupDishToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

@AndroidEntryPoint
class LicensesActivity : BaseGamepadHostActivity() {
    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installGamepadHost(binding.root)
        setupDishToolbar(binding.toolbar)
        applyDishSystemBars(binding.root)
        applyDishActivityTransitions()
        attachDonatePill()

        binding.toolbar.title = getString(R.string.settings_open_source_licenses_title)

        val manifest = loadManifest()
        binding.recyclerLicenses.layoutManager = LinearLayoutManager(this)
        binding.recyclerLicenses.adapter =
            LicensesAdapter(manifest.libraries) { entry -> openLicenseUrl(entry) }
    }

    private fun loadManifest(): LicenseManifest =
        assets.open("licenses/licenses.json").bufferedReader().use { reader ->
            JSON.decodeFromString<LicenseManifest>(reader.readText())
        }

    private fun openLicenseUrl(entry: LicenseEntry) {
        val url = entry.licenses.firstOrNull()?.url ?: entry.url ?: return
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
