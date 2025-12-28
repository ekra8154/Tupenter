package net.tupenter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class TupenterConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("tupenter.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static TupenterConfig INSTANCE = new TupenterConfig();

    public int gracePeriod = 10;
    public int machineGunDelay = 5;
    public int machineGunRate = 1;

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                INSTANCE = GSON.fromJson(reader, TupenterConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
