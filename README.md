# EZVault

**EZVault** is a modern, lightweight, and reactive economy bridge for Minecraft plugins, supporting asynchronous operations, prioritization of multiple providers, and event-based balance tracking — all in a clean and developer-friendly design.

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

---

## 🚀 Quick Start

### Basic Initialization

```java
public class MyPlugin extends JavaPlugin {
    
    @Override
    public void onEnable() {
        // Initialize with default settings
        EZVault.builder(this)
            .providerPriority("Essentials", 10) // Higher priority
            .providerPriority("CMI", 5)
            .backupIntervalSeconds(300) // 5 minute backups
            .httpPort(8080) // Enable REST API
            .connect();
    }
}
```

## 📚 Core API

### Synchronous Methods

```java
// Get player balance
double balance = EZVault.balance(player);

// Check if economy is ready
boolean ready = EZVault.isConnected();
```

### Asynchronous Transactions

```java
// Deposit with callback
EZVault.depositAsync(player, 100.0)
    .thenAccept(success -> {
        if (success) {
            player.sendMessage("You received $100!");
        }
    });

// Withdraw with error handling
EZVault.withdrawAsync(player, 50.0)
    .exceptionally(e -> {
        e.printStackTrace();
        return false;
    });
```

## 🔔 Event System

### Available Events

### Event Class	              Description
### VaultConnectedEvent	When EZVault initializes
### VaultDisconnectedEvent	On shutdown
### VaultBalanceChangedEvent	When player balance changes

### Subscribe to Events

```java
EZVault.events().subscribe(event -> {
    if (event instanceof VaultBalanceChangedEvent) {
        VaultBalanceChangedEvent e = (VaultBalanceChangedEvent) event;
        getLogger().info(String.format(
            "%s's balance changed from %.2f to %.2f",
            e.player.getName(),
            e.oldBalance,
            e.newBalance
        ));
    }
});
```

## ⚙️ Configuration

### Builder Options
 
### Method	                        Description	        Default
### providerPriority(String, int)	Set provider priority	1
### maxRetries(int)	Max transaction retries	5
### retryDelayMillis(long)	Delay between retries	5000ms
### backupIntervalSeconds(int)	Backup frequency	300s
### httpPort(int)	REST API port (0 to disable)	8080
### safeMode(boolean)	Extra safety checks	true

## 🛠️ Advanced Usage

### Custom Circuit Breaker Settings

```java
// Access the circuit breaker
EZVault.circuitBreaker()
    .setFailureThreshold(3)
    .setRetryTime(30, TimeUnit.SECONDS);
```

### Manual Backup

```java
EZVault.backupBalances(); // Trigger immediate backup
```
