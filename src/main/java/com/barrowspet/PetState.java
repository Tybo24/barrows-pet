package com.barrowspet;

import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-account pet progress. Serialized with Gson into the RuneScape profile config,
 * so each account (and each game mode, e.g. leagues) has its own state.
 */
@Data
class PetState
{
	static final int CURRENT_VERSION = 1;

	enum UnlockSource
	{
		/** Rolled on install against the account's previous chest KC. */
		RETROACTIVE,
		/** Rolled for chests opened while the plugin was disabled or not installed. */
		CATCH_UP,
		/** Rolled when looting a chest. */
		CHEST,
		/** Forced with a test command. */
		TEST,
	}

	private int version = CURRENT_VERSION;

	private boolean unlocked;
	/** The chest KC the pet was received on, or -1 if it was not tied to a chest. */
	private int unlockedAtKc = -1;
	private UnlockSource unlockSource;
	private long unlockedAtMillis;

	/** Set once the one-time roll for previous KC has happened. It never runs again while this is true. */
	private boolean retroactiveRollDone;
	private int retroactiveRolls;

	/**
	 * The highest chest KC that has been rolled for. A chest KC at or below this is never rolled again,
	 * which guards against duplicate chat messages and repeat retroactive rolls.
	 */
	private int lastRolledKc;
	private long totalRolls;

	/**
	 * Brothers seen killed since the last chest was looted. The game's own kill flags are not cleared when the
	 * chest is looted, so they can't tell a fresh run from a repeat, and kills are tracked here instead.
	 * May be null in state saved by older versions.
	 */
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Set<Brother> brothersKilledSinceChest = EnumSet.noneOf(Brother.class);

	Set<Brother> brothersKilledSinceChest()
	{
		if (brothersKilledSinceChest == null)
		{
			brothersKilledSinceChest = EnumSet.noneOf(Brother.class);
		}
		return brothersKilledSinceChest;
	}
}
