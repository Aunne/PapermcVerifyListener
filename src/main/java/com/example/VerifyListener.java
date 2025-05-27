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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VerifyListener extends JavaPlugin implements Listener {

    private final OkHttpClient httpClient = new OkHttpClient();
    private static final String API_BASE = "http://127.0.0.1:8000";
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        getLogger().info("VerifyListener 啟動！");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // 1. 移動攔截
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            if (!event.getFrom().getBlock().equals(event.getTo().getBlock())) {
                event.setTo(event.getFrom());
                player.sendMessage("§c請先完成驗證（/verify 驗證碼）才能移動！");
            }
        }
    }

    // 2. 只允許執行 /verify 指令
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage();
        if (msg.toLowerCase().startsWith("/verify ")) {
            event.setCancelled(true); // 攔下不讓伺服器處理
            String[] parts = msg.split(" ", 2);
            if (parts.length < 2 || parts[1].isEmpty()) {
                player.sendMessage("§c請輸入驗證碼，例如 /verify 你的驗證碼");
                return;
            }
            String code = parts[1];
            String uuid = player.getUniqueId().toString();
            sendVerifyToAPI(player, uuid, code);
            return;
        }
        // 其他指令全部攔截
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c驗證前只能執行 /verify 驗證碼");
        }
    }

    // 3. 禁止發送非 /verify 聊天
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String msg = event.getMessage();
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            if (!msg.toLowerCase().startsWith("/verify ")) {
                event.setCancelled(true);
                player.sendMessage("§c驗證前只能發送 /verify 驗證碼 作為訊息");
            }
        }
    }

    // 4. 禁止未驗證者進行互動、背包、物品、攻擊等
    @EventHandler public void onBlockPlace(BlockPlaceEvent event) { checkAndHandle(event.getPlayer(), event); }
    @EventHandler public void onBlockBreak(BlockBreakEvent event) { checkAndHandle(event.getPlayer(), event); }
    @EventHandler public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player)
            checkAndHandle((Player)event.getDamager(), event);
        if (event.getEntity() instanceof Player)
            checkAndHandle((Player)event.getEntity(), event);
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) { checkAndHandle(event.getPlayer(), event); }
    @EventHandler public void onPickup(PlayerPickupItemEvent event) { checkAndHandle(event.getPlayer(), event); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { checkAndHandle(event.getPlayer(), event); }
    @EventHandler public void onOpenInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player)
            checkAndHandle((Player)event.getPlayer(), event);
    }

    // 5. 玩家離線時移除快取
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        verifiedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // ==== 工具 ====
    private void checkAndHandle(Player player, Cancellable event) {
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c請先完成驗證（/verify 驗證碼）才可操作！");
        }
    }

    // ==== HTTP ====
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
                Bukkit.getConsoleSender().sendMessage("§c[VerifyListener] 玩家 " + player.getName() + " 驗證失敗（無法聯絡伺服器）");
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String result = response.body().string();
                if (response.isSuccessful() && result.contains("驗證成功")) {
                    player.sendMessage("§a" + result);
                    Bukkit.getConsoleSender().sendMessage("§a[VerifyListener] 玩家 " + player.getName() + " 驗證成功！");
                    verifiedPlayers.add(player.getUniqueId());
                } else {
                    player.sendMessage("§c" + result);
                    Bukkit.getConsoleSender().sendMessage("§c[VerifyListener] 玩家 " + player.getName() + " 驗證失敗：" + result);
                }
            }
        });
    }
}
