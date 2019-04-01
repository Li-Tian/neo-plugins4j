package neo.plugins;

import com.typesafe.config.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import neo.Fixed8;
import neo.UInt160;
import neo.csharp.common.ByteEnum;
import neo.network.p2p.payloads.Transaction;
import neo.network.p2p.payloads.TransactionType;

public class SimplePolicyPlugin extends Plugin implements ILogPlugin, IPolicyPlugin {

    private static String logDictionary = Class.class.getClass().getResource("/").getPath() +"/Logs";

    private static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");

    @Override
    public boolean filterForMemoryPool(Transaction tx) {
        if (!verifySizeLimits(tx)) return false;

        switch (Settings.Default.blockedAccounts.type)
        {
            case AllowAll:
                return true;
            case AllowList:
                return Arrays.stream(tx.witnesses).allMatch(p -> Settings.Default.blockedAccounts.list.contains(UInt160.parseToScriptHash(p.verificationScript)))
                        || Arrays.stream(tx.outputs).allMatch(p -> Settings.Default.blockedAccounts.list.contains(p.scriptHash));
            case DenyList:
                return Arrays.stream(tx.witnesses).allMatch(p -> !Settings.Default.blockedAccounts.list.contains(UInt160.parseToScriptHash(p.verificationScript)))
                        && Arrays.stream(tx.outputs).allMatch(p ->  !Settings.Default.blockedAccounts.list.contains(p.scriptHash));
            default:
                return false;
        }
    }

    @Override
    public Collection<Transaction> filterForBlock(Collection<Transaction> transactions){
        return filterForBlock_Policy2(transactions);
    }


    @Override
    public int maxTxPerBlock (){
        return Settings.Default.maxTransactionsPerBlock;
    }

    @Override
    public int maxLowPriorityTxPerBlock (){
        return Settings.Default.maxFreeTransactionsPerBlock;
    }

    private static Collection<Transaction> filterForBlock_Policy1(Collection<Transaction> transactions)
    {
        int count = 0, count_free = 0;

        List<Transaction> txs = transactions.stream()
                .sorted(comparator1 )
                .collect(Collectors.toList());

        ArrayList<Transaction> filterList = new ArrayList<>(transactions.size());
        for (Transaction tx: txs){
            if (count++ >= Settings.Default.maxTransactionsPerBlock - 1) break;
            if (!tx.isLowPriority() || count_free++ < Settings.Default.maxFreeTransactionsPerBlock){
                filterList.add(tx);
            }
        }
        return filterList;
    }

    private static Comparator<? super Transaction> comparator1 = ((Comparator<Transaction>) (o1, o2) -> {
        // p -> p.getNetworkFee()/p.size()
        return Fixed8.multiply(o1.getNetworkFee(), o2.size())
                .compareTo(Fixed8.multiply(o2.getNetworkFee(), o1.size()));
    }).reversed()
        .thenComparing(p -> p.getNetworkFee()).reversed()
        .thenComparing(p -> inHigherLowPriorityList(p)).reversed();

    private static Collection<Transaction> filterForBlock_Policy2(Collection<Transaction> transactions)
    {
//          TODO 待处理
//        if (!(transactions is IReadOnlyList<Transaction> tx_list))
//        tx_list = transactions.ToArray();

        List<Transaction> free = transactions.stream()
                .filter(Transaction::isLowPriority)
                .sorted(  comparator2 )
                .limit(Settings.Default.maxFreeTransactionsPerBlock)
                .collect(Collectors.toList());

        List<Transaction> non_free = transactions.stream().filter(p -> !p.isLowPriority())
                .sorted(comparator3)
                .limit(Settings.Default.maxTransactionsPerBlock - free.size() - 1)
                .collect(Collectors.toList());

         non_free.addAll(free);
        return non_free;
    }

    private static Comparator<? super Transaction> comparator2 = ((Comparator<Transaction>) (o1, o2) -> {
        // p -> p.getNetworkFee()/p.size()
        return Fixed8.multiply(o1.getNetworkFee(), o2.size())
                .compareTo(Fixed8.multiply(o2.getNetworkFee(), o1.size()));
    }).reversed()
            .thenComparing(p -> p.getNetworkFee()).reversed()
            .thenComparing(p -> inHigherLowPriorityList(p)).reversed()
            .thenComparing(Transaction::hash);

    private static Comparator<? super Transaction> comparator3 = ((Comparator<Transaction>) (o1, o2) -> {
        // p -> p.getNetworkFee()/p.size()
        return Fixed8.multiply(o1.getNetworkFee(), o2.size())
                .compareTo(Fixed8.multiply(o2.getNetworkFee(), o1.size()));
    }).reversed()
            .thenComparing(p -> p.getNetworkFee()).reversed()
            .thenComparing(Transaction::hash);



    @Override
    public  void log(String source, LogLevel level, String message) {
        if ("ConsensusService".equals(source)){
            return;
        }
        String line = String.format("[%s] %s", format.format(new Date()), message);
        print(line);
        if (logDictionary == null || logDictionary.trim().isEmpty()){
            return;
        }
        synchronized (logDictionary) {
                try {
                    File file = new File(logDictionary);
                    if (!file.exists()){
                        file.mkdirs();
                    }
                    String path = logDictionary + "/{now:yyyy-MM-dd}.log";
                    file = new File(path);
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                    writer.append(line);
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
            }
        }
    }

    public static void print(String format, Object... params){
        System.out.println(String.format(format, params));
    }



    @Override
    public void configure() {
        Settings.load(getConfiguration());
    }

    private boolean verifySizeLimits(Transaction tx)
    {
        if (inHigherLowPriorityList(tx)) return true;

        // Not Allow free TX bigger than MaxFreeTransactionSize
        if (tx.isLowPriority() && tx.size() > Settings.Default.maxFreeTransactionSize){
            return false;
        }

        // Require proportional fee for TX bigger than MaxFreeTransactionSize
        if (tx.size() > Settings.Default.maxFreeTransactionSize)
        {
            Fixed8 extraSize = Fixed8.fromDecimal(BigDecimal.valueOf((tx.size() - Settings.Default.maxFreeTransactionSize)));
            Fixed8 fee = Fixed8.multiply(Settings.Default.feePerExtraByte, extraSize);
            if (tx.getNetworkFee().compareTo(fee)<0){
                return false;
            }
        }
        return true;
    }

    public static boolean inHigherLowPriorityList(Transaction tx){
      return Settings.Default.highPriorityTxType.contains(tx.type);
    }



    static class Settings {
        public int maxTransactionsPerBlock;
        public int maxFreeTransactionsPerBlock;
        public int maxFreeTransactionSize;
        public Fixed8 feePerExtraByte;
        public EnumSet<TransactionType> highPriorityTxType;
        public BlockedAccounts blockedAccounts;

        public static Settings Default;

        private Settings(Config section)
        {
            this.maxTransactionsPerBlock = getValueOrDefault(section, "MaxTransactionsPerBlock", 500, p -> Integer.parseInt(p));
            this.maxFreeTransactionsPerBlock = getValueOrDefault(section, "MaxFreeTransactionsPerBlock", 20, p -> Integer.parseInt(p));
            this.maxFreeTransactionSize = getValueOrDefault(section, ("MaxFreeTransactionSize"), 1024, p -> Integer.parseInt(p));
            this.feePerExtraByte = getValueOrDefault(section, "FeePerExtraByte", Fixed8.fromDecimal(BigDecimal.valueOf(0.00001)), p -> Fixed8.parse(p));
            this.blockedAccounts = new BlockedAccounts(section.getConfig("BlockedAccounts"));
            this.highPriorityTxType = null;

            HashSet<TransactionType> set = new HashSet<>();
            for(String priorityType: section.getStringList("HighPriorityTxType")){
                set.add(TransactionType.valueOf(priorityType));
            }
            if (set.isEmpty()){
                set.add(TransactionType.ClaimTransaction);
            }
            this.highPriorityTxType = EnumSet.copyOf(set);
        }

        public<T> T getValueOrDefault(Config section, String filed,  T defaultValue, Function<String, T> selector){
            if(!section.hasPathOrNull(filed)){
                return defaultValue;
            }
            return selector.apply(section.getString(filed));
        }

        public static void load(Config section)
        {
            Default = new Settings(section);
        }
    }

    enum PolicyType implements ByteEnum
    {
        AllowAll((byte) 0x00),
        DenyAll((byte) 0x01),
        AllowList((byte) 0x02),
        DenyList((byte) 0x03);

         PolicyType(byte value){
            this.value = value;
        }

        @Override
        public byte value() {
            return value;
        }

        private byte value;
    }

   static class  BlockedAccounts{
        public PolicyType type;
        public HashSet<UInt160> list;

        public BlockedAccounts(Config section)
        {
            this.type = PolicyType.AllowAll;
            if (section.hasPathOrNull("Type")){
                this.type = PolicyType.valueOf(section.getString("Type"));
            }
            this.list = new HashSet<>();
            if (section.hasPathOrNull("List") ){
                for (String hash: section.getStringList("List")){
                    this.list.add(UInt160.parse(hash));
                    // C# code TODO test
                    //  this.list = new HashSet<UInt160>(section.GetSection("List").GetChildren().Select(p => p.Value.ToScriptHash()));
                }
            }
        }
    }
}
