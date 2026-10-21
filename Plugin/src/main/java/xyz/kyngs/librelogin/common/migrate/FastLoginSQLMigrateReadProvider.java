/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.migrate;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.*;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.database.connector.DatabaseConnector;
import xyz.kyngs.librelogin.api.database.connector.SQLDatabaseConnector;
import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumProvider;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

public class FastLoginSQLMigrateReadProvider extends SQLMigrateReadProvider {
    private final DatabaseConnector<?, ?> main;
    private final PremiumProvider provider;

    public FastLoginSQLMigrateReadProvider(
            String tableName,
            Logger logger,
            SQLDatabaseConnector connector,
            DatabaseConnector<?, ?> main,
            PremiumProvider provider) {
        super(tableName, logger, connector);
        this.main = main;
        this.provider = provider;
    }

    @Override
    public Collection<User> getAllUsers() {
        return connector.runQuery(
                connection -> {
                    var ps = connection.prepareStatement("SELECT * FROM `%s`".formatted(tableName));

                    var rs = ps.executeQuery();

                    Multimap<UUID, String> premiumUsers = HashMultimap.create();

                    while (rs.next()) {
                        try {
                            if (rs.getInt("Premium") != 1) continue;

                            var premiumUUID = GeneralUtil.fromUnDashedUUID(rs.getString("UUID"));
                            var name = rs.getString("Name");

                            premiumUsers.put(premiumUUID, name);
                        } catch (Exception e) {
                            logger.error(
                                    "Błąd podczas migracji użytkownika z bazy FastLogin,"
                                            + " pomijanie");
                        }
                    }

                    for (Map.Entry<UUID, Collection<String>> entry :
                            premiumUsers.asMap().entrySet()) {
                        var names = entry.getValue();
                        var premiumUUID = entry.getKey();

                        if (premiumUUID == null) {
                            continue;
                        }

                        String name = null;

                        if (names.size() == 1) {
                            name = names.iterator().next();
                        } else if (names.size() > 1) {
                            logger.warn(
                                    "Użytkownicy %s mają ten sam UUID premium %s, kontaktowanie się z Mojang w celu ustalenia właściciela"
                                            .formatted(
                                                    Arrays.toString(names.toArray()), premiumUUID));
                            PremiumUser user = null;

                            while (true) {
                                try {
                                    user = provider.getUserForUUID(premiumUUID);
                                    break;
                                } catch (PremiumException e) {
                                    if (e.getIssue() == PremiumException.Issue.THROTTLED) {
                                        logger.warn(
                                                "Zapytanie do Mojang zostało ograniczone,"
                                                        + " oczekiwanie 5 sekund");
                                        try {
                                            Thread.sleep(5000);
                                        } catch (InterruptedException interruptedException) {
                                            throw new RuntimeException(
                                                    interruptedException); // Probably should not
                                            // continue when
                                            // interrupted
                                        }
                                    } else {
                                        logger.error(
                                                "Nie można skontaktować się z Mojang w celu"
                                                        + " ustalenia właściciela, pomijanie");
                                        e.printStackTrace();
                                        break;
                                    }
                                }
                            }

                            if (user == null) {
                                logger.warn(
                                        "Nie znaleziono właściciela UUID premium %s, pomijanie"
                                                .formatted(premiumUUID));
                                continue;
                            }

                            for (String s : names) {
                                if (s.equalsIgnoreCase(user.name())) {
                                    name = s;
                                    break;
                                }
                            }

                            if (name == null) {
                                logger.error(
                                        "Zarejestrowane nazwy z tym UUID premium nie pasują do nazwy w Mojang %s, pomijanie"
                                                .formatted(user.name()));
                                continue;
                            } else {
                                logger.info(
                                        "Znaleziono właściciela UUID premium %s, nazwa %s"
                                                .formatted(premiumUUID, name));
                            }
                        } else {
                            continue;
                        }

                        assert name != null;

                        if (main instanceof SQLDatabaseConnector sqlMain) {
                            String finalName = name;
                            sqlMain.runQuery(
                                    connection2 -> {
                                        var ps2 =
                                                connection2.prepareStatement(
                                                        "UPDATE librepremium_data SET"
                                                                + " premium_uuid=? WHERE"
                                                                + " last_nickname=?");
                                        ps2.setString(1, premiumUUID.toString());
                                        ps2.setString(2, finalName);
                                        ps2.executeUpdate();
                                    });
                        }
                    }

                    return List.of();
                });
    }
}
