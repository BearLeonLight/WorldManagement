package io.github.bearl.worldmanagement.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MessageServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsConfiguredLocaleAsMiniMessageComponent() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages_zh_TW.yml"), "command:\n  greeting: '<green>世界 <world></green>'\n");
        final String bundled = "command:\n  greeting: '<green>世界 <world></green>'\n";

        final MessageService messages = MessageService.load(temporaryDirectory, LocaleService.normalize("zh-TW"),
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)), warning -> { });

        assertEquals(
            MiniMessage.miniMessage().deserialize("<green>世界 creative</green>"),
            messages.componentOrDefault("command.greeting", "fallback", "world", "creative")
        );
    }

    @Test
    void insertsDynamicValuesWithoutParsingMiniMessageTags() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages_zh_TW.yml"), "command:\n  greeting: '<green>世界 <world></green>'\n");
        final String bundled = "command:\n  greeting: '<green>世界 <world></green>'\n";

        final MessageService messages = MessageService.load(temporaryDirectory, LocaleService.normalize("zh-TW"),
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)), warning -> { });

        assertEquals(
            Component.text("世界 <red>creative</red>", net.kyori.adventure.text.format.NamedTextColor.GREEN),
            messages.componentOrDefault("command.greeting", "fallback", "world", "<red>creative</red>")
        );
    }

    @Test
    void insertsValidatedComponentsWithoutReparsingTheirContent() throws Exception {
        final String bundled = "command:\n  greeting: '<green>世界 <display></green>'\n";
        final MessageService messages = MessageService.load(
            temporaryDirectory,
            "zh_TW",
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)),
            warning -> { }
        );
        final Component display = Component.text("<click:run_command:/op>創意</click>",
            net.kyori.adventure.text.format.NamedTextColor.RED);

        assertEquals(
            Component.text("世界 ", net.kyori.adventure.text.format.NamedTextColor.GREEN).append(display),
            messages.component("command.greeting", Map.of("display", display))
        );
    }

    @Test
    void fallsBackPerKeyToBundledTemplates() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages_zh_TW.yml"), "command:\n  valid: '<green>自訂</green>'\n  invalid: '<red>未閉合'\n");
        final List<String> warnings = new ArrayList<>();
        final String bundled = "command:\n  valid: '<white>預設</white>'\n  invalid: '<yellow>預設錯誤</yellow>'\n  missing: '<aqua>預設缺失</aqua>'\n";

        final MessageService messages = MessageService.load(
            temporaryDirectory,
            LocaleService.normalize("zh-TW"),
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)),
            warnings::add
        );

        assertEquals(MiniMessage.miniMessage().deserialize("<green>自訂</green>"), messages.component("command.valid"));
        assertEquals(MiniMessage.miniMessage().deserialize("<yellow>預設錯誤</yellow>"), messages.component("command.invalid"));
        assertEquals(MiniMessage.miniMessage().deserialize("<aqua>預設缺失</aqua>"), messages.component("command.missing"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("messages_zh_TW.yml")
            && warning.contains("command.invalid") && warning.contains("<red>未閉合")));
        assertTrue(Files.readString(temporaryDirectory.resolve("messages_zh_TW.yml")).contains("missing: <aqua>預設缺失</aqua>"));
        assertTrue(Files.readString(temporaryDirectory.resolve("messages_zh_TW.yml")).contains("valid: <green>自訂</green>"));
    }

    @Test
    void createsConfiguredLocaleFromBundledMessagesWhenMissing() throws Exception {
        final String bundled = "command:\n  greeting: '<green>世界</green>'\n";

        MessageService.load(
            temporaryDirectory,
            "zh_TW",
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)),
            warning -> { }
        );

        assertTrue(Files.readString(temporaryDirectory.resolve("messages_zh_TW.yml")).contains("greeting: <green>世界</green>"));
    }

    @Test
    void fallsBackWhenConfiguredTemplateUsesUnknownPlaceholder() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages_zh_TW.yml"), "command:\n  greeting: '<green>世界 <wrold></green>'\n");
        final List<String> warnings = new ArrayList<>();
        final String bundled = "command:\n  greeting: '<green>世界 <world></green>'\n";

        final MessageService messages = MessageService.load(
            temporaryDirectory,
            "zh_TW",
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)),
            warnings::add
        );

        assertEquals(
            MiniMessage.miniMessage().deserialize("<green>世界 creative</green>"),
            messages.component("command.greeting", "world", "creative")
        );
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("wrold")));
    }

    @Test
    void acceptsResetAsAStandardMiniMessageTag() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages_zh_TW.yml"), "command:\n  status: '<red>錯誤<reset>正常'\n");
        final List<String> warnings = new ArrayList<>();
        final String bundled = "command:\n  status: '<yellow>預設</yellow>'\n";

        final MessageService messages = MessageService.load(
            temporaryDirectory,
            "zh_TW",
            new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8)),
            warnings::add
        );

        assertEquals(MiniMessage.miniMessage().deserialize("<red>錯誤<reset>正常"), messages.component("command.status"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void validatesTheBundledLocaleWithoutFallbacks() throws Exception {
        final Path configured = temporaryDirectory.resolve("messages_zh_TW.yml");
        try (InputStream resource = MessageServiceTest.class.getResourceAsStream("/messages_zh_TW.yml")) {
            Files.copy(resource, configured);
        }
        final List<String> warnings = new ArrayList<>();

        try (InputStream bundled = MessageServiceTest.class.getResourceAsStream("/messages_zh_TW.yml")) {
            final MessageService messages = MessageService.load(temporaryDirectory, "zh_TW", bundled, warnings::add);

            assertEquals(List.of(), warnings);
            assertEquals(
                MiniMessage.miniMessage().deserialize("<dark_gray>[<aqua>WorldManagement</aqua>]</dark_gray> <yellow>WorldManagement 正在載入資料。</yellow>"),
                messages.component("command.loading")
            );
        }
    }
}