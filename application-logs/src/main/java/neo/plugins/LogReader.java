package neo.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.typesafe.config.Config;

import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBFactory;
import org.iq80.leveldb.Options;

import java.io.File;
import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import neo.exception.InvalidOperationException;
import neo.ledger.ApplicationExecutionResult;
import neo.ledger.Blockchain;
import neo.smartcontract.NotifyEventArgs;
import neo.vm.StackItem;

import neo.log.notr.TR;

public class LogReader extends Plugin implements IRpcPlugin {


    private DB db;

    public LogReader() {
        DBFactory factory = new JniDBFactory();
        Options options = new Options();
        options.createIfMissing(true);
        File file = new File(Settings.Default.path);

        try {
            db = factory.open(file, options);
            system.actorSystem.actorOf(Logger.props(system.blockchain, db));
        } catch (IOException e) {
            TR.error(e);
        }
    }


    @Override
    public JsonObject onProcess(HttpServletRequest req, HttpServletResponse res, String method, JsonArray _params) {
        return null;
    }

    @Override
    public String name() {
        TR.enter();
        return TR.exit("ApplicationLogs");
    }

    @Override
    public void configure() {
        Settings.load(getConfiguration());
    }


    public static class Logger extends AbstractActor {


        private DB db;

        public Logger(ActorRef blockchain, DB db) {
            this.db = db;
            blockchain.tell(new Blockchain.Register(), self());
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(Blockchain.ApplicationExecuted.class, applicationExecuted -> handleApplicationExecuted(applicationExecuted))
                    .build();
        }

        private void handleApplicationExecuted(Blockchain.ApplicationExecuted executed) {
            JsonObject json = new JsonObject();
            json.addProperty("txid", executed.transaction.hash().toString());

            JsonArray executionArray = new JsonArray();
            for (ApplicationExecutionResult result : executed.executionResults) {
                JsonObject item = new JsonObject();
                item.addProperty("trigger", result.trigger.name());
                item.addProperty("contract", result.scriptHash.toString());
                item.addProperty("vmstate", result.vmState.getState());
                item.addProperty("gas_consumed", result.gasConsumed.toString());

                try {
                    JsonArray stackItemArray = new JsonArray();
                    for (StackItem stackItem : result.stack) {
                        stackItemArray.add(stackItem.getString());
                    }
                    item.add("stack", stackItemArray);
                } catch (InvalidOperationException e) {
                    item.addProperty("stack", "error: recursive reference");
                }

                JsonArray notifyArray = new JsonArray();
                for (NotifyEventArgs notify : result.notifications) {
                    JsonObject notifyJson = new JsonObject();
                    notifyJson.addProperty("contract", notify.scriptHash.toString());

                    try {
                        notifyJson.addProperty("state", notify.state.getString());
                    } catch (Exception e) {
                        notifyJson.addProperty("state", "error: recursive reference");
                    }
                    notifyArray.add(notifyJson);
                }

                item.add("notifications", notifyArray);
            }
            db.put(executed.transaction.hash().toArray(), json.toString().getBytes());
        }

        public static Props props(ActorRef blockchain, DB db) {
            return Props.create(Logger.class, blockchain, db);
        }
    }

    public static class Settings {
        public String path;

        public static Settings Default;

        private Settings(Config config) {
            this.path = config.getString("Path");
        }

        public static void load(Config config) {
            Default = new Settings(config);
        }
    }
}
