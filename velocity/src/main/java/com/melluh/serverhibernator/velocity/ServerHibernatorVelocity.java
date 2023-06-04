package com.melluh.serverhibernator.velocity;

import com.google.inject.Inject;
import com.melluh.simplehttpserver.HttpServer;
import com.melluh.simplehttpserver.Request;
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
    private boolean possibleIssues = false;

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
                            .get("/heartbeat", this::handleHeartbeat)
                            .get("/shutdown", this::handleShutdown))
                    .parseCookies(false)
                    .start();
            logger.info("HTTP server listening on port " + settings.getHttpServerPort());
        } catch (IOException ex) {
            logger.error("Failed to start HTTP server on port " + settings.getHttpServerPort(), ex);
        }
    }

    private Response handleHeartbeat(Request request) {
        this.lastHeartbeatTime = System.currentTimeMillis();
        if (serverState != ServerState.UP) {
            this.handleServerUp();
        }

        return new Response(Status.OK);
    }

    private Response handleShutdown(Request request) {
        logger.info("Paper server reported shutdown!");
        this.setServerState(ServerState.DOWN);

        return new Response(Status.OK);
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
        this.possibleIssues = false;

        if (limboServer != null && targetServer != null) {
            limboServer.getPlayersConnected().forEach(player -> {
                player.sendMessage(Component.text("Server is ready! Redirecting you...").color(NamedTextColor.GREEN));
                player.createConnectionRequest(targetServer).fireAndForget();
            });
        }
    }

    private void setServerState(ServerState serverState) {
        logger.info("State changed! " + this.serverState + " -> " + serverState);
        this.serverState = serverState;
    }

    private void checkTimeout() {
        if (serverState == ServerState.STARTING) {
            if (System.currentTimeMillis() - serverStartTime > settings.getMaxStartupTime()) {
                logger.info("Paper server did not come up within required timeframe");
                this.possibleIssues = true;
                this.setServerState(ServerState.LOCKOUT);

                if (limboServer != null) {
                    Collection<Player> waitingPlayers = limboServer.getPlayersConnected();
                    waitingPlayers.forEach(player -> player.sendMessage(Component.text("The server did not come online within the expected timeframe! Please contact an admin to resolve this issue.").color(NamedTextColor.RED)));
                }
            }
        }

        if (serverState == ServerState.UP) {
            if (System.currentTimeMillis() - lastHeartbeatTime > settings.getMaxHeartbeatInterval()) {
                logger.info("Paper server timed out!");
                this.setServerState(ServerState.CRASHED);
                this.serverCrashTime = System.currentTimeMillis();

                if (limboServer != null) {
                    Collection<Player> waitingPlayers = limboServer.getPlayersConnected();
                    waitingPlayers.forEach(player -> player.sendMessage(Component.text("It looks like the server crashed! Please wait, it will come back online in a moment.").color(NamedTextColor.RED)));
                }
            }
        }

        if (serverState == ServerState.CRASHED) {
            if (System.currentTimeMillis() - serverCrashTime > settings.getCrashCooldownTime()) {
                logger.info("Crash cooldown time elapsed");
                this.setServerState(ServerState.DOWN);

                if (limboServer != null) {
                    Collection<Player> waitingPlayers = limboServer.getPlayersConnected();
                    if (!waitingPlayers.isEmpty()) {
                        this.startServer();
                        waitingPlayers.forEach(player -> player.sendMessage(Component.text("The server is starting, please wait...").color(NamedTextColor.YELLOW)));
                    }
                }
            }
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getScheduler()
                .buildTask(this, this::checkTimeout)
                .repeat(Duration.ofSeconds(1))
                .schedule();

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
    }

    @Subscribe
    public void onPlayerServerPreConnect(ServerPreConnectEvent event) {
        if (event.getPreviousServer() != null)
            return; // This is not the initial connection to the proxy

        if (serverState == ServerState.STARTING) {
            Component component = Component.text("The server is starting! Please wait...").color(NamedTextColor.YELLOW);
            if (possibleIssues)
                component = component.append(Component.text("\nThe server may be having issues. Contact an admin if you continue to be unable to join.").color(NamedTextColor.RED));

            if (limboServer == null) {
                event.getPlayer().disconnect(component);
            } else {
                event.getPlayer().sendMessage(component);
                event.setResult(ServerResult.allowed(limboServer));
            }

            return;
        }

        if (serverState == ServerState.DOWN) {
            Component component = Component.text("Starting the server for you, please wait...").color(NamedTextColor.YELLOW);
            if (possibleIssues)
                component = component.append(Component.text("\nThe server may be having issues. Contact an admin if you continue to be unable to join.").color(NamedTextColor.RED));

            if (limboServer == null) {
                event.getPlayer().disconnect(component);
            } else {
                event.getPlayer().sendMessage(component);
                event.setResult(ServerResult.allowed(limboServer));
            }

            this.startServer();
            return;
        }

        if (serverState == ServerState.CRASHED) {
            Component component = Component.text("It looks like the server crashed! Please wait, it will come back online in a moment.").color(NamedTextColor.RED);
            if (limboServer == null) {
                event.getPlayer().disconnect(component);
            } else {
                event.getPlayer().sendMessage(component);
                event.setResult(ServerResult.allowed(limboServer));
            }
            return;
        }

        if (serverState == ServerState.LOCKOUT) {
            Component component = Component.text("The server is having issues! Please contact an admin for assistance.").color(NamedTextColor.RED);
            if (limboServer == null) {
                event.getPlayer().disconnect(component);
            } else {
                event.getPlayer().sendMessage(component);
                event.setResult(ServerResult.allowed(limboServer));
            }
        }
    }

    public Logger getLogger() {
        return logger;
    }

}
