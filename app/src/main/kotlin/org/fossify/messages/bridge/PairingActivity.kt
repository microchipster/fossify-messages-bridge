/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivityPairingBinding

/**
 * Settings screen of the bridge pairing: scan the QR code shown by the bridge
 * bot (or paste the payload), or type the homeserver URL, the optional bridge
 * URL and the one-time pairing code.
 *
 * The activity is not exported; it is reachable from the app settings (and
 * from adb on debuggable builds through `run-as`).
 */
class PairingActivity : SimpleActivity() {
    companion object {
        private const val TAG = "PairingActivity"
    }

    private val binding by viewBinding(ActivityPairingBinding::inflate)
    private val store by lazy { PairingStore.newInstance(this) }

    private var pairingInProgress = false

    private val scanCode = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            binding.pairingPayload.setText(contents)
            pairingInProgress = false
            usePayload(contents)
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanQrCode()
        } else {
            toast(R.string.bridge_pairing_camera_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.pairingScrollview))
        updateTextColors(binding.pairingWrapper)

        binding.pairingScan.setOnClickListener { scanQrCode() }
        binding.pairingUsePayload.setOnClickListener {
            usePayload(binding.pairingPayload.text?.toString().orEmpty())
        }
        binding.pairingPair.setOnClickListener { startPairing() }
        binding.pairingUnpair.setOnClickListener { unpair() }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.pairingAppbar, NavigationIcon.Arrow)
        binding.pairingUnpair.beVisibleIf(store.isPaired)
        binding.pairingStatus.text = currentStatusText()
    }

    private fun currentStatusText(): String {
        if (!store.isPaired) {
            return getString(R.string.bridge_pairing_status_not_paired)
        }
        val user = store.pairedUser
        return if (user.isNotEmpty()) {
            getString(R.string.bridge_pairing_status_paired, user, store.bridgeBaseUrl)
        } else {
            getString(R.string.bridge_pairing_status_paired_no_user, store.bridgeBaseUrl)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scanQrCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.bridge_pairing_scan_qr))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        try {
            scanCode.launch(options)
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch the QR scanner", e)
            toast(R.string.bridge_pairing_scan_failed)
        }
    }

    private fun usePayload(payload: String) {
        val data = parsePairingPayload(payload)
        if (data == null) {
            toast(R.string.bridge_pairing_invalid_payload)
            return
        }

        binding.pairingHomeserverUrl.setText(data.homeserverUrl)
        binding.pairingBridgeUrl.setText(data.bridgeUrl)
        binding.pairingCode.setText(data.code)
        toast(R.string.bridge_pairing_payload_loaded)
        startPairing()
    }

    private fun startPairing() {
        if (pairingInProgress) {
            return
        }

        val homeserverUrl = binding.pairingHomeserverUrl.text?.toString().orEmpty().trim()
        val bridgeUrl = binding.pairingBridgeUrl.text?.toString().orEmpty().trim()
        val code = binding.pairingCode.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            toast(R.string.bridge_pairing_missing_code)
            return
        }
        if (homeserverUrl.isEmpty()) {
            toast(R.string.bridge_pairing_missing_hs_url)
            return
        }

        setPairingInProgress(true)
        ensureBackgroundThread {
            val result = runCatching { PairingClient.pair(this@PairingActivity, code, homeserverUrl, bridgeUrl) }
            runOnUiThread {
                setPairingInProgress(false)
                result.onSuccess {
                    BridgeService.maybeStart(this@PairingActivity)
                    binding.pairingUnpair.beVisibleIf(store.isPaired)
                    binding.pairingStatus.text = currentStatusText()
                    toast(currentStatusText())
                }.onFailure { error ->
                    val message = (error as? PairingException)
                        ?.let { "${it.code}: ${it.message}" }
                        ?: error.message
                        ?: error.javaClass.simpleName
                    binding.pairingStatus.text = getString(R.string.bridge_pairing_failed, message)
                    toast(message)
                }
            }
        }
    }

    private fun unpair() {
        store.clearPairing()
        toast(R.string.bridge_pairing_unpaired)
        BridgeService.maybeStart(this)
        binding.pairingUnpair.beVisibleIf(store.isPaired)
        binding.pairingStatus.text = currentStatusText()
    }

    private fun setPairingInProgress(inProgress: Boolean) {
        pairingInProgress = inProgress
        binding.pairingProgress.beVisibleIf(inProgress)
        binding.pairingPair.isEnabled = !inProgress
        binding.pairingScan.isEnabled = !inProgress
        binding.pairingUsePayload.isEnabled = !inProgress
        binding.pairingUnpair.isEnabled = !inProgress
        if (inProgress) {
            binding.pairingStatus.setText(R.string.bridge_pairing_in_progress)
        }
    }

    private data class PairingPayload(val homeserverUrl: String, val bridgeUrl: String, val code: String)

    private fun parsePairingPayload(payload: String): PairingPayload? {
        val uri = Uri.parse(payload.trim())
        val schemeMatches = uri.scheme.equals(PairingClient.PAIRING_SCHEME, ignoreCase = true)
        val hostMatches = uri.host.equals(PairingClient.PAIRING_HOST, ignoreCase = true)
        if (!schemeMatches || !hostMatches) {
            return null
        }

        val homeserverUrl = uri.getQueryParameter("hs").orEmpty().trim()
        val bridgeUrl = uri.getQueryParameter("b").orEmpty().trim()
        val code = uri.getQueryParameter("code").orEmpty().trim()
        if (homeserverUrl.isEmpty() || code.isEmpty()) {
            return null
        }
        return PairingPayload(homeserverUrl = homeserverUrl, bridgeUrl = bridgeUrl, code = code)
    }
}
