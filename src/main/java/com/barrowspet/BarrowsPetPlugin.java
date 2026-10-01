package com.barrowspet;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.Color;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Barrows Pet",
	description = "Roll for a cosmetic Barrows brother pet on every chest, including your previous KC",
	tags = {"barrows", "pet", "brother", "cosmetic", "follower", "transmog", "collection log"}
)
public class BarrowsPetPlugin extends Plugin
{
	static final String PET_NAME = "Lil' Brother";

	private static final String STATE_KEY = "petState";
	private static final String STATE_BACKUP_KEY = "petStateBackup";
	/** Where the core Chat Commands plugin stores the account's Barrows chest count. */
	private static final String KILLCOUNT_GROUP = "killcount";
	private static final String KILLCOUNT_KEY = "barrows chests";

	private static final Pattern CHEST_COUNT = Pattern.compile("Your Barrows chest count is: ([0-9,]+)\\.");
	private static final Color PET_MESSAGE_COLOR = new Color(0xEF1020);
	private static final String NO_FOLLOWER_MESSAGE = "You do not have a follower";
	/** Game ticks after clicking Call Follower in which the game's "no follower" reply is replaced. */
	private static final int CALL_MESSAGE_WINDOW = 3;
	private static final int SCENE_LOAD_SPAWN_DELAY = 1;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	@Inject
	private BarrowsPetConfig config;

	@Inject
	private PetFollower follower;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private CollectionLogUi collectionLogUi;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private PartyPets partyPets;

	@Inject
	private EventBus eventBus;

	private final PetRoller roller = new PetRoller(new Random());

	/** The logged-in account's state, or null when there is no RuneScape profile. */
	@Getter(AccessLevel.PACKAGE)
	private PetState state;
	/** Check whether the one-time retroactive roll is still needed, on the next game tick. */
	private boolean retroactiveCheckPending;
	private boolean unknownKcNotified;
	/** The game tick Call Follower last brought the Barrows pet back. */
	private int petCalledTick = Integer.MIN_VALUE / 2;
	/** The brother kill flags the game currently has set. */
	private final Set<Brother> knownKillFlags = EnumSet.noneOf(Brother.class);
	/** Whether the kill flags have been recorded since logging in, so changes to them are real kills. */
	private boolean killFlagsKnown;
	/** Game ticks to wait after a scene load before spawning the pet. */
	private int spawnDelayTicks;

	// Dev tools hooks: the dev-only plugin in src/test reads and sets these. Players can't reach them.
	/** Replaces the base drop rate when above 0. */
	@Setter(AccessLevel.PACKAGE)
	private int dropRateOverride;
	@Getter(AccessLevel.PACKAGE)
	private int lastChestKc = -1;
	@Getter(AccessLevel.PACKAGE)
	private int lastChestBrothers;
	@Getter(AccessLevel.PACKAGE)
	private int lastChestRate;

	@Override
	protected void startUp()
	{
		eventBus.register(partyPets);
		partyPets.startUp();
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				loadState();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(partyPets);
		partyPets.shutDown();
		clientThread.invoke(() ->
		{
			follower.despawn();
			collectionLogUi.clearPendingPopups();
		});
		state = null;
		retroactiveCheckPending = false;
		unknownKcNotified = false;
		killFlagsKnown = false;
	}

	@Provides
	BarrowsPetConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BarrowsPetConfig.class);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		loadState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case HOPPING:
			case LOGIN_SCREEN:
				// The game re-sends the kill flags when logging back in; don't mistake those for kills.
				killFlagsKnown = false;
				// fall through
			case LOADING:
				// Scene coordinates are about to change; respawn beside the player once it has loaded.
				follower.despawn();
				spawnDelayTicks = SCENE_LOAD_SPAWN_DELAY;
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!killFlagsKnown)
		{
			recordKillFlags();
		}

		if (state == null)
		{
			return;
		}

		if (retroactiveCheckPending)
		{
			retroactiveCheckPending = false;
			rollRetroactiveIfNeeded();
		}

		collectionLogUi.onGameTick();
		updateFollower();
	}

	/**
	 * A brother's kill flag switching from off to on means he was just killed. The game resets the flags when a
	 * new run starts, but also re-sends every flag at login, so changes are only trusted once the flags seen
	 * after logging in have been recorded.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		Brother brother = Brother.forKilledVarbit(event.getVarbitId());
		if (brother == null)
		{
			return;
		}

		boolean wasKilled = knownKillFlags.contains(brother);
		boolean isKilled = event.getValue() != 0;
		if (isKilled)
		{
			knownKillFlags.add(brother);
		}
		else
		{
			knownKillFlags.remove(brother);
		}

		if (killFlagsKnown && !wasKilled && isKilled && state != null && state.brothersKilledSinceChest().add(brother))
		{
			log.debug("{} killed ({} brothers since the last chest)", brother, state.brothersKilledSinceChest().size());
			saveState();
		}
	}

	/**
	 * Record which kill flags are set now that login has finished, so later changes can be trusted as kills.
	 */
	private void recordKillFlags()
	{
		knownKillFlags.clear();
		for (Brother brother : Brother.values())
		{
			if (client.getVarbitValue(brother.getKilledVarbit()) != 0)
			{
				knownKillFlags.add(brother);
			}
		}
		killFlagsKnown = true;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		follower.onClientTick(client.getLocalPlayer());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Only the server sends GAMEMESSAGE, so players cannot fake a chest count by typing it.
		if (event.getType() != ChatMessageType.GAMEMESSAGE || state == null)
		{
			return;
		}

		if (event.getMessage().contains(NO_FOLLOWER_MESSAGE) && client.getTickCount() - petCalledTick <= CALL_MESSAGE_WINDOW)
		{
			// The game doesn't know about the Barrows pet, so swap its reply to Call Follower for one that fits.
			event.getMessageNode().setValue(PET_NAME + " comes back to you.");
			client.refreshChat();
			return;
		}

		Matcher matcher = CHEST_COUNT.matcher(Text.removeTags(event.getMessage()));
		if (!matcher.matches())
		{
			return;
		}

		int kc = Integer.parseInt(matcher.group(1).replace(",", ""));
		// Never count more than the game itself credits for this run.
		int brothers = Math.min(state.brothersKilledSinceChest().size(), brothersKilled());
		state.brothersKilledSinceChest().clear();
		saveState();
		int chestRate = PetRoller.dropRate(baseDropRate(), brothers);
		log.debug("Barrows chest {} looted with {} brothers killed, rolling at 1/{}", kc, brothers, chestRate);
		lastChestKc = kc;
		lastChestBrothers = brothers;
		lastChestRate = chestRate;

		PetRoller.Result result = roller.rollChest(state, kc, chestRate, baseDropRate());
		handleResult(result, kc);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Only reacts to the game's existing button; nothing extra is sent to the server.
		if (event.getParam1() == InterfaceID.Wornitems.CALL_FOLLOWER)
		{
			callPet();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST && state != null && config.showInCollectionLog())
		{
			collectionLogUi.onCollectionLogDrawn(state.isUnlocked());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BarrowsPetConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "brother":
			case "petSize":
				clientThread.invoke(() -> follower.setAppearance(config.brother(), config.petSize()));
				break;
			case "showPet":
				if (config.showPet())
				{
					clientThread.invoke(this::explainHiddenPet);
				}
				break;
		}
	}

	private void loadState()
	{
		state = null;
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}

		PetState loaded = null;
		String json = configManager.getRSProfileConfiguration(BarrowsPetConfig.GROUP, STATE_KEY);
		if (json != null)
		{
			try
			{
				loaded = gson.fromJson(json, PetState.class);
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Unable to read saved Barrows pet state, starting fresh", e);
				configManager.setRSProfileConfiguration(BarrowsPetConfig.GROUP, STATE_BACKUP_KEY, json);
			}
		}

		state = loaded != null ? loaded : new PetState();
		retroactiveCheckPending = true;
		log.debug("Loaded Barrows pet state {}", state);
	}

	void saveState()
	{
		if (state != null)
		{
			configManager.setRSProfileConfiguration(BarrowsPetConfig.GROUP, STATE_KEY, gson.toJson(state));
		}
	}

	/**
	 * The one-time roll for chests opened before the plugin was installed. Uses the KC saved by the
	 * Chat Commands plugin; if that is unknown, the roll happens on the next chest instead.
	 */
	private void rollRetroactiveIfNeeded()
	{
		if (state.isRetroactiveRollDone())
		{
			return;
		}

		Integer kc = configManager.getRSProfileConfiguration(KILLCOUNT_GROUP, KILLCOUNT_KEY, Integer.class);
		if (kc == null || kc <= 0)
		{
			if (!unknownKcNotified)
			{
				unknownKcNotified = true;
				sendMessage("Your Barrows chest count isn't known yet. The next time you loot the chest, "
					+ "you will also get one roll for every chest you opened before.");
			}
			return;
		}

		handleResult(roller.rollRetroactive(state, kc, baseDropRate()), -1);
	}

	/**
	 * Save the outcome of a roll, then announce it.
	 *
	 * @param chestKc the KC of the chest just looted, or -1 for the install-time roll
	 */
	void handleResult(PetRoller.Result result, int chestKc)
	{
		if (result.isSkipped())
		{
			log.debug("Barrows chest {} was already rolled for", chestKc);
			return;
		}

		// Persist before announcing, so a crash or logout can never lose (or repeat) a roll.
		saveState();

		boolean unlockedByEarlierChest = result.isUnlocked() && result.getUnlockedAtKc() != chestKc;
		if (result.isRetroactive())
		{
			if (result.getEarlierRolls() > 0)
			{
				sendMessage(String.format("Rolled for your %,d previous Barrows chests at 1/%,d each. %s",
					result.getEarlierRolls(), baseDropRate(),
					unlockedByEarlierChest
						? String.format("You would have got %s on chest %,d!", PET_NAME, result.getUnlockedAtKc())
						: "No pet this time, good luck!"));
			}
		}
		else if (result.getEarlierRolls() > 0)
		{
			sendMessage(String.format("Rolled for %,d Barrows chests opened while this plugin was off.%s",
				result.getEarlierRolls(),
				unlockedByEarlierChest ? String.format(" You would have got %s on chest %,d!", PET_NAME, result.getUnlockedAtKc()) : ""));
		}

		if (result.isUnlocked())
		{
			announceUnlock();
		}

		if (result.isDuplicate())
		{
			sendPetMessage("You have a funny feeling like you would have been followed...");
		}
	}

	void announceUnlock()
	{
		sendPetMessage("You have a funny feeling like you're being followed.");
		sendGameStyleMessage("New item added to your collection log: " + ColorUtil.wrapWithColorTag(PET_NAME, PET_MESSAGE_COLOR));
		sendMessage("Choose which brother follows you in the Barrows Pet plugin settings.");

		if (config.collectionLogPopup())
		{
			collectionLogUi.queuePopup();
		}
	}

	private void updateFollower()
	{
		Player player = client.getLocalPlayer();
		NPC realFollower = realFollower();
		boolean show = player != null && state.isUnlocked() && config.showPet() && realFollower == null;
		partyPets.setLocalPet(player != null ? player.getName() : null, show, config.brother(), config.petSize());

		if (show && !follower.isSpawned() && spawnDelayTicks > 0)
		{
			// Give a freshly loaded scene a tick to settle before placing the pet in it.
			spawnDelayTicks--;
			return;
		}

		if (show && !follower.isSpawned())
		{
			log.debug("Spawning Barrows pet");
			follower.spawn(player, config.brother(), config.petSize());
		}
		else if (!show && follower.isSpawned())
		{
			follower.despawn();
			if (realFollower != null)
			{
				log.debug("Hiding Barrows pet for real follower {} ({})", realFollower.getName(), realFollower.getId());
				sendMessage("Your Barrows pet steps aside while you have another follower.");
			}
		}

		if (show)
		{
			follower.onGameTick(player);
		}
	}

	/**
	 * The player's in-game follower (pet, familiar, etc.), or null.
	 * <p>
	 * {@link Client#getFollower()} only looks up whichever NPC is in the follower slot, which can be left
	 * pointing at an unrelated NPC after the follower is picked up, so also check it really is a follower.
	 */
	private NPC realFollower()
	{
		NPC npc = client.getFollower();
		if (npc == null)
		{
			return null;
		}

		NPCComposition composition = npc.getTransformedComposition() != null ? npc.getTransformedComposition() : npc.getComposition();
		return composition != null && composition.isFollower() ? npc : null;
	}

	/**
	 * The player clicked the game's own Call Follower button. If the Barrows pet is out, bring it back
	 * beside them, just as the game does for real followers.
	 */
	private void callPet()
	{
		Player player = client.getLocalPlayer();
		if (state == null || player == null || !state.isUnlocked() || !config.showPet() || realFollower() != null)
		{
			return;
		}

		log.debug("Calling Barrows pet");
		if (follower.isSpawned())
		{
			follower.recall(player);
		}
		else
		{
			follower.spawn(player, config.brother(), config.petSize());
		}
		petCalledTick = client.getTickCount();
	}

	/**
	 * Tell the player why turning the pet on did nothing.
	 */
	private void explainHiddenPet()
	{
		if (state == null)
		{
			return;
		}

		if (!state.isUnlocked())
		{
			sendMessage("You haven't unlocked " + PET_NAME + " yet. Keep looting those chests!");
		}
		else if (realFollower() != null)
		{
			sendMessage("You already have a follower. Your Barrows pet will appear once it's gone.");
		}
	}

	int baseDropRate()
	{
		return dropRateOverride > 0 ? dropRateOverride : PetRoller.BASE_DROP_RATE;
	}

	/**
	 * How many of the game's brother kill flags are set. The game does not clear these when the chest is looted,
	 * so this is only an upper bound on the kills for the current run.
	 */
	int brothersKilled()
	{
		int killed = 0;
		for (Brother brother : Brother.values())
		{
			if (client.getVarbitValue(brother.getKilledVarbit()) != 0)
			{
				killed++;
			}
		}
		return killed;
	}

	/**
	 * Replace this account's state, e.g. to wipe it. Used by the dev tools in src/test.
	 */
	void setState(PetState state)
	{
		this.state = state;
	}

	/**
	 * Run the one-time roll for previous KC again on the next tick, if the state says it hasn't happened.
	 * Used by the dev tools in src/test.
	 */
	void requestRetroactiveRoll()
	{
		unknownKcNotified = false;
		retroactiveCheckPending = true;
	}

	/**
	 * A plugin message. Sent as CONSOLE rather than GAMEMESSAGE so that other plugins (screenshots, loot
	 * loggers, Discord notifiers) never mistake it for a real game drop.
	 */
	void sendMessage(String message)
	{
		sendGameStyleMessage(new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Barrows Pet] ")
			.append(ChatColorType.NORMAL)
			.append(message)
			.build());
	}

	private void sendPetMessage(String message)
	{
		sendGameStyleMessage(ColorUtil.wrapWithColorTag(message, PET_MESSAGE_COLOR));
	}

	private void sendGameStyleMessage(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
