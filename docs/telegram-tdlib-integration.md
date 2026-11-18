# Telegram TDLib JSONJava integration — PR #5

## Current implementation
- Official Java JSONJava native interface declarations sourced from upstream commit `03220f6b3043c6fd7785fbf0901d63f19ad19974` (BSL-1.0).
- `TdJsonTelegramSessionClient` uses the official JSONJava functions for client creation, serialized commands, an asynchronous receive pump, phone/code/2FA authorization, logout, public chat lookup, message metadata, file download and file-completion events.
- `TelegramAuthorizationFlow` and `TelegramVideoDownloadGate` enforce `authorizationStateReady` before allowing user-initiated downloads.
- `TelegramApiCredentialStore` encrypts the app API ID/hash at rest using an Android Keystore AES-GCM key, with `noBackupFilesDir` used for TDLib database and downloads. OTP and 2FA passwords are not persisted.
- The Account Telegram menu exposes a native-availability check and authorization prompts when a verified JSONJava library is present.
- `TdlibSavedVideoImporter` copies completed TDLib files only from the app-private TDLib files directory to Saved Videos with a 2 GB limit.
- Existing public Telegram embed resolver, Share Sheet import and direct HTTPS downloads are unaffected.

## Required native library (not bundled in this branch yet)
- Official upstream: https://github.com/tdlib/td
- Pinned source commit: `03220f6b3043c6fd7785fbf0901d63f19ad19974`.
- Run under Linux/WSL2 with Docker: `bash scripts/build-tdlib-jsonjava.sh`.
- This runs the upstream Android Dockerfile with `TDLIB_INTERFACE=JSONJava`, validates the generated Java source against the committed wrapper and copies `libtdjsonjava.so` into `app/src/main/jniLibs/<abi>/`.
- GitHub workflow `tdlib-native-build.yml` builds candidate native binaries and uploads their checksums. Binaries require manual supply-chain and license review before promotion to the app build.
- Until the native libraries are present and validated, the app **will not log in to Telegram** and displays an explicit missing-native message. Android CI can still compile the Java integration safely.

## Developer API credentials
- Obtain an application API ID/hash from the official https://my.telegram.org site. Do not use credentials copied from unrelated Telegram client applications.
- Enter API ID/hash in AI Player's Account Telegram screen, not in GitHub source files, issue comments or logs.
- Keystore encryption protects the stored configuration at rest; a rooted or compromised device has an expanded threat model.
- OTP and 2FA are submitted to TDLib in process, not stored. Java Strings/JSON temporarily hold sensitive data in memory; comprehensive security review remains required.
- Logout clears the encrypted API configuration. Session DB cleanup, filesystem policy, lock screen and full logout behavior must be validated on Android before production release.

## Limitations and remaining work
- Native TDLib candidate build and integration must finish and be tested on the actual Android device; **build success of Java-only APK is not proof of native readiness**.
- Authentication states for QR, email, registration or other new Telegram flows are fail-closed and do not yet offer complete interactive UI.
- Exact TDLib message ID mapping uses the channel post ID shifted left by 20; needs integration test on current upstream TDLib version.
- The downloader currently handles ordinary messageVideo media, not every Telegram media class or protected content.
- Progress/cancel and intermittent-network handling for TDLib download require further device tests.
- Additional security review must cover credential memory lifetime, TDLib's local database cleanup, concurrent clients and callback lifecycle.

## Acceptance gates before merge
1. Native JSONJava build is verified/pinned and loaded on device arm64-v8a.
2. Fresh login, code, two-step password, restart and logout are tested with an account authorized to use Telegram.
3. User-requested `https://t.me/xdcmdj/3076` download succeeds only if account may save that video.
4. Copied video is listed under Saved Videos and works with transcript, translation, subtitles and TTS.
5. Negative cases: unauthorized account, nonexistent message, protected channel, large video, cancellation and lost connectivity.
6. Android assembleDebug/testDebugUnitTest/lintDebug PASS on final commit; security review completed.

**Keep PR #5 Draft and do not merge until all above are satisfied.**