package nl.superman;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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
    private static final double BRAKE = 0.25;             // hoe snel je stilstaat als je geen toets indrukt
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
        boolean posed;            // liggende vliegpose actief
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

    @Override
    public void onEnable() {
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
        if (s.posed) {
            setFlatPose(p, false);
            s.posed = false;
        }
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
        p.setGravity(false);
        p.setFallDistance(0);
        p.setVelocity(s.vel.clone());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
    }

    private void stopFlying(Player p, State s) {
        s.flying = false;
        s.vel = new Vector();
        s.boomActive = false;
        p.setGravity(true);
        p.setFallDistance(0);
        if (s.posed) {
            setFlatPose(p, false);
            s.posed = false;
        }
    }

    // ====== Vliegen (elke tick) ======
    private void flyTick(Player p, State s) {
        Input in = p.getCurrentInput();
        Location loc = p.getLocation();
        Vector look = loc.getDirection();
        Vector flat = new Vector(look.getX(), 0, look.getZ());
        if (flat.lengthSquared() > 1.0E-6) flat.normalize();
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());

        // Tegen iets aangevlogen? Dan remmen we sterk af.
        if (s.lastLoc != null && s.lastLoc.getWorld() == loc.getWorld()) {
            double moved = loc.toVector().distance(s.lastLoc.toVector());
            double expected = s.vel.length();
            if (expected > 0.6 && moved < expected * 0.35) {
                s.vel.multiply(0.35);
            }
        }
        s.lastLoc = loc;

        boolean forward = in.isForward();
        double maxSpeed = in.isSprint() ? MAX_SPRINT_SPEED : MAX_SPEED;

        Vector desired = new Vector();
        if (forward) desired.add(look.clone().multiply(maxSpeed));
        if (in.isBackward()) desired.add(flat.clone().multiply(-HOVER_MOVE_SPEED));
        if (in.isLeft()) desired.subtract(right.clone().multiply(HOVER_MOVE_SPEED));
        if (in.isRight()) desired.add(right.clone().multiply(HOVER_MOVE_SPEED));
        if (in.isJump()) desired.add(new Vector(0, VERTICAL_SPEED, 0));
        if (in.isSneak()) desired.add(new Vector(0, -VERTICAL_SPEED, 0));
        if (desired.length() > maxSpeed) desired.normalize().multiply(maxSpeed);

        double rate = desired.lengthSquared() > 0 ? ACCELERATION : BRAKE;
        s.vel.add(desired.subtract(s.vel).multiply(rate));

        p.setVelocity(s.vel.clone());
        p.setFallDistance(0);

        // Liggende vliegpose (te zien voor andere spelers) zodra je vooruit vliegt
        double speed = s.vel.length();
        boolean wantPose = forward || speed > 0.9;
        if (wantPose != s.posed) {
            setFlatPose(p, wantPose);
            s.posed = wantPose;
        }

        // Sonic boom
        long now = System.currentTimeMillis();
        if (speed >= BOOM_SPEED) {
            if (!s.boomActive && now - s.lastBoom > BOOM_COOLDOWN_MS) {
                sonicBoom(p.getLocation(), s.vel, p);
                s.lastBoom = now;
            }
            s.boomActive = true;
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0.05);
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

    // ====== Sonic boom ======
    private void sonicBoom(Location loc, Vector direction, Entity... skip) {
        World w = loc.getWorld();
        Vector d = direction.clone();
        if (d.lengthSquared() < 1.0E-6) d = new Vector(0, 1, 0);
        d.normalize();

        w.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 4f, 1f);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.5f);
        w.spawnParticle(Particle.SONIC_BOOM, loc, 1);

        // Schokgolfringen loodrecht op de vliegrichting
        Vector u = d.clone().crossProduct(new Vector(0, 1, 0));
        if (u.lengthSquared() < 1.0E-4) {
            u = new Vector(1, 0, 0);
        } else {
            u.normalize();
        }
        Vector v = d.clone().crossProduct(u).normalize();
        for (int ring = 1; ring <= 3; ring++) {
            double radius = ring * 1.5;
            Location center = loc.clone().subtract(d.clone().multiply(ring * 0.8));
            for (int i = 0; i < 32; i++) {
                double a = i * Math.PI * 2 / 32;
                Vector off = u.clone().multiply(Math.cos(a) * radius).add(v.clone().multiply(Math.sin(a) * radius));
                w.spawnParticle(Particle.CLOUD, center.clone().add(off), 1, 0, 0, 0, 0);
            }
        }

        // Schokgolf duwt dichtbije wezens weg
        for (LivingEntity le : w.getNearbyLivingEntities(loc, 8)) {
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

    /** Houdt het slachtoffer elke tick bij de heupen: liggend, dwars voor de speler. */
    private void holdTick(Player p, State s) {
        LivingEntity v = s.grab.entity;
        if (!v.isValid() || v.isDead() || v.getWorld() != p.getWorld()) {
            releaseGrab(p, s);
            return;
        }
        Location pl = p.getLocation();
        double yaw = Math.toRadians(pl.getYaw());
        Vector f = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-f.getZ(), 0, f.getX());

        Location target = pl.clone()
                .add(f.clone().multiply(0.7))
                .subtract(right.clone().multiply(0.9))
                .add(0, 0.7, 0);
        if (s.flying) target.add(s.vel);
        target.setYaw(pl.getYaw() + 90f);
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
        if (speed >= THROW_BOOM_SPEED) {
            sonicBoom(v.getLocation(), dir, p, v);
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
        if (s.flying) stopFlying(p, s);
    }
}
