# GuardLock

An Android app where **two friends guard each other**. Each phone can lock apps on the other
phone, and each phone sees the other's screen time. Same APK on both phones.
Costs 0: Firebase free plan, no server code, sideloaded APK.

Rule of the app: **easy to lock, hard to unlock.** You can add apps to the lock list on your own
phone, but only your partner can unlock or remove them. Firestore security rules enforce this,
not just the UI.

## What it does
- **Two-way pairing** with an 8-character code. One phone makes the code, the other enters it.
- **My phone tab**: setup checklist, add apps to lock, list of what is locked on this phone.
- **Partner tab**: partner's health (protected / weakened / offline), and for each app locked on
  their phone: Lock / Unlock / "Open for 15 min, 1 h, until midnight" / Remove.
- **Compare tab**: screen time, you vs partner: today, last 7 days, and top apps today.
- **No new-user bypass**: adding Android users, guests and profiles is blocked (see below).
- Locks are cached, so they keep working offline and after reboot (fail-closed).

## Screen time
Needs the **Usage access** permission (setup card -> Screen time). Android keeps the usage log;
GuardLock reads it every ~15 min and when you open the app, and uploads only totals per app
for the last 7 days. Your partner sees these numbers. Free-plan cost is far below the quotas.

## Blocking new Android users
A new Android user (or guest, or work profile) does not have GuardLock installed, so it would
bypass every lock. Two levels:

1. **Basic (automatic).** The accessibility service closes the Settings screens for adding a
   user, guest or profile. It depends on the screen text of your phone brand, so treat it as a
   speed bump. The partner's Health card shows "Strong mode off".
2. **Strong mode (optional, recommended).** Make GuardLock the phone's *device owner* once, over
   USB. Then Android itself refuses: adding users, switching users, work/clone profiles,
   **safe mode**, and uninstalling GuardLock.

   ```
   adb shell dpm set-device-owner com.selfapps.guardlock/.service.GuardDeviceAdmin
   ```
   Requirements: USB debugging on; **no Google/Samsung account signed in on the phone while you
   run it** (remove them, run the command, add them back); no extra users on the phone.
   To undo it you must factory-reset the phone, so only do this if both of you agree.

## Battery and memory
- The accessibility service does not poll. It reacts to window changes, to new rules arriving,
  and to one timer when a timed unlock ends.
- Screen time is read from Android's own log. No background collector runs.
- One WorkManager job (~15 min) uploads health and screen time. One foreground service holds
  the rules listener, which is what makes a partner's Lock/Unlock arrive in about a second.
- Native Kotlin + Compose: no cross-platform runtime. Release minification is off on purpose
  until it can be tested on real phones.

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
1. Phone A: open app -> **Start - make my pairing code**.
2. Phone B: open app -> enter the code -> **Pair with my friend**.
3. Both phones: *My phone* tab -> finish the setup card (Accessibility, Screen time,
   notifications, battery, uninstall protection). On Android 13+ a sideloaded app needs
   *Settings -> Apps -> GuardLock -> ⋮ -> Allow restricted settings* first.
4. Phone B: *Add apps to lock*, pick one, open it -> lock screen.
5. Phone A: *Partner* tab -> toggle / "Open for…" -> Phone B's app opens within ~2 s.
6. Both: *Compare* tab -> your bars and your partner's bars.

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

## Known limitations (all planned)
- **Anonymous accounts**: clearing the app's data or reinstalling loses the identity, so the pair
  breaks. Phase 4 will add account linking. For now use *Unpair (dev only)* on both phones and
  start over.
- **Timed unlocks** use the guardian phone's clock for the deadline (the guarded phone converts it
  to a monotonic timer on receipt). Phase 4 moves this to server time.
- **Force-stop / safe mode / uninstall** are detected (heartbeat goes quiet) but only prevented in
  Strong mode.
- Not Play-Store ready: Accessibility use needs a declaration form. Sideload for now.
