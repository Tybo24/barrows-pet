package com.barrowspet;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sent to party members so their clients can draw this player's Barrows pet. Only cosmetic details are
 * shared, and only with people the player has chosen to party with.
 */
@Value
// Compare only the pet details, so an unchanged pet isn't re-sent. The parent's member id is only set on
// arrival, and the parent classes don't define equality, so including them would never match.
@EqualsAndHashCode(callSuper = false)
public class BarrowsPetUpdate extends PartyMemberMessage
{
	/** The in-game name of the player the pet follows, used to find them in the scene. */
	String playerName;
	/** Whether the pet is out. False when it's locked, turned off, not shared, or a real follower is out. */
	boolean visible;
	/** May be null if the sender runs a newer version with a brother this version doesn't know. */
	Brother brother;
	int size;
}
