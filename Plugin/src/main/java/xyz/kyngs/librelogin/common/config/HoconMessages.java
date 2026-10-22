/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.LibreLoginPlugin;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.configuration.CorruptedConfigurationException;
import xyz.kyngs.librelogin.api.configuration.Messages;
import xyz.kyngs.librelogin.common.config.key.ConfigurationKey;
import xyz.kyngs.librelogin.common.config.migrate.messages.FirstMessagesMigrator;
import xyz.kyngs.librelogin.common.config.migrate.messages.SecondMessagesMigrator;
import xyz.kyngs.librelogin.common.config.migrate.messages.ThirdMessagesMigrator;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

public class HoconMessages implements Messages {

    /** The language of the defaults in {@link MessageKeys}, used when the chosen one is missing. */
    public static final String DEFAULT_LANGUAGE = "pl";

    /**
     * Languages shipped with the plugin. Every one except {@link #DEFAULT_LANGUAGE} has a
     * translation in the {@code lang/<language>.conf} resource.
     */
    public static final List<String> BUNDLED_LANGUAGES = List.of(DEFAULT_LANGUAGE, "en");

    public static final String LANG_FOLDER = "lang";

    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[a-z0-9_-]+");
    private static final String LEGACY_FILE = "messages.conf";
    private static final String HEADER =
"""
  !!TEN PLIK JEST ZAPISANY W FORMACIE HOCON!!
  Format HOCON jest bardzo podobny do JSON, ale ma kilka dodatkowych funkcji.
  Więcej informacji o tym formacie znajdziesz na wiki Sponge:
  https://docs.spongepowered.org/stable/en/server/getting-started/configuration/hocon.html
  ----------------------------------------------------------------------------------------
  Wiadomości LibreLogin
  ----------------------------------------------------------------------------------------
  Ten plik zawiera wszystkie wiadomości używane przez wtyczkę, możesz je dowolnie dostosować do swoich potrzeb.
  Wiadomości są zapisywane w formacie MiniMessage, na przykład: <bold><green>Przeładowano!</green></bold>
  Więcej informacji o formacie znajdziesz tutaj: https://docs.advntr.dev/minimessage/format.html
  Dawny format z kodami kolorów (&a, &l, &#ff8800 itp.) oraz znak § NIE są obsługiwane.
  W każdej wiadomości możesz użyć zmiennej <prefix>, która zostanie zastąpiona wartością klucza "prefix".
  Język wiadomości ustawiasz opcją "language" w pliku config.conf.
  Więcej informacji o LibreLogin znajdziesz na stronie GitHub:
  https://github.com/Navio1430/LibreLoginProd
""";

    private static final MiniMessage SERIALIZER = MiniMessage.builder().build();
    private static final String PREFIX_TAG = "prefix";
    private final Logger logger;
    private final HoconPluginConfiguration configuration;
    private Map<String, TextComponent> messages;
    private ConfigurateConfiguration rawMessages;
    private String language;

    public HoconMessages(Logger logger, HoconPluginConfiguration configuration) {
        this.logger = logger;
        this.configuration = configuration;
        messages = new HashMap<>();
    }

    /**
     * @return the system language if it is bundled with the plugin, otherwise {@link
     *     #DEFAULT_LANGUAGE}
     */
    public static String systemLanguage() {
        var system = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        return BUNDLED_LANGUAGES.contains(system) ? system : DEFAULT_LANGUAGE;
    }

    public Map<String, TextComponent> getMessages() {
        return messages;
    }

    /**
     * @return the language the messages are currently loaded in
     */
    public String getLanguage() {
        return language;
    }

    @Override
    public TextComponent getMessage(String key, String... replacements) {

        var message = messages.get(key);

        if (replacements.length == 0) return message;

        var replaceMap = new HashMap<String, String>();

        String toReplace = null;

        for (int i = 0; i < replacements.length; i++) {
            if (i % 2 != 0) {
                replaceMap.put(toReplace, replacements[i]);
            } else {
                toReplace = replacements[i];
            }
        }

        return GeneralUtil.formatComponent(message, replaceMap);
    }

    @Override
    public void reload(LibreLoginPlugin<?, ?> plugin)
            throws IOException, CorruptedConfigurationException {
        var langFolder = new File(plugin.getDataFolder(), LANG_FOLDER);

        if (!langFolder.isDirectory() && !langFolder.mkdirs())
            throw new IOException("Nie można utworzyć folderu " + LANG_FOLDER + "!");

        migrateLegacyFile(plugin.getDataFolder(), langFolder);

        var newLanguage = resolveLanguage(langFolder);
        var file = new File(langFolder, newLanguage + ".conf");
        var translation = loadTranslation(plugin, newLanguage, file);
        var header = translation == null ? null : translation.options().header();

        var adept =
                new ConfigurateConfiguration(
                        langFolder,
                        file.getName(),
                        Set.of(new BiHolder<>(MessageKeys.class, "")),
                        key -> translation == null ? key : translate(key, translation),
                        header == null ? HEADER : header,
                        logger,
                        new FirstMessagesMigrator(),
                        new SecondMessagesMigrator(),
                        new ThirdMessagesMigrator());

        var rawPrefix = adept.getHelper().getString(MessageKeys.PREFIX.key());
        var prefixResolver =
                Placeholder.component(
                        PREFIX_TAG,
                        rawPrefix == null
                                ? Component.empty()
                                : Component.empty().append(SERIALIZER.deserialize(rawPrefix)));

        var newMessages = new HashMap<String, TextComponent>();
        extractKeys("", adept.getHelper().configuration(), prefixResolver, newMessages);
        messages = newMessages;
        rawMessages = adept;
        language = newLanguage;

        logger.info("Wczytano wiadomości w języku: " + language);
    }

    /**
     * Picks the language from the configuration, or {@link #DEFAULT_LANGUAGE} when there are no
     * messages for it.
     */
    private String resolveLanguage(File langFolder) {
        var requested =
                configuration.get(ConfigurationKeys.LANGUAGE).trim().toLowerCase(Locale.ROOT);

        if (!LANGUAGE_PATTERN.matcher(requested).matches()) {
            logger.warn(
                    "Nieprawidłowy język \"%s\", używam języka domyślnego (%s)"
                            .formatted(requested, DEFAULT_LANGUAGE));
            return DEFAULT_LANGUAGE;
        }

        if (BUNDLED_LANGUAGES.contains(requested)
                || new File(langFolder, requested + ".conf").exists()) {
            return requested;
        }

        logger.warn(
                "Język \"%s\" nie jest dostępny (brak pliku %s/%s.conf), używam języka domyślnego (%s)"
                        .formatted(requested, LANG_FOLDER, requested, DEFAULT_LANGUAGE));
        return DEFAULT_LANGUAGE;
    }

    /**
     * Loads the source of translated defaults and comments: the bundled resource for bundled
     * languages, the file itself for custom ones (so its own comments are kept) and nothing for
     * {@link #DEFAULT_LANGUAGE}, whose defaults are already in {@link MessageKeys}.
     */
    private static @Nullable CommentedConfigurationNode loadTranslation(
            LibreLoginPlugin<?, ?> plugin, String language, File file)
            throws IOException, CorruptedConfigurationException {
        if (language.equals(DEFAULT_LANGUAGE)) return null;

        var builder = HoconConfigurationLoader.builder();

        if (BUNDLED_LANGUAGES.contains(language)) {
            var resource = LANG_FOLDER + "/" + language + ".conf";
            var stream = plugin.getResourceAsStream(resource);

            if (stream == null)
                throw new IOException("Brak zasobu " + resource + " w pliku wtyczki!");

            builder.source(
                    () ->
                            new BufferedReader(
                                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } else {
            builder.file(file);
        }

        try {
            return builder.build().load();
        } catch (ConfigurateException e) {
            throw new CorruptedConfigurationException(e);
        }
    }

    private static <T> ConfigurationKey<T> translate(
            ConfigurationKey<T> key, CommentedConfigurationNode translation) {
        var node = new ConfigurateHelper(translation).resolve(key.key());
        @SuppressWarnings("unchecked")
        var value = key.defaultValue() instanceof String ? (T) node.getString() : null;
        var comment = node.comment();

        return new ConfigurationKey<>(
                key.key(),
                value == null ? key.defaultValue() : value,
                comment == null ? key.comment() : comment,
                key.getter());
    }

    /** Moves messages.conf used before languages were introduced into lang/pl.conf. */
    private void migrateLegacyFile(File dataFolder, File langFolder) throws IOException {
        var legacy = new File(dataFolder, LEGACY_FILE);
        var target = new File(langFolder, DEFAULT_LANGUAGE + ".conf");

        if (!legacy.exists() || target.exists()) return;

        Files.move(legacy.toPath(), target.toPath());
        logger.info(
                "Przeniesiono %s do %s/%s".formatted(LEGACY_FILE, LANG_FOLDER, target.getName()));
    }

    private void extractKeys(
            String prefix,
            CommentedConfigurationNode node,
            TagResolver prefixResolver,
            Map<String, TextComponent> target) {
        node.childrenMap()
                .forEach(
                        (key, value) -> {
                            if (!(key instanceof String str)) return;

                            if (value.childrenMap().isEmpty()) {
                                var string = value.getString();

                                if (string == null) return;

                                target.put(
                                        prefix + str,
                                        Component.empty()
                                                .append(
                                                        SERIALIZER.deserialize(
                                                                string, prefixResolver)));
                            } else {
                                extractKeys(prefix + str + ".", value, prefixResolver, target);
                            }
                        });
    }

    public String getRawMessage(String key) {
        return rawMessages.getHelper().getString(key);
    }

    @Override
    public boolean isEmpty(String key) {
        return getRawMessage(key).equals(" ");
    }
}
