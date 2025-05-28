package com.example;

import okhttp3.*; // ← 只要這個，完全不用 javax 相關！
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.*;
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
                    String res = result.trim().replace("\"","");
                    if ("allow".equals(res)) {
                        verifiedPlayers.add(player.getUniqueId());
                        getLogger().info("玩家 " + player.getName() + " 已經驗證過（API同步）");
                    } else {
                        verifiedPlayers.remove(player.getUniqueId());
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

        // ==== /tp 指令攔截（自動補玩家名）====
        if (msg.startsWith("/tp ")) {
            event.setCancelled(true);
            String[] args = rawMsg.trim().split("\\s+");
            String tpCmd;
            if (args.length == 4) {
                // /tp x y z -> /tp 玩家 x y z
                tpCmd = "tp " + player.getName() + " " + args[1] + " " + args[2] + " " + args[3];
            } else {
                // 其他 tp 寫法保持原樣（如 /tp A B, /tp A x y z）
                tpCmd = rawMsg.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), tpCmd);
            return;
        }

        // ==== /gamemode 指令攔截 ====
        if (msg.startsWith("/gamemode ")) {
            event.setCancelled(true);
            String[] args = rawMsg.split(" ", 3); // /gamemode creative
            String gmCmd;
            if (args.length >= 2) {
                if (args.length == 2) {
                    gmCmd = "gamemode " + args[1] + " " + player.getName();
                } else {
                    gmCmd = "gamemode " + args[1] + " " + args[2];
                }
            } else {
                player.sendMessage("§c用法: /gamemode <模式> [玩家]");
                return;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), gmCmd);
            return;
        }

        // 其餘指令可依需求加白名單
    }

    // ====== 聊天攔截 ======
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isVerifying(player)) {
            event.setCancelled(true);
            return;
        }
        String msg = event.getMessage();
        if (!verifiedPlayers.contains(player.getUniqueId())) {
            if (!msg.toLowerCase().startsWith("/verify ")) {
                event.setCancelled(true);
                player.sendMessage("§c驗證前只能發送 /verify 驗證碼 作為訊息");
            }
        }
    }

    // ====== 未驗證互動全面攔截 ======
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
                        // 驗證成功後再次查詢並同步
                        checkVerificationStatus(player);
                    });
                    Bukkit.getConsoleSender().sendMessage("§a[VerifyListener] 玩家 " + player.getName() + " 驗證成功！");
                } else {
                    Bukkit.getScheduler().runTask(VerifyListener.this, () -> {
                        player.sendMessage("§c" + result);
                    });
                    Bukkit.getConsoleSender().sendMessage("§c[VerifyListener] 玩家 " + player.getName() + " 驗證失敗：" + result);
                }
            }
        });
    }
}
