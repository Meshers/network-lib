package test.com.blootoothtester.network.linklayer.bt;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

import test.com.blootoothtester.bluetooth.MyBluetoothAdapter;
import test.com.blootoothtester.util.Constants;
import test.com.blootoothtester.util.Logger;

public class LinkLayerManager {
    private MyBluetoothAdapter mBluetoothAdapter;
    private BroadcastReceiver mBroadcastReceiver;
    private DeviceDiscoveryHandler mDiscoveryHandler;
    private LlContext mLlContext;
    private Logger mLogger = new Logger();

    public LinkLayerManager(byte ownAddr, final byte sessionId, MyBluetoothAdapter bluetoothAdapter,
                            DeviceDiscoveryHandler discoveryHandler) {
        mBluetoothAdapter = bluetoothAdapter;
        mDiscoveryHandler = discoveryHandler;

        LlContext.Callback callback = new LlContext.Callback() {
            @Override
            public void transmitPdu(LinkLayerPdu pdu) {
                mLogger.d("LinkLayerManager", "sendData: seq.id: " + pdu.getSequenceId());
                mBluetoothAdapter.setName(pdu.getAsString());
            }

            @Override
            public void sendUpperLayer(LlMessage message) {
                mDiscoveryHandler.handleDiscovery(message);
            }
        };

        mLlContext = new LlContext(sessionId, Constants.MAX_USERS, ownAddr, callback);
        mLlContext.sendPdu(Constants.PDU_BROADCAST_ADDR, "init".getBytes(Charset.forName("UTF-8")));

        // register for BT discovery events
        IntentFilter filter = new IntentFilter();

        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        // check started just for debugging purposes
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);

        mBroadcastReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                System.out.println("ACTION:" + intent.getAction());
                String action = intent.getAction();

                // if our discovery has finished, time to start again!
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    startReceiving();
                }

                // If not device discovery intent, ignore it
                if (!BluetoothDevice.ACTION_FOUND.equals(action)) return;
                // Get the BluetoothDevice object from the Intent
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                System.out.println("DEVICE:" + device.getName() + ":" + device.getAddress());

                // if it isn't part of our clique, kick it out
                // TODO: Only LlContext should know about sessionId, refactor to reflect that
                // keeping this here currently as as of now LlContext deals only with LlPDUs
                // also debugging concerns arise out of doing a simple try-catch to filter out
                // non matching PDU's
                if (!LinkLayerPdu.isValidPdu(device.getName(), sessionId)) return;

                LinkLayerPdu pdu = LinkLayerPdu.from(device.getName());

                mLlContext.receivePdu(pdu);
            }
        };

        mBluetoothAdapter.getContext().registerReceiver(mBroadcastReceiver, filter);
    }

    public void cleanUp() {
        mBluetoothAdapter.getContext().unregisterReceiver(mBroadcastReceiver);
    }

    public void startReceiving() {
        // this means we got to start our discovery loop
        mBluetoothAdapter.find();
    }

    private void sendData(byte[] packet, byte toAddr) {
        mLlContext.sendPdu(toAddr, packet);
    }

    public void sendData(String msg, byte toAddr) {
        try {
            sendData(msg.getBytes("UTF-8"), toAddr);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}
