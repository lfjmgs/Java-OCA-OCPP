package eu.chargetime.ocpp;
/*
ChargeTime.eu - Java-OCA-OCPP
Copyright (C) 2015-2016 Thomas Volden <tv@chargetime.eu>
Copyright (C) 2022 Emil Melar <emil@iconsultable.no>

MIT License

Copyright (C) 2016-2018 Thomas Volden

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

import eu.chargetime.ocpp.model.*;
import eu.chargetime.ocpp.utilities.SugarUtil;
import java.util.ArrayDeque;
import java.util.UUID;
import javax.xml.soap.SOAPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

/**
 * Abstract class. Handles basic communication: Pack and send messages. Receive and unpack messages.
 *
 * <p>Requires a {@link Transmitter} to send and receive messages. Must be overloaded to implement a
 * specific format.
 */
public abstract class Communicator {
  private static final Logger logger = LoggerFactory.getLogger(Communicator.class);

  private static MessageListener messageListener;

  private RetryRunner retryRunner;
  protected Radio radio;
  private ArrayDeque<Object> transactionQueue;
  private CommunicatorEvents events;
  private boolean failedFlag;
  private UUID sessionId;

  public static void setMessageListener(MessageListener messageListener) {
    Communicator.messageListener = messageListener;
  }

  /**
   * Convert a formatted string into a {@link Request}/{@link Confirmation}. This is useful for call
   * results, where the confirmation type isn't given.
   *
   * @param payload the raw formatted payload.
   * @param type the expected return type.
   * @return the unpacked payload.
   * @throws Exception error occurred while converting.
   */
  public abstract <T> T unpackPayload(Object payload, Class<T> type) throws Exception;

  /**
   * Convert a {@link Request}/{@link Confirmation} into a formatted string.
   *
   * @param payload the payload model.
   * @return the payload in the form of a formatted string.
   */
  public abstract Object packPayload(Object payload);

  /**
   * Create a call result envelope to transmit.
   *
   * @param uniqueId the id the receiver expects.
   * @param action action name of the feature.
   * @param payload packed payload.
   * @return a fully packed message ready to send.
   */
  protected abstract Object makeCallResult(String uniqueId, String action, Object payload);

  /**
   * Create a call envelope to transmit to the server.
   *
   * @param uniqueId the id the receiver must reply with.
   * @param action action name of the feature.
   * @param payload packed payload.
   * @return a fully packed message ready to send.
   */
  protected abstract Object makeCall(String uniqueId, String action, Object payload);

  /**
   * Create a call error envelope to transmit.
   *
   * @param uniqueId the id the receiver expects.
   * @param errorCode an OCPP error code.
   * @param errorDescription an associated error description.
   * @return a fully packed message ready to send.
   */
  protected abstract Object makeCallError(
      String uniqueId, String action, String errorCode, String errorDescription);

  /**
   * Identify an incoming call and parse it into one of the following: {@link CallMessage} a
   * request. {@link CallResultMessage} a response.
   *
   * @param message the raw message
   * @return CallMessage or {@link CallResultMessage}
   */
  protected abstract Message parse(Object message);

  /**
   * Handle required injections.
   *
   * @param transmitter Injected {@link Transmitter}
   */
  public Communicator(Radio transmitter) {
    this(transmitter, true);
  }

  /**
   * Handle required injections.
   *
   * @param transmitter Injected {@link Transmitter}
   * @param enableTransactionQueue flag to enable/disable the transaction queue and associated
   *     processing
   */
  public Communicator(Radio transmitter, boolean enableTransactionQueue) {
    this.radio = transmitter;
    this.transactionQueue = enableTransactionQueue ? new ArrayDeque<>() : null;
    this.retryRunner = enableTransactionQueue ? new RetryRunner() : null;
    this.failedFlag = false;
  }

  /**
   * Use the injected {@link Transmitter} to connect to server.
   *
   * @param uri the url and port of the server.
   * @param events handler for call back events.
   */
  public void connect(String uri, CommunicatorEvents events) {
    this.events = events;
    if (radio instanceof Transmitter) ((Transmitter) radio).connect(uri, new EventHandler(events));
  }

  /**
   * Use the injected {@link Transmitter} to accept a client.
   *
   * @param events handler for call back events.
   */
  public void accept(CommunicatorEvents events) {
    this.events = events;
    if (radio instanceof Receiver) ((Receiver) radio).accept(new EventHandler(events));
  }

  /**
   * Send a new {@link Request}. Stores transaction-related {@link Request}s if offline. New
   * transaction-related {@link Request}s will be placed behind the queue of stored {@link
   * Request}s.
   *
   * @param uniqueId the id the receiver should use to reply.
   * @param action action name of the {@link eu.chargetime.ocpp.feature.Feature}.
   * @param request the outgoing {@link Request}
   */
  public synchronized void sendCall(String uniqueId, String action, Request request) {
    Object call = makeCall(uniqueId, action, packPayload(request));

    if (call != null) {
      if (call instanceof SOAPMessage) {
        logger.trace("Send a message: {}", SugarUtil.soapMessageToString((SOAPMessage) call));
      } else {
        logger.trace("Send a message: {}", call);
      }
    }

    try {
      if (radio.isClosed()) {
        if (request.transactionRelated() && transactionQueue != null) {
          logger.warn("Not connected: storing request to queue: {}", request);
          transactionQueue.add(call);
        } else {
          logger.warn("Not connected: can't send request: {}", request);
          events.onError(
              uniqueId,
              "Not connected",
              "The request can't be sent due to the lack of connection",
              request);
        }
      } else if (request.transactionRelated()
          && transactionQueue != null
          && transactionQueue.size() > 0) {
        transactionQueue.add(call);
        processTransactionQueue();
      } else {
        radio.send(call);
        onSendMessage(sessionId, call, parse(call));
      }
    } catch (NotConnectedException ex) {
      logger.warn("sendCall() failed: not connected");
      if (request.transactionRelated() && transactionQueue != null) {
        transactionQueue.add(call);
      } else {
        events.onError(
            uniqueId,
            "Not connected",
            "The request can't be sent due to the lack of connection",
            request);
      }
    }
  }

  /**
   * Send a {@link Confirmation} reply to a {@link Request}.
   *
   * @param uniqueId the id the receiver expects.
   * @param confirmation the outgoing {@link Confirmation}
   */
  public void sendCallResult(String uniqueId, String action, Confirmation confirmation) {
    try {
      Object message = makeCallResult(uniqueId, action, packPayload(confirmation));
      radio.send(message);
      onSendMessage(sessionId, message, parse(message));

      ConfirmationCompletedHandler completedHandler = confirmation.getCompletedHandler();

      if (completedHandler != null) {
        try {
          completedHandler.onConfirmationCompleted();
        } catch (Throwable e) {
          events.onError(
              uniqueId,
              "ConfirmationCompletedHandlerFailed",
              "The confirmation completed callback handler failed with exception " + e.toString(),
              confirmation);
        }
      }
    } catch (NotConnectedException ex) {
      logger.warn("sendCallResult() failed", ex);
      events.onError(
          uniqueId,
          "Not connected",
          "The confirmation couldn't be send due to the lack of connection",
          confirmation);
    }
  }

  /**
   * Send an error. If offline, the message is thrown away.
   *
   * @param uniqueId the id the receiver expects a response to.
   * @param errorCode an OCPP error Code
   * @param errorDescription a associated error description.
   */
  public void sendCallError(
      String uniqueId, String action, String errorCode, String errorDescription) {
    logger.error(
        "An error occurred. Sending this information: uniqueId {}: action: {}, errorCore: {}, errorDescription: {}",
        uniqueId,
        action,
        errorCode,
        errorDescription);
    try {
      Object message = makeCallError(uniqueId, action, errorCode, errorDescription);
      radio.send(message);
      onSendMessage(sessionId, message, parse(message));
    } catch (NotConnectedException ex) {
      logger.warn("sendCallError() failed", ex);
      events.onError(
          uniqueId,
          "Not connected",
          "The error couldn't be send due to the lack of connection",
          errorCode);
    }
  }

  /** Close down the connection. Uses the {@link Transmitter}. */
  public void disconnect() {
    radio.disconnect();
  }

  private synchronized void processTransactionQueue() {
    if (retryRunner != null && !retryRunner.isAlive()) {
      if (retryRunner.getState() != Thread.State.NEW) {
        retryRunner = new RetryRunner();
      }
      retryRunner.start();
    }
  }

  public void setSessionId(UUID sessionId) {
    this.sessionId = sessionId;
  }

  private class EventHandler implements RadioEvents {
    private final CommunicatorEvents events;

    public EventHandler(CommunicatorEvents events) {
      this.events = events;
    }

    @Override
    public void connected() {
      events.onConnected();
      processTransactionQueue();
    }

    @Override
    public void receivedMessage(Object input) {
      Message message = parse(input);
      onReceivedMessage(sessionId, input, message);
      if (message != null) {
        Object payload = message.getPayload();
        if (payload instanceof Document) {
          logger.trace("Receive a message: {}", SugarUtil.docToString((Document) payload));
        } else {
          logger.trace("Receive a message: {}", message);
        }
      }
      if (message instanceof CallResultMessage) {
        events.onCallResult(message.getId(), message.getAction(), message.getPayload());
      } else if (message instanceof CallErrorMessage) {
        failedFlag = true;
        CallErrorMessage call = (CallErrorMessage) message;
        events.onError(
            call.getId(), call.getErrorCode(), call.getErrorDescription(), call.getRawPayload());
      } else if (message instanceof CallMessage) {
        CallMessage call = (CallMessage) message;
        events.onCall(call.getId(), call.getAction(), call.getPayload());
      }
    }

    @Override
    public void disconnected() {
      events.onDisconnected();
    }
  }

  /**
   * Get queued transaction related request.
   *
   * @return request or null if queue is empty.
   */
  private Object getRetryMessage() {
    Object result = null;
    if (transactionQueue != null && !transactionQueue.isEmpty()) result = transactionQueue.peek();
    return result;
  }

  /**
   * Check if a error message was received.
   *
   * @return whether a fail flag has been raised.
   */
  private boolean hasFailed() {
    return failedFlag;
  }

  private void popRetryMessage() {
    if (transactionQueue != null && !transactionQueue.isEmpty()) transactionQueue.pop();
  }

  /** Will resend transaction related requests. */
  private class RetryRunner extends Thread {
    private static final long DELAY_IN_MILLISECONDS = 1000;

    @Override
    public void run() {
      Object call;
      try {
        while ((call = getRetryMessage()) != null) {
          failedFlag = false;
          radio.send(call);
          onSendMessage(sessionId, call, parse(call));
          Thread.sleep(DELAY_IN_MILLISECONDS);
          if (!hasFailed()) popRetryMessage();
        }
      } catch (Exception ex) {
        logger.warn("RetryRunner::run() failed", ex);
      }
    }
  }

  private static void onReceivedMessage(UUID sessionId, Object original, Message message) {
    if (Communicator.messageListener != null) {
      Communicator.messageListener.onReceivedMessage(sessionId, original, message);
    }
  }

  private static void onSendMessage(UUID sessionId, Object original, Message message) {
    if (Communicator.messageListener != null) {
      Communicator.messageListener.onSendMessage(sessionId, original, message);
    }
  }

  public interface MessageListener {
    void onReceivedMessage(UUID sessionId, Object original, Message message);
    void onSendMessage(UUID sessionId, Object original, Message message);
  }
}
