# ProGuard / R8 rules for the STT Keyboard Dongle app.
#
# v0 ships with minifyEnabled false, so these rules are not strictly active yet.
# They are kept here so that turning on shrinking later does not break the
# BLE callback classes (which R8 can over-eagerly strip if it thinks the
# framework-invoked methods are unused).

# Keep our app classes that the platform instantiates / calls back into.
-keep class com.whitneydesignlabs.sttkeyboard.** { *; }

# Coroutines: keep the metadata R8 needs for suspend functions.
-keepclassmembers class kotlinx.coroutines.** { *; }

# Standard Kotlin metadata.
-keep class kotlin.Metadata { *; }
