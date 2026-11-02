package com.winlator.core;

import android.content.Context;
import android.util.Log;

import com.winlator.container.Container;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

public abstract class WineStartMenuCreator {
    private static int parseShowCommand(String value) {
        if (value.equals("SW_SHOWMAXIMIZED")) {
            return MSLink.SW_SHOWMAXIMIZED;
        }
        else if (value.equals("SW_SHOWMINNOACTIVE")) {
            return MSLink.SW_SHOWMINNOACTIVE;
        }
        else return MSLink.SW_SHOWNORMAL;
    }

    private static void createMenuEntry(JSONObject item, File currentDir) throws JSONException {
        if (item.has("children")) {
            currentDir = new File(currentDir, item.getString("name"));
            currentDir.mkdirs();

            JSONArray children = item.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) createMenuEntry(children.getJSONObject(i), currentDir);
        }
        else {
            createLnk(
                currentDir,
                item.getString("name"),
                item.getString("path"),
                item.optString("cmdArgs"),
                item.optString("iconLocation", item.getString("path")),
                item.optInt("iconIndex", 0),
                item.has("showCommand") ? item.getString("showCommand") : null
            );
        }
    }
    public static void createLnk(File currentDir, String name, String targetPath, String cmdArgs, String iconLocation, int iconIndex, String showCommand) {
        File outputFile = new File(currentDir, name+".lnk");
        MSLink.Options options = new MSLink.Options();
        options.targetPath = targetPath;
        options.cmdArgs = cmdArgs != null ? cmdArgs : "";
        options.iconLocation = iconLocation != null ? iconLocation : targetPath;
        options.iconIndex = iconIndex;
        if (showCommand != null) options.showCommand = parseShowCommand(showCommand);
        MSLink.createFile(options, outputFile);
    }
    public static void createLnk(File currentDir, String name, String targetPath, String cmdArgs) {
        createLnk(currentDir, name, targetPath, cmdArgs, null, 0, null);
    }
    public static void createLnk(File currentDir, String name, String targetPath) {
        createLnk(currentDir, name, targetPath, null, null, 0, null);
    }

    private static void removeMenuEntry(JSONObject item, File currentDir) throws JSONException {
        if (item.has("children")) {
            currentDir = new File(currentDir, item.getString("name"));

            JSONArray children = item.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) removeMenuEntry(children.getJSONObject(i), currentDir);

            if (FileUtils.isEmpty(currentDir)) currentDir.delete();
        }
        else (new File(currentDir, item.getString("name")+".lnk")).delete();
    }

    private static void removeOldMenu(File containerStartMenuFile, File startMenuDir) throws JSONException {
        if (!containerStartMenuFile.isFile()) return;
        JSONArray data = new JSONArray(FileUtils.readString(containerStartMenuFile));
        for (int i = 0; i < data.length(); i++) removeMenuEntry(data.getJSONObject(i), startMenuDir);
    }

    private static boolean menuExists(JSONArray items, File dir) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.has("children")) {
                if (!menuExists(item.getJSONArray("children"), new File(dir, item.getString("name")))) return false;
            }
            else if (!new File(dir, item.getString("name")+".lnk").isFile()) return false;
        }
        return true;
    }

    public static void create(Context context, Container container) {
        try {
            File startMenuDir = container.getStartMenuDir();
            File containerStartMenuFile = new File(container.getRootDir(), ".startmenu");
            JSONArray data = new JSONArray(FileUtils.readString(context, "wine_startmenu.json"));
            String json = data.toString();
            // Same menu as last launch and still on disk: nothing to rebuild.
            if (containerStartMenuFile.isFile() && json.equals(FileUtils.readString(containerStartMenuFile))
                    && menuExists(data, startMenuDir)) return;

            removeOldMenu(containerStartMenuFile, startMenuDir);
            FileUtils.writeString(containerStartMenuFile, json);
            for (int i = 0; i < data.length(); i++) createMenuEntry(data.getJSONObject(i), startMenuDir);
        }
        catch (JSONException e) {
            Log.e("WineStartMenuCreator", "Failed to create: " + e);
        }
    }
}
