package com.elvarg.game.entity.impl.player.persistence.jsonfile;

import com.elvarg.Server;
import com.elvarg.game.entity.impl.player.Player;
import com.elvarg.game.entity.impl.player.persistence.PlayerPersistence;
import com.elvarg.game.entity.impl.player.persistence.PlayerSave;
import com.elvarg.net.security.BCrypt;
import com.elvarg.util.Misc;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JSONFilePlayerPersistence extends PlayerPersistence {

    private static final String PATH = "./data/saves/characters/";
    private static final Gson BUILDER = new GsonBuilder().create();
    private static final Logger logger = Logger.getLogger(JSONFilePlayerPersistence.class.getName());
    private static final int BCRYPT_ROUNDS = 10; // Standard number of rounds for BCrypt

    @Override
    public PlayerSave load(String username) {
        if (!exists(username)) {
            return null;
        }

        Path path = Paths.get(PATH, username + ".json");
        File file = path.toFile();

        try (FileReader fileReader = new FileReader(file)) {
            PlayerSave save = BUILDER.fromJson(fileReader, PlayerSave.class);
            if (save == null) {
                logger.log(Level.SEVERE, "Failed to deserialize player save for: {0}", username);
                throw new RuntimeException("Failed to deserialize player save");
            }
            return save;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load player save for: " + username, e);
            throw new RuntimeException("Failed to load player save for: " + username, e);
        }
    }

    @Override
    public void save(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }

        PlayerSave save = PlayerSave.fromPlayer(player);
        Path path = Paths.get(PATH, player.getUsername() + ".json");
        File file = path.toFile();
        setupDirectory(file);

        Gson builder = new GsonBuilder().setPrettyPrinting().create();

        try (FileWriter writer = new FileWriter(file)) {
            String json = builder.toJson(save);
            if (json == null || json.isEmpty()) {
                throw new RuntimeException("Failed to serialize player save data");
            }
            writer.write(json);
            logger.log(Level.FINE, "Successfully saved player data for: {0}", player.getUsername());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to save player data for: " + player.getUsername(), e);
            throw new RuntimeException("Failed to save player data for: " + player.getUsername(), e);
        }
    }

    @Override
    public boolean exists(String username) {
        String formattedUsername = Misc.formatPlayerName(username.toLowerCase());
        return new File(PATH + formattedUsername + ".json").exists();
    }

    @Override
    public String encryptPassword(String plainPassword) {
        try {
            return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_ROUNDS));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to encrypt password", e);
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    @Override
    public boolean checkPassword(String plainPassword, PlayerSave playerSave) {
        try {
            String storedHash = playerSave.getPasswordHashWithSalt();
            return storedHash != null && BCrypt.checkpw(plainPassword, storedHash);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to verify password", e);
            return false;
        }
    }

    private void setupDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir == null) {
            throw new RuntimeException("Invalid file path - no parent directory");
        }

        parentDir.setWritable(true);
        if (!parentDir.exists()) {
            try {
                if (!parentDir.mkdirs()) {
                    throw new RuntimeException("Failed to create directory: " + parentDir.getAbsolutePath());
                }
            } catch (SecurityException e) {
                logger.log(Level.SEVERE, "Unable to create directory for player data", e);
                throw new RuntimeException("Unable to create directory for player data", e);
            }
        }
    }
}
