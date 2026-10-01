package com.barrowspet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

public class BarrowsPetUpdateTest
{
	@Test
	public void identicalPetsAreEqualSoTheyAreNotResent()
	{
		assertEquals(
			new BarrowsPetUpdate("TyboJones", true, Brother.GUTHAN, 60),
			new BarrowsPetUpdate("TyboJones", true, Brother.GUTHAN, 60));
	}

	@Test
	public void anyChangeIsSent()
	{
		BarrowsPetUpdate pet = new BarrowsPetUpdate("TyboJones", true, Brother.GUTHAN, 60);
		assertNotEquals(pet, new BarrowsPetUpdate("TyboJones", false, Brother.GUTHAN, 60));
		assertNotEquals(pet, new BarrowsPetUpdate("TyboJones", true, Brother.DHAROK, 60));
		assertNotEquals(pet, new BarrowsPetUpdate("TyboJones", true, Brother.GUTHAN, 70));
		assertNotEquals(pet, new BarrowsPetUpdate("TyboOfRivia", true, Brother.GUTHAN, 60));
	}
}
