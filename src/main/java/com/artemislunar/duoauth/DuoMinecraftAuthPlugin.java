package com.artemislunar.duoauth;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

@Plugin(
        id = "duominecraftauth",
        name = "DuoMinecraftAuth",
        version = "1.0.0",
        authors = {"Artemis_Lunar"}
)
public final class DuoMinecraftAuthPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private volatile PluginConfig config;
    private volatile DuoAuthClient duoAuthClient;

    @Inject
    public DuoMinecraftAuthPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        reloadConfig();
        registerCommand();
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        PluginConfig currentConfig = this.config;
        if (currentConfig == null) {
            return null;
        }

        String username = event.getUsername();
        if (!currentConfig.requiresDuo(username)) {
            return null;
        }

        return EventTask.async(() -> handleProtectedLogin(event, username, currentConfig));
    }

    public synchronized void reloadConfig() {
        try {
            this.config = PluginConfig.load(dataDirectory);
            this.duoAuthClient = new DuoAuthClient(this.config);
            logger.info(
                    "Loaded DuoMinecraftAuth config with {} manual protected player(s) and {} protected op(s).",
                    this.config.protectedPlayers().size(),
                    this.config.protectedOps().size()
            );
        } catch (IOException exception) {
            throw new RuntimeException("Failed to load DuoMinecraftAuth config", exception);
        }
    }

    private void handleProtectedLogin(PreLoginEvent event, String username, PluginConfig currentConfig) {
        DuoAuthClient client = Objects.requireNonNull(this.duoAuthClient, "duoAuthClient");
        String duoUsername = currentConfig.resolveDuoUsername(username);
        String ipAddress = NetworkUtil.extractIp(event);

        try {
            DuoDecision decision = client.verify(duoUsername, ipAddress);
            if (decision.allowed()) {
                logger.info("Duo verification succeeded for {} (Duo user {}).", username, duoUsername);
                return;
            }

            logger.warn("Duo verification denied for {} (Duo user {}): {}", username, duoUsername, decision.message());
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(formatMessage(currentConfig, decision.message())));
        } catch (Exception exception) {
            logger.error("Duo verification error for {} (Duo user {}).", username, duoUsername, exception);
            if (currentConfig.failOpen()) {
                logger.warn("Allowing {} through because duo.failOpen=true.", username);
                return;
            }
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    formatMessage(currentConfig, currentConfig.errorMessage())
            ));
        }
    }

    private Component formatMessage(PluginConfig currentConfig, String body) {
        return Component.text(currentConfig.prefix() + " " + body);
    }

    private void registerCommand() {
        CommandManager manager = server.getCommandManager();
        CommandMeta meta = manager.metaBuilder("duoreload").build();
        manager.register(meta, new DuoReloadCommand(this));
    }

    PluginConfig config() {
        return config;
    }
}
