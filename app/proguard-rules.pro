# Allow R8 shrinking and obfuscation across app classes (including dead-code stripping
# of flavor-gated features like GitHubUpdater in playStoreRelease builds).
# Manifest components (Activities, Services, Receivers) are kept automatically by AGP.

-keep class com.wifiguard.limiter.model.** { *; }
-dontwarn org.jetbrains.**
-dontwarn kotlinx.coroutines.**
