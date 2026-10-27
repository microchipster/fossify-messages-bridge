/*
 * SPDX-FileCopyrightText: 2026 microchipster
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.fossify.messages.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.messages.R
import org.fossify.messages.databinding.DialogRedactionPatternsBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.RedactionConfig
import org.fossify.messages.helpers.RedactionPreset

/**
 * Enables the redaction presets and edits the custom `name=regex` extension
 * patterns. Matching messages are deleted regardless of their age.
 */
class RedactionPatternsDialog(
    private val activity: BaseSimpleActivity,
    private val callback: () -> Unit,
) {
    init {
        val binding = DialogRedactionPatternsBinding.inflate(activity.layoutInflater)
        val redactions = activity.config.historyRedactions
        binding.apply {
            redactionPresetOtp.isChecked = RedactionPreset.OTP in redactions.presets
            redactionPresetPasswordReset.isChecked = RedactionPreset.PASSWORD_RESET in redactions.presets
            redactionPresetCardNumber.isChecked = RedactionPreset.CARD_NUMBER in redactions.presets
            redactionPresetSsn.isChecked = RedactionPreset.SSN in redactions.presets
            redactionCustomEdittext.setText(RedactionConfig.extensionsText(redactions.extensions))
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        save(binding, alertDialog)
                    }
                }
            }
    }

    private fun save(binding: DialogRedactionPatternsBinding, alertDialog: AlertDialog) {
        val custom = RedactionConfig.parseExtensions(binding.redactionCustomEdittext.value)
        if (custom == null) {
            activity.toast(R.string.redaction_patterns_invalid)
            return
        }
        activity.config.historyRedactions = RedactionConfig(selectedPresets(binding), custom)
        callback()
        alertDialog.dismiss()
    }

    private fun selectedPresets(binding: DialogRedactionPatternsBinding): Set<RedactionPreset> {
        val presets = mutableSetOf<RedactionPreset>()
        if (binding.redactionPresetOtp.isChecked) {
            presets.add(RedactionPreset.OTP)
        }
        if (binding.redactionPresetPasswordReset.isChecked) {
            presets.add(RedactionPreset.PASSWORD_RESET)
        }
        if (binding.redactionPresetCardNumber.isChecked) {
            presets.add(RedactionPreset.CARD_NUMBER)
        }
        if (binding.redactionPresetSsn.isChecked) {
            presets.add(RedactionPreset.SSN)
        }
        return presets
    }
}
