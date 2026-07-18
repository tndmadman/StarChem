package com.tndmadman.rts;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses and executes local dedicated-server administration commands. */
final class ServerCommandDispatcher {
    private static final long MAX_SHUTDOWN_SECONDS = 24 * 60 * 60;
    private static final int MAX_NOTICE_LENGTH = 512;

    interface Target {
        String status();
        List<String> players();
        List<String> uptime();
        List<String> performance(String scope);
        List<String> systems(String filter, String value);
        List<String> system(String selector);
        List<String> connection(String selector);
        List<String> saveInfo();
        String announce(String message);
        String scheduleShutdown(long delaySeconds, String reason);
        String cancelShutdown();
        String shutdownStatus();
        String disconnect(String selector, String reason);
        List<String> developer(List<String> args);
        boolean save();
        void stop();
        boolean running();
    }

    private final Target target;
    private final PrintStream output;
    private final PrintStream errors;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = Map.of("?", "help", "stats", "perf");

    ServerCommandDispatcher(Target target, PrintStream output, PrintStream errors) {
        if (target == null) throw new IllegalArgumentException("Server console target is required.");
        this.target = target;
        this.output = output == null ? System.out : output;
        this.errors = errors == null ? System.err : errors;
        register("help", "help [command]", "Show available commands or detailed command help.", this::help);
        register("status", "status", "Print server, network, save, and autosave status.", this::status);
        register("players", "players", "List connected and retained player sessions.", this::players);
        register("uptime", "uptime", "Show server start time, uptime, saves, and autosave timing.", this::uptime);
        register("perf", "perf [all|network|simulation]", "Show simulation and network performance counters.", this::performance);
        register("systems", "systems [active|controlled|player <id-or-name>]", "List authoritative galaxy systems.", this::systems);
        register("system", "system <id-or-name>", "Show detailed information for one galaxy system.", this::system);
        register("connection", "connection <player-id-or-name>", "Show sanitized connection diagnostics for a player.", this::connection);
        register("save-info", "save-info", "Show the current save path and save-file state.", this::saveInfo);
        register("save", "save", "Write a manual dedicated-server save.", this::save);
        register("say", "say <message>", "Broadcast a server notice to connected clients.", this::say);
        register("shutdown", "shutdown [now|status|cancel|<duration>] [reason]", "Schedule, inspect, cancel, or perform shutdown.", this::shutdown);
        register("disconnect", "disconnect <player-id-or-name> [reason]", "Temporarily disconnect a player while retaining the session.", this::disconnect);
        register("dev", "dev <status|access|ai|timers|trigger|spawn|remove|reset> ...", "Run developer-only server controls.", this::developer);
        register("version", "version", "Print the running StarChem build identity.", this::version);
        register("stop", "stop", "Save and stop the dedicated server immediately.", this::stop);
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
            String detail = ex.getMessage();
            errors.println("Console command failed" + (detail == null || detail.isBlank() ? "." : ": " + detail));
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

    static long parseDurationSeconds(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("shutdown duration is required.");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        char suffix = normalized.charAt(normalized.length() - 1);
        if (Character.isLetter(suffix)) {
            multiplier = switch (suffix) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 60 * 60;
                default -> throw new IllegalArgumentException("shutdown duration must use seconds, m, or h.");
            };
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            long amount = Long.parseLong(normalized);
            if (amount < 1 || amount > MAX_SHUTDOWN_SECONDS / multiplier) {
                throw new IllegalArgumentException("shutdown duration must be between 1 second and 24 hours.");
            }
            return amount * multiplier;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("shutdown duration is not numeric.");
        }
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
        output.println("  stats - Alias for perf.");
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
        printLines(players);
    }

    private void uptime(List<String> args) {
        if (!requireNoArgs("uptime", args)) return;
        printLines(target.uptime());
    }

    private void performance(List<String> args) {
        if (args.size() > 1) {
            errors.println("Usage: perf [all|network|simulation]");
            return;
        }
        String scope = args.isEmpty() ? "all" : args.get(0).toLowerCase(Locale.ROOT);
        if (!List.of("all", "network", "simulation").contains(scope)) {
            errors.println("Usage: perf [all|network|simulation]");
            return;
        }
        printLines(target.performance(scope));
    }

    private void systems(List<String> args) {
        if (args.isEmpty()) {
            printLines(target.systems("all", ""));
            return;
        }
        String filter = args.get(0).toLowerCase(Locale.ROOT);
        if (("active".equals(filter) || "controlled".equals(filter)) && args.size() == 1) {
            printLines(target.systems(filter, ""));
            return;
        }
        if ("player".equals(filter) && args.size() == 2) {
            printLines(target.systems(filter, args.get(1)));
            return;
        }
        errors.println("Usage: systems [active|controlled|player <id-or-name>]");
    }

    private void system(List<String> args) {
        if (args.size() != 1) {
            errors.println("Usage: system <id-or-name>");
            return;
        }
        printLines(target.system(args.get(0)));
    }

    private void connection(List<String> args) {
        if (args.size() != 1) {
            errors.println("Usage: connection <player-id-or-name>");
            return;
        }
        printLines(target.connection(args.get(0)));
    }

    private void saveInfo(List<String> args) {
        if (!requireNoArgs("save-info", args)) return;
        printLines(target.saveInfo());
    }

    private void save(List<String> args) {
        if (!requireNoArgs("save", args)) return;
        if (!target.running()) {
            errors.println("Server is not running.");
            return;
        }
        if (!target.save()) errors.println("Manual server save failed.");
    }

    private void say(List<String> args) {
        if (args.isEmpty()) {
            errors.println("Usage: say <message>");
            return;
        }
        String message = join(args, 0);
        if (message.length() > MAX_NOTICE_LENGTH) {
            errors.println("Server notice may not exceed " + MAX_NOTICE_LENGTH + " characters.");
            return;
        }
        output.println(target.announce(message));
    }

    private void shutdown(List<String> args) {
        if (args.isEmpty() || "now".equalsIgnoreCase(args.get(0))) {
            if (!target.running()) {
                output.println("Server is already stopped.");
                return;
            }
            output.println("Stopping dedicated server.");
            target.stop();
            return;
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("status".equals(action) && args.size() == 1) {
            output.println(target.shutdownStatus());
            return;
        }
        if ("cancel".equals(action) && args.size() == 1) {
            output.println(target.cancelShutdown());
            return;
        }
        long seconds;
        try {
            seconds = parseDurationSeconds(args.get(0));
        } catch (IllegalArgumentException ex) {
            errors.println(ex.getMessage());
            errors.println("Usage: shutdown [now|status|cancel|<duration>] [reason]");
            return;
        }
        output.println(target.scheduleShutdown(seconds, join(args, 1)));
    }

    private void disconnect(List<String> args) {
        if (args.isEmpty()) {
            errors.println("Usage: disconnect <player-id-or-name> [reason]");
            return;
        }
        output.println(target.disconnect(args.get(0), join(args, 1)));
    }

    private void developer(List<String> args) {
        printLines(target.developer(args));
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

    private void printLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            output.println("No matching results.");
            return;
        }
        for (String line : lines) output.println(line);
    }

    private boolean requireNoArgs(String command, List<String> args) {
        if (args.isEmpty()) return true;
        errors.println("Usage: " + command);
        return false;
    }

    private String join(List<String> args, int from) {
        if (args == null || from >= args.size()) return "";
        return String.join(" ", args.subList(Math.max(0, from), args.size())).trim();
    }

    private record Command(String usage, String description, CommandHandler handler) { }
    @FunctionalInterface private interface CommandHandler { void run(List<String> args); }
}
