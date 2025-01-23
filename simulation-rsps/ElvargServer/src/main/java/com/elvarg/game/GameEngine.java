package com.elvarg.game;

import com.elvarg.Server;
import com.elvarg.game.content.clan.ClanChatManager;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine which processes the game.
 *
 * @author Professor Oak
 */
public final class GameEngine implements Runnable {

    private static final Logger logger = Logger.getLogger(GameEngine.class.getName());
    
    /**
     * The {@link ScheduledExecutorService} which will be used for
     * this engine.
     */
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("GameThread").build()
    );

    private static final int TICK_RATE = System.getenv().containsKey("TICK_RATE")
                                         ? Integer.parseInt(System.getenv("TICK_RATE"))
                                         : GameConstants.GAME_ENGINE_PROCESSING_CYCLE_RATE;

    private int errorCount = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    private long lastErrorTime = 0;
    private static final long ERROR_RESET_TIME = TimeUnit.MINUTES.toMillis(1);

    /**
     * Initializes this {@link GameEngine}.
     */
    public void init() {
        logger.info("Initializing game engine with tick rate: " + TICK_RATE + "ms");
        executorService.scheduleAtFixedRate(this, 0, TICK_RATE, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        try {
            World.process();
            // Reset error count after successful tick
            resetErrorCountIfNeeded();
        } catch (Exception e) {
            handleError("Game processing error", e);
        } catch (Error e) {
            handleFatalError("Critical game error", e);
        }
    }

    private void handleError(String message, Exception e) {
        errorCount++;
        lastErrorTime = System.currentTimeMillis();
        
        logger.log(Level.SEVERE, message + " (Error " + errorCount + " of " + MAX_CONSECUTIVE_ERRORS + ")", e);
        
        try {
            // Try to save game state
            emergencySave();
        } catch (Exception saveError) {
            logger.log(Level.SEVERE, "Failed to perform emergency save after error", saveError);
        }

        if (errorCount >= MAX_CONSECUTIVE_ERRORS) {
            handleFatalError("Too many consecutive errors", e);
        }
    }

    private void handleFatalError(String message, Throwable e) {
        logger.log(Level.SEVERE, "FATAL: " + message, e);
        emergencySave();
        // Notify server admin/monitoring
        Server.getLogger().log(Level.SEVERE, "Game engine shutting down due to fatal error", e);
        // Initiate graceful shutdown
        executorService.shutdown();
        System.exit(1);
    }

    private void emergencySave() {
        logger.info("Performing emergency save...");
        World.savePlayers();
        ClanChatManager.save();
        logger.info("Emergency save completed");
    }

    private void resetErrorCountIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastErrorTime > ERROR_RESET_TIME) {
            errorCount = 0;
        }
    }
}