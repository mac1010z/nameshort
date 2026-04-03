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

    // UUID -> (shortcutName -> command)
    private static final Map<String, Map<String, String>> playerShortcuts = new HashMap<>();
    private static final Path FILE = Paths.get("config/nameshort_shortcuts.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        loadFromFile();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /sc <name> — run a shortcut
            dispatcher.register(literal("sc")
                .then(argument("name", StringArgumentType.word())
                    .suggests(playerShortcutSuggestions())
                    .executes(this::runShortcut)
                )
            );

            // /scm — manage shortcuts
            dispatcher.register(literal("scm")

                // /scm add +/command+ name
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

    // ── Helper ───────────────────────────────────────────────────────────────

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

    // ── Commands ─────────────────────────────────────────────────────────────

    private int runShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        ctx.getSource().getServer().getCommandManager()
            .executeWithPrefix(ctx.getSource(), shortcuts.get(name));
        return 1;
    }

    private int addShortcut(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "input");
        String command = extractCommand(input);

        if (command == null) {
            ctx.getSource().sendError(Text.literal("Format: /scm add +/command+ shortcutName"));
            return 0;
        }

        String name = input.substring(input.lastIndexOf('+') + 1).trim();

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
        String command = extractCommand(input);
        Map<String, String> shortcuts = getShortcuts(ctx.getSource());

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        if (command == null) {
            ctx.getSource().sendError(Text.literal("Format: /scm edit name +/newcommand+"));
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

        String command = shortcuts.get(existing);
        shortcuts.put(alias, command);
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Alias created: /sc " + alias + " -> " + command), false);
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

        String targetCommand = shortcuts.get(name);
        ArrayList<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, String> entry : shortcuts.entrySet()) {
            if (entry.getValue().equals(targetCommand)) {
                toRemove.add(entry.getKey());
            }
        }

        toRemove.forEach(shortcuts::remove);
        saveToFile();

        ctx.getSource().sendFeedback(
            () -> Text.literal("Deleted " + toRemove.size() + " shortcut(s) pointing to: " + targetCommand),
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
        for (String name : shortcuts.keySet()) {
            ctx.getSource().sendFeedback(() -> Text.literal("/sc " + name), false);
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

        String cmd = shortcuts.get(name);
        ctx.getSource().sendFeedback(() -> Text.literal("/sc " + name + " -> " + cmd), false);
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
