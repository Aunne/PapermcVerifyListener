package com.example;

import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerifyListener extends JavaPlugin implements Listener {

    private final OkHttpClient httpClient = new OkHttpClient();
    private static final String API_BASE = "http://127.0.0.1:8000";
    // thread-safe Set for verified UUIDs
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        getLogger().info("VerifyListener 啟動！");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 1. 監聽聊天（/verify 驗證碼）
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

    // 2. 監聽玩家行為（只攔截未驗證者）
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        checkAndHandle(event.getPlayer(), "block_place", event);
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        checkAndHandle(event.getPlayer(), "block_break", event);
    }
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player)
            checkAndHandle((Player)event.getDamager(), "attack", event);
        if (event.getEntity() instanceof Player)
            checkAndHandle((Player)event.getEntity(), "be_attacked", event);
    }
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        checkAndHandle(event.getPlayer(), "interact", event);
    }
    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        checkAndHandle(event.getPlayer(), "pickup", event);
    }
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        checkAndHandle(event.getPlayer(), "drop", event);
    }
    @EventHandler
    public void onOpenInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player)
            checkAndHandle((Player)event.getPlayer(), "open_inventory", event);
    }

    // 登入時自動移除快取（如你想要確保每次都驗證，可選，不加也行）
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        verifiedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // ========== 優化的 check 方法 ==========
    private void checkAndHandle(Player player, String eventType, Cancellable event) {
        UUID uuid = player.getUniqueId();
        if (verifiedPlayers.contains(uuid)) {
            // 已驗證，直接放行
            return;
        }
        // 未驗證才詢問 Python API
        sendActionToAPI(player, eventType, event, uuid);
    }

    // ========== HTTP 方法 ==========
    private void sendVerifyToAPI(Player player, String uuid, String code) {
        String url = API_BASE + "/verify_chat";
        RequestBody body = new FormBody.Builder()
                .add("player", player.getName())
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
                    // 若 API 回應包含 "驗證成功" 這幾個字，則加入 verifiedPlayers
                    if (result.contains("驗證成功")) {
                        verifiedPlayers.add(player.getUniqueId());
                    }
                } else {
                    player.sendMessage("§c" + result);
                }
            }
        });
    }

    // 事件檢查
    private void sendActionToAPI(Player player, String eventType, Cancellable event, UUID uuid) {
        String url = API_BASE + "/check_action";
        RequestBody body = new FormBody.Builder()
                .add("player", player.getName())
                .add("uuid", uuid.toString())
                .add("event_type", eventType)
                .build();
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // 後端沒回應時預設禁止
                if (event != null) event.setCancelled(true);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                if ("allow".equalsIgnoreCase(result.trim())) {
                    // 通過驗證：加進快取
                    verifiedPlayers.add(uuid);
                } else {
                    if (event != null) event.setCancelled(true);
                    player.sendMessage("§c請先完成驗證（/verify 驗證碼）才可操作！");
                }
            }
        });
    }
}
