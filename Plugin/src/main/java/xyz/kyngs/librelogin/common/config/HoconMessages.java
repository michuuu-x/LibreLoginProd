/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.config;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.CommentedConfigurationNode;
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.LibreLoginPlugin;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.configuration.CorruptedConfigurationException;
import xyz.kyngs.librelogin.api.configuration.Messages;
import xyz.kyngs.librelogin.common.config.migrate.messages.FirstMessagesMigrator;
import xyz.kyngs.librelogin.common.config.migrate.messages.SecondMessagesMigrator;
import xyz.kyngs.librelogin.common.config.migrate.messages.ThirdMessagesMigrator;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

public class HoconMessages implements Messages {

    private static final MiniMessage SERIALIZER = MiniMessage.builder().build();
    private static final String PREFIX_TAG = "prefix";
    private final Map<String, TextComponent> messages;
    private final Logger logger;
    private ConfigurateConfiguration rawMessages;

    public HoconMessages(Logger logger) {
        this.logger = logger;
        messages = new HashMap<>();
    }

    public Map<String, TextComponent> getMessages() {
        return messages;
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
        var adept =
                new ConfigurateConfiguration(
                        plugin.getDataFolder(),
                        "messages.conf",
                        Set.of(new BiHolder<>(MessageKeys.class, "")),
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
  Więcej informacji o LibreLogin znajdziesz na stronie GitHub:
  https://github.com/Navio1430/LibreLoginProd
""",
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

        extractKeys("", adept.getHelper().configuration(), prefixResolver);
        rawMessages = adept;
    }

    private void extractKeys(
            String prefix, CommentedConfigurationNode node, TagResolver prefixResolver) {
        node.childrenMap()
                .forEach(
                        (key, value) -> {
                            if (!(key instanceof String str)) return;

                            if (value.childrenMap().isEmpty()) {
                                var string = value.getString();

                                if (string == null) return;

                                messages.put(
                                        prefix + str,
                                        Component.empty()
                                                .append(
                                                        SERIALIZER.deserialize(
                                                                string, prefixResolver)));
                            } else {
                                extractKeys(prefix + str + ".", value, prefixResolver);
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
