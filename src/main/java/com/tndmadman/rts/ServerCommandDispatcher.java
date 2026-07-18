package com.tndmadman.rts;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses and executes local dedicated-server administration commands. */
final class ServerCommandDispatcher {
    private static final long MAX_DURATION_SECONDS = 24 * 60 * 60;
    private static final int MAX_NOTICE_LENGTH = 512;

    interface Target {
        String status();
        List<String> players();
        List<String> leaderboard(int limit);
        List<String> player(String selector, String section);
        List<String> sessions(String filter);
        List<String> uptime();
        List<String> performance(String scope);
        List<String> systems(String filter, String value);
        List<String> system(String selector);
        List<String> connection(String selector);
        List<String> resync(String selector);
        List<String> serverInfo(String scope);
        List<String> saveInfo();
        List<String> autosave(List<String> args);
        List<String> backups(List<String> args);
        List<String> maintenance(List<String> args);
        List<String> slots(List<String> args);
        List<String> motd(List<String> args);
        String announce(String message);
        String scheduleShutdown(long delaySeconds, String reason);
        String cancelShutdown();
        String shutdownStatus();
        String disconnect(String selector, String reason);
        List<String> developer(List<String> args);
        boolean save();
        void stop();
        boolean running();
        default Object extensionContext() { return null; }
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
        register("leaderboard", "leaderboard [top <count>]", "Show authoritative player rankings.", this::leaderboard);
        register("player", "player <id-or-name> [assets|research|systems]", "Show detailed player state.", this::player);
        register("sessions", "sessions [connected|retained]", "Show sanitized server session details.", this::sessions);
        register("uptime", "uptime", "Show server start time, uptime, saves, and autosave timing.", this::uptime);
        register("perf", "perf [all|network|simulation]", "Show simulation and network performance counters.", this::performance);
        register("systems", "systems [active|controlled|player <id-or-name>]", "List authoritative galaxy systems.", this::systems);
        register("system", "system <id-or-name>", "Show detailed information for one galaxy system.", this::system);
        register("connection", "connection <player-id-or-name>", "Show sanitized connection diagnostics for a player.", this::connection);
        register("resync", "resync <player-id-or-name|all|resources>", "Resend authoritative state or force resource correction.", this::resync);
        register("server-info", "server-info [compatibility|tls]", "Show server build, protocol, configuration, and TLS identity.", this::serverInfo);
        register("save-info", "save-info", "Show the current save path and save-file state.", this::saveInfo);
        register("save", "save", "Write a manual dedicated-server save.", this::save);
        register("autosave", "autosave <status|set <duration>|on|off|reset>", "Inspect or change the runtime autosave interval.", this::autosave);
        register("backups", "backups <list|create [label]|verify <selector>|prune>", "Manage and verify save backups.", this::backups);
        register("maintenance", "maintenance <status|on [reason]|off>", "Control admission of new player identities.", this::maintenance);
        register("slots", "slots [set <count>|unlimited]", "Inspect or change the player-session limit.", this::slots);
        register("motd", "motd <show|set <message>|clear|send>", "Manage the persistent message of the day.", this::motd);
        register("whitelist", "whitelist <status|on|off|list|add|remove|add-connected> ...", "Control persistent player admission by identity.", args -> extended("whitelist", args));
        register("kick", "kick <player> [duration] [reason]", "Temporarily block a player while retaining all assets.", args -> extended("kick", args));
        register("kicks", "kicks", "List active temporary kicks.", args -> extended("kicks", args));
        register("unkick", "unkick <entry-id|player|name>", "Remove matching temporary kicks.", args -> extended("unkick", args));
        register("ban", "ban [player|ip|device|mac] <target> <duration|permanent> [reason]", "Persistently block an identity, IP/CIDR, or client device ID.", args -> extended("ban", args));
        register("bans", "bans [all|player|ip|device]", "List active bans.", args -> extended("bans", args));
        register("unban", "unban <entry-id|player|name|target>", "Remove matching bans.", args -> extended("unban", args));
        register("pause", "pause <status|on [reason]|off>", "Pause authoritative simulation while administration remains active.", args -> extended("pause", args));
        register("prune-systems", "prune-systems <preview|run confirm>", "Preview or safely prune abandoned dynamic systems.", args -> extended("prune-systems", args));
        register("health", "health [disk|network|simulation]", "Show JVM, disk, simulation, and network health.", args -> extended("health", args));
        register("activity", "activity [last <count>|player <player>|type <type>|clear|export <filename>]", "Inspect the bounded server event journal.", args -> extended("activity", args));
        register("factions", "factions [npc]", "Show authoritative NPC faction totals and runtime state.", args -> extended("factions", args));
        register("faction", "faction <id-or-name>", "Inspect one NPC faction.", args -> extended("faction", args));
        register("production", "production <summary|player <player>|system <system>|base <base-id>|stalled>", "Inspect authoritative production queues.", args -> extended("production", args));
        register("assets", "assets <player|system> <selector> [ships|bases]", "List detailed authoritative assets.", args -> extended("assets", args));
        register("asset", "asset <unit-or-base-id>", "Inspect one authoritative unit or base.", args -> extended("asset", args));
        register("research", "research <topics|topic <topic>|status|completed|queued|available|blocked <player>>", "Inspect research rules and player research state.", args -> extended("research", args));
        register("tell", "tell <player> <message>", "Send a private server notice to one connected player.", args -> extended("tell", args));
        register("notice", "notice <all <message>|system <system> <message>>", "Send a scoped server notice.", args -> extended("notice", args));
        register("threads", "threads", "List live JVM threads and states.", args -> extended("threads", args));
        register("memory", "memory", "Show JVM heap and non-heap usage.", args -> extended("memory", args));
        register("gc-status", "gc-status", "Show garbage collector statistics without forcing collection.", args -> extended("gc-status", args));
        register("dump", "dump <player|system> <selector> [filename]", "Write a sanitized administration dump.", args -> extended("dump", args));
        register("observations", "observations [player]", "Show retained last-seen IP and client-device moderation signals.", args -> extended("observations", args));
        register("say", "say <message>", "Broadcast a server notice to connected clients.", this::say);
        register("shutdown", "shutdown [now|status|cancel|<duration>] [reason]", "Schedule, inspect, cancel, or perform shutdown.", this::shutdown);
        register("disconnect", "disconnect <player-id-or-name> [reason]", "Temporarily disconnect a player while retaining the session.", this::disconnect);
        register("dev", "dev <status|mode|access|freebuild|resource|research|ai|timers|faction|production|asset|player|spawn> ...", "Run trusted local-console developer and recovery controls.", this::developer);
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
            List<String> arguments = parts.subList(1, parts.size());
            ServerCommandExtensions.auditCommand(target, commandName, arguments);
            command.handler().run(arguments);
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException("duration is required.");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        char suffix = normalized.charAt(normalized.length() - 1);
        if (Character.isLetter(suffix)) {
            multiplier = switch (suffix) {
                case 's' -> 1;
                case 'm' -> 60;
                case 'h' -> 60 * 60;
                case 'd' -> 24 * 60 * 60;
                default -> throw new IllegalArgumentException("duration must use seconds, s, m, h, or d.");
            };
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            long amount = Long.parseLong(normalized);
            if (amount < 1 || amount > MAX_DURATION_SECONDS / multiplier) {
                throw new IllegalArgumentException("duration must be between 1 second and 24 hours.");
            }
            return amount * multiplier;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("duration is not numeric.");
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
        if (players == null || players.isEmpty()) output.println("No player sessions.");
        else {
            output.println("Players (" + players.size() + "):");
            printLines(players);
        }
    }

    private void leaderboard(List<String> args) {
        int limit = Integer.MAX_VALUE;
        if (!args.isEmpty()) {
            if (args.size() != 2 || !"top".equalsIgnoreCase(args.get(0))) {
                errors.println("Usage: leaderboard [top <count>]");
                return;
            }
            try { limit = Integer.parseInt(args.get(1)); }
            catch (NumberFormatException ex) { limit = -1; }
            if (limit < 1 || limit > 1000) {
                errors.println("Leaderboard count must be between 1 and 1000.");
                return;
            }
        }
        printLines(target.leaderboard(limit));
    }

    private void player(List<String> args) {
        if (args.size() < 1 || args.size() > 2) {
            errors.println("Usage: player <id-or-name> [assets|research|systems]");
            return;
        }
        String section = args.size() == 2 ? args.get(1).toLowerCase(Locale.ROOT) : "summary";
        if (!List.of("summary", "assets", "research", "systems").contains(section)) {
            errors.println("Usage: player <id-or-name> [assets|research|systems]");
            return;
        }
        printLines(target.player(args.get(0), section));
    }

    private void sessions(List<String> args) {
        if (args.size() > 1) {
            errors.println("Usage: sessions [connected|retained]");
            return;
        }
        String filter = args.isEmpty() ? "all" : args.get(0).toLowerCase(Locale.ROOT);
        if (!List.of("all", "connected", "retained").contains(filter)) {
            errors.println("Usage: sessions [connected|retained]");
            return;
        }
        printLines(target.sessions(filter));
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

    private void resync(List<String> args) {
        if (args.size() != 1) {
            errors.println("Usage: resync <player-id-or-name|all|resources>");
            return;
        }
        printLines(target.resync(args.get(0)));
    }

    private void serverInfo(List<String> args) {
        if (args.size() > 1) {
            errors.println("Usage: server-info [compatibility|tls]");
            return;
        }
        String scope = args.isEmpty() ? "all" : args.get(0).toLowerCase(Locale.ROOT);
        if (!List.of("all", "compatibility", "tls").contains(scope)) {
            errors.println("Usage: server-info [compatibility|tls]");
            return;
        }
        printLines(target.serverInfo(scope));
    }

    private void saveInfo(List<String> args) {
        if (!requireNoArgs("save-info", args)) return;
        printLines(target.saveInfo());
    }

    private void save(List<String> args) {
        if (!requireNoArgs("save", args)) return;
        if (!target.running()) errors.println("Server is not running.");
        else if (!target.save()) errors.println("Manual server save failed.");
    }

    private void autosave(List<String> args) { printLines(target.autosave(args)); }
    private void backups(List<String> args) { printLines(target.backups(args)); }
    private void maintenance(List<String> args) { printLines(target.maintenance(args)); }
    private void slots(List<String> args) { printLines(target.slots(args)); }
    private void motd(List<String> args) { printLines(target.motd(args)); }

    private void extended(String command, List<String> args) {
        printLines(ServerCommandExtensions.execute(target, command, args));
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
            if (!target.running()) output.println("Server is already stopped.");
            else {
                output.println("Stopping dedicated server.");
                target.stop();
            }
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
        try { seconds = parseDurationSeconds(args.get(0)); }
        catch (IllegalArgumentException ex) {
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

    private void developer(List<String> args) { printLines(target.developer(args)); }

    private void version(List<String> args) {
        if (!requireNoArgs("version", args)) return;
        output.println(BuildInfo.display());
    }

    private void stop(List<String> args) {
        if (!requireNoArgs("stop", args)) return;
        if (!target.running()) output.println("Server is already stopped.");
        else {
            output.println("Stopping dedicated server.");
            target.stop();
        }
    }

    private void printLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) output.println("No matching results.");
        else for (String line : lines) output.println(line);
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
