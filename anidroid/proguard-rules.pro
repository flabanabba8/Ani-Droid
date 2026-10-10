# Ani-Droid release shrinking. Everything is reached through the manifest, Compose or direct calls;
# nothing is loaded by reflection, so only warnings from optional library dependencies are silenced.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
