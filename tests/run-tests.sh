#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JSON_JAR:?Set JSON_JAR to a desktop org.json JAR}"
mkdir -p build/tests
javac -encoding UTF-8 -cp "$JSON_JAR" -d build/tests app/src/main/java/dev/pocketchat/Api.java app/src/main/java/dev/pocketchat/ReplyStream.java app/src/main/java/dev/pocketchat/MessagePayload.java tests/ApiTest.java tests/StreamingTest.java tests/AttachmentPayloadTest.java
for test in ApiTest StreamingTest AttachmentPayloadTest; do
 java -cp "build/tests:$JSON_JAR" "dev.pocketchat.$test"
done
