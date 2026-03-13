package com.artemislunar.duoauth;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;

public final class DuoReloadCommand implements SimpleCommand {

    private final DuoMinecraftAuthPlugin plugin;

    public DuoReloadCommand(DuoMinecraftAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) {
            invocation.source().sendMessage(Component.text("You do not have permission to use this command."));
            return;
        }

        try {
            plugin.reloadConfig();
            invocation.source().sendMessage(Component.text("DuoMinecraftAuth config reloaded."));
        } catch (RuntimeException exception) {
            invocation.source().sendMessage(Component.text("Failed to reload DuoMinecraftAuth config: " + exception.getMessage()));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("duominecraftauth.reload");
    }
}

