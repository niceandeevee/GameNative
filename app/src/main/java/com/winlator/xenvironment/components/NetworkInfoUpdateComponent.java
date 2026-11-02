package com.winlator.xenvironment.components;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.NetworkHelper;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void start() {
        Log.d("NetworkInfoUpdateComponent", "Starting...");
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);
        updateIFAddrsFile(networkHelper.getIFAddresses());
        updateEtcHostsFile(networkHelper.getIPv4Address());
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            private void refresh() {
                updateIFAddrsFile(networkHelper.getIFAddresses());
                updateEtcHostsFile(networkHelper.getIPv4Address());
            }

            @Override
            public void onAvailable(Network network) { refresh(); }

            @Override
            public void onLost(Network network) { refresh(); }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) { refresh(); }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.e("NetworkInfoUpdateComponent", "Failed to register network callback: " + e);
            networkCallback = null;
        }
    }

    @Override
    public void stop() {
        Log.d("NetworkInfoUpdateComponent", "Stopping...");
        if (networkCallback != null) {
            try {
                ConnectivityManager connectivityManager = environment.getContext().getSystemService(ConnectivityManager.class);
                if (connectivityManager != null) connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e("NetworkInfoUpdateComponent", "Failed to unregister network callback: " + e);
            }
            networkCallback = null;
        }
    }

    private void updateAdapterInfoFile(int ipAddress, int netmask, int gateway) {
        File file = new File(environment.getImageFs().getTmpDir(), "adapterinfo");
        FileUtils.writeString(file, "Android Wi-Fi Adapter,"+NetworkHelper.formatIpAddress(ipAddress)+","+NetworkHelper.formatNetmask(netmask)+","+NetworkHelper.formatIpAddress(gateway));
    }

    public void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getImageFs().getTmpDir(), "ifaddrs");
        String content = "";
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                StringBuilder sb = new StringBuilder();
                sb.append(content);
                sb.append(!content.isEmpty() ? "\n" : "");
                sb.append(ifAddress.toString());
                content = sb.toString();
            }
        } else {
            content = new NetworkHelper.IFAddress().toString();
        }
        FileUtils.writeString(file, content);
    }

    public void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getImageFs().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip + "\tlocalhost\n");
    }
}
