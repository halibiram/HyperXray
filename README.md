# HyperXray

<img src="https://raw.githubusercontent.com/halibiram/HyperXray/main/metadata/en-US/images/icon.png" alt="HyperXray Logo" width="150">

[![GitHub Release](https://img.shields.io/github/v/release/halibiram/HyperXray)](https://github.com/halibiram/HyperXray/releases)
[![FDroid Release](https://img.shields.io/f-droid/v/com.hyperxray.an.svg)](https://f-droid.org/packages/com.hyperxray.an)
[![GitHub License](https://img.shields.io/github/license/halibiram/HyperXray)](LICENSE)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/halibiram/HyperXray/.github%2Fworkflows%2Fbuild.yml)](https://github.com/halibiram/HyperXray/actions)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/halibiram/HyperXray/total)

**HyperXray** is a high-performance proxy client for Android, **built upon the robust Xray-core ([@XTLS/Xray-core](https://github.com/XTLS/Xray-core))**.

It features an **innovative approach**: **directly executing the official Xray-core binary**, unlike traditional JNI methods. This method isolates core logic from the app layer, boosting stability and maximizing Xray-core's native performance. HyperXray aims to provide a stable and efficient network experience.

## 🎨 Brand Identity

The HyperXray logo represents the core values of the application:
- **🛡️ Shield**: Security and protection for your network traffic
- **⚡ Zap**: High performance and speed
- **🌊 Waves**: Smooth data flow and connectivity
- **Gradient Background**: Modern design with indigo-purple-blue gradient, symbolizing innovation and technology

## ✨ Key Features

*   **🛡️ Enhanced Stability**: By running Xray-core as an independent child process, HyperXray avoids JNI complexities, potential memory issues, and app crashes linked to core library failures. This isolation significantly improves reliability.
*   **⚡ High Performance**: Leverages Xray-core's native speed and integrates [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for efficient Tun2socks, ensuring low latency and high throughput.
*   **🎨 User-Friendly**: Offers a clean, intuitive UI with modern design and simplified setup, making it easy for users to configure and manage connections.
*   **🔒 Security First**: Built with security and privacy as core principles, ensuring your network traffic is protected.

## Unique Technical Approach

Most Xray-core Android clients use JNI to call a compiled .so library. While easy to integrate, this can cause stability issues like performance overhead, cross-language complexity, and app crashes if the core library fails.

**HyperXray's core difference is how it starts and manages the proxy:**

On installation/update, the embedded Xray-core binary (as `libxray.so`) is extracted. When connecting, the app uses standard Android APIs to **run this binary as a separate child process**, not via JNI calls. Communication happens via defined Inter-Process Communication (IPC).

This design preserves the original Xray-core binary's stability and performance while physically isolating the core process from the main app, enhancing reliability and security.

## Data Files (`geoip.dat` / `geosite.dat`)

The project **includes a simplified version** with basic rules (`"geoip:private"`, `"geoip:cn"`, `"geosite:gfw"`) from [@lhear/v2ray-rules-dat](https://github.com/lhear/v2ray-rules-dat).

## 🚀 Quick Start

1.  **📱 Requirement**: Android 10 (API 29) or higher.
2.  **📥 Get App**: Download the APK from the [Release Page](https://github.com/halibiram/HyperXray/releases) or get it from [F-Droid](https://f-droid.org/packages/com.hyperxray.an).
3.  **💾 Install**: Install the APK on your device.
4.  **⚙️ Configure**: Launch the app, import or manually add server details.
5.  **🔌 Connect**: Select a config and tap connect to start your secure connection.

## 🔧 Build Guide (Developers)

1.  **🛠️ Environment**: Install [Android Studio](https://developer.android.com/studio) and configure the Android SDK (API 35).
2.  **📦 Get Code**: Clone the repo and submodules:
    ```bash
    git clone --recursive https://github.com/halibiram/HyperXray
    cd HyperXray
    ```
3.  **📂 Import**: Open the project in Android Studio.
4.  **🔨 Integrate Core**: Place the Xray-core binary (`libxray.so`) for your target architecture in `app/src/main/jniLibs/[architecture directory]`. E.g., `app/src/main/jniLibs/arm64-v8a/libxray.so`.
5.  **📄 Add Data Files**: The build process automatically downloads `geoip.dat` and `geosite.dat` files during the build. These are required for routing.
6.  **🏗️ Build**: Sync Gradle and run the build task. The app will be built with the new logo and branding.

## 🤝 Contributing

Contributions are welcome! You can help by:
*   🐛 Submitting Bug Reports (Issues)
*   💡 Suggesting Features
*   💻 Submitting Code (Pull Requests)
*   📝 Improving Documentation
*   🎨 Design and UI improvements

Please read our contributing guidelines and code of conduct before submitting contributions.

## 📄 License

This project is licensed under the **[Mozilla Public License Version 2.0](LICENSE)**.

---

**HyperXray** - Fast, Secure, and Reliable Proxy Client for Android 🚀
