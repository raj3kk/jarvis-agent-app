# Keep OkHttp and JSON models
-dontwarn okhttp3.**
-keep class com.jarvis.autonomous.** { *; }
# 1.13.0 Gecko browser: Android Components' service-nimbus (transitive
# via feature-tabs / feature-downloads) references Glean telemetry
# classes that are not on the classpath, so release R8 fails with
# "Missing class mozilla.telemetry.glean.Glean". Nimbus is never used
# (no experiments, no telemetry) and nothing references it, so R8
# strips it; the dontwarn only lets R8 past analysis. (Spike-proven,
# CI run 44990580965.) Follow-up: add the Glean dep or keep this.
-dontwarn mozilla.telemetry.glean.**
