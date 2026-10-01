package com.barrowspet;

import java.util.Random;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.Text;

/**
 * Developer tools for testing Barrows Pet. Lives in src/test, so it is only loaded by the dev launcher
 * ({@link BarrowsPetPluginTest}) and never ships to the Plugin Hub. Type ::bpet in the dev client for usage.
 */
@PluginDescriptor(
	name = "Barrows Pet Dev Tools",
	description = "::bpet test commands for Barrows Pet",
	developerPlugin = true
)
public class BarrowsPetDevToolsPlugin extends Plugin
{
	private static final String COMMAND = "bpet";
	private static final String CHEST_COUNT_PREFIX = "Your Barrows chest count is:";

	@Inject
	private PluginManager pluginManager;

	private final PetRoller roller = new PetRoller(new Random());

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		BarrowsPetPlugin plugin = barrowsPet();
		if (!COMMAND.equalsIgnoreCase(event.getCommand()) || plugin == null)
		{
			return;
		}

		PetState state = plugin.getState();
		if (state == null)
		{
			plugin.sendMessage("Log in first.");
			return;
		}

		String[] args = event.getArguments();
		String action = args.length > 0 ? args[0].toLowerCase() : "";
		switch (action)
		{
			case "status":
				plugin.sendMessage(String.format("Unlocked: %s (kc %d, %s) | Retroactive done: %s (%,d rolls) | Last rolled kc: %,d | Total rolls: %,d | Base rate: 1/%,d | Brothers killed since last chest: %d (game flags: %d)",
					state.isUnlocked(), state.getUnlockedAtKc(), state.getUnlockSource(), state.isRetroactiveRollDone(),
					state.getRetroactiveRolls(), state.getLastRolledKc(), state.getTotalRolls(), plugin.baseDropRate(),
					state.brothersKilledSinceChest().size(), plugin.brothersKilled()));
				break;
			case "roll":
				simulateRolls(plugin, state, args.length > 1 ? parseNumber(args[1]) : 1, args.length > 2 ? parseNumber(args[2]) : PetRoller.BROTHERS);
				break;
			case "drop":
				if (state.isUnlocked())
				{
					plugin.sendMessage("The pet is already unlocked. Use ::bpet reset first.");
					break;
				}
				PetRoller.unlock(state, -1, PetState.UnlockSource.TEST);
				plugin.saveState();
				plugin.announceUnlock();
				break;
			case "retro":
				if (args.length < 2 || parseNumber(args[1]) <= 0)
				{
					plugin.sendMessage("Usage: ::bpet retro <kc>");
					break;
				}
				// Pretend this is a fresh install on an account with the given KC.
				PetState fresh = new PetState();
				plugin.setState(fresh);
				plugin.handleResult(roller.rollRetroactive(fresh, parseNumber(args[1]), plugin.baseDropRate()), -1);
				break;
			case "rate":
				int rate = args.length > 1 ? parseNumber(args[1]) : 0;
				plugin.setDropRateOverride(Math.max(0, rate));
				plugin.sendMessage(rate > 0
					? String.format("Base drop rate set to 1/%,d until the client restarts. Each brother not killed still halves it.", rate)
					: "Base drop rate back to normal.");
				break;
			case "partyecho":
				plugin.sendMessage(plugin.getPartyPets().toggleEcho()
					? "Party echo on: your pet, echoed back by the party server, is drawn as a party member's pet. Join a party first."
					: "Party echo off.");
				break;
			case "popup":
				plugin.getCollectionLogUi().queuePopup();
				break;
			case "reset":
				plugin.setState(new PetState());
				plugin.saveState();
				plugin.requestRetroactiveRoll();
				plugin.sendMessage("Barrows pet data reset for this account. The retroactive roll will run again on the next tick.");
				break;
			default:
				plugin.sendMessage("Usage: ::bpet status | roll [n] [brothers] | drop | retro <kc> | rate <n> | popup | partyecho | reset");
		}
	}

	/**
	 * After Barrows Pet has rolled a chest, say how many brothers it counted and the rate it rolled at.
	 */
	@Subscribe(priority = -1)
	public void onChatMessage(ChatMessage event)
	{
		BarrowsPetPlugin plugin = barrowsPet();
		if (plugin == null || event.getType() != ChatMessageType.GAMEMESSAGE
			|| !Text.removeTags(event.getMessage()).startsWith(CHEST_COUNT_PREFIX) || plugin.getLastChestKc() < 0)
		{
			return;
		}

		plugin.sendMessage(String.format("Chest %,d: %d brother(s) killed, rolled at 1/%,d.",
			plugin.getLastChestKc(), plugin.getLastChestBrothers(), plugin.getLastChestRate()));
	}

	/**
	 * Roll like a chest was looted, without advancing the rolled KC, so real chests are still rolled afterwards.
	 */
	private void simulateRolls(BarrowsPetPlugin plugin, PetState state, int count, int brothers)
	{
		count = Math.max(1, Math.min(count, PetRoller.MAX_SANE_KC));
		int rate = PetRoller.dropRate(plugin.baseDropRate(), brothers);
		int hits = 0;
		int firstHit = -1;
		for (int i = 1; i <= count; i++)
		{
			if (roller.roll(rate))
			{
				hits++;
				if (firstHit < 0)
				{
					firstHit = i;
				}
			}
		}

		plugin.sendMessage(String.format("Simulated %,d chest rolls at 1/%,d: %,d hit(s)%s.", count, rate, hits,
			firstHit > 0 ? String.format(", first on roll %,d", firstHit) : ""));

		if (hits > 0 && !state.isUnlocked())
		{
			PetRoller.unlock(state, -1, PetState.UnlockSource.TEST);
			plugin.saveState();
			plugin.announceUnlock();
		}
	}

	/**
	 * The running Barrows Pet plugin, or null if it is disabled.
	 */
	private BarrowsPetPlugin barrowsPet()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof BarrowsPetPlugin && pluginManager.isPluginEnabled(plugin))
			{
				return (BarrowsPetPlugin) plugin;
			}
		}
		return null;
	}

	private static int parseNumber(String value)
	{
		try
		{
			return Integer.parseInt(value.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
