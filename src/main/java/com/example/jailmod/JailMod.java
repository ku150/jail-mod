package com.example.jailmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

public class JailMod implements ModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/jailmod/config.json");
    private static final File TOML_CONFIG_FILE = new File("config/jailmod/config.toml");
    private static final File LANGUAGE_FILE = new File("config/jailmod/language.txt");
    private static final File JAIL_DATA_FILE = new File("config/jailmod/jail_data.json");
    private static Config config;
    private static Map<String, String> languageStrings = new HashMap<>();
    private static Map<UUID, JailData> jailedPlayers = new HashMap<>();

    // Lista de todos los jugadores que han entrado al servidor alguna vez (para autocompletar offlines)
    private static final Set<String> knownPlayerNames = ConcurrentHashMap.newKeySet();

    private static MinecraftServer serverInstance;
    private static DiscordNotifier discordNotifier;

    // Autocompletado de todos los jugadores (online y offline) para /jail imprison
    private static final SuggestionProvider<CommandSourceStack> ALL_PLAYERS_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();

        for (String name : knownPlayerNames) {
            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }

        MinecraftServer server = context.getSource().getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                String name = player.getName().getString();
                if (name.toLowerCase().startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
        }

        return builder.buildFuture();
    };

    // Autocompletado de jugadores encarcelados para /unjail usando el nuevo nameToIdCache() de Mojang
    private static final SuggestionProvider<CommandSourceStack> JAILED_PLAYERS_SUGGESTIONS = (context, builder) -> {
        MinecraftServer server = context.getSource().getServer();
        String remaining = builder.getRemaining().toLowerCase();

        for (Map.Entry<UUID, JailData> entry : jailedPlayers.entrySet()) {
            UUID uuid = entry.getKey();
            JailData data = entry.getValue();
            String nameToSuggest = data.playerName;

            if ((nameToSuggest == null || nameToSuggest.isEmpty()) && server != null) {
                ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(uuid);
                if (onlinePlayer != null) {
                    nameToSuggest = onlinePlayer.getName().getString();
                } else {
                    Optional<NameAndId> cached = server.services().nameToIdCache().get(uuid);
                    if (cached.isPresent()) {
                        nameToSuggest = cached.get().name();
                    }
                }
            }

            if (nameToSuggest != null && nameToSuggest.toLowerCase().startsWith(remaining)) {
                builder.suggest(nameToSuggest);
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> RETURN_TO_LAST_LOCATION_SUGGESTIONS = (context, builder) -> {
        builder.suggest("true");
        builder.suggest("false");
        return builder.buildFuture();
    };

    private static ConfigFormat configFormat = ConfigFormat.JSON;

    private enum ConfigFormat {
        JSON, TOML
    }

    public static class Config {
        public String _config_guide = "JailMod Configuration Guide: \n" +
                "- admin_roles: Comma-separated list of roles or tags that grant /jail access. Use 'op' to include server operators.\n"
                + "- use_previous_position: If true, released players will be teleported to their original spawn point (if return_to_last_location is false or unavailable).\n"
                + "- return_to_last_location: If true, released players will be teleported back to the exact spot where they were jailed.\n"
                + "- jail_position: The coordinates where players are held while in jail.\n"
                + "- release_position: The fallback coordinates for releasing players if no other location (spawn/last) is used.\n"
                + "- discord_webhook_url: Optional Discord webhook. If empty and BanHammer is installed, JailMod will reuse BanHammer's webhook.\n"
                + "- use_banhammer_webhook: If true and discord_webhook_url is empty, JailMod may reuse BanHammer's webhook.";
        public String admin_roles = "op";
        public boolean use_previous_position = true;
        public boolean return_to_last_location = true;
        public Position release_position = new Position(100, 65, 100);
        public Position jail_position = new Position(0, 60, 0);
        public String discord_webhook_url = "";

        @SerializedName(value = "use_banhammer_webhook", alternate = { "sendJailMessage" })
        public boolean useBanhammerWebhook = true;

        public static class Position {
            public int x; public int y; public int z;
            public Position(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        }
    }

    private static class JailData {
        public UUID playerUUID;
        public String playerName;
        public BlockPos originalSpawnPos;
        public ResourceKey<Level> originalSpawnDimension;
        public boolean hadSpawnPoint;
        public String reason;
        public int remainingTicks;
        public boolean jailedOffline;

        public double lastX;
        public double lastY;
        public double lastZ;
        public float lastYaw;
        public float lastPitch;
        public String lastDimension;

        public boolean hasStatSnapshot;
        public float savedHealth;
        public int savedFoodLevel;
        public float savedSaturationLevel;
        public int savedAir;
        public float savedAbsorption;
        public int savedXpLevel;
        public float savedXpProgress;
        public int savedTotalXp;
        public boolean savedInvulnerable;
        public int savedFireTicks;

        public List<MobEffectSnapshot> savedEffects;
    }

    private static class MobEffectSnapshot {
        public String effectId; public int duration; public int amplifier;
        public boolean ambient; public boolean showParticles; public boolean showIcon;
    }

    private static class JailUpdateResult {
        public final boolean wasAlreadyJailed;
        public final int addedSeconds;
        public final int totalSeconds;

        public JailUpdateResult(boolean wasAlreadyJailed, int addedSeconds, int totalSeconds) {
            this.wasAlreadyJailed = wasAlreadyJailed;
            this.addedSeconds = addedSeconds;
            this.totalSeconds = totalSeconds;
        }
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
            loadKnownPlayers();
            Component message = Component.literal("[Jail-Mod] Loaded").withStyle(style -> style.withColor(0x00FF00).withBold(true));
            server.sendSystemMessage(message);
        });

        loadConfig();
        loadLanguage();
        loadJailData();
        discordNotifier = new DiscordNotifier();
        discordNotifier.reload();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<UUID> playersToRelease = new ArrayList<>();
            for (Map.Entry<UUID, JailData> entry : jailedPlayers.entrySet()) {
                UUID playerUUID = entry.getKey();
                JailData jailData = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                if (player != null) {
                    jailData.remainingTicks--;
                    applyFrozenStats(player, jailData);
                    if (jailData.remainingTicks <= 0) {
                        playersToRelease.add(playerUUID);
                    }
                }
            }
            for (UUID playerUUID : playersToRelease) {
                releasePlayer(playerUUID);
            }
        });

        registerInteractionListeners();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID playerUUID = player.getUUID();

            knownPlayerNames.add(player.nameAndId().name());

            if (jailedPlayers.containsKey(playerUUID)) {
                JailData jailData = jailedPlayers.get(playerUUID);
                jailData.playerName = player.nameAndId().name();

                if (jailData.jailedOffline) {
                    BlockPos originalSpawnPos = null;
                    ResourceKey<Level> originalSpawnDimension = null;
                    var respawn = player.getRespawnConfig();
                    if (respawn != null && respawn.respawnData() != null) {
                        originalSpawnPos = respawn.respawnData().pos();
                        originalSpawnDimension = respawn.respawnData().dimension();
                    }
                    jailData.originalSpawnPos = originalSpawnPos;
                    jailData.originalSpawnDimension = originalSpawnDimension;
                    jailData.hadSpawnPoint = originalSpawnPos != null;

                    jailData.lastX = player.getX();
                    jailData.lastY = player.getY();
                    jailData.lastZ = player.getZ();
                    jailData.lastYaw = player.getYRot();
                    jailData.lastPitch = player.getXRot();
                    jailData.lastDimension = player.level().dimension().identifier().toString();

                    captureStatSnapshot(player, jailData);

                    jailData.jailedOffline = false;
                    saveJailData();
                }

                if (jailData.remainingTicks > 0) {
                    jailPlayer(player, jailData);
                } else {
                    releasePlayer(playerUUID);
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // 1. ÁRBOL PRINCIPAL: /jail
            dispatcher.register(Commands.literal("jail")
                    .then(Commands.literal("imprison")
                            .requires(JailMod::hasAdminPermission)
                            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                    .suggests(ALL_PLAYERS_SUGGESTIONS)
                                    .then(Commands.argument("time", IntegerArgumentType.integer(1))
                                            .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                    .executes(context -> {
                                                        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
                                                        int timeInSeconds = IntegerArgumentType.getInteger(context, "time");
                                                        String reason = StringArgumentType.getString(context, "reason");
                                                        MinecraftServer server = context.getSource().getServer();
                                                        String actorName = context.getSource().getTextName();

                                                        for (NameAndId profile : profiles) {
                                                            ServerPlayer player = server.getPlayerList().getPlayer(profile.id());

                                                            if (player != null) {
                                                                JailUpdateResult result = jailPlayer(player, timeInSeconds, reason, actorName, true);
                                                                if (result.wasAlreadyJailed) {
                                                                    context.getSource().sendSuccess(() -> Component.literal("Added " + result.addedSeconds + " seconds to " + player.getName().getString() + ". Remaining: " + result.totalSeconds + " seconds."), true);
                                                                } else {
                                                                    context.getSource().sendSuccess(() -> Component.literal("Player " + player.getName().getString() + " jailed for " + timeInSeconds + " seconds."), true);
                                                                }
                                                            } else {
                                                                JailUpdateResult result = jailOfflinePlayer(profile, timeInSeconds, reason, actorName, true);
                                                                if (result.wasAlreadyJailed) {
                                                                    context.getSource().sendSuccess(() -> Component.literal("Added " + result.addedSeconds + " seconds to offline player " + profile.name() + ". Remaining: " + result.totalSeconds + " seconds."), true);
                                                                } else {
                                                                    context.getSource().sendSuccess(() -> Component.literal("Offline player " + profile.name() + " jailed for " + timeInSeconds + " seconds. They will be sent to jail upon joining."), true);
                                                                }
                                                            }
                                                        }
                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )
                    .then(Commands.literal("reload")
                            .requires(JailMod::hasAdminPermission)
                            .executes(context -> {
                                loadConfig(); loadLanguage(); discordNotifier.reload();
                                context.getSource().sendSuccess(() -> Component.literal("Configuration, language strings, and Discord message templates successfully reloaded!"), true);
                                return 1;
                            })
                    )
                    .then(Commands.literal("set_jail_position")
                            .requires(JailMod::hasAdminPermission)
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(context -> {
                                        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                        config.jail_position = new Config.Position(pos.getX(), pos.getY(), pos.getZ());
                                        saveConfig();
                                        context.getSource().sendSuccess(() -> Component.literal("Jail position set to (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), true);
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("info")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayer();
                                if (player != null && isPlayerInJail(player)) {
                                    JailData jailData = jailedPlayers.get(player.getUUID());
                                    int remainingSeconds = jailData.remainingTicks / 20;
                                    String reason = jailData.reason;
                                    String message = languageStrings.get("jail_info_message")
                                            .replace("{time}", String.valueOf(remainingSeconds))
                                            .replace("{reason}", reason);
                                    player.sendSystemMessage(Component.literal(message), false);
                                    return 1;
                                } else {
                                    String notInJailMessage = languageStrings.get("not_in_jail_message");
                                    context.getSource().sendSuccess(() -> Component.literal(notInJailMessage), false);
                                    return 0;
                                }
                            })
                    )
                    .then(Commands.literal("return_to_last_location")
                            .requires(JailMod::hasAdminPermission)
                            .then(Commands.argument("value", StringArgumentType.word())
                                    .suggests(RETURN_TO_LAST_LOCATION_SUGGESTIONS)
                                    .executes(context -> {
                                        String value = StringArgumentType.getString(context, "value");
                                        if (value.equalsIgnoreCase("true")) {
                                            config.return_to_last_location = true; config.use_previous_position = true;
                                            saveConfig(); context.getSource().sendSuccess(() -> Component.literal("return_to_last_location set to true."), false);
                                        } else if (value.equalsIgnoreCase("false")) {
                                            config.return_to_last_location = false; config.use_previous_position = false;
                                            saveConfig(); context.getSource().sendSuccess(() -> Component.literal("return_to_last_location set to false."), false);
                                        } else {
                                            context.getSource().sendFailure(Component.literal("Invalid value! Use 'true' or 'false'."));
                                            return 0;
                                        }
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("set_release_position")
                            .requires(JailMod::hasAdminPermission)
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(context -> {
                                        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                        config.release_position = new Config.Position(pos.getX(), pos.getY(), pos.getZ());
                                        saveConfig();
                                        context.getSource().sendSuccess(() -> Component.literal("Release position set to (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), true);
                                        return 1;
                                    })
                            )
                    )
            );

            // 2. ÁRBOL INDEPENDIENTE: /unjail
            dispatcher.register(Commands.literal("unjail")
                    .requires(source -> hasAdminPermission(source))
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                            .suggests(JAILED_PLAYERS_SUGGESTIONS)
                            .executes(context -> {
                                Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
                                MinecraftServer server = context.getSource().getServer();

                                for (NameAndId profile : profiles) {
                                    ServerPlayer player = server.getPlayerList().getPlayer(profile.id());

                                    if (player != null) {
                                        if (!isPlayerInJail(player)) {
                                            context.getSource().sendFailure(Component.literal("Player " + player.getName().getString() + " is not jailed.").withStyle(ChatFormatting.RED));
                                            continue;
                                        }
                                        unjailPlayer(player, true, context.getSource().getTextName());
                                        context.getSource().sendSuccess(() -> Component.literal("Player " + player.getName().getString() + " has been released from jail."), true);
                                    } else {
                                        if (!jailedPlayers.containsKey(profile.id())) {
                                            context.getSource().sendFailure(Component.literal("Offline player " + profile.name() + " is not jailed.").withStyle(ChatFormatting.RED));
                                            continue;
                                        }
                                        JailData removed = jailedPlayers.remove(profile.id());
                                        saveJailData();
                                        discordNotifier.sendUnjailMessage(config, profile.name(), removed.reason, context.getSource().getTextName(), true);
                                        context.getSource().sendSuccess(() -> Component.literal("Offline player " + profile.name() + " has been released from jail."), true);
                                    }
                                }
                                return 1;
                            })
                    )
            );
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> saveJailData());
    }

    private void loadKnownPlayers() {
        File userCache = new File("usercache.json");
        if (userCache.exists()) {
            try (FileReader reader = new FileReader(userCache)) {
                JsonArray cacheArray = GSON.fromJson(reader, JsonArray.class);
                if (cacheArray != null) {
                    for (JsonElement element : cacheArray) {
                        if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                            knownPlayerNames.add(element.getAsJsonObject().get("name").getAsString());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean hasAdminPermission(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            String playerName = player.getName().getString();
            for (String opName : source.getServer().getPlayerList().getOpNames()) {
                if (opName.equalsIgnoreCase(playerName)) { return true; }
            }
            String rolesString = config.admin_roles;
            if (rolesString != null && !rolesString.isEmpty()) {
                String[] roles = rolesString.split(",");
                for (String role : roles) {
                    String trimmedRole = role.trim();
                    if (!trimmedRole.equalsIgnoreCase("op") && player.entityTags().contains(trimmedRole)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return true;
    }

    private void registerInteractionListeners() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("entity_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("entity_interaction_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                ItemStack itemStack = player.getItemInHand(hand);
                if (itemStack.is(Items.LAVA_BUCKET) || itemStack.is(Items.WATER_BUCKET)) {
                    serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("bucket_use_denied")), true);
                    return InteractionResult.FAIL;
                }
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("item_use_denied")), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && isPlayerInJail(serverPlayer)) {
                serverPlayer.sendSystemMessage(Component.literal(languageStrings.get("block_break_denied")), true);
                return false;
            }
            return true;
        });
    }

    public static boolean isPlayerInJail(ServerPlayer player) {
        return player != null && jailedPlayers.containsKey(player.getUUID());
    }

    private JailUpdateResult jailPlayer(ServerPlayer player, int timeInSeconds, String reason, String actorName, boolean notifyWebhook) {
        if (actorName == null || actorName.isEmpty()) { actorName = "system"; }
        int addedTicks = timeInSeconds * 20;
        JailData existingData = jailedPlayers.get(player.getUUID());

        if (existingData != null) {
            existingData.remainingTicks += addedTicks;
            existingData.reason = reason;
            existingData.playerName = player.nameAndId().name();

            applyFrozenStats(player, existingData);
            sendTimeAddedMessages(player, existingData, timeInSeconds);
            saveJailData();

            if (notifyWebhook) {
                discordNotifier.sendJailMessage(config, player.getName().getString(), reason, Math.max(0, existingData.remainingTicks / 20), actorName);
            }
            return new JailUpdateResult(true, timeInSeconds, Math.max(0, existingData.remainingTicks / 20));
        }

        BlockPos originalSpawnPos = null;
        ResourceKey<Level> originalSpawnDimension = null;
        var respawn = player.getRespawnConfig();
        if (respawn != null && respawn.respawnData() != null) {
            originalSpawnPos = respawn.respawnData().pos();
            originalSpawnDimension = respawn.respawnData().dimension();
        }
        boolean hadSpawnPoint = originalSpawnPos != null;

        double lastX = player.getX();
        double lastY = player.getY();
        double lastZ = player.getZ();
        float lastYaw = player.getYRot();
        float lastPitch = player.getXRot();
        String lastDimension = player.level().dimension().identifier().toString();

        JailData jailData = new JailData();
        jailData.playerUUID = player.getUUID();
        jailData.playerName = player.nameAndId().name();
        jailData.originalSpawnPos = originalSpawnPos;
        jailData.originalSpawnDimension = originalSpawnDimension;
        jailData.hadSpawnPoint = hadSpawnPoint;
        jailData.reason = reason;
        jailData.remainingTicks = addedTicks;
        jailData.jailedOffline = false;

        jailData.lastX = lastX;
        jailData.lastY = lastY;
        jailData.lastZ = lastZ;
        jailData.lastYaw = lastYaw;
        jailData.lastPitch = lastPitch;
        jailData.lastDimension = lastDimension;
        captureStatSnapshot(player, jailData);

        jailedPlayers.put(player.getUUID(), jailData);
        jailPlayer(player, jailData);
        saveJailData();

        if (notifyWebhook) {
            discordNotifier.sendJailMessage(config, player.getName().getString(), reason, Math.max(0, jailData.remainingTicks / 20), actorName);
        }

        return new JailUpdateResult(false, 0, Math.max(0, jailData.remainingTicks / 20));
    }

    private JailUpdateResult jailOfflinePlayer(NameAndId profile, int timeInSeconds, String reason, String actorName, boolean notifyWebhook) {
        if (actorName == null || actorName.isEmpty()) { actorName = "system"; }
        int addedTicks = timeInSeconds * 20;
        JailData existingData = jailedPlayers.get(profile.id());

        if (existingData != null) {
            existingData.remainingTicks += addedTicks;
            existingData.reason = reason;
            existingData.playerName = profile.name();
            saveJailData();
            if (notifyWebhook) {
                discordNotifier.sendJailMessage(config, profile.name(), reason, Math.max(0, existingData.remainingTicks / 20), actorName);
            }
            return new JailUpdateResult(true, timeInSeconds, Math.max(0, existingData.remainingTicks / 20));
        }

        JailData jailData = new JailData();
        jailData.playerUUID = profile.id();
        jailData.playerName = profile.name();
        jailData.reason = reason;
        jailData.remainingTicks = addedTicks;
        jailData.jailedOffline = true;

        jailedPlayers.put(profile.id(), jailData);
        saveJailData();

        if (notifyWebhook) {
            discordNotifier.sendJailMessage(config, profile.name(), reason, Math.max(0, jailData.remainingTicks / 20), actorName);
        }

        return new JailUpdateResult(false, 0, Math.max(0, jailData.remainingTicks / 20));
    }

    private void jailPlayer(ServerPlayer player, JailData jailData) {
        ServerLevel world = (ServerLevel) player.level();
        BlockPos jailPos = new BlockPos(config.jail_position.x, config.jail_position.y, config.jail_position.z);
        player.teleportTo(world.getServer().getLevel(Level.OVERWORLD), jailPos.getX() + 0.5, jailPos.getY(), jailPos.getZ() + 0.5,
                EnumSet.noneOf(Relative.class), player.getYRot(), player.getXRot(), false);

        applyFrozenStats(player, jailData);

        String messageToPlayer = languageStrings.get("jail_player")
                .replace("{time}", String.valueOf(jailData.remainingTicks / 20))
                .replace("{reason}", jailData.reason);
        player.sendSystemMessage(Component.literal(messageToPlayer), false);

        String jailMessage = languageStrings.get("jail_broadcast")
                .replace("{player}", player.getName().getString())
                .replace("{time}", String.valueOf(jailData.remainingTicks / 20))
                .replace("{reason}", jailData.reason);
        serverInstance.getPlayerList().broadcastSystemMessage(Component.literal(jailMessage), false);
    }

    private void unjailPlayer(ServerPlayer player, boolean isManual, String actorName) {
        JailData jailData = jailedPlayers.remove(player.getUUID());

        if (jailData != null) {
            ServerLevel world = (ServerLevel) player.level();

            if (config.return_to_last_location && jailData.lastDimension != null) {
                ServerLevel targetWorld = null;
                Identifier lastDimensionId = Identifier.tryParse(jailData.lastDimension);
                if (lastDimensionId != null) {
                    targetWorld = serverInstance.getLevel(ResourceKey.create(Registries.DIMENSION, lastDimensionId));
                }
                if (targetWorld == null) { targetWorld = world; }
                player.teleportTo(targetWorld, jailData.lastX, jailData.lastY, jailData.lastZ,
                        EnumSet.noneOf(Relative.class), jailData.lastYaw, jailData.lastPitch, false);
            } else if (config.use_previous_position && jailData.hadSpawnPoint) {
                ServerLevel spawnWorld = world;
                if (jailData.originalSpawnDimension != null) {
                    ServerLevel configuredSpawnWorld = serverInstance.getLevel(jailData.originalSpawnDimension);
                    if (configuredSpawnWorld != null) { spawnWorld = configuredSpawnWorld; }
                }
                player.teleportTo(spawnWorld, jailData.originalSpawnPos.getX(), jailData.originalSpawnPos.getY(),
                        jailData.originalSpawnPos.getZ(), EnumSet.noneOf(Relative.class), player.getYRot(),
                        player.getXRot(), false);
            } else {
                BlockPos releasePos = new BlockPos(config.release_position.x, config.release_position.y, config.release_position.z);
                player.teleportTo(world.getServer().getLevel(Level.OVERWORLD), releasePos.getX() + 0.5, releasePos.getY(), releasePos.getZ() + 0.5,
                        EnumSet.noneOf(Relative.class), player.getYRot(), player.getXRot(), false);
            }

            restoreStatsAfterJail(player, jailData);

            if (isManual) {
                String messageToPlayer = languageStrings.get("unjail_player_manual");
                player.sendSystemMessage(Component.literal(messageToPlayer), false);
                String broadcastMessage = languageStrings.get("unjail_broadcast_manual").replace("{player}", player.getName().getString());
                serverInstance.getPlayerList().broadcastSystemMessage(Component.literal(broadcastMessage), false);
                discordNotifier.sendUnjailMessage(config, player.getName().getString(), jailData.reason, actorName, true);
            } else {
                String messageToPlayer = languageStrings.get("unjail_player_auto");
                player.sendSystemMessage(Component.literal(messageToPlayer), false);
                String broadcastMessage = languageStrings.get("unjail_broadcast_auto").replace("{player}", player.getName().getString());
                serverInstance.getPlayerList().broadcastSystemMessage(Component.literal(broadcastMessage), false);
                discordNotifier.sendUnjailMessage(config, player.getName().getString(), jailData.reason, actorName, false);
            }

            saveJailData();
        }
    }

    private void captureStatSnapshot(ServerPlayer player, JailData jailData) {
        jailData.savedHealth = player.getHealth();
        var hunger = player.getFoodData();
        jailData.savedFoodLevel = hunger.getFoodLevel();
        jailData.savedSaturationLevel = hunger.getSaturationLevel();
        jailData.savedAir = player.getAirSupply();
        jailData.savedAbsorption = player.getAbsorptionAmount();
        jailData.savedXpLevel = player.experienceLevel;
        jailData.savedXpProgress = player.experienceProgress;
        jailData.savedTotalXp = player.totalExperience;
        jailData.savedInvulnerable = player.getAbilities().invulnerable;
        jailData.savedFireTicks = player.getRemainingFireTicks();
        jailData.hasStatSnapshot = true;
        captureEffectSnapshot(player, jailData);
    }

    private void applyFrozenStats(ServerPlayer player, JailData jailData) {
        if (!jailData.hasStatSnapshot) {
            captureStatSnapshot(player, jailData);
            saveJailData();
        }

        if (!player.getAbilities().invulnerable) {
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
        }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) { targetHealth = player.getMaxHealth(); }
        player.setHealth(targetHealth);

        var hunger = player.getFoodData();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturation(jailData.savedSaturationLevel);

        player.setAirSupply(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);
        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        if (player.getRemainingFireTicks() != 0) { player.setRemainingFireTicks(0); }
        applyFrozenEffects(player, jailData);
    }

    private void restoreStatsAfterJail(ServerPlayer player, JailData jailData) {
        if (!jailData.hasStatSnapshot) { return; }

        float targetHealth = jailData.savedHealth;
        if (targetHealth > player.getMaxHealth()) { targetHealth = player.getMaxHealth(); }
        player.setHealth(targetHealth);

        var hunger = player.getFoodData();
        hunger.setFoodLevel(jailData.savedFoodLevel);
        hunger.setSaturation(jailData.savedSaturationLevel);

        player.setAirSupply(jailData.savedAir);
        player.setAbsorptionAmount(jailData.savedAbsorption);
        player.experienceLevel = jailData.savedXpLevel;
        player.experienceProgress = jailData.savedXpProgress;
        player.totalExperience = jailData.savedTotalXp;

        player.getAbilities().invulnerable = jailData.savedInvulnerable;
        player.onUpdateAbilities();

        if (jailData.savedFireTicks > 0) {
            player.setRemainingFireTicks(jailData.savedFireTicks);
        } else if (player.getRemainingFireTicks() != 0) {
            player.setRemainingFireTicks(0);
        }
        restoreEffectsAfterJail(player, jailData);
    }

    private void captureEffectSnapshot(ServerPlayer player, JailData jailData) {
        jailData.savedEffects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            Holder<MobEffect> effectType = effect.getEffect();
            var effectKey = effectType.unwrapKey().orElse(null);
            if (effectKey == null) { continue; }
            MobEffectSnapshot snapshot = new MobEffectSnapshot();
            snapshot.effectId = effectKey.identifier().toString();
            snapshot.duration = effect.getDuration();
            snapshot.amplifier = effect.getAmplifier();
            snapshot.ambient = effect.isAmbient();
            snapshot.showParticles = effect.isVisible();
            snapshot.showIcon = effect.showIcon();
            jailData.savedEffects.add(snapshot);
        }
    }

    private void applyFrozenEffects(ServerPlayer player, JailData jailData) {
        if (jailData.savedEffects == null) {
            captureEffectSnapshot(player, jailData);
            saveJailData();
        }
        if (jailData.savedEffects == null) { return; }

        Set<Identifier> snapshotIds = new HashSet<>();
        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId != null) {
                Identifier snapshotId = Identifier.tryParse(snapshot.effectId);
                if (snapshotId != null) { snapshotIds.add(snapshotId); }
            }
        }

        if (!player.getActiveEffects().isEmpty()) {
            List<MobEffectInstance> currentEffects = new ArrayList<>(player.getActiveEffects());
            for (MobEffectInstance current : currentEffects) {
                Holder<MobEffect> currentType = current.getEffect();
                var currentKey = currentType.unwrapKey().orElse(null);
                Identifier currentId = currentKey != null ? currentKey.identifier() : null;
                if (currentId == null || !snapshotIds.contains(currentId)) {
                    player.removeEffect(currentType);
                }
            }
        }

        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) { continue; }
            Identifier effectId = Identifier.tryParse(snapshot.effectId);
            if (effectId == null) { continue; }
            Holder<MobEffect> statusEffect = BuiltInRegistries.MOB_EFFECT.get(effectId).orElse(null);
            if (statusEffect == null) { continue; }
            MobEffectInstance instance = new MobEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addEffect(instance);
        }
    }

    private void restoreEffectsAfterJail(ServerPlayer player, JailData jailData) {
        if (jailData.savedEffects == null) { return; }
        player.removeAllEffects();
        for (MobEffectSnapshot snapshot : jailData.savedEffects) {
            if (snapshot.effectId == null) { continue; }
            Identifier effectId = Identifier.tryParse(snapshot.effectId);
            if (effectId == null) { continue; }
            Holder<MobEffect> statusEffect = BuiltInRegistries.MOB_EFFECT.get(effectId).orElse(null);
            if (statusEffect == null) { continue; }
            MobEffectInstance instance = new MobEffectInstance(statusEffect, snapshot.duration,
                    snapshot.amplifier, snapshot.ambient, snapshot.showParticles, snapshot.showIcon);
            player.addEffect(instance);
        }
    }

    private void sendTimeAddedMessages(ServerPlayer player, JailData jailData, int addedSeconds) {
        int remainingSeconds = Math.max(0, jailData.remainingTicks / 20);
        String messageToPlayer = languageStrings.get("jail_time_added_player")
                .replace("{added}", String.valueOf(addedSeconds))
                .replace("{time}", String.valueOf(remainingSeconds))
                .replace("{reason}", jailData.reason);
        player.sendSystemMessage(Component.literal(messageToPlayer), false);

        String broadcastMessage = languageStrings.get("jail_time_added_broadcast")
                .replace("{player}", player.getName().getString())
                .replace("{added}", String.valueOf(addedSeconds))
                .replace("{time}", String.valueOf(remainingSeconds))
                .replace("{reason}", jailData.reason);
        serverInstance.getPlayerList().broadcastSystemMessage(Component.literal(broadcastMessage), false);
    }

    private void releasePlayer(UUID playerUUID) {
        ServerPlayer player = serverInstance.getPlayerList().getPlayer(playerUUID);
        if (player != null) {
            unjailPlayer(player, false, "system");
        }
    }

    private void loadJailData() {
        if (JAIL_DATA_FILE.exists()) {
            try (FileReader reader = new FileReader(JAIL_DATA_FILE)) {
                JailData[] loadedData = GSON.fromJson(reader, JailData[].class);
                if (loadedData != null) {
                    for (JailData data : loadedData) {
                        jailedPlayers.put(data.playerUUID, data);
                    }
                }
                System.out.println("Jail status loaded.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void saveJailData() {
        if (!JAIL_DATA_FILE.getParentFile().exists()) {
            JAIL_DATA_FILE.getParentFile().mkdirs();
        }
        try (FileWriter writer = new FileWriter(JAIL_DATA_FILE)) {
            GSON.toJson(jailedPlayers.values().toArray(new JailData[0]), writer);
            System.out.println("Jail status saved.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        if (!CONFIG_FILE.getParentFile().exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
        }

        if (TOML_CONFIG_FILE.exists()) {
            configFormat = ConfigFormat.TOML;
            config = loadTomlConfig(TOML_CONFIG_FILE);
            if (config == null) { config = new Config(); }
            saveConfig();
            System.out.println("Configuration loaded and patched: " + TOML_CONFIG_FILE.getAbsolutePath());
            return;
        }

        configFormat = ConfigFormat.JSON;
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                StringBuilder jsonContent = new StringBuilder();
                int i;
                while ((i = reader.read()) != -1) { jsonContent.append((char) i); }

                Config loadedConfig = GSON.fromJson(jsonContent.toString(), Config.class);
                Config defaultConfig = new Config();

                if (loadedConfig != null) {
                    if (loadedConfig._config_guide == null) loadedConfig._config_guide = defaultConfig._config_guide;
                    if (loadedConfig.release_position == null) loadedConfig.release_position = defaultConfig.release_position;
                    if (loadedConfig.jail_position == null) loadedConfig.jail_position = defaultConfig.jail_position;
                    if (!jsonContent.toString().contains("return_to_last_location")) {
                        loadedConfig.return_to_last_location = defaultConfig.return_to_last_location;
                    }
                    if (!jsonContent.toString().contains("discord_webhook_url")) {
                        loadedConfig.discord_webhook_url = defaultConfig.discord_webhook_url;
                    }
                    if (jsonContent.toString().contains("use_banhammer_webhook")) {
                        loadedConfig.useBanhammerWebhook = extractBooleanFromJson(jsonContent.toString(), "use_banhammer_webhook", defaultConfig.useBanhammerWebhook);
                    } else if (jsonContent.toString().contains("sendJailMessage")) {
                        loadedConfig.useBanhammerWebhook = extractBooleanFromJson(jsonContent.toString(), "sendJailMessage", defaultConfig.useBanhammerWebhook);
                    } else {
                        loadedConfig.useBanhammerWebhook = defaultConfig.useBanhammerWebhook;
                    }
                } else {
                    loadedConfig = defaultConfig;
                }

                config = loadedConfig;
                saveConfig();
                System.out.println("Configuration loaded and patched: " + CONFIG_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            config = new Config();
            saveConfig();
            System.out.println("Default config file created: " + CONFIG_FILE.getAbsolutePath());
        }
    }

    private void loadLanguage() {
        if (!LANGUAGE_FILE.getParentFile().exists()) { LANGUAGE_FILE.getParentFile().mkdirs(); }

        if (LANGUAGE_FILE.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(LANGUAGE_FILE))) {
                languageStrings.clear();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        languageStrings.put(parts[0].trim(), parts[1].trim());
                    }
                }
                if (ensureLanguageDefaults()) { saveLanguage(); }
                System.out.println("Language file loaded: " + LANGUAGE_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            createDefaultLanguageFile();
            System.out.println("Default language file created: " + LANGUAGE_FILE.getAbsolutePath());
        }
    }

    private void createDefaultLanguageFile() {
        languageStrings.clear();
        ensureLanguageDefaults();
        saveLanguage();
    }

    private boolean ensureLanguageDefaults() {
        boolean updated = false;
        updated |= ensureLanguageKey("jail_player", "You have been jailed for {time} seconds! Reason: {reason}");
        updated |= ensureLanguageKey("jail_broadcast", "{player} has been jailed for {time} seconds. Reason: {reason}");
        updated |= ensureLanguageKey("unjail_player_manual", "You have been manually released from jail!");
        updated |= ensureLanguageKey("unjail_broadcast_manual", "{player} has been manually released from jail!");
        updated |= ensureLanguageKey("unjail_player_auto", "You have been released after serving your sentence.");
        updated |= ensureLanguageKey("unjail_broadcast_auto", "{player} has been released after serving their sentence.");
        updated |= ensureLanguageKey("block_interaction_denied", "You cannot interact with blocks while in jail!");
        updated |= ensureLanguageKey("entity_interaction_denied", "You cannot interact with entities while in jail!");
        updated |= ensureLanguageKey("bucket_use_denied", "You cannot use lava or water buckets while in jail!");
        updated |= ensureLanguageKey("item_use_denied", "You cannot use items while in jail!");
        updated |= ensureLanguageKey("block_break_denied", "You cannot break blocks while in jail!");
        updated |= ensureLanguageKey("jail_info_message", "You are in jail for another {time} seconds. Reason: {reason}.");
        updated |= ensureLanguageKey("not_in_jail_message", "You are not in jail!");
        updated |= ensureLanguageKey("jail_time_added_player", "Your jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}");
        updated |= ensureLanguageKey("jail_time_added_broadcast", "{player}'s jail time has been extended by {added} seconds. Remaining: {time} seconds. Reason: {reason}");
        return updated;
    }

    private boolean ensureLanguageKey(String key, String value) {
        if (!languageStrings.containsKey(key)) {
            languageStrings.put(key, value);
            return true;
        }
        return false;
    }

    private void saveLanguage() {
        try (FileWriter writer = new FileWriter(LANGUAGE_FILE)) {
            for (Map.Entry<String, String> entry : languageStrings.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            System.out.println("Language file saved: " + LANGUAGE_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Config loadTomlConfig(File tomlFile) {
        Config loadedConfig = new Config();
        try (BufferedReader reader = new BufferedReader(new FileReader(tomlFile))) {
            String line;
            String currentSection = "";
            boolean inMultiline = false;
            StringBuilder multilineValue = new StringBuilder();
            String multilineKey = null;
            String multilineSection = "";

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inMultiline) {
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) { continue; }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                        continue;
                    }
                    int equalsIndex = trimmed.indexOf('=');
                    if (equalsIndex < 0) { continue; }

                    String key = trimmed.substring(0, equalsIndex).trim();
                    String rawValue = trimmed.substring(equalsIndex + 1).trim();

                    if (rawValue.startsWith("\"\"\"")) {
                        String remainder = rawValue.substring(3);
                        int endIndex = remainder.indexOf("\"\"\"");
                        if (endIndex >= 0) {
                            String value = remainder.substring(0, endIndex);
                            applyTomlValue(loadedConfig, currentSection, key, value, true);
                        } else {
                            inMultiline = true;
                            multilineKey = key;
                            multilineSection = currentSection;
                            multilineValue.setLength(0);
                            multilineValue.append(remainder);
                        }
                        continue;
                    }
                    rawValue = stripTomlComment(rawValue);
                    applyTomlValue(loadedConfig, currentSection, key, rawValue, false);
                } else {
                    int endIndex = trimmed.indexOf("\"\"\"");
                    if (endIndex >= 0) {
                        multilineValue.append("\n").append(trimmed, 0, endIndex);
                        applyTomlValue(loadedConfig, multilineSection, multilineKey, multilineValue.toString(), true);
                        inMultiline = false;
                        multilineKey = null;
                        multilineSection = "";
                        multilineValue.setLength(0);
                    } else {
                        multilineValue.append("\n").append(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return loadedConfig;
    }

    private void applyTomlValue(Config loadedConfig, String section, String key, String rawValue, boolean isMultiline) {
        if (loadedConfig == null || key == null) { return; }
        String effectiveSection = section == null ? "" : section.trim();
        String effectiveKey = key.trim();
        if (effectiveSection.isEmpty() && effectiveKey.contains(".")) {
            int dotIndex = effectiveKey.indexOf('.');
            effectiveSection = effectiveKey.substring(0, dotIndex).trim();
            effectiveKey = effectiveKey.substring(dotIndex + 1).trim();
        }

        if (effectiveSection.isEmpty()) {
            switch (effectiveKey) {
                case "_config_guide" -> loadedConfig._config_guide = isMultiline ? rawValue : parseTomlString(rawValue);
                case "admin_roles" -> loadedConfig.admin_roles = parseTomlString(rawValue);
                case "use_previous_position" -> loadedConfig.use_previous_position = parseTomlBoolean(rawValue, loadedConfig.use_previous_position);
                case "return_to_last_location" -> loadedConfig.return_to_last_location = parseTomlBoolean(rawValue, loadedConfig.return_to_last_location);
                case "discord_webhook_url" -> loadedConfig.discord_webhook_url = parseTomlString(rawValue);
                case "sendJailMessage" -> loadedConfig.useBanhammerWebhook = parseTomlBoolean(rawValue, loadedConfig.useBanhammerWebhook);
                case "use_banhammer_webhook" -> loadedConfig.useBanhammerWebhook = parseTomlBoolean(rawValue, loadedConfig.useBanhammerWebhook);
                default -> { }
            }
            return;
        }

        switch (effectiveSection) {
            case "release_position" -> applyTomlPosition(loadedConfig.release_position, effectiveKey, rawValue);
            case "jail_position" -> applyTomlPosition(loadedConfig.jail_position, effectiveKey, rawValue);
            default -> { }
        }
    }

    private void applyTomlPosition(Config.Position position, String key, String rawValue) {
        if (position == null || key == null) { return; }
        int value = parseTomlInt(rawValue, 0);
        switch (key) {
            case "x" -> position.x = value;
            case "y" -> position.y = value;
            case "z" -> position.z = value;
            default -> { }
        }
    }

    private String stripTomlComment(String rawValue) {
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < rawValue.length(); i++) {
            char c = rawValue.charAt(i);
            if (inQuotes) {
                if (c == '\\') { i++; } else if (c == quoteChar) { inQuotes = false; }
            } else {
                if (c == '"' || c == '\'') { inQuotes = true; quoteChar = c; } else if (c == '#') { return rawValue.substring(0, i).trim(); }
            }
        }
        return rawValue.trim();
    }

    private String parseTomlString(String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        trimmed = trimmed.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
        return trimmed;
    }

    private boolean parseTomlBoolean(String rawValue, boolean fallback) {
        if (rawValue == null) { return fallback; }
        String trimmed = rawValue.trim();
        if ("true".equalsIgnoreCase(trimmed)) { return true; }
        if ("false".equalsIgnoreCase(trimmed)) { return false; }
        return fallback;
    }

    private int parseTomlInt(String rawValue, int fallback) {
        if (rawValue == null) { return fallback; }
        String cleaned = rawValue.trim().replace("_", "");
        try { return Integer.parseInt(cleaned); } catch (NumberFormatException e) { return fallback; }
    }

    private String escapeTomlString(String value) {
        if (value == null) { return ""; }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    private void saveTomlConfig() {
        Config defaultConfig = new Config();
        if (config.release_position == null) { config.release_position = defaultConfig.release_position; }
        if (config.jail_position == null) { config.jail_position = defaultConfig.jail_position; }

        try (FileWriter writer = new FileWriter(TOML_CONFIG_FILE)) {
            writer.write("_config_guide = \"" + escapeTomlString(config._config_guide) + "\"\n");
            writer.write("admin_roles = \"" + escapeTomlString(config.admin_roles) + "\"\n");
            writer.write("use_previous_position = " + config.use_previous_position + "\n");
            writer.write("return_to_last_location = " + config.return_to_last_location + "\n\n");
            writer.write("discord_webhook_url = \"" + escapeTomlString(config.discord_webhook_url) + "\"\n\n");
            writer.write("use_banhammer_webhook = " + config.useBanhammerWebhook + "\n\n");

            writer.write("[release_position]\n");
            writer.write("x = " + config.release_position.x + "\n");
            writer.write("y = " + config.release_position.y + "\n");
            writer.write("z = " + config.release_position.z + "\n\n");

            writer.write("[jail_position]\n");
            writer.write("x = " + config.jail_position.x + "\n");
            writer.write("y = " + config.jail_position.y + "\n");
            writer.write("z = " + config.jail_position.z + "\n");

            System.out.println("Configuration saved: " + TOML_CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveConfig() {
        if (configFormat == ConfigFormat.TOML) {
            saveTomlConfig();
            return;
        }

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
            System.out.println("Configuration saved: " + CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean extractBooleanFromJson(String json, String key, boolean fallback) {
        try {
            Map<?, ?> root = GSON.fromJson(json, Map.class);
            if (root == null) { return fallback; }
            Object value = root.get(key);
            if (value instanceof Boolean bool) { return bool; }
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}