package arcademadness.autotransfer;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class AutoTransfer extends Plugin implements Listener {

    private ServerInfo defaultServer;
    private ServerInfo fallbackServer;
    private ScheduledTask pingTask;
    private boolean isPinging;

    @Override
    public void onEnable() {
        setServers();
        getProxy().getPluginManager().registerListener(this, this);

        if (defaultServer == null || fallbackServer == null) {
            getLogger().warning("Default or fallback server not set correctly!");
        }
    }

    private void setServers() {
        List<String> priorities = ProxyServer.getInstance().getConfig().getListeners().iterator().next().getServerPriority();

        if (!priorities.isEmpty()) {
            defaultServer = getServerByName(priorities.get(0));
            getLogger().info("Default server set to: " + defaultServer.getName());
        }

        if (priorities.size() > 1) {
            fallbackServer = getServerByName(priorities.get(1));
            getLogger().info("Fallback server set to: " + fallbackServer.getName());
        }
    }

    private ServerInfo getServerByName(String name) {
        return ProxyServer.getInstance().getServers().get(name);
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        Server server = event.getServer();

        getLogger().info(player.getServer().getInfo().getName());

        if (server.getInfo().equals(fallbackServer)) {
            getLogger().info("Player " + player.getName() + " is connecting to the fallback server. Starting to ping the default server...");
            startPingingDefaultServer();
        }
    }

    private void startPingingDefaultServer() {
        // Avoid starting multiple ping tasks
        if (!isPinging) {
            isPinging = true;
            pingTask = ProxyServer.getInstance().getScheduler().schedule(this, this::pingDefaultServer, 0, 10, TimeUnit.SECONDS);
            getLogger().info("Started pinging the default server.");
        }
    }

    private void pingDefaultServer() {
        defaultServer.ping((result, error) -> {
            if (error == null) {
                getLogger().info("Default server is online, transferring players...");
                transferPlayersToDefaultServer();
            } else {
                getLogger().info("Default server is still offline: " + error.getMessage());
            }
        });
    }

    private void transferPlayersToDefaultServer() {
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player.getServer().getInfo().equals(fallbackServer)) {
                player.connect(defaultServer, new Callback<Boolean>() {
                    @Override
                    public void done(Boolean success, Throwable error) {
                        if (success) {
                            player.sendMessage(ChatColor.GREEN + "You have been transferred to the default server!");
                            getLogger().info("Player " + player.getName() + " successfully transferred to the default server.");
                        } else {
                            if (error != null) {
                                getLogger().warning("Error transferring player: " + error.getMessage());
                            }
                        }
                    }
                });
            }
        }

        if (areAllPlayersTransferred()) {
            stopPinging();
        }
    }

    private boolean areAllPlayersTransferred() {
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player.getServer().getInfo().equals(fallbackServer)) {
                return false;
            }
        }
        return true;
    }

    private void stopPinging() {
        if (pingTask != null) {
            pingTask.cancel();
            pingTask = null;
            isPinging = false;
            getLogger().info("All players transferred, stopping pinging.");
        }
    }
}
