# Telegram: user-consented video import

## Supported
- Public Telegram post URLs: best-effort parsing of public embed direct video URLs.
- Authenticated user app handoff: open post in Telegram, which is already logged in; share a VIDEO FILE via Android Sharesheet to AI Player. AI Player uses the temporarily granted content URI to copy video into app-private storage. No Telegram credentials are passed to AI Player.
- Direct HTTPS MP4/WebM/MKV URLs use the existing SavedVideoStore pipeline.

## Limitations
- Sharing a Telegram POST LINK is not the same as sharing media bytes.
- Some posts or Telegram versions do not permit saving or sharing media. Respect restrictions; fall back to opening video manually when permitted.
- Import accepts MIME video/mp4, video/webm or video/x-matroska, at most 2 GB.
- Automatic authenticated Telegram URL download is NOT implemented. Full TDLib login is a separate feature requiring a security-reviewed native library, explicit account authorization, secure local session management and logout.
- No Telegram user password, verification code, cookie, bot token or session file is requested or stored here.

## Device regression checklist
1. Import shared video from Telegram using Android Sharesheet and verify Saved videos and playback.
2. Cancel import confirmation and verify no video created.
3. Direct HTTPS download remains working.
4. Public Telegram post lacking direct media explains limitation and opens the Telegram app on request.
5. Reject unsupported content type and clean partial files on provider/IO/storage failures.
6. Transcript, translation, subtitles and TTS still work for imported media.

## Future full TDLib authentication (NOT part of PR #4)
- Pinned official TDLib binary, user-provided official API ID/hash, explicit phone/QR authentication and two-step verification.
- Secure on-device session state with logout and deletion, no secrets in source control.
- User-initiated scoped message/file download, access controls and provider-specific error states.
- Security and privacy review, integration tests, and device acceptance gates before merge.