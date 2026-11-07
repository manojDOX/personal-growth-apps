# GuardLock (Phase 1)

An Android app where a **guardian** (your friend) controls which apps are locked on **your** phone.
Same APK, two roles. Costs ₹0: Firebase free plan, no server code, sideloaded APK.

Rule of the app: **easy to lock, hard to unlock.** You can add apps to the lock list, but only the
guardian can unlock or remove them - enforced by Firestore security rules, not just the UI.

## What Phase 1 does
- Role pick (guardian / guarded) and pairing by an 8-character code
- Guarded user picks apps to lock; the lock screen covers them (Accessibility service)
- Guardian sees the list live and can Lock / Unlock / "Open for 15 min, 1 h, until midnight" / Remove
- Guarded phone sends a heartbeat (WorkManager ~15 min + on service start); guardian sees
  protected / weakened / offline status
- Rules are cached, so locks keep working offline and after reboot (fail-closed)

Not yet (Phases 2-5): unlock requests, usage/attempt stats, consistency calendar, tamper alerts,
schedules.

## One-time setup

1. **Android Studio** (Koala or newer). Open the `guardlock` folder. Let it sync (it downloads the
   SDK platform 35 and Gradle 8.9 itself; the bundled JDK 17 is used).
2. **Firebase** (console.firebase.google.com, free Spark plan, no card):
   - Create a project -> *Add app -> Android*, package name **`com.selfapps.guardlock`**
   - Download `google-services.json` into **`guardlock/app/`**
   - *Build -> Authentication -> Sign-in method -> Anonymous -> Enable*
   - *Build -> Firestore Database -> Create database* (production mode)
   - Firestore *Rules* tab -> paste the contents of `firestore.rules` -> Publish
3. Run on two phones (or one phone + one emulator). Don't pick **Cloud Functions** or anything
   that asks for a billing account.

## Trying it
1. Phone A: open app -> **I'm the guardian** -> note the code.
2. Phone B: open app -> **I'm being guarded** -> enter the code.
3. Phone B: finish the setup card (Accessibility, notifications, battery, uninstall protection).
   On Android 13+ a sideloaded app needs *Settings -> Apps -> GuardLock -> ⋮ -> Allow restricted
   settings* before the Accessibility switch becomes usable.
4. Phone B: *Add apps to lock*, pick one, open it -> lock screen.
5. Phone A: toggle the switch / "Open for…" -> Phone B's app opens within ~2 s.

## CI/CD (GitHub Actions)

`.github/workflows/guardlock.yml` runs on every push to the `guardlock` branch:

| Job | Runs on | Needs secrets | Does |
|---|---|---|---|
| `build` | push, PR, manual | no | builds the debug APK (uploaded as an artifact) |
| `deploy` | push / manual on `guardlock` only | yes (environment `guardlock`) | builds a **signed release APK**, deploys `firestore.rules`, then ships the APK to testers via **Firebase App Distribution** |

PRs never reach `deploy`, so secrets are never exposed to forks.

### Create the environment
GitHub repo -> Settings -> Environments -> New environment named **`guardlock`**
(optionally add "Required reviewers" to approve each deploy, and restrict it to the `guardlock` branch).
Add these **secrets** to it:

| Secret | What it is |
|---|---|
| `GOOGLE_SERVICES_JSON_BASE64` | `base64 -w0 app/google-services.json` (Firebase console download) |
| `KEYSTORE_BASE64` | `base64 -w0 guardlock-release.jks` (see below) |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | values you chose when creating the keystore |
| `FIREBASE_PROJECT_ID` | Firebase project id (Project settings -> General) |
| `FIREBASE_APP_ID` | the Android app id, looks like `1:1234567890:android:abc123...` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | full JSON key of a service account (below) |

Optional **variable**: `TESTER_GROUPS` (comma-separated App Distribution group aliases, default `testers`).

Create the release keystore once and keep it safe (losing it means testers must uninstall to update):

```
keytool -genkeypair -v -keystore guardlock-release.jks -alias guardlock -keyalg RSA -keysize 2048 -validity 10000
```

Service account: Google Cloud console -> IAM -> Service accounts -> create, grant roles
**Firebase App Distribution Admin** and **Firebase Rules Admin**, then Keys -> Add key -> JSON.

In Firebase console -> App Distribution -> Testers & Groups, create a group with alias `testers` and
add yourself and your friend. Testers get an email/app notification for each new build.

## Known Phase 1 limitations (all planned)
- **Anonymous accounts**: clearing the app's data or reinstalling loses the identity, so the pair
  breaks. Phase 4 will add account linking. For now use the guardian's *Unpair (dev only)* and
  start over.
- **Timed unlocks** use the guardian phone's clock for the deadline (the guarded phone converts it
  to a monotonic timer on receipt). Phase 4 moves this to server time.
- **Force-stop / safe mode / uninstall** are detected (heartbeat goes quiet) but not prevented.
- Not Play-Store ready: Accessibility use needs a declaration form. Sideload for now.
