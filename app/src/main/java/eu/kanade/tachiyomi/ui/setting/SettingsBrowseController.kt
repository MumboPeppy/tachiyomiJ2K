package eu.kanade.tachiyomi.ui.setting

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.data.updater.AutoUpdaterJob
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.MigrationController
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.launchIn
import uy.kohesive.injekt.injectLazy

class SettingsBrowseController : SettingsController() {

    val sourceManager: SourceManager by injectLazy()
    var updatedExtNotifPref: SwitchPreferenceCompat? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.sources

        preferenceCategory {
            titleRes = R.string.extensions
            switchPreference {
                key = PreferenceKeys.automaticExtUpdates
                titleRes = R.string.check_for_extension_updates
                defaultValue = true

                onChange {
                    it as Boolean
                    ExtensionUpdateJob.setupTask(context, it)
                    true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                intListPreference(activity) {
                    key = PreferenceKeys.autoUpdateExtensions
                    titleRes = R.string.auto_update_extensions
                    entryRange = 0..2
                    entriesRes = arrayOf(
                        R.string.over_any_network,
                        R.string.over_wifi_only,
                        R.string.dont_auto_update
                    )
                    defaultValue = AutoUpdaterJob.ONLY_ON_UNMETERED
                }
                infoPreference(R.string.some_extensions_may_not_update)
                switchPreference {
                    key = "notify_ext_updated"
                    isPersistent = false
                    titleRes = R.string.notify_extension_updated
                    isChecked = Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_EXT_UPDATED)
                    updatedExtNotifPref = this
                    onChange {
                        false
                    }
                    onClick {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                            putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_EXT_UPDATED)
                        }
                        startActivity(intent)
                    }
                }
            }
        }

        preferenceCategory {
            titleRes = R.string.pref_global_search
            switchPreference {
                key = PreferenceKeys.onlySearchPinned
                titleRes = R.string.only_search_pinned_when
            }
        }

        preferenceCategory {
            titleRes = R.string.migration
            // Only show this if someone has mass migrated manga once

            preference {
                titleRes = R.string.source_migration
                onClick { router.pushController(MigrationController().withFadeTransaction()) }
            }
            if (preferences.skipPreMigration().getOrDefault() || preferences.migrationSources()
                .isSet()
            ) {
                switchPreference {
                    key = PreferenceKeys.skipPreMigration
                    titleRes = R.string.skip_pre_migration
                    summaryRes = R.string.use_last_saved_migration_preferences
                    defaultValue = false
                }
            }
            preference {
                key = "match_pinned_sources"
                titleRes = R.string.match_pinned_sources
                summaryRes = R.string.only_enable_pinned_for_migration
                onClick {
                    val ogSources = preferences.migrationSources().get()
                    val pinnedSources =
                        (preferences.pinnedCatalogues().get() ?: emptySet()).joinToString("/")
                    preferences.migrationSources().set(pinnedSources)
                    (activity as? MainActivity)?.setUndoSnackBar(
                        view?.snack(
                            R.string.migration_sources_changed
                        ) {
                            setAction(R.string.undo) {
                                preferences.migrationSources().set(ogSources)
                            }
                        }
                    )
                }
            }

            preference {
                key = "match_enabled_sources"
                titleRes = R.string.match_enabled_sources
                summaryRes = R.string.only_enable_enabled_for_migration
                onClick {
                    val ogSources = preferences.migrationSources().get()
                    val languages = preferences.enabledLanguages().get()
                    val hiddenCatalogues = preferences.hiddenSources().get()
                    val enabledSources =
                        sourceManager.getCatalogueSources().filter { it.lang in languages }
                            .filterNot { it.id.toString() in hiddenCatalogues }
                            .sortedBy { "(${it.lang}) ${it.name}" }
                            .joinToString("/") { it.id.toString() }
                    preferences.migrationSources().set(enabledSources)
                    (activity as? MainActivity)?.setUndoSnackBar(
                        view?.snack(
                            R.string.migration_sources_changed
                        ) {
                            setAction(R.string.undo) {
                                preferences.migrationSources().set(ogSources)
                            }
                        }
                    )
                }
            }

            infoPreference(R.string.you_can_migrate_in_library)
        }

        preferenceCategory {
            titleRes = R.string.nsfw_sources

            switchPreference {
                key = PreferenceKeys.showNsfwSource
                titleRes = R.string.show_in_sources
                summaryRes = R.string.requires_app_restart
                defaultValue = true
            }
            switchPreference {
                key = PreferenceKeys.showNsfwExtension
                titleRes = R.string.show_in_extensions
                defaultValue = true
            }
            switchPreference {
                key = PreferenceKeys.labelNsfwExtension
                titleRes = R.string.label_in_extensions
                defaultValue = true

                preferences.showNsfwExtension().asImmediateFlow { isVisible = it }.launchIn(viewScope)
            }

            infoPreference(R.string.does_not_prevent_unofficial_nsfw)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        updatedExtNotifPref?.isChecked = Notifications.isNotificationChannelEnabled(activity, Notifications.CHANNEL_EXT_UPDATED)
    }
}
