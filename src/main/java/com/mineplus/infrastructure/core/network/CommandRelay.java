package com.mineplus.infrastructure.core.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Cross-server command relay (mega-plan D4 / Phase D): sends administrative
 * commands from one backend to another over the {@link MessageBus}
 * (channel {@link #CHANNEL}), with per-command permission checking at the
 * receiving side and result reporting back to the sender.
 *
 * <p>Commands are JSON-ish key/value lines by contract; the relay keeps
 * them opaque except for the {@code name} key it routes on. Permission
 * checks are pluggable so the Bukkit adapter maps them to its
 * permission system without this class depending on host types.
 */
public final class CommandRelay {

    public static final String CHANNEL = "mineplus:command";
    public static final String RESULT_CHANNEL = "mineplus:command-result";

    /** Result of a remote execution. */
    public record Result(String command, String sourceServer, boolean ok, String output) {
    }

    /** Permission hook: name of the command plus the relay's server id. */
    public interface PermissionChecker {
        boolean allowed(String serverId, String commandName);
    }

    private final MessageBus bus;
    private final Map<java.util.UUID, java.util.function.Consumer<Result>> pending = new ConcurrentHashMap<>();
    private final PermissionChecker permissions;

    public CommandRelay(MessageBus bus, PermissionChecker permissions) {
        this.bus = bus;
        this.permissions = permissions;
        bus.on(CHANNEL, this::onCommand);
        bus.on(RESULT_CHANNEL, this::onResult);
    }

    /**
     * Sends a command line (e.g. {@code "name=kick target=Bob"}) to the
     * target server. Results arrive through the returned handle's callback.
     */
    public void send(String targetServer, String commandLine, java.util.function.Consumer<Result> callback) {
        MessageBus.Message message = MessageBus.Message.of(CHANNEL, bus.serverId(), targetServer, commandLine);
        if (callback != null) {
            pending.put(message.id(), callback);
        }
        bus.send(CHANNEL, targetServer, commandLine);
    }

    private void onCommand(MessageBus.Message message) {
        String commandName = extractName(message.payload());
        if (commandName == null) {
            replyResult(message, false, "malformed command (no name=)");
            return;
        }
        if (permissions != null && !permissions.allowed(message.targetServer(), commandName)) {
            replyResult(message, false, "permission denied: " + commandName);
            return;
        }
        // Actual execution is the adapter's job: it registers a local
        // handler per command name. Missing handler = not deployed here.
        replyResult(message, false, "no local handler for: " + commandName);
    }

    /** Adapters register the actual executors for command names here. */
    public void registerHandler(String commandName, Consumer<String> executor) {
        bus.on(CHANNEL, message -> {
            String name = extractName(message.payload());
            if (commandName.equals(name) && (permissions == null
                    || permissions.allowed(message.targetServer(), commandName))) {
                executor.accept(message.payload());
                replyResult(message, true, "ok");
            }
        });
    }

    private void onResult(MessageBus.Message message) {
        // Results are carried as "name=<cmd> ok=<true|false> out=<text>".
        String name = extractName(message.payload());
        boolean ok = message.payload().contains("ok=true");
        String out = message.payload();
        pending.values().forEach(callback -> callback.accept(
                new Result(name == null ? "?" : name, message.sourceServer(), ok, out)));
        pending.clear();
    }

    private void replyResult(MessageBus.Message original, boolean ok, String output) {
        String commandName = extractName(original.payload());
        bus.send(RESULT_CHANNEL, original.sourceServer(),
                "name=" + (commandName == null ? "?" : commandName)
                        + " ok=" + ok + " out=" + output);
    }

    private static String extractName(String payload) {
        for (String token : payload.split(" ")) {
            if (token.startsWith("name=")) {
                String value = token.substring(5).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
