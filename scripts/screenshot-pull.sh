#!/bin/sh
# Pull screenshots from device and dump each as base64 into the log.
# Called as a single line by the emulator-runner script block.

mkdir -p screenshots

# Try to pull from where ScreenshotTour saves (external files dir or additionalTestOutputDir)
adb pull /sdcard/Android/data/com.androidclaw.app/files/screenshots screenshots/ 2>/dev/null || true
adb pull /sdcard/Android/media/com.androidclaw.app/additional_test_output screenshots/ 2>/dev/null || true

echo "===SCREENSHOTS_BEGIN==="
find screenshots -name "*.png" -type f | sort | while IFS= read -r f; do
  echo "===IMG_NAME:$(basename "$f")"
  base64 -w0 "$f"
  printf '\n===IMG_END\n'
done
echo "===SCREENSHOTS_END==="
