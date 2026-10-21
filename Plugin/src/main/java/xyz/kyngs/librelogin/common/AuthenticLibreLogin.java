/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common;

import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.*;

import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import net.kyori.adventure.audience.Audience;
import org.bstats.charts.CustomChart;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.LibreLoginPlugin;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.PlatformHandle;
import xyz.kyngs.librelogin.api.configuration.CorruptedConfigurationException;
import xyz.kyngs.librelogin.api.crypto.CryptoProvider;
import xyz.kyngs.librelogin.api.crypto.HashedPassword;
import xyz.kyngs.librelogin.api.database.*;
import xyz.kyngs.librelogin.api.database.connector.DatabaseConnector;
import xyz.kyngs.librelogin.api.database.connector.MySQLDatabaseConnector;
import xyz.kyngs.librelogin.api.database.connector.PostgreSQLDatabaseConnector;
import xyz.kyngs.librelogin.api.database.connector.SQLiteDatabaseConnector;
import xyz.kyngs.librelogin.api.integration.LimboIntegration;
import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.api.server.ServerHandler;
import xyz.kyngs.librelogin.api.totp.TOTPProvider;
import xyz.kyngs.librelogin.api.util.Release;
import xyz.kyngs.librelogin.api.util.SemanticVersion;
import xyz.kyngs.librelogin.api.util.ThrowableFunction;
import xyz.kyngs.librelogin.common.authorization.AuthenticAuthorizationProvider;
import xyz.kyngs.librelogin.common.command.CommandProvider;
import xyz.kyngs.librelogin.common.command.InvalidCommandArgument;
import xyz.kyngs.librelogin.common.config.HoconMessages;
import xyz.kyngs.librelogin.common.config.HoconPluginConfiguration;
import xyz.kyngs.librelogin.common.crypto.Argon2IDCryptoProvider;
import xyz.kyngs.librelogin.common.crypto.BCrypt2ACryptoProvider;
import xyz.kyngs.librelogin.common.crypto.LogITMessageDigestCryptoProvider;
import xyz.kyngs.librelogin.common.crypto.MessageDigestCryptoProvider;
import xyz.kyngs.librelogin.common.database.AuthenticDatabaseProvider;
import xyz.kyngs.librelogin.common.database.AuthenticUser;
import xyz.kyngs.librelogin.common.database.connector.AuthenticMySQLDatabaseConnector;
import xyz.kyngs.librelogin.common.database.connector.AuthenticPostgreSQLDatabaseConnector;
import xyz.kyngs.librelogin.common.database.connector.AuthenticSQLiteDatabaseConnector;
import xyz.kyngs.librelogin.common.database.connector.DatabaseConnectorRegistration;
import xyz.kyngs.librelogin.common.database.provider.LibreLoginMySQLDatabaseProvider;
import xyz.kyngs.librelogin.common.database.provider.LibreLoginPostgreSQLDatabaseProvider;
import xyz.kyngs.librelogin.common.database.provider.LibreLoginSQLiteDatabaseProvider;
import xyz.kyngs.librelogin.common.event.AuthenticEventProvider;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.integration.FloodgateIntegration;
import xyz.kyngs.librelogin.common.integration.luckperms.LuckPermsIntegration;
import xyz.kyngs.librelogin.common.listener.LoginTryListener;
import xyz.kyngs.librelogin.common.log.Log4JFilter;
import xyz.kyngs.librelogin.common.log.SimpleLogFilter;
import xyz.kyngs.librelogin.common.mail.AuthenticEMailHandler;
import xyz.kyngs.librelogin.common.migrate.*;
import xyz.kyngs.librelogin.common.premium.AuthenticPremiumProvider;
import xyz.kyngs.librelogin.common.server.AuthenticServerHandler;
import xyz.kyngs.librelogin.common.totp.AuthenticTOTPProvider;
import xyz.kyngs.librelogin.common.util.CancellableTask;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

public abstract class AuthenticLibreLogin<P, S> implements LibreLoginPlugin<P, S> {

    public static final Gson GSON = new Gson();
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd. MM. yyyy HH:mm");
    public static final ExecutorService EXECUTOR;

    static {
        EXECUTOR = new ForkJoinPool(4);
    }

    private final Map<String, CryptoProvider> cryptoProviders;
    private final Map<String, ReadDatabaseProviderRegistration<?, ?, ?>> readProviders;
    private final Map<Class<?>, DatabaseConnectorRegistration<?, ?>> databaseConnectors;
    private final Multimap<P, CancellableTask> cancelOnExit;
    private final PlatformHandle<P, S> platformHandle;
    private final Set<String> forbiddenPasswords;
    protected Logger logger;
    private AuthenticPremiumProvider premiumProvider;
    private AuthenticEventProvider<P, S> eventProvider;
    private AuthenticServerHandler<P, S> serverHandler;
    private TOTPProvider totpProvider;
    private AuthenticImageProjector<P, S> imageProjector;
    private FloodgateIntegration floodgateApi;
    private LuckPermsIntegration<P, S> luckpermsApi;
    private SemanticVersion version;
    private HoconPluginConfiguration configuration;
    private HoconMessages messages;
    private AuthenticAuthorizationProvider<P, S> authorizationProvider;
    private CommandProvider<P, S> commandProvider;
    private ReadWriteDatabaseProvider databaseProvider;
    private DatabaseConnector<?, ?> databaseConnector;
    private AuthenticEMailHandler eMailHandler;
    private LoginTryListener<P, S> loginTryListener;

    protected AuthenticLibreLogin() {
        cryptoProviders = new ConcurrentHashMap<>();
        readProviders = new ConcurrentHashMap<>();
        databaseConnectors = new ConcurrentHashMap<>();
        platformHandle = providePlatformHandle();
        forbiddenPasswords = new HashSet<>();
        cancelOnExit = HashMultimap.create();
    }

    public Map<Class<?>, DatabaseConnectorRegistration<?, ?>> getDatabaseConnectors() {
        return databaseConnectors;
    }

    @Override
    public <E extends Exception, C extends DatabaseConnector<E, ?>> void registerDatabaseConnector(
            Class<?> clazz, ThrowableFunction<String, C, E> factory, String id) {
        registerDatabaseConnector(new DatabaseConnectorRegistration<>(factory, null, id), clazz);
    }

    @Override
    public void registerReadProvider(ReadDatabaseProviderRegistration<?, ?, ?> registration) {
        readProviders.put(registration.id(), registration);
    }

    @Override
    public AuthenticEMailHandler getEmailHandler() {
        return eMailHandler;
    }

    @Nullable
    @Override
    public LimboIntegration<S> getLimboIntegration() {
        return null;
    }

    @Override
    public User createUser(
            UUID uuid,
            UUID premiumUUID,
            HashedPassword hashedPassword,
            String lastNickname,
            Timestamp joinDate,
            Timestamp lastSeen,
            String secret,
            String ip,
            Timestamp lastAuthentication,
            String lastServer,
            String email) {
        return new AuthenticUser(
                uuid,
                premiumUUID,
                hashedPassword,
                lastNickname,
                joinDate,
                lastSeen,
                secret,
                ip,
                lastAuthentication,
                lastServer,
                email);
    }

    public void registerDatabaseConnector(
            DatabaseConnectorRegistration<?, ?> registration, Class<?> clazz) {
        databaseConnectors.put(clazz, registration);
    }

    @Override
    public PlatformHandle<P, S> getPlatformHandle() {
        return platformHandle;
    }

    protected abstract PlatformHandle<P, S> providePlatformHandle();

    @Override
    public SemanticVersion getParsedVersion() {
        return version;
    }

    @Override
    public boolean validPassword(String password) {
        var length = password.length() >= configuration.get(MINIMUM_PASSWORD_LENGTH);

        if (!length) {
            return false;
        }

        var upper = password.toUpperCase();

        return !forbiddenPasswords.contains(upper);
    }

    @Override
    public Map<String, ReadDatabaseProviderRegistration<?, ?, ?>> getReadProviders() {
        return Map.copyOf(readProviders);
    }

    public CommandProvider<P, S> getCommandProvider() {
        return commandProvider;
    }

    @Override
    public ReadWriteDatabaseProvider getDatabaseProvider() {
        return databaseProvider;
    }

    @Override
    public AuthenticPremiumProvider getPremiumProvider() {
        return premiumProvider;
    }

    @Override
    public TOTPProvider getTOTPProvider() {
        return totpProvider;
    }

    @Override
    public AuthenticImageProjector<P, S> getImageProjector() {
        return imageProjector;
    }

    @Override
    public ServerHandler<P, S> getServerHandler() {
        return serverHandler;
    }

    protected void enable() {
        version = SemanticVersion.parse(getVersion());
        if (logger == null) logger = provideLogger();

        try {
            new Log4JFilter().inject();
        } catch (Throwable ignored) {
            logger.info("LogFilter nie jest obsługiwany na tej platformie");
            var simpleLogger = getSimpleLogger();

            if (simpleLogger != null) {
                logger.info("Używanie SimpleLogFilter");
                new SimpleLogFilter(simpleLogger).inject();
            }
        }

        var folder = getDataFolder();
        if (!folder.exists()) {
            var oldFolder =
                    new File(
                            folder.getParentFile(),
                            folder.getName().equals("librelogin")
                                    ? "librepremium"
                                    : "LibrePremium");
            if (oldFolder.exists()) {
                logger.info("Migrowanie konfiguracji i wiadomości ze starego folderu...");
                if (!oldFolder.renameTo(folder)) {
                    throw new RuntimeException(
                            "Nie można zmigrować konfiguracji i wiadomości ze starego folderu!");
                }
            }
        }

        try {
            Files.copy(
                    getResourceAsStream("LICENSE.txt"),
                    new File(folder, "LICENSE.txt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Silently ignore
        }

        if (platformHandle.getPlatformIdentifier().equals("paper")) {
            LIMBO.setDefault(List.of("limbo"));

            var lobby = HashMultimap.<String, String>create();
            lobby.put("root", "world");

            LOBBY.setDefault(lobby);
        }

        eventProvider = new AuthenticEventProvider<>(this);
        premiumProvider = new AuthenticPremiumProvider(this);

        registerCryptoProvider(new MessageDigestCryptoProvider("SHA-256"));
        registerCryptoProvider(new MessageDigestCryptoProvider("SHA-512"));
        registerCryptoProvider(new BCrypt2ACryptoProvider());
        registerCryptoProvider(new Argon2IDCryptoProvider(logger));
        registerCryptoProvider(new LogITMessageDigestCryptoProvider("LOGIT-SHA-256", "SHA-256"));

        setupDB();

        checkDataFolder();

        loadConfigs();

        logger.info("Wczytywanie zakazanych haseł...");

        try {
            loadForbiddenPasswords();
        } catch (IOException e) {
            e.printStackTrace();
            logger.info(
                    "Wystąpił nieznany wyjątek podczas próby wczytania zakazanych haseł,"
                            + " najprawdopodobniej nie jest to Twoja wina");
            shutdownProxy(1);
        }

        logger.info("Wczytano zakazane hasła: %s".formatted(forbiddenPasswords.size()));

        connectToDB();

        serverHandler = new AuthenticServerHandler<>(this);

        this.loginTryListener = new LoginTryListener<>(this);

        // Moved to a different class to avoid class loading issues
        GeneralUtil.checkAndMigrate(configuration, logger, this);

        imageProjector = provideImageProjector();

        if (imageProjector != null) {
            if (!configuration.get(TOTP_ENABLED)) {
                imageProjector = null;
                logger.warn("2FA jest wyłączone w konfiguracji, przerywanie...");
            } else {
                imageProjector.enable();
            }
        }

        totpProvider = imageProjector == null ? null : new AuthenticTOTPProvider(this);
        eMailHandler = configuration.get(MAIL_ENABLED) ? new AuthenticEMailHandler(this) : null;

        authorizationProvider = new AuthenticAuthorizationProvider<>(this);
        commandProvider = new CommandProvider<>(this);

        if (version.dev()) {
            logger.warn("!! UŻYWASZ WERSJI DEWELOPERSKIEJ LIBRELOGIN !!");
            logger.warn(
                    "!! TO NIE JEST WERSJA WYDANIOWA, UŻYWAJ JEJ TYLKO WTEDY, GDY ZOSTAŁO TO"
                            + " WYRAŹNIE ZALECONE. NIE UŻYWAJ JEJ NA SERWERZE PRODUKCYJNYM !!");
        } else {
            initMetrics();
        }

        delay(this::checkForUpdates, 1000);

        if (pluginPresent("floodgate")) {
            logger.info("Wykryto Floodgate, włączanie obsługi Bedrock...");
            floodgateApi = new FloodgateIntegration();
        }

        if (pluginPresent("luckperms")) {
            logger.info("Wykryto LuckPerms, włączanie dostawcy kontekstów");
            luckpermsApi = new LuckPermsIntegration<>(this);
        }

        if (multiProxyEnabled()) {
            logger.info("Wykryto konfigurację MultiProxy, włączanie obsługi MultiProxy...");
        }
    }

    public <C extends DatabaseConnector<?, ?>>
            DatabaseConnectorRegistration<?, C> getDatabaseConnector(Class<C> clazz) {
        return (DatabaseConnectorRegistration<?, C>) databaseConnectors.get(clazz);
    }

    private void connectToDB() {
        logger.info("Łączenie z bazą danych...");

        try {
            var registration = readProviders.get(configuration.get(DATABASE_TYPE));
            if (registration == null) {
                logger.error(
                        "Typ bazy danych %s nie istnieje, sprawdź swoją konfigurację"
                                .formatted(configuration.get(DATABASE_TYPE)));
                shutdownProxy(1);
            }

            DatabaseConnector<?, ?> connector = null;

            if (registration.databaseConnector() != null) {
                var connectorRegistration = getDatabaseConnector(registration.databaseConnector());

                if (connectorRegistration == null) {
                    logger.error(
                            "Typ bazy danych %s jest uszkodzony, użyj innego"
                                    .formatted(configuration.get(DATABASE_TYPE)));
                    shutdownProxy(1);
                }

                connector =
                        connectorRegistration
                                .factory()
                                .apply("database.properties." + connectorRegistration.id() + ".");

                connector.connect();
            }

            var provider = registration.create(connector);

            if (provider instanceof ReadWriteDatabaseProvider casted) {
                databaseProvider = casted;
                databaseConnector = connector;
            } else {
                logger.error(
                        "Typu bazy danych %s nie można używać do zapisu, użyj innego"
                                .formatted(configuration.get(DATABASE_TYPE)));
                shutdownProxy(1);
            }

        } catch (Exception e) {
            var cause = GeneralUtil.getFurthestCause(e);
            logger.error("!! NAJPRAWDOPODOBNIEJ NIE JEST TO BŁĄD SPOWODOWANY PRZEZ LIBRELOGIN !!");
            logger.error(
                    "Nie udało się połączyć z bazą danych, najprawdopodobniej przyczyną są błędne dane logowania. Przyczyna: %s: %s"
                            .formatted(cause.getClass().getSimpleName(), cause.getMessage()));
            shutdownProxy(1);
        }

        logger.info("Pomyślnie połączono z bazą danych");

        if (databaseProvider instanceof AuthenticDatabaseProvider<?> casted) {
            logger.info("Sprawdzanie poprawności schematu");

            try {
                casted.validateSchema();
            } catch (Exception e) {
                var cause = GeneralUtil.getFurthestCause(e);
                logger.error(
                        "Nie udało się zweryfikować schematu! Przyczyna: %s: %s"
                                .formatted(cause.getClass().getSimpleName(), cause.getMessage()));
                logger.error("Zgłoś problem na naszym GitHubie lub odwiedź wsparcie na Discordzie");
                shutdownProxy(1);
            }

            logger.info("Schemat zweryfikowany");
        }
    }

    private void loadConfigs() {
        logger.info("Wczytywanie wiadomości...");

        messages = new HoconMessages(logger);

        try {
            messages.reload(this);
        } catch (IOException e) {
            e.printStackTrace();
            logger.info(
                    "Wystąpił nieznany wyjątek podczas próby wczytania wiadomości,"
                            + " najprawdopodobniej nie jest to Twoja wina");
            shutdownProxy(1);
        } catch (CorruptedConfigurationException e) {
            var cause = GeneralUtil.getFurthestCause(e);
            logger.error("!! NAJPRAWDOPODOBNIEJ NIE JEST TO BŁĄD SPOWODOWANY PRZEZ LIBRELOGIN !!");
            logger.error(
                    "!!Plik wiadomości jest uszkodzony, wskazówek szukaj poniżej. Jeśli nie wiesz, co zrobić, usuń plik wiadomości, a zostanie utworzony nowy. Przyczyna: %s: %s"
                            .formatted(cause.getClass().getSimpleName(), cause.getMessage()));
            shutdownProxy(1);
        }

        logger.info("Wczytywanie konfiguracji...");

        var defaults = new ArrayList<BiHolder<Class<?>, String>>();

        for (DatabaseConnectorRegistration<?, ?> value : databaseConnectors.values()) {
            if (value.configClass() == null) continue;
            defaults.add(
                    new BiHolder<>(value.configClass(), "database.properties." + value.id() + "."));
            defaults.add(
                    new BiHolder<>(
                            value.configClass(), "migration.old-database." + value.id() + "."));
        }

        configuration = new HoconPluginConfiguration(logger, defaults);

        try {
            if (configuration.reload(this)) {
                logger.warn(
                        "!! Wygenerowano nową konfigurację, uzupełnij ją; w razie wątpliwości"
                                + " zajrzyj na wiki !!");
                shutdownProxy(0);
            }

            var limbos = configuration.get(LIMBO);
            var lobby = configuration.get(LOBBY);

            for (String value : lobby.values()) {
                if (limbos.contains(value)) {
                    throw new CorruptedConfigurationException(
                            "Serwer/świat lobby %s jest jednocześnie serwerem/światem limbo, jest to niedozwolone"
                                    .formatted(value));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.info(
                    "Wystąpił nieznany wyjątek podczas próby wczytania konfiguracji,"
                            + " najprawdopodobniej nie jest to Twoja wina");
            shutdownProxy(1);
        } catch (CorruptedConfigurationException e) {
            var cause = GeneralUtil.getFurthestCause(e);
            logger.error("!! NAJPRAWDOPODOBNIEJ NIE JEST TO BŁĄD SPOWODOWANY PRZEZ LIBRELOGIN !!");
            logger.error(
                    "!!Konfiguracja jest uszkodzona, wskazówek szukaj poniżej. Jeśli nie wiesz, co zrobić, usuń plik konfiguracyjny, a zostanie utworzony nowy. Przyczyna: %s: %s"
                            .formatted(cause.getClass().getSimpleName(), cause.getMessage()));
            shutdownProxy(1);
        }
    }

    private void setupDB() {
        registerDatabaseConnector(
                new DatabaseConnectorRegistration<>(
                        prefix -> new AuthenticMySQLDatabaseConnector(this, prefix),
                        AuthenticMySQLDatabaseConnector.Configuration.class,
                        "mysql"),
                MySQLDatabaseConnector.class);
        registerDatabaseConnector(
                new DatabaseConnectorRegistration<>(
                        prefix -> new AuthenticSQLiteDatabaseConnector(this, prefix),
                        AuthenticSQLiteDatabaseConnector.Configuration.class,
                        "sqlite"),
                SQLiteDatabaseConnector.class);
        registerDatabaseConnector(
                new DatabaseConnectorRegistration<>(
                        prefix -> new AuthenticPostgreSQLDatabaseConnector(this, prefix),
                        AuthenticPostgreSQLDatabaseConnector.Configuration.class,
                        "postgresql"),
                PostgreSQLDatabaseConnector.class);

        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new LibreLoginMySQLDatabaseProvider(connector, this),
                        "librelogin-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new LibreLoginSQLiteDatabaseProvider(connector, this),
                        "librelogin-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new LibreLoginPostgreSQLDatabaseProvider(connector, this),
                        "librelogin-postgresql",
                        PostgreSQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new AegisSQLMigrateReadProvider(
                                        configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "aegis-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new AuthMeSQLMigrateReadProvider(
                                        configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "authme-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new AuthMeSQLMigrateReadProvider(
                                        configuration.get(MIGRATION_POSTGRESQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "authme-postgresql",
                        PostgreSQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new AuthMeSQLMigrateReadProvider("authme", logger, connector),
                        "authme-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new DBASQLMigrateReadProvider(
                                        configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "dba-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new JPremiumSQLMigrateReadProvider(
                                        configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "jpremium-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new NLoginSQLMigrateReadProvider("nlogin", logger, connector),
                        "nlogin-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new NLoginSQLMigrateReadProvider("nlogin", logger, connector),
                        "nlogin-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new FastLoginSQLMigrateReadProvider(
                                        "premium",
                                        logger,
                                        connector,
                                        databaseConnector,
                                        premiumProvider),
                        "fastlogin-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new FastLoginSQLMigrateReadProvider(
                                        "premium",
                                        logger,
                                        connector,
                                        databaseConnector,
                                        premiumProvider),
                        "fastlogin-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new UniqueCodeAuthSQLMigrateReadProvider(
                                        "uniquecode_proxy_users", logger, connector, this),
                        "uniquecodeauth-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new LoginSecuritySQLMigrateReadProvider(
                                        "ls_players", logger, connector),
                        "loginsecurity-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new LoginSecuritySQLMigrateReadProvider(
                                        "ls_players", logger, connector),
                        "loginsecurity-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new LimboAuthSQLMigrateReadProvider("AUTH", logger, connector),
                        "limboauth-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new AuthySQLMigrateReadProvider("players", logger, connector),
                        "authy-mysql",
                        MySQLDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector -> new AuthySQLMigrateReadProvider("players", logger, connector),
                        "authy-sqlite",
                        SQLiteDatabaseConnector.class));
        registerReadProvider(
                new ReadDatabaseProviderRegistration<>(
                        connector ->
                                new LogItSQLMigrateReadProvider(
                                        configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE),
                                        logger,
                                        connector),
                        "logit-mysql",
                        MySQLDatabaseConnector.class));
        // Currently disabled as crazylogin stores all names in lowercase
        /*registerReadProvider(new ReadDatabaseProviderRegistration<>(
                connector -> new CrazyLoginSQLMigrateReadProvider(configuration.get(MIGRATION_MYSQL_OLD_DATABASE_TABLE), logger, connector),
                "crazylogin-mysql",
                MySQLDatabaseConnector.class
        ));*/
    }

    private void loadForbiddenPasswords() throws IOException {
        var file = new File(getDataFolder(), "forbidden-passwords.txt");

        if (!file.exists()) {
            logger.info("Lista zakazanych haseł nie istnieje, pobieranie...");
            try (BufferedInputStream in =
                    new BufferedInputStream(
                            new URL(
                                            "https://raw.githubusercontent.com/kyngs/LibreLogin/dev/forbidden-passwords.txt")
                                    .openStream())) {
                if (!file.createNewFile()) {
                    throw new IOException("Nie udało się utworzyć pliku");
                }
                try (var fos = new FileOutputStream(file)) {
                    var dataBuffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                        fos.write(dataBuffer, 0, bytesRead);
                    }
                }
                logger.info("Pomyślnie pobrano listę zakazanych haseł");
            } catch (IOException e) {
                e.printStackTrace();
                logger.warn("Nie udało się pobrać listy zakazanych haseł, używanie szablonu");
                Files.copy(getResourceAsStream("forbidden-passwords-template.txt"), file.toPath());
            }
        }

        try (var reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# ")) {
                    continue;
                }
                forbiddenPasswords.add(line.toUpperCase(Locale.ROOT));
            }
        }
    }

    private void checkForUpdates() {
        logger.info("Sprawdzanie aktualizacji...");

        try {
            var connection =
                    new URL("https://api.github.com/repos/Navio1430/LibreLoginProd/releases")
                            .openConnection();

            connection.setRequestProperty("User-Agent", "LibreLogin");

            var in = connection.getInputStream();

            var root = GSON.fromJson(new InputStreamReader(in), JsonArray.class);

            in.close(); // Not the safest way, but a slight leak isn't a big deal

            List<Release> behind = new ArrayList<>();
            SemanticVersion latest = null;

            for (JsonElement raw : root) {
                var release = raw.getAsJsonObject();

                var version = SemanticVersion.parse(release.get("tag_name").getAsString());

                if (latest == null) latest = version;

                var shouldBreak =
                        switch (this.version.compare(version)) {
                            case 0, 1 -> true;
                            default -> {
                                behind.add(new Release(version, release.get("name").getAsString()));
                                yield false;
                            }
                        };

                if (shouldBreak) {
                    break;
                }
            }

            if (behind.isEmpty()) {
                logger.info("Używasz najnowszej wersji LibreLogin");
            } else {
                Collections.reverse(behind);
                logger.warn("!! UŻYWASZ NIEAKTUALNEJ WERSJI LIBRELOGIN !!");
                logger.info(
                        "Używasz wersji %s, najnowsza wersja to %s. Liczba nowszych wersji: %s. Nowsze wersje:"
                                .formatted(getVersion(), latest, behind.size()));
                for (Release release : behind) {
                    logger.info("- %s".formatted(release.name()));
                }
                logger.warn("!! ZAKTUALIZUJ DO NAJNOWSZEJ WERSJI !!");
            }
        } catch (Exception e) {
            logger.warn("Nie udało się sprawdzić aktualizacji", e);
        }
    }

    public UUID generateNewUUID(String name, @Nullable UUID premiumID) {
        return switch (configuration.getNewUUIDCreator()) {
            case RANDOM -> UUID.randomUUID();
            case MOJANG -> premiumID == null ? GeneralUtil.getCrackedUUIDFromName(name) : premiumID;
            case CRACKED -> GeneralUtil.getCrackedUUIDFromName(name);
        };
    }

    protected void disable() {
        if (databaseConnector != null) {
            try {
                databaseConnector.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("Nie udało się rozłączyć z bazą danych, ignorowanie...");
            }
        }
        if (luckpermsApi != null) {
            luckpermsApi.disable();
        }
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    public HoconPluginConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public HoconMessages getMessages() {
        return messages;
    }

    @Override
    public void checkDataFolder() {
        var folder = getDataFolder();

        if (!folder.exists())
            if (!folder.mkdir())
                throw new RuntimeException("Nie udało się utworzyć folderu danych");
    }

    protected abstract Logger provideLogger();

    public abstract CommandManager<?, ?, ?, ?, ?, ?> provideManager();

    public abstract P getPlayerFromIssuer(CommandIssuer issuer);

    public abstract void authorize(P player, User user, Audience audience);

    public abstract CancellableTask delay(Runnable runnable, long delayInMillis);

    public abstract CancellableTask repeat(
            Runnable runnable, long delayInMillis, long repeatInMillis);

    public abstract boolean pluginPresent(String pluginName);

    protected abstract AuthenticImageProjector<P, S> provideImageProjector();

    public PremiumUser getUserOrThrowICA(String username) throws InvalidCommandArgument {
        try {
            return getPremiumProvider().getUserForName(username);
        } catch (PremiumException e) {
            throw new InvalidCommandArgument(
                    getMessages()
                            .getMessage(
                                    switch (e.getIssue()) {
                                        case THROTTLED -> "error-premium-throttled";
                                        default -> "error-premium-unknown";
                                    }));
        }
    }

    protected abstract void initMetrics(CustomChart... charts);

    @Override
    public AuthenticAuthorizationProvider<P, S> getAuthorizationProvider() {
        return authorizationProvider;
    }

    @Override
    public CryptoProvider getCryptoProvider(String id) {
        return cryptoProviders.get(id);
    }

    @Override
    public void registerCryptoProvider(CryptoProvider provider) {
        cryptoProviders.put(provider.getIdentifier(), provider);
    }

    @Override
    public CryptoProvider getDefaultCryptoProvider() {
        return getCryptoProvider(configuration.get(DEFAULT_CRYPTO_PROVIDER));
    }

    @Override
    public void migrate(ReadDatabaseProvider from, WriteDatabaseProvider to) {
        logger.info("Odczytywanie danych...");
        var users = from.getAllUsers();
        logger.info("Dane odczytane, wstawianie do bazy danych...");
        to.insertUsers(users);
    }

    @Override
    public AuthenticEventProvider<P, S> getEventProvider() {
        return eventProvider;
    }

    public LoginTryListener<P, S> getLoginTryListener() {
        return loginTryListener;
    }

    public void onExit(P player) {
        cancelOnExit.removeAll(player).forEach(CancellableTask::cancel);
        if (configuration.get(REMEMBER_LAST_SERVER)) {
            var server = platformHandle.getPlayersServerName(player);
            if (server == null) return;
            var user = databaseProvider.getByUUID(platformHandle.getUUIDForPlayer(player));
            if (user != null && !getConfiguration().get(LIMBO).contains(server)) {
                user.setLastServer(server);
                databaseProvider.updateUser(user);
            }
        }
    }

    public void cancelOnExit(CancellableTask task, P player) {
        cancelOnExit.put(player, task);
    }

    public boolean floodgateEnabled() {
        return floodgateApi != null;
    }

    public boolean luckpermsEnabled() {
        return luckpermsApi != null;
    }

    public boolean fromFloodgate(UUID uuid) {
        return floodgateApi != null && uuid != null && floodgateApi.isFloodgateId(uuid);
    }

    protected void shutdownProxy(int code) {
        //noinspection finally
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ignored) {
        } finally {
            System.exit(code);
        }
    }

    public abstract Audience getAudienceFromIssuer(CommandIssuer issuer);

    /** Pozwala platformie zarejestrować dodatkowe komendy, które nie istnieją na innych. */
    public void registerPlatformCommands(CommandManager<?, ?, ?, ?, ?, ?> manager) {}

    protected boolean mainThread() {
        return false;
    }

    public void reportMainThread() {
        if (mainThread()) {
            logger.error(
                    "OPERACJA WEJŚCIA/WYJŚCIA (IO) JEST WYKONYWANA W GŁÓWNYM WĄTKU! TO POWAŻNY"
                            + " BŁĄD! ZGŁOŚ GO DEWELOPEROWI WTYCZKI I DOŁĄCZ PONIŻSZY"
                            + " STACKTRACE!");
            new Throwable().printStackTrace();
        }
    }

    public boolean fromFloodgate(String username) {
        return floodgateApi != null && floodgateApi.getPlayer(username) != null;
    }

    protected java.util.logging.Logger getSimpleLogger() {
        return null;
    }
}
