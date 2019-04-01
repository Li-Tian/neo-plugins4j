package neo.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.typesafe.config.Config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import neo.exception.RpcException;

public class RpcSecurity extends Plugin implements IRpcPlugin {


    public void PreProcess(HttpServletRequest request, HttpServletResponse response, String method, JsonArray param) {
        if (!checkAuth(request, response) || Settings.Default.disabledMethodsContain(method))
            throw new RpcException(-400, "Access denied");
    }

    @Override
    public JsonObject onProcess(HttpServletRequest req, HttpServletResponse response, String method, JsonArray _params) {
        return null;
    }

    public void PostProcess(HttpServletRequest request, HttpServletResponse response, String method, JsonArray param, JsonObject result) {
    }

    private boolean checkAuth(HttpServletRequest request, HttpServletResponse response) {
        if (Settings.Default.rpcUser == null || Settings.Default.rpcUser.trim().isEmpty()) {
            return true;
        }

        response.addHeader("WWW-Authenticate", "Basic realm=\"Restricted\"");

        String reqauth = request.getHeader("Authorization");
        String authstring = null;
        try {
            byte[] results = Base64.getDecoder().decode((reqauth.replace("Basic ", "").trim()));
            authstring = new String(results, "UTF-8");
            // C# code: authstring = Encoding.UTF8.GetString(Convert.FromBase64String(reqauth.replace("Basic ", "").trim()));
        } catch (Exception e) {
            return false;
        }

        String[] items = authstring.split(":");
        ArrayList<String> list = new ArrayList<>(items.length);
        for (String item : items) {
            if (item != null && !item.trim().isEmpty()) {
                list.add(item);
            }
        }
        if (list.size() < 2) {
            return false;
        }
        return list.get(0).equals(Settings.Default.rpcPass) && list.get(1).equals(Settings.Default.rpcPass);
        // C# code
        //        String[] authvalues = authstring.split(new String[]{":"}, StringSplitOptions.RemoveEmptyEntries);
        //        if (authvalues.length < 2)
        //            return false;
        //        return authvalues[0] == Settings.Default.rpcUser && authvalues[1] == Settings.Default.rpcPass;
    }


    @Override
    public void configure() {
        Settings.load(getConfiguration());
    }

    static class Settings {
        public String rpcUser;
        public String rpcPass;
        public String[] disabledMethods;

        public static Settings Default;

        private Settings(Config config) {
            this.rpcUser = config.getString("RpcUser");
            this.rpcPass = config.getString("RpcPass");
            List<String> lists = config.getStringList("DisabledMethods");
            this.disabledMethods = lists.toArray(new String[lists.size()]);
        }

        public boolean disabledMethodsContain(String method) {
            for (int i = 0; i < disabledMethods.length; i++) {
                if (disabledMethods[i].equals(method)) {
                    return true;
                }
            }
            return false;
        }

        public static void load(Config config) {
            Default = new Settings(config);
        }
    }

}
