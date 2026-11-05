# ChopChop API

ChopChop decides wounds on the server and draws them on every client with the mod. The API lets another mod or
plugin — for example a Mordhau-style combat mod with directional swings and parries — decide **itself** where a body
is cut, and leave the drawing, syncing, falling limbs, blood and consequences (a lost head kills, lost legs crawl,
a lost hand drops its item) to ChopChop.

There are two front ends with the same calls:

| Server                         | Class                                         | Types                     |
|--------------------------------|-----------------------------------------------|---------------------------|
| Fabric (dedicated or the singleplayer host) | `ru.deelter.chopchop.api.ChopChopApi`        | `LivingEntity`, `Vec3`    |
| Paper plugin                   | `ru.deelter.chopchop.paper.api.ChopChopApi`   | `LivingEntity`, `Vector`, `Location` |

Both run the same shared code (`src/shared`), so a call gives the same result on either server.

**Server side only, server thread only**, but for dead bodies on the client (below).

## Setup

### Fabric

Depend on the ChopChop jar (compile only is enough if ChopChop is optional) and declare it in `fabric.mod.json`:

```json
"depends":  { "chopchop": "*" }
```

or `"suggests"` and check `FabricLoader.getInstance().isModLoaded("chopchop")` before touching the API classes.

### Paper

Put the ChopChop plugin jar on your compile classpath and add to `plugin.yml`:

```yaml
depend: [ChopChop]      # or softdepend, then check Bukkit.getPluginManager().isPluginEnabled("ChopChop")
```

## Concepts

**Profiles and parts.** Every supported entity type has a body profile: a list of named parts, mirroring the
vanilla model parts. The built-in profiles (`player`, `zombie`, `humanoid`, `skeleton`, `pig`) use
`head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` (the pig has its own leg names). Ask
`ChopChopApi.parts(entity)` for the names of a given entity; an entity without a profile has no body
(`hasBody` is false) and every call on it does nothing.

**Along.** A place on a part is given as `along`: `0.0` at the root of the part (shoulder, hip, neck), `1.0` at
its end (hand, foot, top of the head). Severing at `along = 0.5` takes the arm from the elbow down. A head always
comes off whole.

**Voxels.** Each part is a grid of voxels, about one per pixel of the model texture (an arm is 4×12×4). Wound
sizes are in voxels.

**Rest pose.** The server does not know the animation a client draws. Rays are traced on the rest pose of the
profile, turned like the entity (body yaw, scale, swimming, crawling). Aim at where the part *stands*, not where an
arm swings in the animation. For a combat mod this is usually what you want: you already know which part your
swing was aimed at and can call `sever(target, "left_arm", 0.6, ...)` directly without a ray.

## Reading the state

```java
ChopChopApi.hasBody(zombie);                    // has a profile
ChopChopApi.parts(zombie);                      // ["head", "body", "right_arm", ...]
ChopChopApi.remaining(zombie, "right_arm");     // 1.0 whole ... 0.0 cut off at the shoulder
ChopChopApi.isSevered(zombie, "right_arm");
ChopChopApi.isHeadless(zombie);
ChopChopApi.isLegless(zombie);
ChopChopApi.isHandless(zombie, "right");        // the arm with that hand is cut anywhere
```

Use these for gameplay: a fighter without the right hand cannot hold a sword, a legless one cannot dodge, and so on.

### All the wounds at once, without depending on ChopChop

Two methods return plain Java values only (maps, strings, numbers, booleans), so another mod or plugin can call
them through reflection, with no compile or runtime dependency and no ChopChop classes in its code:

```java
Map<String, Object> wounds = ChopChopApi.woundsOf(uuid);           // null: whole, or unknown
Map<UUID, Map<String, Object>> all = ChopChopApi.playerWounds();   // every online player that has wounds
```

| key | type | |
|---|---|---|
| `profile` | String | the body profile, `"player"` |
| `revision` | Integer | counted up on every change |
| `headless` | Boolean | the head came off |
| `legless` | Boolean | every leg is cut off |
| `legsGone` | Integer | legs cut off, at any height |
| `parts` | Map<String, Map<String, Object>> | the damaged parts by name (`"right_arm"`), each below |
| `parts.*.carved` | Integer | voxels carved out by wounds |
| `parts.*.voxels` | Integer | voxels of the whole part |
| `parts.*.wounded` | Double | carved / voxels, 0 to 1 |
| `parts.*.severed` | Boolean | cut off at some height |
| `parts.*.remaining` | Double | share of the part still on, 0 to 1 |
| `parts.*.hand` | String | `"right"` or `"left"`, only for an arm with a hand |
| `parts.*.leg` | Boolean | the body stands on it |

The maps are copies, server thread only. Through reflection:

```java
// Fabric: ru.deelter.chopchop.api.ChopChopApi, Paper: ru.deelter.chopchop.paper.api.ChopChopApi
Class<?> api = Class.forName("ru.deelter.chopchop.api.ChopChopApi");
@SuppressWarnings("unchecked")
Map<UUID, Map<String, Object>> all = (Map<UUID, Map<String, Object>>) api.getMethod("playerWounds").invoke(null);
```
On Paper load the class with the ChopChop plugin's class loader
(`Bukkit.getPluginManager().getPlugin("ChopChop").getClass().getClassLoader()`).

## Finding where a blow lands

```java
// Fabric
BodySpot spot = ChopChopApi.locate(target, attacker.getEyePosition(), attacker.getViewVector(1f), 4.5, true);
// Paper
BodySpot spot = ChopChopApi.locate(target, player.getEyeLocation(), player.getEyeLocation().getDirection(), 4.5, true);

if (spot != null) {
    spot.part();   // "left_arm"
    spot.along();  // 0.0 .. 1.0
    spot.point();  // world point (Vec3 / Location), for your own particles and sounds
}
```

`nearest = true`: when the ray misses the model (it only touched the collision box) the part nearest to the ray is
taken instead of returning null. The ray stops at the first voxel still there: a cut-off part is never found, and a
second blow at an open wound goes deeper.

## Severing

```java
// cut the left arm off at the elbow; the attacker gets the kill and the kill cam for a head
ChopChopApi.sever(target, "left_arm", 0.5, attacker, false);

// cut where a located spot is
ChopChopApi.sever(target, spot, attacker, false);

// a head comes off whole, whatever "along" is
ChopChopApi.sever(target, "head", 0, attacker, false);

// fling = true: the piece is thrown off hard, as if torn by a blast
ChopChopApi.sever(target, "right_leg", 0.2, null, true);
```

Returns whether anything more came off (cutting below an existing cut does nothing). After a sever the usual
consequences run:

| What came off           | What follows (server rules apply)                                              |
|-------------------------|--------------------------------------------------------------------------------|
| head                    | the body dies after `headlessSeconds` (mob) / `headlessPlayerSeconds` (player), the kill goes to `attacker` |
| all legs                | `legless`: crawl / lie (mobs) / nothing                                        |
| an arm with a hand (mob)| the item in that hand is taken (`disarmMobs`); with no arms the mob stops attacking |

The rules of the server (`severing.chance`, durability, `heads: false`...) do **not** gate API calls: if you call
`sever`, the part comes off. Only `legless: protect` is not checked either — check `isLegless`/`remaining` yourself if
you need that.

## Cutting in half (fatality)

```java
// the lower half (trunk below the middle + both legs) falls off, the entity dies, the kill goes to the attacker
ChopChopApi.halve(target, attacker);
// cut higher (0 neck .. 1 hips), lower half thrown hard
ChopChopApi.halve(target, 0.4, attacker, true);
```

Same on Paper (`ru.deelter.chopchop.paper.api.ChopChopApi`). Returns false for an entity whose profile has no
part named `body`. The finishing blow makes no extra wounds of its own. As an optional dependency, call it from a
class that is loaded only when ChopChop is present (`FabricLoader.isModLoaded("chopchop")` /
`isPluginEnabled("ChopChop")`).

## Wounds without severing

```java
// a round hole, 1.5 voxels in radius, 1 voxel deep: a stab
ChopChopApi.wound(target, spot, WoundShape.hole(1.5, 1), attacker);

// a cut along the swing: 8 voxels long, 1.6 wide, 1 deep
Vec3 swing = new Vec3(0, -1, 0);                 // an overhead chop: the cut runs up and down
ChopChopApi.wound(target, spot, WoundShape.cut(swing, 8, 1.6, 1), attacker);

// Paper: the same with org.bukkit.util.Vector
ChopChopApi.wound(target, spot, WoundShape.cut(new Vector(1, 0, 0), 6, 1.4, 1), attacker);
```

`swing` is the direction the blade moved, in world space (for a horizontal slash from the attacker's right to left:
the attacker's left vector). It is projected onto the surface of the part, so the scar follows the real swing:
level for a horizontal slash, vertical for an overhead, diagonal for a diagonal one.

Wounds dig in only from the side the spot is on and **never come out on the far side** of a part: a chop at the
chest never opens the back. Depth `0` is skin only; `1` – `2` is a deep gash (parts are 4 voxels thick).

## Taking over the automatic wounds

By default ChopChop turns every hit the game deals into wounds by its own rules. A combat mod that decides cuts
itself should switch that off for the hits it handles, or wounds happen twice:

```java
// Fabric: (target, DamageSource) -> true lets ChopChop handle it
ChopChopApi.setAutomaticWounds((target, source) ->
        !(source.getEntity() instanceof Player));   // players' blows are ours; arrows, mobs, blasts stay automatic

// Paper: (target, EntityDamageEvent)
ChopChopApi.setAutomaticWounds((target, event) ->
        !(event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player));

// back to the default
ChopChopApi.setAutomaticWounds(null);
```

The filter is asked before ChopChop looks at a hit. It is not asked for API calls.

## Healing

```java
ChopChopApi.heal(entity);   // whole again, on every client; crawling and the headless countdown end
```

## Example: a parried swing system

```java
void onSwingLanded(LivingEntity attacker, LivingEntity target, SwingDirection dir, float damage, boolean parried) {
    if (parried || !ChopChopApi.hasBody(target)) return;
    BodySpot spot = ChopChopApi.locate(target, attacker.getEyePosition(), aimOf(attacker), 4, true);
    if (spot == null) return;

    Vec3 swing = dir.worldVector(attacker);          // your own: overhead, left, right, stab
    if (dir == SwingDirection.STAB) {
        ChopChopApi.wound(target, spot, WoundShape.hole(1.2 + damage * 0.08, 2), attacker);
    } else if (damage > 9 && !spot.part().equals("body")) {
        ChopChopApi.sever(target, spot, attacker, false);   // a heavy blow takes the limb
    } else {
        ChopChopApi.wound(target, spot, WoundShape.cut(swing, 4 + damage * 0.5, 1.4, 1), attacker);
    }
}
```

## Dead bodies (server side)

Dead mobs and players stay on the clients as bodies for `corpseSeconds`. The server keeps each one's wounds under
the id the body goes by (the entity id of a mob, an id of its own for a player) and can cut it further:

```java
// Fabric: ServerLevel and AABB; Paper: World and BoundingBox, Location instead of Vec3
Map<Integer, Vec3> bodies = ChopChopApi.corpses(level, area);   // id -> where it fell
ChopChopApi.severCorpse(id, "left_arm", 0.5, false);             // the arm off at the elbow, falling as a piece
ChopChopApi.woundCorpse(id, "body", 0.4, WoundShape.hole(1.5, 1));
```

Bodies fall, slide and are thrown on each client, so where one lies may be a block or two from where it fell; the
server does not know how it lies, so a wound goes on the front of the part and a cut runs along it. Cutting a body
is only for the eye: no damage, no drops, no kill cam. Players cut bodies by clicking at them; a client mod with
blades of its own uses `ChopChopClientApi` (below).

## Notes

- Mobs' wounds live as long as the mob is loaded; player wounds until respawn (or longer with `persistPlayers`).
- Everything is sent to the players with the mod who track the entity, and to the entity itself if it is a player.
  Players without the mod see nothing and play as usual.
- Dead mobs and players stay as bodies for `corpseSeconds` on clients with the mod (players as a copy with their
  skin, armour and wounds, `playerCorpses`); they keep their wounds, and severing a body
  in that time works too (the server entity is gone after the vanilla 20 ticks of death, so do it before that).
- The API classes are the only stable surface. Anything else in ChopChop (`ChopServer`, `WoundBook`, the
  protocol) may change between versions.

## Dead bodies on the client

Dead bodies live on the clients for `corpseSeconds`; a click at one cuts it (the server decides the cut). A mod with
blades of its own (a VR combat mod) reaches them with `ru.deelter.chopchop.client.api.ChopChopClientApi`, client
thread only:

```java
for (ChopChopClientApi.Corpse body : ChopChopClientApi.corpses(area)) {
    body.id();   // the id the server knows it by
    body.box();  // about where it lies, for a blade to meet it
}
ChopChopClientApi.cut(id, bladeFrom, bladeDir, bladeLength);   // cut where the blade meets the body
ChopChopClientApi.corpseUnderCrosshair();                       // a click now is a cut: leave the click to ChopChop
```
