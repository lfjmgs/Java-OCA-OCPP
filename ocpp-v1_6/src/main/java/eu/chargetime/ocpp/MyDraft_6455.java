package eu.chargetime.ocpp;

import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.exceptions.InvalidFrameException;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.framing.Framedata;
import org.java_websocket.protocols.IProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MyDraft_6455 extends Draft_6455 {

    private static final Logger logger = LoggerFactory.getLogger(MyDraft_6455.class);

    public MyDraft_6455() {
        super();
    }

    public MyDraft_6455(IExtension inputExtension) {
        super(inputExtension);
    }

    public MyDraft_6455(List<IExtension> inputExtensions) {
        super(inputExtensions);
    }

    public MyDraft_6455(List<IExtension> inputExtensions, List<IProtocol> inputProtocols) {
        super(inputExtensions, inputProtocols);
    }

    public MyDraft_6455(List<IExtension> inputExtensions, int inputMaxFrameSize) {
        super(inputExtensions, inputMaxFrameSize);
    }

    public MyDraft_6455(List<IExtension> inputExtensions, List<IProtocol> inputProtocols, int inputMaxFrameSize) {
        super(inputExtensions, inputProtocols, inputMaxFrameSize);
    }

    @Override
    public List<Framedata> translateFrame(ByteBuffer buffer) throws InvalidDataException {
        ByteBuffer duplicate = buffer.duplicate();
        try {
            return super.translateFrame(buffer);
        } catch (InvalidDataException e) {
            if (logger.isDebugEnabled()) {
                String hex = byteBufferToHex(duplicate);
                logger.debug("translateFrame-InvalidDataException: {}", hex);
            }
            throw e;
        }
    }

    public static String byteBufferToHex(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    @Override
    public Draft copyInstance() {
        ArrayList<IExtension> newExtensions = new ArrayList<>();
        for (IExtension knownExtension : getKnownExtensions()) {
            newExtensions.add(knownExtension.copyInstance());
        }
        ArrayList<IProtocol> newProtocols = new ArrayList<>();
        for (IProtocol knownProtocol : getKnownProtocols()) {
            newProtocols.add(knownProtocol.copyInstance());
        }
        return new MyDraft_6455(newExtensions, newProtocols, getMaxFrameSize());
    }
}
