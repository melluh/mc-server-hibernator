package com.melluh.serverhibernator.velocity;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Settings {

    private static final String FILE_NAME = "config.json";

    private String startDirectory, startCommand;
    private long maxStartupTime, maxHeartbeatInterval, crashCooldownTime;
    private int httpServerPort;
    private String targetServerName, limboServerName;

    public boolean load(ServerHibernatorVelocity plugin, Path directory) {
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }

            File file = new File(directory.toFile(), FILE_NAME);
            if (!file.exists()) {
                plugin.getLogger().error("{} is missing", FILE_NAME);
                return false;
            }

            String str = Files.readString(file.toPath());
            JsonObject json = JsonParser.object().from(str);

            this.startDirectory = json.getString("start_directory");
            this.startCommand = json.getString("start_command");
            this.maxStartupTime = json.getInt("max_startup_time") * 1000L;
            this.maxHeartbeatInterval = json.getInt("max_heartbeat_interval") * 1000L;
            this.crashCooldownTime = json.getInt("crash_cooldown_time") * 1000L;
            this.httpServerPort = json.getInt("http_server_port");
            this.targetServerName = json.getString("target_server_name");
            this.limboServerName = json.getString("limbo_server_name");

            return true;
        } catch (IOException ex) {
            plugin.getLogger().error("Failed to read " + FILE_NAME, ex);
            return false;
        } catch (JsonParserException ex) {
            plugin.getLogger().error(FILE_NAME + " has malformed JSON.", ex);
            return false;
        }
    }

    public String getStartDirectory() {
        return startDirectory;
    }

    public String getStartCommand() {
        return startCommand;
    }

    public long getMaxStartupTime() {
        return maxStartupTime;
    }

    public long getMaxHeartbeatInterval() {
        return maxHeartbeatInterval;
    }

    public long getCrashCooldownTime() {
        return crashCooldownTime;
    }

    public int getHttpServerPort() {
        return httpServerPort;
    }

    public String getTargetServerName() {
        return targetServerName;
    }

    public String getLimboServerName() {
        return limboServerName;
    }

}
