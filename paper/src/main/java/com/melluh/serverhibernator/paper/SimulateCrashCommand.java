package com.melluh.serverhibernator.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public record SimulateCrashCommand(ServerHibernatorPaper plugin) implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        plugin.simulateCrash();
        sender.sendMessage(Component.text("Simulating crash!").color(NamedTextColor.RED));
        return true;
    }

}
