/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.LibraryManager;
import net.byteflux.libby.PaperLibraryManager;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librelogin.api.provider.LibreLoginProvider;

public class PaperBootstrap extends JavaPlugin implements LibreLoginProvider<Player, World> {

    private PaperLibreLogin libreLogin;

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(
            @NotNull String worldName, @Nullable String id) {
        return id == null ? null : id.equals("void") ? new VoidWorldGenerator() : null;
    }

    @Override
    public void onLoad() {
        getLogger().info("Analizowanie konfiguracji serwera...");

        try {
            var adventureClass = Class.forName("net.kyori.adventure.audience.Audience");

            if (!adventureClass.isAssignableFrom(Player.class)) {
                throw new ClassNotFoundException();
            }
        } catch (ClassNotFoundException e) {
            unsupportedSetup();
        }

        getLogger()
                .info(
                        "Wykryto dystrybucję serwera zgodną z Adventure - "
                                + getServer().getName()
                                + " "
                                + getServer().getVersion());

        LibraryManager libraryManager;

        try {
            Class.forName("io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader");
            libraryManager = new PaperLibraryManager(this);
        } catch (ClassNotFoundException e) {
            libraryManager = new BukkitLibraryManager(this);
        }

        getSLF4JLogger().info("Wczytywanie bibliotek...");

        try {
            // The Librarian gradle plugin generates librarian.json, Libby looks for libby.json
            libraryManager.configureFromJSON(getResource("librarian.json"));
        } catch (Exception e) {
            getSLF4JLogger()
                    .error(
                            "Nie udało się wczytać bibliotek, zatrzymywanie serwera, aby uniknąć"
                                    + " szkód",
                            e);
            stopServer();
        }

        libreLogin = new PaperLibreLogin(this);
    }

    @Override
    public void onEnable() {
        getLogger().info("Uruchamianie LibreLogin...");
        libreLogin.enable();
    }

    private void unsupportedSetup() {
        getLogger().severe("***********************************************************");

        getLogger()
                .severe(
                        "Wykryto nieobsługiwaną dystrybucję serwera. Użyj Papera lub jego forków."
                                + " SPIGOT NIE JEST OBSŁUGIWANY!");

        getLogger().severe("***********************************************************");

        stopServer();
    }

    private void stopServer() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ignored) {
        }

        System.exit(1);
    }

    @Override
    public void onDisable() {
        libreLogin.disable();
    }

    @Override
    public PaperLibreLogin getLibreLogin() {
        return libreLogin;
    }

    protected void disable() {
        setEnabled(false);
    }
}
