package com.example.sc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.*;
import java.io.*;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public class SCMod implements ModInitializer {

    private static final String CHAIN_PREFIX = "__chain__:";
    // UUID -> (shortcutName -> command or chain)
    private static final Map<String, Map<String, String>> playerShortcuts = new HashMap<>();
    private static final Path FILE = Paths.get("config/nameshort_shortcuts.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        loadFromFile();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /sc <name> [trailing args...] — run a shortcut
            dispatcher.register(literal("sc")
                .then(argument("name", StringArgumentType.word())
                    .suggests(playerShortcutSuggestions())
                    .executes(this::runShortcut)
                    .then(argument("args", StringArgumentType.greedyString())
                        .executes(this::runShortcutWithArgs)
                    )
                )
            );

            // /scm — manage shortcuts
            dispatcher.register(literal("scm")

                // /scm add +/command+ name  OR  /scm add +-sc1--sc2-+ name
                .then(literal("add")
                    .then(argument("input", StringArgumentType.greedyString())
                        .executes(this::addShortcut)
                    )
                )

                // /scm edit name +/newcommand+
                .then(literal("edit")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(playerShortcutSuggestions())
                        .then(argument("input", StringArgumentType.greedyString())
                            .executes(this::editShortcut)
                        )
                    )
                )

                // /scm delete all | /scm delete <name>
                .then(literal("delete")
                    .then(literal("all")
                        .executes(this::deleteAll)
                    )
                    .then(argument("name", StringArgumentType.word())
                        .suggests(playerShortcutSuggestions())
                        .executes(this::deleteShortcut)
                    )
                )

                // /scm alias <existing> <newname>
                // /scm alias delete <name>
                // /scm alias delete <name> related
                .then(literal("alias")
                    .then(literal("delete")
                        .then(argument("name", StringArgumentType.word())
                            .suggests(playerShortcutSuggestions())
                            .executes(this::deleteAlias)
                            .then(literal("related")
                                .executes(this::deleteAliasAndRelated)
                            )
                        )
                    )
                    .then(argument("existing", StringArgumentType.word())
                        .suggests(playerShortcutSuggestions())
                        .then(argument("alias", StringArgumentType.word())
                            .executes(this::addAlias)
                        )
                    )
                )

                // /scm list | /scm list <name>
                .then(literal("list")
                    .executes(this::listAll)
                    .then(argument("name", StringArgumentType.word())
                        .suggests(playerShortcutSuggestions())
                        .executes(this::listOne)
                    )
                )
            );
        });
    }

    // ── Suggestion provider ──────────────────────────────────────────────────

    private SuggestionProvider<ServerCommandSource> playerShortcutSuggestions() {
        return (ctx, builder) -> {
            getShortcuts(ctx.getSource()).keySet().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> getShortcuts(ServerCommandSource source) {
        String uuid = source.getPlayer() != null
            ? source.getPlayer().getUuidAsString()
            : "server";
        return playerShortcuts.computeIfAbsent(uuid, k -> new HashMap<>());
    }

    private String extractCommand(String input) {
        int start = input.indexOf('+');
        int end = input.lastIndexOf('+');
        if (start == -1 || end == -1 || start == end) return null;
        String cmd = input.substring(start + 1, end).trim();
        return cmd.startsWith("/") ? cmd : "/" + cmd;
    }

    // Returns shortcut names if input is a chain (e.g. +-sc1--sc2-+), else null
    private String[] extractChainNames(String input) {
        int start = input.indexOf('+');
        int end = input.lastIndexOf('+');
        if (start == -1 || end == -1 || start == end) return null;
        String content = input.substring(start + 1, end).trim();
        if (!content.matches("(-[a-z0-9_]+-)+")) return null;
        String[] parts = content.split("-");
        ArrayList<String> names = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) names.add(part);
        }
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    private String parseName(String input) {
        String name = input.substring(input.lastIndexOf('+') + 1).trim();
        return name;
    }

    // Execute a stored value (command or chain), optionally appending trailing args
    private int execute(ServerCommandSource source, String stored, String trailingArgs) {
        if (stored.startsWith(CHAIN_PREFIX)) {
            String[] names = stored.substring(CHAIN_PREFIX.length()).split(" ");
            Map<String, String> shortcuts = getShortcuts(source);
            for (String n : names) {
                if (!shortcuts.containsKey(n)) {
                    source.sendError(Text.literal("Chain: shortcut '" + n + "' not found"));
                    return 0;
                }
                // Chains don't forward trailing args — each runs as saved
                execute(source, shortcuts.get(n), null);
            }
            return 1;
        } else {
            String cmd = trailingArgs != null ? stored + " " + trailingArgs : stored;
            source.getServer().getCommandManager().executeWithPrefix(source, cmd);
            return 1;
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    private int runShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        return execute(ctx.getSource(), shortcuts.get(name), null);
    }

    private int runShortcutWithArgs(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String args = StringArgumentType.getString(ctx, "args");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        return execute(ctx.getSource(), shortcuts.get(name), args);
    }

    private int addShortcut(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "input");

        // Try chain syntax first: +-sc1--sc2-+ name
        String[] chainNames = extractChainNames(input);
        if (chainNames != null) {
            String name = parseName(input);
            if (name.isEmpty()) {
                ctx.getSource().sendError(Text.literal("Missing shortcut name after closing +"));
                return 0;
            }
            if (!name.matches("[a-z0-9_]+")) {
                ctx.getSource().sendError(Text.literal("Name must use only lowercase letters, numbers, or underscores"));
                return 0;
            }
            Map<String, String> shortcuts = getShortcuts(ctx.getSource());
            String stored = CHAIN_PREFIX + String.join(" ", chainNames);
            shortcuts.put(name, stored);
            saveToFile();
            final String[] finalNames = chainNames;
            ctx.getSource().sendFeedback(() -> Text.literal("Chain saved: /sc " + name + " -> [" + String.join(", ", finalNames) + "]"), false);
            return 1;
        }

        // Regular command: +/command+ name
        String command = extractCommand(input);
        if (command == null) {
            ctx.getSource().sendError(Text.literal("Format: /scm add +/command+ name  OR  /scm add +-sc1--sc2-+ name"));
            return 0;
        }

        String name = parseName(input);
        if (name.isEmpty()) {
            ctx.getSource().sendError(Text.literal("Missing shortcut name after closing +"));
            return 0;
        }
        if (!name.matches("[a-z0-9_]+")) {
            ctx.getSource().sendError(Text.literal("Name must use only lowercase letters, numbers, or underscores"));
            return 0;
        }

        Map<String, String> shortcuts = getShortcuts(ctx.getSource());
        shortcuts.put(name, command);
        saveToFile();

        final String finalCommand = command;
        ctx.getSource().sendFeedback(() -> Text.literal("Saved: /sc " + name + " -> " + finalCommand), false);
        return 1;
    }

    private int editShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String input = StringArgumentType.getString(ctx, "input");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        // Support editing into a chain or a command
        String[] chainNames = extractChainNames(input);
        if (chainNames != null) {
            String stored = CHAIN_PREFIX + String.join(" ", chainNames);
            shortcuts.put(name, stored);
            saveToFile();
            final String[] finalNames = chainNames;
            ctx.getSource().sendFeedback(() -> Text.literal("Updated: /sc " + name + " -> chain [" + String.join(", ", finalNames) + "]"), false);
            return 1;
        }

        String command = extractCommand(input);
        if (command == null) {
            ctx.getSource().sendError(Text.literal("Format: /scm edit name +/newcommand+  OR  /scm edit name +-sc1--sc2-+"));
            return 0;
        }

        shortcuts.put(name, command);
        saveToFile();

        final String finalCommand = command;
        ctx.getSource().sendFeedback(() -> Text.literal("Updated: /sc " + name + " -> " + finalCommand), false);
        return 1;
    }

    private int deleteShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        shortcuts.remove(name);
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted: " + name), false);
        return 1;
    }

    private int deleteAll(CommandContext<ServerCommandSource> ctx) {
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());
        int count = shortcuts.size();
        shortcuts.clear();
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted all " + count + " shortcuts"), false);
        return 1;
    }

    private int addAlias(CommandContext<ServerCommandSource> ctx) {
        String existing = StringArgumentType.getString(ctx, "existing");
        String alias = StringArgumentType.getString(ctx, "alias");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(existing)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + existing + "' not found"));
            return 0;
        }

        if (!alias.matches("[a-z0-9_]+")) {
            ctx.getSource().sendError(Text.literal("Alias must use only lowercase letters, numbers, or underscores"));
            return 0;
        }

        String stored = shortcuts.get(existing);
        shortcuts.put(alias, stored);
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Alias created: /sc " + alias + " -> " + existing), false);
        return 1;
    }

    private int deleteAlias(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Alias '" + name + "' not found"));
            return 0;
        }

        shortcuts.remove(name);
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted alias: " + name), false);
        return 1;
    }

    private int deleteAliasAndRelated(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        String targetStored = shortcuts.get(name);
        ArrayList<String> toRemove = new ArrayList<>();

        // Delete all shortcuts with the same stored value (aliases of this shortcut)
        for (Map.Entry<String, String> entry : shortcuts.entrySet()) {
            if (entry.getValue().equals(targetStored)) {
                toRemove.add(entry.getKey());
            }
        }

        // If it's a chain, also delete the component shortcuts
        if (targetStored.startsWith(CHAIN_PREFIX)) {
            String[] chainNames = targetStored.substring(CHAIN_PREFIX.length()).split(" ");
            for (String chainName : chainNames) {
                if (!toRemove.contains(chainName)) {
                    toRemove.add(chainName);
                }
            }
        }

        toRemove.forEach(shortcuts::remove);
        saveToFile();

        ctx.getSource().sendFeedback(
            () -> Text.literal("Deleted " + toRemove.size() + " shortcut(s)"),
            false
        );
        return 1;
    }

    private int listAll(CommandContext<ServerCommandSource> ctx) {
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (shortcuts.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No shortcuts saved"), false);
            return 1;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("--- Shortcuts ---"), false);
        for (String key : new java.util.TreeMap<>(shortcuts).keySet()) {
            String stored = shortcuts.get(key);
            if (stored.startsWith(CHAIN_PREFIX)) {
                String chain = stored.substring(CHAIN_PREFIX.length()).replace(" ", ", ");
                ctx.getSource().sendFeedback(() -> Text.literal("/sc " + key + " [chain: " + chain + "]"), false);
            } else {
                ctx.getSource().sendFeedback(() -> Text.literal("/sc " + key), false);
            }
        }
        return 1;
    }

    private int listOne(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        String stored = shortcuts.get(name);
        if (stored.startsWith(CHAIN_PREFIX)) {
            String chain = stored.substring(CHAIN_PREFIX.length()).replace(" ", ", ");
            ctx.getSource().sendFeedback(() -> Text.literal("/sc " + name + " -> chain [" + chain + "]"), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.literal("/sc " + name + " -> " + stored), false);
        }
        return 1;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private static void saveToFile() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(playerShortcuts, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadFromFile() {
        if (!Files.exists(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, Map<String, String>> data = GSON.fromJson(
                reader,
                new TypeToken<Map<String, Map<String, String>>>(){}.getType()
            );
            if (data != null) playerShortcuts.putAll(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
