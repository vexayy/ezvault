# EZVault

**EZVault** is a modern, lightweight, and reactive economy bridge for Minecraft plugins, supporting asynchronous operations, prioritization of multiple providers, and event-based balance tracking — all in a clean and developer-friendly design.

![Logo](https://github.com/yourusername/EZVault/logo.png) <!-- Podmień na prawdziwy link lub usuń -->

## ✨ Features

- ⚡ **Asynchronous Economy Operations**  
  Non-blocking balance updates and queries with RxJava 3.

- 🔁 **Multiple Provider Priorities**  
  Automatically resolve conflicts between Vault, Essentials, and others.

- 📡 **Built-in HTTP API**  
  Optional internal HTTP server for integrations and diagnostics.

- 📊 **Reactive Events**  
  Subscribe to `VaultBalanceChangedEvent` and others to track player economy changes in real-time.

- 🧩 **Simple Integration**  
  One-line setup with powerful builder options.

---

## 🚀 Getting Started

### 📦 Installation (Maven)

Add the following to your `pom.xml`:

```xml
<repositories>
    <repository>
       <id>jitpack.io</id>
       <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.vexayy</groupId>
        <artifactId>ezvault</artifactId>
        <version>v1.0</version>
    </dependency>
</dependencies>
```
