package com.termux.installer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Iterator;

public class BootstrapSource implements Serializable {

    public final String name;
    public final String urlTemplate;
    public final String sha256;
    public final String variant;

    public BootstrapSource(String name, String urlTemplate,
                           String sha256, String variant) {
        this.name = name;
        this.urlTemplate = urlTemplate;
        this.sha256 = sha256;
        this.variant = variant;
    }

    public String resolveUrl(String arch) {
        return urlTemplate.replace("{arch}", arch);
    }

    public static BootstrapSource fromJson(JSONObject obj, String deviceArch) throws JSONException {
        String urlTemplate = obj.getString("urlTemplate");

        JSONObject sha256ByArch = obj.optJSONObject("sha256ByArch");
        String sha256 = null;
        if (sha256ByArch != null) {
            if (sha256ByArch.has(deviceArch)) {
                sha256 = sha256ByArch.getString(deviceArch);
            } else {
                // fallback: take first available
                Iterator<String> keys = sha256ByArch.keys();
                if (keys.hasNext()) {
                    sha256 = sha256ByArch.getString(keys.next());
                }
            }
        }

        return new BootstrapSource(
            obj.optString("name", obj.getString("id")),
            urlTemplate,
            sha256,
            obj.optString("variant", obj.getString("id"))
        );
    }
}
