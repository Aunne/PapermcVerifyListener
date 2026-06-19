package com.example;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerifyListener extends JavaPlugin implements Listener {

    private final OkHttpClient httpClient = new OkHttpClient();
    private static final String API_BASE = "http://127.0.0.1:8000";
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> verifyingPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        getLogger().info("VerifyListener 啟動！");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ====== 玩家登入時查詢驗證狀態 ======
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        verifyingPlayers.add(player.getUniqueId());
        checkVerificationStatus(player);
    }

    // 查詢 API 驗證狀態
    private void checkVerificationStatus(Player player) {
        String url = API_BASE + "/check_action";
        RequestBody body = new FormBody.Builder()
                .add("uuid", player.getUniqueId().toString())
                .build();
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                    verifyingPlayers.remove(player.getUniqueId());
                    verifiedPlayers.remove(player.getUniqueId());
                    getLogger().warning("無法連線到驗證後端，無法確認 " + player.getName() + " 狀態");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                    verifyingPlayers.remove(player.getUniqueId());
                    String res = result.trim().replace("\"", "");
                    if ("allow".equals(res)) {
                        verifiedPlayers.add(player.getUniqueId());

                        // 重新同步玩家可用指令，讓 /tp /gamemode 候選字更新
                        player.updateCommands();

                        getLogger().info("玩家 " + player.getName() + " 已經驗證過（API同步）");
                    } else {
                        verifiedPlayers.remove(player.getUniqueId());

                        // 未驗證時也刷新一次，避免候選字殘留
                        player.updateCommands();

                        getLogger().info("玩家 " + player.getName() + " 尚未驗證（API同步）");
                    }
                });
            }
        });
    }

    // 檢查是否驗證中
    private boolean isVerifying(Player player) {
        if (verifyingPlayers.contains(player.getUniqueId())) {
            player.sendMessage("§e驗證狀態同步中，請稍後...");
            return true;
        }
        return false;
    }

    // ====== 玩家移動攔截 ======
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isVerifying(player)) {
            event.setTo(event.getFrom());
            return;
        }
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            if (!event.getFrom().getBlock().equals(event.getTo().getBlock())) {
                event.setTo(event.getFrom());
                player.sendMessage("§c請先完成驗證（/verify 驗證碼）才能移動！");
            }
        }
    }

    // ====== 指令攔截 ======
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String rawMsg = event.getMessage();
        String msg = rawMsg.toLowerCase();

        if (isVerifying(player)) {
            event.setCancelled(true);
            return;
        }

        // /verify 指令永遠允許
        if (msg.startsWith("/verify ")) {
            event.setCancelled(true);

            String[] parts = rawMsg.split(" ", 2);
            if (parts.length < 2 || parts[1].isEmpty()) {
                player.sendMessage("§c請輸入驗證碼，例如 /verify 你的驗證碼");
                return;
            }

            String code = parts[1];
            String uuid = player.getUniqueId().toString();
            sendVerifyToAPI(player, uuid, code);
            return;
        }

        // 未驗證只能 /verify
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c驗證前只能執行 /verify 驗證碼");
            return;
        }

        // 已驗證玩家：不攔截指令
        // /tp、/gamemode 是否能用，交給 LuckPerms 判斷
    }

    // ====== 聊天攔截 ======
    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        if (isVerifying(player)) {
            event.setCancelled(true);
            return;
        }

        String msg = PlainTextComponentSerializer.plainText()
                .serialize(event.message());

        if (!verifiedPlayers.contains(player.getUniqueId())) {
            if (!msg.toLowerCase().startsWith("/verify ")) {
                event.setCancelled(true);
                player.sendMessage("§c驗證前只能發送 /verify 驗證碼 作為訊息");
            }
        }
    }

    // ====== 未驗證互動全面攔截 ======
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        checkAndHandle(event.getPlayer(), event);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        checkAndHandle(event.getPlayer(), event);
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player)
            checkAndHandle((Player) event.getDamager(), event);
        if (event.getEntity() instanceof Player)
            checkAndHandle((Player) event.getEntity(), event);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        checkAndHandle(event.getPlayer(), event);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            checkAndHandle(player, event);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        checkAndHandle(event.getPlayer(), event);
    }

    @EventHandler
    public void onOpenInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player)
            checkAndHandle((Player) event.getPlayer(), event);
    }

    // 玩家離線時移除快取與同步中狀態
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        verifiedPlayers.remove(uuid);
        verifyingPlayers.remove(uuid);
    }

    // 工具：檢查是否驗證
    private void checkAndHandle(Player player, Cancellable event) {
        if (isVerifying(player)) {
            event.setCancelled(true);
            return;
        }
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c請先完成驗證（/verify 驗證碼）才可操作！");
        }
    }

    // HTTP 驗證流程
    private void sendVerifyToAPI(Player player, String uuid, String code) {
        String url = API_BASE + "/verify";
        RequestBody body = new FormBody.Builder()
                .add("uuid", uuid)
                .add("code", code)
                .build();
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                    player.sendMessage("§c驗證失敗：無法聯絡伺服器！");
                });
                Bukkit.getConsoleSender().sendMessage("§c[VerifyListener] 玩家 " + player.getName() + " 驗證失敗（無法聯絡伺服器）");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                if (response.isSuccessful() && result.contains("驗證成功")) {
                    Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                        player.sendMessage("§a" + result);

                        // 後端已經驗證成功，先讓外掛本地狀態通過
                        verifiedPlayers.add(player.getUniqueId());

                        // 後端已經用 LuckPerms 給權限，刷新候選字
                        player.updateCommands();

                        // 再向後端同步一次狀態
                        checkVerificationStatus(player);
                    });
                    Bukkit.getConsoleSender().sendMessage("§a[VerifyListener] 玩家 " + player.getName() + " 驗證成功！");
                } else {
                    Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                        player.sendMessage("§c" + result);
                    });
                    Bukkit.getConsoleSender()
                            .sendMessage("§c[VerifyListener] 玩家 " + player.getName() + " 驗證失敗：" + result);
                }
            }
        });
    }
}
