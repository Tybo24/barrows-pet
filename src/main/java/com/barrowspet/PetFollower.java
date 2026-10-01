package com.barrowspet;

import java.util.ArrayDeque;
import java.util.Deque;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Renders a Barrows brother as a client-side {@link RuneLiteObject} that follows the player like an in-game
 * follower. Nothing is sent to the server; only this client can see it.
 * <p>
 * Movement mimics the game: each game tick the pet takes a step towards the player using the simple NPC
 * pathing (diagonal first, then straight, respecting walls) and stops once it is next to them. The steps are
 * then walked at actor walking speed, speeding up only when they back up, and while standing still the pet
 * turns to face the player.
 * <p>
 * Must only be used from the client thread.
 */
@Slf4j
class PetFollower
{
	private static final int TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	/** Local units moved per client tick while walking, matching in-game actors (a tile takes 32 client ticks). */
	private static final int WALK_SPEED = 4;
	/** Never move faster than this multiple of walking speed when catching up. */
	private static final int MAX_SPEED_MULTIPLIER = 3;
	/** Orientation units turned per client tick (2048 is a full turn), the default for NPCs. */
	private static final int TURN_SPEED = 32;
	/** Client ticks the pet must stand still before switching back to its idle animation, to avoid flicker. */
	private static final int IDLE_DELAY = 6;
	/** Tiles away from the player before the pet teleports back beside them. */
	private static final int MAX_DISTANCE = 10;
	/** Client ticks (2 seconds) to keep matching a newly placed pet's height to the ground. */
	private static final int HEIGHT_SETTLE_TICKS = 100;
	/** All Barrows brothers use ambient=50 and contrast=50 in their NPC definitions. */
	private static final int NPC_AMBIENT = 50;
	private static final int NPC_CONTRAST = 50;
	private static final int[][] ADJACENT = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

	private final Client client;

	private RuneLiteObject object;
	private AnimationController animation;
	private Brother brother;
	private int sizePercent;

	/** The tile the pet is logically on, i.e. where it will be once it has walked its queued steps. */
	private LocalPoint petTile;
	/** Tile centres the pet still has to walk through. */
	private final Deque<LocalPoint> steps = new ArrayDeque<>();
	private LocalPoint lastPlayerTile;
	private int targetOrientation;
	private boolean walking;
	private int stillTicks;
	private int heightSettleTicks;

	@Inject
	PetFollower(Client client)
	{
		this.client = client;
	}

	boolean isSpawned()
	{
		return object != null;
	}

	void spawn(Player player, Brother brother, int sizePercent)
	{
		despawn();

		Model model = createModel(brother, sizePercent);
		LocalPoint playerTile = serverTile(player);
		if (model == null || playerTile == null)
		{
			return;
		}

		this.brother = brother;
		this.sizePercent = sizePercent;

		animation = new AnimationController(client, brother.getReadyAnimation());
		object = client.createRuneLiteObject();
		object.setModel(model);
		object.setAnimationController(animation);
		object.setOrientation(player.getOrientation());
		targetOrientation = player.getOrientation();
		walking = false;

		WorldView wv = player.getWorldView();
		teleport(tileBeside(wv, playerTile), wv.getPlane());
		lastPlayerTile = playerTile;
		object.setActive(true);
		log.debug("Spawned {} at scene {},{} level {} height {} (player at {},{}, world view {})", brother,
			petTile.getSceneX(), petTile.getSceneY(), object.getLevel(), object.getZ(),
			playerTile.getSceneX(), playerTile.getSceneY(), wv.getId());
	}

	/**
	 * Bring the pet back beside the player, like the Call Follower button does for real followers.
	 */
	void recall(Player player)
	{
		LocalPoint playerTile = serverTile(player);
		if (object == null || playerTile == null)
		{
			return;
		}

		WorldView wv = player.getWorldView();
		teleport(tileBeside(wv, playerTile), wv.getPlane());
		lastPlayerTile = playerTile;
	}

	void despawn()
	{
		if (object != null)
		{
			object.setActive(false);
		}
		object = null;
		animation = null;
		petTile = null;
		lastPlayerTile = null;
		steps.clear();
	}

	/**
	 * Swap the transmog or size of a spawned pet in place.
	 */
	void setAppearance(Brother brother, int sizePercent)
	{
		if (object == null || (brother == this.brother && sizePercent == this.sizePercent))
		{
			return;
		}

		Model model = createModel(brother, sizePercent);
		if (model == null)
		{
			return;
		}

		this.brother = brother;
		this.sizePercent = sizePercent;
		object.setModel(model);
		animation.setAnimation(client.loadAnimation(walking ? brother.getWalkAnimation() : brother.getReadyAnimation()));
	}

	/**
	 * Decide this tick's steps towards the player, like the server does for followers.
	 */
	void onGameTick(Player player)
	{
		if (object == null)
		{
			return;
		}

		WorldView wv = player.getWorldView();
		LocalPoint playerTile = serverTile(player);
		if (playerTile == null)
		{
			return;
		}

		// Teleported, changed floor, or boarded/left a boat: put the pet back beside the player.
		if (tileDistance(petTile, playerTile) > MAX_DISTANCE || object.getLevel() != wv.getPlane()
			|| petTile.getWorldView() != wv.getId())
		{
			teleport(tileBeside(wv, playerTile), wv.getPlane());
			lastPlayerTile = playerTile;
			return;
		}

		// The game moves NPCs before players each tick, so a follower always chases where the player was on
		// the previous tick. This is what makes it trail behind rather than head for where the player is going.
		LocalPoint target = lastPlayerTile != null ? lastPlayerTile : playerTile;
		int playerMoved = tileDistance(target, playerTile);
		lastPlayerTile = playerTile;

		if (petTile.equals(playerTile))
		{
			// The player walked onto the pet; step off like a real follower would.
			queueStep(tileBeside(wv, playerTile));
			return;
		}

		// Walk one tile a tick, or two (running) if the player is running or the pet has fallen behind.
		int stepsThisTick = playerMoved >= 2 || tileDistance(petTile, target) > 2 ? 2 : 1;
		for (int i = 0; i < stepsThisTick && tileDistance(petTile, target) > 1; i++)
		{
			LocalPoint next = stepTowards(wv, petTile, target);
			if (next == null || next.equals(playerTile))
			{
				// Stuck behind something (it will teleport back if the player gets too far away),
				// or the step would land on the player.
				break;
			}
			queueStep(next);
		}
	}

	/**
	 * Smoothly walk the queued steps and turn. Called every client tick (20ms).
	 */
	void onClientTick(Player player)
	{
		if (object == null)
		{
			return;
		}

		LocalPoint current = object.getLocation();
		if (!steps.isEmpty())
		{
			LocalPoint next = steps.peek();
			int dx = next.getX() - current.getX();
			int dy = next.getY() - current.getY();
			// Like the client does for actors, move faster when steps back up so the pet catches up.
			int speed = WALK_SPEED * Math.min(steps.size(), MAX_SPEED_MULTIPLIER);
			double length = Math.hypot(dx, dy);

			int x = next.getX();
			int y = next.getY();
			if (length > speed)
			{
				x = current.getX() + (int) Math.round(dx * speed / length);
				y = current.getY() + (int) Math.round(dy * speed / length);
			}
			else
			{
				steps.poll();
			}

			if (dx != 0 || dy != 0)
			{
				targetOrientation = orientationFor(dx, dy);
			}
			object.setLocation(new LocalPoint(x, y, current.getWorldView()), object.getLevel());
			stillTicks = 0;
			setWalking(true);
		}
		else
		{
			// Ground heights may not be final when the pet is placed in a freshly loaded scene (e.g. entering a
			// house), which can leave it buried. Keep re-placing it for a moment so its height follows the ground.
			// Moving re-places it anyway, so this is only needed while it stands still.
			if (heightSettleTicks > 0)
			{
				heightSettleTicks--;
				object.setLocation(current, object.getLevel());
			}

			if (walking && ++stillTicks > IDLE_DELAY)
			{
				setWalking(false);
			}

			// Standing still: watch the player.
			LocalPoint playerLocation = player == null ? null : player.getLocalLocation();
			if (playerLocation != null && playerLocation.getWorldView() == current.getWorldView())
			{
				int dx = playerLocation.getX() - current.getX();
				int dy = playerLocation.getY() - current.getY();
				if (dx != 0 || dy != 0)
				{
					targetOrientation = orientationFor(dx, dy);
				}
			}
		}

		int orientation = object.getOrientation();
		int diff = ((targetOrientation - orientation + 3072) % 2048) - 1024;
		if (diff != 0)
		{
			int turn = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, diff));
			object.setOrientation((orientation + turn) & 2047);
		}
	}

	private void queueStep(LocalPoint tile)
	{
		petTile = tile;
		steps.add(tile);
	}

	private void teleport(LocalPoint tile, int plane)
	{
		steps.clear();
		petTile = tile;
		object.setLocation(tile, plane);
		heightSettleTicks = HEIGHT_SETTLE_TICKS;
		setWalking(false);
	}

	private void setWalking(boolean walking)
	{
		if (this.walking == walking)
		{
			return;
		}

		this.walking = walking;
		animation.setAnimation(client.loadAnimation(walking ? brother.getWalkAnimation() : brother.getReadyAnimation()));
	}

	/**
	 * The tile the server has the player on, rather than their smoothly interpolated position.
	 */
	private static LocalPoint serverTile(Player player)
	{
		WorldPoint worldPoint = player.getWorldLocation();
		return worldPoint == null ? null : LocalPoint.fromWorld(player.getWorldView(), worldPoint);
	}

	private static int tileDistance(LocalPoint a, LocalPoint b)
	{
		return Math.max(Math.abs(a.getSceneX() - b.getSceneX()), Math.abs(a.getSceneY() - b.getSceneY()));
	}

	/**
	 * One step of the game's simple NPC pathing: try diagonally towards the target, then along x, then along y.
	 * Returns null if every option is blocked.
	 */
	private static LocalPoint stepTowards(WorldView wv, LocalPoint from, LocalPoint to)
	{
		int dx = Integer.signum(to.getSceneX() - from.getSceneX());
		int dy = Integer.signum(to.getSceneY() - from.getSceneY());
		int[][] flags = collisionFlags(wv);

		if (dx != 0 && dy != 0 && canStep(wv, flags, from, dx, dy))
		{
			return offset(wv, from, dx, dy);
		}
		if (dx != 0 && canStep(wv, flags, from, dx, 0))
		{
			return offset(wv, from, dx, 0);
		}
		if (dy != 0 && canStep(wv, flags, from, 0, dy))
		{
			return offset(wv, from, 0, dy);
		}
		return null;
	}

	/**
	 * Whether a single-tile step from {@code from} by (dx, dy) is allowed by the scene's collision map,
	 * including walls on the destination tile and, for diagonals, both adjacent straight steps.
	 */
	private static boolean canStep(WorldView wv, int[][] flags, LocalPoint from, int dx, int dy)
	{
		int x = from.getSceneX() + dx;
		int y = from.getSceneY() + dy;
		if (x < 0 || y < 0 || x >= wv.getSizeX() || y >= wv.getSizeY())
		{
			return false;
		}
		if (flags == null)
		{
			return true;
		}

		if (dx != 0 && dy != 0 && (!canStep(wv, flags, from, dx, 0) || !canStep(wv, flags, from, 0, dy)))
		{
			return false;
		}

		return (flags[x][y] & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | wallsBlocking(dx, dy))) == 0;
	}

	/**
	 * The wall flags on a destination tile that block entering it while moving by (dx, dy).
	 */
	private static int wallsBlocking(int dx, int dy)
	{
		int walls = 0;
		if (dx > 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else if (dx < 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		if (dy > 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else if (dy < 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}

		if (dx > 0 && dy > 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
		}
		else if (dx < 0 && dy > 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
		}
		else if (dx > 0 && dy < 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
		}
		else if (dx < 0 && dy < 0)
		{
			walls |= CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
		}
		return walls;
	}

	private static int[][] collisionFlags(WorldView wv)
	{
		CollisionData[] collisionMaps = wv.getCollisionMaps();
		return collisionMaps == null ? null : collisionMaps[wv.getPlane()].getFlags();
	}

	private static LocalPoint offset(WorldView wv, LocalPoint tile, int dx, int dy)
	{
		return new LocalPoint(tile.getX() + dx * TILE_SIZE, tile.getY() + dy * TILE_SIZE, wv);
	}

	/**
	 * A walkable tile next to {@code tile}, or {@code tile} itself if every neighbour is blocked.
	 */
	private static LocalPoint tileBeside(WorldView wv, LocalPoint tile)
	{
		int[][] flags = collisionFlags(wv);
		for (int[] direction : ADJACENT)
		{
			if (canStep(wv, flags, tile, direction[0], direction[1]))
			{
				return offset(wv, tile, direction[0], direction[1]);
			}
		}
		return tile;
	}

	/**
	 * Convert a direction into a Jagex orientation (0 = south, 512 = west, 1024 = north, 1536 = east).
	 */
	private static int orientationFor(int dx, int dy)
	{
		return ((int) Math.round(Math.atan2(dx, dy) * 1024 / Math.PI) + 1024) & 2047;
	}

	/**
	 * Build the brother's model the way the client builds an NPC's: merge its parts, apply its recolours,
	 * scale it and light it.
	 */
	private Model createModel(Brother brother, int sizePercent)
	{
		NPCComposition npc = client.getNpcDefinition(brother.getNpcId());
		int[] modelIds = npc.getModels();
		if (modelIds == null || modelIds.length == 0)
		{
			log.debug("No models for {}", brother);
			return null;
		}

		ModelData[] parts = new ModelData[modelIds.length];
		for (int i = 0; i < modelIds.length; i++)
		{
			parts[i] = client.loadModelData(modelIds[i]);
			if (parts[i] == null)
			{
				log.debug("Unable to load model {} for {}", modelIds[i], brother);
				return null;
			}
		}

		ModelData model = client.mergeModels(parts).cloneColors().cloneVertices();

		short[] recolorFrom = npc.getColorToReplace();
		short[] recolorTo = npc.getColorToReplaceWith();
		if (recolorFrom != null && recolorTo != null)
		{
			for (int i = 0; i < Math.min(recolorFrom.length, recolorTo.length); i++)
			{
				model.recolor(recolorFrom[i], recolorTo[i]);
			}
		}

		int widthScale = npc.getWidthScale() * sizePercent / 100;
		int heightScale = npc.getHeightScale() * sizePercent / 100;
		model.scale(widthScale, heightScale, widthScale);

		return model.light(ModelData.DEFAULT_AMBIENT + NPC_AMBIENT, 850 + NPC_CONTRAST * 5, -30, -50, -30);
	}
}
