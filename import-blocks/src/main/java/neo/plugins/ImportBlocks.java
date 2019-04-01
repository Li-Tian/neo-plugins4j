package neo.plugins;

import com.typesafe.config.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import neo.csharp.BitConverter;
import neo.csharp.Uint;
import neo.csharp.io.BinaryReader;
import neo.io.SerializeHelper;
import neo.ledger.Blockchain;
import neo.network.p2p.payloads.Block;
import neo.persistence.Snapshot;

public class ImportBlocks extends Plugin {


    public ImportBlocks() {
        onImport();
    }

    private static boolean checkMaxOnImportHeight(Uint currentImportBlockHeight)
    {
        if (Settings.Default.maxOnImportHeight.compareTo(Uint.ZERO) == 0
                || Settings.Default.maxOnImportHeight.compareTo(currentImportBlockHeight) >= 0) {
            return true;
        }else {
            return false;
        }
    }


    @Override
    public void configure() {
        Settings.load(getConfiguration());
    }


    private boolean onExport(String[] args)
    {
        if (args.length < 2) return false;
        if ( !"block".equals(args[1].toLowerCase()) && !"blocks".equals(args[1].toLowerCase()) ) {
            return false;
        }

        Uint start = Uint.ZERO;
        try{
            int tmp = Integer.parseInt(args[2]);
        }catch (NumberFormatException e){
            // ignore this error
        }

        Uint height = Blockchain.singleton().height();
        if (args.length >= 3 && start.compareTo(Uint.ZERO) > 0) {
            if (start.compareTo(height) > 0) {
                return true;
            }
            Uint count = args.length >= 4 ? new Uint(Integer.valueOf(args[3])) : Uint.MAX_VALUE_2;
            Uint tmp = Uint.add(Uint.subtract(height, start), Uint.ONE);
            count = tmp.compareTo(count) < 0 ? tmp: count;

            Uint end = Uint.subtract(Uint.add(start, count), Uint.ONE);
            String path = String.format("chain.%s.acc", start.toString());

            File file = new File(path);

                try {
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    RandomAccessFile accessFile = new RandomAccessFile(path, "rw");
                    if (accessFile.length() > 0){
                        accessFile.seek(Uint.BYTES);
                        byte[] buffer = new byte[Uint.BYTES];
                        accessFile.read(buffer, 0, buffer.length);
                        Uint length =  BitConverter.toUint(buffer);
                        start =  Uint.add(start, length);
                        accessFile.seek(Uint.BYTES);
                    }else{
                        accessFile.write(BitConverter.getBytes(start), 0, Uint.BYTES);
                    }
                    if (start.compareTo(end) <= 0){
                        accessFile.write(BitConverter.getBytes(count), 0, Uint.BYTES);
                    }
                    accessFile.seek(accessFile.length()); // goto end , TODO test

                    Snapshot snapshot = Blockchain.singleton().getSnapshot();
                    for (Uint i = start; i.compareTo(end) < 0; i = i.add(Uint.ONE)){
                        Block block = snapshot.getBlock(i);
                        byte[] array = SerializeHelper.toBytes(block);
                        accessFile.write(BitConverter.getBytes(array.length),0, Uint.BYTES);
                        accessFile.write(array, 0, array.length);

                        // TODO c# code: Console.SetCursorPosition(0, Console.CursorTop);
                        print("[{%s}/{%s}]", i.toString(), end.toString());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else {
                start = Uint.ZERO;
                Uint end = Blockchain.singleton().height();
                Uint count = Uint.subtract(Uint.add(end, Uint.ONE), start);
                String path = args.length >= 3 ? args[2]: "chain.acc";

            try {
                File file =new File(path);
                if (!file.exists()) {
                    file.createNewFile();
                }
                RandomAccessFile accessFile = new RandomAccessFile(path, "rw");
                if (accessFile.length() > 0){
                    byte[] buffer = new byte[Uint.BYTES];
                    accessFile.read(buffer, 0, buffer.length);
                    start = BitConverter.toUint(buffer);
                    accessFile.seek(0);
                }
                if (start.compareTo(end) <= 0) {
                    accessFile.write(BitConverter.getBytes(count), 0, Uint.BYTES);
                }
                accessFile.seek(accessFile.length());

                Snapshot snapshot = Blockchain.singleton().getSnapshot();
                for (Uint i = start; i.compareTo(end) <= 0; i = i.add(Uint.ONE))
                {
                    Block block = snapshot.getBlock(i);
                    byte[] array = SerializeHelper.toBytes(block);
                    accessFile.write(BitConverter.getBytes(array.length),0, Uint.BYTES);
                    accessFile.write(array, 0, array.length);

                    // TODO c# code: Console.SetCursorPosition(0, Console.CursorTop);
                    print("[{%s}/{%s}]", i.toString(), end.toString());
                }
            }catch (IOException e){
                e.printStackTrace();
            }
        }
        print("\n");
        return true;
    }


    private static Block[] getBlocks(InputStream stream, boolean read_start){
        BinaryReader reader = new BinaryReader(stream);

        Uint start = read_start ? reader.readUint() : Uint.ZERO;
        Uint count = reader.readUint();
        Uint end = Uint.add(start, count).subtract(Uint.ONE);

        int length = Math.min(Uint.subtract(end, start).intValue(), Settings.Default.maxOnImportHeight.intValue());
        Block[] blocks = new Block[ length];
        int i = 0;
        for (Uint height = start; height.compareTo(end) <= 0; height = height.add(Uint.ONE)){
            byte[] array = reader.readFully(reader.readUint().intValue());
            if (!checkMaxOnImportHeight(height)){
                continue;
            }
            if (height.compareTo(Blockchain.singleton().height()) > 0){
                Block block = SerializeHelper.parse(Block::new, array);
                blocks[i] = block;
                i++;
            }
        }
        return blocks;
    }




    private  void onImport()
    {
        suspendNodeStartup();

        try{
            String path_acc = "chain.acc";
            File file = new File(path_acc);
            if (file.exists()){
                Block[] blocks = getBlocks(new FileInputStream(file), false);

                // TODO akka system..
//                using (FileStream fs = new FileStream(path_acc, FileMode.Open, FileAccess.Read, FileShare.Read))
//                await System.Blockchain.Ask<Blockchain.ImportCompleted>(new Blockchain.Import
//                {
//                    Blocks = GetBlocks(fs)
//                });
            }


            String path_acc_zip = path_acc + ".zip";
            file = new File(path_acc_zip);
            if (file.exists()){
                ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
                Block[] blocks = getBlocks(zis, false);
//                await System.Blockchain.Ask<Blockchain.ImportCompleted>(new Blockchain.Import
//                {
//                    Blocks = GetBlocks(zs)
//                });
            }

            file = new File(".");
            File[] files = file.listFiles();

            ArrayList<FileDescriptor> descriptors = new ArrayList<>();
            Pattern p = Pattern.compile("\\d+");


            for (File subFile: files){
                String name = subFile.getName();
               if ( name.matches("chain.*.acc")){
                   FileDescriptor descriptor = new FileDescriptor();
                    descriptor.filename = name;
                    descriptor.isCompressed = false;
                    Matcher matcher = p.matcher(name);
                    matcher.find();
                    descriptor.start = Uint.valueOf(matcher.group());
                    descriptors.add(descriptor);
               }else if(name.matches("chain.*.acc.zip")){
                   FileDescriptor descriptor = new FileDescriptor();
                   descriptor.filename = name;
                   descriptor.isCompressed = true;
                   Matcher matcher = p.matcher(name);
                   matcher.find();
                   descriptor.start = Uint.valueOf(matcher.group());
                   descriptors.add(descriptor);
               }
            }
            descriptors.sort(Comparator.comparing(o -> o.start));
            for (FileDescriptor descriptor: descriptors){
                if (descriptor.start.compareTo(Uint.add(Blockchain.singleton().height(), Uint.ONE)) > 0) {
                    break;
                }
                if (descriptor.isCompressed){
                    ZipInputStream zis = new ZipInputStream(new FileInputStream(file));
                    Block[] blocks = getBlocks(zis, true);
                    // TODO ...
//                    await System.Blockchain.Ask<Blockchain.ImportCompleted>(new Blockchain.Import
//                    {
//                        Blocks = GetBlocks(zs, true)
//                    });
                }
                else{
                    Block[] blocks = getBlocks(new FileInputStream(file), true);
                    // TODO ...
//                    await System.Blockchain.Ask<Blockchain.ImportCompleted>(new Blockchain.Import
//                    {
//                        Blocks = GetBlocks(fs, true)
//                    });
                }
            }
        }catch (IOException e){
            e.printStackTrace();
        }
        resumeNodeStartup();
    }


    private class FileDescriptor  {
        String filename;
        Uint start;
        boolean isCompressed;
    }

    private boolean onHelp(String[] args)
    {
        if (args.length < 2) {
            return false;
        }
        if (! args[1].toLowerCase().equals(name().toLowerCase())) {
            return false;
        }

        print("%s} Commands:\n" + "\texport block[s] <index>\n", name());
        return true;
    }

    @Override
    protected  boolean onMessage(Object message)
    {
        if (!(message instanceof String[])){
            return false;
        }
        String[]  args = (String[]) message;
        if (args.length == 0) {
            return false;
        }

        switch (args[0].toLowerCase())
        {
            case "help":
                return onHelp(args);
            case "export":
                return onExport(args);
        }
        return false;
    }

     public static class Settings {

        public Uint maxOnImportHeight = Uint.ZERO;

        public static Settings Default;

        private Settings(Config config){
            if (config.hasPathOrNull("MaxOnImportHeight")){
                this.maxOnImportHeight = new Uint(config.getInt("MaxOnImportHeight"));
            }
        }

        public static void load(Config config){
            Default = new Settings(config);
        }
    }

    public void print(String format, Object... params){
        System.out.println(String.format(format, params));
    }
}
