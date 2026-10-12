# Validation — v1.1, 2026-10-12

- Built installable APK using JDK 17, Android SDK platform 35, build-tools 35.0.0,
  and D8 configured with min-api 19.
- Independently compiled all app Java sources against Android API 19's android.jar;
  no references requiring newer Android Java APIs were found by compilation.
- APK signature verification succeeds with minimum SDK 19, including v1 signing
  required by Android 4.4. Also signed with v2/v3 for newer devices.
- 32 adapter and local mock-HTTP regression checks pass (tests/ApiTest.java).
- 37 streaming checks pass (tests/StreamingTest.java), including live HTTP
  first-chunk delivery before completion for all five provider formats, fragmented
  UTF-8, SSE framing, partial failures, cancellation and bounded output.
- APK manifest reports versionCode 2, versionName 1.1, minimum SDK 19.
- Signing certificate SHA-256 matches the original v1.0 APK exactly:
  adf1f937dcee9079c70ade6855a6a7231e93e63ae68e782c463180ec53994a69.
  This permits an in-place upgrade preserving app data.
- Previously verified in v1.0 (unchanged TLS code): bundled CA loader succeeds and a local HTTPS server with an untrusted
  self-signed certificate is rejected (tests/TlsTest.java, desktop JVM).
- Mozilla CA bundle hash matches curl's published SHA-256:
  a41b5d356aea97a529fe27e0f7316d2f9d946d75927476cf9cf1b90637d00505
- No API credentials are included. Source package excludes local signing keys,
  build caches, SDK binaries, test dependencies and generated app classes.

User reports v1.0 works on their device. Not independently verified for v1.1:
device/emulator UI behavior; device-specific Android 4.4 TLS/ciphers;
real Ollama or cloud model requests; provider account billing/quota/access;
Android Studio/Gradle build path. Desktop TLS tests do not prove an old device's
TLS engine supports every cloud endpoint.
