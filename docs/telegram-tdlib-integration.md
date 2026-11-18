# TDLib authenticated Telegram integration — staged implementation (PR #5)

## Current implementation status
- Implemented and unit-testable: explicit authorization state mapping, client boundary, unavailable-native fail-closed implementation.
- **NOT implemented yet**: official TDLib JNI binaries and Java binding, TDLib client/event loop, API ID/hash provision, phone/QR authentication, two-step verification, encrypted database lifecycle, message lookup, downloadFile, or UI login.
- A missing TDLib implementation MUST NOT silently claim login success or suggest that authenticated Telegram URL downloads work.
- Preserve the existing direct HTTPS downloads, public-post best-effort resolver, and Telegram app Share Sheet import from PR #4.

## Official implementation dependency
- Use official upstream source: https://github.com/tdlib/td and official Android build guide: https://github.com/tdlib/td/blob/master/example/android/README.md .
- Pin the exact upstream commit with a reviewed checksum and build metadata before bundling any generated .so or Java source. Do not fetch unreviewed third-party Telegram SDK binaries.
- Build ABI packages deliberately, including arm64-v8a for the test device, and verify license/size implications.
- Official TDLib API reference: https://core.telegram.org/tdlib/docs/ . TDLib Java JNI and JSON APIs have distinct packaging; never mix mismatched Java bindings and native builds.

## Security and privacy requirements
1. Users explicitly enable Telegram in AI Player; no automated access to existing Telegram app sessions.
2. Telegram api_id/api_hash should be configured through an approved secure product integration process, not copied from random sample projects or committed in source.
3. TDLib private database and files belong in internal app storage; ensure logout removes the TDLib session and local secrets. Evaluate encryption/storage and Android backup exclusions.
4. Do not record phone, code, 2FA password, message content or session token in logs/analytics. Handle mutable char[] input and wipe it as soon as a native call consumes it.
5. Authenticated API must be disabled until authorizationStateReady. Unknown states or missing native binaries fail closed.
6. Only download media from a message explicitly selected by the user and currently accessible to the account. Respect access, forwarding/saving restrictions, copyright, and Telegram rules.
7. Show progress/cancel/error, cap files at 2 GB and coordinate with the existing SavedVideoStore storage and import pipeline.

## Intended TDLib state machine and UI
- INITIALIZING: setTdlibParameters from approved configuration; read native updateAuthorizationState.
- PHONE_REQUIRED: setAuthenticationPhoneNumber or requestQrCodeAuthentication.
- CODE_REQUIRED / EMAIL_CODE_REQUIRED: checkAuthenticationCode / checkAuthenticationEmailCode.
- PASSWORD_REQUIRED: checkAuthenticationPassword for Telegram 2FA.
- READY: allow user-initiated getChat/getMessage/downloadFile flows.
- LOGGING_OUT/CLOSED: revoke access, close client, remove local session as selected by user.
- Additional states (registration, email and QR confirmation) must have explicit behavior and are never treated as READY.

## Acceptance gates
- Reproducible official TDLib native + Java build, license audit, and no source-controlled credentials.
- Unit tests for every auth transition and failure, lifecycle tests including restart/log out.
- Instrumentation on Android device: login, two-step verification, cancel, private channel access denial, authorized video download, playback, transcript/subtitle/TTS.
- GitHub Actions assembleDebug/testDebugUnitTest/lintDebug pass on latest head.
- PR stays Draft and must not merge until native TDLib bridge and device acceptance checks are complete.