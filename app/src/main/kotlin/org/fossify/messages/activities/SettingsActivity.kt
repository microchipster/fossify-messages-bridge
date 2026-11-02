package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatWithDeprecatedBadge
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.bridge.BridgeConfig
import org.fossify.messages.bridge.CdnDiscovery
import org.fossify.messages.bridge.CdnExpiry
import org.fossify.messages.bridge.CdnSendMode
import org.fossify.messages.bridge.CdnSettings
import org.fossify.messages.bridge.CdnThreshold
import org.fossify.messages.bridge.PairingActivity
import org.fossify.messages.bridge.PairingStore
import org.fossify.messages.databinding.ActivitySettingsBinding
import org.fossify.messages.dialogs.CdnThresholdDialog
import org.fossify.messages.dialogs.ExportMessagesDialog
import org.fossify.messages.dialogs.RedactionPatternsDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.emptyMessagesRecycleBin
import org.fossify.messages.extensions.historyRetentionText
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.runHistoryCleanup
import org.fossify.messages.helpers.FILE_SIZE_100_KB
import org.fossify.messages.helpers.FILE_SIZE_1_MB
import org.fossify.messages.helpers.FILE_SIZE_200_KB
import org.fossify.messages.helpers.FILE_SIZE_2_MB
import org.fossify.messages.helpers.FILE_SIZE_300_KB
import org.fossify.messages.helpers.FILE_SIZE_600_KB
import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.fossify.messages.helpers.HistoryRetention
import org.fossify.messages.helpers.LOCK_SCREEN_NOTHING
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER_MESSAGE
import org.fossify.messages.helpers.MessagesImporter
import org.fossify.messages.helpers.refreshConversations
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(org.fossify.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.settingsNestedScrollview,
            topAppBar = binding.settingsAppbar
        )
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeNotifications()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageBlockedKeywords()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupShowCharacterCounter()
        setupUseSimpleCharacters()
        setupSendOnEnter()
        setupEnableDeliveryReports()
        setupSendLongMessageAsMMS()
        setupGroupMessageAsMMS()
        setupKeepConversationsArchived()
        setupLockScreenVisibility()
        setupMMSFileSizeLimit()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        setupHistoryRetention()
        setupHistoryRedactions()
        setupHistoryCleanupNow()
        setupAppPasswordProtection()
        setupMessagesExport()
        setupMessagesImport()
        setupBridgePairing()
        setupCdnSettings()
        updateTextColors(binding.settingsNestedScrollview)

        if (
            blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()
        ) {
            refreshConversations()
        }

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsOutgoingMessagesLabel,
            binding.settingsNotificationsLabel,
            binding.settingsArchivedMessagesLabel,
            binding.settingsRecycleBinLabel,
            binding.settingsSecurityLabel,
            binding.settingsMigratingLabel,
            binding.settingsBridgeLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            exportMessagesDialog = ExportMessagesDialog(this) { fileName ->
                saveDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    private fun setupBridgePairing() = binding.apply {
        val store = PairingStore.newInstance(this@SettingsActivity)
        settingsBridgePairing.text = if (store.isPaired) {
            store.pairedUser.ifEmpty { getString(R.string.bridge_pairing_status_paired_no_user, store.bridgeBaseUrl) }
        } else {
            getString(R.string.bridge_pairing_status_not_paired)
        }
        settingsBridgePairingHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, PairingActivity::class.java))
        }
    }

    private fun setupCdnSettings() = binding.apply {
        val bridge = BridgeConfig.newInstance(this@SettingsActivity)

        settingsCdnThreshold.text = getCdnThresholdText(bridge.cdnThresholdMb)
        settingsCdnThresholdHolder.setOnClickListener {
            showCdnThresholdPicker(bridge)
        }

        settingsCdnSendMode.text = getCdnSendModeText(bridge.cdnSendMode)
        settingsCdnSendModeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(1, getString(R.string.cdn_mode_ask), CdnSendMode.ASK),
                RadioItem(2, getString(R.string.cdn_mode_link), CdnSendMode.LINK),
                RadioItem(3, getString(R.string.cdn_mode_never), CdnSendMode.NEVER),
            )
            val checkedItemId = items.first { it.value == bridge.cdnSendMode }.id
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                bridge.cdnSendMode = it as CdnSendMode
                settingsCdnSendMode.text = getCdnSendModeText(bridge.cdnSendMode)
            }
        }

        settingsCdnAskCompress.isChecked = bridge.cdnAskCompress
        settingsCdnAskCompressHolder.setOnClickListener {
            settingsCdnAskCompress.toggle()
            bridge.cdnAskCompress = settingsCdnAskCompress.isChecked
        }

        settingsCdnUploadLimit.text = getString(R.string.cdn_upload_limit_mb, bridge.cdnUploadLimitMb)
        settingsCdnUploadLimitHolder.setOnClickListener {
            val items = arrayListOf<RadioItem>().apply {
                CdnSettings.UPLOAD_LIMIT_CHOICES.forEach { mb ->
                    add(RadioItem(mb, getString(R.string.cdn_upload_limit_mb, mb)))
                }
            }
            RadioGroupDialog(this@SettingsActivity, items, bridge.cdnUploadLimitMb) {
                bridge.cdnUploadLimitMb = it as Int
                settingsCdnUploadLimit.text = getString(R.string.cdn_upload_limit_mb, bridge.cdnUploadLimitMb)
            }
        }

        settingsCdnExpiry.text = getString(R.string.cdn_expiry_days, bridge.cdnExpiryDays)
        settingsCdnExpiryHolder.setOnClickListener {
            val items = arrayListOf<RadioItem>().apply {
                CdnExpiry.DAY_CHOICES.forEach { days ->
                    add(RadioItem(days, getString(R.string.cdn_expiry_days, days)))
                }
            }
            RadioGroupDialog(this@SettingsActivity, items, bridge.cdnExpiryDays) {
                bridge.cdnExpiryDays = it as Int
                settingsCdnExpiry.text = getString(R.string.cdn_expiry_days, bridge.cdnExpiryDays)
            }
        }

        settingsCdnAskExpiry.isChecked = bridge.cdnAskExpiry
        settingsCdnAskExpiryHolder.setOnClickListener {
            settingsCdnAskExpiry.toggle()
            bridge.cdnAskExpiry = settingsCdnAskExpiry.isChecked
        }

        setCdnLinksAvailability(CdnDiscovery.cachedBaseUrl(this@SettingsActivity))
        ensureBackgroundThread {
            val baseUrl = CdnDiscovery.refresh(this@SettingsActivity)
            runOnUiThread { setCdnLinksAvailability(baseUrl) }
        }
    }

    /** The mode is only meaningful once the bridge advertises a `cdn_base_url`. */
    private fun setCdnLinksAvailability(cdnBaseUrl: String) = binding.apply {
        val available = cdnBaseUrl.isNotEmpty()
        settingsCdnSendMode.isEnabled = available
        settingsCdnSendModeHolder.isEnabled = available
    }

    private fun getCdnSendModeText(mode: CdnSendMode) = getString(
        when (mode) {
            CdnSendMode.ASK -> R.string.cdn_mode_ask
            CdnSendMode.LINK -> R.string.cdn_mode_link
            CdnSendMode.NEVER -> R.string.cdn_mode_never
        }
    )

    private fun getCdnThresholdText(thresholdMb: Int) = when (thresholdMb) {
        CdnThreshold.AUTO_MB -> getString(R.string.cdn_threshold_auto)
        CdnThreshold.DEFAULT_MB -> getString(R.string.cdn_threshold_any)
        else -> getString(R.string.cdn_threshold_mb, thresholdMb)
    }

    private fun showCdnThresholdPicker(bridge: BridgeConfig) {
        val items = arrayListOf<RadioItem>()
        items += RadioItem(1, getString(R.string.cdn_threshold_any), CdnThreshold.DEFAULT_MB)
        items += RadioItem(2, getString(R.string.cdn_threshold_auto), CdnThreshold.AUTO_MB)
        CdnThreshold.CHOICES_MB.forEach { mb ->
            items += RadioItem(items.size + 1, getString(R.string.cdn_threshold_mb, mb), mb)
        }
        items += RadioItem(items.size + 1, getString(R.string.cdn_threshold_custom), CdnThreshold.CUSTOM_MB)

        val checkedItemId = items.firstOrNull { it.value == bridge.cdnThresholdMb }?.id ?: items.last().id
        RadioGroupDialog(this, items, checkedItemId) { chosen ->
            if (chosen == CdnThreshold.CUSTOM_MB) {
                CdnThresholdDialog(this, bridge.cdnThresholdMb) { mb ->
                    bridge.cdnThresholdMb = mb
                    updateCdnThresholdText(bridge)
                }
            } else {
                bridge.cdnThresholdMb = chosen as Int
                updateCdnThresholdText(bridge)
            }
        }
    }

    private fun updateCdnThresholdText(bridge: BridgeConfig) = binding.apply {
        settingsCdnThreshold.text = getCdnThresholdText(bridge.cdnThresholdMb)
    }

    override fun onPause() {
        super.onPause()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    private fun setupCustomizeColors() = binding.apply {
        settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupCustomizeNotifications() = binding.apply {
        settingsCustomizeNotificationsHolder.setOnClickListener {
            launchCustomizeNotificationsIntent()
        }
    }

    private fun setupUseEnglish() = binding.apply {
        settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )
        settingsUseEnglish.isChecked = config.useEnglish
        settingsUseEnglishHolder.setOnClickListener {
            settingsUseEnglish.toggle()
            config.useEnglish = settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() = binding.apply {
        settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            settingsLanguageHolder.beVisible()
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            settingsLanguageHolder.beGone()
        }
    }

    private fun setupManageBlockedNumbers() = binding.apply {
        settingsManageBlockedNumbers.text =
            getString(org.fossify.commons.R.string.manage_blocked_numbers)
        settingsManageBlockedNumbersHolder.beVisible()
        settingsManageBlockedNumbersHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupManageBlockedKeywords() = binding.apply {
        settingsManageBlockedKeywords.text =
            getString(R.string.manage_blocked_keywords)

        settingsManageBlockedKeywordsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedKeywordsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() = binding.apply {
        settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this@SettingsActivity) {
                refreshConversations()
            }
        }
    }

    private fun setupFontSize() = binding.apply {
        settingsFontSize.text = getFontSizeText()
        settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(org.fossify.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(org.fossify.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(org.fossify.commons.R.string.large)),
                RadioItem(
                    FONT_SIZE_EXTRA_LARGE,
                    getString(org.fossify.commons.R.string.extra_large)
                )
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = config.showCharacterCounter
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
            config.showCharacterCounter = settingsShowCharacterCounter.isChecked
        }
    }

    private fun setupUseSimpleCharacters() = binding.apply {
        settingsUseSimpleCharacters.isChecked = config.useSimpleCharacters
        settingsUseSimpleCharactersHolder.setOnClickListener {
            settingsUseSimpleCharacters.toggle()
            config.useSimpleCharacters = settingsUseSimpleCharacters.isChecked
        }
    }

    private fun setupSendOnEnter() = binding.apply {
        settingsSendOnEnter.isChecked = config.sendOnEnter
        settingsSendOnEnterHolder.setOnClickListener {
            settingsSendOnEnter.toggle()
            config.sendOnEnter = settingsSendOnEnter.isChecked
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = config.enableDeliveryReports
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
            config.enableDeliveryReports = settingsEnableDeliveryReports.isChecked
        }
    }

    private fun setupSendLongMessageAsMMS() = binding.apply {
        settingsSendLongMessageMms.isChecked = config.sendLongMessageMMS
        settingsSendLongMessageMmsHolder.setOnClickListener {
            settingsSendLongMessageMms.toggle()
            config.sendLongMessageMMS = settingsSendLongMessageMms.isChecked
        }
    }

    private fun setupGroupMessageAsMMS() = binding.apply {
        settingsSendGroupMessageMms.isChecked = config.sendGroupMessageMMS
        settingsSendGroupMessageMmsHolder.setOnClickListener {
            settingsSendGroupMessageMms.toggle()
            config.sendGroupMessageMMS = settingsSendGroupMessageMms.isChecked
        }
    }

    private fun setupKeepConversationsArchived() = binding.apply {
        settingsKeepConversationsArchived.isChecked = config.keepConversationsArchived
        settingsKeepConversationsArchivedHolder.setOnClickListener {
            settingsKeepConversationsArchived.toggle()
            config.keepConversationsArchived = settingsKeepConversationsArchived.isChecked
        }
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(org.fossify.commons.R.string.nothing)),
            )

            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting) {
                config.lockScreenVisibilitySetting = it as Int
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> org.fossify.commons.R.string.nothing
        }
    )

    private fun setupMMSFileSizeLimit() = binding.apply {
        settingsMmsFileSizeLimit.text = getMMSFileLimitText()
        settingsMmsFileSizeLimitHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(7, getString(R.string.mms_file_size_limit_none), FILE_SIZE_NONE),
                RadioItem(6, getString(R.string.mms_file_size_limit_2mb), FILE_SIZE_2_MB),
                RadioItem(5, getString(R.string.mms_file_size_limit_1mb), FILE_SIZE_1_MB),
                RadioItem(4, getString(R.string.mms_file_size_limit_600kb), FILE_SIZE_600_KB),
                RadioItem(3, getString(R.string.mms_file_size_limit_300kb), FILE_SIZE_300_KB),
                RadioItem(2, getString(R.string.mms_file_size_limit_200kb), FILE_SIZE_200_KB),
                RadioItem(1, getString(R.string.mms_file_size_limit_100kb), FILE_SIZE_100_KB),
            )

            val checkedItemId = items.find { it.value == config.mmsFileSizeLimit }?.id ?: 7
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                config.mmsFileSizeLimit = it as Long
                settingsMmsFileSizeLimit.text = getMMSFileLimitText()
            }
        }
    }

    private fun setupUseRecycleBin() = binding.apply {
        updateRecycleBinButtons()
        settingsUseRecycleBin.isChecked = config.useRecycleBin
        settingsUseRecycleBin.text = formatWithDeprecatedBadge(
            labelRes = org.fossify.commons.R.string.move_items_into_recycle_bin
        )
        settingsUseRecycleBinHolder.setOnClickListener {
            settingsUseRecycleBin.toggle()
            config.useRecycleBin = settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() = binding.apply {
        settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() = binding.apply {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread {
                settingsEmptyRecycleBinSize.text =
                    resources.getQuantityString(
                        R.plurals.delete_messages,
                        recycleBinMessages,
                        recycleBinMessages
                    )
            }
        }

        settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinMessages == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this@SettingsActivity,
                    message = "",
                    messageId = R.string.empty_recycle_bin_messages_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                    }
                    recycleBinMessages = 0
                    settingsEmptyRecycleBinSize.text =
                        resources.getQuantityString(
                            R.plurals.delete_messages,
                            recycleBinMessages,
                            recycleBinMessages
                        )
                }
            }
        }
    }

    private fun setupHistoryRetention() = binding.apply {
        settingsHistoryRetention.text = historyRetentionText(config.historyRetentionDays)
        settingsHistoryRetentionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(1, historyRetentionText(HistoryRetention.KEEP_FOREVER), HistoryRetention.KEEP_FOREVER),
                RadioItem(2, historyRetentionText(HistoryRetention.THIRTY_DAYS), HistoryRetention.THIRTY_DAYS),
                RadioItem(3, historyRetentionText(HistoryRetention.NINETY_DAYS), HistoryRetention.NINETY_DAYS),
                RadioItem(4, historyRetentionText(HistoryRetention.ONE_YEAR_DAYS), HistoryRetention.ONE_YEAR_DAYS),
            )
            val checkedItemId = items.firstOrNull { it.value == config.historyRetentionDays }?.id ?: 1
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                config.historyRetentionDays = it as Int
                settingsHistoryRetention.text = historyRetentionText(it)
            }
        }
    }

    private fun updateHistoryRedactionsText() = binding.apply {
        val redactions = config.historyRedactions
        val count = redactions.presets.size + redactions.extensions.size
        settingsHistoryRedactions.text = if (count == 0) {
            getString(R.string.history_retention_redactions_none)
        } else {
            resources.getQuantityString(R.plurals.history_redaction_patterns, count, count)
        }
    }

    private fun setupHistoryRedactions() = binding.apply {
        updateHistoryRedactionsText()
        settingsHistoryRedactionsHolder.setOnClickListener {
            RedactionPatternsDialog(this@SettingsActivity) {
                updateHistoryRedactionsText()
            }
        }
    }

    private fun setupHistoryCleanupNow() = binding.apply {
        settingsHistoryCleanupNowHolder.setOnClickListener {
            ConfirmationDialog(
                activity = this@SettingsActivity,
                message = "",
                messageId = R.string.history_retention_cleanup_confirmation,
                positive = org.fossify.commons.R.string.yes,
                negative = org.fossify.commons.R.string.no,
            ) {
                ensureBackgroundThread {
                    val deleted = runHistoryCleanup()
                    runOnUiThread {
                        val message = if (deleted == 0) {
                            getString(R.string.history_retention_cleanup_none)
                        } else {
                            resources.getQuantityString(R.plurals.history_retention_deleted, deleted, deleted)
                        }
                        toast(message)
                    }
                }
            }
        }
    }

    private fun setupAppPasswordProtection() = binding.apply {
        settingsAppPasswordProtection.isChecked = config.isAppPasswordProtectionOn
        settingsAppPasswordProtectionHolder.setOnClickListener {
            val tabToShow = if (config.isAppPasswordProtectionOn) {
                config.appProtectionType
            } else {
                SHOW_ALL_TABS
            }

            SecurityDialog(
                activity = this@SettingsActivity,
                requiredHash = config.appPasswordHash,
                showTabIndex = tabToShow
            ) { hash, type, success ->
                if (success) {
                    val hasPasswordProtection = config.isAppPasswordProtectionOn
                    settingsAppPasswordProtection.isChecked = !hasPasswordProtection
                    config.isAppPasswordProtectionOn = !hasPasswordProtection
                    config.appPasswordHash = if (hasPasswordProtection) "" else hash
                    config.appProtectionType = type

                    if (config.isAppPasswordProtectionOn) {
                        val confirmationTextId =
                            if (config.appProtectionType == PROTECTION_FINGERPRINT) {
                                org.fossify.commons.R.string.fingerprint_setup_successfully
                            } else {
                                org.fossify.commons.R.string.protection_setup_successfully
                            }

                        ConfirmationDialog(
                            activity = this@SettingsActivity,
                            message = "",
                            messageId = confirmationTextId,
                            positive = org.fossify.commons.R.string.ok,
                            negative = 0
                        ) { }
                    }
                }
            }
        }
    }

    private fun getMMSFileLimitText() = getString(
        when (config.mmsFileSizeLimit) {
            FILE_SIZE_100_KB -> R.string.mms_file_size_limit_100kb
            FILE_SIZE_200_KB -> R.string.mms_file_size_limit_200kb
            FILE_SIZE_300_KB -> R.string.mms_file_size_limit_300kb
            FILE_SIZE_600_KB -> R.string.mms_file_size_limit_600kb
            FILE_SIZE_1_MB -> R.string.mms_file_size_limit_1mb
            FILE_SIZE_2_MB -> R.string.mms_file_size_limit_2mb
            else -> R.string.mms_file_size_limit_none
        }
    )
}
