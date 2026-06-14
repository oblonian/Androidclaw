#!/bin/sh
# Collect screenshots captured by ScreenshotTour and dump each as base64 into
# the log so the UI can be reviewed without downloading the run artifact.
#
# AGP pulls files written to the test's additionalTestOutputDir off the device
# into app/build/outputs/connected_android_test_additional_output/ (and removes
# them from the device), so by the time this runs the device dir is already
# empty — we read from the build output dir instead. We still try an adb pull as
# a fallback in case a future change writes to the app's external files dir.

mkdir -p screenshots

# Primary source: AGP-collected additional test output on the runner.
find app/build/outputs/connected_android_test_additional_output -name "*.png" -type f 2>/dev/null \
  | while IFS= read -r f; do cp "$f" "screenshots/$(basename "$f")"; done

# Fallback: anything still on the device under the app's external files dir.
adb pull /sdcard/Android/data/com.androidclaw.app/files/screenshots screenshots/ 2>/dev/null || true

echo "===SCREENSHOTS_BEGIN==="
find screenshots -name "*.png" -type f | sort | while IFS= read -r f; do
  echo "===IMG_NAME:$(basename "$f")"
  base64 -w0 "$f"
  printf '\n===IMG_END\n'
done
echo "===SCREENSHOTS_END==="
