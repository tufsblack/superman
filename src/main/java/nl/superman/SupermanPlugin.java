package nl.superman;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SupermanPlugin extends JavaPlugin implements Listener {

    // ====== Instellingen (pas aan naar wens). Snelheden zijn in blokken per tick (20 ticks = 1 sec) ======
    private static final double MAX_SPEED = 1.2;          // normaal vliegen (24 blokken/sec)
    private static final double MAX_SPRINT_SPEED = 3.0;   // snel vliegen met sprint-toets (60 blokken/sec)
    private static final double HOVER_MOVE_SPEED = 0.6;   // zijwaarts/achteruit
    private static final double VERTICAL_SPEED = 0.6;     // spatie = omhoog, shift = omlaag
    private static final double ACCELERATION = 0.10;      // hoe snel je optrekt
    private static final double BRAKE_HORIZONTAL = 0.30; // remmen zonder toetsen (zijwaarts/vooruit)
    private static final double BRAKE_VERTICAL = 0.35;   // remmen in de hoogte
    private static final double VERTICAL_RESPONSE = 0.30; // hoe snel je omhoog/omlaag reageert

    // Vasthouden: afstand voor je en hoogte vanaf je voeten (pas aan als het niet precies klopt)
    private static final double HOLD_FRONT = 0.55;
    private static final double HOLD_HEIGHT = 0.9;
    private static final double HOLD_FRONT_GLIDING = 1.6;
    private static final double HOLD_HEIGHT_GLIDING = 0.15;
    private static final double BOOM_SPEED = 2.0;         // vanaf deze snelheid krijg je een sonic boom
    private static final long BOOM_COOLDOWN_MS = 2500;

    private static final double THROW_SPEED = 2.0;        // normale worp (linkermuisknop)
    private static final double HARD_THROW_SPEED = 4.0;   // harde worp (sneaken + linkermuisknop)
    private static final double THROW_BOOM_SPEED = 3.0;   // vanaf deze worpsnelheid een sonic boom
    private static final double IMPACT_DAMAGE_PER_SPEED = 3.0; // schade bij inslag = snelheid * dit

    // ====== Interne gegevens ======
    private static class Grab {
        LivingEntity entity;
        boolean wasAware = true;
    }

    private static class State {
        boolean flying;
        boolean gliding;          // vlieganimatie (glijden) actief
        boolean gliderEquipped;   // onzichtbare vleugels staan aan
        ItemStack savedChest;     // jouw eigen borstplaat tijdens het vliegen
        boolean allowFlightBefore;
        Vector vel = new Vector();
        Location lastLoc;
        long lastBoom;
        boolean boomActive;
        Grab grab;
    }

    private static class Thrown {
        LivingEntity entity;
        UUID thrower;
        double speed;
        int ticks;
        Location last;
        boolean wasAware = true;
    }

    private final Map<UUID, State> active = new HashMap<>();
    private final Set<UUID> heldVictims = new HashSet<>();
    private final List<Thrown> thrown = new ArrayList<>();
    private NamespacedKey gliderKey;
    private NamespacedKey savedChestKey;

    @Override
    public void onEnable() {
        gliderKey = new NamespacedKey(this, "superman_glider");
        savedChestKey = new NamespacedKey(this, "saved_chest");
        getServer().getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                for (Map.Entry<UUID, State> entry : new ArrayList<>(active.entrySet())) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    State s = entry.getValue();
                    if (p == null || !p.isOnline()) continue;
                    if (p.isDead()) {
                        if (s.flying) stopFlying(p, s);
                        if (s.grab != null) releaseGrab(p, s);
                        continue;
                    }
                    if (!p.getAllowFlight()) p.setAllowFlight(true);
                    if (s.flying) flyTick(p, s);
                    if (s.grab != null) holdTick(p, s);
                    if (tick % 5 == 0) sendBar(p, s);
                }
                tickThrown();
            }
        }.runTaskTimer(this, 1L, 1L);
        getLogger().info("SupermanPlugin aan. Gebruik /superman om de modus aan of uit te zetten.");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) disable(p, false);
        }
        for (Thrown t : new ArrayList<>(thrown)) finishThrown(t);
        thrown.clear();
        active.clear();
        heldVictims.clear();
    }

    // ====== /superman ======
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Dit commando kan alleen door een speler worden gebruikt.");
            return true;
        }
        if (!p.hasPermission("superman.use")) {
            p.sendMessage(Component.text("Je hebt hier geen permissie voor.", NamedTextColor.RED));
            return true;
        }
        if (active.containsKey(p.getUniqueId())) {
            disable(p, true);
        } else {
            enable(p);
        }
        return true;
    }

    private void enable(Player p) {
        State s = new State();
        s.allowFlightBefore = p.getAllowFlight();
        active.put(p.getUniqueId(), s);
        p.setAllowFlight(true);
        p.sendMessage(Component.text("Superman-modus: ", NamedTextColor.GRAY)
                .append(Component.text("AAN", NamedTextColor.GREEN, TextDecoration.BOLD)));
        p.sendMessage(Component.text(
                "Dubbel spatie = vliegen aan/uit | Rechtermuisknop op iemand = oppakken | "
                        + "Rechtermuisknop of F = loslaten | Linkermuisknop = gooien (sneaken = harde worp)",
                NamedTextColor.GRAY));
        sendBar(p, s);
    }

    private void disable(Player p, boolean announce) {
        State s = active.remove(p.getUniqueId());
        if (s == null) return;
        if (s.grab != null) releaseGrab(p, s);
        if (s.flying) stopFlying(p, s);
        GameMode gm = p.getGameMode();
        if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
            p.setAllowFlight(s.allowFlightBefore);
        }
        p.sendActionBar(Component.empty());
        if (announce) {
            p.sendMessage(Component.text("Superman-modus: ", NamedTextColor.GRAY)
                    .append(Component.text("UIT", NamedTextColor.RED, TextDecoration.BOLD)));
        }
    }

    // ====== Actiebalk ======
    private void sendBar(Player p, State s) {
        Component bar = Component.text("Vliegmodus: ", NamedTextColor.GRAY)
                .append(s.flying
                        ? Component.text("GEACTIVEERD", NamedTextColor.GREEN, TextDecoration.BOLD)
                        : Component.text("GEDEACTIVEERD", NamedTextColor.RED, TextDecoration.BOLD));
        if (s.flying) {
            bar = bar.append(Component.text(" | " + Math.round(s.vel.length() * 20) + " blokken/sec",
                    NamedTextColor.AQUA));
            if (s.vel.length() >= BOOM_SPEED) {
                bar = bar.append(Component.text(String.format(" | Mach %.1f", machOf(s.vel.length())),
                        NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }
        if (s.grab != null) {
            bar = bar.append(Component.text(" | Vast: " + s.grab.entity.getName(), NamedTextColor.YELLOW));
        }
        p.sendActionBar(bar);
    }

    // ====== Dubbel spatie ======
    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null) return;
        if (!event.isFlying()) return;

        // Wij regelen het vliegen zelf, de normale vlucht van Minecraft blijft uit
        event.setCancelled(true);
        if (s.flying) {
            stopFlying(p, s);
        } else {
            startFlying(p, s);
        }
        sendBar(p, s);
    }

    private void startFlying(Player p, State s) {
        s.flying = true;
        s.vel = new Vector(0, 0.4, 0);
        s.lastLoc = p.getLocation();
        s.boomActive = false;
        equipGlider(p, s);
        p.setGravity(false);
        p.setFallDistance(0);
        p.setVelocity(s.vel.clone());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);

        takeoffBoom(p);
    }

    private void stopFlying(Player p, State s) {
        s.flying = false;
        s.vel = new Vector();
        s.boomActive = false;
        p.setGravity(true);
        p.setFallDistance(0);
        unequipGlider(p, s);
    }

    // ====== Vliegen (elke tick) ======
    private void flyTick(Player p, State s) {
        Input in = p.getCurrentInput();
        Location loc = p.getLocation();
        Vector look = loc.getDirection();
        Vector flat = new Vector(look.getX(), 0, look.getZ());
        if (flat.lengthSquared() > 1.0E-6) flat.normalize();
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());

        boolean forward = in.isForward();
        boolean back = in.isBackward();
        boolean left = in.isLeft();
        boolean rightKey = in.isRight();
        boolean jump = in.isJump();
        boolean sneak = in.isSneak();
        double maxSpeed = in.isSprint() ? MAX_SPRINT_SPEED : MAX_SPEED;

        // Gewenste snelheid
        Vector desired = new Vector();
        if (forward) desired.add(look.clone().multiply(maxSpeed));
        if (back) desired.add(flat.clone().multiply(-HOVER_MOVE_SPEED));
        if (left) desired.subtract(right.clone().multiply(HOVER_MOVE_SPEED));
        if (rightKey) desired.add(right.clone().multiply(HOVER_MOVE_SPEED));
        Vector desH = new Vector(desired.getX(), 0, desired.getZ());
        if (desH.length() > maxSpeed) desH.normalize().multiply(maxSpeed);
        double desY = desired.getY();
        if (jump) desY += VERTICAL_SPEED;
        if (sneak) desY -= VERTICAL_SPEED;
        desY = Math.max(-maxSpeed, Math.min(maxSpeed, desY));

        // Horizontaal en verticaal apart: zo blijft omhoog vliegen recht omhoog,
        // en remt je voorwaartse snelheid meteen af als je stopt met W.
        boolean hInput = forward || back || left || rightKey;
        boolean vInput = forward || jump || sneak;
        double rateH = hInput ? ACCELERATION : BRAKE_HORIZONTAL;
        double rateY = (jump || sneak) ? VERTICAL_RESPONSE : (vInput ? ACCELERATION : BRAKE_VERTICAL);

        Vector velH = new Vector(s.vel.getX(), 0, s.vel.getZ());
        velH.add(desH.subtract(velH).multiply(rateH));
        double velY = s.vel.getY() + (desY - s.vel.getY()) * rateY;
        s.vel = new Vector(velH.getX(), velY, velH.getZ());

        // Een blok vlak voor je? Dan rem je netjes af in plaats van er tegenaan te knallen
        double sp = s.vel.length();
        if (sp > 0.05) {
            Location mid = loc.clone().add(0, 0.9, 0);
            RayTraceResult hit = loc.getWorld().rayTraceBlocks(mid, s.vel.clone().normalize(), sp + 1.0,
                    FluidCollisionMode.NEVER, true);
            if (hit != null) {
                double allowed = Math.max(0, hit.getHitPosition().distance(mid.toVector()) - 0.8);
                if (allowed < sp) s.vel.multiply(allowed / sp);
            }
        }

        p.setVelocity(s.vel.clone());
        p.setFallDistance(0);

        // Vlieganimatie: alleen liggend als je vooruit vliegt. Recht omhoog/omlaag of zweven = rechtop.
        double speed = s.vel.length();
        double hSpeed = Math.hypot(s.vel.getX(), s.vel.getZ());
        s.gliding = p.isGliding();
        boolean verticalOnly = (jump || sneak) && !forward;
        boolean wantGlide = forward || (!verticalOnly && hSpeed > 1.2);
        if (wantGlide && !s.gliding && !p.isOnGround()) {
            p.setGliding(true);
        } else if (!wantGlide && s.gliding) {
            p.setGliding(false);
        }

        // Sonic boom
        long now = System.currentTimeMillis();
        if (speed >= BOOM_SPEED) {
            if (!s.boomActive && now - s.lastBoom > BOOM_COOLDOWN_MS) {
                double mach = machOf(speed);
                sonicBoom(p.getLocation().add(0, 1, 0), s.vel, mach, 1.5, p);
                showBoomTitle(p, mach);
                s.lastBoom = now;
            }
            s.boomActive = true;
            vaporRing(p.getLocation().add(0, 1, 0), s.vel);
        } else if (speed < BOOM_SPEED * 0.8) {
            s.boomActive = false;
        }
    }

    private void setFlatPose(Entity e, boolean flat) {
        if (flat) {
            e.setPose(Pose.SWIMMING, true);
        } else {
            e.setPose(Pose.STANDING, false);
        }
    }

    // ====== Echte sonic boom ======
    // In het spel geldt BOOM_SPEED als "geluidssnelheid" (Mach 1). Daarboven verandert de schokkegel net als
    // in het echt: halve tophoek = asin(1 / Mach). Bij Mach 1 is het een vlakke schijf, bij Mach 2 een
    // spitse kegel van 30 graden. Bij een echte sonic boom hoor je ook een dubbele knal.
    private double machOf(double speed) {
        return Math.max(1.05, Math.min(4.0, speed / BOOM_SPEED));
    }

    private Vector[] basis(Vector d) {
        Vector u = d.clone().crossProduct(new Vector(0, 1, 0));
        if (u.lengthSquared() < 1.0E-4) {
            u = new Vector(1, 0, 0);
        } else {
            u.normalize();
        }
        Vector v = d.clone().crossProduct(u).normalize();
        return new Vector[]{u, v};
    }

    private void showBoomTitle(Player p, double mach) {
        p.showTitle(Title.title(Component.empty(),
                Component.text(String.format("SONIC BOOM - Mach %.1f", mach), NamedTextColor.GOLD,
                        TextDecoration.BOLD),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(300))));
    }

    private void sonicBoom(Location loc, Vector direction, double mach, double scale, Entity... skip) {
        World w = loc.getWorld();
        Vector dir = direction.clone();
        if (dir.lengthSquared() < 1.0E-6) dir = new Vector(0, 1, 0);
        dir.normalize();
        final Vector fd = dir;
        Vector[] b = basis(fd);
        final Vector fu = b[0];
        final Vector fv = b[1];
        final Location origin = loc.clone();

        // Dubbele knal
        w.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 8f, 1.5f);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 4f, 1.3f);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            w.playSound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 8f, 1.2f);
            w.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.9f);
        }, 4L);

        // Lichtflits en kern
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        w.spawnParticle(Particle.END_ROD, loc, 60, 0.6, 0.6, 0.6, 0.35);

        final double tanMach = Math.tan(Math.asin(1.0 / mach));
        final double coneLength = 16 * scale;
        final double maxRadius = 9 * scale;

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                i++;

                // 1) Schokgolfschijf loodrecht op de vliegrichting, dijt uit en drijft naar achteren
                double ringR = (1.0 + i * 1.3) * scale;
                Location rc = origin.clone().subtract(fd.clone().multiply(i * 0.4));
                int pts = (int) (24 + ringR * 6);
                for (int k = 0; k < pts; k++) {
                    double a = k * Math.PI * 2 / pts;
                    Vector off = fu.clone().multiply(Math.cos(a) * ringR).add(fv.clone().multiply(Math.sin(a) * ringR));
                    w.spawnParticle(Particle.CLOUD, rc.clone().add(off), 1, 0, 0, 0, 0);
                }

                // 2) Machkegel (condensatiewolk): schil die in 8 ticks achter je uitgroeit
                if (i <= 8) {
                    double from = (i - 1) * coneLength / 8;
                    double to = i * coneLength / 8;
                    for (double a = from; a < to; a += 0.7) {
                        double r = Math.min(a * tanMach, maxRadius);
                        Location axis = origin.clone().subtract(fd.clone().multiply(a));
                        int n = Math.max(8, (int) (r * 5));
                        for (int k = 0; k < n; k++) {
                            double ang = k * Math.PI * 2 / n;
                            Vector off = fu.clone().multiply(Math.cos(ang) * r)
                                    .add(fv.clone().multiply(Math.sin(ang) * r));
                            w.spawnParticle(Particle.CLOUD, axis.clone().add(off), 1, 0, 0, 0, 0);
                        }
                    }
                }
                if (i >= 12) cancel();
            }
        }.runTaskTimer(this, 0L, 1L);

        // Schokgolf duwt dichtbije wezens weg
        double blastR = Math.min(12, 6 * scale);
        for (LivingEntity le : w.getNearbyLivingEntities(loc, blastR)) {
            boolean skipIt = heldVictims.contains(le.getUniqueId());
            for (Entity ex : skip) {
                if (ex == le) skipIt = true;
            }
            if (skipIt) continue;
            Vector push = le.getLocation().toVector().subtract(loc.toVector());
            if (push.lengthSquared() < 1.0E-6) continue;
            le.setVelocity(push.normalize().multiply(1.0).setY(0.4));
        }
    }

    /** Condensatieringen achter je zolang je supersonisch vliegt. */
    private void vaporRing(Location at, Vector vel) {
        if (vel.lengthSquared() < 1.0E-6) return;
        Vector d = vel.clone().normalize();
        Vector[] b = basis(d);
        Location c = at.clone().subtract(d.clone().multiply(2.5));
        World w = at.getWorld();
        for (int k = 0; k < 20; k++) {
            double a = k * Math.PI * 2 / 20;
            Vector off = b[0].clone().multiply(Math.cos(a) * 1.8).add(b[1].clone().multiply(Math.sin(a) * 1.8));
            w.spawnParticle(Particle.CLOUD, c.clone().add(off), 1, 0, 0, 0, 0);
        }
    }

    /** Sonic boom bij het opstijgen: kegel naar beneden, schokgolf over de grond en stof. */
    private void takeoffBoom(Player p) {
        final Location feet = p.getLocation();
        final World w = feet.getWorld();
        sonicBoom(feet.clone().add(0, 0.6, 0), new Vector(0, 1, 0), 1.5, 1.2, p);

        Block below = feet.clone().subtract(0, 0.5, 0).getBlock();
        final BlockData data = below.getType().isAir() ? null : below.getBlockData();
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                i++;
                double r = i * 1.4;
                int pts = (int) (16 + r * 8);
                for (int k = 0; k < pts; k++) {
                    double a = k * Math.PI * 2 / pts;
                    Location pt = feet.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r);
                    w.spawnParticle(Particle.CLOUD, pt, 1, 0, 0, 0, 0);
                    if (data != null) {
                        w.spawnParticle(Particle.BLOCK, pt, 2, 0.2, 0.1, 0.2, 0.05, data);
                    }
                }
                if (i >= 9) cancel();
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ====== Oppakken (rechtermuisknop op een wezen) ======
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null) return;

        if (s.grab != null) {
            event.setCancelled(true);
            releaseGrab(p, s);
            sendBar(p, s);
            return;
        }
        if (!(event.getRightClicked() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        if (heldVictims.contains(victim.getUniqueId())) return;
        if (victim instanceof Player vp && vp.getGameMode() == GameMode.SPECTATOR) return;

        event.setCancelled(true);
        grab(p, s, victim);
        sendBar(p, s);
    }

    private void grab(Player p, State s, LivingEntity victim) {
        Grab g = new Grab();
        g.entity = victim;
        if (victim instanceof Mob mob) {
            g.wasAware = mob.isAware();
            mob.setAware(false);
        }
        victim.setGravity(false);
        victim.setPose(Pose.SWIMMING, true);
        s.grab = g;
        heldVictims.add(victim.getUniqueId());
        p.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 1.2f);
    }

    /** Houdt het slachtoffer elke tick midden voor je vast: liggend, op heuphoogte bij je handen. */
    private void holdTick(Player p, State s) {
        LivingEntity v = s.grab.entity;
        if (!v.isValid() || v.isDead() || v.getWorld() != p.getWorld()) {
            releaseGrab(p, s);
            return;
        }
        Location pl = p.getLocation();
        Vector look = pl.getDirection();
        double yaw = Math.toRadians(pl.getYaw());
        Vector f = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));

        Location target;
        if (s.gliding) {
            // Jij ligt horizontaal: het slachtoffer ligt recht voor je uit in jouw kijkrichting
            target = pl.clone().add(look.clone().multiply(HOLD_FRONT_GLIDING)).add(0, HOLD_HEIGHT_GLIDING, 0);
        } else {
            target = pl.clone().add(f.clone().multiply(HOLD_FRONT)).add(0, HOLD_HEIGHT, 0);
        }
        if (s.flying) target.add(s.vel);
        target.setYaw(pl.getYaw());
        target.setPitch(0f);

        v.teleport(target);
        v.setVelocity(new Vector());
        v.setFallDistance(0);
    }

    /** Laat het slachtoffer los zonder te gooien. */
    private void releaseGrab(Player p, State s) {
        Grab g = s.grab;
        s.grab = null;
        if (g == null) return;
        heldVictims.remove(g.entity.getUniqueId());
        restoreVictim(g.entity, g.wasAware);
        if (g.entity.isValid()) g.entity.setVelocity(new Vector());
    }

    private void restoreVictim(LivingEntity v, boolean wasAware) {
        v.setGravity(true);
        v.setPose(Pose.STANDING, false);
        if (v instanceof Mob mob) mob.setAware(wasAware);
    }

    // Loslaten met rechtermuisknop (met iets in je hand / op een blok) of met de F-toets
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null || s.grab == null) return;

        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            releaseGrab(p, s);
            sendBar(p, s);
        } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            throwVictim(p, s);
            sendBar(p, s);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null || s.grab == null) return;
        event.setCancelled(true);
        releaseGrab(p, s);
        sendBar(p, s);
    }

    // Linkermuisknop terwijl je iemand vasthoudt = gooien (ook als je de persoon zelf raakt)
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        State s = active.get(p.getUniqueId());
        if (s == null || s.grab == null) return;
        event.setCancelled(true);
        throwVictim(p, s);
        sendBar(p, s);
    }

    // ====== Gooien ======
    private void throwVictim(Player p, State s) {
        Grab g = s.grab;
        s.grab = null;
        if (g == null) return;
        LivingEntity v = g.entity;
        heldVictims.remove(v.getUniqueId());
        if (!v.isValid()) return;

        Vector dir = p.getLocation().getDirection().normalize();
        double speed = p.isSneaking() ? HARD_THROW_SPEED : THROW_SPEED;
        if (s.flying) speed += Math.max(0, s.vel.dot(dir));
        speed = Math.min(speed, 3.9); // hogere snelheden kan Minecraft niet versturen

        v.setGravity(true);
        v.setVelocity(dir.clone().multiply(speed));

        Thrown t = new Thrown();
        t.entity = v;
        t.thrower = p.getUniqueId();
        t.speed = speed;
        t.wasAware = g.wasAware;
        t.last = v.getLocation();
        thrown.add(t);

        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 2f, 0.7f);
        boolean flyingFast = s.flying && s.vel.length() >= BOOM_SPEED;
        if (speed >= THROW_BOOM_SPEED || flyingFast) {
            double mach = machOf(speed);
            sonicBoom(v.getLocation().add(0, 0.5, 0), dir, mach, 1.8, p, v);
            showBoomTitle(p, mach);
        }
    }

    private void tickThrown() {
        Iterator<Thrown> it = thrown.iterator();
        while (it.hasNext()) {
            Thrown t = it.next();
            LivingEntity v = t.entity;
            if (!v.isValid() || v.isDead()) {
                it.remove();
                continue;
            }
            t.ticks++;
            v.setFallDistance(0);
            Location l = v.getLocation();
            World w = l.getWorld();
            w.spawnParticle(Particle.CLOUD, l.clone().add(0, 0.4, 0), 5, 0.2, 0.2, 0.2, 0.02);
            if (t.speed >= THROW_BOOM_SPEED) {
                w.spawnParticle(Particle.FLAME, l.clone().add(0, 0.4, 0), 3, 0.2, 0.2, 0.2, 0.01);
            }

            double moved = (t.last != null && t.last.getWorld() == w) ? l.distance(t.last) : 99;
            t.last = l;
            boolean landed = t.ticks > 3 && (v.isOnGround() || moved < 0.15);
            if (landed) {
                impact(t, l);
            }
            if (landed || t.ticks > 120) {
                finishThrown(t);
                it.remove();
            }
        }
    }

    private void impact(Thrown t, Location l) {
        LivingEntity v = t.entity;
        World w = l.getWorld();
        double dmg = Math.min(t.speed * IMPACT_DAMAGE_PER_SPEED, 40);
        Player thrower = Bukkit.getPlayer(t.thrower);
        if (thrower != null) {
            v.damage(dmg, thrower);
        } else {
            v.damage(dmg);
        }
        w.spawnParticle(Particle.EXPLOSION, l, 1);
        w.spawnParticle(Particle.CLOUD, l, 40, 0.8, 0.3, 0.8, 0.1);
        Block below = l.clone().subtract(0, 0.3, 0).getBlock();
        if (!below.getType().isAir()) {
            w.spawnParticle(Particle.BLOCK, l, 60, 0.6, 0.2, 0.6, 0.1, below.getBlockData());
        }
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.8f);
    }

    private void finishThrown(Thrown t) {
        if (t.entity.isValid()) {
            restoreVictim(t.entity, t.wasAware);
        }
    }

    // ====== Opruimen ======
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        disable(event.getPlayer(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        State s = active.get(p.getUniqueId());
        if (s == null) return;
        if (s.grab != null) releaseGrab(p, s);
        if (s.gliderEquipped) {
            // Vleugels nooit laten vallen; jouw eigen borstplaat wel terugbezorgen
            event.getDrops().removeIf(this::isGlider);
            event.getItemsToKeep().removeIf(this::isGlider);
            ItemStack saved = s.savedChest;
            s.savedChest = null;
            s.gliderEquipped = false;
            p.getPersistentDataContainer().remove(savedChestKey);
            if (event.getKeepInventory()) {
                p.getInventory().setChestplate(saved);
            } else if (saved != null) {
                event.getDrops().add(saved);
            }
        }
        if (s.flying) stopFlying(p, s);
    }

    // ====== Onzichtbare vleugels: zorgen voor de echte vlieganimatie, ook in F5 ======
    private ItemStack createGlider() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Superman-vleugels", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.setGlider(true);
        EquippableComponent eq = meta.getEquippable();
        eq.setSlot(EquipmentSlot.CHEST);
        meta.setEquippable(eq);
        meta.getPersistentDataContainer().set(gliderKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isGlider(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(gliderKey, PersistentDataType.BYTE);
    }

    private void equipGlider(Player p, State s) {
        PlayerInventory inv = p.getInventory();
        ItemStack current = inv.getChestplate();
        if (isGlider(current)) {
            s.gliderEquipped = true;
            return;
        }
        if (current != null && !current.getType().isAir()) {
            s.savedChest = current.clone();
            // Ook veilig opgeslagen op de speler, voor het geval de server crasht tijdens het vliegen
            p.getPersistentDataContainer().set(savedChestKey, PersistentDataType.BYTE_ARRAY,
                    current.serializeAsBytes());
        }
        inv.setChestplate(createGlider());
        s.gliderEquipped = true;
    }

    private void unequipGlider(Player p, State s) {
        if (!s.gliderEquipped) return;
        s.gliderEquipped = false;
        p.setGliding(false);
        s.gliding = false;
        PlayerInventory inv = p.getInventory();
        if (isGlider(inv.getChestplate())) {
            inv.setChestplate(s.savedChest);
        } else if (s.savedChest != null) {
            returnItem(p, s.savedChest);
        }
        s.savedChest = null;
        p.getPersistentDataContainer().remove(savedChestKey);
    }

    private void returnItem(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (active.containsKey(p.getUniqueId())) return;
        p.setGravity(true);
        PlayerInventory inv = p.getInventory();
        byte[] data = p.getPersistentDataContainer().get(savedChestKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) {
            if (isGlider(inv.getChestplate())) inv.setChestplate(null);
            return;
        }
        ItemStack saved = ItemStack.deserializeBytes(data);
        ItemStack chest = inv.getChestplate();
        if (isGlider(chest) || chest == null || chest.getType().isAir()) {
            inv.setChestplate(saved);
        } else {
            returnItem(p, saved);
        }
        p.getPersistentDataContainer().remove(savedChestKey);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (isGlider(event.getCurrentItem()) || isGlider(event.getCursor())) {
            event.setCancelled(true);
        }
    }
}
