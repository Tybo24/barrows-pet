# Barrows Pet

A cosmetic Barrows brother pet that follows you around. It is drawn by your client and nothing is sent to the game, so only you can see it, plus members of your RuneLite party who also have the plugin.

## How you get it
- **Every chest:** each time you loot the Barrows chest you get a roll. Killing all six brothers gives 1/3,000, and each brother not killed halves the chance (5 brothers is 1/6,000, down to 1/192,000 for none). Looting the chest without doing the run is never a faster way to the pet.
- **Your previous KC:** the first time you log in with the plugin, it rolls once for every chest you have already opened, at the full 1/3,000 rate. It reads your chest count from the Chat Commands plugin. If that isn't known yet, the roll happens on your next chest instead.
- Every chest KC is rolled exactly once. Chests opened while the plugin was off are caught up on your next chest.

When you get the pet you see the pet drop message, a collection log chat message and the collection log popup. The pet also gets a slot on the Barrows Chests page of the collection log.

## Settings
- **Show pet**: have the pet follow you. If you drop a real pet (or have any other in-game follower out), the Barrows pet steps aside, and it comes back as soon as you pick that pet up. If it gets stuck or left behind, use the **Call Follower** button on the Worn Equipment tab to bring it back beside you.
- **Pet transmog**: Ahrim, Dharok, Guthan, Karil, Torag or Verac.
- **Pet size**: 30–100% of a real brother.
- **Share my pet with party** and **Show party members' pets**: in a RuneLite party, members who also have this plugin see each other's Barrows pets. This uses RuneLite's own party service, and only the pet (brother, size, whether it's out) and your name are shared, only with your party.
- **Collection log popup**: show the in-game collection log popup when you get the pet.
- **Add to collection log**: add a pet slot to the Barrows Chests page of the collection log, greyed out until you get the pet.

## Development
Run the dev client with `./gradlew run` (or the **Barrows Pet** IntelliJ run configuration). It also loads **Barrows Pet Dev Tools**, a developer-only plugin in `src/test` that never ships to the Plugin Hub. Type in the chatbox:

| Command | Effect |
| --- | --- |
| `::bpet status` | Show saved state |
| `::bpet roll [n] [brothers]` | Simulate n chest rolls with that many brothers killed (default 6; doesn't change your rolled KC) |
| `::bpet drop` | Force the pet drop |
| `::bpet retro <kc>` | Wipe state and run the install-time roll as if your KC were `<kc>` |
| `::bpet rate <n>` | Set the base drop rate to 1/n until the client restarts (e.g. 2 to test quickly; each brother not killed still halves it). `::bpet rate 0` resets it |
| `::bpet popup` | Show the collection log popup |
| `::bpet partyecho` | Toggle seeing your own shared pet as a party member's pet (join a party on your own to test sharing solo) |
| `::bpet reset` | Wipe this account's state |

Every real chest also prints how many brothers were counted and the rate it rolled at.
