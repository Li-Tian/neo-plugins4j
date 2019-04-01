package neo.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.typesafe.config.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

import neo.UInt160;
import neo.csharp.BitConverter;
import neo.csharp.Uint;
import neo.csharp.common.ByteEnum;
import neo.csharp.io.ISerializable;
import neo.io.SerializeHelper;
import neo.io.caching.DataCache;
import neo.ledger.Blockchain;
import neo.ledger.StorageItem;
import neo.ledger.StorageKey;
import neo.persistence.Snapshot;

public class StatesDumper extends Plugin implements IPersistencePlugin {

    private JsonArray bsCache = new JsonArray();

    @Override
    public void onPersist(Snapshot snapshot, ArrayList<Blockchain.ApplicationExecuted> applicationExecutedList) {
        if (Settings.Default.PersistAction.hasFlag(PersistActions.StorageChanges))
            onPersistStorage(snapshot);
    }

    private void onPersistStorage(Snapshot snapshot) {
        Uint blockIndex = snapshot.getHeight();
        if (blockIndex.compareTo(Settings.Default.HeightToBegin) >= 0) {
            JsonArray array = new JsonArray();

            for (DataCache<StorageKey, StorageItem>.Trackable trackable : snapshot.getStorages().getChangeSet()) {
                JsonObject state = new JsonObject();

                switch (trackable.state) {
                    case ADDED:
                        state.addProperty("state", "Added");
                        state.addProperty("key", BitConverter.toHexString(trackable.key.key));
                        state.addProperty("value", BitConverter.toHexString(trackable.item.value));
                        // Here we have a new trackable.Key and trackable.Item
                        break;
                    case CHANGED:
                        state.addProperty("state", "Changed");
                        state.addProperty("key", BitConverter.toHexString(trackable.key.key));
                        state.addProperty("value", BitConverter.toHexString(trackable.item.value));
                        break;
                    case DELETED:
                        state.addProperty("state", "Deleted");
                        state.addProperty("key", BitConverter.toHexString(trackable.key.key));
                        break;
                }
                array.add(state);
            }

            JsonObject bsItem = new JsonObject();
            bsItem.addProperty("block", blockIndex.intValue());
            bsItem.addProperty("size", array.size());
            bsItem.add("storage", array);
            bsCache.add(bsItem);
        }
    }

    @Override
    public void onCommit(Snapshot snapshot) {
        if (Settings.Default.PersistAction.hasFlag(PersistActions.StorageChanges))
            onCommitStorage(snapshot);
    }

    @Override
    public boolean shouldThrowExceptionFromCommit(Exception ex) {
        print("Error writing States with StatesDumper.{Environment.NewLine}{ex}", System.lineSeparator(), ex);
        return true;
    }


    @Override
    public void configure() {
        Settings.load(getConfiguration());
    }

    @Override
    protected boolean onMessage(Object message) {
        if (!(message instanceof String[])) {
            return false;
        }
        String[] args = (String[]) message;
        if (args.length == 0) {
            return false;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                return onHelp(args);
            case "dump":
                return onDump(args);
        }
        return false;
    }


    private boolean onDump(String[] args) {
        if (args.length < 2) return false;
        switch (args[1].toLowerCase()) {
            case "storage":
                dump(args.length >= 3
                        ? Blockchain.singleton().getStore().getStorages().find(UInt160.parse(args[2]).toArray())
                        : Blockchain.singleton().getStore().getStorages().find());
                return true;
            default:
                return false;
        }
    }


    private static <TKey extends ISerializable, TValue extends ISerializable> void dump(Collection<Map.Entry<TKey, TValue>> states) {

        JsonArray array = new JsonArray(states.size());
        for (Map.Entry<TKey, TValue> p : states) {
            JsonObject item = new JsonObject();
            item.addProperty("key", BitConverter.toHexString(SerializeHelper.toBytes(p.getKey())));
            item.addProperty("value", BitConverter.toHexString(SerializeHelper.toBytes(p.getValue())));
            array.add(item);
        }

        String path = "dump.json";
        appendToFile(array.toString(), path);

        print("States (%d) have been dumped into file %s", array.size(), path);
    }


    private boolean onHelp(String[] args) {
        if (args.length < 2) return false;
        if (!args[1].toLowerCase().equals(name().toLowerCase())) {
            return false;
        }
        print("%s Commands:\\n\" + \"\\tdump storage <key>\\n", name());
        return true;
    }

    public static void appendToFile(String content, String path) {
        File file = new File(path);
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            writer.append(content);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void print(String format, Object... params) {
        System.out.println(String.format(format, params));
    }

    public void onCommitStorage(Snapshot snapshot) {
        Uint blockIndex = snapshot.getHeight();
        if (bsCache.size() > 0) {
            if ((blockIndex.longValue() % Settings.Default.BlockCacheSize.longValue() == 0)
                    || (blockIndex.longValue() > Settings.Default.HeightToStartRealTimeSyncing.longValue())) {
                String dirPath = "./Storage";
                createDirectory(dirPath);
                String path = String.format("%s/dump-block-%s.json",
                        handlePaths(dirPath, blockIndex), blockIndex.toString());
                appendToFile(bsCache.toString(), path);
                bsCache = new JsonArray();
            }
        }
    }


    private static String handlePaths(String dirPath, Uint blockIndex) {
        //Default Parameter
        Uint storagePerFolder = new Uint(100000);
        // C#:  Uint folder = (((blockIndex.longValue() - 1) / storagePerFolder.longValue()) + 1) * storagePerFolder.longValue();
        Uint folder = storagePerFolder.multiply(Uint.ONE.add(Uint.divide(blockIndex.subtract(Uint.ONE), storagePerFolder)));
        if (blockIndex.intValue() == 0) {
            folder = Uint.ZERO;
        }
        String dirPathWithBlock = String.format("%s/BlockStorage_%s", dirPath, folder);
        createDirectory(dirPathWithBlock);
        return dirPathWithBlock;
    }

    public static void createDirectory(String dirPath) {
        File file = new File(dirPath);
        if (!file.exists()) {
            file.mkdirs();
        }
    }


    public enum PersistActions implements ByteEnum {

        StorageChanges((byte) 0b00000001);

        PersistActions(byte value) {
            this.value = value;
        }

        @Override
        public byte value() {
            return this.value;
        }

        private byte value;


        private boolean hasFlag(PersistActions other) {
            return (this.value & other.value) == other.value;
        }
    }


    static class Settings {

        public Uint BlockCacheSize;
        public Uint HeightToBegin;
        public Uint HeightToStartRealTimeSyncing;

        public PersistActions PersistAction;

        public static Settings Default;


        private Settings(Config section) {
            /// Geting settings for storage changes state dumper
            this.BlockCacheSize = getValueOrDefault(section, "BlockCacheSize", new Uint(1000), p -> Uint.parseUint(p));
            this.HeightToBegin = getValueOrDefault(section, "HeightToBegin", Uint.ZERO, p -> Uint.parseUint(p));
            this.HeightToStartRealTimeSyncing = getValueOrDefault(section, "HeightToStartRealTimeSyncing", new Uint(2883000), p -> Uint.parseUint(p));
            this.PersistAction = getValueOrDefault(section, "PersistAction", PersistActions.StorageChanges, p -> PersistActions.valueOf(p));
        }

        public <T> T getValueOrDefault(Config section, String filed, T defaultValue, Function<String, T> selector) {
            if (!section.hasPathOrNull(filed)) {
                return defaultValue;
            }
            return selector.apply(section.getString(filed));
        }

        public static void load(Config section) {
            Default = new Settings(section);
        }
    }
}
