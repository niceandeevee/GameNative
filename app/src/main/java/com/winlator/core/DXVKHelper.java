package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class DXVKHelper {
    private static final String TAG = "DXVKHelper";
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", "/data/data/app.gamenative/files/imagefs"+ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File dxvkConfigFile = new File(imageFs.config_path+"/dxvk.conf");

        // DXVK_CONFIG is split on ';' by DXVK and read raw from the environment (execve, no shell),
        // so entries must be ';'-separated with no surrounding quotes. The old "\"...\n...\"" form
        // made DXVK see a key starting with '"' and silently drop every option.
        StringBuilder content = new StringBuilder();
        // Keep any DXVK_CONFIG the user set in the container env vars instead of overwriting it.
        String userConfig = envVars.get("DXVK_CONFIG").trim();
        if (userConfig.length() >= 2 && userConfig.startsWith("\"") && userConfig.endsWith("\""))
            userConfig = userConfig.substring(1, userConfig.length() - 1);
        for (String option : userConfig.split("[;\n]")) {
            if (!option.trim().isEmpty()) appendOption(content, option.trim());
        }
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            appendOption(content, "dxgi.maxDeviceMemory = " + maxDeviceMemory);
            appendOption(content, "dxgi.maxSharedMemory = " + maxDeviceMemory);
            // dxgi.* only covers D3D10/11; D3D9 titles read their reported VRAM from this one.
            appendOption(content, "d3d9.maxAvailableMemory = " + maxDeviceMemory);
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            appendOption(content, "d3d11.maxFeatureLevel = " + maxFeatureLevel);
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }
        String customDevice = config.get("customDevice");
        String[] parts = customDevice.split(":", 3);
        if (parts.length == 3) {
            String desc = parts[2].replace("\"", "").replace(";", "");
            appendOption(content, "dxgi.customDeviceId = " + parts[0]);
            appendOption(content, "dxgi.customVendorId = " + parts[1]);
            appendOption(content, "d3d9.customDeviceId = " + parts[0]);
            appendOption(content, "d3d9.customVendorId = " + parts[1]);
            appendOption(content, "dxgi.customDeviceDesc = \"" + desc + "\"");
            appendOption(content, "d3d9.customDeviceDesc = \"" + desc + "\"");
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            appendOption(content, "d3d11.constantBufferRangeCheck = True");
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");


        // DXVK 1.x never reads DXVK_CONFIG (added in 2.1): it only reads DXVK_CONFIG_FILE, so the same
        // options also go to that file. DXVK is a Windows DLL and opens the path through Wine, which
        // resolves a bare "/data/..." against the current drive (C:), so the path goes through z: (/).
        // Without this, Black Ops on async-1.10.3 saw ~4 GB of texture memory instead of the 1 GB cap,
        // loaded full-resolution textures and ran its 32-bit address space out in the intro movie.
        writeConfigFile(dxvkConfigFile, content.toString());
        envVars.put("DXVK_CONFIG_FILE", toWinePath(dxvkConfigFile));
        if (content.length() > 0) envVars.put("DXVK_CONFIG", content.toString());
        else envVars.remove("DXVK_CONFIG");
    }

    /** dxvk.conf form of the ';'-separated DXVK_CONFIG options: one "key = value" per line. */
    static String toConfigFileContent(String options) {
        StringBuilder file = new StringBuilder();
        for (String option : options.split(";")) {
            if (!option.trim().isEmpty()) file.append(option.trim()).append('\n');
        }
        return file.toString();
    }

    /** Path of a Unix file as Wine programs open it, through the z: drive that maps to /. */
    static String toWinePath(File file) {
        return "Z:" + file.getAbsolutePath();
    }

    /**
     * Writes this launch's options, or deletes the file when there are none so a cap set for one game
     * doesn't carry over to the next (the imagefs config dir is shared by every container).
     */
    static void writeConfigFile(File file, String options) {
        String text = toConfigFileContent(options);
        try {
            if (text.isEmpty()) {
                if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file);
                return;
            }
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
            try (java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(text);
            }
        } catch (java.io.IOException | SecurityException e) {
            Log.w(TAG, "Could not write " + file + ": " + e);
        }
    }

    private static void appendOption(StringBuilder content, String option) {
        if (content.length() > 0) content.append("; ");
        content.append(option);
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
    }
}
