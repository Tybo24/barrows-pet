package com.barrowspet;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.util.Text;

/**
 * Shares this player's Barrows pet with their RuneLite party, and draws the pets of party members who also
 * have the plugin. Uses RuneLite's own party service, so no third-party server is involved.
 * <p>
 * Party messages arrive on a network thread, so all game state is only touched on the client thread.
 */
@Slf4j
@Singleton
class PartyPets
{
	private static final int MIN_SIZE = 30;
	private static final int MAX_SIZE = 100;
	/** Game ticks to wait after a scene load before spawning pets, matching the local pet. */
	private static final int SCENE_LOAD_SPAWN_DELAY = 1;

	private static class RemotePet
	{
		final PetFollower follower;
		String playerName;
		boolean visible;
		Brother brother;
		int size;
		/** The party member's player while they are in the scene, otherwise null. */
		Player player;

		RemotePet(PetFollower follower)
		{
			this.follower = follower;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final PartyService partyService;
	private final WSClient wsClient;
	private final BarrowsPetConfig config;

	/** Party members' pets, by party member id. */
	private final Map<Long, RemotePet> pets = new HashMap<>();
	private BarrowsPetUpdate lastSent;
	/** Send our pet again even if it hasn't changed, e.g. because someone joined the party. */
	private volatile boolean resendPending;
	private int spawnDelayTicks;
	/** Dev tools hook: treat our own pet, echoed back by the party server, as another member's. */
	private boolean echoOwnPet;

	@Inject
	PartyPets(Client client, ClientThread clientThread, PartyService partyService, WSClient wsClient, BarrowsPetConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.partyService = partyService;
		this.wsClient = wsClient;
		this.config = config;
	}

	void startUp()
	{
		wsClient.registerMessage(BarrowsPetUpdate.class);
		resendPending = true;
	}

	void shutDown()
	{
		wsClient.unregisterMessage(BarrowsPetUpdate.class);
		clientThread.invoke(this::clear);
		lastSent = null;
	}

	/**
	 * Tell the party what this player's pet looks like. Called every game tick; only sends when it changes.
	 */
	void setLocalPet(String playerName, boolean visible, Brother brother, int size)
	{
		if (playerName == null || !partyService.isInParty())
		{
			return;
		}

		BarrowsPetUpdate update = new BarrowsPetUpdate(playerName, visible && config.sharePet(), brother, size);
		if (resendPending || !update.equals(lastSent))
		{
			resendPending = false;
			lastSent = update;
			partyService.send(update);
			log.debug("Sent Barrows pet to party: {}", update);
		}
	}

	/**
	 * Toggle drawing our own pet as if it came from another party member. Used by the dev tools in src/test.
	 *
	 * @return whether echo is now on
	 */
	boolean toggleEcho()
	{
		echoOwnPet = !echoOwnPet;
		if (!echoOwnPet)
		{
			PartyMember local = partyService.getLocalMember();
			if (local != null)
			{
				remove(local.getMemberId());
			}
		}
		resendPending = true;
		return echoOwnPet;
	}

	@Subscribe
	public void onBarrowsPetUpdate(BarrowsPetUpdate update)
	{
		clientThread.invokeLater(() -> receive(update));
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		// Someone joined the party; make sure they get our pet.
		resendPending = true;
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invokeLater(() -> remove(event.getMemberId()));
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invokeLater(this::clear);
		lastSent = null;
		resendPending = true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
			case HOPPING:
			case LOGIN_SCREEN:
				// Scene coordinates are about to change; respawn beside each player once it has loaded.
				for (RemotePet pet : pets.values())
				{
					pet.follower.despawn();
					pet.player = null;
				}
				spawnDelayTicks = SCENE_LOAD_SPAWN_DELAY;
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (pets.isEmpty())
		{
			return;
		}

		if (spawnDelayTicks > 0)
		{
			spawnDelayTicks--;
			return;
		}

		Map<String, Player> players = new HashMap<>();
		if (config.showPartyPets())
		{
			for (Player player : client.getTopLevelWorldView().players())
			{
				if (player != null && player.getName() != null)
				{
					players.put(Text.toJagexName(player.getName()), player);
				}
			}
		}

		for (RemotePet pet : pets.values())
		{
			Player player = pet.visible && pet.brother != null && pet.playerName != null
				? players.get(Text.toJagexName(pet.playerName))
				: null;
			pet.player = player;

			if (player == null)
			{
				pet.follower.despawn();
			}
			else if (!pet.follower.isSpawned())
			{
				pet.follower.spawn(player, pet.brother, pet.size);
			}
			else
			{
				pet.follower.onGameTick(player);
			}
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		for (RemotePet pet : pets.values())
		{
			if (pet.player != null)
			{
				pet.follower.onClientTick(pet.player);
			}
		}
	}

	private void receive(BarrowsPetUpdate update)
	{
		PartyMember local = partyService.getLocalMember();
		if (!echoOwnPet && local != null && local.getMemberId() == update.getMemberId())
		{
			return;
		}

		log.debug("Received Barrows pet from party member {}: {}", update.getMemberId(), update);
		RemotePet pet = pets.computeIfAbsent(update.getMemberId(), id -> new RemotePet(new PetFollower(client)));
		pet.playerName = update.getPlayerName();
		pet.visible = update.isVisible();
		pet.brother = update.getBrother();
		// Never trust another client to keep the size sensible.
		pet.size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, update.getSize()));

		if (pet.visible && pet.brother != null)
		{
			pet.follower.setAppearance(pet.brother, pet.size);
		}
		else
		{
			pet.follower.despawn();
			pet.player = null;
		}
	}

	private void remove(long memberId)
	{
		RemotePet pet = pets.remove(memberId);
		if (pet != null)
		{
			pet.follower.despawn();
		}
	}

	private void clear()
	{
		for (RemotePet pet : pets.values())
		{
			pet.follower.despawn();
		}
		pets.clear();
	}
}
