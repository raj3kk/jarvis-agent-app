# Keep OkHttp and JSON models
-dontwarn okhttp3.**
-keep class com.jarvis.autonomous.** { *; }

# GeckoView spike ONLY (branch geckoview-spike — DELETE with the
# branch): AC's service-nimbus (transitive via feature-tabs/downloads)
# references Glean telemetry classes (mozilla.telemetry.glean.*) that
# are NOT on the classpath, so release R8 fails with "Missing class
# mozilla.telemetry.glean.Glean" (CI run 44990580965). Nimbus is never
# used by the spike (no experiments, no telemetry) and nothing in the
# app references it, so R8 strips it; the dontwarn only lets R8 get
# past the analysis phase. Phase 1 must decide: add the Glean dep or
# keep this dontwarn (same reasoning — the product does not use Nimbus).
-dontwarn mozilla.telemetry.glean.**
