<img width="480" height="800" alt="providers" src="https://github.com/user-attachments/assets/3f853f90-4eea-42a6-9092-4619f1d09c1f" />
<img width="480" height="800" alt="chat" src="https://github.com/user-attachments/assets/410e7208-9f32-4a9d-b5d1-93a5da1bc05e" />


# Pocket Chat — Android 4.4+

An entirely vibe coded small native Java chat client for Ollama on your PC, OpenAI, Claude, Gemini,
and other HTTPS services implementing OpenAI Chat Completions. No ads, analytics,
WebView, Google Play Services, or third-party runtime SDKs.

## Install

1. Copy `PocketChat.apk` to the phone and open it. Enable
   **Settings → Security → Unknown sources** to allow installation, then disable
   that setting again when finished.
2. Open **Pocket Chat**, select a provider, and tap **Settings**.
3. Set the base URL and API key, tap **Find models**, and select a text-chat model.
   You can also type an exact model ID. Tap **Save** and send a message.

## Connect Ollama on your PC

Install Ollama on the PC, download a model that fits your PC, and confirm it works
locally. `ollama list` shows the installed model names.

Ollama normally listens only on the PC's loopback interface. Set
`OLLAMA_HOST=0.0.0.0:11434` and restart Ollama to listen on the local network.

**Windows (PowerShell):**

```powershell
[Environment]::SetEnvironmentVariable('OLLAMA_HOST', '0.0.0.0:11434', 'User')
```

Then quit Ollama from the system tray and launch it again. Allow inbound TCP
11434 on your private home network in Windows Firewall. Run `ipconfig` to find
the PC's Wi-Fi/Ethernet IPv4 address.

**Linux, manually started Ollama:** stop any already-running Ollama service first,
then run:

```sh
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

For a systemd installation, use `sudo systemctl edit ollama`, add:

```ini
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
```

Then run `sudo systemctl daemon-reload` and `sudo systemctl restart ollama`.

**macOS app:** run `launchctl setenv OLLAMA_HOST "0.0.0.0:11434"`, then quit and
relaunch Ollama.

In Pocket Chat select **Ollama**, replace the example address with
`http://YOUR_PC_IP:11434`, leave the key blank, and tap **Find models**. Do not
enter `localhost`: on the phone.

Use the same trusted LAN/Wi-Fi. Guest networks can prevent devices from talking
to each other. HTTP Ollama traffic is unencrypted and its local server normally
has no authentication; limit firewall access to your LAN and do not port-forward
11434 to the internet. Native Android requests do not need OLLAMA_ORIGINS/CORS.

## Providers and cost

| Provider | Base URL | Key / model |
|---|---|---|
| Ollama | `http://YOUR_PC_IP:11434` | No key for standard local Ollama; choose an installed model |
| OpenAI | `https://api.openai.com/v1` | OpenAI API key, credit and a Chat Completions-compatible model |
| Claude | `https://api.anthropic.com/v1` | Claude API key, credit and a Messages-compatible model |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | Google AI Studio API key and a generateContent model |
| OpenAI-compatible | Your service's HTTPS API base, often ending in `/v1` | Service-specific key and model |

Keys: https://platform.openai.com/api-keys · https://platform.claude.com/ ·
https://aistudio.google.com/apikey

## Behavior and privacy

- Text chat streams into the reply bubble as it is generated. UI updates are batched
  at up to 20 times per second to keep old phones responsive. Auto-scroll follows
  while you are near the bottom; scroll up to read earlier messages during generation.
- Text only: no images, voice, tools, markdown renderer or background chat service.
- Ollama uses newline-delimited JSON; the cloud providers use server-sent events.
  Compatible servers that ignore streaming and return ordinary JSON still work,
  but their answers appear all at once. There is no automatic retry/fallback request.
- One locally saved chat per provider. Switching providers keeps them separate.
  Changing a provider's base URL or model deletes its previous local chat.
- The most recent 20 exchanges (up to roughly 120,000 characters) are retained;
  older exchanges are discarded. This is a character bound, not a model's token
  limit. Use New chat if a model reports that its context is full.
- Messages are limited to 32,000 characters; replies exceeding 64,000 characters
  are visibly truncated and the stream is closed. Ordinary JSON responses are bounded
  at 2 MiB; streams have an 8 MiB character traffic limit and 256 KiB event/line limits.
- Keys stay in process memory by default and must be reentered after the process
  ends. “Remember key” saves the key in private preferences **without additional
  encryption**. Do not enable it on a shared or untrusted phone.
- Messages and recent context are sent directly to the selected API endpoint.
  There is no app developer backend. Custom endpoints receive whatever key and
  text you enter for that provider.
- Requests are not retried automatically. Stop cancels the local request; it cannot
  guarantee that a server stops processing or billing it. Stop keeps a partial reply
  with a [Stopped] label. Dropped/failed streams keep a partial reply with a
  [Reply interrupted] label, so they are never presented as complete. If no text
  arrived, the prompt returns to the composer and is not added to saved history.
- Rotation is handled without restarting requests. Process death can interrupt a
  request; completed saved history remains.

## Android 4.4 HTTPS compatibility

The app explicitly enables TLS 1.2 (and TLS 1.3 where supported). It includes
Mozilla's CA roots, converted by curl, to avoid relying on the phone's decade-old
root list. Certificate-chain and hostname verification remain enabled; there is
no “ignore SSL errors” option. HTTPS uses this bundled trust store, not user-added
or private system certificates. Refresh the bundle and rebuild as roots change.

A CA bundle cannot add new ciphers to Android's TLS implementation. Some future
servers may still be incompatible. Keep the phone's date/time correct. If a
provider still fails TLS negotiation, a current device is the direct workaround;
local HTTP Ollama does not depend on the phone's HTTPS stack.

## Build the APK yourself

### Command line (the build used for this APK)

Install JDK 17, Android SDK command-line tools, platform 35 and build-tools 35.0.0:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
export ANDROID_HOME=/path/to/android-sdk
./build-apk.sh
```

The output is `build/PocketChat.apk`. Requires `bash`, `zip`, `javac`, `jar`,
`keytool` and the Android SDK tools on a Linux/macOS PC. The script creates a local
`build/development.jks` signing key. Retain it privately for future upgrades;
a different signing key requires uninstalling the previous APK (which removes
its saved app data). The signing key for the supplied APK is not distributed.
On Linux, if zipalign cannot find libc++.so, add the SDK build-tools `lib64`
directory to `LD_LIBRARY_PATH`.

### Android Studio / Gradle

Open this folder as a project. The Gradle files use AGP 8.7.3, compileSdk 35,
minSdk 19, targetSdk 28, and Java 8 bytecode. Use JDK 17 and Gradle 8.9.
There is no included Gradle wrapper; with Gradle installed, run
`gradle :app:assembleDebug`, or generate a wrapper with `gradle wrapper`.
The provided APK was built by the script above; the Gradle path is included for
editing and has not been executed here. Target SDK 28 is intentional for this
legacy sideload app and is not intended to meet current Play Store requirements.

## Tests

`tests/ApiTest.java` is a desktop test harness using org.json 20240303 (test only).
It exercises five adapter formats, response parsing, blocked responses, validation,
UTF-8, an actual local mock HTTP server, model listing, auth headers, cancellation,
quota errors, secret redaction, and redirect refusal. No real keys are needed.

```sh
mkdir -p test-build
javac -cp /path/to/json-20240303.jar -d test-build \
  app/src/main/java/dev/pocketchat/Api.java \
  app/src/main/java/dev/pocketchat/ReplyStream.java tests/ApiTest.java tests/StreamingTest.java
java -cp /path/to/json-20240303.jar:test-build dev.pocketchat.ApiTest
java -cp /path/to/json-20240303.jar:test-build dev.pocketchat.StreamingTest
```

The streaming test also verifies fragmented UTF-8, multiline SSE, interrupted
streams, cancellation, malformed events, output limits and an HTTP server that
will not finish until the client has delivered its first chunk for each provider.
See `VALIDATION.md` for what was and was not verified.

## Source references

- Ollama: https://docs.ollama.com/api/chat and https://docs.ollama.com/faq
- OpenAI: https://platform.openai.com/docs/api-reference/chat/create
- Claude: https://platform.claude.com/docs/en/api/messages/create
- Gemini: https://ai.google.dev/api/generate-content
- Pricing: https://ai.google.dev/gemini-api/docs/pricing and https://platform.openai.com/docs/pricing
- Android TLS: https://developer.android.com/reference/javax/net/ssl/SSLSocket
- Root certificates: https://curl.se/docs/caextract.html

App source is MIT licensed; see LICENSE. The unmodified Mozilla CA bundle is
separately licensed under MPL 2.0; see MOZILLA-LICENSE.txt and THIRD-PARTY.md.

