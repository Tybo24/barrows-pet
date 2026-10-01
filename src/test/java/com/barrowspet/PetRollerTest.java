package com.barrowspet;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PetRollerTest
{
	/** A Random whose nextInt always returns 0, so every roll hits. */
	private static final Random ALWAYS_HIT = new Random()
	{
		@Override
		public int nextInt(int bound)
		{
			return 0;
		}
	};

	/** A Random whose nextInt never returns 0, so every roll misses. */
	private static final Random NEVER_HIT = new Random()
	{
		@Override
		public int nextInt(int bound)
		{
			return bound - 1;
		}
	};

	@Test
	public void retroactiveRollsEveryPreviousChestOnce()
	{
		PetState state = new PetState();
		PetRoller.Result result = new PetRoller(NEVER_HIT).rollRetroactive(state, 1234, 5000);

		assertTrue(result.isRetroactive());
		assertEquals(1234, result.getEarlierRolls());
		assertFalse(result.isUnlocked());
		assertTrue(state.isRetroactiveRollDone());
		assertEquals(1234, state.getLastRolledKc());
		assertEquals(1234, state.getTotalRolls());
	}

	@Test
	public void retroactiveRollNeverRunsTwice()
	{
		PetState state = new PetState();
		new PetRoller(NEVER_HIT).rollRetroactive(state, 100, 5000);

		PetRoller.Result second = new PetRoller(ALWAYS_HIT).rollRetroactive(state, 200, 5000);

		assertTrue(second.isSkipped());
		assertFalse(state.isUnlocked());
		assertEquals(100, state.getLastRolledKc());
	}

	@Test
	public void retroactiveHitUnlocksOnFirstChest()
	{
		PetState state = new PetState();
		PetRoller.Result result = new PetRoller(ALWAYS_HIT).rollRetroactive(state, 500, 5000);

		assertEquals(1, result.getUnlockedAtKc());
		assertTrue(state.isUnlocked());
		assertEquals(PetState.UnlockSource.RETROACTIVE, state.getUnlockSource());
	}

	@Test
	public void sameChestIsNeverRolledTwice()
	{
		PetState state = new PetState();
		new PetRoller(NEVER_HIT).rollRetroactive(state, 10, 5000);
		new PetRoller(NEVER_HIT).rollChest(state, 11, 5000, 5000);

		PetRoller.Result replay = new PetRoller(ALWAYS_HIT).rollChest(state, 11, 5000, 5000);
		PetRoller.Result older = new PetRoller(ALWAYS_HIT).rollChest(state, 5, 5000, 5000);

		assertTrue(replay.isSkipped());
		assertTrue(older.isSkipped());
		assertFalse(state.isUnlocked());
		assertEquals(11, state.getTotalRolls());
	}

	@Test
	public void firstChestWithUnknownKcRollsPreviousChestsToo()
	{
		PetState state = new PetState();
		PetRoller.Result result = new PetRoller(NEVER_HIT).rollChest(state, 300, 5000, 5000);

		assertTrue(result.isRetroactive());
		assertEquals(299, result.getEarlierRolls());
		assertTrue(state.isRetroactiveRollDone());
		assertEquals(299, state.getRetroactiveRolls());
		assertEquals(300, state.getLastRolledKc());
		assertEquals(300, state.getTotalRolls());
	}

	@Test
	public void chestsOpenedWhilePluginWasOffAreCaughtUp()
	{
		PetState state = new PetState();
		new PetRoller(NEVER_HIT).rollRetroactive(state, 100, 5000);

		PetRoller.Result result = new PetRoller(NEVER_HIT).rollChest(state, 150, 5000, 5000);

		assertFalse(result.isRetroactive());
		assertEquals(49, result.getEarlierRolls());
		assertEquals(100, state.getRetroactiveRolls());
		assertEquals(150, state.getTotalRolls());
	}

	@Test
	public void chestHitUnlocksAtThatChest()
	{
		PetState state = new PetState();
		new PetRoller(NEVER_HIT).rollRetroactive(state, 100, 5000);

		PetRoller.Result result = new PetRoller(ALWAYS_HIT).rollChest(state, 101, 5000, 5000);

		assertEquals(101, result.getUnlockedAtKc());
		assertEquals(PetState.UnlockSource.CHEST, state.getUnlockSource());
	}

	@Test
	public void hitAfterUnlockIsADuplicate()
	{
		PetState state = new PetState();
		PetRoller.unlock(state, -1, PetState.UnlockSource.TEST);
		state.setRetroactiveRollDone(true);

		PetRoller.Result result = new PetRoller(ALWAYS_HIT).rollChest(state, 1, 5000, 5000);

		assertTrue(result.isDuplicate());
		assertFalse(result.isUnlocked());
		assertEquals(PetState.UnlockSource.TEST, state.getUnlockSource());
	}

	@Test
	public void absurdKcIsIgnored()
	{
		PetState state = new PetState();
		assertTrue(new PetRoller(ALWAYS_HIT).rollChest(state, PetRoller.MAX_SANE_KC + 1, 5000, 5000).isSkipped());
		assertFalse(state.isRetroactiveRollDone());
	}

	@Test
	public void eachMissingBrotherHalvesTheChance()
	{
		assertEquals(3000, PetRoller.dropRate(3000, 6));
		assertEquals(6000, PetRoller.dropRate(3000, 5));
		assertEquals(24000, PetRoller.dropRate(3000, 3));
		assertEquals(192000, PetRoller.dropRate(3000, 0));
		// Out-of-range counts are clamped.
		assertEquals(3000, PetRoller.dropRate(3000, 7));
		assertEquals(192000, PetRoller.dropRate(3000, -1));
		assertEquals(Integer.MAX_VALUE, PetRoller.dropRate(Integer.MAX_VALUE, 0));
	}

	@Test
	public void earlierChestsUseTheFullRateAndThisChestUsesItsOwn()
	{
		List<Integer> bounds = new ArrayList<>();
		Random recording = new Random()
		{
			@Override
			public int nextInt(int bound)
			{
				bounds.add(bound);
				return bound - 1;
			}
		};

		PetState state = new PetState();
		new PetRoller(recording).rollChest(state, 3, 192000, 3000);

		// Two earlier chests at the full rate, then the looted chest at its scaled rate.
		assertEquals(Arrays.asList(3000, 3000, 192000), bounds);
	}

	@Test
	public void killedBrothersSurviveSavingAndOldStateLoads()
	{
		Gson gson = new Gson();
		PetState state = new PetState();
		state.brothersKilledSinceChest().add(Brother.DHAROK);
		state.brothersKilledSinceChest().add(Brother.AHRIM);

		PetState loaded = gson.fromJson(gson.toJson(state), PetState.class);
		assertEquals(EnumSet.of(Brother.AHRIM, Brother.DHAROK), EnumSet.copyOf(loaded.brothersKilledSinceChest()));

		// State saved before kills were tracked has no such field.
		PetState old = gson.fromJson("{\"version\":1,\"unlocked\":false,\"lastRolledKc\":617}", PetState.class);
		assertTrue(old.brothersKilledSinceChest().isEmpty());
		assertEquals(617, old.getLastRolledKc());
	}

	@Test
	public void dropRateIsRoughlyOneInRate()
	{
		PetRoller roller = new PetRoller(new Random(42));
		int hits = 0;
		for (int i = 0; i < 1_000_000; i++)
		{
			if (roller.roll(5000))
			{
				hits++;
			}
		}
		// Expect ~200 hits for 1/5000 over a million rolls.
		assertTrue("hits=" + hits, hits > 150 && hits < 250);
	}
}
