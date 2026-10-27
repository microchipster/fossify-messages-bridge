/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.bridge.CdnThreshold
import org.fossify.messages.databinding.DialogCdnThresholdBinding

/**
 * Asks for a hand-typed "send files larger than" size in MB and validates it
 * against [CdnThreshold]. The comparison happens here so a typo never leaves a
 * nonsense threshold in the preferences.
 */
class CdnThresholdDialog(
    private val activity: Activity,
    private val currentMb: Int,
    private val callback: (Int) -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogCdnThresholdBinding.inflate(activity.layoutInflater).apply {
            cdnThresholdEditText.apply {
                if (currentMb >= CdnThreshold.MIN_MB) {
                    setText(currentMb.toString())
                    setSelection(text?.length ?: 0)
                }
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.cdn_threshold_custom_title) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.cdnThresholdEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val mb = binding.cdnThresholdEditText.text.toString().trim().toIntOrNull()
                        if (mb == null || mb !in CdnThreshold.MIN_MB..CdnThreshold.MAX_MB) {
                            activity.toast(
                                activity.getString(
                                    R.string.cdn_threshold_invalid,
                                    CdnThreshold.MIN_MB,
                                    CdnThreshold.MAX_MB,
                                )
                            )
                            return@setOnClickListener
                        }

                        callback(mb)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
