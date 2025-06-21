package dev;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import org.bstats.bukkit.Metrics;

public final class EZVault {

    private static JavaPlugin plugin;
    private static Logger logger;

    private static final Map<String, EconomyWrapper> economyProviders = new ConcurrentHashMap<>();
    private static volatile String activeProviderName = null;

    private static File backupDir;
    private static final int MAX_BACKUPS = 10;

    private static ScheduledExecutorService scheduler;
    private static ExecutorService asyncExecutor;

    private static Metrics metrics;

    private static final PublishSubject<VaultEvent> eventSubject = PublishSubject.create();

    private static final RateLimiter rateLimiter = new RateLimiter(100, 1, TimeUnit.SECONDS); // 100 req/s

    private static HttpServer httpServer;

    private static final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 10000);


    public interface VaultEvent {}
    public static class VaultConnectedEvent implements VaultEvent {}
    public static class VaultDisconnectedEvent implements VaultEvent {}
    public static class VaultConnectionFailedEvent implements VaultEvent {
        public final Throwable cause;
        public VaultConnectionFailedEvent(Throwable t) { cause = t; }
    }
    public static class VaultBalanceChangedEvent implements VaultEvent {
        public final Player player;
        public final double oldBalance, newBalance;
        public VaultBalanceChangedEvent(Player p, double oldB, double newB) {
            player = p; oldBalance = oldB; newBalance = newB;
        }
    }


    private static class EconomyWrapper {
        final Economy economy;
        final int priority;
        volatile int activePlayers = 0;
        EconomyWrapper(Economy eco, int priority) {
            this.economy = eco; this.priority = priority;
        }
    }

    public static class EZVaultBuilder {
        private int maxRetries = 5;
        private long retryDelayMillis = 5000;
        private boolean safeMode = true;
        private int backupIntervalSeconds = 300;
        private int httpPort = 8080;

        private final Map<String, Integer> providerPriorities = new HashMap<>();

        public EZVaultBuilder providerPriority(String pluginName, int priority) {
            providerPriorities.put(pluginName.toLowerCase(), priority);
            return this;
        }

        public EZVaultBuilder maxRetries(int r) { maxRetries = r; return this; }
        public EZVaultBuilder retryDelayMillis(long d) { retryDelayMillis = d; return this; }
        public EZVaultBuilder safeMode(boolean b) { safeMode = b; return this; }
        public EZVaultBuilder backupIntervalSeconds(int s) { backupIntervalSeconds = s; return this; }
        public EZVaultBuilder httpPort(int p) { httpPort = p; return this; }

        public void connect() {
            safeRun(() -> {
                logger.info("EZVault connecting...");
                discoverProviders();
                startBackupTask(backupIntervalSeconds);
                try {
                    startHttpServer(httpPort);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                eventSubject.onNext(new VaultConnectedEvent());
                activeProviderName = selectActiveProvider();
            });
        }
    }


    private static void discoverProviders() {
        safeRun(() -> {
            economyProviders.clear();
            for (RegisteredServiceProvider<Economy> rsp : Bukkit.getServicesManager().getRegistrations(Economy.class)) {
                Economy eco = rsp.getProvider();
                String pluginName = rsp.getPlugin().getName().toLowerCase();
                int priority = 1;
                priority = EZVaultBuilderHolder.instance.providerPriorities.getOrDefault(pluginName, 1);
                economyProviders.put(pluginName, new EconomyWrapper(eco, priority));
                logger.info("Discovered economy provider: " + pluginName + " with priority " + priority);
            }
            if (economyProviders.isEmpty()) {
                logger.warning("No economy providers found!");
                eventSubject.onNext(new VaultConnectionFailedEvent(new IllegalStateException("No economy providers found")));
            }
        });
    }

    private static String selectActiveProvider() {
        return economyProviders.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue().priority, a.getValue().priority))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }


    public static CompletableFuture<Boolean> depositAsync(Player player, double amount) {
        return runAsyncWithRetry(() -> {
            EconomyWrapper ecoWrap = economyProviders.get(activeProviderName);
            if (ecoWrap == null) throw new IllegalStateException("No active economy provider");
            double oldBalance = ecoWrap.economy.getBalance(player);
            boolean success = ecoWrap.economy.depositPlayer(player, amount).transactionSuccess();
            if (success) {
                eventSubject.onNext(new VaultBalanceChangedEvent(player, oldBalance, oldBalance + amount));
            }
            return success;
        }, 3);
    }

    public static CompletableFuture<Boolean> withdrawAsync(Player player, double amount) {
        return runAsyncWithRetry(() -> {
            EconomyWrapper ecoWrap = economyProviders.get(activeProviderName);
            if (ecoWrap == null) throw new IllegalStateException("No active economy provider");
            double oldBalance = ecoWrap.economy.getBalance(player);
            boolean success = ecoWrap.economy.withdrawPlayer(player, amount).transactionSuccess();
            if (success) {
                eventSubject.onNext(new VaultBalanceChangedEvent(player, oldBalance, oldBalance - amount));
            }
            return success;
        }, 3);
    }

    private static <T> CompletableFuture<T> runAsyncWithRetry(Callable<T> callable, int maxRetries) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runAsyncAttempt(callable, maxRetries, future);
        return future;
    }

    private static <T> void runAsyncAttempt(Callable<T> callable, int retriesLeft, CompletableFuture<T> future) {
        if (!rateLimiter.tryAcquire()) {
            future.completeExceptionally(new IllegalStateException("Rate limit exceeded"));
            return;
        }
        if (circuitBreaker.isOpen()) {
            future.completeExceptionally(new IllegalStateException("Circuit breaker is open"));
            return;
        }
        asyncExecutor.submit(() -> {
            try {
                T result = callable.call();
                future.complete(result);
                circuitBreaker.recordSuccess();
            } catch (Throwable t) {
                circuitBreaker.recordFailure();
                if (retriesLeft > 0) {
                    logger.warning("Async operation failed, retries left: " + retriesLeft + ", error: " + t.getMessage());
                    scheduler.schedule(() -> runAsyncAttempt(callable, retriesLeft -1, future), 2, TimeUnit.SECONDS);
                } else {
                    future.completeExceptionally(t);
                }
            }
        });
    }


    private static void startBackupTask(int intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            safeRun(() -> {
                logger.info("Starting economy backup...");
                backupBalances();
            });
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static void backupBalances() {
        try {
            if (!backupDir.exists()) backupDir.mkdirs();
            String fileName = "backup_" + System.currentTimeMillis() + ".yml";
            File backupFile = new File(backupDir, fileName);
            YamlConfiguration config = new YamlConfiguration();
            for (Player p : Bukkit.getOnlinePlayers()) {
                config.set(p.getUniqueId().toString(), balance(p));
            }
            config.save(backupFile);
            cleanupBackups();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Backup failed", e);
        }
    }

    private static void cleanupBackups() {
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_BACKUPS; i < files.length; i++) {
            files[i].delete();
        }
    }

    private static void startHttpServer(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/balance", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String playerName = params.get("player");
            if (playerName == null) {
                exchange.sendResponseHeaders(400, 0);
                exchange.getResponseBody().write("Missing player param".getBytes());
                exchange.close();
                return;
            }
            Player p = Bukkit.getPlayer(playerName);
            if (p == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().write("Player not online".getBytes());
                exchange.close();
                return;
            }
            double bal = balance(p);
            byte[] response = ("Balance:" + bal).getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        logger.info("EZVault HTTP server started on port " + port);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String,String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }


    public static double balance(Player p) {
        EconomyWrapper ecoWrap = economyProviders.get(activeProviderName);
        if (ecoWrap == null) return 0;
        try {
            return ecoWrap.economy.getBalance(p);
        } catch (Exception e) {
            logger.warning("Balance fetch error: " + e.getMessage());
            return 0;
        }
    }


    static {
        plugin = JavaPlugin.getProvidingPlugin(EZVault.class);
        logger = plugin.getLogger();
        scheduler = Executors.newScheduledThreadPool(2);
        asyncExecutor = Executors.newCachedThreadPool();
        backupDir = new File(plugin.getDataFolder(), "backups");
        EZVaultBuilderHolder.instance = new EZVaultBuilder();
    }

    private static class EZVaultBuilderHolder {
        private static EZVaultBuilder instance;
    }

    public static EZVaultBuilder builder(JavaPlugin plug) {
        plugin = plug;
        logger = plugin.getLogger();
        return EZVaultBuilderHolder.instance;
    }


    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "SafeRun error: " + t.getMessage(), t);
        }
    }


    public static Observable<VaultEvent> events() {
        return eventSubject.hide();
    }


    public static void shutdown() {
        safeRun(() -> {
            if (httpServer != null) httpServer.stop(0);
            scheduler.shutdownNow();
            asyncExecutor.shutdownNow();
            eventSubject.onComplete();
            economyProviders.clear();
        });
    }


    private static class CircuitBreaker {
        private final int failureThreshold;
        private final long retryTimeMillis;
        private int failureCount = 0;
        private long lastFailureTime = 0;
        private boolean open = false;

        CircuitBreaker(int failureThreshold, long retryTimeMillis) {
            this.failureThreshold = failureThreshold;
            this.retryTimeMillis = retryTimeMillis;
        }

        synchronized void recordFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            if (failureCount >= failureThreshold) {
                open = true;
                logger.warning("Circuit breaker OPENED due to failures");
            }
        }

        synchronized void recordSuccess() {
            failureCount = 0;
            open = false;
        }

        synchronized boolean isOpen() {
            if (open && (System.currentTimeMillis() - lastFailureTime) > retryTimeMillis) {
                open = false;
                failureCount = 0;
                logger.info("Circuit breaker CLOSED after timeout");
            }
            return open;
        }
    }


    private static class RateLimiter {
        private final int maxRequests;
        private final long periodMillis;
        private int requestCount;
        private long periodStart;

        RateLimiter(int maxRequests, long period, TimeUnit unit) {
            this.maxRequests = maxRequests;
            this.periodMillis = unit.toMillis(period);
            this.requestCount = 0;
            this.periodStart = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - periodStart > periodMillis) {
                periodStart = now;
                requestCount = 0;
            }
            if (requestCount < maxRequests) {
                requestCount++;
                return true;
            }
            return false;
        }
    }
}
