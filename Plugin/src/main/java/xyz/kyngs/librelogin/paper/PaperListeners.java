/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.crypto.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.config.MessageKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;
import xyz.kyngs.librelogin.common.util.GeneralUtil;
import xyz.kyngs.librelogin.paper.protocol.EncryptionUtil;
import xyz.kyngs.librelogin.paper.protocol.ProtocolUtil;

public class PaperListeners extends AuthenticListeners<PaperLibreLogin, Player, World>
        implements Listener {

    // net.minecraft.network.Connection#setEncryptionKey(SecretKey)
    private static Method encryptMethod;

    private final KeyPair keyPair = EncryptionUtil.generateKeyPair();
    private final Random random = new SecureRandom();
    private final Cache<String, EncryptionData> encryptionDataCache =
            Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();
    private final FloodgateHelper floodgateHelper;
    private final Cache<UUID, String> ipCache;
    private final Cache<UUID, User> readOnlyUserCache;
    private final Cache<UUID, Location> spawnLocationCache;
    private final Cache<UUID, Location> joinedWhileDead;

    public PaperListeners(PaperLibreLogin plugin) {
        super(plugin);

        floodgateHelper = this.plugin.floodgateEnabled() ? new FloodgateHelper() : null;

        ipCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        readOnlyUserCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        spawnLocationCache = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();

        joinedWhileDead = Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).build();
    }

    public Cache<UUID, Location> getSpawnLocationCache() {
        return spawnLocationCache;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GeneralUtil.runAsync(() -> onPlayerDisconnect(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var location = joinedWhileDead.getIfPresent(event.getPlayer().getUniqueId());
        if (location == null) {
            return;
        }

        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.PLUGIN) {
            return;
        }

        event.setRespawnLocation(location);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var puuid = player.getUniqueId();

        var data = readOnlyUserCache.getIfPresent(puuid);
        if (data == null && !plugin.fromFloodgate(player.getName())) {
            player.kick(Component.text("Wewnętrzny błąd, spróbuj ponownie później."));
            return;
        }

        readOnlyUserCache.invalidate(puuid);
        if (player.isDead()) {
            spawnLocationCache.invalidate(puuid);
            player.spigot().respawn();
        } else {
            joinedWhileDead.invalidate(puuid);
        }

        onPostLogin(player, data);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostLogin(PlayerLoginEvent event) {
        ipCache.put(event.getPlayer().getUniqueId(), event.getAddress().getHostAddress());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (plugin.fromFloodgate(event.getName())) return;

        var user = plugin.getDatabaseProvider().getByName(event.getName());

        var newProfile = Bukkit.createProfileExact(user.getUuid(), event.getName());

        event.setPlayerProfile(newProfile);

        readOnlyUserCache.put(user.getUuid(), user);
    }

    // Changed to an async variant of this event
    // cuz the previous one is deprecated and causes a bug (Issue #52)
    @SuppressWarnings("UnstableApiUsage")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void chooseWorld(AsyncPlayerSpawnLocationEvent event) {
        var puuid = event.getConnection().getProfile().getId();

        var ip = ipCache.getIfPresent(puuid);
        if (ip == null) {
            Bukkit.getScheduler()
                    .runTask(
                            plugin.getBootstrap(),
                            () ->
                                    plugin.getPlayerForUUID(puuid)
                                            .kick(
                                                    Component.text(
                                                            "Wewnętrzny błąd, spróbuj ponownie"
                                                                    + " później.")));
            return;
        }

        ipCache.invalidate(puuid);

        var world = chooseServer(puuid, ip, readOnlyUserCache.getIfPresent(puuid));
        if (world.value() == null) {
            Bukkit.getScheduler()
                    .runTask(
                            plugin.getBootstrap(),
                            () ->
                                    plugin.getPlayerForUUID(puuid)
                                            .kick(
                                                    plugin.getMessages()
                                                            .getMessage(
                                                                    "kick-no-"
                                                                            + (world.key()
                                                                                    ? "lobby"
                                                                                    : "limbo"))));
            return;
        }

        Function<World, Boolean> isLimbo =
                (w) -> plugin.getConfiguration().get(ConfigurationKeys.LIMBO).contains(w.getName());

        World choosenWorld = world.value();
        Location eventSpawnLocation = event.getSpawnLocation();
        boolean isAuthenticated = world.key();

        if (!event.isNewPlayer()
                && !isLimbo.apply(eventSpawnLocation.getWorld())
                && isLimbo.apply(choosenWorld)) {
            spawnLocationCache.put(puuid, eventSpawnLocation);
        }

        if (isAuthenticated && !isLimbo.apply(choosenWorld)) {
            joinedWhileDead.invalidate(puuid);
            return;
        }

        var loc = plugin.getLocationStorage().get(PaperLocationStorage.Type.LOGIN);
        if (loc == null) {
            loc = choosenWorld.getSpawnLocation();
        }

        joinedWhileDead.put(puuid, loc);
        event.setSpawnLocation(loc);
    }

    /* Commented out when migrating to PacketEvents
    //Unused, might be useful in the future
    public void setUUID(Player player, String username) {
        var profile = plugin.getDatabaseProvider().getByName(username);

        try {
            var network = getNetworkManager(player);

            var clazz = network.getClass();
            var accessor = Accessors.getFieldAccessorOrNull(clazz, "spoofedUUID", UUID.class);
            accessor.set(network, profile.getUuid());
        } catch (Exception e) {
            e.printStackTrace();
            kickPlayer("Internal error", player);
        }
    }*/

    public void asyncPacketReceive(PacketReceiveEvent event) {
        var user = event.getUser();
        var type = event.getPacketType();

        plugin.getLogger()
                .debug(
                        "Odebrano pakiet "
                                + type
                                + " od "
                                + user.getName()
                                + " ("
                                + user.getAddress().toString()
                                + ")");

        if (type == PacketType.Login.Client.LOGIN_START) {
            var packet = new WrapperLoginClientLoginStart(event);
            var sessionKey = user.getAddress().toString();

            encryptionDataCache.invalidate(sessionKey);

            if (plugin.floodgateEnabled()) {
                var success = floodgateHelper.processFloodgateTasks(event, packet);
                // don't continue execution if the player was kicked by Floodgate
                if (!success) {
                    return;
                }
            }
            var username = packet.getUsername();

            if (Bukkit.getPlayer(username) != null) {
                kickPlayer(
                        plugin.getMessages().getMessage(MessageKeys.KICK_ALREADY_CONNECTED.key()),
                        user);
                return;
            }

            if (plugin.fromFloodgate(username)) {
                // Floodgate player, do not handle, only retransmit the packet. The UUID will be set
                // by Floodgate
                receiveFakeStartPacket(username, event.getChannel(), UUID.randomUUID());
                return;
            }
            var preLoginResult = onPreLogin(username, user.getAddress().getAddress());
            switch (preLoginResult.state()) {
                case DENIED -> {
                    assert preLoginResult.message() != null;
                    kickPlayer(preLoginResult.message(), user);
                }
                case FORCE_ONLINE -> {
                    byte[] token;
                    try {
                        token = EncryptionUtil.generateVerifyToken(random);

                        var newPacket =
                                new WrapperLoginServerEncryptionRequest(
                                        "", keyPair.getPublic(), token);

                        encryptionDataCache.put(
                                sessionKey,
                                new EncryptionData(
                                        username, token, preLoginResult.user().getUuid()));

                        PacketEvents.getAPI()
                                .getProtocolManager()
                                .sendPacket(event.getChannel(), newPacket);
                    } catch (Exception e) {
                        plugin.getLogger()
                                .error(
                                        "Nie udało się wysłać pakietu rozpoczęcia szyfrowania dla"
                                                + " gracza "
                                                + username
                                                + "! Wyrzucanie gracza.");
                        e.printStackTrace();
                        kickPlayer("Wewnętrzny błąd", user);
                    }
                }
                default -> {
                    // The original event has been cancelled, so we need to send a fake start
                    // packet. It should be safe to set a random UUID as it will be replaced by the
                    // real one later
                    receiveFakeStartPacket(username, event.getChannel(), UUID.randomUUID());
                }
            }
        } else {
            var packet = new WrapperLoginClientEncryptionResponse(event);
            var sharedSecret = packet.getEncryptedSharedSecret();

            var data = encryptionDataCache.getIfPresent(user.getAddress().toString());

            if (data == null) {
                kickPlayer("Niedozwolony stan szyfrowania", user);
                return;
            }

            var expectedToken = data.token().clone();

            if (!verifyNonce(packet, expectedToken)) {
                kickPlayer("Nieprawidłowy nonce", user);
                return;
            }

            // Verify session
            var privateKey = keyPair.getPrivate();

            SecretKey loginKey;

            try {
                loginKey = EncryptionUtil.decryptSharedKey(privateKey, sharedSecret);
            } catch (GeneralSecurityException securityEx) {
                kickPlayer("Nie można odszyfrować wspólnego sekretu", user);
                return;
            }

            try {
                if (!enableEncryption(loginKey, user, event.getChannel())) {
                    return;
                }
            } catch (Exception e) {
                kickPlayer("Nie można odszyfrować wspólnego sekretu", user);
                return;
            }

            var serverId = EncryptionUtil.getServerIdHashString("", loginKey, keyPair.getPublic());
            var username = data.username();
            var address = user.getAddress();

            try {
                if (hasJoined(username, serverId, address.getAddress())) {
                    receiveFakeStartPacket(username, event.getChannel(), data.uuid());
                } else {
                    kickPlayer("Nieprawidłowa sesja", user);
                }
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException) {
                    plugin.getLogger()
                            .warn("Przekroczono czas weryfikacji sesji (5 sekund) dla " + username);
                }
                kickPlayer("Nie można zweryfikować sesji", user);
            }
        }
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        event.setCancelled(true);

        var copy = event.clone();

        AuthenticLibreLogin.EXECUTOR.execute(
                () -> {
                    try {
                        asyncPacketReceive(copy);
                    } finally {
                        copy.cleanUp();
                    }
                });
    }

    /**
     * fake a new login packet in order to let the server handle all the other stuff
     *
     * @author games647 and FastLogin contributors
     */
    private void receiveFakeStartPacket(String username, Object channel, UUID uuid) {
        var startPacket =
                new WrapperLoginClientLoginStart(
                        getServerVersion().toClientVersion(), username, null, uuid);
        PacketEvents.getAPI().getProtocolManager().receivePacketSilently(channel, startPacket);
    }

    public boolean hasJoined(String username, String serverHash, InetAddress hostIp)
            throws IOException {
        String url;
        if (hostIp instanceof Inet6Address
                || plugin.getConfiguration().get(ConfigurationKeys.ALLOW_PROXY_CONNECTIONS)) {
            url =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s",
                            username, serverHash);
        } else {
            var encodedIP = URLEncoder.encode(hostIp.getHostAddress(), StandardCharsets.UTF_8);
            url =
                    String.format(
                            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s",
                            username, serverHash, encodedIP);
        }

        var conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        int responseCode = conn.getResponseCode();
        conn.disconnect();
        return responseCode != 204;
    }

    /**
     * @author games647 and FastLogin contributors, kyngs
     */
    private boolean enableEncryption(
            SecretKey loginKey,
            com.github.retrooper.packetevents.protocol.player.User user,
            Object channel)
            throws IllegalArgumentException {
        // Initialize method reflections
        if (encryptMethod == null) {
            Class<?> networkManagerClass =
                    SpigotReflectionUtil.getNetworkManagers().get(0).getClass();

            encryptMethod =
                    Reflection.getMethod(networkManagerClass, "setEncryptionKey", SecretKey.class);
        }

        try {
            Object networkManager = ProtocolUtil.findNetworkManager(channel);

            // Encrypt/decrypt packet flow, this behaviour is expected by the client
            encryptMethod.invoke(networkManager, loginKey);
        } catch (Exception ex) {
            kickPlayer("Nie udało się włączyć szyfrowania", user);
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    private void kickPlayer(
            String reason, com.github.retrooper.packetevents.protocol.player.User player) {
        kickPlayer(Component.text(reason), player);
    }

    private void kickPlayer(
            Component reason, com.github.retrooper.packetevents.protocol.player.User player) {
        // Cannot use Player#kick(Component) because it doesn't work in the login state
        var kickPacket = new WrapperLoginServerDisconnect(reason);
        try {
            // send kick packet at login state
            PacketEvents.getAPI().getProtocolManager().sendPacket(player.getChannel(), kickPacket);
        } finally {
            // tell the server that we want to close the connection
            player.closeConnection();
        }
    }

    /**
     * @author games647 and FastLogin contributors
     */
    private boolean verifyNonce(WrapperLoginClientEncryptionResponse packet, byte[] expectedToken) {
        try {
            byte[] nonce = packet.getEncryptedVerifyToken().get();
            return EncryptionUtil.verifyNonce(expectedToken, keyPair.getPrivate(), nonce);
        } catch (NoSuchAlgorithmException
                | InvalidKeyException
                | NoSuchPaddingException
                | IllegalBlockSizeException
                | BadPaddingException signatureEx) {
            return false;
        }
    }
}
