package net.nehaverse.sqlcreative;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Sqlcreative extends JavaPlugin implements Listener {

    private Connection connection;

    @Override
    public void onEnable() {
        setupDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Sqlcreative plugin enabled!");
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        getLogger().info("Sqlcreative plugin disabled!");
    }

    private void setupDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "inventory.db");
            // ディレクトリが存在しない場合は作成
            dbFile.getParentFile().mkdirs();

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // テーブルが存在しない場合は作成
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS player_inventories (" +
                        "uuid TEXT NOT NULL, " +
                        "gamemode TEXT NOT NULL, " +
                        "slot INTEGER NOT NULL, " +
                        "material TEXT NOT NULL, " +
                        "amount INTEGER NOT NULL, " +
                        "durability INTEGER NOT NULL, " +
                        "enchantments TEXT, " +
                        "PRIMARY KEY (uuid, gamemode, slot))");
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();

        // 現在の持ち物を保存
        saveInventory(player, player.getGameMode());

        // 新しいゲームモードの持ち物を読み込む
        loadInventory(player, newGameMode);
    }

    private void saveInventory(Player player, GameMode gameMode) {
        UUID uuid = player.getUniqueId();

        // 一旦、ゲームモードに関連する既存の持ち物情報を削除
        try (PreparedStatement deleteStmt = connection.prepareStatement(
                "DELETE FROM player_inventories WHERE uuid = ? AND gamemode = ?")) {
            deleteStmt.setString(1, uuid.toString());
            deleteStmt.setString(2, gameMode.name());
            deleteStmt.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // 新しい持ち物情報をデータベースに挿入
        try (PreparedStatement insertStmt = connection.prepareStatement(
                "INSERT INTO player_inventories (uuid, gamemode, slot, material, amount, durability, enchantments) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item != null && item.getType() != Material.AIR) {
                    insertStmt.setString(1, uuid.toString());
                    insertStmt.setString(2, gameMode.name());
                    insertStmt.setInt(3, slot);
                    insertStmt.setString(4, item.getType().name());
                    insertStmt.setInt(5, item.getAmount());
                    insertStmt.setShort(6, item.getDurability());
                    insertStmt.setString(7, serializeEnchantments(item.getEnchantments()));
                    insertStmt.addBatch();
                }
            }
            insertStmt.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadInventory(Player player, GameMode gameMode) {
        UUID uuid = player.getUniqueId();
        player.getInventory().clear();

        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT slot, material, amount, durability, enchantments FROM player_inventories WHERE uuid = ? AND gamemode = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, gameMode.name());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    Material material = Material.valueOf(rs.getString("material"));
                    int amount = rs.getInt("amount");
                    short durability = rs.getShort("durability");
                    String enchantments = rs.getString("enchantments");

                    ItemStack item = new ItemStack(material, amount);
                    item.setDurability(durability);
                    if (enchantments != null && !enchantments.isEmpty()) {
                        item.addEnchantments(deserializeEnchantments(enchantments));
                    }
                    player.getInventory().setItem(slot, item);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String serializeEnchantments(Map<Enchantment, Integer> enchantments) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(entry.getKey().getName()).append(":").append(entry.getValue());
        }
        return sb.toString();
    }

    private Map<Enchantment, Integer> deserializeEnchantments(String enchantments) {
        Map<Enchantment, Integer> map = new HashMap<>();
        String[] entries = enchantments.split(";");
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                Enchantment enchantment = Enchantment.getByName(parts[0]);
                if (enchantment != null) {
                    map.put(enchantment, Integer.parseInt(parts[1]));
                }
            }
        }
        return map;
    }
}