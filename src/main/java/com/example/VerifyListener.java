package com.example;

import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.http.WebSocket.Listener;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.security.auth.callback.Callback;

public class VerifyListener extends JavaPlugin implements Listener {

    private final OkHttpClient httpClient = new OkHttpClient();
    private static final String API_BASE = "http://127.0.0.1:8000";
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        getLogger().info("VerifyListener 啟動！");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // ========== 1. 攔移動 ==========
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            // 只有實際移動時才攔，不然部分版本可能卡住
            if (!event.getFrom().getBlock().equals(event.getTo().getBlock())) {
                event.setTo(event.getFrom());
                player.sendMessage("§c請先完成驗證（/verify 驗證碼）才能移動！");
            }
        }
    }

    // ========== 2. 只允許 /verify 指令 ==========
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            if (!msg.startsWith("/verify ")) {
                event.setCancelled(true);
                player.sendMessage("§c驗證前只能輸入 /verify 驗證碼");
            }
        }
    }

    // ========== 3. 攔其他互動/背包/物品/攻擊等 ==========
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
    public void onPickup(PlayerPickupItemEvent event) {
        checkAndHandle(event.getPlayer(), event);
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

    // ========== 4. 玩家聊天 /verify 驗證碼 ==========
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage();
        if (msg.toLowerCase().startsWith("/verify ")) {
            String code = msg.split(" ", 2)[1];
            Player player = event.getPlayer();
            String uuid = player.getUniqueId().toString();
            sendVerifyToAPI(player, uuid, code);
        }
    }

    // ========== 5. 玩家離線時移除快取 ==========
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        verifiedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // ========== check ==========
    private void checkAndHandle(Player player, Cancellable event) {
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c請先完成驗證（/verify 驗證碼）才可操作！");
        }
    }

    // ========== HTTP ==========

    // 驗證碼提交
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
                player.sendMessage("§c驗證失敗：無法聯絡伺服器！");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                if (response.isSuccessful()) {
                    player.sendMessage("§a" + result);
                    if (result.contains("驗證成功")) {
                        verifiedPlayers.add(player.getUniqueId());
                        player.sendMessage("§b你已經通過驗證，歡迎加入伺服器！");
                        // 在 console 顯示
                        getLogger().info("玩家 " + player.getName() + " (" + uuid + ") 已經通過驗證！");
                    }
                } else {
                    player.sendMessage("§c" + result);
                }
            }
        });
    }
}
