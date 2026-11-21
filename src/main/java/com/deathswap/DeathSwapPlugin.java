package com.deathswap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class DeathSwapPlugin extends JavaPlugin implements Listener, TabExecutor {

    // ---- Settings you may want to change ----
    private static final int SWAP_INTERVAL_SECONDS = 5 * 60; // swap every 5 minutes
    private static final int WARNING_SECONDS = 30;           // warn when 30s left
    private static final Material WAND_MATERIAL = Material.BLAZE_ROD; // the "gold wand" item
    private static final boolean WAND_SINGLE_USE = true;     // false = wand is reusable

    private static final Set<String> BANNED_ITEMS = Set.of(
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "COMMAND_BLOCK_MINECART", "BARRIER", "LIGHT", "STRUCTURE_BLOCK",
            "STRUCTURE_VOID", "JIGSAW", "DEBUG_STICK", "BEDROCK", "KNOWLEDGE_BOOK",
            "SPAWNER", "TRIAL_SPAWNER", "VAULT", "REINFORCED_DEEPSLATE",
            "END_PORTAL_FRAME", "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK"
    );

    private NamespacedKey wandKey;
    private boolean running = false;
    private int secondsLeft;
    private BukkitTask gameTask;

    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, BukkitTask> creativeTasks = new HashMap<>();
    private final Map<UUID, int[]> creativeEventCounter = new HashMap<>(); // {tick, count}

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "wand_seconds");
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("deathswap");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (running) endGame(null);
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(text("Usage: /deathswap start <wand seconds> | /deathswap stop", NamedTextColor.RED));
            return true;
        }

        if (args[0].equalsIgnoreCase("stop")) {
            if (!running) {
                sender.sendMessage(text("No deathswap game is running.", NamedTextColor.RED));
                return true;
            }
            endGame(text("Deathswap was stopped.", NamedTextColor.YELLOW));
            return true;
        }

        if (args[0].equalsIgnoreCase("start")) {
            if (running) {
                sender.sendMessage(text("A game is already running. Use /deathswap stop first.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(text("Usage: /deathswap start <wand seconds>", NamedTextColor.RED));
                return true;
            }
            int wandSeconds;
            try {
                wandSeconds = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(text("The number must be a whole number of seconds.", NamedTextColor.RED));
                return true;
            }
            if (wandSeconds < 1) {
                sender.sendMessage(text("The number must be at least 1.", NamedTextColor.RED));
                return true;
            }
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.size() < 2) {
                sender.sendMessage(text("You need at least 2 players online.", NamedTextColor.RED));
                return true;
            }
            startGame(players, wandSeconds);
            return true;
        }

        sender.sendMessage(text("Usage: /deathswap start <wand seconds> | /deathswap stop", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return List.of("10", "30", "60");
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Game flow
    // ------------------------------------------------------------------

    private void startGame(List<Player> players, int wandSeconds) {
        running = true;
        secondsLeft = SWAP_INTERVAL_SECONDS;
        alive.clear();
        eliminated.clear();

        for (Player p : players) {
            alive.add(p.getUniqueId());
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().addItem(createWand(wandSeconds));
        }

        Bukkit.broadcast(text("Deathswap started! You swap places every "
                + (SWAP_INTERVAL_SECONDS / 60) + " minutes. Kill the others with a trap!", NamedTextColor.GREEN));
        Bukkit.broadcast(text("Everyone got a Gold Wand: right click for " + wandSeconds
                + "s of creative mode.", NamedTextColor.GOLD));

        gameTask = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    private void tick() {
        secondsLeft--;
        if (secondsLeft == WARNING_SECONDS) {
            Bukkit.broadcast(text("Swapping in " + WARNING_SECONDS + " seconds!", NamedTextColor.RED));
        }
        if (secondsLeft <= 0) {
            swap();
            secondsLeft = SWAP_INTERVAL_SECONDS;
        }
    }

    private void swap() {
        List<Player> players = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) players.add(p);
        }
        if (players.size() < 2) return;

        List<Location> locations = new ArrayList<>();
        for (Player p : players) locations.add(p.getLocation().clone());

        for (int i = 0; i < players.size(); i++) {
            players.get(i).teleport(locations.get((i + 1) % players.size()));
        }
        Bukkit.broadcast(text("SWAP!", NamedTextColor.GOLD));
    }

    private void endGame(Component message) {
        running = false;
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        for (BukkitTask t : creativeTasks.values()) t.cancel();
        creativeTasks.clear();
        creativeEventCounter.clear();

        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                removeWands(p);
            }
        }
        alive.clear();
        eliminated.clear();
        if (message != null) Bukkit.broadcast(message);
    }

    private void eliminate(Player p) {
        if (!alive.remove(p.getUniqueId())) return;
        eliminated.add(p.getUniqueId());
        BukkitTask t = creativeTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();

        if (alive.size() <= 1) {
            String winner = "nobody";
            for (UUID id : alive) {
                Player w = Bukkit.getPlayer(id);
                if (w != null) winner = w.getName();
            }
            endGame(text("Deathswap over! Winner: " + winner, NamedTextColor.GREEN));
        }
    }

    // ------------------------------------------------------------------
    // Wand
    // ------------------------------------------------------------------

    private ItemStack createWand(int seconds) {
        ItemStack item = new ItemStack(WAND_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("Gold Wand", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                text("Right click for " + seconds + "s of creative mode", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.INTEGER, seconds);
        item.setItemMeta(meta);
        return item;
    }

    private Integer getWandSeconds(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.INTEGER);
    }

    private void removeWands(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (getWandSeconds(contents[i]) != null) p.getInventory().setItem(i, null);
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (getWandSeconds(off) != null) p.getInventory().setItemInOffHand(null);
    }

    @EventHandler
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        Integer seconds = getWandSeconds(item);
        if (seconds == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!running || !alive.contains(p.getUniqueId())) return;

        if (creativeTasks.containsKey(p.getUniqueId())) {
            p.sendMessage(text("You are already in creative mode.", NamedTextColor.RED));
            return;
        }

        if (WAND_SINGLE_USE) item.setAmount(item.getAmount() - 1);

        p.setGameMode(GameMode.CREATIVE);
        p.sendMessage(text("Creative mode for " + seconds + " seconds!", NamedTextColor.GOLD));

        UUID id = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            creativeTasks.remove(id);
            creativeEventCounter.remove(id);
            Player pl = Bukkit.getPlayer(id);
            if (pl != null && running && alive.contains(id)) {
                pl.setGameMode(GameMode.SURVIVAL);
                pl.sendMessage(text("Creative mode ended.", NamedTextColor.YELLOW));
            }
        }, seconds * 20L);
        creativeTasks.put(id, task);
    }

    // ------------------------------------------------------------------
    // Creative restrictions
    // ------------------------------------------------------------------

    @EventHandler
    public void onCreativeInventory(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!creativeTasks.containsKey(p.getUniqueId())) return;

        // Block saved hotbars: loading one sends many slot updates in a single tick.
        int tick = Bukkit.getCurrentTick();
        int[] counter = creativeEventCounter.computeIfAbsent(p.getUniqueId(), k -> new int[]{-1, 0});
        if (counter[0] == tick) {
            counter[1]++;
        } else {
            counter[0] = tick;
            counter[1] = 1;
        }
        if (counter[1] > 1) {
            e.setCancelled(true);
            return;
        }

        // Block operator items.
        ItemStack cursor = e.getCursor();
        if (cursor != null && cursor.getType() != Material.AIR && isBanned(cursor)) {
            e.setCancelled(true);
            p.sendMessage(text("You can't take that item.", NamedTextColor.RED));
        }
    }

    private boolean isBanned(ItemStack item) {
        String name = item.getType().name();
        if (BANNED_ITEMS.contains(name) || name.endsWith("_SPAWN_EGG")) return true;
        // Block copying containers with contents (ctrl + middle click).
        ItemMeta meta = item.getItemMeta();
        return meta instanceof BlockStateMeta bsm && bsm.hasBlockState();
    }

    // ------------------------------------------------------------------
    // Death / leave handling
    // ------------------------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!running) return;
        Player p = e.getEntity();
        if (!alive.contains(p.getUniqueId())) return;
        Bukkit.broadcast(text(p.getName() + " died and is out!", NamedTextColor.RED));
        eliminate(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (running && eliminated.contains(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(this, () -> p.setGameMode(GameMode.SPECTATOR));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!running) return;
        Player p = e.getPlayer();
        if (alive.contains(p.getUniqueId())) {
            Bukkit.broadcast(text(p.getName() + " left and is out!", NamedTextColor.RED));
            p.setGameMode(GameMode.SURVIVAL);
            eliminate(p);
        }
    }

    // ------------------------------------------------------------------

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color);
    }
}
