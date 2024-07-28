package arcademadness.autotransfer;

import net.md_5.bungee.api.Favicon;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AutoMOTD implements Listener {

    private final ServerInfo defaultServer;
    private final File cacheFile;
    private final ExecutorService executorService;
    private final Logger logger;

    private String cachedDescription = null;
    private Favicon cachedFavicon = null;
    private boolean newDataAvailable = false;
    private long lastSaveTime = 0;

    public AutoMOTD(ServerInfo defaultS, File pluginFolder, Logger mylogger) {
        this.defaultServer = defaultS;
        this.logger = mylogger;

        cacheFile = new File(pluginFolder, "serverping_cache.dat");
        if (!cacheFile.exists()) {
            try {
                cacheFile.createNewFile();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create cache file", e);
            }
        }

        executorService = Executors.newSingleThreadExecutor();
        loadPingData();
    }

    @EventHandler
    public void onProxyPing(ProxyPingEvent event) {
        final ServerPing[] resultHolder = new ServerPing[1];
        CountDownLatch latch = new CountDownLatch(1);

        int playerCount = ProxyServer.getInstance().getOnlineCount();

        ServerPing eventResponse = event.getResponse();
        eventResponse.setPlayers(new ServerPing.Players(playerCount + 1, playerCount, new ServerPing.PlayerInfo[0]));

        defaultServer.ping((result, error) -> {
            if (error == null) {
                eventResponse.setDescriptionComponent(result.getDescriptionComponent());
                eventResponse.setFavicon(result.getFaviconObject());

                String newDescription = result.getDescriptionComponent().toLegacyText();
                Favicon newFavicon = result.getFaviconObject();

                if (!newDescription.equals(cachedDescription) ||
                        (cachedFavicon == null && newFavicon != null) ||
                        (cachedFavicon != null && !cachedFavicon.equals(newFavicon))) {

                    executorService.submit(() -> savePingData(result));
                }

            } else {
                if (newDataAvailable) {
                    loadPingData();
                }
                if (cachedDescription != null) {
                    eventResponse.setDescriptionComponent(new TextComponent(cachedDescription));
                }
                if (cachedFavicon != null) {
                    eventResponse.setFavicon(cachedFavicon);
                }
            }
            resultHolder[0] = eventResponse;
            latch.countDown();
        });

        try {
            latch.await();
            ServerPing result = resultHolder[0];
            if (result != null) {
                event.setResponse(result);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void savePingData(ServerPing result) {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastSaveTime < 30000) {
            return;
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(cacheFile.toPath()))) {
            oos.writeObject(result.getDescriptionComponent().toLegacyText());

            Favicon favicon = result.getFaviconObject();
            if (favicon != null) {
                oos.writeObject(favicon.getEncoded());
            } else {
                oos.writeObject(null);
            }

            cachedDescription = result.getDescriptionComponent().toLegacyText();
            cachedFavicon = result.getFaviconObject();
            newDataAvailable = true;

            lastSaveTime = System.currentTimeMillis();

            logger.info("Ping data saved asynchronously.");

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save ping data", e);
        }
    }

    private void loadPingData() {
        if (!cacheFile.exists() || cacheFile.length() == 0) {
            logger.warning("Cache file does not exist or is empty. Using default values.");
            return;
        }

        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(cacheFile.toPath()))) {
            cachedDescription = (String) ois.readObject();
            String faviconBase64 = (String) ois.readObject();
            if (faviconBase64 != null) {
                cachedFavicon = Favicon.create(faviconBase64);
            } else {
                cachedFavicon = null;
            }

            newDataAvailable = false;

            logger.info("Ping data loaded from cache.");

        } catch (IOException | ClassNotFoundException e) {
            logger.log(Level.SEVERE, "Failed to load ping data", e);
        }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}
