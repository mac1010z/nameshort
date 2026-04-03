package com.example.sc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.*;
import java.io.*;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public class SCMod implements ModInitializer {

    private static final Map<String, String> shortcuts = new HashMap<>();
    private static final Path FILE = Paths.get("config/nameshort_shortcuts.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        loadFromFile();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("sc")

                // /sc add +/command+ shortcutName
                .then(literal("add")
                    .then(argument("input", StringArgumentType.greedyString())
                        .executes(this::addShortcut)
                    )
                )

                // /sc delete shortcutName
                .then(literal("delete")
                    .then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            shortcuts.keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(this::deleteShortcut)
                    )
                )

                // /sc list  or  /sc list shortcutName
                .then(literal("list")
                    .executes(this::listAll)
                    .then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            shortcuts.keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(this::listOne)
                    )
                )

                // /sc shortcutName  (run it)
                .then(argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        shortcuts.keySet().forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(this::runShortcut)
                )
            );
        });
    }

    // Parse input as "+/command+ name" from a single greedy string
    private int addShortcut(CommandContext<ServerCommandSource> ctx) {
        String input = StringArgumentType.getString(ctx, "input");

        int start = input.indexOf('+');
        int end = input.lastIndexOf('+');

        if (start == -1 || end == -1 || start == end) {
            ctx.getSource().sendError(Text.literal("Format: /sc add +/command+ shortcutName"));
            return 0;
        }

        String command = input.substring(start + 1, end).trim();
        String name = input.substring(end + 1).trim();

        if (name.isEmpty()) {
            ctx.getSource().sendError(Text.literal("Missing shortcut name after the closing +"));
            return 0;
        }

        if (!name.matches("[a-z0-9_]+")) {
            ctx.getSource().sendError(Text.literal("Name must use only lowercase letters, numbers, or underscores"));
            return 0;
        }

        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        shortcuts.put(name, command);
        saveToFile();

        final String finalCommand = command;
        ctx.getSource().sendFeedback(() -> Text.literal("Saved: /sc " + name + " -> " + finalCommand), false);
        return 1;
    }

    private int deleteShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        shortcuts.remove(name);
        saveToFile();

        ctx.getSource().sendFeedback(() -> Text.literal("Deleted: " + name), false);
        return 1;
    }

    private int listAll(CommandContext<ServerCommandSource> ctx) {
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

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        String cmd = shortcuts.get(name);
        ctx.getSource().sendFeedback(() -> Text.literal("/sc " + name + " -> " + cmd), false);
        return 1;
    }

    private int runShortcut(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");

        if (!shortcuts.containsKey(name)) {
            ctx.getSource().sendError(Text.literal("Shortcut '" + name + "' not found"));
            return 0;
        }

        ctx.getSource().getServer().getCommandManager()
            .executeWithPrefix(ctx.getSource(), shortcuts.get(name));
        return 1;
    }

    private static void saveToFile() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(shortcuts, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadFromFile() {
        if (!Files.exists(FILE)) return;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            Map<String, String> data = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (data != null) shortcuts.putAll(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
