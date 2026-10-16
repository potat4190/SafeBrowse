# Room, Hilt and Compose ship their own consumer rules; these cover the rest.

# Keep the DNS wire-format classes intact — they are read reflectively by Room
# and their field order matters for packet parsing.
-keep class com.safebrowse.dns.** { *; }
-keep class com.safebrowse.vpn.** { *; }

# The VpnService subclass is instantiated by the framework, by name.
-keep class com.safebrowse.vpn.SafeVpnService { *; }
-keep class com.safebrowse.receivers.BootReceiver { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
