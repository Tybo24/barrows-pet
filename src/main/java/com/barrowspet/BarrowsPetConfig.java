package com.barrowspet;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(BarrowsPetConfig.GROUP)
public interface BarrowsPetConfig extends Config
{
	String GROUP = "barrowspet";

	@ConfigSection(
		name = "Pet",
		description = "Pet appearance and visibility",
		position = 0
	)
	String petSection = "pet";

	@ConfigSection(
		name = "Notifications",
		description = "How a pet drop is announced",
		position = 1
	)
	String notificationSection = "notifications";

	@ConfigItem(
		keyName = "showPet",
		name = "Show pet",
		description = "Have your Barrows pet follow you. It stays hidden while you have an in-game follower.",
		position = 0,
		section = petSection
	)
	default boolean showPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "brother",
		name = "Pet transmog",
		description = "Which Barrows brother follows you once the pet is unlocked",
		position = 1,
		section = petSection
	)
	default Brother brother()
	{
		return Brother.DHAROK;
	}

	@Range(min = 30, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "petSize",
		name = "Pet size",
		description = "Size of the pet relative to the real Barrows brother",
		position = 2,
		section = petSection
	)
	default int petSize()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "sharePet",
		name = "Share my pet with party",
		description = "Let members of your RuneLite party who also have this plugin see your pet",
		position = 3,
		section = petSection
	)
	default boolean sharePet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPartyPets",
		name = "Show party members' pets",
		description = "Show the Barrows pets of members of your RuneLite party who also have this plugin",
		position = 4,
		section = petSection
	)
	default boolean showPartyPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "collectionLogPopup",
		name = "Collection log popup",
		description = "Show the in-game collection log popup when you get the pet",
		position = 0,
		section = notificationSection
	)
	default boolean collectionLogPopup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showInCollectionLog",
		name = "Add to collection log",
		description = "Add a pet slot to the Barrows Chests page of the collection log",
		position = 1,
		section = notificationSection
	)
	default boolean showInCollectionLog()
	{
		return true;
	}
}
