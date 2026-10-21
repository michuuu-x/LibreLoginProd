/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.config;

import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.DEFAULT_CRYPTO_PROVIDER;
import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.NEW_UUID_CREATOR;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.LibreLoginPlugin;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.configuration.CorruptedConfigurationException;
import xyz.kyngs.librelogin.common.config.key.ConfigurationKey;
import xyz.kyngs.librelogin.common.config.migrate.config.*;

public class HoconPluginConfiguration {

    private final Logger logger;
    private final Collection<BiHolder<Class<?>, String>> defaultKeys;
    private ConfigurateHelper helper;

    public HoconPluginConfiguration(
            Logger logger, Collection<BiHolder<Class<?>, String>> defaultKeys) {
        this.logger = logger;
        this.defaultKeys =
                new ArrayList<>(
                        defaultKeys); // Make this independent on the original collection in case it
        // gets modified
        this.defaultKeys.add(
                new BiHolder<>(
                        ConfigurationKeys.class,
                        "")); // Make sure the default configuration keys always have top priority
    }

    public ConfigurateHelper getHelper() {
        return helper;
    }

    public boolean reload(LibreLoginPlugin<?, ?> plugin)
            throws IOException, CorruptedConfigurationException {
        var adept =
                new ConfigurateConfiguration(
                        plugin.getDataFolder(),
                        "config.conf",
                        defaultKeys,
"""
  !!TEN PLIK JEST ZAPISANY W FORMACIE HOCON!!
  Format HOCON jest bardzo podobny do JSON, ale ma kilka dodatkowych funkcji.
  Więcej informacji o tym formacie znajdziesz na wiki Sponge:
  https://docs.spongepowered.org/stable/en/server/getting-started/configuration/hocon.html
  ----------------------------------------------------------------------------------------
  Konfiguracja LibreLogin
  ----------------------------------------------------------------------------------------
  To jest plik konfiguracyjny wtyczki LibreLogin.
  Więcej informacji o LibreLogin znajdziesz na stronie GitHub:
  https://github.com/Navio1430/LibreLoginProd
""",
                        logger,
                        new FirstConfigurationMigrator(),
                        new SecondConfigurationMigrator(),
                        new ThirdConfigurationMigrator(),
                        new FourthConfigurationMigrator(),
                        new FifthConfigurationMigrator(),
                        new SixthConfigurationMigrator(),
                        new SeventhConfigurationMigrator(),
                        new EightConfigurationMigrator());

        var helperAdept = adept.getHelper();

        if (!adept.isNewlyCreated()
                && plugin.getCryptoProvider(helperAdept.get(DEFAULT_CRYPTO_PROVIDER)) == null) {
            throw new CorruptedConfigurationException("Nie znaleziono dostawcy szyfrowania");
        }

        helper = helperAdept;

        return adept.isNewlyCreated();
    }

    public NewUUIDCreator getNewUUIDCreator() {
        var name = get(NEW_UUID_CREATOR);

        try {
            return NewUUIDCreator.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NewUUIDCreator.RANDOM;
        }
    }

    public <T> T get(ConfigurationKey<T> key) {
        return helper.get(key);
    }
}
