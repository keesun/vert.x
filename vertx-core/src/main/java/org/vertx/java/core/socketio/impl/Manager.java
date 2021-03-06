package org.vertx.java.core.socketio.impl;

import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.net.NetSocket;
import org.vertx.java.core.socketio.SocketIOSocket;
import org.vertx.java.core.socketio.impl.handlers.HandshakeHandler;
import org.vertx.java.core.socketio.impl.handlers.HttpRequestHandler;
import org.vertx.java.core.socketio.impl.handlers.StaticHandler;
import org.vertx.java.core.socketio.impl.transports.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The core component of socket.io
 *
 * @see <a href="https://github.com/LearnBoost/socket.io/blob/master/lib/manager.js">manager.js</a>
 * @author Keesun Baik
 */
public class Manager {

	private static final Logger log = LoggerFactory.getLogger(Manager.class);
	public static final String DEFAULT_NSP = "";

	private Settings settings;
	private Handler<SocketIOSocket> socketHandler;
	private AuthorizationHandler globalAuthorizationHandler;
	private Map<String, HandshakeData> handshaken;
	private Map<String, Transport> transports;
	private Map<String, List<Buffer>> closed;
	private Map<String, Boolean> open;
	private Map<String, Boolean> connected;
	private Map<String, Namespace> namespaces;
	private Map<String, Room> rooms;
	private Map<String, RoomClient> roomClients;
	private Namespace sockets;
	private VertxInternal vertx;
	private HttpServer httpServer;

	private HandshakeHandler handshakeHandler;
	private HttpRequestHandler httpRequestHandler;
	private StaticHandler staticHandler;

	public Manager(VertxInternal vertx, HttpServer httpServer) {
		this.vertx = vertx;
		this.httpServer = httpServer;
		this.handshaken = vertx.sharedData().getMap("handshaken");
		this.transports = vertx.sharedData().getMap("transports");
		this.closed = vertx.sharedData().getMap("closed");
		this.open = vertx.sharedData().getMap("open");
		this.connected = vertx.sharedData().getMap("connected");
		this.namespaces = vertx.sharedData().getMap("namespaces");
		this.rooms = vertx.sharedData().getMap("rooms");
		this.roomClients = vertx.sharedData().getMap("roomClients");
		this.sockets = this.of(DEFAULT_NSP);

		this.handshakeHandler = new HandshakeHandler(this);
		this.httpRequestHandler = new HttpRequestHandler(this);
		this.staticHandler = new StaticHandler(this);

		this.log.info("socket.io started");
	}

	public Namespace sockets() {
		return this.sockets;
	}

	public Namespace of(String nsp) {
		if (this.namespaces.containsKey(nsp)) {
			return this.namespaces.get(nsp);
		}

		this.namespaces.put(nsp, new Namespace(this, nsp));
		return this.namespaces.get(nsp);
	}

	/**
	 * Handles flash sockets
	 * @return
	 */
	public Handler<NetSocket> flashSocketHandler() {
		return new Handler<NetSocket>() {
			final String policy = "<?xml version=\"1.0\"?>" +
					"<!DOCTYPE cross-domain-policy SYSTEM \"http://www.macromedia.com/xml/dtds/cross-domain-policy.dtd\">" +
					"<cross-domain-policy>" +
					"<allow-access-from domain=\"*\" to-ports=\"*\"/>" +
					"</cross-domain-policy>";

			public void handle(final NetSocket socket) {
				socket.write(policy);

				socket.dataHandler(new Handler<Buffer>() {
					public void handle(Buffer data) {
						//TODO FlashSocket.init
						System.out.println("FlashSocket data");
						System.out.println(data.toString());

						if(data.toString().contains("policy-file-request")) {
							System.out.println("send policy");
							socket.write(policy);
							socket.close();
						}
					}
				});
			}
		};
	}

	/**
	 * Handles WebSocket requests.
	 *
	 * @return
	 */
	public Handler<ServerWebSocket> webSocketHandler() {
		return new Handler<ServerWebSocket>() {
			public void handle(ServerWebSocket socket) {
				ClientData clientData = new ClientData(socket);

				System.out.println("WebSocket Handler");
				System.out.println(clientData);

				if (clientData.getId() != null) {
					httpRequestHandler.handle(clientData);
				} else {
					handshakeHandler.handle(clientData);
				}
			}
		};
	}

	/**
	 * Handles an HTTP request.
	 *
	 * @see "Manager.prototype.handleRequest"
	 * @return
	 */
	public Handler<HttpServerRequest> requestHandler() {
		return new Handler<HttpServerRequest>() {
			public void handle(HttpServerRequest req) {
				ClientData clientData = new ClientData(settings.getNamespace(), req);

				System.out.println("path: " + req.path);
				System.out.println("url: " + req.uri);
				System.out.println(clientData);

				if (clientData.isStatic()) {
					staticHandler.handle(clientData);
					return;
				}

				if (clientData.getProtocol() != 1) {
					writeError(req, 500, "Protocol version not supported");
					if (log.isInfoEnabled())
						log.info("client protocol version unsupported");
					return;
				} else {
					if (clientData.getId() != null) {
						httpRequestHandler.handle(clientData);
					} else {
						handshakeHandler.handle(clientData);
					}
				}
			}
		};
	}

	public void writeError(HttpServerRequest req, int status, String message) {
		req.response.statusCode = status;
		req.response.end(message);
	}

	public void writeError(HttpServerRequest req, Exception e) {
		req.response.statusCode = 500;
		req.response.end("handshake error");
	}

	/**
	 * Called when a client handshakes.
	 *
	 * @see "Manager.prototype.onHandshake"
	 * @param id
	 * @param handshakeData
	 */
	public void onHandshake(String id, HandshakeData handshakeData) {
		handshaken.put(id, handshakeData);
	}

	/**
	 * Called when a client joins a nsp / room.
	 *
	 * @see "Manager.prototype.onJoin"
	 * @param sessionId
	 * @param roomName = namespace + "/" + name
	 */
	public void onJoin(String sessionId, String roomName) {
		if (this.roomClients.get(sessionId) == null) {
			this.roomClients.put(sessionId, new RoomClient());
		}

		if (this.rooms.get(roomName) == null) {
			this.rooms.put(roomName, new Room());
		}

		Room room = this.rooms.get(roomName);
		if (!room.contains(sessionId)) {
			room.push(sessionId);
			this.roomClients.get(sessionId).put(roomName, true);
		}
	}

	/**
	 * Called when a client leaves a nsp / room.
	 *
	 * @see "Manager.prototype.onLeave"
	 * @param sessionId
	 * @param roomName
	 */
	public void onLeave(String sessionId, String roomName) {
		Room room = this.rooms.get(roomName);
		if (room != null) {
			room.remove(sessionId);

			if (room.size() == 0) {
				this.rooms.remove(roomName);
			}

			RoomClient roomClient = this.roomClients.get(sessionId);
			if (roomClient != null) {
				roomClient.remove(roomName);
			}
		}
	}

	/**
	 * Called when a message is sent to a namespace and/or room.
	 *
	 * @see "Manager.prototype.onDispatch"
	 * @param room
	 * @param encodedPacket
	 * @param isVolatile
	 * @param exceptions
	 */
	public void onDispatch(String room, String encodedPacket, boolean isVolatile, JsonArray exceptions) {
		Room thisRoom = this.rooms.get(room);
		if (thisRoom != null) {
			for (String id : thisRoom.values()) {
				if (!exceptions.contains(id)) {
					Transport transport = this.transports.get(id);
					if (transport != null && transport.isOpen()) {
						transports.get(id).onDispatch(encodedPacket, isVolatile);
					} else if (!isVolatile) {
						this.onClientDispatch(id, encodedPacket);
					}

				}
			}
		}
	}

	/**
	 * Dispatches a message for a closed client.
	 *
	 * @see "Manager.prototype.onClientDispatch"
	 * @param id
	 * @param encodedPacket
	 */
	public void onClientDispatch(String id, String encodedPacket) {
		if (this.closed.get(id) != null) {
			this.closed.get(id).add(new Buffer(encodedPacket));
		}
	}

	/**
	 * Called when a client closes a request in different node.
	 *
	 * @see "Manager.prototype.onClose"
	 * @param sessionId
	 */
	public void onClose(String sessionId) {
		if (this.open.get(sessionId) != null) {
			this.open.remove(sessionId);
		}

		this.closed.put(sessionId, new ShareableList<Buffer>());

		//		this.store.subscribe('dispatch:' + id, function (packet, volatile) {
		//			if (!volatile) {
		//				self.onClientDispatch(id, packet);
		//			}
		//		});
	}

	/**
	 * Receives a message for a client.
	 *
	 * @see "Manager.prototype.onClientMessage"
	 * @param sessionId
	 * @param packet
	 */
	public void onClientMessage(String sessionId, JsonObject packet) {
		Namespace namespace = namespaces.get(packet.getString("endpoint"));
		if (namespace != null) {
			namespace.handlePacket(sessionId, packet, socketHandler);
		}
	}

	/**
	 * Fired when a client disconnects (not triggered).
	 *
	 * @see "Manager.prototype.onClientDisconnect"
	 * @param sessionId
	 * @param reason
	 * @param flag
	 */
	public void onClientDisconnect(String sessionId, String reason, boolean flag) {
		for (Map.Entry<String, Namespace> entry : namespaces.entrySet()) {
			RoomClient roomClient = this.roomClients.get(sessionId);
			boolean isInARoom = (roomClient != null) && (roomClient.isIn(entry.getKey()));
			entry.getValue().handleDisconnect(sessionId, reason, isInARoom);
		}

		this.onDisconnect(sessionId);
	}

	/**
	 * Called when a client disconnects.
	 *
	 * @see "Manager.prototype.onDisconnect"
	 * @param sessionId
	 */
	public void onDisconnect(String sessionId) {
		this.handshaken.remove(sessionId);

		Boolean isOpen = this.open.get(sessionId);
		if (isOpen != null && isOpen) {
			this.open.remove(sessionId);
		}

		Boolean isConnected = this.connected.get(sessionId);
		if (isConnected != null && isConnected) {
			this.connected.remove(sessionId);
		}

		Transport transport = this.transports.get(sessionId);
		if (transport != null) {
			transport.discard();
			this.transports.remove(sessionId);
		}

		if (this.closed.get(sessionId) != null) {
			this.closed.remove(sessionId);
		}

		RoomClient roomClient = this.roomClients.get(sessionId);
		if (roomClient != null) {
			for (String room : roomClient.rooms()) {
				if (roomClient.isIn(room)) {
					this.onLeave(sessionId, room);
				}
			}
			this.roomClients.remove(sessionId);
		}

		//		this.store.destroyClient(id, this.get('client store expiration'));
		//
		//		this.store.unsubscribe('dispatch:' + id);
		//
		//		if (local) {
		//			this.store.unsubscribe('message:' + id);
		//			this.store.unsubscribe('disconnect:' + id);
		//		}
	}

	/**
	 * Called when a client connects (ie: transport first opens)
	 *
	 * @see "Manager.prototype.onConnect"
	 * @param sessionId
	 */
	public void onConnect(String sessionId) {
		connected.put(sessionId, true);
	}

	/**
	 * Called when a client opens a request in a different node.
	 *
	 * @see "Manager.prototype.onOpen"
	 * @param sessionId
	 */
	public void onOpen(final String sessionId) {
		open.put(sessionId, true);
		if (this.closed(sessionId) != null) {
			vertx.eventBus().unregisterHandler("dispatch:" + sessionId, new Handler<Message>() {
				@Override
				public void handle(Message event) {
					Transport transport = transports.get(sessionId);
					List<Buffer> buffers = closed.get(sessionId);
					if (buffers != null && buffers.size() > 0 && transport != null) {
						if (transport.isOpen()) {
							transport.payload(buffers);
							closed.remove(sessionId);
						}
					}
				}
			});
		}

		Transport transport = this.transport(sessionId);
		if (transport != null) {
			transport.discard();
			this.transports.remove(sessionId);
		}
	}

	/**
	 * TODO supports multiple transports
	 *
	 * @param clientData
	 * @return
	 */
	public Transport newTransport(ClientData clientData) {
		String transport = clientData.getTransport();
		switch (transport) {
			case "flashsocket":
				return new FlashSocket(this, clientData);
			case "websocket":
				return new WebSocketTransport(this, clientData);
			case "htmlfile":
				return new HtmlFile(this, clientData);
			case "xhr-polling":
				return new XhrPolling(this, clientData);
			case "jsonp-polling":
				return new JsonpPolling(this, clientData);
			default:
				throw new IllegalArgumentException(transport + " is not supported.");
		}
	}

	public Transport transport(String sessionId) {
		return transports.get(sessionId);
	}

	public HandshakeData handshakeData(String sessionId) {
		return handshaken.get(sessionId);
	}

	public List<Buffer> closed(String sessionId) {
		return closed.get(sessionId);
	}

	public void removeClosed(String sessionId) {
		closed.remove(sessionId);
	}

	public void putTransport(String sessionId, Transport transport) {
		transports.put(sessionId, transport);
	}

	public Boolean connected(String sessionId) {
		return connected.get(sessionId);
	}

	public Collection<Namespace> getNamespaceValues() {
		return namespaces.values();
	}

	public Transport getTranport(String id) {
		return this.transports.get(id);
	}

	public void setGlobalAuthorizationHandler(AuthorizationHandler globalAuthorizationHandler) {
		this.globalAuthorizationHandler = globalAuthorizationHandler;
	}

	public AuthorizationHandler getGlobalAuthorizationHandler() {
		return globalAuthorizationHandler;
	}

	public Settings buildSettings(JsonObject config) {
		this.settings = new Settings(config);
		return this.settings;
	}

	public Settings getSettings() {
		return settings;
	}

	public void setSettings(Settings settings) {
		this.settings = settings;
	}

	public Handler<SocketIOSocket> getSocketHandler() {
		return socketHandler;
	}

	public void setSocketHandler(Handler<SocketIOSocket> socketHandler) {
		this.socketHandler = socketHandler;
	}

	public VertxInternal getVertx() {
		return vertx;
	}

	public Map<String, SocketIOSocket> getMap(String mapName) {
		return this.vertx.sharedData().getMap(mapName);
	}

	public Map<String, HandshakeData> getHandshaken() {
		return handshaken;
	}

	public Store getStore() {
		return this.getSettings().getStore();
	}

	public HttpServer getHttpServer() {
		return httpServer;
	}


}