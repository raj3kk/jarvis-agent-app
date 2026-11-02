# J.A.R.V.I.S. — Autonomous Multi-Step Android AI Agent

Ek complete, production-ready **Autonomous Android AI Agent** jo background mein chalta hai, ek normal dost ki tarah **Hinglish/Hindi/English** mein baat karta hai, aur **Android Accessibility Service** ka use karke kisi bhi app (WhatsApp, YouTube, Instagram, Chrome, Zomato, Spotify, etc.) ko khol kar **multi-step tasks (Tap, Type, Scroll, Swipe, Back/Home, Hardware controls)** khud execute karta hai.

---

## Bina Android Studio Ke Direct `.APK` Kaise Download Karein? (2-Minute Cloud Build)

Is project mein **GitHub Actions Auto-APK Builder** (`.github/workflows/build-apk.yml`) pehle se laga hua hai:

1. GitHub par ek naya repository banayein (e.g., `jarvis-android-agent`).
2. Is `JarvisAgentApp` folder ki saari files us repository mein upload/push kar dein.
3. GitHub repo ke **`Actions`** tab par click karein -> **`Build Jarvis Autonomous APK`** workflow apne aap chalega.
4. 2–3 minute mein build green ho jayega -> Niche **`Artifacts`** section se **`Jarvis-Autonomous-Release-APK`** download karein, zip extract karein aur apne phone mein `.apk` install kar lein!

---

## Android 13 / 14 / 15 Par Accessibility Permission On Karne Ka Important Tip

Jab aap sideloaded `.apk` install karte hain, toh Android 13+ security ki wajah se Accessibility setting greyed-out ("Restricted setting") dikha sakta hai. Isko 10 second mein unlock karne ka tarika:
1. APK install karne ke baad ek baar **Accessibility Settings** mein jaake `JARVIS Autonomous Screen Controller` par tap karein (Agar Restricted popup aaye toh OK karein).
2. Ab phone ki **Settings -> Apps -> JARVIS AI Agent** kholein.
3. Top-right corner ke **3 dots (`⋮`)** par tap karein aur **"Allow restricted settings"** par click karke fingerprint/PIN de dein.
4. Ab wapas Jarvis app ke **`Permissions` Tab** mein jayein aur saare 5 cards ko **GRANTED ✅** kar dein!

---

## Project Structure

- `PRODUCTION_PLAN.md`: Complete UI/UX Blueprint, Feature Architecture, aur Multi-Step ReAct Loop design.
- `app/src/main/AndroidManifest.xml`: All required permissions (`BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_MICROPHONE`, `QUERY_ALL_PACKAGES`, etc.).
- `app/src/main/java/com/jarvis/autonomous/`:
  - `service/JarvisAccessibilityService.kt`: Full Screen UI Hierarchy Parser, Password Redactor, Node Clicker, Coordinate Gesture Tap/Swipe/LongPress, App Launcher, Flashlight/Volume/Call/Alarm controller.
  - `service/JarvisForegroundService.kt`: Persistent background service with Partial WakeLock, Notification controls, and Voice + Overlay coordination.
  - `service/JarvisNotificationService.kt`: Real-time WhatsApp/SMS notification reader.
  - `agent/JarvisAgentEngine.kt` & `agent/LlmClient.kt`: Multi-step ReAct Loop supporting **Google Gemini**, **OpenAI**, **Groq**, and **OpenRouter**, mid-task voice clarification (`askUser`), and Payment Safe-Mode guard.
  - `voice/VoiceManager.kt`: Continuous Wake-Word (`"Hey Jarvis"`) + SpeechRecognizer + Hinglish TTS.
  - `ui/overlay/FloatingJarvisOverlay.kt`: Draggable Arc-Reactor HUD pill over all apps with live step telemetry & Emergency Stop button.
  - `ui/MainActivity.kt`: 4-Tab Futuristic Jetpack Compose UI (`Command Center`, `Permissions`, `AI Brain`, `Memory & Logs`).

---

## Production signing (important)

- Bina keystore ke build **debug key se sign** hota hai — phone par install ho jayega, par ye production signing nahi hai. Baad me production key se sign ki hui update install karne ke liye purani app uninstall karni padegi.
- Production key ke liye repo Secrets me ye 4 set karein, phir dobara build chalayein: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Keystore file aur `keystore.properties` kabhi commit nahi hote (`.gitignore` me hain).
- Build me Gradle wrapper (`./gradlew`, Gradle 8.7) use hota hai, isliye runner par Gradle version ka guess nahi lagta.

---

## Models (live-verified 2026-11-02 — purane dead IDs se 404 aata tha)

| Provider | Default model (ab) | Fallback |
|---|---|---|
| Google Gemini | `gemini-3.8-flash` | `gemini-3.5-flash-lite`, `gemini-3-flash-preview` |
| Groq | `openai/gpt-oss-120b` | `openai/gpt-oss-20b` |
| OpenAI | `gpt-4o-mini` (API me 2027-01-14 tak) | `gpt-4.1-mini` |
| OpenRouter | `google/gemini-3.8-flash` | `google/gemini-3.5-flash-lite`, `google/gemini-3-flash-preview` |

Purani build me save hua dead model (`gemini-2.5-flash`, `llama-3.3-70b-versatile` wagera) nayi build khud auto-migrate kar degi — dobara type karne ki zaroorat nahi. Koi model 404/503 de to app us provider ke fallback model par khud try karegi, phir bhi fail ho to poora error (code + reason) dikhayegi, 60 character me kaatke nahi.
