/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.kyngs.librelogin.api.Logger;

/** Przechowuje lokalizacje ustawiane komendami, w pliku locations.yml w folderze wtyczki. */
public class PaperLocationStorage {

    public enum Type {
        /** Miejsce, w którym pojawia się gracz przed zalogowaniem. */
        LOGIN("login"),
        /** Miejsce, w którym pojawia się gracz po zalogowaniu. */
        HUB("hub");

        private final String key;

        Type(String key) {
            this.key = key;
        }
    }

    private record StoredLocation(
            String world, double x, double y, double z, float yaw, float pitch) {}

    private final File file;
    private final Logger logger;
    private final Map<Type, StoredLocation> locations = new ConcurrentHashMap<>();

    public PaperLocationStorage(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "locations.yml");
        this.logger = logger;

        load();
    }

    private void load() {
        if (!file.exists()) return;

        var yaml = YamlConfiguration.loadConfiguration(file);

        for (var type : Type.values()) {
            var section = yaml.getConfigurationSection(type.key);
            if (section == null) continue;

            var world = section.getString("world");
            if (world == null) {
                logger.warn(
                        "Location %s in file %s has no world set, skipping."
                                .formatted(type.key, file.getName()));
                continue;
            }

            locations.put(
                    type,
                    new StoredLocation(
                            world,
                            section.getDouble("x"),
                            section.getDouble("y"),
                            section.getDouble("z"),
                            (float) section.getDouble("yaw"),
                            (float) section.getDouble("pitch")));
        }
    }

    /**
     * @return zapisana lokalizacja albo null, gdy nie została ustawiona lub jej świat nie jest
     *     załadowany
     */
    public Location get(Type type) {
        var stored = locations.get(type);
        if (stored == null) return null;

        var world = Bukkit.getWorld(stored.world());
        if (world == null) {
            logger.warn(
                    "World %s of the saved location (%s) is not loaded, using the spawn instead"
                            .formatted(stored.world(), type.key));
            return null;
        }

        return new Location(
                world, stored.x(), stored.y(), stored.z(), stored.yaw(), stored.pitch());
    }

    public synchronized void set(Type type, Location location) throws IOException {
        var stored =
                new StoredLocation(
                        location.getWorld().getName(),
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch());

        var previous = locations.put(type, stored);

        try {
            save();
        } catch (IOException e) {
            if (previous == null) {
                locations.remove(type);
            } else {
                locations.put(type, previous);
            }
            throw e;
        }
    }

    private void save() throws IOException {
        var yaml = new YamlConfiguration();

        locations.forEach(
                (type, stored) -> {
                    yaml.set(type.key + ".world", stored.world());
                    yaml.set(type.key + ".x", stored.x());
                    yaml.set(type.key + ".y", stored.y());
                    yaml.set(type.key + ".z", stored.z());
                    yaml.set(type.key + ".yaw", stored.yaw());
                    yaml.set(type.key + ".pitch", stored.pitch());
                });

        var parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create folder " + parent);
        }

        yaml.save(file);
    }
}
