package net.junsuzu.syncedNet;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.proxy.player.TabListEntry.Builder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Plugin(id = "syncednet", name = "SyncedNet", version = BuildConstants.VERSION, description = "MGMGのPlugin", url = "jun-suzu.net", authors = {"JUN-SUZU"})
public class SyncedNet {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("syncnet:gate");

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // プラグインの初期化処理
        logger.info("SyncedNet plugin has been initialized.");
        // タブリストの同期を全プレイヤーに適用
        syncAllTabLists();
        server.getChannelRegistrar().register(CHANNEL);
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return; // イベントの発生元がプレイヤーでない場合は無視
        }

        String message = event.getMessage();
        Player sender = (Player) event.getPlayer();

        // 各サーバーのプレイヤーにメッセージを送信(ただし、送信者と同じサーバーには送信しない)
        for (Player player : server.getAllPlayers()) {
            if (!player.getCurrentServer().isPresent() || player.getCurrentServer().get().getServerInfo().equals(sender.getCurrentServer().get().getServerInfo())) {
                continue; // 送信者と同じサーバーのプレイヤーには送信しない
            }
            player.sendMessage(Component.text(sender.getUsername() + ": " + message));
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();

        // ここでタブリストを同期
        syncTabList(player);
    }

    @Subscribe
    public void onPlayerJoin(ServerConnectedEvent event) {
        Player player = event.getPlayer();

        syncAllTabLists();
    }

    public void syncAllTabLists() {
        // 全てのプレイヤーのタブリストを同期
        for (Player player : server.getAllPlayers()) {
            syncTabList(player);
        }
    }

    public void syncTabList(Player player) {
        TabList tabList = player.getTabList();
        // タブリストをクリア
        tabList.clearAll();
        // 全てのプレイヤーのタブリストエントリーを追加
        for (Player otherPlayer : server.getAllPlayers()) {
            GameProfile profile = otherPlayer.getGameProfile();
            Builder entryBuilder = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(profile)
                    .displayName(Component.text(otherPlayer.getUsername()))
                    .latency((int) otherPlayer.getPing());
            tabList.addEntry(entryBuilder.build());
        }
    }

    @Inject
    public void registerCommands() {
        server.getCommandManager().register("syncnet", new SyncNetCommand(server));
    }

    public static class SyncNetCommand implements SimpleCommand {

        private final ProxyServer server;

        public SyncNetCommand(ProxyServer server) {
            this.server = server;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            // 権限チェック velocity.command.syncnet
            if (!source.hasPermission("velocity.command.syncnet")){
                source.sendMessage(Component.text("権限がありません。"));
                return;
            }

            if (args.length != 3 || !args[0].equalsIgnoreCase("server")) {
                source.sendMessage(Component.text("使い方: /syncNet server <サーバー名> <ユーザー名>"));
                return;
            }

            String serverName = args[1];
            String targetPlayerName = args[2];

            Optional<Player> targetPlayer = server.getPlayer(targetPlayerName);
            if (targetPlayer.isEmpty()) {
                source.sendMessage(Component.text("プレイヤーが見つかりません。"));
                return;
            }

            Optional<RegisteredServer> targetServer = server.getServer(serverName);
            if (targetServer.isEmpty()) {
                source.sendMessage(Component.text("サーバーが見つかりません。"));
                return;
            }

            // 移動実行
            targetPlayer.get().createConnectionRequest(targetServer.get()).fireAndForget();
            source.sendMessage(Component.text(targetPlayerName + " を " + serverName + " に移動しました。"));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();

            if (args.length == 1) {
                // 最初の引数（固定: "server"）
                return Collections.singletonList("server").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("server")) {
                // 2番目の引数（サーバー名）
                return server.getAllServers().stream()
                        .map(s -> s.getServerInfo().getName())
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (args.length == 3 && args[0].equalsIgnoreCase("server")) {
                // 3番目の引数（ユーザー名）
                return server.getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }

            return Collections.emptyList();
        }
    }


    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String action = in.readUTF();
        ServerConnection serverConn = (ServerConnection) event.getSource();
        if ("gate_enter".equals(action)) {
            String gateId = in.readUTF();
            String targetServer = in.readUTF();
            String targetGate = in.readUTF();
            UUID playerUUID = UUID.fromString(in.readUTF());
            // 送信元と転送先が同じサーバーの場合は無視
            if (serverConn.getServerInfo().getName().equalsIgnoreCase(targetServer)) {
                return;
            }
            Optional<RegisteredServer> dest = server.getServer(targetServer);
            dest.ifPresent(server -> {
                // send gate_enter to targetServer backend
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("gate_enter");
                out.writeUTF(targetGate);
                out.writeUTF(playerUUID.toString());
                server.sendPluginMessage(CHANNEL, out.toByteArray());
            });
        }
        else if ("gate_prepared".equals(action)) {
            UUID playerUUID = UUID.fromString(in.readUTF());
            Optional<Player> optPlayer = server.getPlayer(playerUUID);
            if (optPlayer.isEmpty()) return;
            Player pl = optPlayer.get();
            logger.info("Gate prepared for player: " + pl.getUsername() + " on server: " + serverConn.getServerInfo().getName());
            Optional<RegisteredServer> dest = pl.getCurrentServer().flatMap(sc -> Optional.of(sc.getServer()));
            if (dest.isEmpty()) return;
            pl.createConnectionRequest(dest.get()).connect().thenAccept(result -> {
                // 転送後の処理（必要なら）
            });
        }
    }
}
