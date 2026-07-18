package com.tndmadman.rts;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses and executes local dedicated-server administration commands. */
final class ServerCommandDispatcher {
    interface Target {
        String status();
        List<String> players();
        boolean save();
        void stop();
        boolean running();
    }

    private final Target target;
    private final PrintStream output;
    private final PrintStream errors;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = Map.of("?", "help", "shutdown", "stop");

    ServerCommandDispatcher(Target target, PrintStream output, PrintStream errors) {
        if (target == null) throw new IllegalArgumentException("Server console target is required.");
        this.target = target;
        this.output = output == null ? System.out : output;
        this.errors = errors == null ? System.err : errors;
        register("help", "help [command]", "Show available commands or detailed command help.", this::help);
        register("status", "status", "Print server, network, save, and autosave status.", this::status);
        register("players", "players", "List connected and retained player sessions.", this::players);
        register("save", "save", "Write a manual dedicated-server save.", this::save);
        register("version", "version", "Print the running StarChem build identity.", this::version);
        register("stop", "stop", "Save and stop the dedicated server cleanly.", this::stop);
    }

    void execute(String line) {
        List<String> parts;
        try {
            parts = tokenize(line);
        } catch (IllegalArgumentException ex) {
            errors.println("Invalid console command: " + ex.getMessage());
            return;
        }
        if (parts.isEmpty()) return;
        String requested = parts.get(0).toLowerCase(Locale.ROOT);
        String commandName = aliases.getOrDefault(requested, requested);
        Command command = commands.get(commandName);
        if (command == null) {
            errors.println("Unknown console command: " + parts.get(0) + ". Type 'help' for available commands.");
            return;
        }
        try {
            command.handler().run(parts.subList(1, parts.size()));
        } catch (RuntimeException ex) {
            errors.println("Console command failed: " + ex.getMessage());
        }
    }

    static List<String> tokenize(String line) {
        if (line == null || line.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaping) {
                token.append(c);
                escaping = false;
                started = true;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                started = true;
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                started = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
                continue;
            }
            token.append(c);
            started = true;
        }
        if (escaping) throw new IllegalArgumentException("command ends with an incomplete escape.");
        if (quote != 0) throw new IllegalArgumentException("command contains an unterminated quote.");
        if (started) tokens.add(token.toString());
        return List.copyOf(tokens);
    }

    private void register(String name, String usage, String description, CommandHandler handler) {
        commands.put(name, new Command(usage, description, handler));
    }

    private void help(List<String> args) {
        if (args.size() > 1) {
            errors.println("Usage: help [command]");
            return;
        }
        if (args.size() == 1) {
            String requested = args.get(0).toLowerCase(Locale.ROOT);
            String commandName = aliases.getOrDefault(requested, requested);
            Command command = commands.get(commandName);
            if (command == null) {
                errors.println("Unknown console command: " + args.get(0) + '.');
                return;
            }
            output.println(command.usage() + " - " + command.description());
            return;
        }
        output.println("Dedicated server commands:");
        for (Command command : commands.values()) output.println("  " + command.usage() + " - " + command.description());
        output.println("  shutdown - Alias for stop.");
    }

    private void status(List<String> args) {
        if (!requireNoArgs("status", args)) return;
        output.println(target.status());
    }

    private void players(List<String> args) {
        if (!requireNoArgs("players", args)) return;
        List<String> players = target.players();
        if (players == null || players.isEmpty()) {
            output.println("No player sessions.");
            return;
        }
        output.println("Players (" + players.size() + "):");
        for (String player : players) output.println("  " + player);
    }

    private void save(List<String> args) {
        if (!requireNoArgs("save", args)) return;
        if (!target.running()) {
            errors.println("Server is not running.");
            return;
        }
        if (!target.save()) errors.println("Manual server save failed.");
    }

    private void version(List<String> args) {
        if (!requireNoArgs("version", args)) return;
        output.println(BuildInfo.display());
    }

    private void stop(List<String> args) {
        if (!requireNoArgs("stop", args)) return;
        if (!target.running()) {
            output.println("Server is already stopped.");
            return;
        }
        output.println("Stopping dedicated server.");
        target.stop();
    }

    private boolean requireNoArgs(String command, List<String> args) {
        if (args.isEmpty()) return true;
        errors.println("Usage: " + command);
        return false;
    }

    private record Command(String usage, String description, CommandHandler handler) { }
    @FunctionalInterface private interface CommandHandler { void run(List<String> args); }
}
