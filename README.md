# JSTB - Job Search & Talent Bridge (Google Play Edition)

**JSTB** is a smart job search companion and assisted application assistant for Android. It helps job seekers discover relevant opportunities, automatically match positions with their profile and CV skills, and assists in filling out job application forms smoothly with a single tap to submit.

---

## Features

- **Smart Job Matching:** Automatically discovers and matches job listings against your CV profile (>= 60% match score).
- **1-Tap Assisted Apply:** In-app browser assists in auto-filling repetitive application details (name, email, phone, experience, expected salary) while leaving final submission control firmly in your hands.
- **Privacy First:** Applications are submitted directly by you. No credentials or passwords are ever stored.
- **Google Drive Integration:** Seamlessly sync your CV and saved preferences directly to your personal Google Drive account.
- **Cross-Platform Job Tracking:** Track application progress and history across all your target positions.

---

## Architecture & Compliance

- **Google Play Compliant:** Adheres strictly to Google Play User Data, Accessibility, and Spam policies.
- **Zero Headless Automation:** Operates strictly with user presence and interaction.
- **Official Google Play Distribution:** App updates and bundles are delivered securely via Google Play Store (AAB format).

---

## Building from Source

### Requirements
- JDK 17+
- Android SDK (API 34+)
- `aapt2`, `dalvik-exchange`, `bundletool`

```bash
cd android
./build.sh          # Produces out/JSTB.aab (Google Play Bundle)
APK=1 ./build.sh    # Produces out/JSTB.apk (Universal testing APK)
```

---

## Authors & Contributors

- **Ahmed Sabet** ([@AhmedSabet0](https://github.com/AhmedSabet0))
- **Antigravity** (Google DeepMind)
