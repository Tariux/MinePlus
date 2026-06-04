package com.mineplus.infrastructure.command;

import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;

public interface SubCommand {

    String name();

    default List<String> aliases() {
        return Collections.emptyList();
    }

    String description();

    String usage();

    default String permission() {
        return "";
    }

    boolean execute(CommandSender sender, String label, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
