package arcademadness.autotransfer;

import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public class AutoMoveFromCrash implements Listener {

    private final ServerInfo fallbackServer;
    private final Logger logger;

    // Message that signifies the player was kicked due to server failure.
    private static final String SERVER_DOWN_MESSAGE = "The server you were previously on went down, you have been connected to a fallback server";

    public AutoMoveFromCrash(ServerInfo fallbackServer, Logger logger) {
        this.fallbackServer = fallbackServer;
        this.logger = logger;
    }

    @EventHandler
    public void onServerKick(ServerKickEvent event) {
        ProxiedPlayer player = event.getPlayer();
        ServerInfo kickedFrom = event.getKickedFrom();

        // Check if the kick reason is the specific server down message
        String kickReason = event.getReason().toLegacyText();
        if (!kickReason.contains(SERVER_DOWN_MESSAGE)) {
            return;
        }

        if (fallbackServer == null) {
            logger.warning("No fallback server configured.");
            return;
        }

        event.setCancelled(true);

        checkAndMovePlayerToFallback(player, event);
    }

    private void checkAndMovePlayerToFallback(ProxiedPlayer player, ServerKickEvent event) {
        final CountDownLatch latch = new CountDownLatch(1);

        fallbackServer.ping(new Callback<ServerPing>() {
            @Override
            public void done(ServerPing result, Throwable error) {
                if (error == null) {
                    logger.info("Fallback server is online. Moving player " + player.getName() + " to " + fallbackServer.getName());
                    player.connect(fallbackServer, (success, cause) -> {
                        if (!success) {
                            logger.warning("Failed to move player " + player.getName() + " to fallback server. Disconnecting the player.");
                            player.disconnect(event.getReason());
                        } else {
                            logger.info("Player " + player.getName() + " successfully moved to fallback server.");
                        }
                    });
                } else {
                    logger.warning("Fallback server is offline. Disconnecting player " + player.getName() + ".");
                    player.disconnect(event.getReason());
                }
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.log(java.util.logging.Level.SEVERE, "Error while awaiting server ping: ", e);
            player.disconnect(event.getReason());
        }
    }
}
