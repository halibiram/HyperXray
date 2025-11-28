#!/bin/bash
# HyperXray JNI Verification Test Script

echo "=== HyperXray JNI Verification Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if APK exists (try different names)
APK_PATH=""
for apk in "app/build/outputs/apk/debug/app-debug.apk" "app/build/outputs/apk/debug/hyperxray-universal.apk" "app/build/outputs/apk/debug/hyperxray-arm64-v8a.apk"; do
    if [ -f "$apk" ]; then
        APK_PATH="$apk"
        break
    fi
done

if [ -z "$APK_PATH" ]; then
    echo -e "${RED}✗ APK not found${NC}"
    echo "Please run: ./gradlew assembleDebug"
    exit 1
fi

echo -e "${GREEN}✓ APK found: $APK_PATH${NC}"
echo ""

# Check APK contents
echo "=== Checking APK Contents ==="
echo "Native libraries in APK:"
unzip -l "$APK_PATH" 2>/dev/null | grep -E "lib/.*\.so" | grep -E "hyperxray" | head -10
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${YELLOW}⚠ No Android device connected${NC}"
    echo "Please connect a device and enable USB debugging"
    exit 1
fi

echo -e "${GREEN}✓ Android device connected${NC}"
echo ""

# Install APK
echo "=== Installing APK ==="
adb uninstall com.hyperxray.an 2>/dev/null
adb install -r "$APK_PATH"
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ APK installed successfully${NC}"
else
    echo -e "${RED}✗ APK installation failed${NC}"
    exit 1
fi
echo ""

# Clear logcat
echo "=== Clearing logcat ==="
adb logcat -c
echo -e "${GREEN}✓ Logcat cleared${NC}"
echo ""

# Start logging in background
echo "=== Starting log monitoring ==="
echo "Monitoring logs for: HyperXray-JNI, HyperVpnService, AndroidRuntime"
adb logcat -s HyperXray-JNI:V HyperVpnService:V AndroidRuntime:E *:S &
LOGCAT_PID=$!
echo "Logcat PID: $LOGCAT_PID"
echo ""

# Launch app
echo "=== Launching app ==="
adb shell am start -n com.hyperxray.an/.activity.MainActivity
sleep 3
echo -e "${GREEN}✓ App launched${NC}"
echo ""

# Wait for library loading
echo "=== Waiting for library initialization (5 seconds) ==="
sleep 5

# Check logs for library loading
echo "=== Checking library loading status ==="
LOG_OUTPUT=$(adb logcat -d | grep -E "HyperXray|Native library|JNI|Go library" | tail -20)
if echo "$LOG_OUTPUT" | grep -q "loaded successfully"; then
    echo -e "${GREEN}✓ Library loading messages found${NC}"
    echo "$LOG_OUTPUT"
else
    echo -e "${YELLOW}⚠ No library loading messages found${NC}"
    echo "Checking for errors..."
    adb logcat -d | grep -E "Error|FATAL|Exception|UnsatisfiedLinkError" | tail -10
fi
echo ""

# Stop logcat
echo "=== Stopping log monitoring ==="
kill $LOGCAT_PID 2>/dev/null
echo ""

# Final status
echo "=== Test Summary ==="
echo "1. APK built and installed: ✓"
echo "2. App launched: ✓"
echo "3. Library loading: Check logs above"
echo ""
echo "Next steps:"
echo "- Check the logs above for any errors"
echo "- Try to start VPN connection through the app UI"
echo "- Monitor logs with: adb logcat -s HyperXray-JNI:V HyperVpnService:V"
echo ""

