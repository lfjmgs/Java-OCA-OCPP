package eu.chargetime.ocpp;
/*
   ChargeTime.eu - Java-OCA-OCPP

   MIT License

   Copyright (C) 2016-2018 Thomas Volden <tv@chargetime.eu>

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

import eu.chargetime.ocpp.model.DisconnectionInformation;
import eu.chargetime.ocpp.model.SessionInformation;
import eu.chargetime.ocpp.wss.WssFactoryBuilder;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketListener implements Listener {
  private static final Logger logger = LoggerFactory.getLogger(WebSocketListener.class);

  private static final int DEFAULT_WEBSOCKET_WORKER_COUNT = 4;
  private static final int TIMEOUT_IN_MILLIS = 10000;

  private static final int OCPPJ_CP_MIN_PASSWORD_LENGTH = 16;
  private static final int OCPPJ_CP_MAX_PASSWORD_LENGTH = 40;

  private static final String HTTP_HEADER_PROXIED_ADDRESS = "X-Forwarded-For";

  private final ISessionFactory sessionFactory;
  private final List<Draft> drafts;

  private final JSONConfiguration configuration;
  private volatile WebSocketServer server;
  private WssFactoryBuilder wssFactoryBuilder;
  private final Map<WebSocket, WebSocketReceiver> sockets;
  private final Map<UUID, DisconnectionInformation> disconnectionInformations;
  private final Map<WebSocket, UUID> sessionIds;
  private volatile boolean closed = true;
  private boolean handleRequestAsync;

  public WebSocketListener(
      ISessionFactory sessionFactory, JSONConfiguration configuration, Draft... drafts) {
    this.sessionFactory = sessionFactory;
    this.configuration = configuration;
    this.drafts = Arrays.asList(drafts);
    this.sockets = new ConcurrentHashMap<>();
    this.disconnectionInformations = new ConcurrentHashMap<>();
    this.sessionIds = new ConcurrentHashMap<>();
  }

  public WebSocketListener(ISessionFactory sessionFactory, Draft... drafts) {
    this(sessionFactory, JSONConfiguration.get(), drafts);
  }

  @Override
  public void open(String hostname, int port, ListenerEvents handler) {
    server =
        new WebSocketServer(
            new InetSocketAddress(hostname, port),
            configuration.getParameter(
                JSONConfiguration.WEBSOCKET_WORKER_COUNT, DEFAULT_WEBSOCKET_WORKER_COUNT),
            drafts) {
          @Override
          public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
            if(Draft_HttpHealthCheck.isHttp(clientHandshake)){
              logger.debug("On HTTP Request, for heathcheck");
              webSocket.close(Draft_HttpHealthCheck.HTTP_HEALTH_CHECK_CLOSE_CODE);
              return;
            }
            logger.debug(
                "On connection open (resource descriptor: {})",
                clientHandshake.getResourceDescriptor());

            WebSocketReceiver receiver =
                new WebSocketReceiver(
                    new WebSocketReceiverEvents() {
                      @Override
                      public boolean isClosed() {
                        return closed;
                      }

                      @Override
                      public void close() {
                        webSocket.close();
                      }

                      @Override
                      public void relay(String message) {
                        webSocket.send(message);
                      }
                    });

            sockets.put(webSocket, receiver);

            String proxiedAddress = clientHandshake.getFieldValue(HTTP_HEADER_PROXIED_ADDRESS);

            logger.debug(
                "New web-socket connection opened from address: {} proxied for: {}",
                webSocket.getRemoteSocketAddress(),
                proxiedAddress);

            SessionInformation information =
                new SessionInformation.Builder()
                    .Identifier(clientHandshake.getResourceDescriptor())
                    .InternetAddress(webSocket.getRemoteSocketAddress())
                    .ProxiedAddress(proxiedAddress)
                    .build();

            ISession session = sessionFactory.createSession(new JSONCommunicator(receiver));
            sessionIds.put(webSocket, session.getSessionId());
            handler.newSession(session, information);
          }

          @Override
          public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
              WebSocket webSocket, Draft draft, ClientHandshake clientHandshake)
              throws InvalidDataException {
            SessionInformation information =
                new SessionInformation.Builder()
                    .Identifier(clientHandshake.getResourceDescriptor())
                    .InternetAddress(webSocket.getRemoteSocketAddress())
                    .build();

            String username = null;
            String password = null;
            if (clientHandshake.hasFieldValue("Authorization")) {
              String authorization = clientHandshake.getFieldValue("Authorization");
              if (authorization != null && authorization.toLowerCase().startsWith("basic")) {
                // Authorization: Basic base64credentials
                String base64Credentials = authorization.substring("Basic".length()).trim();
                byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
                // split credentials on username and password
                for (int i = 0; i < credDecoded.length; i++) {
                  if (credDecoded[i] == ':') {
                    username =
                        new String(Arrays.copyOfRange(credDecoded, 0, i), StandardCharsets.UTF_8);
                    if (i + 1 < credDecoded.length) {
                      password = new String(Arrays.copyOfRange(credDecoded, i + 1, credDecoded.length));
                    }
                    break;
                  }
                }
              }
              if (password == null
                  || password.length() < configuration.getParameter(JSONConfiguration.OCPPJ_CP_MIN_PASSWORD_LENGTH, OCPPJ_CP_MIN_PASSWORD_LENGTH)
                  || password.length() > configuration.getParameter(JSONConfiguration.OCPPJ_CP_MAX_PASSWORD_LENGTH, OCPPJ_CP_MAX_PASSWORD_LENGTH))
                throw new InvalidDataException(401, "Invalid password length");
            }

            try {
              handler.authenticateSession(information, username, password);
            } catch (AuthenticationException e) {
              throw new InvalidDataException(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
              throw new InvalidDataException(401, e.getMessage());
            }
            return super.onWebsocketHandshakeReceivedAsServer(webSocket, draft, clientHandshake);
          }

          @Override
          public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
            logger.info(
                "On connection close (resource descriptor: {}, code: {}, reason: {}, remote: {})",
                webSocket.getResourceDescriptor(),
                code,
                reason,
                remote);

            if(code == Draft_HttpHealthCheck.HTTP_HEALTH_CHECK_CLOSE_CODE)
              return;

            WebSocketReceiver receiver = sockets.get(webSocket);
            if (receiver != null) {
              UUID sessionId = sessionIds.get(webSocket);
              if (sessionId != null) {
                disconnectionInformations.put(sessionId, new DisconnectionInformation(code, remote, reason));
              }
              receiver.disconnect();
              sockets.remove(webSocket);
            } else {
              logger.debug("Receiver for socket not found: {}", webSocket);
            }
          }

          @Override
          public void onMessage(WebSocket webSocket, String message) {
            sockets.get(webSocket).relay(message);
          }

          @Override
          public void onError(WebSocket webSocket, Exception ex) {
            String resourceDescriptor =
                (webSocket != null)
                    ? webSocket.getResourceDescriptor()
                    : "not defined (webSocket is null)";

            if (ex instanceof ConnectException) {
              logger.error(
                  "On error (resource descriptor: " + resourceDescriptor + ") triggered caused by:",
                  ex);
            } else {
              logger.error(
                  "On error (resource descriptor: " + resourceDescriptor + ") triggered:", ex);
            }
          }

          @Override
          public void onStart() {
            logger.debug("Server socket bound");
          }
        };

    if (wssFactoryBuilder != null) {
      server.setWebSocketFactory(wssFactoryBuilder.build());
    }

    configure();
    server.start();
    closed = false;
  }

  void configure() {
    server.setReuseAddr(configuration.getParameter(JSONConfiguration.REUSE_ADDR_PARAMETER, true));
    server.setTcpNoDelay(
        configuration.getParameter(JSONConfiguration.TCP_NO_DELAY_PARAMETER, false));
    server.setConnectionLostTimeout(
        configuration.getParameter(JSONConfiguration.PING_INTERVAL_PARAMETER, 60));
  }

  void enableWSS(WssFactoryBuilder wssFactoryBuilder) {
    if (server != null) {
      throw new IllegalStateException("Cannot enable WSS on already running server");
    }

    this.wssFactoryBuilder = wssFactoryBuilder;
  }

  @Override
  public void close() {

    if (server == null) {
      return;
    }

    try {
      server.stop(TIMEOUT_IN_MILLIS);
      sockets.clear();
    } catch (InterruptedException e) {
      // Do second try
      try {
        server.stop();
      } catch (InterruptedException ex) {
        logger.error("Failed to close listener", ex);
      }
    } finally {
      closed = true;
      server = null;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void setAsyncRequestHandler(boolean async) {
    this.handleRequestAsync = async;
  }

  public DisconnectionInformation removeDisconnectionInformation(UUID sessionIndex) {
    return disconnectionInformations.remove(sessionIndex);
  }
}
