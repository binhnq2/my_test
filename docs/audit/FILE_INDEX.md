# File index

Every file the audit bundle carries, the bundle it is in, and why it
was included. Files that were asked for but do not exist, and files
that exist but are not reached from production code, are listed in
their own sections below.

| bundle | relative path | bytes | why |
|---|---|---|---|
| SOURCE_AUDIT_BUNDLE_01.md | `app/build.gradle.kts` | 4724 | A. app module config, SDK levels, R8 |
| SOURCE_AUDIT_BUNDLE_01.md | `app/proguard-rules.pro` | 2694 | A. R8 keep rules |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/AndroidManifest.xml` | 10271 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/Ts8LauncherApp.kt` | 576 | B. Application class, composition root |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/di/AppGraph.kt` | 30417 | C. dependency graph, single wiring point |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/AppSettings.kt` | 23531 | C. launcher state and settings |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/AppStateMachine.kt` | 15641 | C. launcher state and settings |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/LocationRuntime.kt` | 5798 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/MapHost.kt` | 6387 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/MusicRuntime.kt` | 11998 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/OnlineMusicRuntime.kt` | 22299 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/ParkingRuntime.kt` | 12262 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/PrefsKeyValueStore.kt` | 2681 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/SavedPlaces.kt` | 5571 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/SpeechRecognizerAdapter.kt` | 14589 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/SpeedRuntime.kt` | 13585 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/VoiceCommandExecutor.kt` | 16375 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/VoiceFeedback.kt` | 8810 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/VoiceInstantController.kt` | 14499 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/runtime/VoiceTrigger.kt` | 5598 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/service/MediaButtonReceiver.kt` | 2825 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/service/MusicPlaybackService.kt` | 19521 | F. playback service and MediaSession |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/service/SessionBuilders.kt` | 5657 | F. playback service and MediaSession |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/service/UsbMediaReceiver.kt` | 4100 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/AlbumArtLoader.kt` | 4589 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/AppCatalog.kt` | 8708 | J. app drawer |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/AppDrawerActivity.kt` | 8277 | J. app drawer |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/BaseScreenActivity.kt` | 13495 | C. screen base and transitions |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/BottomNavView.kt` | 6400 | C/G. bottom navigation and volume stepper |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/ClockFormat.kt` | 3584 | supporting source |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/HomeActivity.kt` | 31170 | D. Home screen |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/MapActivity.kt` | 41169 | E. map screen |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/MapManagementActivity.kt` | 41119 | E. map management |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/MusicCardView.kt` | 12250 | D/F. music panel, shared by Home/Map/Music |
| SOURCE_AUDIT_BUNDLE_01.md | `app/src/main/java/com/ts8/launcher/ui/MusicFullscreenActivity.kt` | 36085 | F. music screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/MusicRowAdapter.kt` | 10154 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/ParkingModeActivity.kt` | 5237 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/QsTileView.kt` | 4406 | J. quick settings |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/QuickSettingsActivity.kt` | 19715 | J. quick settings |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/ScreenOffActivity.kt` | 1938 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/ScreenTransition.kt` | 3876 | C. screen base and transitions |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/SearchActivity.kt` | 21611 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/SettingsActivity.kt` | 17612 | J. settings navigation |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/SpeedViews.kt` | 3765 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/StatusBarView.kt` | 6197 | D. status/header area |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayHost.kt` | 9066 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/java/com/ts8/launcher/ui/VoiceOverlayView.kt` | 8535 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/color/music_tab_tint.xml` | 1300 | B. colour selector |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/color/nav_item_tint.xml` | 249 | B. colour selector |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_button_ghost.xml` | 723 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_button_primary.xml` | 558 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_card.xml` | 247 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_card_raised.xml` | 254 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_chip.xml` | 253 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_circle_accent.xml` | 416 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_circle_control.xml` | 814 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_circle_ring.xml` | 2141 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_eta_strip.xml` | 1282 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_input_field.xml` | 739 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_list_item.xml` | 813 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_parking.xml` | 797 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_search_field.xml` | 313 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_speed_card.xml` | 254 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_speed_limit.xml` | 486 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/bg_tab_pill.xml` | 1549 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_battery.xml` | 537 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_bluetooth.xml` | 584 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_brightness.xml` | 1359 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_chevron_left.xml` | 452 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_chevron_right.xml` | 453 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_close.xml` | 507 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_compass.xml` | 513 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_expand.xml` | 618 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_folder.xml` | 513 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_heart.xml` | 585 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_heart_filled.xml` | 585 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_launcher_foreground.xml` | 753 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_layers.xml` | 521 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_arrive.xml` | 621 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_depart.xml` | 460 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_left.xml` | 527 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_right.xml` | 519 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_roundabout.xml` | 679 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_slight_left.xml` | 524 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_slight_right.xml` | 525 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_straight.xml` | 460 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_maneuver_uturn.xml` | 558 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_mic.xml` | 602 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_minus.xml` | 423 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_moon.xml` | 463 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_mute.xml` | 798 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_nav_apps.xml` | 547 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_nav_home.xml` | 441 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_nav_map.xml` | 456 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_nav_music.xml` | 505 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_nav_phone.xml` | 696 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_next.xml` | 442 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_parking.xml` | 518 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_pause.xml` | 437 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_pin.xml` | 577 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_play.xml` | 420 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_plus.xml` | 441 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_power.xml` | 630 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_prev.xml` | 434 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_queue.xml` | 496 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_refresh.xml` | 618 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_repeat.xml` | 472 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_route.xml` | 656 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_search.xml` | 654 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_settings.xml` | 1310 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_shuffle.xml` | 585 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_signal.xml` | 417 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_volume.xml` | 613 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_warning.xml` | 459 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/ic_wifi.xml` | 585 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/progress_music.xml` | 843 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/drawable/seek_thumb.xml` | 240 | B. drawable used by a screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_app_drawer.xml` | 5812 | J. app drawer |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_home.xml` | 17041 | D. Home screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_map.xml` | 22624 | E. map screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_map_management.xml` | 10695 | E. map screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_music_fullscreen.xml` | 23196 | F. music screen |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_parking_mode.xml` | 3862 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_quick_settings.xml` | 10547 | J. quick settings |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_screen_off.xml` | 714 | B. screen or component layout |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_search.xml` | 14276 | B. screen or component layout |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/activity_settings.xml` | 16300 | J. settings navigation |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_app_tile.xml` | 1492 | J. app drawer |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_bottom_nav.xml` | 6336 | C/G. bottom navigation and volume stepper |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_music_card.xml` | 10344 | D/F. music panel, shared by Home/Map/Music |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_music_row.xml` | 3703 | B. screen or component layout |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_qs_tile.xml` | 1634 | J. quick settings |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_search_row.xml` | 2214 | B. screen or component layout |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_status_bar.xml` | 4988 | D. status/header area |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/layout/view_voice_overlay.xml` | 6384 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 257 | B. launcher icon |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/mipmap-anydpi/ic_launcher.xml` | 644 | B. launcher icon |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/values/attrs.xml` | 1203 | B. theme, style, colour, dimension tokens |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/values/colors.xml` | 2901 | B. theme, style, colour, dimension tokens |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/values/dimens.xml` | 2365 | B. theme, style, colour, dimension tokens |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/values/strings.xml` | 25592 | B. theme, style, colour, dimension tokens |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/values/themes.xml` | 6468 | B. theme, style, colour, dimension tokens |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/main/res/xml/usb_device_filter.xml` | 256 | B. XML resource referenced by the manifest |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/test/java/com/ts8/launcher/runtime/AppStateMachineTest.kt` | 14311 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_02.md | `app/src/test/java/com/ts8/launcher/runtime/OnlineTrackIdTest.kt` | 3769 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_02.md | `build.gradle.kts` | 382 | A. root build config |
| SOURCE_AUDIT_BUNDLE_02.md | `buildSrc/build.gradle.kts` | 227 | A. module config |
| SOURCE_AUDIT_BUNDLE_02.md | `buildSrc/src/main/kotlin/CoreModulePlugin.kt` | 1790 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `buildSrc/src/main/resources/META-INF/gradle-plugins/ts8.core-module.properties` | 38 | supporting source |
| SOURCE_AUDIT_BUNDLE_02.md | `core/diagnostics/build.gradle.kts` | 94 | A. module config |
| SOURCE_AUDIT_BUNDLE_02.md | `core/diagnostics/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/build.gradle.kts` | 143 | A. module config |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/location/AndroidLocationSource.kt` | 6884 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/location/DefaultLocationEngine.kt` | 17351 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/location/LocationEngine.kt` | 5070 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/parking/LocationStability.kt` | 4664 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingScenarios.kt` | 16208 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingSignals.kt` | 7689 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_02.md | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateResolver.kt` | 27535 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/parking/ParkingStateStore.kt` | 9551 | I. parking mode |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/OverspeedDetector.kt` | 3488 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedAlertController.kt` | 5668 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedFilter.kt` | 2685 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedGate.kt` | 3030 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedLimitResolver.kt` | 3718 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedMonitor.kt` | 12419 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/main/java/com/ts8/core/speed/SpeedTypes.kt` | 6412 | G/I. location, speed, parking signals |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/test/java/com/ts8/core/location/DefaultLocationEngineTest.kt` | 10843 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/test/java/com/ts8/core/parking/ParkingResolverTest.kt` | 16045 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/test/java/com/ts8/core/parking/ParkingScenariosTest.kt` | 1696 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/location-engine/src/test/java/com/ts8/core/speed/SpeedPipelineTest.kt` | 15078 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/build.gradle.kts` | 86 | A. module config |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/src/main/java/com/ts8/core/map/Geo.kt` | 2698 | E. map contract types |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/src/main/java/com/ts8/core/map/MapController.kt` | 5638 | E. map contract types |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/src/main/java/com/ts8/core/map/MapDisplayConfig.kt` | 4865 | E. map contract types |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-api/src/test/java/com/ts8/core/map/RouteGeometryTest.kt` | 5054 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/build.gradle.kts` | 1422 | A. module config |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/AndroidSqliteProbe.kt` | 2893 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreMapController.kt` | 33435 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapLibreRuntime.kt` | 3124 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/MapPack.kt` | 23638 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/OverlayIcons.kt` | 3979 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackDiagnostics.kt` | 6512 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackImporter.kt` | 11428 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackPaths.kt` | 3358 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/PackValidator.kt` | 20387 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/main/java/com/ts8/core/map/maplibre/StyleBuilder.kt` | 24750 | E. MapLibre engine, pack pipeline |
| SOURCE_AUDIT_BUNDLE_03.md | `core/map-engine-maplibre/src/test/java/com/ts8/core/map/maplibre/PackPathsTest.kt` | 5100 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-api/build.gradle.kts` | 175 | A. module config |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-api/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-api/src/main/java/com/ts8/core/music/MediaScanner.kt` | 4042 | supporting source |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-api/src/main/java/com/ts8/core/music/MusicApi.kt` | 6328 | supporting source |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-api/src/main/java/com/ts8/core/music/Track.kt` | 7805 | supporting source |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/build.gradle.kts` | 355 | A. module config |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/AndroidCodecSupport.kt` | 10621 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/AudioTagReader.kt` | 33945 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/Checkpoint.kt` | 6051 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/CheckpointRestore.kt` | 2273 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/LocalMusicBackend.kt` | 2098 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/LocalMusicPlayer.kt` | 30178 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/MediaStoreScanner.kt` | 20131 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/MusicDatabase.kt` | 14704 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/PlaybackCore.kt` | 6873 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/PlaybackQueue.kt` | 5935 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/main/java/com/ts8/core/music/local/ScanDiff.kt` | 5868 | F. local/USB music |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/test/java/com/ts8/core/music/local/AudioTagReaderTest.kt` | 19095 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/test/java/com/ts8/core/music/local/PlaybackCoreTest.kt` | 14270 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-engine-local/src/test/java/com/ts8/core/music/local/PlaybackQueueTest.kt` | 16665 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-provider-online/build.gradle.kts` | 149 | A. module config |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-provider-online/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_03.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/CircuitBreaker.kt` | 7753 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/HttpSupport.kt` | 10712 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/NavidromeProvider.kt` | 22403 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/OnlineMusicGateway.kt` | 17459 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/OnlineMusicProvider.kt` | 13390 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/PodcastRssProvider.kt` | 25312 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/RadioBrowserProvider.kt` | 17708 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/main/java/com/ts8/core/music/online/RecordingOnlineProvider.kt` | 7783 | F. online providers |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/test/java/com/ts8/core/music/online/CircuitBreakerTest.kt` | 5517 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/music-provider-online/src/test/java/com/ts8/core/music/online/OnlineMusicGatewayTest.kt` | 13412 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/build.gradle.kts` | 358 | A. module config |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/navigation/NavigationController.kt` | 23203 | E. navigation state |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/navigation/NavigationState.kt` | 3549 | E. navigation state |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/navigation/VoiceGuidance.kt` | 9581 | E. navigation state |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/CommandRouter.kt` | 17154 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/IntentParser.kt` | 12883 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/VietnameseVoicePrompts.kt` | 7031 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceCommand.kt` | 9338 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceScenarios.kt` | 18858 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceState.kt` | 5088 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceStateStore.kt` | 8537 | H. Voice Instant |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/test/java/com/ts8/core/navigation/NavigationControllerTest.kt` | 14716 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/test/java/com/ts8/core/voice/IntentParserTest.kt` | 9400 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/navigation-core/src/test/java/com/ts8/core/voice/VoiceScenariosTest.kt` | 987 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/build.gradle.kts` | 141 | A. module config |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/src/main/java/com/ts8/core/search/PlaceSearch.kt` | 2124 | E. places.db search |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/src/main/java/com/ts8/core/search/PlaceSearchService.kt` | 7275 | E. places.db search |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/src/main/java/com/ts8/core/search/SqlitePlaceSearch.kt` | 9667 | E. places.db search |
| SOURCE_AUDIT_BUNDLE_04.md | `core/place-search/src/main/java/com/ts8/core/search/VietnameseText.kt` | 2230 | E. places.db search |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/build.gradle.kts` | 142 | A. module config |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/main/java/com/ts8/core/routing/AStarRouter.kt` | 22128 | E. routing graph |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/main/java/com/ts8/core/routing/GraphIndex.kt` | 7573 | E. routing graph |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/main/java/com/ts8/core/routing/RoutingEngine.kt` | 2899 | E. routing graph |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/main/java/com/ts8/core/routing/RoutingGraph.kt` | 10428 | E. routing graph |
| SOURCE_AUDIT_BUNDLE_04.md | `core/routing-engine-offline/src/test/java/com/ts8/core/routing/AStarRouterPackTest.kt` | 8440 | test: evidence for pass-without-wiring review |
| SOURCE_AUDIT_BUNDLE_04.md | `core/vehicle-audio-bridge/build.gradle.kts` | 142 | A. module config |
| SOURCE_AUDIT_BUNDLE_04.md | `core/vehicle-audio-bridge/src/main/AndroidManifest.xml` | 52 | B. manifest, permissions, exported components |
| SOURCE_AUDIT_BUNDLE_04.md | `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/AndroidVehicleAudioBridge.kt` | 10831 | G/H. AudioManager, focus, duck/restore |
| SOURCE_AUDIT_BUNDLE_04.md | `core/vehicle-audio-bridge/src/main/java/com/ts8/core/audio/VehicleAudioBridge.kt` | 5038 | G/H. AudioManager, focus, duck/restore |
| SOURCE_AUDIT_BUNDLE_04.md | `gradle.properties` | 1654 | A. JVM/AGP flags |
| SOURCE_AUDIT_BUNDLE_04.md | `gradle/wrapper/gradle-wrapper.properties` | 253 | A. Gradle version pin |
| SOURCE_AUDIT_BUNDLE_04.md | `settings.gradle.kts` | 703 | A. module graph |

259 files in four bundles. Every row above was emitted by
`build_audit_bundle.py` from the file on disk, and `verify_audit_bundle.py`
re-reads all 259 and compares them byte for byte.

### Present in the tree but deliberately not bundled

| path | why excluded |
|---|---|
| `app/src/main.zip` | 283,965-byte archive of `app/src/main` (141 entries). Excluded by the `.zip` binary-asset rule in `build_audit_bundle.py:54`, because every file it contains is *already* bundled individually — including it would duplicate the source in a second, less convenient form. Verified on 2026-10-31: all 141 entries are byte-identical to the current `app/src/main` tree (141 same, 0 differing, 0 missing), so it is a faithful copy of the audited source and its age does not imply an unaccounted-for edit. Noted here so a reviewer who lists the directory is not left wondering why it is absent. |

---

## Files requested but not present in the tree

The task named some paths and components that the project does not contain.
Each was searched for directly rather than assumed missing; the check is given
so a reviewer can repeat it.

| requested | search performed | result |
|---|---|---|
| a Gradle **version catalog** (`gradle/libs.versions.toml`) | `ls gradle/libs.versions.toml`, `find . -name libs.versions.toml -not -path "*/build/*"` | **absent** — every module declares its dependencies with literal versions |
| a **navigation graph** (`nav_graph.xml`, `NavHost`) | `grep -rl "nav_graph"`, `grep -rl "NavHost"` over `app/src` and `core` | **0 files** — navigation is `startActivity` with `REORDER_TO_FRONT or SINGLE_TOP` |
| **Fragments** (`androidx.fragment`) | `grep -rl "androidx.fragment"` over `app/src` and `core` | **0 files** — all screens are Activities |
| **ViewModel** | `grep -rl "ViewModel"` over `app/src` and `core` | **0 files** — state lives in `runtime/*.kt` singletons held by `AppGraph` |
| **Jetpack Compose** | `grep -rl "androidx.compose"`; and a manual read of all 14 `setContent` hits | **0 Compose** — the 14 hits are `setContentView` and `NotificationCompat.setContentTitle`/`setContentText`/`setContentIntent` |
| `view_weather.xml` | `find . -name "*view_weather*"` | **absent** — the weather chip is `weatherChip` inside `view_status_bar.xml:55` |
| `ScreenModeCoordinator` | `find . -name "*ScreenModeCoordinator*"` | **absent** — the nearest real type is `AppStateMachine` |
| `AppStateStore` | `find . -name "*AppStateStore*"` | **absent** — the nearest real type is `AppStateMachine` plus `PrefsKeyValueStore` |
| `LocalMusicRepository` | `find . -name "*LocalMusicRepository*"` | **absent** — the nearest real type is `LocalMusicBackend` in `music-engine-local` |
| a `ui/` **map renderer** other than MapLibre | `grep -rl "WebView\|flutter\|react-native"` | **0 files** — MapLibre is the only renderer, as the brief requires |

Names in the task that do exist, under a different path or type: the "App Drawer"
is `AppDrawerActivity` + `AppCatalog`; "Quick Settings" is `QuickSettingsActivity`
+ `QsTileView`; "Parking store" is `ParkingStateStore`.

---

## Files present but not reached from production code

These exist, are bundled, and are compiled in — but no production path executes
or reads them. They are listed because a reviewer who reads the bundle needs to
know which files are evidence-of-intent and which are live behaviour.

### Test doubles and scenario tables shipped in `src/main`

| file | what it is | why it is not production |
|---|---|---|
| `core/music-provider-online/src/main/java/com/ts8/core/music/online/RecordingOnlineProvider.kt` | a recording provider double | `OnlineMusicRuntime` builds `RadioBrowserProvider`, `PodcastRssProvider` and `NavidromeProvider`; no production code constructs `RecordingOnlineProvider` |
| `core/location-engine/src/main/java/com/ts8/core/parking/ParkingScenarios.kt` | named signal scenarios for the resolver tests | referenced from production source only in KDoc (`ParkingStateResolver.kt:171`); executed by `ParkingScenariosTest` |
| `core/navigation-core/src/main/java/com/ts8/core/voice/VoiceScenarios.kt` | the voice command scenario table | referenced from production source only in KDoc (`CommandRouter.kt:10`, `VoiceCommand.kt:204`, `VoiceStateStore.kt:112`); executed by `VoiceScenariosTest` |

### Production code with no production caller

| file / symbol | what it does | why it is dead |
|---|---|---|
| `AppStateMachine.kt` — `state()` (line 92) | returns the current primary state | no production caller; `restoreAfterProcessDeath()`'s return is discarded at `BaseScreenActivity.kt:91` and overwritten at `:169` — see IMPLEMENTATION_FINDINGS F-15 |
| `MapLibreMapController.kt` — `onSaveInstanceState()` (line 223) | forwards to `mapView.onSaveInstanceState` | the activities call `MapHost.onSaveInstanceState` (`MapHost.kt:149-151`) directly instead; this wrapper is never called |
| `OnlineMusicRuntime.kt` — `browse()` (line 240) | returns trending/recent/genre shelves | zero UI call sites — see F-09 |
| `OnlineMusicRuntime.kt` — `hasAnyProvider()` (line 498) | whether any provider is configured | never called |
| `PackDiagnostics.kt` — `PackFailureCode.STYLE_LOAD_FAILED` (line 56) | the style-rejection failure code | declared and never used; no `OnDidFailLoadingMapListener` is registered — see F-10 |
| `MapManagementActivity.kt` (whole file, 1000+ lines) | pack import, validation, activation, removal, diagnostics | **not declared in `AndroidManifest.xml`** — unreachable, and the one caller would throw. See F-11 |
| `gradle.properties` — `EXPAND_STATUS_BAR` permission (`AndroidManifest.xml:8`) | permission for the system shade | requested but never used; no `expandNotificationsPanel` call exists |

### Resource declarations with no consumer

Counts verified by reference scan over `app/src` (`@type/name` and `R.type.name`,
excluding comments); full detail in IMPLEMENTATION_FINDINGS F-02.

| kind | unused | of total |
|---|---|---|
| drawable | `ic_folder`, `ic_pin`, `ic_refresh`, `ic_route` | 4 / 68 |
| colour | `route_line`, `route_line_casing`, `nav_muted` | 3 / 29 |
| dimen | `scorebar_height`, `app_grid_spacing`, `text_huge` | 3 / 33 |
| style | `Text.Body`, `Card.Surface`, `QsTileIcon` | 3 / 13 |
| string | 85 names, listed below | 85 / 356 |

The 85 unreferenced strings, verbatim:

```
home_speed_limit_none, search_minute, search_loading, search_open_map,
search_pick_on_map, search_results_label, search_unsave, music_scanning,
music_buffering, music_retry, music_queue_empty, music_search_offline,
music_online_no_config, music_online_provider_line, music_online_count,
music_online_failed, music_online_ready, nav_start, nav_voice_unavailable,
nav_follow_off, qs_restart_mic, perm_location_body, perm_storage_body,
perm_grant, perm_denied_forever, perm_open_settings, cd_map, generic_loading,
map_mgmt_entry, map_cta_diagnostics, map_action_repair, map_import_title,
map_import_zip_hint, map_import_stage, map_import_zip_slip,
speed_threshold_custom, speed_no_data, speed_limit_none,
speed_overspeed_alert, voice_confirm, voice_success, voice_error_unknown_command,
voice_unavailable_short, voice_cancel, voice_cmd_map, voice_cmd_navigate,
voice_cmd_search, voice_cmd_music_play, voice_cmd_music_stop, voice_cmd_next,
voice_cmd_previous, voice_cmd_phone, voice_cmd_app, voice_cmd_volume_up,
voice_cmd_volume_down, voice_cmd_home, voice_conflict_music,
voice_overlay_title, voice_hint, voice_command_label, music_tab_device,
music_tab_online, music_online_radio, music_online_podcast, music_online_server,
music_online_buffering, music_online_unavailable, music_online_blocked,
music_online_retry, music_online_offline, music_online_radio_hint,
music_online_health_ok, music_online_health_fail, music_online_no_stream,
parking_title, parking_enter, parking_auto, parking_auto_hint, parking_state,
parking_reason, parking_return, overlay_import_progress, error_dialog_ok,
error_dialog_retry, generic_retry
```

Two of these are the clearest evidence in the audit of features that were
designed and then not connected: `map_mgmt_entry` ("Quản lý bản đồ &amp; cài
đặt", `strings.xml:189`) and `music_tab_online` ("Trực tuyến",
`strings.xml:369`). Both name a persistent entry point that no layout uses,
matching the missing Map Management entry (F-11) and the search-only online
implementation (F-09).

An important negative: `colorPrimary`, `colorPrimaryDark` and `colorAccent`
also show zero `@color/` references, but they are **not** unused — `themes.xml:5-7`
maps them into the framework theme, which is the standard way to set them. They
are excluded from the unused colour count.
