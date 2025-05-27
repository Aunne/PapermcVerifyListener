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

public class VerifyListener extends JavaPlugin implements Listener {

    private final OkHttpClient httpClient = new OkHttpClient();

    // 你的 Python API 的 base URL
    private static final String API_BASE = "http://127.0.0.1:8000";

    @Override
    public void onEnable() {
        getLogger().info("VerifyListener 啟動！");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 1. 監聽聊天：/verify 驗證碼，通報 Python 驗證
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String msg = event.getMessage();
        if (msg.toLowerCase().startsWith("/verify ")) {
            String code = msg.split(" ", 2)[1];
            Player player = event.getPlayer();
            String uuid = player.getUniqueId().toString();
            sendVerifyToAPI(player, uuid, code);
            // 你可視需求決定是否 event.setCancelled(true)
        } else {
            // 其它聊天行為也可送 API 檢查（視需求）
            sendActionToAPI(event.getPlayer(), "chat", event);
        }
    }

    // 2. 監聽玩家移動
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        sendActionToAPI(event.getPlayer(), "move", event);
    }

    // 3. 監聽方塊放置/破壞
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        sendActionToAPI(event.getPlayer(), "block_place", event);
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        sendActionToAPI(event.getPlayer(), "block_break", event);
    }

    // 4. 監聽攻擊
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            sendActionToAPI((Player)event.getDamager(), "attack", event);
        }
        if (event.getEntity() instanceof Player) {
            sendActionToAPI((Player)event.getEntity(), "be_attacked", event);
        }
    }

    // 5. 監聽互動（右鍵）
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        sendActionToAPI(event.getPlayer(), "interact", event);
    }

    // 6. 監聽撿起/丟棄物品
    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        sendActionToAPI(event.getPlayer(), "pickup", event);
    }
    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        sendActionToAPI(event.getPlayer(), "drop", event);
    }

    // 7. 監聽打開/關閉箱子（進階）
    @EventHandler
    public void onOpenInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            sendActionToAPI((Player)event.getPlayer(), "open_inventory", event);
        }
    }

    // ========== HTTP 輔助方法 ==========
    // 驗證：發送 /verify 驗證請求
    private void sendVerifyToAPI(Player player, String uuid, String code) {
        String url = API_BASE + "/verify";
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
                } else {
                    player.sendMessage("§c" + result);
                }
            }
        });
    }

    // 行為事件：發送給 API 檢查是否允許
    private void sendActionToAPI(Player player, String eventType, Cancellable event) {
        String url = API_BASE + "/check_action";
        String uuid = player.getUniqueId().toString();
        RequestBody body = new FormBody.Builder()
                .add("player", player.getName())
                .add("uuid", uuid)
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
                // Python 回傳 allow/deny
                if (!"allow".equalsIgnoreCase(result.trim())) {
                    if (event != null) event.setCancelled(true);
                    player.sendMessage("§c請先完成驗證（/verify 驗證碼）才可操作！");
                }
                // 若允許，不做事
            }
        });
    }
}
