package com.barrowspet;

import java.util.Random;
import lombok.Value;

/**
 * Pure drop logic, kept free of client code so it can be unit tested.
 * <p>
 * Every chest KC gets exactly one roll, ever. {@link PetState#getLastRolledKc()} records the highest KC
 * rolled so far, so replayed chat messages, re-running the retroactive roll, or re-installing the plugin
 * can never roll the same chest twice.
 */
class PetRoller
{
	/** The drop rate when all six brothers were killed. */
	static final int BASE_DROP_RATE = 3000;
	static final int BROTHERS = 6;

	/** Ignore anything above this; no account has anywhere near this many chests. */
	static final int MAX_SANE_KC = 250_000;

	@Value
	static class Result
	{
		/** The KC was already accounted for, so nothing was rolled. */
		boolean skipped;
		/** This call performed the one-time roll for previous KC. */
		boolean retroactive;
		/** How many earlier chests were rolled before the current one (retroactive or catch-up). */
		int earlierRolls;
		/** The chest KC the pet was unlocked on by this call, or -1. */
		int unlockedAtKc;
		/** A roll hit while the pet was already unlocked. */
		boolean duplicate;

		boolean isUnlocked()
		{
			return unlockedAtKc > 0;
		}
	}

	private static final Result SKIPPED = new Result(true, false, 0, -1, false);

	private final Random random;

	PetRoller(Random random)
	{
		this.random = random;
	}

	/**
	 * The drop rate for a chest looted after killing {@code brothersKilled} brothers. Each brother not killed
	 * halves the chance, so looting the chest without doing the run (only one kill of any enemy is needed to
	 * get loot) is never a faster way to the pet than full runs.
	 */
	static int dropRate(int baseRate, int brothersKilled)
	{
		int missing = BROTHERS - Math.max(0, Math.min(BROTHERS, brothersKilled));
		return (int) Math.min(Integer.MAX_VALUE, (long) baseRate << missing);
	}

	boolean roll(int dropRate)
	{
		return random.nextInt(Math.max(1, dropRate)) == 0;
	}

	/**
	 * Roll once for each KC in [from, to], returning the first KC that hit, or -1.
	 */
	int rollRange(int from, int to, int dropRate)
	{
		for (int kc = from; kc <= to; kc++)
		{
			if (roll(dropRate))
			{
				return kc;
			}
		}
		return -1;
	}

	/**
	 * The one-time roll on install, for every chest up to and including {@code kc}.
	 */
	Result rollRetroactive(PetState state, int kc, int dropRate)
	{
		if (state.isRetroactiveRollDone() || kc <= state.getLastRolledKc() || kc > MAX_SANE_KC)
		{
			return SKIPPED;
		}

		int from = state.getLastRolledKc() + 1;
		int rolls = kc - from + 1;
		int hitKc = rollRange(from, kc, dropRate);

		state.setRetroactiveRollDone(true);
		state.setRetroactiveRolls(rolls);
		state.setLastRolledKc(kc);
		state.setTotalRolls(state.getTotalRolls() + rolls);

		return applyHit(state, hitKc, PetState.UnlockSource.RETROACTIVE, true, rolls);
	}

	/**
	 * A chest was looted and the game reported the new chest count {@code kc}.
	 * <p>
	 * Any gap between the last rolled KC and this one (chests opened before install, or while the plugin
	 * was turned off) is rolled first at {@code earlierDropRate}, since how many brothers were killed for
	 * those chests is unknown. Then this chest is rolled at {@code chestDropRate}.
	 */
	Result rollChest(PetState state, int kc, int chestDropRate, int earlierDropRate)
	{
		if (kc <= state.getLastRolledKc() || kc > MAX_SANE_KC)
		{
			return SKIPPED;
		}

		boolean retroactive = !state.isRetroactiveRollDone();
		int from = state.getLastRolledKc() + 1;
		int earlierRolls = kc - from;
		int earlierHitKc = rollRange(from, kc - 1, earlierDropRate);
		boolean chestHit = roll(chestDropRate);

		if (retroactive)
		{
			state.setRetroactiveRollDone(true);
			state.setRetroactiveRolls(earlierRolls);
		}
		state.setLastRolledKc(kc);
		state.setTotalRolls(state.getTotalRolls() + earlierRolls + 1);

		boolean duplicate = false;
		int unlockedAtKc = -1;
		if (earlierHitKc > 0)
		{
			Result earlier = applyHit(state, earlierHitKc,
				retroactive ? PetState.UnlockSource.RETROACTIVE : PetState.UnlockSource.CATCH_UP, retroactive, earlierRolls);
			unlockedAtKc = earlier.getUnlockedAtKc();
			duplicate = earlier.isDuplicate();
		}
		if (chestHit)
		{
			Result chest = applyHit(state, kc, PetState.UnlockSource.CHEST, retroactive, earlierRolls);
			unlockedAtKc = Math.max(unlockedAtKc, chest.getUnlockedAtKc());
			duplicate |= chest.isDuplicate();
		}

		return new Result(false, retroactive, earlierRolls, unlockedAtKc, duplicate);
	}

	/**
	 * Unlock the pet outright, used by the test command.
	 */
	static void unlock(PetState state, int kc, PetState.UnlockSource source)
	{
		state.setUnlocked(true);
		state.setUnlockedAtKc(kc);
		state.setUnlockSource(source);
		state.setUnlockedAtMillis(System.currentTimeMillis());
	}

	private static Result applyHit(PetState state, int hitKc, PetState.UnlockSource source, boolean retroactive, int earlierRolls)
	{
		if (hitKc <= 0)
		{
			return new Result(false, retroactive, earlierRolls, -1, false);
		}

		if (state.isUnlocked())
		{
			return new Result(false, retroactive, earlierRolls, -1, true);
		}

		unlock(state, hitKc, source);
		return new Result(false, retroactive, earlierRolls, hitKc, false);
	}
}
