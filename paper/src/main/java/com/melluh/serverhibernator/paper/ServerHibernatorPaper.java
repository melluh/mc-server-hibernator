package com.melluh.serverhibernator.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.logging.Level;

public class ServerHibernatorPaper extends JavaPlugin implements Listener {

    private final HttpClient httpServer = HttpClient.newHttpClient();
    private long lastPlayerQuitTime;

    @Override
    public void onEnable() {
        if (!(new File(this.getDataFolder(), "config.yml")).exists())
            this.saveDefaultConfig();

        int heartbeatInterval = this.getConfig().getInt("heartbeatInterval", 10);

        BukkitScheduler scheduler = this.getServer().getScheduler();
        scheduler.runTaskTimerAsynchronously(this, () -> this.callEndpoint("/heartbeat"), 0, heartbeatInterval * 20L);
        scheduler.runTaskTimer(this, this::checkEmptyTimeout, 100, 100);
        scheduler.runTaskLater(this, () -> this.lastPlayerQuitTime = System.currentTimeMillis(), 1L);

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        this.callEndpoint("/shutdown");
    }

    private void checkEmptyTimeout() {
        if (Bukkit.getOnlinePlayers().isEmpty() && System.currentTimeMillis() - lastPlayerQuitTime > this.getConfig().getInt("emptyTimeShutdown") * 1000L) {
            this.getLogger().info("Server has been empty for too long! Shutting down...");
            Bukkit.shutdown();
        }
    }

    private void callEndpoint(String path) {
        URI uri = URI.create(this.getConfig().getString("host") + path);
        HttpRequest request = HttpRequest.newBuilder(uri).build();

        try {
            HttpResponse<Void> response = httpServer.send(request, BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                this.getLogger().warning("Unexpected status code from proxy endpoint " + path + ": " + response.statusCode());
            }
        } catch (IOException | InterruptedException ex) {
            this.getLogger().log(Level.SEVERE, "Failed to call proxy endpoint " + path, ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.lastPlayerQuitTime = System.currentTimeMillis();
    }

}
