package com.melluh.serverhibernator.velocity;

import com.google.inject.Inject;
import com.melluh.simplehttpserver.HttpServer;
import com.melluh.simplehttpserver.protocol.Status;
import com.melluh.simplehttpserver.response.Response;
import com.melluh.simplehttpserver.router.Router;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;

@Plugin(id = "serverhibernator", name = "ServerHibernator", version = "1.0.0-SNAPSHOT", authors = "Melluh")
public class ServerHibernatorVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Settings settings;

    private RegisteredServer targetServer, limboServer;

    private ServerState serverState = ServerState.DOWN;
    private long serverStartTime, lastHeartbeatTime, serverCrashTime;

    @Inject
    public ServerHibernatorVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;

        this.settings = new Settings();
        if (!settings.load(this, dataDirectory)) {
            this.getLogger().error("Initialization failed: could not read config");
            return;
        }

        try {
            new HttpServer(settings.getHttpServerPort())
                    .use(new Router()
                            .get("/heartbeat", request -> {
                                this.lastHeartbeatTime = System.currentTimeMillis();
                                if (serverState != ServerState.UP) {
                                    this.handleServerUp();
                                }
                                return new Response(Status.OK);
                            })
                            .get("/shutdown", request -> {
                                logger.info("Paper server reported shutdown!");
                                this.setServerState(ServerState.DOWN);
                                return new Response(Status.OK);
                            }))
                    .parseCookies(false)
                    .start();
            logger.info("HTTP server listening on port " + settings.getHttpServerPort());
        } catch (IOException ex) {
            logger.error("Failed to start HTTP server on port " + settings.getHttpServerPort(), ex);
        }
    }

    private void startServer() {
        this.serverStartTime = System.currentTimeMillis();
        this.setServerState(ServerState.STARTING);

        try {
            new ProcessBuilder(settings.getStartCommand().split(" "))
                    .directory(new File(settings.getStartDirectory()))
                    .inheritIO()
                    .start();
        } catch (IOException ex) {
            logger.error("Failed to run start command", ex);
        }
    }

    private void handleServerUp() {
        this.setServerState(ServerState.UP);
        if (limboServer != null && targetServer != null) {
            limboServer.getPlayersConnected().forEach(player -> {
                player.sendMessage(Component.text("Server is ready! Redirecting you...").color(NamedTextColor.GREEN));
                player.createConnectionRequest(targetServer).fireAndForget();
            });
        }
    }

    private void checkTimeout() {
        if (serverState == ServerState.STARTING) {
            if (System.currentTimeMillis() - serverStartTime > settings.getMaxStartupTime()) {
                logger.info("Paper server did not come up within required timeframe");
                this.setServerState(ServerState.LOCKOUT);
                this.notifyLimboPlayers(Component.text("The server did not come online within the expected timeframe! Please contact an admin to resolve this issue.").color(NamedTextColor.RED));
            }
        }

        if (serverState == ServerState.UP) {
            if (System.currentTimeMillis() - lastHeartbeatTime > settings.getMaxHeartbeatInterval()) {
                logger.info("Paper server timed out!");
                this.setServerState(ServerState.CRASHED);
                this.serverCrashTime = System.currentTimeMillis();
                this.notifyLimboPlayers(Component.text("It looks like the server crashed! Please wait, it will come back online in a moment.").color(NamedTextColor.RED));
            }
        }

        if (serverState == ServerState.CRASHED) {
            if (System.currentTimeMillis() - serverCrashTime > settings.getCrashCooldownTime()) {
                logger.info("Crash cooldown time elapsed");
                this.setServerState(ServerState.DOWN);

                boolean limboHasPlayers = this.notifyLimboPlayers(Component.text("The server is starting, please wait...").color(NamedTextColor.YELLOW));
                if (limboHasPlayers) {
                    this.startServer();
                }
            }
        }
    }

    private void setServerState(ServerState serverState) {
        logger.info("State changed! " + this.serverState + " -> " + serverState);
        this.serverState = serverState;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String targetServerName = settings.getTargetServerName();
        if (targetServerName != null && !targetServerName.isEmpty()) {
            this.targetServer = server.getServer(targetServerName)
                    .orElseThrow(() -> new IllegalArgumentException("Target server '" + targetServerName + "' not defined"));
        }

        String limboServerName = settings.getLimboServerName();
        if (limboServerName != null && !limboServerName.isEmpty()) {
            this.limboServer = server.getServer(limboServerName)
                    .orElseThrow(() -> new IllegalArgumentException("Limbo server '" + limboServerName + "' not defined"));
        }

        server.getScheduler()
                .buildTask(this, this::checkTimeout)
                .repeat(Duration.ofSeconds(1))
                .schedule();
    }

    @Subscribe
    public void onPlayerServerPreConnect(ServerPreConnectEvent event) {
        // Only run on initial connection to the proxy
        if (event.getPreviousServer() != null)
            return;

        switch (serverState) {
            case DOWN -> {
                this.disconnectOrSendToLimbo(event, Component.text("Starting the server for you, please wait...").color(NamedTextColor.YELLOW));
                this.startServer();
            }
            case STARTING -> this.disconnectOrSendToLimbo(event, Component.text("The server is starting! Please wait...").color(NamedTextColor.YELLOW));
            case CRASHED -> this.disconnectOrSendToLimbo(event, Component.text("It looks like the server crashed! Please wait, it will come back online in a moment.").color(NamedTextColor.RED));
            case LOCKOUT -> this.disconnectOrSendToLimbo(event, Component.text("The server is having issues! Please contact an admin for assistance.").color(NamedTextColor.RED));
        }
    }

    private void disconnectOrSendToLimbo(ServerPreConnectEvent event, Component message) {
        if (limboServer != null) {
            event.getPlayer().sendMessage(message);
            event.setResult(ServerResult.allowed(limboServer));
        } else {
            event.getPlayer().disconnect(message);
        }
    }

    private boolean notifyLimboPlayers(Component message) {
        if (limboServer != null) {
            Collection<Player> limboPlayers = limboServer.getPlayersConnected();
            limboPlayers.forEach(player -> player.sendMessage(message));
            return !limboPlayers.isEmpty();
        }
        return false;
    }

    public Logger getLogger() {
        return logger;
    }

    public enum ServerState {
        DOWN, STARTING, UP, CRASHED, LOCKOUT
    }

}
