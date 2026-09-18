name: Build Jarvis Android APK

on:
  workflow_dispatch:
  push:
    branches:
      - main

concurrency:
  group: jarvis-android-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout Jarvis
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        shell: bash
        run: |
          set -euo pipefail

          SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

          yes | "$SDKMANAGER" --licenses >/dev/null || true

          "$SDKMANAGER" \
            "platforms;android-35" \
            "build-tools;35.0.0"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.11.1'

      - name: Check Jarvis project
        shell: bash
        run: |
          set -euo pipefail

          echo "Checking Jarvis Android files..."

          REQUIRED_FILES=(
            "settings.gradle.kts"
            "build.gradle.kts"
            "gradle.properties"
            "app/build.gradle.kts"
            "app/src/main/AndroidManifest.xml"
            "app/src/main/java/com/novajarvis/android/MainActivity.kt"
            "app/src/main/java/com/novajarvis/android/ProjectWorkspace.kt"
            "app/src/main/res/values/styles.xml"
          )

          for FILE in "${REQUIRED_FILES[@]}"; do
            if [ ! -f "$FILE" ]; then
              echo "ERROR: Missing required file: $FILE"
              exit 1
            fi

            echo "OK: $FILE"
          done

          echo "Jarvis Android project is ready."

      - name: Build Jarvis APK
        shell: bash
        run: |
          set -euo pipefail

          gradle \
            --no-daemon \
            --stacktrace \
            :app:assembleDebug

      - name: Verify APK
        shell: bash
        run: |
          set -euo pipefail

          APK="app/build/outputs/apk/debug/app-debug.apk"

          if [ ! -f "$APK" ]; then
            echo "ERROR: Jarvis APK was not created."
            exit 1
          fi

          if [ ! -s "$APK" ]; then
            echo "ERROR: Jarvis APK exists but is empty."
            exit 1
          fi

          echo "Jarvis APK successfully created:"
          ls -lh "$APK"

      - name: Upload Jarvis APK
        uses: actions/upload-artifact@v4
        with:
          name: Jarvis-Android-APK
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 30
