# Integration Testing Guide for Refactored TProxyService

## Overview

This guide outlines integration testing procedures for the refactored `TProxyService` that uses manager classes for separation of concerns.

## Test Environment Requirements

- Android device or emulator (API 24+)
- VPN permission granted
- Valid Xray configuration file
- Root access (optional, for advanced testing)

## Integration Test Checklist

### 1. Manager Initialization Tests

#### VpnInterfaceManager
- [ ] Manager initializes correctly in `onCreate()`
- [ ] `isEstablished()` returns false initially
- [ ] `getTunFd()` returns null initially
- [ ] Thread-safe access to TUN FileDescriptor

#### XrayProcessManager
- [ ] Manager initializes correctly in `onCreate()`
- [ ] `isProcessRunning()` returns false initially
- [ ] `getProcessId()` returns null initially
- [ ] `getMultiXrayCoreManager()` returns null initially
- [ ] Thread-safe process state management

#### HevSocksManager
- [ ] Manager initializes correctly in `onCreate()`
- [ ] `isRunning()` returns false initially
- [ ] `getStats()` returns null when not running
- [ ] Config file creation works correctly

#### ServiceNotificationManager
- [ ] Manager initializes correctly in `onCreate()`
- [ ] Notification channel is created
- [ ] Foreground notification is displayed

### 2. VPN Interface Lifecycle Tests

- [ ] VPN interface establishes successfully
- [ ] TUN FileDescriptor is valid after establishment
- [ ] VPN interface closes correctly on stop
- [ ] Multiple establish/close cycles work correctly
- [ ] VPN permission handling works correctly

### 3. Xray Process Lifecycle Tests

#### Single Instance Mode
- [ ] Process starts successfully
- [ ] Process streams are read correctly
- [ ] Logs are broadcast correctly
- [ ] Process stops gracefully
- [ ] Process cleanup completes

#### Multi-Instance Mode
- [ ] Multiple instances start successfully
- [ ] Instance status updates are broadcast
- [ ] Load balancing works correctly
- [ ] Sticky routing works correctly
- [ ] All instances stop correctly

### 4. TProxy Service Lifecycle Tests

- [ ] TProxy starts after VPN interface is established
- [ ] TProxy config file is created correctly
- [ ] TProxy statistics are collected correctly
- [ ] TProxy stops before VPN interface closes
- [ ] TProxy restart works correctly (AI optimizer)

### 5. Notification Tests

- [ ] Notification is shown when service starts
- [ ] Notification updates correctly
- [ ] Notification channel is created
- [ ] Foreground service stays alive

### 6. Thread Safety Tests

- [ ] Concurrent access to `getTunFd()` is safe
- [ ] Concurrent access to `isEstablished()` is safe
- [ ] Concurrent access to `isProcessRunning()` is safe
- [ ] Concurrent start/stop operations are handled correctly

### 7. Error Handling Tests

- [ ] VPN permission denied is handled correctly
- [ ] Invalid config file is handled correctly
- [ ] Process crash is handled correctly
- [ ] TProxy start failure is handled correctly
- [ ] Network errors are handled correctly

### 8. Integration Flow Tests

#### Complete Connection Flow
1. [ ] Service receives ACTION_START intent
2. [ ] VPN interface is established
3. [ ] Xray process(es) start
4. [ ] TProxy service starts
5. [ ] Notification is shown
6. [ ] Logs are broadcast
7. [ ] Stats are collected

#### Complete Disconnection Flow
1. [ ] Service receives ACTION_DISCONNECT intent
2. [ ] TProxy service stops
3. [ ] Xray process(es) stop
4. [ ] VPN interface closes
5. [ ] Notification is removed
6. [ ] All resources are cleaned up

#### Config Reload Flow
1. [ ] Service receives ACTION_RELOAD_CONFIG intent
2. [ ] Xray process(es) restart with new config
3. [ ] TProxy config is updated if needed
4. [ ] Service continues running

### 9. Manager Coordination Tests

- [ ] Managers are initialized in correct order
- [ ] Managers are cleaned up in correct order
- [ ] Manager state is consistent across operations
- [ ] Manager errors are handled gracefully

### 10. Performance Tests

- [ ] Service startup time is acceptable (< 5 seconds)
- [ ] Process startup time is acceptable (< 10 seconds)
- [ ] Memory usage is within limits
- [ ] CPU usage is acceptable
- [ ] No memory leaks during start/stop cycles

## Manual Testing Steps

### Basic Connection Test

1. Start the app
2. Select a valid Xray configuration
3. Tap "Connect"
4. Verify:
   - VPN permission dialog appears (if not granted)
   - VPN interface is established
   - Xray process starts
   - TProxy service starts
   - Notification appears
   - Connection is successful

### Basic Disconnection Test

1. While connected, tap "Disconnect"
2. Verify:
   - TProxy service stops
   - Xray process stops
   - VPN interface closes
   - Notification is removed
   - All resources are cleaned up

### Config Reload Test

1. While connected, modify Xray configuration
2. Send ACTION_RELOAD_CONFIG intent
3. Verify:
   - Xray process restarts with new config
   - Connection continues working
   - No service restart required

### Multi-Instance Test

1. Set `xrayCoreInstanceCount` to 2 or more
2. Connect
3. Verify:
   - Multiple Xray instances start
   - Instance status updates are received
   - Load balancing works
   - All instances stop on disconnect

### Error Recovery Test

1. Start connection
2. Simulate error (kill Xray process, network error, etc.)
3. Verify:
   - Error is detected
   - Error is broadcast to UI
   - Service attempts recovery if applicable
   - Service stops gracefully if recovery fails

## Automated Integration Tests

For automated integration tests, use Android Instrumented Tests:

```kotlin
@RunWith(AndroidJUnit4::class)
class TProxyServiceIntegrationTest {
    @get:Rule
    val serviceRule = ServiceTestRule()
    
    @Test
    fun testCompleteConnectionFlow() {
        // Test complete connection flow
    }
    
    @Test
    fun testManagerCoordination() {
        // Test manager coordination
    }
    
    @Test
    fun testErrorHandling() {
        // Test error handling
    }
}
```

## Test Data

- Valid Xray configuration file
- Invalid Xray configuration file
- Configuration with multiple outbounds
- Configuration with Reality protocol
- Configuration with VLESS protocol

## Expected Results

- All managers initialize correctly
- VPN interface establishes successfully
- Xray process(es) start and run correctly
- TProxy service starts and runs correctly
- Notifications are displayed correctly
- Logs are broadcast correctly
- Stats are collected correctly
- All cleanup operations complete successfully
- No memory leaks or resource leaks
- Thread safety is maintained

## Known Limitations

- Native JNI functions require actual Android environment
- VPN interface requires VPN permission
- Process management requires actual process execution
- File system operations require proper permissions

## Notes

- Integration tests should be run on actual devices or emulators
- Some tests may require root access for advanced scenarios
- Performance tests should be run on real devices
- Memory leak tests should use LeakCanary or similar tools



