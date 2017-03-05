package test.com.blootoothtester.network.linklayer;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.util.HashMap;
import java.util.Random;

import test.com.blootoothtester.util.Constants;

/**
 * This class contains information about the current context in which the LinkLayer is running such
 * as messages received so far, session ID, etc.
 */
@SuppressWarnings("WeakerAccess") // TODO: remove once finalised
public class LlContext {

    public interface Callback {
        void transmitPdu(LinkLayerPdu pdu);

        void sendUpperLayer(LinkLayerPdu pdu);
    }

    private final byte mSessionId;
    private final byte mOwnAddr;
    private final byte[] mAckArray;
    private final Callback mCallback;
    @SuppressLint("UseSparseArrays")
    private final HashMap<Byte, LlUser> mUserMap = new HashMap<>();

    private LinkLayerPdu mCurrentPdu;
    private long mLastOwnMessageTime;
    private final AckInterpreter mAckInterpreter;
    private final Handler mRepeatHandler;

    private static final int REPEAT_HANDLER_WHAT_SCHEDULED = 1;
    private static final int REPEAT_HANDLER_WHAT_ABRUPT = 2;


    @SuppressLint("HandlerLeak")
    public LlContext(byte sessionId, int maxUsers, byte ownAddr, Callback callback) {
        mSessionId = sessionId;
        mOwnAddr = ownAddr;
        mCallback = callback;
        mAckArray = new byte[maxUsers];
        // as 0 will end up truncating it
        for (int i = 0; i < mAckArray.length; i++) {
            mAckArray[i] = 1;
        }

        mAckInterpreter = new AckInterpreter(
                new AckInterpreter.Notifier() {
                    @Override
                    public void onMissingCleared(byte missingAddr, byte missingSeqId) {
                        if (mCurrentPdu.getType() == LinkLayerPdu.Type.REPEAT
                                && mCurrentPdu.getFromAddress() == missingAddr
                                && mCurrentPdu.getSequenceId() == missingSeqId) {
                            mRepeatHandler.removeCallbacksAndMessages(null);
                            mRepeatHandler.sendEmptyMessage(REPEAT_HANDLER_WHAT_ABRUPT);
                        }
                    }
                }
        );

        mRepeatHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                HashMap<AckInterpreter.MissingMessage, Integer> counter
                        = mAckInterpreter.getMissingCounter();

                boolean nextRepeatSet = false;

                if (counter.size() != 0) {
                    Random rnd = new Random();
                    int i = rnd.nextInt(counter.keySet().size());

                    AckInterpreter.MissingMessage message = counter.keySet()
                            .toArray(new AckInterpreter.MissingMessage[0])[i];

                    long timeSinceOwnMessage = System.currentTimeMillis() - mLastOwnMessageTime;
                    if (timeSinceOwnMessage < Constants.MIN_OWN_MSG_TIME_MS) {
                        sendEmptyMessageDelayed(REPEAT_HANDLER_WHAT_ABRUPT,
                                Constants.MIN_OWN_MSG_TIME_MS - timeSinceOwnMessage);
                        nextRepeatSet = true;
                    } else {
                        LlMessage llMessage =
                                mUserMap.get(message.getFromId()).getMessageWithSequenceId(
                                        message.getSequenceId());
                        LinkLayerPdu oldPdu = LinkLayerPdu.getMessagePdu(
                                mSessionId,
                                mAckArray,
                                message.getSequenceId(),
                                llMessage.getFromAddress(),
                                llMessage.getToAddress(),
                                llMessage.getData()
                        );
                        sendPduToLowerLayer(
                                LinkLayerPdu.getRepeatPdu(mAckArray, mOwnAddr, oldPdu)
                        );
                    }
                }

                if (msg.what == REPEAT_HANDLER_WHAT_SCHEDULED) {
                    mAckInterpreter.reset();
                }

                if (!nextRepeatSet) {
                    sendEmptyMessageDelayed(REPEAT_HANDLER_WHAT_SCHEDULED,
                            Constants.NACK_CHECK_INTERVAL_MS);
                }
            }
        };

        startRepeatLooper();
    }

    /**
     * updates the LinkLayer context to reflect the receival of the message
     *
     * @param pdu the pdu discovered
     */
    public void receivePdu(LinkLayerPdu pdu) {
        switch (pdu.getType()) {
            case MESSAGE:
            case REPEAT:
                boolean isNewAcceptedPdu = addPdu(pdu);

                if (isNewAcceptedPdu) {
                    sendUpdatedAckArray();
                }

                if (isNewAcceptedPdu && (pdu.getToAddress() == Constants.PDU_BROADCAST_ADDR ||
                        pdu.getToAddress() == mOwnAddr)) {
                    // send message to upper layer
                    mCallback.sendUpperLayer(pdu);
                }
                break;
        }
    }

    private void sendUpdatedAckArray() {
        mCurrentPdu = LinkLayerPdu.getAckChangedPdu(mCurrentPdu, mAckArray);
        mCallback.transmitPdu(mCurrentPdu);
    }

    /**
     * adds a received PDU to the LLContext if the message is not out of order and not a repeat,
     * else ignores the message
     * Also compares the AckArray with
     *
     * @param pdu the pdu to process
     * @return true if this is a new PDU which has been accepted into the context (not necessarily
     * destined for this device though), false otherwise
     */
    public boolean addPdu(LinkLayerPdu pdu) {
        byte fromAddress = pdu.getFromAddress();
        byte toAddress = pdu.getToAddress();

        switch (pdu.getType()) {
            case MESSAGE:
                mAckInterpreter.handle(mAckArray, pdu.getAckArray(), pdu.getFromAddress());
                break;
            case REPEAT:
                mAckInterpreter.handle(mAckArray, pdu.getAckArray(), pdu.getRepeaterAddress());
                break;
        }

        LlUser sendingUser = getUserFor(fromAddress);

        byte sequenceId = pdu.getSequenceId();
        if (sequenceId != getAckValueFor(fromAddress) + 1) {
            // this packet is out of order or redundant, ignore it
            // TODO: Allow for storing of out of order packets too, e.g. based on a window
            return false;
        }

        sendingUser.addMessage(new LlMessage(fromAddress, toAddress, pdu.getSequenceId(),
                pdu.getData()));
        // TODO: This is tightly coupled with the way we allocate addresses (starting from 1)
        setInAckArray(sendingUser.getAddr(), sequenceId);
        return true;
    }

    private void startRepeatLooper() {
        mRepeatHandler.sendEmptyMessageDelayed(REPEAT_HANDLER_WHAT_SCHEDULED,
                Constants.NACK_CHECK_INTERVAL_MS);
    }

    public int getAckValueFor(byte addr) {
        return mAckArray[AckArrayUtils.getIndexFromAddress(addr)];
    }

    public LlUser getUserFor(byte fromAddress) {
        if (!mUserMap.containsKey(fromAddress)) {
            mUserMap.put(fromAddress, new LlUser(fromAddress));
        }

        return mUserMap.get(fromAddress);
    }

    private void setInAckArray(byte addr, byte sequenceId) {
        mAckArray[AckArrayUtils.getIndexFromAddress(addr)] = sequenceId;
    }

    /**
     * This function assumes that the message it generates will be sent immediately
     * and updates its context correspondingly
     * it DOES NOT send the pdu, only returns the pdu to be sent
     *
     * @param toAddr the address of recipient device
     * @param data   the data to be encoded and sent
     */
    public void sendPdu(byte toAddr, byte[] data) {
        byte newSequenceId = (byte) (getAckValueFor(mOwnAddr) + 1);

        setInAckArray(mOwnAddr, newSequenceId);
        getUserFor(mOwnAddr).addMessage(
                new LlMessage(mOwnAddr, toAddr, newSequenceId, data)
        );

        LinkLayerPdu pdu = LinkLayerPdu.getMessagePdu(
                mSessionId,
                mAckArray,
                newSequenceId,
                mOwnAddr,
                toAddr,
                data);

        mLastOwnMessageTime = System.currentTimeMillis();
        sendPduToLowerLayer(pdu);
    }

    private void sendPduToLowerLayer(LinkLayerPdu pdu) {
        mCurrentPdu = pdu;
        mCallback.transmitPdu(pdu);
    }
}
