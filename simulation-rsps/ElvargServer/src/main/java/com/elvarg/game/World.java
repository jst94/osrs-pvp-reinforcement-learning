package com.elvarg.game;

import com.elvarg.Server;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.elvarg.game.content.minigames.MinigameHandler;
import com.elvarg.game.entity.impl.MobileList;
import com.elvarg.game.entity.impl.grounditem.ItemOnGround;
import com.elvarg.game.entity.impl.grounditem.ItemOnGroundManager;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.entity.impl.object.GameObject;
import com.elvarg.game.entity.impl.object.MapObjects;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.entity.impl.playerbot.PlayerBot;
import com.elvarg.game.entity.updating.NPCUpdating;
import com.elvarg.game.entity.updating.PlayerUpdating;
import com.elvarg.game.entity.updating.sync.GameSyncExecutor;
import com.elvarg.game.entity.updating.sync.GameSyncTask;
import com.elvarg.game.event.EventDispatcher;
import com.elvarg.game.event.events.PlayerPacketsFlushedEvent;
import com.elvarg.game.model.Graphic;
import com.elvarg.game.model.GraphicHeight;
import com.elvarg.game.model.Location;
import com.elvarg.game.model.commands.impl.Players;
import com.elvarg.game.task.TaskManager;
import com.elvarg.util.Misc;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import static com.elvarg.game.GameConstants.PLAYER_PERSISTENCE;

/**
 * Represents the world, processing it and its characters.
 *
 * @author Professor Oak
 * @author lare96
 */
public class World {

	private static final Logger logger = Logger.getLogger(World.class.getName());
	private static final int MAX_PLAYERS = 500;
	private static final int ERROR_THRESHOLD = 3; // Number of errors before forcing logout

	/**
	 * The collection of active {@link Player}s.
	 */
	private static MobileList<Player> players = new MobileList<>(MAX_PLAYERS);

	/**
	 * The collection of active {@link PlayerBot}s.
	 */
	private static TreeMap<String, PlayerBot> playerBots = new TreeMap<String, PlayerBot>(String.CASE_INSENSITIVE_ORDER);
	
	// Error tracking
	private static final ThreadLocal<Integer> playerErrors = ThreadLocal.withInitial(() -> 0);
	private static final ThreadLocal<Integer> npcErrors = ThreadLocal.withInitial(() -> 0);
	
	/**
	 * Handles a player-related error, with recovery attempts before forcing logout
	 * @param player The player that encountered the error
	 * @param error The exception that occurred
	 * @param context Additional context about what was happening
	 * @return true if the player should be logged out
	 */
	private static boolean handlePlayerError(Player player, Exception error, String context) {
	    int errorCount = playerErrors.get() + 1;
	    playerErrors.set(errorCount);
	    
	    logger.log(Level.SEVERE,
	        String.format("Error for player %s during %s (Error %d/%d)",
	            player.getUsername(), context, errorCount, ERROR_THRESHOLD),
	        error);
	    
	    if (errorCount >= ERROR_THRESHOLD) {
	        logger.log(Level.SEVERE,
	            String.format("Forcing logout for player %s after %d consecutive errors",
	                player.getUsername(), errorCount));
	        return true;
	    }
	    return false;
	}
	
	/**
	 * Handles an NPC-related error
	 * @param npc The NPC that encountered the error
	 * @param error The exception that occurred
	 * @param context Additional context about what was happening
	 */
	private static void handleNPCError(NPC npc, Exception error, String context) {
	    int errorCount = npcErrors.get() + 1;
	    npcErrors.set(errorCount);
	    
	    logger.log(Level.SEVERE,
	        String.format("Error for NPC %d at %s during %s (Error %d)",
	            npc.getId(), npc.getLocation(), context, errorCount),
	        error);
	}
	
	/**
	 * Resets error counters after successful operations
	 */
	private static void resetErrorCounters() {
	    playerErrors.set(0);
	    npcErrors.set(0);
	}

	/**
	 * The collection of active {@link NPC}s.
	 */
	private static MobileList<NPC> npcs = new MobileList<>(5000);

	/**
	 * The collection of active {@link ItemOnGround}s..
	 */
	private static List<ItemOnGround> items = new LinkedList<>();

	/**
	 * The collection of active {@link GameObject}s..
	 */
	private static List<GameObject> objects = new LinkedList<>();

	/**
	 * The collection of removed {@link GameObject}s..
	 */
	private static LinkedHashSet<GameObject> removedObjects = new LinkedHashSet<>();

	/**
	 * The collection of {@link Players}s waiting to be added to the game.
	 */
	private static Queue<Player> addPlayerQueue = new ConcurrentLinkedQueue<>();

	/**
	 * The collection of {@link Players}s waiting to be removed from the game.
	 */
	private static Queue<Player> removePlayerQueue = new ConcurrentLinkedQueue<>();

	/**
	 * The collection of {@link Players}s waiting to be added to the game.
	 */
	private static Queue<NPC> addNPCQueue = new ConcurrentLinkedQueue<>();

	/**
	 * The collection of {@link Players}s waiting to be removed from the game.
	 */
	private static Queue<NPC> removeNPCQueue = new ConcurrentLinkedQueue<>();

	/**
	 * The manager for game synchronization.
	 */
	private static GameSyncExecutor executor = new GameSyncExecutor();

	/**
	 * Processes the world.
	 */
	public static void process() {
		// Process all active {@link Task}s..
		TaskManager.process();

		// Process all minigames
		MinigameHandler.process();

		// Process all ground items..
		ItemOnGroundManager.process();

		// Add pending players..
		for (int i = 0; i < GameConstants.QUEUED_LOOP_THRESHOLD; i++) {
			Player player = addPlayerQueue.poll();
			if (player == null) {
				break;
			}
			// Kick any copies before adding the new player
			World.getPlayerByName(player.getUsername()).ifPresent(e -> e.requestLogout());
			getPlayers().add(player);
		}

		// Deregister queued players.
		int amount = 0;
		Iterator<Player> $it = removePlayerQueue.iterator();
		while ($it.hasNext()) {
			Player player = $it.next();
			if (player == null || amount >= GameConstants.QUEUED_LOOP_THRESHOLD) {
				break;
			}
			if (player.canLogout() || player.getForcedLogoutTimer().finished() || Server.isUpdating()) {
				getPlayers().remove(player);
				$it.remove();
			}
			amount++;
		}

		// Add pending Npcs..
		for (int i = 0; i < GameConstants.QUEUED_LOOP_THRESHOLD; i++) {
			NPC npc = addNPCQueue.poll();
			if (npc == null) {
				break;
			}
			getNpcs().add(npc);
		}

		// Removing pending npcs..
		for (int i = 0; i < GameConstants.QUEUED_LOOP_THRESHOLD; i++) {
			NPC npc = removeNPCQueue.poll();
			if (npc == null) {
				break;
			}
			getNpcs().remove(npc);
		}

		// Handle synchronization tasks.
		// Process player packets first, then process player (ex. combat).
		// This seems to be how OSRS does it (at least, for combat).
		final List<Player> sortedPlayers = players.stream()
		                                    .filter(Objects::nonNull)
		                                    .sorted(Comparator.comparingDouble(Player::getPid))
											.toList();
		sortedPlayers.forEach(player -> {
			       try {
				       player.processPackets();
				       resetErrorCounters(); // Reset on success
			       }
			       catch (Exception e) {
				       if (handlePlayerError(player, e, "packet processing")) {
					       player.requestLogout();
				       }
			       }
		       });
		sortedPlayers.forEach(player -> {
			       try {
				       player.processPlayer();
				       resetErrorCounters(); // Reset on success
			       }
			       catch (Exception e) {
				       if (handlePlayerError(player, e, "player processing")) {
					       player.requestLogout();
				       }
			       }
		       });

		executor.sync(new GameSyncTask(false, false) {
			@Override
			public void execute(int index) {
				NPC npc = npcs.get(index);
				if (npc != null) {
					try {
						npc.process();
						resetErrorCounters(); // Reset on success
					} catch (Exception e) {
						handleNPCError(npc, e, "NPC processing");
					}
				}
			}
		});

		executor.sync(new GameSyncTask(true) {
			@Override
			public void execute(int index) {
				Player player = players.get(index);
				if (player instanceof PlayerBot) {
					// No need to sync a bot
					// Note this breaks some stuff like multi target spells, but boosts performance
					return;
				}
				synchronized (player) {
					try {
						PlayerUpdating.update(player);
						NPCUpdating.update(player);
						resetErrorCounters(); // Reset on success
					} catch (Exception e) {
						if (handlePlayerError(player, e, "player/NPC updating")) {
							player.requestLogout();
						}
					}
				}
			}
		});

		executor.sync(new GameSyncTask(true) {
			@Override
			public void execute(int index) {
				Player player = players.get(index);
				synchronized (player) {
					try {
						player.resetUpdating();
						player.setCachedUpdateBlock(null);
						player.getSession().flush();
						EventDispatcher.getGlobal().dispatch(new PlayerPacketsFlushedEvent(player));
						resetErrorCounters(); // Reset on success
					} catch (Exception e) {
						if (handlePlayerError(player, e, "player state reset")) {
							player.requestLogout();
						}
					}
				}
			}
		});

		executor.sync(new GameSyncTask(false) {
			@Override
			public void execute(int index) {
				NPC npc = npcs.get(index);
				synchronized (npc) {
					try {
						npc.resetUpdating();
					}
					catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		});
	}

	/**
	 * Gets a player by their username.
	 *
	 * @param username
	 * 		The username of the player.
	 * @return The player with the matching username.
	 */
	public static Optional<Player> getPlayerByName(String username) {
		return players.search(p -> p != null && p.getUsername().equals(Misc.formatText(username)));
	}

	/**
	 * Broadcasts a message to all players in the game.
	 *
	 * @param message
	 * 		The message to broadcast.
	 */
	public static void sendMessage(String message) {
		players.forEach(p -> p.getPacketSender().sendMessage(message));
	}

	/**
	 * Broadcasts a message to all staff-members in the game.
	 *
	 * @param message
	 * 		The message to broadcast.
	 */
	public static void sendStaffMessage(String message) {
		players.stream()
		       .filter(p -> !Objects.isNull(p) && p.isStaff())
		       .forEach(p -> p.getPacketSender().sendMessage(message));
	}

	/**
	 * Saves all players in the game.
	 */
	public static void savePlayers() {
		players.forEach(PLAYER_PERSISTENCE::save);
	}

	public static MobileList<Player> getPlayers() {
		return players;
	}

	public static MobileList<NPC> getNpcs() {
		return npcs;
	}

	public static TreeMap<String, PlayerBot> getPlayerBots() {return playerBots;}

	public static List<ItemOnGround> getItems() {
		return items;
	}

	public static List<GameObject> getObjects() {
		return objects;
	}

	public static LinkedHashSet<GameObject> getRemovedObjects() {
		return removedObjects;
	}

	public static Queue<Player> getAddPlayerQueue() {
		return addPlayerQueue;
	}

	public static Queue<Player> getRemovePlayerQueue() {
		return removePlayerQueue;
	}

	public static Queue<NPC> getAddNPCQueue() {
		return addNPCQueue;
	}

	public static Queue<NPC> getRemoveNPCQueue() {
		return removeNPCQueue;
	}

	/**
	 * Simple way of finding objects in the world
	 *
	 * @param id
	 * @param loc
	 * @return
	 */
	public static Optional<GameObject> findSpawnedObject(int id, Location loc) {
		return getObjects().stream().filter(i -> i.getId() == id).filter(l -> l.getLocation().equals(loc)).findAny();
	}

	public static GameObject findCacheObject(Player player, int id, Location loc) {
		return MapObjects.get(player, id, loc);
	}

	/**
	 * Sends GFX at the location to all players within a 32 tile radius
	 *
	 * @param id
	 * @param position
	 */
	public static void sendLocalGraphics(int id, Location position, GraphicHeight graphicHeight) {
		players.stream()
		       .filter(Objects::nonNull)
		       .filter(p -> p.getLocation().isWithinDistance(position, 32))
		       .forEach(p -> p.getPacketSender().sendGraphic(new Graphic(id, graphicHeight), position));
	}
}
