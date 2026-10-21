/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Subcommand;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import xyz.kyngs.librelogin.common.command.Command;
import xyz.kyngs.librelogin.common.command.InvalidCommandArgument;

@CommandAlias("librelogin")
public class PaperLocationCommand extends Command<Player> {

    private final PaperLibreLogin paperPlugin;

    public PaperLocationCommand(PaperLibreLogin plugin) {
        super(plugin);
        this.paperPlugin = plugin;
    }

    // Parametr gracza jest typu Object, bo tylko dla niego CommandProvider rejestruje kontekst
    // gracza wykonującego komendę (ACF nie zna typu P po type erasure).
    @Subcommand("setLoginLocation")
    @CommandPermission("librelogin.location.set")
    public CompletionStage<Void> onSetLoginLocation(Audience audience, Object player) {
        return setLocation(
                audience,
                (Player) player,
                PaperLocationStorage.Type.LOGIN,
                "info-login-location-set");
    }

    @Subcommand("setHubSpawn")
    @CommandPermission("librelogin.location.set")
    public CompletionStage<Void> onSetHubSpawn(Audience audience, Object player) {
        return setLocation(
                audience, (Player) player, PaperLocationStorage.Type.HUB, "info-hub-spawn-set");
    }

    private CompletionStage<Void> setLocation(
            Audience audience, Player player, PaperLocationStorage.Type type, String messageKey) {
        // Komenda wykonuje się na głównym wątku, a pozycję gracza można tu bezpiecznie odczytać
        var location = player.getLocation();

        return runAsync(
                () -> {
                    try {
                        paperPlugin.getLocationStorage().set(type, location);
                    } catch (IOException e) {
                        e.printStackTrace();
                        throw new InvalidCommandArgument(getMessage("error-unknown"));
                    }

                    audience.sendMessage(
                            getMessage(
                                    messageKey,
                                    "%world%",
                                    location.getWorld().getName(),
                                    "%x%",
                                    format(location.getX()),
                                    "%y%",
                                    format(location.getY()),
                                    "%z%",
                                    format(location.getZ()),
                                    "%yaw%",
                                    format(location.getYaw()),
                                    "%pitch%",
                                    format(location.getPitch())));
                });
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
