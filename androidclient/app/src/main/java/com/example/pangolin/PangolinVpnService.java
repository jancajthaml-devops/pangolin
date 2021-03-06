package com.example.pangolin;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class PangolinVpnService extends VpnService {
    final static String ACTION_DISCONNECT = "disconnect";
    final static String ACTION_CONNECT = "connect";
    final static int MAX_PACKET_SIZE = 65536;
    static String serverIP, localIP;
    static int localPrefixLength = 24;
    static int serverPort;
    static String dns;
    Thread sendThread,recvThread;
    Thread sendrecvThread;
    ParcelFileDescriptor localTunnel;
    private PendingIntent pendingIntent;

    public PangolinVpnService() {
    }

    @Override
    public void onCreate(){
        pendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_CANCEL_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("onStartCommand", "start: " + intent.getAction());
        try {
            if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
                disconnect();
                return START_NOT_STICKY;
            } else {
                Bundle ex = intent.getExtras();
                serverIP = ex.getString("serverIP");
                serverPort = ex.getInt("serverPort");
                String[] localAddrs = ex.getString("localIP").split("/");
                if(localAddrs.length>=1){
                    localIP = localAddrs[0];
                }
                if(localAddrs.length>=2){
                    localPrefixLength = Integer.parseInt(localAddrs[1]);
                }

                dns = ex.getString("dns");

                Notification.Builder builder = new Notification.Builder(this);
                builder.setContentIntent(pendingIntent)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Pangolin")
                        .setContentText("<Server>" + serverIP + ":" + serverPort)
                        .setWhen(System.currentTimeMillis());
                Notification notification = builder.build();

                //startForeground(1, new Notification(R.mipmap.ic_launcher, "Pangolin", System.currentTimeMillis()));
                startForeground(1, notification);
                connect();
            }
        }catch (Exception e){
            Log.e("onStartCommmand", e.toString());
        }
        return START_STICKY;
    }

    public static ByteBuffer compress(ByteBuffer bf) {
        try {
            bf.rewind();
            byte[] bs = new byte[bf.remaining()];
            bf.get(bs, 0, bs.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(bs);
            gzip.flush();
            gzip.close();
            ByteBuffer res = ByteBuffer.allocate(MAX_PACKET_SIZE);
            byte[] outbs = out.toByteArray();
            res.put(outbs);
            res.limit(outbs.length);
            res.rewind();
            return res;

        }catch(Exception e){
            Log.e("Compress", e.toString());
        }
        return null;
    }

    public static ByteBuffer uncompress(ByteBuffer bf){
        try {
            bf.rewind();
            byte[] bs = new byte[bf.remaining()];
            bf.get(bs, 0, bs.length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayInputStream in = new ByteArrayInputStream(bs);
            GZIPInputStream gunzip = new GZIPInputStream(in);
            byte[] buffer = new byte[256];
            int n;
            while ((n = gunzip.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            ByteBuffer res = ByteBuffer.allocate(MAX_PACKET_SIZE);
            byte[] outbs = out.toByteArray();
            res.put(outbs);
            res.limit(outbs.length);
            res.rewind();
            return res;

        }catch(Exception e){
            Log.e("uncompress: ", e.toString());

        }
        return null;
    }

    private void disconnect(){
        Log.i("disconnect", "disconnecting...");
        try {
            if (sendrecvThread!=null) {
                sendrecvThread.interrupt();
                sendrecvThread = null;
            }
            if (localTunnel != null) {
                localTunnel.close();
                localTunnel = null;
            }
            stopForeground(true);
        }catch(Exception e){
            Log.e("disconnect", e.toString());
        }

    }

    private void connect(){
        Log.i("connect", "connecting...");
        Log.i("vpn", serverIP + " " + serverPort + " " + localIP + " " + dns);
        try {
            if(sendrecvThread!=null) sendrecvThread.interrupt();
            sendrecvThread = new Thread() {
                @Override
                public void run() {
                    try {
                        final DatagramChannel udp = DatagramChannel.open();
                        SocketAddress serverAdd = new InetSocketAddress(serverIP, serverPort);
                        udp.connect(serverAdd);
                        udp.configureBlocking(false);
                        PangolinVpnService.this.protect(udp.socket());

                        VpnService.Builder builder = PangolinVpnService.this.new Builder();
                        builder.setMtu(1500)
                                .addAddress(localIP, localPrefixLength)
                                .addRoute("0.0.0.0", 0)
                                .addDnsServer(dns)
                                .setSession("Pangolin")
                                .setConfigureIntent(null);
                        localTunnel = builder.establish();


                        FileInputStream in = new FileInputStream(localTunnel.getFileDescriptor());
                        FileOutputStream out = new FileOutputStream(localTunnel.getFileDescriptor());

                        ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

                        while(true){
                            try {
                                int ln = in.read(packet.array());

                                if (ln > 0) {
                                    //Log.i("========send", "===========" + ln);
                                    packet.limit(ln);
                                    udp.write(compress(packet));
                                }
                                packet.clear();

                                ln = udp.read(packet);

                                if (ln > 0) {
                                    //Log.i("========recv", "===========" + ln);
                                    packet.limit(ln);
                                    ByteBuffer unpacket = uncompress(packet);
                                    byte[] bs = new byte[unpacket.remaining()];
                                    unpacket.rewind();
                                    unpacket.get(bs, 0, bs.length);
                                    out.write(bs);
                                }
                                packet.clear();

                            }catch(Exception e){
                                Log.e("send/rec", e.toString());
                            }
                        }

                    }catch(Exception e){
                        Log.e("send/recv", e.toString());
                    }
                }

            };

            sendrecvThread.start();


        }catch(Exception e){
            Log.e("vpn", e.toString());
        }
    }


}
