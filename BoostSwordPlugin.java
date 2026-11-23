package dev.boostsword;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BoostSwordPlugin extends JavaPlugin implements Listener {

    private NamespacedKey swordKey;

    private final Map<UUID, Long> dashReadyAt = new HashMap<>();
    private final Map<UUID, Long> boostReadyAt = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordKey = new NamespacedKey(this, "boost_sword");
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---------------------------------------------------------------- item

    private ItemStack createSword() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Skyrunner Blade", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click: Boost", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak + Right-click: Dash", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isBoostSword(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(Component.text("BoostSword config reloaded.", NamedTextColor.GREEN));
            return true;
        }

        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Usage: /boostsword <player>", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createSword()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        target.sendMessage(Component.text("You received the Skyrunner Blade!", NamedTextColor.AQUA));
        return true;
    }

    // ----------------------------------------------------------- abilities

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!isBoostSword(player.getInventory().getItemInMainHand())) return;
        if (!player.hasPermission("boostsword.use")) return;

        if (player.isSneaking()) {
            dash(player);
        } else {
            boost(player);
        }
    }

    private void dash(Player player) {
        if (onCooldown(player, dashReadyAt, "Dash")) return;

        double strength = getConfig().getDouble("dash.strength", 2.2);
        double minLift = getConfig().getDouble("dash.min-lift", 0.2);

        Vector velocity = player.getLocation().getDirection().normalize().multiply(strength);
        if (velocity.getY() < minLift) velocity.setY(minLift);
        player.setVelocity(velocity);

        startCooldown(player, dashReadyAt, getConfig().getDouble("dash.cooldown-seconds", 2));
        grantFallProtection(player);

        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.5, 0),
                25, 0.3, 0.3, 0.3, 0.05);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.4f);
    }

    private void boost(Player player) {
        if (onCooldown(player, boostReadyAt, "Boost")) return;

        double strength = getConfig().getDouble("boost.strength", 1.8);

        Vector velocity = player.getVelocity();
        velocity.setY(strength);
        player.setVelocity(velocity);

        startCooldown(player, boostReadyAt, getConfig().getDouble("boost.cooldown-seconds", 4));
        grantFallProtection(player);

        player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(),
                40, 0.3, 0.1, 0.3, 0.15);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
    }

    // ----------------------------------------------------------- cooldowns

    private boolean onCooldown(Player player, Map<UUID, Long> map, String name) {
        long remaining = map.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        if (remaining > 0) {
            player.sendActionBar(Component.text(
                    name + " ready in " + String.format("%.1f", remaining / 1000.0) + "s",
                    NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private void startCooldown(Player player, Map<UUID, Long> map, double seconds) {
        map.put(player.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
    }

    // -------------------------------------------------------- fall damage

    private void grantFallProtection(Player player) {
        double seconds = getConfig().getDouble("fall-protection-seconds", 8);
        noFallUntil.put(player.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
    }

    @EventHandler
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (noFallUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        dashReadyAt.remove(id);
        boostReadyAt.remove(id);
        noFallUntil.remove(id);
    }
}
