package me.hyposelenia.shorestylish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-side entry point for ShoreStylish. */
public final class ShoreStylish implements ModInitializer {
    public static final String MOD_ID = "shorestylish";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Map<UUID, String> NICKNAMES = new ConcurrentHashMap<>();
    private static final Type NICKNAME_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final Pattern ITEM_TOKEN = Pattern.compile("\\[(?:i|item)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private static final Identifier FORMAT_PHASE = Identifier.fromNamespaceAndPath(MOD_ID, "format_chat");
    private static final Map<String, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry("0", "black"), Map.entry("1", "dark_blue"), Map.entry("2", "dark_green"),
            Map.entry("3", "dark_aqua"), Map.entry("4", "dark_red"), Map.entry("5", "dark_purple"),
            Map.entry("6", "gold"), Map.entry("7", "gray"), Map.entry("8", "dark_gray"),
            Map.entry("9", "blue"), Map.entry("a", "green"), Map.entry("b", "aqua"),
            Map.entry("c", "red"), Map.entry("d", "light_purple"), Map.entry("e", "yellow"),
            Map.entry("f", "white"), Map.entry("k", "obfuscated"), Map.entry("l", "bold"),
            Map.entry("m", "strikethrough"), Map.entry("n", "underlined"), Map.entry("o", "italic"),
            Map.entry("r", "reset"));
    private static Config config;

    @Override
    public void onInitialize() {
        config = loadConfig();
        loadNicknames();
        registerNickCommand();
        // Chat mods may cancel this event. Run before the default phase so our replacement is applied first.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(FORMAT_PHASE, Event.DEFAULT_PHASE);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(FORMAT_PHASE, (message, sender, params) -> {
            String source = message.signedContent();
            if (!config.enable_chat_format && (!config.enable_message_format || !containsFormatting(source))) return true;
            ItemStack heldItem = sender.getMainHandItem().copy();
            Component chatMessage = config.enable_message_format
                    ? formatPlayerMessage(source, heldItem)
                    : linkify(message.decoratedContent());
            sender.level().getServer().getPlayerList().broadcastSystemMessage(
                    formatChat(sender, chatMessage), false);
            return false;
        });
    }

    private static void registerNickCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("nick")
                        .executes(ShoreStylish::showNickHelp)
                        .then(Commands.literal("reset")
                                .executes(ShoreStylish::clearOwnNickname)
                                .then(Commands.argument("playername", StringArgumentType.word())
                                        .executes(ShoreStylish::clearOtherNickname)))
                        .then(Commands.argument("nickname", StringArgumentType.greedyString())
                                .executes(ShoreStylish::setNickname))));
    }

    private static int setNickname(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Only players can have a nickname."));
            return 0;
        }
        if (!canUseNick(context.getSource())) {
            context.getSource().sendFailure(Component.literal("You do not have permission to change your nickname."));
            return 0;
        }
        String nickname = StringArgumentType.getString(context, "nickname").trim();
        if (nickname.isEmpty()) return showNickHelp(context);
        NICKNAMES.put(player.getUUID(), nickname);
        saveNicknames();
        context.getSource().sendSuccess(() -> Component.literal("Your nickname is now ")
                .append(displayName(player)), false);
        return 1;
    }

    private static int showNickHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "This command allows you to set a custom display nickname when chatting.\n")
                .append(Component.literal("You can use "))
                .append(Component.literal("https://www.birdflop.com/resources/rgb/")
                        .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(
                                URI.create("https://www.birdflop.com/resources/rgb/")))))
                .append(Component.literal(" to create gradient nicknames (choose MiniMessage format!).")), false);
        return 1;
    }

    private static int clearOwnNickname(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || !canUseNick(context.getSource())) {
            context.getSource().sendFailure(Component.literal("You do not have permission to change your nickname."));
            return 0;
        }
        NICKNAMES.remove(player.getUUID());
        saveNicknames();
        context.getSource().sendSuccess(() -> Component.literal("Your nickname has been reset."), false);
        return 1;
    }

    private static int clearOtherNickname(CommandContext<CommandSourceStack> context) {
        if (!config.enable_nicknames || !context.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            context.getSource().sendFailure(Component.literal("You do not have permission to reset another player's nickname."));
            return 0;
        }
        String playerName = StringArgumentType.getString(context, "playername");
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            context.getSource().sendFailure(Component.literal("That player must be online to reset their nickname."));
            return 0;
        }
        if (NICKNAMES.remove(player.getUUID()) == null) {
            context.getSource().sendFailure(Component.literal(player.getName().getString() + " does not have a nickname."));
            return 0;
        }
        saveNicknames();
        context.getSource().sendSuccess(() -> Component.literal("Reset " + player.getName().getString() + "'s nickname."), false);
        return 1;
    }

    private static boolean canUseNick(CommandSourceStack source) {
        return config.enable_nicknames && (config.nickname_permission == NicknamePermission.EVERYONE
                || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
    }

    private static Component displayName(ServerPlayer player) {
        String nickname = NICKNAMES.get(player.getUUID());
        if (nickname == null) return player.getDisplayName();
        return Component.empty()
                .setStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal(player.getName().getString()))))
                .append(toMinecraft(format(nickname)));
    }

    private static boolean containsFormatting(String text) {
        return text.indexOf('&') >= 0 || text.indexOf('<') >= 0;
    }

    private static net.kyori.adventure.text.Component format(String input) {
        return MINI_MESSAGE.deserialize(translateAmpersandCodes(input));
    }

    private static Component formatChat(ServerPlayer player, Component chatMessage) {
        if (!config.enable_chat_format) {
            return Component.translatable("chat.type.text", displayName(player), chatMessage);
        }
        String usernameMarker = "SHORESTYLISH_USERNAME";
        String messageMarker = "SHORESTYLISH_CHAT_MESSAGE";
        Component template = toMinecraft(format(config.chat_format
                .replace("%username%", usernameMarker)
                .replace("%chatmessage%", messageMarker)));
        return replaceMarkers(template, Map.of(usernameMarker, displayName(player), messageMarker, chatMessage));
    }

    private static Component replaceMarkers(Component component, Map<String, Component> replacements) {
        MutableComponent result = Component.empty().setStyle(component.getStyle());
        if (component.getContents() instanceof PlainTextContents text) {
            String content = text.text();
            int position = 0;
            while (position < content.length()) {
                int markerStart = -1;
                String marker = null;
                for (String candidate : replacements.keySet()) {
                    int index = content.indexOf(candidate, position);
                    if (index >= 0 && (markerStart < 0 || index < markerStart)) {
                        markerStart = index;
                        marker = candidate;
                    }
                }
                if (markerStart < 0) break;
                result.append(Component.literal(content.substring(position, markerStart)).setStyle(component.getStyle()));
                result.append(Component.empty().setStyle(component.getStyle()).append(replacements.get(marker)));
                position = markerStart + marker.length();
            }
            result.append(Component.literal(content.substring(position)).setStyle(component.getStyle()));
        } else {
            result.append(MutableComponent.create(component.getContents()).setStyle(component.getStyle()));
        }
        for (Component child : component.getSiblings()) result.append(replaceMarkers(child, replacements));
        return result;
    }

    private static Component formatPlayerMessage(String source, ItemStack heldItem) {
        Matcher matcher = ITEM_TOKEN.matcher(source);
        if (!matcher.find()) return linkify(toMinecraft(format(source)));

        MutableComponent result = Component.empty();
        int position = 0;
        do {
            result.append(linkify(toMinecraft(format(source.substring(position, matcher.start())))));
            result.append(heldItem.isEmpty() ? linkify(toMinecraft(format(matcher.group()))) : itemComponent(heldItem));
            position = matcher.end();
        } while (matcher.find());
        result.append(linkify(toMinecraft(format(source.substring(position)))));
        return result;
    }

    private static Component itemComponent(ItemStack item) {
        return Component.literal("[")
                .append(item.getHoverName().copy().withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(item)))))
                .append("]");
    }

    private static Component linkify(Component component) {
        MutableComponent result = Component.empty().setStyle(component.getStyle());
        if (component.getContents() instanceof PlainTextContents text) {
            Matcher matcher = URL.matcher(text.text());
            int position = 0;
            while (matcher.find()) {
                result.append(Component.literal(text.text().substring(position, matcher.start())).setStyle(component.getStyle()));
                String url = matcher.group();
                try {
                    result.append(Component.literal(url).setStyle(component.getStyle()
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
                } catch (IllegalArgumentException ignored) {
                    result.append(Component.literal(url).setStyle(component.getStyle()));
                }
                position = matcher.end();
            }
            result.append(Component.literal(text.text().substring(position)).setStyle(component.getStyle()));
        } else {
            result.append(MutableComponent.create(component.getContents()).setStyle(component.getStyle()));
        }
        for (Component child : component.getSiblings()) result.append(linkify(child));
        return result;
    }

    private static String translateAmpersandCodes(String text) {
        String hexTranslated = text.replaceAll(
                "(?i)&x&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])",
                "<#$1$2$3$4$5$6>");
        Matcher matcher = LEGACY_CODE.matcher(hexTranslated);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String tag = LEGACY_TAGS.get(matcher.group(1).toLowerCase());
            matcher.appendReplacement(result, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Component toMinecraft(net.kyori.adventure.text.Component component) {
        String content = component instanceof net.kyori.adventure.text.TextComponent textComponent
                ? textComponent.content() : "";
        MutableComponent result = Component.literal(content).setStyle(toMinecraftStyle(component.style()));
        for (net.kyori.adventure.text.Component child : component.children()) {
            result.append(toMinecraft(child));
        }
        return result;
    }

    private static Style toMinecraftStyle(net.kyori.adventure.text.format.Style adventureStyle) {
        Style style = Style.EMPTY;
        if (adventureStyle.color() != null) {
            style = style.withColor(adventureStyle.color().value());
        }
        return style
                .withBold(decorationValue(adventureStyle, net.kyori.adventure.text.format.TextDecoration.BOLD))
                .withItalic(decorationValue(adventureStyle, net.kyori.adventure.text.format.TextDecoration.ITALIC))
                .withUnderlined(decorationValue(adventureStyle, net.kyori.adventure.text.format.TextDecoration.UNDERLINED))
                .withStrikethrough(decorationValue(adventureStyle, net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH))
                .withObfuscated(decorationValue(adventureStyle, net.kyori.adventure.text.format.TextDecoration.OBFUSCATED));
    }

    private static Boolean decorationValue(net.kyori.adventure.text.format.Style style,
                                           net.kyori.adventure.text.format.TextDecoration decoration) {
        return switch (style.decoration(decoration)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> null;
        };
    }

    private static Config loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("shorestylish.json");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        loaded = loaded.normalized();
                        saveConfig(file, loaded);
                        return loaded;
                    }
                }
            }
            Config defaults = new Config();
            saveConfig(file, defaults);
            return defaults;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load ShoreStylish config", exception);
        }
    }

    private static void saveConfig(Path file, Config value) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(value, writer);
        }
    }

    private static void loadNicknames() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("shorestylish-nicknames.json");
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> savedNicknames = GSON.fromJson(reader, NICKNAME_MAP_TYPE);
            if (savedNicknames == null) return;
            for (Map.Entry<String, String> entry : savedNicknames.entrySet()) {
                try {
                    if (entry.getValue() != null) NICKNAMES.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed UUID entries so one stale entry does not prevent startup.
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load ShoreStylish nicknames", exception);
        }
    }

    private static void saveNicknames() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("shorestylish-nicknames.json");
        Map<String, String> savedNicknames = new HashMap<>();
        NICKNAMES.forEach((uuid, nickname) -> savedNicknames.put(uuid.toString(), nickname));
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(savedNicknames, writer);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save ShoreStylish nicknames", exception);
        }
    }

    public static final class Config {
        public boolean enable_message_format = true;
        public boolean enable_chat_format = true;
        public String chat_format = "&7%username%: &f%chatmessage%";
        public boolean enable_nicknames = true;
        public NicknamePermission nickname_permission = NicknamePermission.EVERYONE;
        private Config normalized() {
            if (nickname_permission == null) nickname_permission = NicknamePermission.EVERYONE;
            if (chat_format == null || chat_format.isBlank()) chat_format = "&7%username%: &f%chatmessage%";
            return this;
        }
    }

    public enum NicknamePermission { EVERYONE, OP }
}
